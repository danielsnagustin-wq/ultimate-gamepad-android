package com.ultimate.gamepad.tv.receiver

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ultimate.gamepad.core.GamepadClassification
import com.ultimate.gamepad.core.GamepadProfiles

/**
 * Diagnóstico "¿Cómo me ve Android?" — Etapa 1.
 *
 * Es la pantalla que evita adivinar en la Etapa 0. Lee los
 * InputDevice reales del sistema con InputDevice.getDeviceIds()
 * y para cada uno muestra nombre, vendor/product, getSources()
 * en texto, si incluye SOURCE_GAMEPAD y SOURCE_JOYSTICK, y la
 * lista de ejes disponibles.
 *
 * Veredicto por dispositivo:
 * - "Reconocido como: MANDO DE JUEGO" si tiene SOURCE_GAMEPAD o
 *   SOURCE_JOYSTICK, ejes AXIS_X/AXIS_Y y nombre de mando
 *   (Xbox / Ultimate Gamepad / gamepad), y NO es solo DPAD/KEYBOARD.
 *   NUEVO — "Parece mando real": SÍ solo si TODAS estas se cumplen
 *   a la vez (comprobación, no opinión de la app):
 *   nombre exacto del perfil de emulación activo (Fase 2: Xbox 360,
 *   Xbox One, DS4, DualSense o genérico; por defecto Xbox 360), sin
 *   "Virtual" ni "Ultimate" en el nombre, sources incluyen GAMEPAD
 *   y JOYSTICK, y ejes: X, Y, HAT_X, HAT_Y siempre, stick derecho
 *   Z+RZ o RX+RY, y gatillos LTRIGGER+RTRIGGER, BRAKE+GAS, o Z+RZ
 *   cuando el stick va en RX+RY (uinput Xbox). El vendor/product
 *   NO es FALLO: por Bluetooth HID la API no permite fijarlo y
 *   Android asigna 0 cuando no hay VID/PID disponible; si todo lo
 *   demás coincide, es SÍ con aviso. Si falla una de las demás,
 *   es NO y el método se marca FALLO aunque el Wi-Fi funcione:
 *   "llegó el paquete" no es éxito.
 * - "Reconocido como: CONTROL DE TV - FALLO" si solo tiene
 *   SOURCE_DPAD / SOURCE_KEYBOARD, o no tiene ejes de joystick.
 *   Ese es exactamente el bug de la app de Play Store que el
 *   usuario reportó: la TV la veía como mando a distancia.
 *
 * Esta pantalla no crea ni modifica dispositivos: solo lee y
 * muestra. Si no hay ningún mando, lo dice: "Sin mandos".
 */
object Diagnostics {
    data class DeviceReport(
        val id: Int,
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val sourcesText: String,
        val hasGamepad: Boolean,
        val hasJoystick: Boolean,
        val axes: List<String>,
        /**
         * Botones que el sistema dice que tiene el dispositivo
         * (Fase 3, InputDevice.hasKeys sobre los KEYCODE_BUTTON_*
         * del mando). Android no expone valores en vivo del
         * dispositivo por API: los valores analógicos en vivo de
         * la PRUEBA DE MANDO salen del último paquete recibido
         * por red, y la pantalla lo dice así.
         */
        val buttons: List<String>,
        val verdict: String,
        /** SÍ solo si parece un mando físico del perfil activo (ver looksLikeRealGamepad). */
        val looksLikeRealGamepad: Boolean,
        val looksLikeRealReason: String,
    )

