package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HidInputReportTest {

    @Test fun `estado vacio son 9 bytes con hat en centro`() {
        val report = HidInputReport.from(GamepadState())
        assertEquals(HidInputReport.SIZE_BYTES, report.size)
        assertEquals(9, report.size)
        assertEquals(0.toByte(), report[0])
        assertEquals(0.toByte(), report[1])
        assertEquals(8.toByte(), report[2]) // hat centrado
        assertEquals(0.toByte(), report[3])
        assertEquals(0.toByte(), report[4])
        assertEquals(0.toByte(), report[5])
        assertEquals(0.toByte(), report[6])
        assertEquals(0.toByte(), report[7])
        assertEquals(0.toByte(), report[8])
    }

    @Test fun `cada boton va en el bit del orden Android (A B X Y LB RB)`() {
        fun bit(button: Int): Int {
            val report = HidInputReport.from(GamepadState(buttons = button))
            return report[0].toInt() and 0xFF
        }
        assertEquals(0x01, bit(GamepadButtons.A))
        assertEquals(0x02, bit(GamepadButtons.B))
        assertEquals(0x08, bit(GamepadButtons.X)) // uso 4 -> BUTTON_X
        assertEquals(0x10, bit(GamepadButtons.Y)) // uso 5 -> BUTTON_Y
        assertEquals(0x40, bit(GamepadButtons.LB)) // uso 7 -> BUTTON_L1
        assertEquals(0x80, bit(GamepadButtons.RB)) // uso 8 -> BUTTON_R1
    }

    @Test fun `segundo byte con LT RT SELECT START HOME L3 R3 CAPTURE`() {
        fun bit(button: Int): Int {
            val report = HidInputReport.from(GamepadState(buttons = button))
            return report[1].toInt() and 0xFF
        }
        assertEquals(0x04, bit(GamepadButtons.SELECT)) // uso 11
        assertEquals(0x08, bit(GamepadButtons.START)) // uso 12
        assertEquals(0x10, bit(GamepadButtons.HOME)) // uso 13 -> BUTTON_MODE
        assertEquals(0x20, bit(GamepadButtons.L3)) // uso 14
        assertEquals(0x40, bit(GamepadButtons.R3)) // uso 15
        assertEquals(0x80, bit(GamepadButtons.CAPTURE)) // uso 16
    }

    @Test fun `gatillos digitales por encima de la mitad`() {
        val lt = HidInputReport.from(GamepadState(leftTrigger = 0.6f))
        assertEquals(0x01, lt[1].toInt() and 0xFF)
        val rt = HidInputReport.from(GamepadState(rightTrigger = 0.9f))
        assertEquals(0x02, rt[1].toInt() and 0xFF)
        val bajo = HidInputReport.from(GamepadState(leftTrigger = 0.5f, rightTrigger = 0.4f))
        assertEquals(0, bajo[1].toInt() and 0xFF)
    }

    @Test fun `gatillos analogicos 0-255 en los bytes 7 y 8`() {
        val report = HidInputReport.from(GamepadState(leftTrigger = 1f, rightTrigger = 0.5f))
        assertEquals(0xFF, report[7].toInt() and 0xFF)
        assertEquals(127, report[8].toInt() and 0xFF)
        val cuarto = HidInputReport.from(GamepadState(leftTrigger = 0.25f))
        assertEquals(63, cuarto[7].toInt() and 0xFF)
    }

    @Test fun `sticks con signo -127 a 127 centro 0`() {
        val report = HidInputReport.from(
            GamepadState(leftX = 1f, leftY = -1f, rightX = 0.5f, rightY = -0.5f),
        )
        assertEquals(127, report[3].toInt())
        assertEquals(-127, report[4].toInt())
        assertEquals(63, report[5].toInt())
        assertEquals(-63, report[6].toInt())
        // Fuera de rango se recorta, nunca se desborda.
        val recortado = HidInputReport.from(GamepadState(leftX = 2f, leftY = -2f))
        assertEquals(127, recortado[3].toInt())
        assertEquals(-127, recortado[4].toInt())
    }

    @Test fun `hat switch en las 8 direcciones y centro`() {
        fun hat(vararg buttons: Int): Int {
            var mask = 0
            for (b in buttons) mask = mask or b
            return HidInputReport.from(GamepadState(buttons = mask))[2].toInt() and 0xFF
        }
        assertEquals(0, hat(GamepadButtons.DPAD_UP))
        assertEquals(1, hat(GamepadButtons.DPAD_UP, GamepadButtons.DPAD_RIGHT))
        assertEquals(2, hat(GamepadButtons.DPAD_RIGHT))
        assertEquals(3, hat(GamepadButtons.DPAD_RIGHT, GamepadButtons.DPAD_DOWN))
        assertEquals(4, hat(GamepadButtons.DPAD_DOWN))
        assertEquals(5, hat(GamepadButtons.DPAD_DOWN, GamepadButtons.DPAD_LEFT))
        assertEquals(6, hat(GamepadButtons.DPAD_LEFT))
        assertEquals(7, hat(GamepadButtons.DPAD_LEFT, GamepadButtons.DPAD_UP))
        assertEquals(8, hat())
    }

    @Test fun `todos los botones a la vez llenan los dos bytes (salvo los usos 3 y 6, sin boton)`() {
        val all = GamepadButtons.A or GamepadButtons.B or GamepadButtons.X or
            GamepadButtons.Y or GamepadButtons.LB or GamepadButtons.RB or
            GamepadButtons.SELECT or GamepadButtons.START or GamepadButtons.HOME or
            GamepadButtons.L3 or GamepadButtons.R3 or GamepadButtons.CAPTURE
        val report = HidInputReport.from(
            GamepadState(buttons = all, leftTrigger = 1f, rightTrigger = 1f),
        )
        // b0 = 0xDB, no 0xFF: los usos 3 (BUTTON_C) y 6 (BUTTON_Z)
        // no tienen botón y quedan a 0 a propósito.
        assertEquals(0xDB, report[0].toInt() and 0xFF)
        assertEquals(0xFF, report[1].toInt() and 0xFF)
    }
}
