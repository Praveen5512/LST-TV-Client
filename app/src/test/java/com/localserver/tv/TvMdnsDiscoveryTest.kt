package com.localserver.tv

import com.localserver.tv.data.discovery.TvMdnsPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress

class TvMdnsDiscoveryTest {

    @Test
    fun testBuildQueryPacket() {
        val queryBytes = TvMdnsPacket.buildQueryPacket("_http._tcp.local")
        assertTrue(queryBytes.size > 12)
        // Question count should be 1
        assertEquals(0, queryBytes[4].toInt())
        assertEquals(1, queryBytes[5].toInt())
    }

    @Test
    fun testParseMdnsResponsePacket() {
        // Build a simulated mDNS authoritative response packet (RFC 6762 / 1035)
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        val serviceName = "MyPhoneMedia"
        val fullServiceName = "$serviceName._http._tcp.local"
        val hostName = "$serviceName.local"
        val targetIp = "192.168.43.1"
        val targetPort = 8080

        // Header: ID=0, Flags=0x8400 (Standard response, AA=1), QD=0, AN=3, NS=0, AR=0
        dos.writeShort(0)
        dos.writeShort(0x8400)
        dos.writeShort(0)
        dos.writeShort(3) // 3 Answers: PTR + SRV + A
        dos.writeShort(0)
        dos.writeShort(0)

        // 1. PTR Record: _http._tcp.local -> MyPhoneMedia._http._tcp.local
        TvMdnsPacket.writeDomainName(out, "_http._tcp.local")
        dos.writeShort(TvMdnsPacket.TYPE_PTR)
        dos.writeShort(0x8001) // Class IN + Cache flush
        dos.writeInt(120) // TTL
        val ptrData = ByteArrayOutputStream().apply { TvMdnsPacket.writeDomainName(this, fullServiceName) }.toByteArray()
        dos.writeShort(ptrData.size)
        dos.write(ptrData)

        // 2. SRV Record: MyPhoneMedia._http._tcp.local -> port 8080, target MyPhoneMedia.local
        TvMdnsPacket.writeDomainName(out, fullServiceName)
        dos.writeShort(TvMdnsPacket.TYPE_SRV)
        dos.writeShort(0x8001)
        dos.writeInt(120)
        val srvData = ByteArrayOutputStream().apply {
            val sDos = DataOutputStream(this)
            sDos.writeShort(0) // Priority
            sDos.writeShort(0) // Weight
            sDos.writeShort(targetPort) // Port
            TvMdnsPacket.writeDomainName(this, hostName)
        }.toByteArray()
        dos.writeShort(srvData.size)
        dos.write(srvData)

        // 3. A Record: MyPhoneMedia.local -> 192.168.43.1
        TvMdnsPacket.writeDomainName(out, hostName)
        dos.writeShort(TvMdnsPacket.TYPE_A)
        dos.writeShort(0x8001)
        dos.writeInt(120)
        val ipBytes = InetAddress.getByName(targetIp).address
        dos.writeShort(ipBytes.size)
        dos.write(ipBytes)

        val packetBytes = out.toByteArray()

        // Parse using TvMdnsPacket
        val discovered = TvMdnsPacket.parseResponsePacket(packetBytes, packetBytes.size)

        assertEquals(1, discovered.size)
        val s = discovered.first()
        assertEquals("MyPhoneMedia", s.serviceName)
        assertEquals("192.168.43.1", s.hostAddress)
        assertEquals(8080, s.port)
    }
}
