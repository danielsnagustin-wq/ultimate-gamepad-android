package com.ultimate.gamepad.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estado completo de un mando en un instante.
 *
 * Botones como bitmask (ver GamepadButtons). Sticks en [-1, 1],
 * gatillos en [0, 1]. Esta clase no sabe nada de Android UI,
 * Bluetooth ni red: es pura lógica y se comparte entre teléfono y TV.
 *
 * [extraButtons] (Fase 4): botones extra que NO caben en el
 * bitmask de 16 bits (GamepadExtraButtons: L4/R4). Viajan en el
 * byte reservado 29 del protocolo (GamepadProtocol), NO en el
 * bitmask; por eso el remapeo de GameProfile NO los cubre. El
 * descriptor HID (16 botones, todos estándar) tampoco los envía
 * como botones propios: el ViewModel los remapea a L3/R3 en publish()
 * (GamepadButtons.extraRemapBits, ronda REA GFN+OpenNOW), que
 * GeForce NOW y OpenNOW sí pasan al juego por todos los
 * transportes, incluido Bluetooth HID.
 */
data class GamepadState(
    val buttons: Int = 0,
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val sequence: Int = 0,
    val timestampMs: Long = 0L,
    val extraButtons: Int = 0,
) {
    fun isPressed(button: Int): Boolean = (buttons and button) != 0

    fun isExtraPressed(button: Int): Boolean = (extraButtons and button) != 0

    fun withDeadZone(deadZone: Float): GamepadState {
        require(deadZone in 0f..0.9f) { "deadZone debe estar entre 0 y 0.9" }
        return copy(
            leftX = applyDeadZone1D(leftX, deadZone),
            leftY = applyDeadZone1D(leftY, deadZone),
            rightX = applyDeadZone1D(rightX, deadZone),
            rightY = applyDeadZone1D(rightY, deadZone),
        )
    }

    /** Curva de respuesta: 1.0 = lineal, >1 = más suave en el centro. */
    fun withResponseCurve(exponent: Float): GamepadState {
        // Ronda 2 P2-10: exponente 0 devuelve ±1 (cualquier valor
        // ^0 = 1) y uno negativo amplifica por encima de 1.
        require(exponent > 0f) { "exponent debe ser > 0" }
        fun curve(v: Float): Float {
            if (v == 0f) return 0f
            val sign = if (v > 0) 1f else -1f
            return sign * Math.pow(abs(v).toDouble(), exponent.toDouble()).toFloat()
        }
        return copy(leftX = curve(leftX), leftY = curve(leftY), rightX = curve(rightX), rightY = curve(rightY))
    }

    companion object {
        fun applyDeadZone1D(value: Float, deadZone: Float): Float {
            // Ronda 2 P2-10: deadZone = 1 da 0/0 = NaN.
            require(deadZone in 0f..0.9f) { "deadZone debe estar entre 0 y 0.9" }
            if (abs(value) < deadZone) return 0f
            val sign = if (value > 0) 1f else -1f
            return sign * ((abs(value) - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        }

        /** Dead zone radial: ignora el desplazamiento si el vector es corto. */
        fun applyRadialDeadZone(x: Float, y: Float, deadZone: Float): Pair<Float, Float> {
            require(deadZone in 0f..0.9f) { "deadZone debe estar entre 0 y 0.9" }
            val mag = sqrt(x * x + y * y)
            if (mag < deadZone || mag == 0f) return 0f to 0f
            val scale = ((mag - deadZone) / (1f - deadZone)).coerceIn(0f, 1f) / mag
            return (x * scale) to (y * scale)
        }
    }
}

/** Bitmask de botones. Debe coincidir 1:1 en teléfono y TV. */
object GamepadButtons {
    const val A: Int = 1 shl 0
    const val B: Int = 1 shl 1
    const val X: Int = 1 shl 2
    const val Y: Int = 1 shl 3
    const val LB: Int = 1 shl 4
    const val RB: Int = 1 shl 5
    const val START: Int = 1 shl 6
    const val SELECT: Int = 1 shl 7
    const val L3: Int = 1 shl 8
    const val R3: Int = 1 shl 9
    const val DPAD_UP: Int = 1 shl 10
    const val DPAD_DOWN: Int = 1 shl 11
    const val DPAD_LEFT: Int = 1 shl 12
    const val DPAD_RIGHT: Int = 1 shl 13
    const val HOME: Int = 1 shl 14
    const val CAPTURE: Int = 1 shl 15

    /**
     * Remapeo de paletas L4/R4 (ronda REA GFN+OpenNOW, 2026-11-11):
     * GeForce NOW y OpenNOW ignoran BTN_TRIGGER_HAPPY2/3 (lo que la
     * TV emite por el byte 29) y el descriptor HID no tiene hueco
     * para L4/R4, así que cada paleta actúa como un botón estándar
     * del mando. Función pura para probarla en :core. [l4Target] /
     * [r4Target] son bits de GamepadButtons (0 = la paleta no hace
     * nada); por defecto L4→L3 y R4→R3.
     */
    fun extraRemapBits(extraButtons: Int, l4Target: Int, r4Target: Int): Int {
        var out = 0
        if ((extraButtons and GamepadExtraButtons.L4) != 0) out = out or l4Target
        if ((extraButtons and GamepadExtraButtons.R4) != 0) out = out or r4Target
        return out
    }
}

/**
 * Botones extra (Fase 4). El bitmask de 16 bits está lleno
 * (16/16) y NO se puede ampliar sin romper el protocolo: L4/R4
 * viajan en el byte reservado 29 (bit 0 = L4, bit 1 = R4) y se
 * guardan en GamepadState.extraButtons, separados del bitmask.
 * En la TV se inyectan como BTN_TRIGGER_HAPPY2/3 (uinput), que
 * GeForce NOW y OpenNOW ignoran; por Bluetooth HID NO se envían
 * como botones propios (el descriptor declara 16 botones y los 16
 * son botones estándar, sin hueco para las paletas) y el remapeo
 * por juego tampoco los cubre.
 * Lo que sí llega al juego por todos los transportes es el
 * remapeo a L3/R3 (GamepadButtons.extraRemapBits, aplicado en
 * GamepadViewModel.publish()).
 */
object GamepadExtraButtons {
    const val L4: Int = 1 shl 0
    const val R4: Int = 1 shl 1
}
