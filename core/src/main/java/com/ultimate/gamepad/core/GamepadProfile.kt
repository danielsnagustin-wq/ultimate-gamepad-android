package com.ultimate.gamepad.core

/**
 * Perfil de emulación de mando — Fase 2 (ULTIMATE GAMEPAD PRO).
 *
 * Distinción obligatoria (docs/PLAN-GAMEPAD-PRO.md §5):
 * - Perfil VISUAL: cómo se dibuja el mando en el teléfono (colores y
 *   etiquetas). Cambiarlo solo cambia la UI.
 * - Perfil de EMULACIÓN: qué dispositivo virtual crea la TV (nombre,
 *   vendor/product, descriptor HID, tabla de botones/ejes). Cambiarlo
 *   recrea el dispositivo uinput y cambia el descriptor HID. Es lo que
 *   OpenNOW ve.
 * Regla: TIPO DE MANDO (pantalla de la Fase 3) cambia los dos a la
 * vez; nunca se muestra un mando de PlayStation mientras la TV expone
 * un Xbox 360. El perfil activo se guarda en el teléfono
 * (GamepadProfilePrefs) y se envía en cada paquete en el byte
 * reservado del protocolo (offset 28); la TV lo aplica al conectar y
 * recrea el dispositivo si cambia.
 *
 * GamepadState NO cambia: el estado (botones, sticks, gatillos) es
 * neutro y el mismo para todos los perfiles; el perfil solo dice
 * CÓMO se expone ese estado (nombre, VID/PID, etiquetas).
 *
 * IDs (byte reservado del protocolo, offset 28):
 *   0 = Xbox 360, 1 = Xbox One, 2 = DualShock 4, 3 = DualSense,
 *   4 = genérico. Los paquetes viejos llevan 0 y siguen siendo
 *   Xbox 360 (compatible hacia atrás, sin subir a protocolo v2).
 *
 * Touchpad (DS4/DualSense): opción (a) del plan — NO implementado en
 * esta versión y se dice claro: el touchpad no es necesario para
 * jugar a GeForce NOW y muchos juegos no lo usan. Implementarlo
 * exigiría un segundo dispositivo uinput táctil (BTN_TOUCH +
 * ABS_X/ABS_Y de toque), trabajo aparte. Tampoco se promete háptica
 * avanzada ni gatillos adaptativos del DualSense: necesitan reportes
 * HID feature que uinput no expone.
 *
 * L4/R4 NO es Fase 2 (es Fase 4): el bitmask de botones está lleno
 * (16/16 bits) y NO se toca en esta fase.
 */
data class GamepadProfile(
    val id: Int,
    /** Nombre corto para la UI, p. ej. "Xbox 360". */
    val name: String,
    /** Nombre EXACTO que expone el dispositivo virtual (uinput / HID). */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val version: Int = 0x0114,
    /** Etiqueta de cada botón (clave = bit de GamepadButtons) para la UI. */
    val buttonLabels: Map<Int, String>,
    /** El mando físico tiene touchpad (DS4/DualSense). */
    val hasTouchpad: Boolean = false,
    /**
     * El touchpad NO se emula en esta versión (opción (a) del plan).
     * En el teléfono el botón de captura se etiqueta como no
     * disponible en DS4; en DualSense es el botón Create.
     */
    val touchpadImplemented: Boolean = false,
)

object GamepadProfiles {
    const val XBOX_360_ID: Int = 0
    const val XBOX_ONE_ID: Int = 1
    const val DS4_ID: Int = 2
    const val DUALSENSE_ID: Int = 3
    const val GENERIC_ID: Int = 4

    private val xboxLabels: Map<Int, String> = mapOf(
        GamepadButtons.A to "A",
        GamepadButtons.B to "B",
        GamepadButtons.X to "X",
        GamepadButtons.Y to "Y",
        GamepadButtons.LB to "LB",
        GamepadButtons.RB to "RB",
        GamepadButtons.START to "Start",
        GamepadButtons.SELECT to "Back",
        GamepadButtons.L3 to "L3",
        GamepadButtons.R3 to "R3",
        GamepadButtons.DPAD_UP to "Arriba",
        GamepadButtons.DPAD_DOWN to "Abajo",
        GamepadButtons.DPAD_LEFT to "Izquierda",
        GamepadButtons.DPAD_RIGHT to "Derecha",
        GamepadButtons.HOME to "Xbox",
        GamepadButtons.CAPTURE to "Share",
    )

