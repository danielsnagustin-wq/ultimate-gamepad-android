package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class GamepadProtocolTest {
    @Test fun `encode-decode conserva el estado`() {
        val state = GamepadState(
            buttons = GamepadButtons.A or GamepadButtons.DPAD_UP,
            leftX = 0.5f, leftY = -0.25f, rightX = -1f, rightY = 1f,
            leftTrigger = 0.8f, rightTrigger = 0f,
            sequence = 42, timestampMs = 1_700_000_000_000L,
            extraButtons = GamepadExtraButtons.L4,
        )
        val decoded = GamepadProtocol.decode(GamepadProtocol.encode(state))
        assertNotNull(decoded)
        decoded!!
        assertEquals(state.buttons, decoded.buttons)
        assertEquals(state.sequence, decoded.sequence)
        assertEquals(state.timestampMs, decoded.timestampMs)
        assertEquals(state.extraButtons, decoded.extraButtons)
        assertEquals(0.5f, decoded.leftX, 0.01f)
        assertEquals(-0.25f, decoded.leftY, 0.01f)
        assertEquals(-1f, decoded.rightX, 0.01f)
        assertEquals(1f, decoded.rightY, 0.01f)
        assertEquals(0.8f, decoded.leftTrigger, 0.01f)
    }

    @Test fun `decode rechaza magic, version y longitud invalidos`() {
        val good = GamepadProtocol.encode(GamepadState(buttons = GamepadButtons.A))
        val badMagic = good.copyOf().also { it[0] = 0x58 } // 'X'
        assertNull(GamepadProtocol.decode(badMagic))
        val badVersion = good.copyOf().also { it[2] = 99 }
        assertNull(GamepadProtocol.decode(badVersion))
        assertNull(GamepadProtocol.decode(ByteArray(10)))
        assertNull(GamepadProtocol.decode(ByteArray(0)))
    }

    @Test fun `paquete corrupto se descarta`() {
        val bytes = GamepadProtocol.encode(GamepadState(buttons = GamepadButtons.A))
        bytes[16] = (bytes[16].toInt() xor 0xFF).toByte()
        assertNull(GamepadProtocol.decode(bytes))
    }

    @Test fun `dead zone ignora movimientos pequeños`() {
        assertEquals(0f, GamepadState.applyDeadZone1D(0.1f, 0.15f), 0f)
        assertEquals(0.5f, GamepadState.applyDeadZone1D(0.5f, 0.0f), 0.001f)
    }

    @Test fun `QR de emparejamiento se parsea y un PIN malo no`() {
        val info = PairingInfo.parseQrPayload("UGP1|192.168.1.20|51999|4821")
        assertNotNull(info)
        assertEquals("192.168.1.20", info!!.host)
        assertNull(PairingInfo.parseQrPayload("UGP1|192.168.1.20|51999|12"))
        assertNull(PairingInfo.parseQrPayload("OTRO|192.168.1.20|51999|4821"))
    }
}
