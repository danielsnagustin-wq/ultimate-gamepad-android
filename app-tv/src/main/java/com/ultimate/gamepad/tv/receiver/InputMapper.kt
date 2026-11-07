package com.ultimate.gamepad.tv.receiver

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadState

/**
 * Convierte GamepadState a eventos Android con source de mando.
 *
 * REGLA OBLIGATORIA (bug reportado 2026-11-07): TODOS los eventos
 * llevan SOURCE_GAMEPAD | SOURCE_JOYSTICK. Nunca SOURCE_DPAD solo,
 * nunca SOURCE_KEYBOARD, nunca SOURCE_TOUCHSCREEN. La cruceta va
 * por AXIS_HAT_X/Y ADEMÁS de por KEYCODE_DPAD_* con source GAMEPAD.
 * Nunca KEYCODE_ENTER ni KEYCODE_BACK para jugar: eso es lo que
 * hace que Android clasifique la app como control de TV.
 *
 * Esto es el FALLBACK (inyección B), no la vía principal. La vía
 * principal es el mando virtual uinput Xbox 360 (inyección A), que
 * aparece en InputDevice.getDeviceIds() como un mando real y es lo
 * que OpenNOW necesita. Ver UinputGamepad.kt y Diagnostics.kt.
 */
object InputMapper {
    /** Fuente única para todo evento de juego. Ver GamepadClassification. */
    const val GAMEPAD_SOURCE: Int = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK

    /** Comprueba que un evento no se ha colado con fuente de mando a distancia. */
    fun assertGamepadSource(sources: Int): Boolean =
        (sources and InputDevice.SOURCE_GAMEPAD) != 0 || (sources and InputDevice.SOURCE_JOYSTICK) != 0

    fun buttonToKeyCode(button: Int): Int = when (button) {
        GamepadButtons.A -> KeyEvent.KEYCODE_BUTTON_A
        GamepadButtons.B -> KeyEvent.KEYCODE_BUTTON_B
        GamepadButtons.X -> KeyEvent.KEYCODE_BUTTON_X
        GamepadButtons.Y -> KeyEvent.KEYCODE_BUTTON_Y
        GamepadButtons.LB -> KeyEvent.KEYCODE_BUTTON_L1
        GamepadButtons.RB -> KeyEvent.KEYCODE_BUTTON_R1
        GamepadButtons.START -> KeyEvent.KEYCODE_BUTTON_START
        GamepadButtons.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
        GamepadButtons.L3 -> KeyEvent.KEYCODE_BUTTON_THUMBL
        GamepadButtons.R3 -> KeyEvent.KEYCODE_BUTTON_THUMBR
        GamepadButtons.HOME -> KeyEvent.KEYCODE_BUTTON_MODE
        GamepadButtons.CAPTURE -> KeyEvent.KEYCODE_BUTTON_1
        GamepadButtons.DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
        GamepadButtons.DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        GamepadButtons.DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        GamepadButtons.DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    fun allButtons(): List<Int> = listOf(
        GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y,
        GamepadButtons.LB, GamepadButtons.RB, GamepadButtons.START, GamepadButtons.SELECT,
        GamepadButtons.L3, GamepadButtons.R3, GamepadButtons.HOME, GamepadButtons.CAPTURE,
        GamepadButtons.DPAD_UP, GamepadButtons.DPAD_DOWN, GamepadButtons.DPAD_LEFT, GamepadButtons.DPAD_RIGHT,
    )

    fun keyEventFor(state: GamepadState, previous: GamepadState, button: Int, nowMs: Long): KeyEvent? {
        val pressed = state.isPressed(button)
        val wasPressed = previous.isPressed(button)
        if (pressed == wasPressed) return null
        val keyCode = buttonToKeyCode(button)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return null
        // Cruceta: KEYCODE_DPAD_* SIEMPRE con source GAMEPAD (nunca
        // SOURCE_DPAD solo) y además por AXIS_HAT_X/Y en el MotionEvent.
        // PROHIBIDO: KEYCODE_ENTER / KEYCODE_BACK para jugar.
        return KeyEvent(
            nowMs, nowMs,
            if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode, 0, 0,
            KeyEvent.KEYCODE_UNKNOWN, 0,
            KeyEvent.FLAG_FROM_SYSTEM,
            GAMEPAD_SOURCE,
        )
    }

    /** MotionEvent de joystick. deviceId = 0: el inyector shell lo asigna; si OpenNOW lo rechaza, es señal de que hace falta uinput. */
    fun motionEventFor(state: GamepadState, nowMs: Long): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_X, state.leftX)
                setAxisValue(MotionEvent.AXIS_Y, state.leftY)
                setAxisValue(MotionEvent.AXIS_Z, state.rightX)
                setAxisValue(MotionEvent.AXIS_RZ, state.rightY)
                setAxisValue(MotionEvent.AXIS_LTRIGGER, state.leftTrigger)
                setAxisValue(MotionEvent.AXIS_RTRIGGER, state.rightTrigger)
                setAxisValue(MotionEvent.AXIS_HAT_X, hatX(state))
                setAxisValue(MotionEvent.AXIS_HAT_Y, hatY(state))
            },
        )
        return MotionEvent.obtain(
            nowMs, nowMs, MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            GAMEPAD_SOURCE, 0,
        )
    }

    private fun hatX(s: GamepadState): Float = when {
        s.isPressed(GamepadButtons.DPAD_LEFT) -> -1f
        s.isPressed(GamepadButtons.DPAD_RIGHT) -> 1f
        else -> 0f
    }
    private fun hatY(s: GamepadState): Float = when {
        s.isPressed(GamepadButtons.DPAD_UP) -> -1f
        s.isPressed(GamepadButtons.DPAD_DOWN) -> 1f
        else -> 0f
    }
}
