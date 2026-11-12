package com.ultimate.gamepad.core

/**
 * Reporte de entrada HID del mando (9 bytes de datos, sin el
 * Report ID 1; ver HidGamepadDescriptor). Función pura en :core
 * para poder probarla sin Android (ronda Bluetooth dos vías,
 * 2026-11-11).
 *
 * Orden de botones (Android/Generic.kl — el kernel Linux mapea
 * el uso HID nº N al código 0x130 + (N-1), y Android lo traduce
 * a BUTTON_A/B/X/Y/L1/R1/L2/R2/SELECT/START/MODE/THUMBL/THUMBR):
 *   byte 0: A=0x01 (uso 1), B=0x02 (uso 2), X=0x08 (uso 4),
 *           Y=0x10 (uso 5), LB=0x40 (uso 7), RB=0x80 (uso 8)
 *   byte 1: LT=0x01 (uso 9), RT=0x02 (uso 10), SELECT=0x04 (uso 11),
 *           START=0x08 (uso 12), HOME=0x10 (uso 13), L3=0x20 (uso 14),
 *           R3=0x40 (uso 15), CAPTURE=0x80 (uso 16)
 *   byte 2: hat switch (0-7, 8=centro), como antes.
 *   bytes 3-6: sticks X/Y/Z/Rz con signo (-127..127, centro 0).
 *   bytes 7-8: gatillos analógicos LT/RT (0..255).
 *
 * El orden ANTERIOR (X=0x04, Y=0x08, LB=0x10, RB=0x20,
 * SELECT=0x40, START=0x80, L3=0x01 b1, R3=0x02 b1, LT=0x04 b1,
 * RT=0x08 b1) estaba mal: con Generic.kl, X salía como BUTTON_C,
 * Y como BUTTON_X, LB como BUTTON_Y, etc. — solo A y B llegaban
 * bien al juego. Los gatillos digitales del bitmask (GamepadButtons
 * no tiene bit para LT/RT) se derivan del valor analógico: >0.5 =
 * pulsado, como hacía hidReportFrom antes.
 */
object HidInputReport {
    /** Tamaño del reporte de entrada (datos, sin el Report ID 1). */
    const val SIZE_BYTES: Int = 9

    /** Umbral analógico para considerar un gatillo "pulsado" (digital). */
    const val TRIGGER_DIGITAL_THRESHOLD: Float = 0.5f

    fun from(state: GamepadState): ByteArray {
        var b0 = 0
        if (state.isPressed(GamepadButtons.A)) b0 = b0 or 0x01
        if (state.isPressed(GamepadButtons.B)) b0 = b0 or 0x02
        if (state.isPressed(GamepadButtons.X)) b0 = b0 or 0x08
        if (state.isPressed(GamepadButtons.Y)) b0 = b0 or 0x10
        if (state.isPressed(GamepadButtons.LB)) b0 = b0 or 0x40
        if (state.isPressed(GamepadButtons.RB)) b0 = b0 or 0x80
        var b1 = 0
        if (state.leftTrigger > TRIGGER_DIGITAL_THRESHOLD) b1 = b1 or 0x01
        if (state.rightTrigger > TRIGGER_DIGITAL_THRESHOLD) b1 = b1 or 0x02
        if (state.isPressed(GamepadButtons.SELECT)) b1 = b1 or 0x04
        if (state.isPressed(GamepadButtons.START)) b1 = b1 or 0x08
        if (state.isPressed(GamepadButtons.HOME)) b1 = b1 or 0x10
        if (state.isPressed(GamepadButtons.L3)) b1 = b1 or 0x20
        if (state.isPressed(GamepadButtons.R3)) b1 = b1 or 0x40
        if (state.isPressed(GamepadButtons.CAPTURE)) b1 = b1 or 0x80
        return byteArrayOf(
            b0.toByte(),
            b1.toByte(),
            hatValue(state).toByte(),
            axisByte(state.leftX),
            axisByte(state.leftY),
            axisByte(state.rightX),
            axisByte(state.rightY),
            triggerByte(state.leftTrigger),
            triggerByte(state.rightTrigger),
        )
    }

    /** Eje con signo: -1..1 → -127..127 (centro 0), como antes. */
    fun axisByte(v: Float): Byte = (v.coerceIn(-1f, 1f) * 127f).toInt().toByte()

    /** Gatillo analógico: 0..1 → 0..255. */
    fun triggerByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()

    /** Hat switch 0..7 según el D-pad, 8 = centro (sin dirección). */
    fun hatValue(state: GamepadState): Int {
        val up = state.isPressed(GamepadButtons.DPAD_UP)
        val down = state.isPressed(GamepadButtons.DPAD_DOWN)
        val left = state.isPressed(GamepadButtons.DPAD_LEFT)
        val right = state.isPressed(GamepadButtons.DPAD_RIGHT)
        return when {
            up && right -> 1
            right && down -> 3
            down && left -> 5
            left && up -> 7
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> 8 // Centro: sin dirección (valor nulo del hat, estándar HID).
        }
    }
}
