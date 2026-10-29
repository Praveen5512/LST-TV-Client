package com.localserver.tv.data.discovery

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress

data class RawMdnsService(
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Encodes mDNS Query packets and decodes mDNS Response packets per RFC 6762 / RFC 1035.
 * Used for zero-configuration network discovery across Wi-Fi, Ethernet, and Android Hotspot networks.
 */
object TvMdnsPacket {

    const val MDNS_PORT = 5353
    const val MDNS_MULTICAST_IPV4 = "224.0.0.251"

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33

    const val CLASS_IN = 1

    /**
     * Builds an mDNS Query packet asking for PTR records of _http._tcp.local.
     */
    fun buildQueryPacket(queryDomain: String = "_http._tcp.local"): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        dos.writeShort(0) // Transaction ID: 0 in mDNS
        dos.writeShort(0x0000) // Flags: Standard Query
        dos.writeShort(1) // 1 Question
        dos.writeShort(0) // Answers: 0
        dos.writeShort(0) // Authority: 0
        dos.writeShort(0) // Additional: 0

        writeDomainName(out, queryDomain)
        dos.writeShort(TYPE_PTR)
        dos.writeShort(CLASS_IN) // IN class

        return out.toByteArray()
    }

    /**
     * Parses an mDNS response packet and extracts any discovered HTTP media services.
     */
    fun parseResponsePacket(buffer: ByteArray, length: Int): List<RawMdnsService> {
        if (length < 12) return emptyList()

        val flags = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (!isResponse) return emptyList()

        val qdCount = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        val anCount = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
        val nsCount = ((buffer[8].toInt() and 0xFF) shl 8) or (buffer[9].toInt() and 0xFF)
        val arCount = ((buffer[10].toInt() and 0xFF) shl 8) or (buffer[11].toInt() and 0xFF)

        val totalRecords = anCount + nsCount + arCount
        if (totalRecords <= 0) return emptyList()

        var offset = 12

        // Skip questions
        for (i in 0 until qdCount) {
            if (offset >= length) break
            val (_, nextOffset) = readDomainName(buffer, offset, length)
            offset = nextOffset + 4 // QTYPE(2) + QCLASS(2)
        }

        // Temporary storage to correlate PTR, SRV, TXT, and A records
        val ptrRecords = mutableMapOf<String, String>() // Service Type -> Full Name
        data class SrvData(val originalName: String, val hostTarget: String, val port: Int)
        val srvRecords = mutableMapOf<String, SrvData>() // Full Name Lowercase -> SrvData
        val aRecords = mutableMapOf<String, String>() // Host Target Lowercase -> IP Address
        val txtRecords = mutableMapOf<String, Map<String, String>>() // Full Name Lowercase -> Attributes

        for (i in 0 until totalRecords) {
            if (offset >= length) break
            val (name, nextOffset) = readDomainName(buffer, offset, length)
            offset = nextOffset

            if (offset + 10 > length) break
            val rType = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
            val rdLength = ((buffer[offset + 8].toInt() and 0xFF) shl 8) or (buffer[offset + 9].toInt() and 0xFF)
            offset += 10

            val rDataEnd = (offset + rdLength).coerceAtMost(length)

            when (rType) {
                TYPE_PTR -> {
                    val (targetName, _) = readDomainName(buffer, offset, rDataEnd)
                    ptrRecords[name.lowercase()] = targetName
                }
                TYPE_SRV -> {
                    if (offset + 6 <= rDataEnd) {
                        val port = ((buffer[offset + 4].toInt() and 0xFF) shl 8) or (buffer[offset + 5].toInt() and 0xFF)
                        val (hostTarget, _) = readDomainName(buffer, offset + 6, rDataEnd)
                        srvRecords[name.lowercase()] = SrvData(name, hostTarget, port)
                    }
                }
                TYPE_A -> {
                    if (offset + 4 <= rDataEnd) {
                        val ipBytes = buffer.copyOfRange(offset, offset + 4)
                        val ipStr = runCatching { InetAddress.getByAddress(ipBytes).hostAddress }.getOrNull()
                        if (ipStr != null) {
                            aRecords[name.lowercase()] = ipStr
                        }
                    }
                }
                TYPE_TXT -> {
                    val attrs = mutableMapOf<String, String>()
                    var txtCur = offset
                    while (txtCur < rDataEnd) {
                        val strLen = buffer[txtCur].toInt() and 0xFF
                        txtCur++
                        if (strLen > 0 && txtCur + strLen <= rDataEnd) {
                            val text = String(buffer, txtCur, strLen, Charsets.UTF_8)
                            val eq = text.indexOf('=')
                            if (eq != -1) {
                                attrs[text.substring(0, eq).trim()] = text.substring(eq + 1).trim()
                            } else {
                                attrs[text.trim()] = ""
                            }
                            txtCur += strLen
                        } else {
                            break
                        }
                    }
                    txtRecords[name.lowercase()] = attrs
                }
            }

            offset = rDataEnd
        }

        val results = mutableListOf<RawMdnsService>()

        // Correlate SRV and A records
        for ((srvKey, srvInfo) in srvRecords) {
            val hostTarget = srvInfo.hostTarget
            val port = srvInfo.port
            val srvName = srvInfo.originalName
            val ip = aRecords[hostTarget.lowercase()]
                ?: aRecords.values.firstOrNull() // Fallback to any A record in this packet if hostname differs
            if (ip != null && port > 0) {
                val cleanServiceName = srvName
                    .substringBefore("._http._tcp")
                    .substringBefore(".local")
                    .trim()

                val attrs = txtRecords[srvKey] ?: emptyMap()
                val displayName = attrs["name"]?.ifBlank { null } ?: cleanServiceName.ifEmpty { "LocalMediaServer" }

                results.add(
                    RawMdnsService(
                        serviceName = displayName,
                        hostAddress = ip,
                        port = port,
                        attributes = attrs
                    )
                )
            }
        }

        return results
    }

    /**
     * Writes a domain name as RFC 1035 length-prefixed labels ending with a zero byte.
     */
    fun writeDomainName(out: ByteArrayOutputStream, name: String) {
        val clean = name.trim().trimEnd('.')
        if (clean.isEmpty()) {
            out.write(0)
            return
        }
        val parts = clean.split('.')
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            val len = bytes.size.coerceAtMost(63)
            out.write(len)
            out.write(bytes, 0, len)
        }
        out.write(0)
    }

    /**
     * Reads a domain name with support for DNS compression pointers (0xC0).
     */
    fun readDomainName(buffer: ByteArray, startOffset: Int, maxLen: Int = buffer.size): Pair<String, Int> {
        var cur = startOffset
        val labels = mutableListOf<String>()
        var jumped = false
        var returnOffset = -1
        var steps = 0

        while (cur < maxLen && steps < 100) {
            steps++
            val len = buffer[cur].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) returnOffset = cur + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (cur + 1 >= maxLen) break
                val ptr = ((len and 0x3F) shl 8) or (buffer[cur + 1].toInt() and 0xFF)
                if (!jumped) {
                    returnOffset = cur + 2
                    jumped = true
                }
                cur = ptr
            } else {
                cur++
                if (cur + len <= maxLen) {
                    labels.add(String(buffer, cur, len, Charsets.UTF_8))
                    cur += len
                } else {
                    break
                }
            }
        }

        val finalOffset = if (returnOffset != -1) returnOffset else cur
        return Pair(labels.joinToString("."), finalOffset)
    }
}
