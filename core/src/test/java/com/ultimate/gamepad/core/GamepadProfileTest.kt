package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadProfileTest {
    @Test fun `hay cinco perfiles con id y nombre de dispositivo distintos`() {
        assertEquals(5, GamepadProfiles.ALL.size)
        assertEquals(5, GamepadProfiles.ALL.map { it.id }.distinct().size)
        assertEquals(5, GamepadProfiles.ALL.map { it.deviceName }.distinct().size)
        assertEquals("Xbox 360 Controller", GamepadProfiles.XBOX_360.deviceName)
        assertEquals("Xbox One Controller", GamepadProfiles.XBOX_ONE.deviceName)
        assertEquals("Wireless Controller", GamepadProfiles.DS4.deviceName)
        assertEquals("DualSense Wireless Controller", GamepadProfiles.DUALSENSE.deviceName)
        assertEquals("Generic Gamepad", GamepadProfiles.GENERIC.deviceName)
    }

    @Test fun `vendor y product por perfil`() {
        assertEquals(0x045E, GamepadProfiles.XBOX_360.vendorId)
        assertEquals(0x028E, GamepadProfiles.XBOX_360.productId)
        assertEquals(0x02DD, GamepadProfiles.XBOX_ONE.productId)
        assertEquals(0x054C, GamepadProfiles.DS4.vendorId)
        assertEquals(0x05C4, GamepadProfiles.DS4.productId)
        assertEquals(0x0CE6, GamepadProfiles.DUALSENSE.productId)
    }

    @Test fun `un id desconocido se sanea a Xbox 360`() {
        assertEquals(GamepadProfiles.XBOX_360, GamepadProfiles.fromId(99))
        assertEquals(GamepadProfiles.XBOX_360, GamepadProfiles.fromId(-1))
        assertEquals(GamepadProfiles.DS4, GamepadProfiles.fromId(2))
    }

    @Test fun `el profileId viaja en el byte 28 y el estado no cambia`() {
        val state = GamepadState(buttons = GamepadButtons.A, leftX = 0.5f, sequence = 7)
        val bytes = GamepadProtocol.encode(state, GamepadProfiles.DS4_ID)
        assertEquals(32, bytes.size)
        assertEquals(GamepadProfiles.DS4_ID, GamepadProtocol.profileIdOf(bytes))
        assertEquals(28, GamepadProtocol.PROFILE_OFFSET)
        val decoded = GamepadProtocol.decode(bytes)
        assertNotNull(decoded)
        assertEquals(state.buttons, decoded!!.buttons)
        assertEquals(0.5f, decoded.leftX, 0.01f)
        assertEquals(7, decoded.sequence)
    }

    @Test fun `sin profileId el paquete es Xbox 360 como antes`() {
        val bytes = GamepadProtocol.encode(GamepadState())
        assertEquals(GamepadProfiles.XBOX_360_ID, GamepadProtocol.profileIdOf(bytes))
        // OJO: comparar como Byte, no como Int: assertEquals(0, bytes[29])
        // compara Integer(0) con Byte(0) por Object.equals y FALLA
        // aunque los dos valgan 0.
        assertEquals(0.toByte(), bytes[29])
        assertEquals(0.toByte(), bytes[30])
        assertEquals(0.toByte(), bytes[31])
    }

    @Test fun `el descriptor HID es UNICO para todos los perfiles y declara 16 botones + gatillos analogicos (9 bytes de datos + Report ID 1)`() {
        val xbox = HidGamepadDescriptor.descriptorFor(GamepadProfiles.XBOX_360_ID)
        val ds4 = HidGamepadDescriptor.descriptorFor(GamepadProfiles.DS4_ID)
        val dualsense = HidGamepadDescriptor.descriptorFor(GamepadProfiles.DUALSENSE_ID)
        val generic = HidGamepadDescriptor.descriptorFor(GamepadProfiles.GENERIC_ID)
        // Un solo descriptor: el mismo para todos los perfiles (el
        // perfil solo cambia el nombre expuesto). La variante Sony
        // de 14 botones desaparece: con otro orden de botones saldría
        // revuelta en la TV, que no aplica ningún .kl de Sony.
        assertTrue(xbox.contentEquals(HidGamepadDescriptor.BYTES))
        assertTrue(ds4.contentEquals(HidGamepadDescriptor.BYTES))
        assertTrue(dualsense.contentEquals(HidGamepadDescriptor.BYTES))
        assertTrue(generic.contentEquals(HidGamepadDescriptor.BYTES))
        assertEquals(486, HidGamepadDescriptor.BYTES.size)
        assertEquals(HidInputReport.SIZE_BYTES, HidGamepadDescriptor.INPUT_REPORT_SIZE)
        // 16 botones (Usage Maximum y Report Count 16), sin relleno.
        assertEquals(0x10.toByte(), xbox[17])
        assertEquals(0x10.toByte(), xbox[25])
        // Rumble (ronda REA GFN+OpenNOW): Report ID (1) en la entrada
        // del mando y colección PID (Usage Page 0x0F) con reportes 2-7.
        assertEquals(0x85.toByte(), xbox[6])
        assertEquals(0x01.toByte(), xbox[7])
        assertTrue(contains(xbox, byteArrayOf(0x05, 0x0F)))
        for (id in 2..7) {
            assertTrue(contains(xbox, byteArrayOf(0x85.toByte(), id.toByte())))
        }
        // Gatillos analógicos (ronda Bluetooth dos vías): Usage Page
        // Simulation (0x02) con Brake (0xC5) = LT y Accelerator
        // (0xC4) = RT, rango lógico 0..255.
        assertTrue(contains(xbox, byteArrayOf(0x05, 0x02, 0x09, 0xC5.toByte(), 0x09, 0xC4.toByte())))
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test fun `DS4 y DualSense declaran touchpad en hardware pero no implementado`() {
        assertTrue(GamepadProfiles.DS4.hasTouchpad)
        assertTrue(GamepadProfiles.DUALSENSE.hasTouchpad)
        assertEquals(false, GamepadProfiles.DS4.touchpadImplemented)
        assertEquals(false, GamepadProfiles.DUALSENSE.touchpadImplemented)
        assertEquals(false, GamepadProfiles.XBOX_360.hasTouchpad)
    }
}
