package com.ultimate.gamepad.core

/**
 * CLASIFICACIÓN OBLIGATORIA COMO MANDO DE JUEGO — Etapa 1.
 *
 * Bug reportado por el usuario (2026-11-07): probó una app de
 * gamepad de Play Store y la TV la reconoció como si fuera un
 * control de TV / mando a distancia, no como mando de juego.
 * OpenNOW / GeForce NOW entonces solo la usa para navegar menús,
 * no para jugar. Eso es exactamente lo que NO debe pasar aquí.
 *
 * Causa probable de esa app: enviaba eventos con SOURCE_DPAD /
 * SOURCE_KEYBOARD, o un descriptor HID genérico / de control
 * remoto, y Android la clasificó como mando a distancia.
 *
 * REGLAS — valen para TODOS los transportes e inyecciones:
 *
 * 1. Fuente de eventos: siempre SOURCE_GAMEPAD | SOURCE_JOYSTICK.
 *    Nunca SOURCE_DPAD solo, nunca SOURCE_KEYBOARD, nunca
 *    SOURCE_TOUCHSCREEN para botones/sticks.
 * 2. KeyEvents: KEYCODE_BUTTON_A/B/X/Y, BUTTON_L1/R1, BUTTON_L2/R2
 *    (gatillos digitales si hace falta), BUTTON_START/SELECT,
 *    BUTTON_THUMBL/R, y la cruceta como KEYCODE_DPAD_UP/DOWN/
 *    LEFT/RIGHT PERO siempre con source GAMEPAD (nunca con
 *    SOURCE_DPAD solo) y además por AXIS_HAT_X/Y. Nunca
 *    KEYCODE_ENTER ni KEYCODE_BACK para jugar.
 * 3. MotionEvents: AXIS_X/AXIS_Y (stick izq), AXIS_Z/AXIS_RZ
 *    (stick der), AXIS_LTRIGGER/AXIS_RTRIGGER, AXIS_HAT_X/
 *    AXIS_HAT_Y (cruceta), siempre con SOURCE_JOYSTICK.
 * 4. uinput: nombre expuesto ÚNICAMENTE "Xbox 360 Controller".
 *    PROHIBIDO exponer "Virtual" o "Ultimate Gamepad" como nombre
 *    de dispositivo: eso delata que es virtual y es criterio de
 *    FALLO en la Etapa 0, aunque lleguen paquetes.
 *    KEY_POWER, KEY_HOME, KEY_VOLUME*, KEY_MENU, KEY_BACK, y
 *    Usage Page Consumer (0x0C) / Remote Control.
 * 5. Descriptor HID Bluetooth: Usage Page 0x01 Generic Desktop,
 *    Usage 0x05 Game Pad (no Joystick ambiguo, no Remote Control
 *    0x0C, no Keyboard 0x07). Colección Application Game Pad,
 *    ejes, hat switch, botones Usage Page 0x09 Button.
 *
 * En Android, SOURCE_GAMEPAD = 0x400 (1024) y SOURCE_JOYSTICK =
 * 0x1000000 (16777216). Se definen aquí como constantes para que
 * :core pueda validarlos sin depender de android.view.InputDevice
 * (que no existe en :core puro); las apps Android usan los valores
 * de InputDevice, que son los mismos.
 */
object GamepadClassification {
    const val SOURCE_GAMEPAD: Int = 0x00000400
    const val SOURCE_JOYSTICK: Int = 0x01000000
    const val SOURCE_GAMEPAD_JOYSTICK: Int = SOURCE_GAMEPAD or SOURCE_JOYSTICK
    // Fuentes PROHIBIDAS para eventos de juego (causan "control de TV").
    const val SOURCE_DPAD: Int = 0x00000200
    const val SOURCE_KEYBOARD: Int = 0x00000100
    const val SOURCE_TOUCHSCREEN: Int = 0x00001000

    fun isGamepadSource(sources: Int): Boolean =
        (sources and SOURCE_GAMEPAD) != 0 || (sources and SOURCE_JOYSTICK) != 0

    fun hasForbiddenSourceOnly(sources: Int): Boolean =
        !isGamepadSource(sources) &&
            ((sources and SOURCE_DPAD) != 0 || (sources and SOURCE_KEYBOARD) != 0)
}

/** Especificación exacta del mando virtual uinput Xbox 360. */
object UinputSpec {
    const val DEVICE_NAME: String = "Xbox 360 Controller"
    // No hay nombre alternativo expuesto. "Ultimate Gamepad" es el
    // nombre de la app, nunca del dispositivo: exponerlo es FALLO.
    const val VENDOR_ID: Int = 0x045E // Microsoft
    const val PRODUCT_ID: Int = 0x028E // Xbox 360 Controller
    const val BUSTYPE_USB: Int = 0x03 // BUS_USB
    const val VERSION: Int = 0x0114

