package com.ultimate.gamepad.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estado completo de un mando en un instante.
 *
 * Botones como bitmask (ver GamepadButtons). Sticks en [-1, 1],
 * gatillos en [0, 1]. Esta clase no sabe nada de Android UI,
 * Bluetooth ni red: es pura lógica y se comparte entre teléfono y TV.
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
) {
    fun isPressed(button: Int): Boolean = (buttons and button) != 0

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
        fun curve(v: Float): Float {
            if (v == 0f) return 0f
            val sign = if (v > 0) 1f else -1f
            return sign * Math.pow(abs(v).toDouble(), exponent.toDouble()).toFloat()
        }
        return copy(leftX = curve(leftX), leftY = curve(leftY), rightX = curve(rightX), rightY = curve(rightY))
    }

    companion object {
        fun applyDeadZone1D(value: Float, deadZone: Float): Float {
            if (abs(value) < deadZone) return 0f
            val sign = if (value > 0) 1f else -1f
            return sign * ((abs(value) - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        }

        /** Dead zone radial: ignora el desplazamiento si el vector es corto. */
        fun applyRadialDeadZone(x: Float, y: Float, deadZone: Float): Pair<Float, Float> {
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
}