    val XBOX_360: GamepadProfile = GamepadProfile(
        id = XBOX_360_ID,
        name = "Xbox 360",
        deviceName = "Xbox 360 Controller",
        vendorId = 0x045E, // Microsoft
        productId = 0x028E,
        buttonLabels = xboxLabels,
    )

    val XBOX_ONE: GamepadProfile = GamepadProfile(
        id = XBOX_ONE_ID,
        name = "Xbox One",
        deviceName = "Xbox One Controller",
        vendorId = 0x045E, // Microsoft
        productId = 0x02DD,
        buttonLabels = xboxLabels + mapOf(
            GamepadButtons.START to "Menu",
            GamepadButtons.SELECT to "View",
        ),
    )

    private val playstationLabels: Map<Int, String> = mapOf(
        GamepadButtons.A to "Cross",
        GamepadButtons.B to "Circle",
        GamepadButtons.X to "Square",
        GamepadButtons.Y to "Triangle",
        GamepadButtons.LB to "L1",
        GamepadButtons.RB to "R1",
        GamepadButtons.START to "Options",
        GamepadButtons.SELECT to "Share",
        GamepadButtons.L3 to "L3",
        GamepadButtons.R3 to "R3",
        GamepadButtons.DPAD_UP to "Arriba",
        GamepadButtons.DPAD_DOWN to "Abajo",
        GamepadButtons.DPAD_LEFT to "Izquierda",
        GamepadButtons.DPAD_RIGHT to "Derecha",
        GamepadButtons.HOME to "PS",
        GamepadButtons.CAPTURE to "Touchpad (no disponible)",
    )

    val DS4: GamepadProfile = GamepadProfile(
        id = DS4_ID,
        name = "DualShock 4",
        deviceName = "Wireless Controller",
        vendorId = 0x054C, // Sony
        productId = 0x05C4,
        buttonLabels = playstationLabels,
        hasTouchpad = true,
    )

    val DUALSENSE: GamepadProfile = GamepadProfile(
        id = DUALSENSE_ID,
        name = "DualSense",
        deviceName = "DualSense Wireless Controller",
        vendorId = 0x054C, // Sony
        productId = 0x0CE6,
        buttonLabels = playstationLabels + mapOf(
            GamepadButtons.CAPTURE to "Create",
        ),
        hasTouchpad = true,
    )

    val GENERIC: GamepadProfile = GamepadProfile(
        id = GENERIC_ID,
        name = "Genérico",
        deviceName = "Generic Gamepad",
        // VID/PID neutros, sin identidad de Microsoft ni de Sony.
        vendorId = 0x1234,
        productId = 0x5678,
        buttonLabels = mapOf(
            GamepadButtons.A to "Botón A",
            GamepadButtons.B to "Botón B",
            GamepadButtons.X to "Botón X",
            GamepadButtons.Y to "Botón Y",
            GamepadButtons.LB to "LB",
            GamepadButtons.RB to "RB",
            GamepadButtons.START to "Start",
            GamepadButtons.SELECT to "Select",
            GamepadButtons.L3 to "L3",
            GamepadButtons.R3 to "R3",
            GamepadButtons.DPAD_UP to "Arriba",
            GamepadButtons.DPAD_DOWN to "Abajo",
            GamepadButtons.DPAD_LEFT to "Izquierda",
            GamepadButtons.DPAD_RIGHT to "Derecha",
            GamepadButtons.HOME to "Inicio",
            GamepadButtons.CAPTURE to "Captura",
        ),
    )

    val ALL: List<GamepadProfile> = listOf(XBOX_360, XBOX_ONE, DS4, DUALSENSE, GENERIC)

    /** Un id desconocido o corrupto (el byte no va cubierto por el CRC) es Xbox 360. */
    fun fromId(id: Int): GamepadProfile = ALL.firstOrNull { it.id == id } ?: XBOX_360

    fun isValidId(id: Int): Boolean = ALL.any { it.id == id }

    /** Etiqueta de un botón en un perfil; si falta, el nombre del bit. */
    fun labelFor(profileId: Int, button: Int): String =
        fromId(profileId).buttonLabels[button] ?: "Botón $button"
}
