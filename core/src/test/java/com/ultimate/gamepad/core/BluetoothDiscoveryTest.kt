package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDiscoveryTest {

    private fun dev(name: String?, address: String, bonded: Boolean, rssi: Int? = null) =
        DiscoveredDevice(name = name, address = address, bonded = bonded, rssi = rssi)

    @Test fun `merge no duplica por MAC y manda el emparejado`() {
        val bonded = listOf(dev("TV Salón", "AA:BB:CC:DD:EE:01", bonded = true))
        val found = listOf(
            dev("TV Salón", "aa:bb:cc:dd:ee:01", bonded = false, rssi = -55),
            dev("Mando vecino", "AA:BB:CC:DD:EE:02", bonded = false, rssi = -70),
        )
        val merged = BluetoothDiscovery.merge(bonded, found)
        assertEquals(2, merged.size)
        val tv = merged.first { it.address == "AA:BB:CC:DD:EE:01" }
        assertTrue(tv.bonded)
        assertEquals("TV Salón", tv.name)
        assertEquals(-55, tv.rssi) // el RSSI del encontrado se conserva
        assertFalse(merged.first { it.address == "AA:BB:CC:DD:EE:02" }.bonded)
    }

    @Test fun `merge con listas vacias da lista vacia`() {
        assertTrue(BluetoothDiscovery.merge(emptyList(), emptyList()).isEmpty())
        assertEquals(1, BluetoothDiscovery.merge(listOf(dev("TV", "AA:00", true)), emptyList()).size)
    }

    @Test fun `sortForDisplay emparejados primero y luego por senal`() {
        val devices = listOf(
            dev("Lejos", "AA:03", bonded = false, rssi = -80),
            dev("TV", "AA:01", bonded = true),
            dev("Cerca", "AA:02", bonded = false, rssi = -40),
            dev(null, "AA:04", bonded = false, rssi = -30),
        )
        val sorted = BluetoothDiscovery.sortForDisplay(devices)
        assertEquals("AA:01", sorted[0].address) // emparejado primero
        // Entre no emparejados manda la señal: -30 antes que -40 y -80.
        assertEquals("AA:04", sorted[1].address)
        assertEquals("AA:02", sorted[2].address)
        assertEquals("AA:03", sorted[3].address)
    }

    @Test fun `sortForDisplay sin nombre al final de su grupo`() {
        val devices = listOf(
            dev(null, "AA:02", bonded = false, rssi = -50),
            dev("Con nombre", "AA:01", bonded = false, rssi = -50),
        )
        val sorted = BluetoothDiscovery.sortForDisplay(devices)
        assertEquals("AA:01", sorted[0].address)
        assertEquals("AA:02", sorted[1].address)
    }

    @Test fun `looksLikeTv marca nombres de TV y no otros`() {
        assertTrue(BluetoothDiscovery.looksLikeTv(dev("TV Salón", "AA:01", false)))
        assertTrue(BluetoothDiscovery.looksLikeTv(dev("BRAVIA 4K", "AA:02", false)))
        assertTrue(BluetoothDiscovery.looksLikeTv(dev("LG webOS TV", "AA:03", false)))
        assertTrue(BluetoothDiscovery.looksLikeTv(dev("Android TV", "AA:04", false)))
        assertFalse(BluetoothDiscovery.looksLikeTv(dev("Mando Xbox", "AA:05", false)))
        assertFalse(BluetoothDiscovery.looksLikeTv(dev(null, "AA:06", false)))
        assertFalse(BluetoothDiscovery.looksLikeTv(dev("Auriculares", "AA:07", false)))
    }
}
