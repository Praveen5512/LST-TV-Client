package com.localserver.tv.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val isOnline: Boolean = true
) {
    val baseUrl: String
        get() = "http://$host:$port"
}

/**
 * Multi-Engine Network Discovery Manager for Android TV.
 *
 * Combines three complementary discovery engines:
 * 1. Android Native NsdManager: Hardened mDNS service discovery for standard Wi-Fi / Ethernet networks.
 * 2. Pure Kotlin UDP mDNS Scanner: Direct UDP 5353 multicast scanner for Hotspot / SoftAP interfaces.
 * 3. Hotspot Gateway & Subnet Probe: Direct HTTP handshake on default gateway & local /24 subnet.
 */
class TvNsdDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private val serverMap = ConcurrentHashMap<String, DiscoveredServer>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()

    @Volatile
    private var isResolving = false
    private var resolveWatchdogJob: Job? = null
    private var probeJob: Job? = null

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var activeServiceTypeUsed: String = SERVICE_TYPE

    // Engine 2: Pure UDP mDNS Scanner
    private val mdnsScanner = TvMdnsScanner { rawService ->
        handleDiscoveredEndpoint(
            sourceTag = "mDNS-UDP",
            suggestedName = rawService.serviceName,
            host = rawService.hostAddress,
            port = rawService.port
        )
    }

    companion object {
        private const val TAG = "TvNsdDiscoveryManager"
        const val SERVICE_TYPE = "_http._tcp"
        const val SERVICE_TYPE_WITH_DOT = "_http._tcp."
        const val SERVICE_NAME_PREFIX = "LocalMediaServer"
        const val DEFAULT_SERVER_PORT = 8080
        private const val RESOLVE_TIMEOUT_MS = 3500L
    }

    fun startDiscovery() {
        if (_isDiscovering.value) {
            Log.d(TAG, "Discovery already active, triggering rescan")
            mdnsScanner.triggerImmediateQuery()
            probeDefaultGatewayAndSubnets()
            return
        }

        _isDiscovering.value = true
        acquireMulticastLock()

        // 1. Start Engine 2: Pure UDP mDNS Scanner
        try {
            mdnsScanner.start()
        } catch (e: Exception) {
            Log.w(TAG, "Could not start pure UDP mDNS scanner: ${e.message}")
        }

        // 2. Start Engine 3: Gateway & Subnet Prober
        probeDefaultGatewayAndSubnets()

        // 3. Start Engine 1: Android Native NsdManager
        startNativeNsd(SERVICE_TYPE)
    }

    private fun startNativeNsd(serviceType: String) {
        if (nsdManager == null) {
            Log.w(TAG, "NsdManager not available on this Android TV device; relying on UDP mDNS & Gateway Prober")
            return
        }

        activeServiceTypeUsed = serviceType
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(regType: String?, errorCode: Int) {
                Log.e(TAG, "Native NSD Start discovery failed for $regType with error $errorCode")
                // If failed with standard type, try fallback with trailing dot once
                if (regType == SERVICE_TYPE) {
                    Log.i(TAG, "Retrying Native NSD discovery with trailing dot: $SERVICE_TYPE_WITH_DOT")
                    scope.launch {
                        delay(200)
                        if (_isDiscovering.value) {
                            startNativeNsd(SERVICE_TYPE_WITH_DOT)
                        }
                    }
                }
            }

            override fun onStopDiscoveryFailed(regType: String?, errorCode: Int) {
                Log.w(TAG, "Native NSD Stop discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(regType: String?) {
                Log.i(TAG, "Native NSD Discovery successfully started for $regType")
            }

            override fun onDiscoveryStopped(regType: String?) {
                Log.d(TAG, "Native NSD Discovery stopped for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                val serviceName = serviceInfo.serviceName ?: ""
                Log.d(TAG, "Native NSD Service found: $serviceName (${serviceInfo.serviceType})")

                // Resolve any HTTP service found or services matching our prefix / media keywords
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                val name = serviceInfo.serviceName ?: return
                Log.d(TAG, "Native NSD Service lost notification: $name")
                // Note: We avoid eagerly removing servers here in case mDNS packet was temporarily dropped
            }
        }

        try {
            discoveryListener = listener
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting Native NSD discovery", e)
        }
    }

    fun stopDiscovery() {
        if (!_isDiscovering.value) return
        _isDiscovering.value = false

        // Stop Engine 2
        try {
            mdnsScanner.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping mDNS scanner", e)
        }

        // Stop Engine 3
        probeJob?.cancel()
        probeJob = null

        // Stop Engine 1
        val listener = discoveryListener
        discoveryListener = null
        if (nsdManager != null && listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping native NSD service discovery: ${e.message}")
            }
        }

        resolveWatchdogJob?.cancel()
        resolveWatchdogJob = null
        resolveQueue.clear()
        isResolving = false

        releaseMulticastLock()
    }

    fun rescan() {
        scope.launch {
            stopDiscovery()
            serverMap.clear()
            _discoveredServers.value = emptyList()
            delay(300)
            startDiscovery()
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.add(serviceInfo)
        processResolveQueue()
    }

    @Synchronized
    private fun processResolveQueue() {
        if (isResolving) return
        val nextService = resolveQueue.poll() ?: return
        isResolving = true

        // Watchdog: If native NSD hangs without firing callbacks, reset isResolving after timeout
        resolveWatchdogJob?.cancel()
        resolveWatchdogJob = scope.launch(Dispatchers.IO) {
            delay(RESOLVE_TIMEOUT_MS)
            if (isResolving) {
                Log.w(TAG, "Native NSD Resolve watchdog expired for ${nextService.serviceName}; continuing queue")
                isResolving = false
                processResolveQueue()
            }
        }

        // For Android 14+ (API 34), check registerServiceInfoCallback if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && nsdManager != null) {
            try {
                nsdManager.registerServiceInfoCallback(
                    nextService,
                    context.mainExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            finishResolve()
                        }

                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            handleResolvedServiceInfo(serviceInfo)
                            finishResolve()
                            runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                        }

                        override fun onServiceLost() {}

                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
                return
            } catch (e: Exception) {
                Log.d(TAG, "registerServiceInfoCallback not supported, falling back to resolveService: ${e.message}")
            }
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "Native NSD Resolve failed for ${serviceInfo?.serviceName}: error $errorCode")
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    // Re-enqueue the service with backoff
                    resolveQueue.add(nextService)
                    scope.launch {
                        delay(250)
                        finishResolve()
                    }
                } else {
                    finishResolve()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null) {
                    handleResolvedServiceInfo(serviceInfo)
                }
                finishResolve()
            }
        }

        try {
            @Suppress("DEPRECATION")
            nsdManager?.resolveService(nextService, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error calling native resolveService", e)
            finishResolve()
        }
    }

    private fun finishResolve() {
        resolveWatchdogJob?.cancel()
        resolveWatchdogJob = null
        isResolving = false
        processResolveQueue()
    }

    private fun handleResolvedServiceInfo(serviceInfo: NsdServiceInfo) {
        val host: InetAddress? = serviceInfo.host
        val port = serviceInfo.port
        val hostAddress = host?.hostAddress ?: ""
        val cleanHost = if (hostAddress.contains("%")) hostAddress.substringBefore("%") else hostAddress

        val formattedHost = if (cleanHost.contains(":") && !cleanHost.startsWith("[")) {
            "[$cleanHost]"
        } else {
            cleanHost
        }

        if (formattedHost.isNotEmpty() && port > 0) {
            val friendlyName = serviceInfo.serviceName
                .removePrefix("$SERVICE_NAME_PREFIX-")
                .removePrefix(SERVICE_NAME_PREFIX)
                .trim()
                .ifEmpty { "Media Server ($formattedHost)" }

            handleDiscoveredEndpoint(
                sourceTag = "Native-NSD",
                suggestedName = friendlyName,
                host = formattedHost,
                port = port
            )
        }
    }

    /**
     * Unified endpoint handler for all 3 discovery engines.
     * Verifies server reachability and extracts actual server name from /api/status.
     */
    private fun handleDiscoveredEndpoint(
        sourceTag: String,
        suggestedName: String,
        host: String,
        port: Int
    ) {
        val cleanHost = if (host.contains("%")) host.substringBefore("%") else host
        val formattedHost = if (cleanHost.contains(":") && !cleanHost.startsWith("[")) "[$cleanHost]" else cleanHost
        val serverKey = "$formattedHost:$port"

        scope.launch(Dispatchers.IO) {
            val verified = verifyMediaServer(formattedHost, port)
            if (verified != null) {
                val finalName = verified.name.ifBlank { suggestedName }
                val server = DiscoveredServer(
                    name = finalName,
                    host = formattedHost,
                    port = port,
                    isOnline = true
                )
                serverMap[serverKey] = server
                _discoveredServers.value = serverMap.values.toList()
                Log.i(TAG, "[$sourceTag] Verified and added Media Server: ${server.name} at ${server.baseUrl}")
            } else {
                Log.d(TAG, "[$sourceTag] Endpoint $formattedHost:$port did not respond to Media Server protocol check")
            }
        }
    }

    private data class VerifiedDetails(val name: String)

    /**
     * Probes an HTTP host/port to verify if it is our Local Media Server.
     */
    private fun verifyMediaServer(host: String, port: Int): VerifiedDetails? {
        // Try /api/status first
        val statusUrl = "http://$host:$port/api/status"
        try {
            val url = URL(statusUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1800
                readTimeout = 1800
                requestMethod = "GET"
            }
            conn.connect()
            if (conn.responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return try {
                    val json = JSONObject(responseText)
                    val status = json.optString("status", "")
                    if (status.contains("running", ignoreCase = true) || json.has("sharesCount") || json.has("addresses")) {
                        val customName = json.optString("serverName", "").ifBlank {
                            json.optString("displayName", "")
                        }
                        VerifiedDetails(name = customName.ifBlank { "Local Media Server" })
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    VerifiedDetails(name = "Local Media Server")
                }
            }
            conn.disconnect()
        } catch (_: Exception) {}

        // Fallback: check /api/shares directly
        val sharesUrl = "http://$host:$port/api/shares"
        try {
            val url = URL(sharesUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
            }
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                return VerifiedDetails(name = "Local Media Server ($host)")
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Engine 3: Gateway & Subnet Prober.
     * Guarantees discovery over Hotspot (SoftAP) and networks where mDNS multicast is blocked.
     */
    private fun probeDefaultGatewayAndSubnets() {
        probeJob?.cancel()
        probeJob = scope.launch(Dispatchers.IO) {
            val candidateIps = mutableSetOf<String>()

            // 1. Check ConnectivityManager active link properties for default gateway
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
                try {
                    val linkProps = cm.getLinkProperties(cm.activeNetwork)
                    for (route in linkProps?.routes.orEmpty()) {
                        val gateway = route.gateway
                        if (gateway is Inet4Address && !gateway.isLoopbackAddress && !gateway.isAnyLocalAddress) {
                            candidateIps.add(gateway.hostAddress ?: "")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error reading LinkProperties gateway: ${e.message}")
                }
            }

            // 2. Check WifiManager DHCP gateway
            try {
                wifiManager?.dhcpInfo?.let { dhcp ->
                    if (dhcp.gateway != 0) {
                        @Suppress("DEPRECATION")
                        val gwIp = Formatter.formatIpAddress(dhcp.gateway)
                        if (!gwIp.isNullOrBlank() && gwIp != "0.0.0.0") {
                            candidateIps.add(gwIp)
                        }
                    }
                }
            } catch (_: Exception) {}

            // 3. Known standard Android Hotspot gateway IPs
            candidateIps.add("192.168.43.1") // Standard Android Mobile Hotspot default
            candidateIps.add("192.168.49.1") // Wi-Fi Direct (P2P) default

            // Probe all candidate gateways concurrently
            for (ip in candidateIps.filter { it.isNotEmpty() }) {
                launch {
                    handleDiscoveredEndpoint(
                        sourceTag = "Gateway-Probe",
                        suggestedName = "Media Server (Hotspot)",
                        host = ip,
                        port = DEFAULT_SERVER_PORT
                    )
                }
            }

            // If no server discovered after 1.8 seconds, launch fast parallel probe of local /24 subnet
            delay(1800)
            if (isActive && _discoveredServers.value.isEmpty()) {
                probeLocalSubnet()
            }
        }
    }

    private suspend fun probeLocalSubnet() {
        val localIpv4 = getLocalIpv4Address() ?: return
        val lastDot = localIpv4.lastIndexOf('.')
        if (lastDot == -1) return
        val prefix = localIpv4.substring(0, lastDot + 1) // e.g. "192.168.1."

        Log.d(TAG, "Probing local subnet $prefix* for port $DEFAULT_SERVER_PORT")

        // Probe active /24 subnet IPs in small batches with low latency
        for (batchStart in 1..254 step 32) {
            if (!scope.isActive || _discoveredServers.value.isNotEmpty()) break
            val batchEnd = (batchStart + 31).coerceAtMost(254)
            for (hostNum in batchStart..batchEnd) {
                val targetIp = "$prefix$hostNum"
                if (targetIp == localIpv4) continue

                scope.launch(Dispatchers.IO) {
                    val verified = verifyMediaServer(targetIp, DEFAULT_SERVER_PORT)
                    if (verified != null) {
                        handleDiscoveredEndpoint(
                            sourceTag = "Subnet-Probe",
                            suggestedName = verified.name,
                            host = targetIp,
                            port = DEFAULT_SERVER_PORT
                        )
                    }
                }
            }
            delay(120) // Brief pacing between concurrent socket batches
        }
    }

    private fun getLocalIpv4Address(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            try {
                multicastLock = wifiManager?.createMulticastLock("TVClientMulticastLock")?.apply {
                    setReferenceCounted(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not create MulticastLock: ${e.message}")
            }
        }
        try {
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        }
    }
}