    // Solo EV_KEY de mando (códigos evdev / linux/input-event-codes.h).
    // BTN_A=0x130, BTN_B=0x131, BTN_X=0x133, BTN_Y=0x134,
    // BTN_TL=0x136, BTN_TR=0x137, BTN_TL2=0x138, BTN_TR2=0x139,
    // BTN_SELECT=0x13A, BTN_START=0x13B, BTN_THUMBL=0x13D, BTN_THUMBR=0x13E,
    // BTN_DPAD_UP=0x220, DOWN=0x221, LEFT=0x222, RIGHT=0x223.
    val ALLOWED_EV_KEY: List<Int> = listOf(
        0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x138, 0x139,
        0x13A, 0x13B, 0x13C, 0x13D, 0x13E, 0x220, 0x221, 0x222, 0x223,
        0x2C0, // BTN_TRIGGER_HAPPY1 = Compartir (botón Xbox Share)
    )
    // PROHIBIDO: KEY_POWER 0x74, KEY_HOME 0x66, KEY_VOLUMEUP 0x73,
    // KEY_VOLUMEDOWN 0x72, KEY_MENU 0x8B, KEY_BACK 0x9E, y cualquier
    // tecla de Usage Page Consumer / Remote Control.
    val FORBIDDEN_EV_KEY: List<Int> = listOf(0x74, 0x66, 0x73, 0x72, 0x8B, 0x9E)

    // Solo EV_ABS de mando, con rangos correctos.
    // ABS_X=0x00, ABS_Y=0x01, ABS_Z=0x02, ABS_RX=0x03, ABS_RY=0x04,
    // ABS_RZ=0x05, ABS_HAT0X=0x10, ABS_HAT0Y=0x11.
    data class AbsAxis(val code: Int, val min: Int, val max: Int, val flat: Int = 0, val fuzz: Int = 0)
    val ALLOWED_EV_ABS: List<AbsAxis> = listOf(
        AbsAxis(0x00, -32768, 32767), // ABS_X stick izq
        AbsAxis(0x01, -32768, 32767), // ABS_Y stick izq
        AbsAxis(0x03, -32768, 32767), // ABS_RX stick der
        AbsAxis(0x04, -32768, 32767), // ABS_RY stick der
        AbsAxis(0x02, 0, 255),        // ABS_Z gatillo LT
        AbsAxis(0x05, 0, 255),        // ABS_RZ gatillo RT
        AbsAxis(0x10, -1, 1),         // ABS_HAT0X cruceta
        AbsAxis(0x11, -1, 1),         // ABS_HAT0Y cruceta
    )
}

/**
 * Descriptor HID Bluetooth de Game Pad.
 * Usage Page 0x01 Generic Desktop, Usage 0x05 Game Pad.
 * Colección Application (Game Pad), botones Usage Page 0x09,
 * hat switch, ejes X/Y/Z/Rz. NO incluye Usage Page 0x0C
 * (Consumer), 0x07 (Keyboard) ni 0x0C Remote Control.
 *
 * Descriptor estándar de gamepad genérico, usado por proyectos
 * open-source de BluetoothHidDevice. La Etapa 0 debe validarlo
 * contra la Hyundai: si la TV lo rechaza o lo clasifica mal,
 * se ajusta aquí y solo aquí (un solo lugar, no en cada transporte).
 */
object HidGamepadDescriptor {
    val BYTES: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x05.toByte(), // Usage (Game Pad)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
        0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
        0x19.toByte(), 0x01.toByte(), //     Usage Minimum (1)
        0x29.toByte(), 0x0C.toByte(), //     Usage Maximum (12)
        0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), //     Report Size (1)
        0x95.toByte(), 0x0C.toByte(), //     Report Count (12)
        0x81.toByte(), 0x02.toByte(), //     Input (Data,Var,Abs)
        0x75.toByte(), 0x01.toByte(), //     Report Size (1)
        0x95.toByte(), 0x04.toByte(), //     Report Count (4) padding
        0x81.toByte(), 0x03.toByte(), //     Input (Const,Var,Abs)
        0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x39.toByte(), //     Usage (Hat switch)
        0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
        0x25.toByte(), 0x07.toByte(), //     Logical Maximum (7)
        0x35.toByte(), 0x00.toByte(), //     Physical Minimum (0)
        0x46.toByte(), 0x3B.toByte(), 0x01.toByte(), // Physical Maximum (315)
        0x65.toByte(), 0x14.toByte(), //     Unit (Degrees)
        0x75.toByte(), 0x04.toByte(), //     Report Size (4)
        0x95.toByte(), 0x01.toByte(), //     Report Count (1)
        0x81.toByte(), 0x02.toByte(), //     Input (Data,Var,Abs)
        0x75.toByte(), 0x04.toByte(), //     Report Size (4) padding
        0x95.toByte(), 0x01.toByte(), //     Report Count (1)
        0x81.toByte(), 0x03.toByte(), //     Input (Const,Var,Abs)
        0x09.toByte(), 0x01.toByte(), //     Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), //     Collection (Physical)
        0x09.toByte(), 0x30.toByte(), //       Usage (X)
        0x09.toByte(), 0x31.toByte(), //       Usage (Y)
        0x09.toByte(), 0x32.toByte(), //       Usage (Z)
        0x09.toByte(), 0x35.toByte(), //       Usage (Rz)
        0x15.toByte(), 0x81.toByte(), //       Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), //       Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), //       Report Size (8)
        0x95.toByte(), 0x04.toByte(), //       Report Count (4)
        0x81.toByte(), 0x02.toByte(), //       Input (Data,Var,Abs)
        0xC0.toByte(), //     End Collection
        0xC0.toByte(), //   End Collection
        0xC0.toByte(), // End Collection
    )
}
