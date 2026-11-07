package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fase3CoreTest {
    // --- ControllerTestChecklist ---

    @Test fun `la checklist empieza vacia con 20 controles`() {
        val c = ControllerTestChecklist()
        assertEquals(0, c.testedCount)
        assertEquals(20, c.totalCount)
        assertFalse(c.allDone)
        assertEquals(20, c.items().size)
        assertTrue(c.items().none { it.tested })
    }

    @Test fun `pulsar un boton lo marca y solo ese`() {
        val c = ControllerTestChecklist()
        c.update(GamepadState(buttons = GamepadButtons.A))
        val items = c.items()
        assertTrue(items.first { it.id == "btn_${GamepadButtons.A}" }.tested)
        assertFalse(items.first { it.id == "btn_${GamepadButtons.B}" }.tested)
        assertEquals(1, c.testedCount)
    }

    @Test fun `un stick solo se marca pasado la mitad del recorrido`() {
        val c = ControllerTestChecklist()
        c.update(GamepadState(leftX = 0.4f))
        assertFalse(c.items().first { it.id == "stick_left" }.tested)
        c.update(GamepadState(leftY = -0.8f))
        assertTrue(c.items().first { it.id == "stick_left" }.tested)
        assertFalse(c.items().first { it.id == "stick_right" }.tested)
    }

    @Test fun `los gatillos se marcan pasando de 0_5`() {
        val c = ControllerTestChecklist()
        c.update(GamepadState(leftTrigger = 0.5f, rightTrigger = 0.9f))
        assertFalse(c.items().first { it.id == "trigger_left" }.tested)
        assertTrue(c.items().first { it.id == "trigger_right" }.tested)
    }

    @Test fun `las etiquetas siguen el perfil activo`() {
        val c = ControllerTestChecklist()
        val ds4 = c.items(GamepadProfiles.DS4_ID)
        assertEquals("Cross", ds4.first { it.id == "btn_${GamepadButtons.A}" }.label)
        assertEquals("Circle", ds4.first { it.id == "btn_${GamepadButtons.B}" }.label)
        val xbox = c.items(GamepadProfiles.XBOX_360_ID)
        assertEquals("A", xbox.first { it.id == "btn_${GamepadButtons.A}" }.label)
    }

    @Test fun `reset limpia y allDone con todo probado`() {
        val c = ControllerTestChecklist()
        val all = GamepadButtons.A or GamepadButtons.B or GamepadButtons.X or GamepadButtons.Y or
            GamepadButtons.LB or GamepadButtons.RB or GamepadButtons.START or GamepadButtons.SELECT or
            GamepadButtons.L3 or GamepadButtons.R3 or GamepadButtons.DPAD_UP or GamepadButtons.DPAD_DOWN or
            GamepadButtons.DPAD_LEFT or GamepadButtons.DPAD_RIGHT or GamepadButtons.HOME or GamepadButtons.CAPTURE
        c.update(GamepadState(buttons = all, leftX = 1f, rightX = -1f, leftTrigger = 1f, rightTrigger = 1f))
        assertTrue(c.allDone)
        assertEquals(20, c.testedCount)
        c.reset()
        assertEquals(0, c.testedCount)
        assertFalse(c.allDone)
    }

    // --- NetworkStats ---

    @Test fun `jitter es la media de las diferencias entre pings consecutivos`() {
        assertNull(NetworkStats.jitterMs(emptyList()))
        assertNull(NetworkStats.jitterMs(listOf(10L)))
        assertEquals(0L, NetworkStats.jitterMs(listOf(10L, 10L, 10L)))
        assertEquals(5L, NetworkStats.jitterMs(listOf(10L, 15L, 10L)))
        assertEquals(10L, NetworkStats.jitterMs(listOf(10L, 20L, 30L)))
    }

    @Test fun `hueco de secuencia con wrap y duplicados`() {
        assertEquals(0, NetworkStats.sequenceGap(-1, 0))
        assertEquals(0, NetworkStats.sequenceGap(5, 6))
        assertEquals(2, NetworkStats.sequenceGap(5, 8))
        assertEquals(0, NetworkStats.sequenceGap(5, 5))
        assertEquals(0, NetworkStats.sequenceGap(5, 4))
        // Wrap: de 65534 a 1 faltan 65535 y 0 (hueco 2); a 2
        // faltan 65535, 0 y 1 (hueco 3); de 65535 a 0 es
        // consecutivo (hueco 0) y a 1 falta solo el 0 (hueco 1).
        assertEquals(2, NetworkStats.sequenceGap(65534, 1))
        assertEquals(3, NetworkStats.sequenceGap(65534, 2))
        assertEquals(0, NetworkStats.sequenceGap(65535, 0))
        assertEquals(1, NetworkStats.sequenceGap(65535, 1))
    }

    // --- GameProfile ---

    @Test fun `sanitized recorta rangos y limpia el remap`() {
        val p = GameProfile(
            name = "", profileId = 99, deadZone = 0.9f, curve = 9f, triggerScale = 0.1f,
            remap = mapOf(GamepadButtons.A to GamepadButtons.B, GamepadButtons.B to GamepadButtons.B, 1 shl 20 to GamepadButtons.A),
        ).sanitized()
        assertEquals("Sin nombre", p.name)
        assertEquals(GamepadProfiles.XBOX_360_ID, p.profileId)
        assertEquals(0.15f, p.deadZone, 0.001f)
        assertEquals(1.5f, p.curve, 0.001f)
        assertEquals(0.5f, p.triggerScale, 0.001f)
        assertEquals(mapOf(GamepadButtons.A to GamepadButtons.B), p.remap)
    }

    @Test fun `applyRemap traduce bits y un intercambio funciona en los dos sentidos`() {
        val p = GameProfile(name = "T", remap = mapOf(GamepadButtons.A to GamepadButtons.B, GamepadButtons.B to GamepadButtons.A))
        assertEquals(GamepadButtons.B, p.applyRemap(GamepadButtons.A))
        assertEquals(GamepadButtons.A, p.applyRemap(GamepadButtons.B))
        assertEquals(GamepadButtons.A or GamepadButtons.B, p.applyRemap(GamepadButtons.A or GamepadButtons.B))
        assertEquals(GamepadButtons.X, p.applyRemap(GamepadButtons.X))
        assertEquals(0, p.applyRemap(0))
        assertEquals(GamepadButtons.A, GameProfile(name = "S").applyRemap(GamepadButtons.A))
    }
}