    /** Criterio de éxito de la Etapa 0, comprobado contra el sistema, no contra lo que cree la app. [profileId] es el perfil de emulación activo (Fase 2); por defecto Xbox 360.
     *
     * Regla de ejes (H-01): ningún mando Xbox 360 expone los 8 ejes
     * a la vez, así que el stick derecho y los gatillos se aceptan
     * en cualquiera de sus formas reales:
     * - Stick derecho: (Z+RZ) o (RX+RY).
     * - Gatillos: (LTRIGGER+RTRIGGER) o (BRAKE+GAS) o (Z+RZ cuando
     *   RX+RY llevan el stick, que es el uinput Xbox: sus gatillos
     *   son ABS_Z/ABS_RZ).
     * - X, Y, HAT_X, HAT_Y siempre obligatorios; nombre exacto del
     *   perfil y GAMEPAD+JOYSTICK obligatorios.
     * VID/PID: por Bluetooth HID la API no permite fijarlos y
     * Android asigna 0 cuando no hay VID/PID disponible, así que
     * si nombre+fuentes+ejes pasan pero el VID/PID no coincide, NO
     * es FALLO: devuelve true con el motivo avisando que el
     * vendor/product no es verificable por HID. Solo el uinput
     * (que sí fija VID/PID) debería fallar por VID/PID, y eso queda
     * como comportamiento futuro, no en este arreglo.
     */
    fun looksLikeRealGamepad(name: String, vendorId: Int, productId: Int, sources: Int, axes: List<String>, profileId: Int = GamepadProfiles.XBOX_360_ID): Pair<Boolean, String> {
        val profile = GamepadProfiles.fromId(profileId)
        if (name.contains("virtual", ignoreCase = true)) return false to "el nombre contiene 'Virtual'"
        if (name.contains("ultimate", ignoreCase = true)) return false to "el nombre contiene 'Ultimate'"
        if (!name.equals(profile.deviceName, ignoreCase = true)) return false to "nombre distinto de '${profile.deviceName}' (perfil ${profile.name}): $name"
        val hasGamepad = (sources and android.view.InputDevice.SOURCE_GAMEPAD) != 0
        val hasJoystick = (sources and android.view.InputDevice.SOURCE_JOYSTICK) != 0
        if (!hasGamepad || !hasJoystick) return false to "getSources() no incluye GAMEPAD+JOYSTICK"
        val axisSet = axes.toSet()
        // Ejes base: stick izquierdo + cruceta, siempre obligatorios.
        val missingBase = listOf("AXIS_X", "AXIS_Y", "AXIS_HAT_X", "AXIS_HAT_Y") - axisSet
        if (missingBase.isNotEmpty()) return false to "faltan ejes: ${missingBase.joinToString(", ")}"
        // Stick derecho: Z+RZ (BT HID y DS4) o RX+RY (uinput Xbox).
        val rightStick = ("AXIS_Z" in axisSet && "AXIS_RZ" in axisSet) || ("AXIS_RX" in axisSet && "AXIS_RY" in axisSet)
        if (!rightStick) return false to "falta el stick derecho (AXIS_Z+AXIS_RZ o AXIS_RX+AXIS_RY)"
        // Gatillos: LTRIGGER+RTRIGGER (DS4/.kl), BRAKE+GAS (BT HID)
        // o Z+RZ cuando RX+RY llevan el stick (uinput Xbox).
        val triggers = ("AXIS_LTRIGGER" in axisSet && "AXIS_RTRIGGER" in axisSet) ||
            ("AXIS_BRAKE" in axisSet && "AXIS_GAS" in axisSet) ||
            ("AXIS_RX" in axisSet && "AXIS_RY" in axisSet && "AXIS_Z" in axisSet && "AXIS_RZ" in axisSet)
        if (!triggers) return false to "faltan los gatillos (AXIS_LTRIGGER+AXIS_RTRIGGER, AXIS_BRAKE+AXIS_GAS o AXIS_Z+AXIS_RZ con stick en RX/RY)"
        val vidPidOk = vendorId == profile.vendorId && productId == profile.productId
        return if (vidPidOk) {
            true to "nombre, vendor/product, GAMEPAD+JOYSTICK y ejes coinciden con un ${profile.name} físico"
        } else {
            true to "nombre, GAMEPAD+JOYSTICK y ejes coinciden con un ${profile.name} físico (vendor/product no verificables por HID: 0x${vendorId.toString(16)}/0x${productId.toString(16)} en vez de 0x${profile.vendorId.toString(16)}/0x${profile.productId.toString(16)})"
        }
    }

    fun anyRealGamepad(): Boolean = scan().any { it.looksLikeRealGamepad }

    fun axisName(axis: Int): String = when (axis) {
        MotionEvent.AXIS_X -> "AXIS_X"
        MotionEvent.AXIS_Y -> "AXIS_Y"
        MotionEvent.AXIS_Z -> "AXIS_Z"
        MotionEvent.AXIS_RX -> "AXIS_RX"
        MotionEvent.AXIS_RY -> "AXIS_RY"
        MotionEvent.AXIS_RZ -> "AXIS_RZ"
        MotionEvent.AXIS_LTRIGGER -> "AXIS_LTRIGGER"
        MotionEvent.AXIS_RTRIGGER -> "AXIS_RTRIGGER"
        // H-10: los gatillos del BT HID llegan como BRAKE/GAS; sin
        // estos casos la pantalla mostraba "AXIS_22"/"AXIS_23".
        MotionEvent.AXIS_GAS -> "AXIS_GAS"
        MotionEvent.AXIS_BRAKE -> "AXIS_BRAKE"
        MotionEvent.AXIS_HAT_X -> "AXIS_HAT_X"
        MotionEvent.AXIS_HAT_Y -> "AXIS_HAT_Y"
        else -> "AXIS_$axis"
    }

    fun sourcesText(sources: Int): String {
        val parts = mutableListOf<String>()
        if (sources and InputDevice.SOURCE_GAMEPAD != 0) parts += "GAMEPAD"
        if (sources and InputDevice.SOURCE_JOYSTICK != 0) parts += "JOYSTICK"
        if (sources and InputDevice.SOURCE_DPAD != 0) parts += "DPAD"
        if (sources and InputDevice.SOURCE_KEYBOARD != 0) parts += "KEYBOARD"
        if (sources and InputDevice.SOURCE_TOUCHSCREEN != 0) parts += "TOUCHSCREEN"
        if (sources and InputDevice.SOURCE_MOUSE != 0) parts += "MOUSE"
        return if (parts.isEmpty()) "ninguna (0x${sources.toString(16)})" else parts.joinToString(" | ")
    }

