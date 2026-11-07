package com.ultimate.gamepad.tv.shizuku

import com.ultimate.gamepad.core.GamepadClassification
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.UinputSpec

/**
 * Mando virtual Xbox 360 vía /dev/uinput — implementación nativa
 * (app-tv/src/main/cpp/uinput_gamepad.c, compilada con CMake/NDK).
 *
 * Especificación EXACTA que cumple la implementación nativa
 * (ver UinputSpec en :core — una sola fuente de verdad):
 * - Nombre expuesto: ÚNICAMENTE "Xbox 360 Controller".
 *   PROHIBIDO exponer "Virtual" o "Ultimate Gamepad" como nombre
 *   de dispositivo: eso delata que es virtual y es FALLO en la
 *   Etapa 0, aunque lleguen paquetes por Wi-Fi.
 * - vendor 0x045E (Microsoft), product 0x028E, bustype BUS_USB (0x03), version 0x0114
 * - Solo EV_KEY de mando: BTN_A/B/X/Y, BTN_TL/TR, BTN_TL2/TR2,
 *   BTN_START/SELECT, BTN_MODE (guía Xbox), BTN_THUMBL/R,
 *   BTN_DPAD_UP/DOWN/LEFT/RIGHT, BTN_TRIGGER_HAPPY1 (Compartir)
 * - Solo EV_ABS: ABS_X/Y (-32768..32767, stick izq),
 *   ABS_RX/RY (-32768..32767, stick der), ABS_Z/RZ (0..255,
 *   gatillos LT/RT), ABS_HAT0X/HAT0Y (-1..1, cruceta)
 * - PROHIBIDO: KEY_POWER, KEY_HOME, KEY_VOLUME*, KEY_MENU,
 *   KEY_BACK, y Usage Page Consumer (0x0C) / Remote Control.
 *   Incluir una sola de esas teclas es lo que hace que Android
 *   clasifique el dispositivo como control de TV en vez de mando.
 *
 * Honestidad: si la librería nativa no está (el .so no se
 * compiló) o /dev/uinput no se puede abrir, create() devuelve
 * false y isReady() false. El receptor usa entonces
 * injectInputEvent (inyección B) y la UI lo muestra como tal.
 * isReady() solo es true cuando el kernel aceptó el dispositivo.
 */
object UinputGamepad {
    val spec: UinputSpec = UinputSpec

    @Volatile private var nativeLoaded: Boolean = false
    @Volatile private var ready: Boolean = false

    init {
        nativeLoaded = runCatching {
            System.loadLibrary("uinput_gamepad")
            true
        }.getOrDefault(false)
    }

    fun specSummary(): String =
        "${UinputSpec.DEVICE_NAME} vendor=0x045E product=0x028E bustype=BUS_USB " +
            "EV_KEY=${UinputSpec.ALLOWED_EV_KEY.size} botones de mando, " +
            "EV_ABS=${UinputSpec.ALLOWED_EV_ABS.size} ejes, prohibidas=${UinputSpec.FORBIDDEN_EV_KEY.size} teclas de remoto"

    fun isSpecValid(): Boolean =
        UinputSpec.ALLOWED_EV_KEY.none { it in UinputSpec.FORBIDDEN_EV_KEY } &&
            GamepadClassification.isGamepadSource(GamepadClassification.SOURCE_GAMEPAD_JOYSTICK)

    /** ¿Se cargó el .so nativo? Sin él no hay mando virtual. */
    fun isNativeLoaded(): Boolean = nativeLoaded

    fun create(): Boolean {
        if (!nativeLoaded) return false
        ready = runCatching { nativeCreate() }.getOrDefault(false)
        return ready
    }

    fun isReady(): Boolean = ready && nativeLoaded

    /** Envía un estado ya decodificado al mando virtual. Solo si está listo. */
    fun sendReport(state: GamepadState): Boolean {
        if (!isReady()) return false
        return runCatching {
            nativeSendReport(
                state.buttons,
                state.leftX, state.leftY,
                state.rightX, state.rightY,
                state.leftTrigger, state.rightTrigger,
            )
        }.getOrDefault(false)
    }

    fun destroy() {
        if (nativeLoaded) runCatching { nativeDestroy() }
        ready = false
    }

    private external fun nativeCreate(): Boolean
    private external fun nativeSendReport(
        buttons: Int,
        lx: Float, ly: Float,
        rx: Float, ry: Float,
        lt: Float, rt: Float,
    ): Boolean
    private external fun nativeDestroy()
}
