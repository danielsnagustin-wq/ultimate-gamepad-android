package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fase4CoreTest {
    // --- L4/R4 en el protocolo (byte 29) ---

    @Test fun `L4 y R4 sobreviven a encode-decode sin tocar el bitmask`() {
        val state = GamepadState(
            buttons = GamepadButtons.A,
            extraButtons = GamepadExtraButtons.L4 or GamepadExtraButtons.R4,
            sequence = 7, timestampMs = 1_700_000_000_000L,
        )
        val bytes = GamepadProtocol.encode(state, GamepadProfiles.XBOX_360_ID)
        assertEquals(GamepadExtraButtons.L4 or GamepadExtraButtons.R4, bytes[29].toInt() and 0xFF)
        assertEquals(0, bytes[30].toInt() and 0xFF)
        assertEquals(0, bytes[31].toInt() and 0xFF)
        val decoded = GamepadProtocol.decode(bytes)
        assertNotNull(decoded)
        decoded!!
        assertEquals(GamepadButtons.A, decoded.buttons)
        assertEquals(GamepadExtraButtons.L4 or GamepadExtraButtons.R4, decoded.extraButtons)
        assertTrue(decoded.isExtraPressed(GamepadExtraButtons.L4))
        assertTrue(decoded.isExtraPressed(GamepadExtraButtons.R4))
        assertFalse(decoded.isExtraPressed(GamepadExtraButtons.L4 shl 2))
    }

    @Test fun `un paquete viejo con byte 29 a 0 decodifica extraButtons a 0`() {
        val state = GamepadState(buttons = GamepadButtons.B)
        val bytes = GamepadProtocol.encode(state)
        assertEquals(0, bytes[29].toInt() and 0xFF)
        assertEquals(0, GamepadProtocol.decode(bytes)!!.extraButtons)
        assertEquals(0, GamepadProtocol.extraButtonsOf(bytes))
        assertEquals(GamepadExtraButtons.L4, GamepadProtocol.extraButtonsOf(ByteArray(30).also { it[29] = 1 }))
    }

    @Test fun `corromper el byte 29 no rompe el paquete (no va cubierto por el CRC)`() {
        val bytes = GamepadProtocol.encode(GamepadState(buttons = GamepadButtons.A, extraButtons = 0))
        bytes[29] = 0x03
        val decoded = GamepadProtocol.decode(bytes)
        assertNotNull(decoded)
        assertEquals(GamepadExtraButtons.L4 or GamepadExtraButtons.R4, decoded!!.extraButtons)
        assertEquals(GamepadButtons.A, decoded.buttons)
    }

    @Test fun `L4 y R4 conviven con el profileId del byte 28`() {
        val bytes = GamepadProtocol.encode(
            GamepadState(extraButtons = GamepadExtraButtons.L4),
            GamepadProfiles.DUALSENSE_ID,
        )
        assertEquals(GamepadProfiles.DUALSENSE_ID, GamepadProtocol.profileIdOf(bytes))
        assertEquals(GamepadExtraButtons.L4, GamepadProtocol.extraButtonsOf(bytes))
        assertEquals(GamepadExtraButtons.L4, GamepadProtocol.decode(bytes)!!.extraButtons)
    }

    // --- Turbo ---

    @Test fun `turbo quita los botones con turbo en fase OFF y los deja en ON`() {
        val held = GamepadButtons.A or GamepadButtons.B or GamepadButtons.X
        val turbo = setOf(GamepadButtons.A, GamepadButtons.B)
        assertEquals(held, Turbo.apply(held, turbo, true))
        assertEquals(GamepadButtons.X, Turbo.apply(held, turbo, false))
        assertEquals(held, Turbo.apply(held, emptySet(), false))
        assertEquals(held, Turbo.apply(held, turbo, true))
    }

    @Test fun `medio periodo a 10 Hz son 50 ms`() {
        assertEquals(50L, Turbo.halfPeriodMs(10))
        assertEquals(100L, Turbo.halfPeriodMs(5))
        assertEquals(16L, Turbo.halfPeriodMs(100)) // recortado a 1..30 Hz y mínimo 16 ms
        val clean = TurboConfig(setOf(1 shl 20), 99).sanitized()
        assertEquals(emptySet<Int>(), clean.buttons)
        assertEquals(30, clean.rateHz)
        assertEquals(setOf(GamepadButtons.A), TurboConfig(setOf(GamepadButtons.A, 1 shl 20), 10).sanitized().buttons)
    }

    // --- Macros ---

    @Test fun `grabar A-B y reproducirla guarda los cambios con sus tiempos`() {
        val r = MacroRecorder()
        r.start(1000L)
        r.onState(1000L, 0, 0)
        r.onState(1100L, GamepadButtons.A, 0)
        r.onState(1300L, GamepadButtons.A or GamepadButtons.B, 0)
        r.onState(1500L, 0, 0)
        val macro = r.stop(1600L, "Combo")
        assertNotNull(macro)
        macro!!
        assertEquals("Combo", macro.name)
        assertEquals(3, macro.events.size)
        assertEquals(100L, macro.events[0].delayMs)
        assertEquals(GamepadButtons.A, macro.events[0].buttons)
        assertEquals(300L, macro.events[1].delayMs)
        assertEquals(GamepadButtons.A or GamepadButtons.B, macro.events[1].buttons)
        assertEquals(500L, macro.events[2].delayMs)
        assertEquals(0, macro.events[2].buttons)
        assertEquals(500L, macro.durationMs)
        assertFalse(r.recording)
    }

    @Test fun `parar con botones pulsados anade el evento final soltando todo`() {
        val r = MacroRecorder()
        r.start(0L)
        r.onState(100L, GamepadButtons.X, GamepadExtraButtons.L4)
        val macro = r.stop(500L, "X+L4")
        assertNotNull(macro)
        assertEquals(2, macro!!.events.size)
        assertEquals(0, macro.events[1].buttons)
        assertEquals(0, macro.events[1].extraButtons)
        assertEquals(500L, macro.events[1].delayMs)
    }

    @Test fun `grabar sin pulsar nada no crea macro`() {
        val r = MacroRecorder()
        r.start(0L)
        r.onState(100L, 0, 0)
        assertNull(r.stop(200L, "Vacia"))
    }

    @Test fun `sanitized recorta nombre y eventos`() {
        val events = (0 until 300).map { MacroEvent(it.toLong(), GamepadButtons.A, 0) }
        val m = GamepadMacro("", events).sanitized()
        assertEquals("Sin nombre", m.name)
        assertEquals(GamepadMacro.MAX_EVENTS, m.events.size)
    }

    // --- Giroscopio ---

    @Test fun `sin inclinacion el stick esta a cero`() {
        assertEquals(0f to 0f, GyroMapper.mapTilt(0f, 0f, 1f))
    }

    @Test fun `45 grados de roll es todo el recorrido en X`() {
        val (x, y) = GyroMapper.mapTilt(0f, GyroMapper.FULL_TILT_RAD, 1f)
        assertEquals(1f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    @Test fun `inclinar hacia adelante es stick hacia arriba (Y negativa)`() {
        val (_, y) = GyroMapper.mapTilt(-GyroMapper.FULL_TILT_RAD, 0f, 1f)
        assertEquals(-1f, y, 0.001f)
    }

    @Test fun `la sensibilidad multiplica y se recorta a 1`() {
        val (x, _) = GyroMapper.mapTilt(0f, GyroMapper.FULL_TILT_RAD / 2f, 2f)
        assertEquals(1f, x, 0.001f)
        val (x2, _) = GyroMapper.mapTilt(0f, GyroMapper.FULL_TILT_RAD / 2f, 0.5f)
        assertEquals(0.25f, x2, 0.001f)
    }

    // --- Teclado virtual (KeyMap + ExtrasProtocol) ---

    @Test fun `KeyMap cubre letras digitos y espacio con su SHIFT`() {
        assertEquals(30 to false, KeyMap.forChar('a'))
        assertEquals(30 to true, KeyMap.forChar('A'))
        assertEquals(57 to false, KeyMap.forChar(' '))
        assertEquals(2 to false, KeyMap.forChar('1'))
        assertEquals(2 to true, KeyMap.forChar('!'))
        assertEquals(28 to false, KeyMap.forChar('\n'))
        assertNull(KeyMap.forChar('ñ'))
        assertNull(KeyMap.forChar('¿'))
        assertEquals(KeyMap.KEY_ENTER, KeyMap.forSpecial("enter"))
        assertEquals(KeyMap.KEY_BACKSPACE, KeyMap.forSpecial("backspace"))
        assertNull(KeyMap.forSpecial("f1"))
    }

    @Test fun `paquete de teclado sobrevive a encode-decode`() {
        val bytes = ExtrasProtocol.encodeKeyboard(KeyMap.KEY_A, ExtrasProtocol.ACTION_PRESS)
        assertTrue(ExtrasProtocol.isKeyboard(bytes))
        assertFalse(ExtrasProtocol.isMouse(bytes))
        val ev = ExtrasProtocol.decodeKeyboard(bytes)
        assertNotNull(ev)
        assertEquals(KeyMap.KEY_A, ev!!.keyCode)
        assertEquals(ExtrasProtocol.ACTION_PRESS, ev.action)
        val rel = ExtrasProtocol.decodeKeyboard(ExtrasProtocol.encodeKeyboard(KeyMap.KEY_ENTER, ExtrasProtocol.ACTION_RELEASE))
        assertEquals(ExtrasProtocol.ACTION_RELEASE, rel!!.action)
        assertNull(ExtrasProtocol.decodeKeyboard(byteArrayOf(0x55, 0x47, 0, 0, 0, 0, 0, 0))) // magic de mando, no de teclado
    }

    @Test fun `paquete de raton sobrevive a encode-decode con deltas negativos`() {
        val bytes = ExtrasProtocol.encodeMouse(-120, 45, ExtrasProtocol.MOUSE_BUTTON_LEFT)
        assertTrue(ExtrasProtocol.isMouse(bytes))
        assertFalse(ExtrasProtocol.isKeyboard(bytes))
        val ev = ExtrasProtocol.decodeMouse(bytes)
        assertNotNull(ev)
        assertEquals(-120, ev!!.dx)
        assertEquals(45, ev.dy)
        assertEquals(ExtrasProtocol.MOUSE_BUTTON_LEFT, ev.buttons)
        assertEquals(ExtrasProtocol.ACTION_PRESS, ev.action)
        val create = ExtrasProtocol.decodeMouse(ExtrasProtocol.encodeMouse(0, 0, 0, ExtrasProtocol.ACTION_CREATE))
        assertEquals(ExtrasProtocol.ACTION_CREATE, create!!.action)
    }

    // --- Checklist con L4/R4 ---

    @Test fun `la checklist marca L4 y R4 y el total es 22`() {
        val c = ControllerTestChecklist()
        assertEquals(22, c.totalCount)
        c.update(GamepadState(extraButtons = GamepadExtraButtons.L4))
        assertTrue(c.items().first { it.id == "extra_${GamepadExtraButtons.L4}" }.tested)
        assertFalse(c.items().first { it.id == "extra_${GamepadExtraButtons.R4}" }.tested)
        assertEquals("L4", c.items().first { it.id == "extra_${GamepadExtraButtons.L4}" }.label)
    }
}