    /** Códigos de botón de mando que se comprueban con hasKeys(). */
    private val BUTTON_KEYCODES: List<Pair<Int, String>> = listOf(
        KeyEvent.KEYCODE_BUTTON_A to "A",
        KeyEvent.KEYCODE_BUTTON_B to "B",
        KeyEvent.KEYCODE_BUTTON_X to "X",
        KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1",
        KeyEvent.KEYCODE_BUTTON_R1 to "R1",
        KeyEvent.KEYCODE_BUTTON_L2 to "L2",
        KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_START to "START",
        KeyEvent.KEYCODE_BUTTON_SELECT to "SELECT",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_BUTTON_MODE to "HOME",
        // T8: CAPTURE llega por la vía B como KEYCODE_BUTTON_1 (y
        // por la vía A como BTN_TRIGGER_HAPPY1, que Generic.kl
        // traduce al mismo BUTTON_1). Antes no se listaba.
        KeyEvent.KEYCODE_BUTTON_1 to "CAPTURE",
        KeyEvent.KEYCODE_DPAD_UP to "DPAD ↑",
        KeyEvent.KEYCODE_DPAD_DOWN to "DPAD ↓",
        KeyEvent.KEYCODE_DPAD_LEFT to "DPAD ←",
        KeyEvent.KEYCODE_DPAD_RIGHT to "DPAD →",
    )

    /** Botones que el sistema reconoce en el dispositivo (hasKeys); lista vacía si no se puede leer. */
    fun buttonNames(device: InputDevice): List<String> = runCatching {
        val keys = BUTTON_KEYCODES.map { it.first }.toIntArray()
        val present = device.hasKeys(*keys)
        BUTTON_KEYCODES.filterIndexed { i, _ -> present.getOrNull(i) == true }.map { it.second }
    }.getOrDefault(emptyList())

    fun scan(): List<DeviceReport> {
        return InputDevice.getDeviceIds().toList().mapNotNull { id ->
            val device = InputDevice.getDevice(id) ?: return@mapNotNull null
            val sources = device.sources
            val hasGamepad = (sources and InputDevice.SOURCE_GAMEPAD) != 0
            val hasJoystick = (sources and InputDevice.SOURCE_JOYSTICK) != 0
            val axes = device.motionRanges.map { axisName(it.axis) }.distinct().sorted()
            val isGamepad = GamepadClassification.isGamepadSource(sources) &&
                (axes.contains("AXIS_X") || axes.contains("AXIS_Y"))
            val verdict = if (isGamepad) "Reconocido como: MANDO DE JUEGO"
            else "Reconocido como: CONTROL DE TV - FALLO"
            val (real, realReason) = looksLikeRealGamepad(device.name, device.vendorId, device.productId, sources, axes, UdpReceiverService.activeProfileId)
            DeviceReport(
                id = id, name = device.name,
                vendorId = device.vendorId, productId = device.productId,
                sourcesText = sourcesText(sources),
                hasGamepad = hasGamepad, hasJoystick = hasJoystick,
                axes = axes, buttons = buttonNames(device), verdict = verdict,
                looksLikeRealGamepad = real, looksLikeRealReason = realReason,
            )
        }
    }

    fun render(): String = render(scan())

    // T10: renderiza unos informes ya escaneados, sin volver a
    // escanear: refresh() escanea 1x por tick y reutiliza el
    // resultado para la vista principal, la prueba y el detalle.
    fun render(reports: List<DeviceReport>): String {
        val profile = GamepadProfiles.fromId(UdpReceiverService.activeProfileId)
        val header = "¿Cómo me ve Android?\nPerfil activo: ${profile.name} (${profile.deviceName}, vendor=0x${profile.vendorId.toString(16)} product=0x${profile.productId.toString(16)})"
        if (reports.isEmpty()) return "$header\nSin dispositivos de entrada detectados."
        val sb = StringBuilder("$header\n")
        for (r in reports) {
            sb.append("\n[${r.id}] ${r.name}\n")
            sb.append("  vendor=0x${r.vendorId.toString(16)} product=0x${r.productId.toString(16)}\n")
            sb.append("  sources: ${r.sourcesText}\n")
            sb.append("  GAMEPAD=${if (r.hasGamepad) "SÍ" else "NO"}  JOYSTICK=${if (r.hasJoystick) "SÍ" else "NO"}\n")
            sb.append("  ejes: ${if (r.axes.isEmpty()) "ninguno" else r.axes.joinToString(", ")}\n")
            sb.append("  botones (según el sistema): ${if (r.buttons.isEmpty()) "ninguno detectado" else r.buttons.joinToString(", ")}\n")
            sb.append("  ${r.verdict}\n")
            sb.append("  Parece mando real: ${if (r.looksLikeRealGamepad) "SÍ" else "NO"} — ${r.looksLikeRealReason}\n")
        }
        return sb.toString()
    }
}
