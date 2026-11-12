package com.ultimate.gamepad.core

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    @Test fun `broadcast dirigido de una subred 24`() {
        val bcast = Discovery.broadcastFor(InetAddress.getByName("192.168.1.20"), 24)
        assertEquals("192.168.1.255", bcast?.hostAddress)
    }

    @Test fun `broadcast dirigido de una subred 25`() {
        val bcast = Discovery.broadcastFor(InetAddress.getByName("192.168.1.20"), 25)
        assertEquals("192.168.1.127", bcast?.hostAddress)
    }

    @Test fun `broadcast dirigido de una red clase A`() {
        val bcast = Discovery.broadcastFor(InetAddress.getByName("10.0.5.6"), 8)
        assertEquals("10.255.255.255", bcast?.hostAddress)
    }

    @Test fun `broadcast dirigido del hotspot 192_168_43`() {
        val bcast = Discovery.broadcastFor(InetAddress.getByName("192.168.43.1"), 24)
        assertEquals("192.168.43.255", bcast?.hostAddress)
    }

    @Test fun `prefijo invalido da null`() {
        assertNull(Discovery.broadcastFor(InetAddress.getByName("192.168.1.20"), 33))
        assertNull(Discovery.broadcastFor(InetAddress.getByName("192.168.1.20"), -1))
    }

    @Test fun `hotspot es 192_168_43_x y la wifi normal no`() {
        assertTrue(Discovery.isHotspotAddress(InetAddress.getByName("192.168.43.1")))
        assertTrue(Discovery.isHotspotAddress(InetAddress.getByName("192.168.43.57")))
        assertFalse(Discovery.isHotspotAddress(InetAddress.getByName("192.168.1.20")))
        assertFalse(Discovery.isHotspotAddress(InetAddress.getByName("10.0.0.1")))
    }

    @Test fun `privada RFC1918 si, publica y CGNAT no`() {
        assertTrue(Discovery.isPrivateIpv4(InetAddress.getByName("10.0.0.1")))
        assertTrue(Discovery.isPrivateIpv4(InetAddress.getByName("172.16.0.9")))
        assertTrue(Discovery.isPrivateIpv4(InetAddress.getByName("192.168.1.20")))
        assertFalse(Discovery.isPrivateIpv4(InetAddress.getByName("172.32.0.1")))
        assertFalse(Discovery.isPrivateIpv4(InetAddress.getByName("100.64.0.1")))
        assertFalse(Discovery.isPrivateIpv4(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun `broadcasts locales no incluyen loopback ni revientan sin red`() {
        val list = Discovery.localBroadcastAddresses()
        assertNotNull(list)
        for (addr in list) {
            assertFalse(addr.isLoopbackAddress)
            assertTrue(Discovery.isPrivateIpv4(addr) || addr.hostAddress?.endsWith(".255") == true)
        }
        // hasHotspotInterface no lanza aunque no haya interfaces.
        Discovery.hasHotspotInterface()
    }

    @Test fun `respuesta de descubrimiento se parsea`() {
        val tv = Discovery.parseResponse("UGRSP1|TV Ultimate Gamepad|192.168.1.20|51999|4821".toByteArray(Charsets.UTF_8))
        assertNotNull(tv)
        assertEquals("192.168.1.20", tv!!.ip)
        assertEquals(51999, tv.port)
        assertEquals("4821", tv.pin)
        assertNull(Discovery.parseResponse("BASURA".toByteArray(Charsets.UTF_8)))
    }

    @Test fun `respuesta de descubrimiento rechaza puerto e IP invalidos`() {
        fun resp(ip: String, port: String) =
            "UGRSP1|TV|$ip|$port|4821".toByteArray(Charsets.UTF_8)
        assertNull(Discovery.parseResponse(resp("192.168.1.20", "0")))
        assertNull(Discovery.parseResponse(resp("192.168.1.20", "65536")))
        assertNull(Discovery.parseResponse(resp("192.168.1.20", "99999")))
        assertNull(Discovery.parseResponse(resp("192.168.1.20", "-1")))
        assertNull(Discovery.parseResponse(resp("no-es-ip", "51999")))
        assertNull(Discovery.parseResponse(resp("192.168.1.999", "51999")))
        assertNull(Discovery.parseResponse(resp("192.168.1", "51999")))
        assertNull(Discovery.parseResponse(resp("192.168.1.20.5", "51999")))
        // Con espacios se recorta y vale (ronda 2 P2-9).
        val tv = Discovery.parseResponse("UGRSP1| TV | 192.168.1.20 | 51999 | 4821 ".toByteArray(Charsets.UTF_8))
        assertNotNull(tv)
        assertEquals("192.168.1.20", tv!!.ip)
        assertEquals("TV", tv.name)
    }
}
