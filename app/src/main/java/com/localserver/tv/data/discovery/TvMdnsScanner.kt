package com.localserver.tv.data.discovery

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections

/**
 * Pure Kotlin Multicast DNS (mDNS) scanner.
 *
 * Runs concurrently with Android's native NsdManager to provide:
 * 1. Guaranteed discovery over Android Hotspot / SoftAP connections (where NsdManager is blind).
 * 2. Resistance to Android TV NsdManager daemon crashes or resolve deadlocks.
 * 3. Support for both Ethernet (eth0) and Wi-Fi (wlan0) interfaces.
 */
class TvMdnsScanner(
    private val onServiceDiscovered: (RawMdnsService) -> Unit
) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var socket: MulticastSocket? = null
    private var listenerJob: Job? = null
    private var queryJob: Job? = null
    private val joinedInterfaces = mutableListOf<NetworkInterface>()

    @Volatile
    private var isRunning = false

    companion object {
        private const val TAG = "TvMdnsScanner"
        private const val QUERY_INTERVAL_MS = 6_000L
    }

    @Synchronized
    fun start() {
        if (isRunning) return

        try {
            // Attempt to bind to standard mDNS port 5353 with reuseAddress.
            // If already bound exclusively by OS, fall back to ephemeral port.
            val sock = try {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(TvMdnsPacket.MDNS_PORT))
                    soTimeout = 2000
                    timeToLive = 255
                }
            } catch (e: Exception) {
                Log.d(TAG, "Port 5353 in use, binding scanner to ephemeral port: ${e.message}")
                MulticastSocket(0).apply {
                    soTimeout = 2000
                    timeToLive = 255
                }
            }

            socket = sock
            isRunning = true

            joinMulticastOnInterfaces(sock)

            // Send initial query immediately
            sendQuery()

            // Coroutine: Listen for incoming mDNS packets
            listenerJob = scope.launch {
                listenForResponses(sock)
            }

            // Coroutine: Periodic query re-broadcast
            queryJob = scope.launch {
                while (isActive && isRunning) {
                    delay(QUERY_INTERVAL_MS)
                    if (isRunning) {
                        sendQuery()
                    }
                }
            }

            Log.i(TAG, "TvMdnsScanner started successfully on port ${sock.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TvMdnsScanner", e)
            isRunning = false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false

        listenerJob?.cancel()
        listenerJob = null
        queryJob?.cancel()
        queryJob = null

        socket?.let { sock ->
            val group = runCatching { InetAddress.getByName(TvMdnsPacket.MDNS_MULTICAST_IPV4) }.getOrNull()
            if (group != null) {
                val groupAddress = InetSocketAddress(group, TvMdnsPacket.MDNS_PORT)
                for (intf in joinedInterfaces) {
                    runCatching { sock.leaveGroup(groupAddress, intf) }
                }
            }
            joinedInterfaces.clear()
            runCatching { sock.close() }
        }
        socket = null
        Log.i(TAG, "TvMdnsScanner stopped")
    }

    fun triggerImmediateQuery() {
        scope.launch {
            sendQuery()
        }
    }

    private fun joinMulticastOnInterfaces(sock: MulticastSocket) {
        val group = runCatching { InetAddress.getByName(TvMdnsPacket.MDNS_MULTICAST_IPV4) }.getOrNull() ?: return
        val groupAddress = InetSocketAddress(group, TvMdnsPacket.MDNS_PORT)

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                if (!joinedInterfaces.contains(intf)) {
                    try {
                        sock.joinGroup(groupAddress, intf)
                        joinedInterfaces.add(intf)
                        Log.d(TAG, "Joined mDNS multicast on ${intf.name} (${intf.displayName})")
                    } catch (e: Exception) {
                        Log.v(TAG, "Could not join mDNS group on ${intf.name}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating network interfaces for mDNS", e)
        }
    }

    private fun sendQuery() {
        if (!isRunning) return
        val sock = socket ?: return

        try {
            val queryBytes = TvMdnsPacket.buildQueryPacket("_http._tcp.local")
            val group = InetAddress.getByName(TvMdnsPacket.MDNS_MULTICAST_IPV4)
            val packet = DatagramPacket(queryBytes, queryBytes.size, group, TvMdnsPacket.MDNS_PORT)
            sock.send(packet)
            Log.d(TAG, "Broadcasted mDNS query for _http._tcp.local")

            // Also send query to subnet broadcast addresses to pierce router multicast filters
            broadcastToSubnets(sock, queryBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Error sending mDNS query", e)
        }
    }

    private fun broadcastToSubnets(sock: MulticastSocket, bytes: ByteArray) {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.interfaceAddresses) {
                    val broadcast = addr.broadcast ?: continue
                    try {
                        val packet = DatagramPacket(bytes, bytes.size, broadcast, TvMdnsPacket.MDNS_PORT)
                        sock.send(packet)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun listenForResponses(sock: MulticastSocket) {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning && !sock.isClosed) {
            try {
                sock.receive(packet)
                if (packet.length < 12) continue

                val services = TvMdnsPacket.parseResponsePacket(buffer, packet.length)
                for (service in services) {
                    Log.i(TAG, "Discovered mDNS service via UDP: ${service.serviceName} at ${service.hostAddress}:${service.port}")
                    onServiceDiscovered(service)
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Normal timeout, continue loop
            } catch (e: Exception) {
                if (isRunning) {
                    Log.d(TAG, "Scanner receive notice: ${e.message}")
                }
                break
            }
        }
    }
}
