package com.ultimate.gamepad.tv.shizuku

import com.ultimate.gamepad.core.GamepadClassification
import com.ultimate.gamepad.core.UinputSpec

/**
 * Mando virtual Xbox 360 vía /dev/uinput — PENDIENTE DE ETAPA 0.
 *
 * Especificación EXACTA que debe cumplir la implementación nativa
 * (ver UinputSpec en :core — una sola fuente de verdad):
 * - Nombre expuesto: ÚNICAMENTE "Xbox 360 Controller".
 *   PROHIBIDO exponer "Virtual" o "Ultimate Gamepad" como nombre
 *   de dispositivo: eso delata que es virtual y es FALLO en la
 *   Etapa 0, aunque lleguen paquetes por Wi-Fi.
 * - vendor 0x045E (Microsoft), product 0x028E, bustype BUS_USB (0x03), version 0x0114
 * - Solo EV_KEY de mando: BTN_A/B/X/Y, BTN_TL/TR, BTN_TL2/TR2,
 *   BTN_START/SELECT, BTN_THUMBL/R, BTN_DPAD_UP/DOWN/LEFT/RIGHT
 * - Solo EV_ABS: ABS_X/Y (-32768..32767, stick izq),
 *   ABS_RX/RY (-32768..32767, stick der), ABS_Z/RZ (0..255,
 *   gatillos LT/RT), ABS_HAT0X/HAT0Y (-1..1, cruceta)
 * - PROHIBIDO: KEY_POWER, KEY_HOME, KEY_VOLUME*, KEY_MENU,
 *   KEY_BACK, y Usage Page Consumer (0x0C) / Remote Control.
 *   Incluir una sola de esas teclas es lo que hace que Android
 *   clasifique el dispositivo como control de TV en vez de mando.
 *
 * Por qué este archivo devuelve false en Etapa 1:
 * - Abrir /dev/uinput e ioctl (UI_DEV_SETUP / UI_DEV_CREATE) solo
 *   se puede desde código nativo (JNI, libevdev) en el proceso
 *   shell (Shizuku). Kotlin puro no puede hacer ioctl.
 * - El proyecto de referencia Virtual-Xbox-Controller-Android hace
 *   exactamente eso desde su UserService Shizuku. Reutilizar su
 *   enfoque es el trabajo de la Etapa 0: compilar su .so, probarlo
 *   en la Hyundai y solo entonces poner isReady() = true.
 *
 * NO se finge aquí: create() devuelve false y isReady() false hasta
 * que la Etapa 0 demuestre en la TV real que el mando aparece en
 * Ajustes > Mandos y en Diagnostics.kt como "MANDO DE JUEGO".
 * Mientras tanto el receptor usa injectInputEvent (inyección B)
 * y la UI lo muestra como tal.
 */
object UinputGamepad {
    val spec: UinputSpec = UinputSpec
    fun specSummary(): String =
        "${UinputSpec.DEVICE_NAME} vendor=0x045E product=0x028E bustype=BUS_USB " +
            "EV_KEY=${UinputSpec.ALLOWED_EV_KEY.size} botones de mando, " +
            "EV_ABS=${UinputSpec.ALLOWED_EV_ABS.size} ejes, prohibidas=${UinputSpec.FORBIDDEN_EV_KEY.size} teclas de remoto"
    fun isSpecValid(): Boolean =
        UinputSpec.ALLOWED_EV_KEY.none { it in UinputSpec.FORBIDDEN_EV_KEY } &&
            GamepadClassification.isGamepadSource(GamepadClassification.SOURCE_GAMEPAD_JOYSTICK)
    fun create(): Boolean = false
    fun isReady(): Boolean = false
    fun destroy() { /* nada que destruir hasta Etapa 0 */ }
}
