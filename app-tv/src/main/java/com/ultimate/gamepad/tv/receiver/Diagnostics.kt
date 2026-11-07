package com.ultimate.gamepad.tv.receiver

import android.view.InputDevice
import android.view.MotionEvent
import com.ultimate.gamepad.core.GamepadClassification

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
 *   nombre == "Xbox 360 Controller" (sin "Virtual" ni "Ultimate"),
 *   vendor 0x045E, product 0x028E, sources incluyen GAMEPAD y
 *   JOYSTICK, y ejes incluyen X, Y, Z, RZ, LTRIGGER, RTRIGGER,
 *   HAT_X, HAT_Y. Si falla una, es NO y el método se marca FALLO
 *   aunque el Wi-Fi funcione: "llegó el paquete" no es éxito.
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
        val verdict: String,
        /** SÍ solo si parece un mando físico Xbox 360 (ver looksLikeRealGamepad). */
        val looksLikeRealGamepad: Boolean,
        val looksLikeRealReason: String,
    )

    /** Criterio de éxito de la Etapa 0, comprobado contra el sistema, no contra lo que cree la app. */
    fun looksLikeRealGamepad(name: String, vendorId: Int, productId: Int, sources: Int, axes: List<String>): Pair<Boolean, String> {
        if (name.contains("virtual", ignoreCase = true)) return false to "el nombre contiene 'Virtual'"
        if (name.contains("ultimate", ignoreCase = true)) return false to "el nombre contiene 'Ultimate'"
        if (!name.equals("Xbox 360 Controller", ignoreCase = true)) return false to "nombre distinto de 'Xbox 360 Controller': $name"
        if (vendorId != 0x045E || productId != 0x028E) return false to "vendor/product distintos de 0x045E/0x028E"
        val hasGamepad = (sources and android.view.InputDevice.SOURCE_GAMEPAD) != 0
        val hasJoystick = (sources and android.view.InputDevice.SOURCE_JOYSTICK) != 0
        if (!hasGamepad || !hasJoystick) return false to "getSources() no incluye GAMEPAD+JOYSTICK"
        val required = listOf("AXIS_X", "AXIS_Y", "AXIS_Z", "AXIS_RZ", "AXIS_LTRIGGER", "AXIS_RTRIGGER", "AXIS_HAT_X", "AXIS_HAT_Y")
        val missing = required - axes.toSet()
        if (missing.isNotEmpty()) return false to "faltan ejes: ${missing.joinToString(", ")}"
        return true to "nombre, vendor/product, GAMEPAD+JOYSTICK y ejes coinciden con un Xbox 360 físico"
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
            val (real, realReason) = looksLikeRealGamepad(device.name, device.vendorId, device.productId, sources, axes)
            DeviceReport(
                id = id, name = device.name,
                vendorId = device.vendorId, productId = device.productId,
                sourcesText = sourcesText(sources),
                hasGamepad = hasGamepad, hasJoystick = hasJoystick,
                axes = axes, verdict = verdict,
                looksLikeRealGamepad = real, looksLikeRealReason = realReason,
            )
        }
    }

    fun render(): String {
        val reports = scan()
        if (reports.isEmpty()) return "¿Cómo me ve Android?\nSin dispositivos de entrada detectados."
        val sb = StringBuilder("¿Cómo me ve Android?\n")
        for (r in reports) {
            sb.append("\n[${r.id}] ${r.name}\n")
            sb.append("  vendor=0x${r.vendorId.toString(16)} product=0x${r.productId.toString(16)}\n")
            sb.append("  sources: ${r.sourcesText}\n")
            sb.append("  GAMEPAD=${if (r.hasGamepad) "SÍ" else "NO"}  JOYSTICK=${if (r.hasJoystick) "SÍ" else "NO"}\n")
            sb.append("  ejes: ${if (r.axes.isEmpty()) "ninguno" else r.axes.joinToString(", ")}\n")
            sb.append("  ${r.verdict}\n")
            sb.append("  Parece mando real: ${if (r.looksLikeRealGamepad) "SÍ" else "NO"} — ${r.looksLikeRealReason}\n")
        }
        return sb.toString()
    }
}
