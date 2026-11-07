package com.ultimate.gamepad.tv.shizuku

import com.ultimate.gamepad.core.GamepadClassification
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.UinputSpec

/**
 * Mando virtual vía /dev/uinput — implementación nativa
 * (app-tv/src/main/cpp/uinput_gamepad.c, compilada con CMake/NDK).
 *
 * Especificación EXACTA que cumple la implementación nativa
 * (ver GamepadProfiles en :core — una sola fuente de verdad,
 * y UinputSpec para el Xbox 360):
 * - Nombre expuesto según el perfil activo: "Xbox 360 Controller"
 *   (0x045E/0x028E), "Xbox One Controller" (0x045E/0x02DD),
 *   "Wireless Controller" DS4 (0x054C/0x05C4), "DualSense Wireless
 *   Controller" (0x054C/0x0CE6) o "Generic Gamepad" (0x1234/0x5678).
 *   PROHIBIDO exponer "Virtual" o "Ultimate Gamepad" como nombre
 *   de dispositivo: eso delata que es virtual y es FALLO en la
 *   Etapa 0, aunque lleguen paquetes por Wi-Fi.
 * - bustype BUS_USB (0x03), version 0x0114
 * - Solo EV_KEY de mando: BTN_A/B/X/Y, BTN_TL/TR, BTN_TL2/TR2,
 *   BTN_START/SELECT, BTN_MODE (guía), BTN_THUMBL/R,
 *   BTN_DPAD_UP/DOWN/LEFT/RIGHT, BTN_TRIGGER_HAPPY1 (Compartir),
 *   BTN_TRIGGER_HAPPY2 (L4, Fase 4), BTN_TRIGGER_HAPPY3 (R4, Fase 4)
 * - Solo EV_ABS: ABS_X/Y (-32768..32767, stick izq),
 *   ABS_RX/RY (-32768..32767, stick der), ABS_Z/RZ (0..255,
 *   gatillos LT/RT), ABS_HAT0X/HAT0Y (-1..1, cruceta)
 * - PROHIBIDO: KEY_POWER, KEY_HOME, KEY_VOLUME*, KEY_MENU,
 *   KEY_BACK, y Usage Page Consumer (0x0C) / Remote Control.
 *   Incluir una sola de esas teclas es lo que hace que Android
 *   clasifique el dispositivo como control de TV en vez de mando.
 *
 * Cambio de perfil (Fase 2): create(profileId) crea el dispositivo
 * con ese perfil; recreate(profileId) destruye el actual y crea el
 * nuevo (cambio en caliente). El touchpad de DS4/DualSense NO se
 * emula (opción (a) del plan).
 *
 * Teclado virtual y ratón (Fase 4): OTROS dispositivos uinput
 * SEPARADOS del mando, con su propio nombre y vendor/product
 * ("Ultimate Gamepad Keyboard" 0x1234/0x5679 y "Ultimate Gamepad
 * Mouse" 0x1234/0x567A), creados a demanda por keyboardEvent() /
 * mouseEvent() y destruidos con destroyExtras(). Nunca se mezclan
 * sus teclas con el mando: esa es la regla que evita que Android
 * reclasifique el conjunto como control de TV.
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
    /** Perfil del dispositivo virtual creado (GamepadProfiles); 0 = Xbox 360. */
    @Volatile var activeProfileId: Int = GamepadProfiles.XBOX_360_ID
        private set

    init {
        nativeLoaded = runCatching {
            System.loadLibrary("uinput_gamepad")
            true
        }.getOrDefault(false)
    }

    fun specSummary(): String {
        val profile = GamepadProfiles.fromId(activeProfileId)
        return "${profile.deviceName} vendor=0x${profile.vendorId.toString(16)} " +
            "product=0x${profile.productId.toString(16)} bustype=BUS_USB " +
            "EV_KEY=${UinputSpec.ALLOWED_EV_KEY.size} botones de mando, " +
            "EV_ABS=${UinputSpec.ALLOWED_EV_ABS.size} ejes, prohibidas=${UinputSpec.FORBIDDEN_EV_KEY.size} teclas de remoto"
    }

    fun isSpecValid(): Boolean =
        UinputSpec.ALLOWED_EV_KEY.none { it in UinputSpec.FORBIDDEN_EV_KEY } &&
            GamepadClassification.isGamepadSource(GamepadClassification.SOURCE_GAMEPAD_JOYSTICK)

    /** ¿Se cargó el .so nativo? Sin él no hay mando virtual. */
    fun isNativeLoaded(): Boolean = nativeLoaded

    /** Crea el mando virtual con el perfil dado (0 = Xbox 360 por defecto). */
    fun create(profileId: Int = GamepadProfiles.XBOX_360_ID): Boolean {
        if (!nativeLoaded) return false
        val id = GamepadProfiles.fromId(profileId).id
        ready = runCatching { nativeCreate(id) }.getOrDefault(false)
        if (ready) activeProfileId = id
        return ready
    }

    /** Cambio de perfil en caliente: destruye el dispositivo actual y crea el nuevo. */
    fun recreate(profileId: Int): Boolean {
        destroy()
        return create(profileId)
    }

    fun isReady(): Boolean = ready && nativeLoaded

    /** Envía un estado ya decodificado al mando virtual. Solo si está listo. L4/R4 (Fase 4) van en state.extraButtons. */
    fun sendReport(state: GamepadState): Boolean {
        if (!isReady()) return false
        return runCatching {
            nativeSendReport(
                state.buttons, state.extraButtons,
                state.leftX, state.leftY,
                state.rightX, state.rightY,
                state.leftTrigger, state.rightTrigger,
            )
        }.getOrDefault(false)
    }

    /**
     * Teclado virtual (Fase 4): pulsa/suelta una tecla evdev en el
     * dispositivo de teclado aparte (lo crea a demanda). [action]:
     * 0 = pulsar, 1 = soltar, 2 = crear, 3 = destruir.
     * Devuelve false si el .so no cargó o /dev/uinput no se pudo abrir.
     */
    fun keyboardEvent(keyCode: Int, action: Int): Boolean {
        if (!nativeLoaded) return false
        return runCatching { nativeKeyboardEvent(keyCode, action) }.getOrDefault(false)
    }

    /**
     * Modo ratón (Fase 4): mueve el cursor y actualiza los botones
     * en el dispositivo de ratón aparte (lo crea a demanda).
     * [buttons]: bit 0 = izquierdo, bit 1 = derecho, bit 2 =
     * central. [action]: 0 = mover/clic, 1 = crear, 2 = destruir.
     */
    fun mouseEvent(dx: Int, dy: Int, buttons: Int, action: Int): Boolean {
        if (!nativeLoaded) return false
        return runCatching { nativeMouseEvent(dx, dy, buttons, action) }.getOrDefault(false)
    }

    /** Destruye los dispositivos de teclado y ratón (Fase 4). El mando no se toca. */
    fun destroyExtras() {
        if (nativeLoaded) runCatching { nativeDestroyExtras() }
    }

    fun destroy() {
        if (nativeLoaded) runCatching { nativeDestroy() }
        ready = false
        activeProfileId = GamepadProfiles.XBOX_360_ID
    }

    private external fun nativeCreate(profileId: Int): Boolean
    private external fun nativeSendReport(
        buttons: Int,
        extraButtons: Int,
        lx: Float, ly: Float,
        rx: Float, ry: Float,
        lt: Float, rt: Float,
    ): Boolean
    private external fun nativeKeyboardEvent(keyCode: Int, action: Int): Boolean
    private external fun nativeMouseEvent(dx: Int, dy: Int, buttons: Int, action: Int): Boolean
    private external fun nativeDestroyExtras()
    private external fun nativeDestroy()
}
