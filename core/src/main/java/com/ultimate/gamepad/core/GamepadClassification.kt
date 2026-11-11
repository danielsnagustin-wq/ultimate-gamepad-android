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
 * 4. uinput: el nombre expuesto depende del perfil activo
 *    (GamepadProfiles: "Xbox 360 Controller", "Xbox One
 *    Controller", "Wireless Controller", "DualSense Wireless
 *    Controller" o "Generic Gamepad"). PROHIBIDO exponer "Virtual"
 *    o "Ultimate Gamepad" como nombre de dispositivo: eso delata
 *    que es virtual y es criterio de FALLO en la Etapa 0, aunque
 *    lleguen paquetes. PROHIBIDOS también KEY_POWER, KEY_HOME,
 *    KEY_VOLUME*, KEY_MENU, KEY_BACK, y Usage Page Consumer (0x0C)
 *    / Remote Control.
 * 5. Descriptor HID Bluetooth: Usage Page 0x01 Generic Desktop,
 *    Usage 0x05 Game Pad (no Joystick ambiguo, no Remote Control
 *    0x0C, no Keyboard 0x07). Colección Application Game Pad,
 *    ejes, hat switch, botones Usage Page 0x09 Button. Hay un
 *    descriptor por perfil (HidGamepadDescriptor.descriptorFor):
 *    Xbox y genérico declaran 12 botones; DS4/DualSense declaran
 *    14 (los 2 extra son los botones del sistema PlayStation y
 *    quedan a 0 en esta versión: el touchpad NO se emula, opción
 *    (a) de la Fase 2).
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
    // BTN_TRIGGER_HAPPY2=0x2C1 (L4, Fase 4), BTN_TRIGGER_HAPPY3=0x2C2 (R4, Fase 4).
    val ALLOWED_EV_KEY: List<Int> = listOf(
        0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x138, 0x139,
        0x13A, 0x13B, 0x13C, 0x13D, 0x13E, 0x220, 0x221, 0x222, 0x223,
        0x2C0, // BTN_TRIGGER_HAPPY1 = Compartir (botón Xbox Share)
        0x2C1, // BTN_TRIGGER_HAPPY2 = L4 (Fase 4)
        0x2C2, // BTN_TRIGGER_HAPPY3 = R4 (Fase 4)
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
    /**
     * Descriptor HID Bluetooth por perfil de emulación (Fase 2).
     *
     * Xbox 360 / Xbox One / genérico: el descriptor de 12 botones
     * de siempre ([BYTES]). DS4 / DualSense: la misma disposición
     * de reporte de 7 bytes de datos, pero declara 14 botones
     * (Usage Maximum 14, Report Count 14 + 2 bits de relleno en
     * vez de 12 + 4): el reporte sigue siendo de 7 bytes de datos
     * y los 2 botones extra (PS / Create) quedan siempre a 0 en
     * esta versión. El touchpad NO está en ningún descriptor:
     * opción (a) del plan, no se implementa en esta versión y se
     * dice claro en la UI y en docs/PLAN-GAMEPAD-PRO.md. La API de
     * BluetoothHidDevice no permite fijar VID/PID: por HID la
     * identidad es nombre + descriptor; la identidad completa
     * (vendor/product) solo la da uinput en la TV.
     *
     * Rumble (ronda REA GFN+OpenNOW, 2026-11-11): el descriptor
     * lleva ADEMÁS una colección Application PID (Usage Page 0x0F,
     * solo fuerza constante, reportes 2-7). Como hay reportes con
     * ID, el reporte de entrada del mando lleva Report ID (1):
     * en el cable son 8 bytes (ID + 7 de datos); la API de
     * BluetoothHidDevice entrega/recibe los 7 datos SIN el ID
     * (el ID va aparte en sendReport/replyReport/onSetReport).
     */
    fun descriptorFor(profileId: Int): ByteArray {
        val profile = GamepadProfiles.fromId(profileId)
        if (profile.id != GamepadProfiles.DS4_ID && profile.id != GamepadProfiles.DUALSENSE_ID) {
            return BYTES.copyOf()
        }
        // Variante Sony: mismo reporte de 7 bytes de datos, 14 botones.
        // Índices en BYTES: [17] = Usage Maximum (12), [25] =
        // Report Count de botones (12), [31] = Report Count del
        // relleno (4). 14 + 2 = 16 bits = los mismos 2 bytes.
        // (+2 respecto a antes: el Report ID (1) ocupa 2 bytes
        // justo después de abrir la colección Application.)
        val bytes = BYTES.copyOf()
        bytes[17] = 0x0E.toByte()
        bytes[25] = 0x0E.toByte()
        bytes[31] = 0x02.toByte()
        return bytes
    }

    val BYTES: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x05.toByte(), // Usage (Game Pad)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        // Report ID (1): al haber reportes PID con ID (2-7), el
        // reporte de entrada también lleva ID; sin él, el host no
        // puede distinguir los reportes. En el cable son 8 bytes
        // (ID + 7 de datos); la API entrega los 7 datos sin el ID.
        0x85.toByte(), 0x01.toByte(), // Report ID (1)
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
        // --- Colección PID: force feedback, solo fuerza constante
        // (USB PID 1.0 §5.1/§6/§9; ver RumbleReport.kt). El teléfono
        // tiene UN motor: fuerte y débil colapsan al mismo valor.
        // Report ID 2: Set Effect (Output, 17 bytes). ID 3: Set
        // Constant Force (Output, 3 bytes). ID 4: Effect Operation
        // (Output, 3 bytes). ID 5: Create New Effect (Feature,
        // 2 bytes). ID 6: PID Block Load (Feature, 4 bytes). ID 7:
        // Device Gain (Feature, 1 byte). Los valores de los arrays
        // nombrados son ordinales (1 = primer selector), no usages.
        0x05.toByte(), 0x0F.toByte(), // Usage Page (Physical Interface Device)
        0x09.toByte(), 0x01.toByte(), // Usage (Physical Interface Device)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        // Set Effect Report, ID 2
        0x09.toByte(), 0x21.toByte(), // Usage (Set Effect Report)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x85.toByte(), 0x02.toByte(), // Report ID (2)
        0x09.toByte(), 0x22.toByte(), // Usage (Effect Block Index)
        0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
        0x75.toByte(), 0x07.toByte(), // Report Size (7)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x09.toByte(), 0x24.toByte(), // Usage (ROM Flag)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x09.toByte(), 0x25.toByte(), // Usage (Effect Type)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x09.toByte(), 0x26.toByte(), // Usage (ET Constant Force)
        0x15.toByte(), 0x01.toByte(), // Logical Minimum (1)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x91.toByte(), 0x00.toByte(), // Output (Data,Ary,Abs)
        0xC0.toByte(), // End Collection
        0x09.toByte(), 0x50.toByte(), // Usage (Duration)
        0x09.toByte(), 0x54.toByte(), // Usage (Trigger Repeat Interval)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x26.toByte(), 0x10.toByte(), 0x27.toByte(), // Logical Maximum (10000)
        0x46.toByte(), 0x10.toByte(), 0x27.toByte(), // Physical Maximum (10000)
        0x75.toByte(), 0x10.toByte(), // Report Size (16)
        0x66.toByte(), 0x03.toByte(), 0x10.toByte(), // Unit (Eng Lin:Time)
        0x55.toByte(), 0x0D.toByte(), // Unit Exponent (-3)
        0x95.toByte(), 0x02.toByte(), // Report Count (2)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x55.toByte(), 0x0A.toByte(), // Unit Exponent (-6)
        0x09.toByte(), 0x51.toByte(), // Usage (Sample Period)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x45.toByte(), 0x00.toByte(), // Physical Maximum (0)
        0x55.toByte(), 0x00.toByte(), // Unit Exponent (0)
        0x65.toByte(), 0x00.toByte(), // Unit (None)
        0x09.toByte(), 0x52.toByte(), // Usage (Gain)
        0x09.toByte(), 0x53.toByte(), // Usage (Trigger Button)
        0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x02.toByte(), // Report Count (2)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x09.toByte(), 0x55.toByte(), // Usage (Axes Enable)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x01.toByte(), // Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), // Collection (Physical)
        0x09.toByte(), 0x30.toByte(), // Usage (X)
        0x09.toByte(), 0x31.toByte(), // Usage (Y)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x95.toByte(), 0x02.toByte(), // Report Count (2)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0xC0.toByte(), // End Collection
        0xC0.toByte(), // End Collection
        0x95.toByte(), 0x06.toByte(), // Report Count (6) padding
        0x91.toByte(), 0x03.toByte(), // Output (Const,Var,Abs)
        0x05.toByte(), 0x0F.toByte(), // Usage Page (Physical Interface)
        0x09.toByte(), 0x57.toByte(), // Usage (Direction)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x01.toByte(), // Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), // Collection (Physical)
        0x09.toByte(), 0x30.toByte(), // Usage (X)
        0x09.toByte(), 0x31.toByte(), // Usage (Y)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), // Logical Maximum (255)
        0x46.toByte(), 0x68.toByte(), 0x01.toByte(), // Physical Maximum (360)
        0x66.toByte(), 0x14.toByte(), 0x00.toByte(), // Unit (Eng Rot:Angular Pos)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x02.toByte(), // Report Count (2)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x65.toByte(), 0x00.toByte(), // Unit (None)
        0x45.toByte(), 0x00.toByte(), // Physical Maximum (0)
        0xC0.toByte(), // End Collection
        0xC0.toByte(), // End Collection
        0x05.toByte(), 0x0F.toByte(), // Usage Page (Physical Interface)
        0x09.toByte(), 0x58.toByte(), // Usage (Type Specific Block Offset)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x0B.toByte(), 0x01.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x00.toByte(), // Usage (Ordinals:Instance 1)
        0x0B.toByte(), 0x02.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x00.toByte(), // Usage (Ordinals:Instance 2)
        0x26.toByte(), 0xFD.toByte(), 0x7F.toByte(), // Logical Maximum (32765)
        0x75.toByte(), 0x10.toByte(), // Report Size (16)
        0x95.toByte(), 0x02.toByte(), // Report Count (2)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0xC0.toByte(), // End Collection
        0xC0.toByte(), // End Collection (Set Effect Report)
        // Set Constant Force Report, ID 3
        0x09.toByte(), 0x73.toByte(), // Usage (Set Constant Force Report)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x85.toByte(), 0x03.toByte(), // Report ID (3)
        0x09.toByte(), 0x23.toByte(), // Usage (Parameter Block Offset)
        0x26.toByte(), 0xFD.toByte(), 0x7F.toByte(), // Logical Maximum (32765)
        0x75.toByte(), 0x0F.toByte(), // Report Size (15)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x09.toByte(), 0x24.toByte(), // Usage (ROM Flag)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x09.toByte(), 0x70.toByte(), // Usage (Magnitude)
        0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), // Logical Maximum (255)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0xC0.toByte(), // End Collection
        // Effect Operation Report, ID 4
        0x09.toByte(), 0x77.toByte(), // Usage (Effect Operation Report)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x85.toByte(), 0x04.toByte(), // Report ID (4)
        0x09.toByte(), 0x22.toByte(), // Usage (Effect Block Index)
        0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
        0x75.toByte(), 0x07.toByte(), // Report Size (7)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x09.toByte(), 0x24.toByte(), // Usage (ROM Flag)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0x09.toByte(), 0x78.toByte(), // Usage (Effect Operation)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x09.toByte(), 0x79.toByte(), // Usage (Op Effect Start)
        0x09.toByte(), 0x7A.toByte(), // Usage (Op Effect Start Solo)
        0x09.toByte(), 0x7B.toByte(), // Usage (Op Effect Stop)
        0x15.toByte(), 0x01.toByte(), // Logical Minimum (1)
        0x25.toByte(), 0x03.toByte(), // Logical Maximum (3)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x91.toByte(), 0x00.toByte(), // Output (Data,Ary,Abs)
        0xC0.toByte(), // End Collection
        0x09.toByte(), 0x7C.toByte(), // Usage (Loop Count)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0xFF.toByte(), // Logical Maximum (255)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x91.toByte(), 0x02.toByte(), // Output (Data,Var,Abs)
        0xC0.toByte(), // End Collection
        // Create New Effect Report, ID 5 (Feature, §9)
        0x09.toByte(), 0xAB.toByte(), // Usage (Create New Effect Report)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x85.toByte(), 0x05.toByte(), // Report ID (5)
        0x09.toByte(), 0x25.toByte(), // Usage (Effect Type)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x09.toByte(), 0x26.toByte(), // Usage (ET Constant Force)
        0x15.toByte(), 0x01.toByte(), // Logical Minimum (1)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0xB1.toByte(), 0x00.toByte(), // Feature (Data,Ary,Abs)
        0xC0.toByte(), // End Collection
        0x0B.toByte(), 0x3B.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), // Usage (Generic Desktop:Byte Count)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), // Logical Maximum (255)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0xB1.toByte(), 0x02.toByte(), // Feature (Data,Var,Abs)
        0xC0.toByte(), // End Collection
        // PID Block Load Report, ID 6 (Feature, §9)
        0x09.toByte(), 0x89.toByte(), // Usage (PID Block Load Report)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x85.toByte(), 0x06.toByte(), // Report ID (6)
        0x09.toByte(), 0x22.toByte(), // Usage (Effect Block Index)
        0x15.toByte(), 0x01.toByte(), // Logical Minimum (1)
        0x25.toByte(), 0x0A.toByte(), // Logical Maximum (10)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0xB1.toByte(), 0x02.toByte(), // Feature (Data,Var,Abs)
        0x09.toByte(), 0x8B.toByte(), // Usage (Block Load Status)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x09.toByte(), 0x8C.toByte(), // Usage (Block Load Success)
        0x09.toByte(), 0x8D.toByte(), // Usage (Block Load Full)
        0x09.toByte(), 0x8E.toByte(), // Usage (Block Load Error)
        0x15.toByte(), 0x01.toByte(), // Logical Minimum (1)
        0x25.toByte(), 0x03.toByte(), // Logical Maximum (3)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0xB1.toByte(), 0x00.toByte(), // Feature (Data,Ary,Abs)
        0xC0.toByte(), // End Collection
        0x09.toByte(), 0xAC.toByte(), // Usage (RAM Pool Available)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0xFF.toByte(), // Logical Maximum (65535)
        0x75.toByte(), 0x10.toByte(), // Report Size (16)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0xB1.toByte(), 0x02.toByte(), // Feature (Data,Var,Abs)
        0xC0.toByte(), // End Collection
        // Device Gain Report, ID 7 (Feature)
        0x09.toByte(), 0x7D.toByte(), // Usage (Device Gain Report)
        0xA1.toByte(), 0x02.toByte(), // Collection (Logical)
        0x85.toByte(), 0x07.toByte(), // Report ID (7)
        0x09.toByte(), 0x7E.toByte(), // Usage (Device Gain)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), // Logical Maximum (255)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0xB1.toByte(), 0x02.toByte(), // Feature (Data,Var,Abs)
        0xC0.toByte(), // End Collection
        0xC0.toByte(), // End Collection (PID Application)
    )
}
