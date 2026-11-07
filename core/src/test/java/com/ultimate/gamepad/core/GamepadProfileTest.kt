package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals(0, bytes[29])
        assertEquals(0, bytes[30])
        assertEquals(0, bytes[31])
    }

    @Test fun `el descriptor HID es por perfil y mantiene 7 bytes de reporte`() {
        val xbox = HidGamepadDescriptor.descriptorFor(GamepadProfiles.XBOX_360_ID)
        val ds4 = HidGamepadDescriptor.descriptorFor(GamepadProfiles.DS4_ID)
        val dualsense = HidGamepadDescriptor.descriptorFor(GamepadProfiles.DUALSENSE_ID)
        val generic = HidGamepadDescriptor.descriptorFor(GamepadProfiles.GENERIC_ID)
        assertEquals(HidGamepadDescriptor.BYTES.size, xbox.size)
        assertEquals(HidGamepadDescriptor.BYTES.size, ds4.size)
        assertEquals(HidGamepadDescriptor.BYTES.size, dualsense.size)
        assertEquals(HidGamepadDescriptor.BYTES.size, generic.size)
        // Xbox y genérico: el descriptor de 12 botones de siempre.
        assertTrue(xbox.contentEquals(HidGamepadDescriptor.BYTES))
        assertTrue(generic.contentEquals(HidGamepadDescriptor.BYTES))
        // DS4/DualSense: 14 botones (Usage Maximum y Report Count 14,
        // relleno 2 en vez de 4); el resto del descriptor es idéntico.
        assertNotEquals(xbox[15], ds4[15])
        assertEquals(0x0E.toByte(), ds4[15])
        assertEquals(0x0E.toByte(), ds4[23])
        assertEquals(0x02.toByte(), ds4[29])
        assertTrue(ds4.contentEquals(dualsense))
    }

    @Test fun `DS4 y DualSense declaran touchpad en hardware pero no implementado`() {
        assertTrue(GamepadProfiles.DS4.hasTouchpad)
        assertTrue(GamepadProfiles.DUALSENSE.hasTouchpad)
        assertEquals(false, GamepadProfiles.DS4.touchpadImplemented)
        assertEquals(false, GamepadProfiles.DUALSENSE.touchpadImplemented)
        assertEquals(false, GamepadProfiles.XBOX_360.hasTouchpad)
    }
}
