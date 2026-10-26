package com.localserver.tv.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
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
    private var isResolving = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    companion object {
        private const val TAG = "TvNsdDiscoveryManager"
        const val SERVICE_TYPE = "_http._tcp."
        const val SERVICE_NAME_PREFIX = "LocalMediaServer"
    }

    fun startDiscovery() {
        if (_isDiscovering.value) return
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                _isDiscovering.value = false
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
                _isDiscovering.value = false
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Discovery started for $serviceType")
                _isDiscovering.value = true
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "Discovery stopped")
                _isDiscovering.value = false
                releaseMulticastLock()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                val serviceName = serviceInfo.serviceName ?: ""
                Log.d(TAG, "Service found: $serviceName")

                // Accept any server matching prefix or resolve to test media server
                if (serviceName.contains(SERVICE_NAME_PREFIX, ignoreCase = true) ||
                    serviceName.contains("Media", ignoreCase = true)
                ) {
                    enqueueResolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                val name = serviceInfo.serviceName ?: return
                serverMap.remove(name)
                _discoveredServers.value = serverMap.values.toList()
                Log.d(TAG, "Service lost: $name")
            }
        }

        try {
            discoveryListener = listener
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering services", e)
            _isDiscovering.value = false
            releaseMulticastLock()
        }
    }

    fun stopDiscovery() {
        if (!_isDiscovering.value) return
        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        } finally {
            discoveryListener = null
            _isDiscovering.value = false
            releaseMulticastLock()
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

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo?.serviceName}: $errorCode")
                isResolving = false
                processResolveQueue()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null) {
                    val host: InetAddress? = serviceInfo.host
                    val port = serviceInfo.port
                    val hostAddress = host?.hostAddress ?: ""
                    val cleanHost = if (hostAddress.contains("%")) {
                        hostAddress.substringBefore("%")
                    } else {
                        hostAddress
                    }

                    if (cleanHost.isNotEmpty() && port > 0) {
                        val friendlyName = serviceInfo.serviceName
                            .removePrefix("$SERVICE_NAME_PREFIX-")
                            .removePrefix(SERVICE_NAME_PREFIX)
                            .trim()
                            .ifEmpty { "Media Server ($cleanHost)" }

                        val server = DiscoveredServer(
                            name = friendlyName,
                            host = cleanHost,
                            port = port,
                            isOnline = true
                        )
                        serverMap[serviceInfo.serviceName] = server
                        _discoveredServers.value = serverMap.values.toList()
                        Log.i(TAG, "Discovered server: ${server.name} at ${server.baseUrl}")
                    }
                }
                isResolving = false
                processResolveQueue()
            }
        }

        try {
            nsdManager?.resolveService(nextService, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
            isResolving = false
            processResolveQueue()
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager?.createMulticastLock("TVClientMulticastLock")?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock", e)
        }
    }
}
