package com.ultimate.gamepad.phone.ui

import android.content.Context
import org.json.JSONObject

/**
 * Diseño del mando a gusto del usuario: cada elemento se puede
 * mostrar u ocultar, hacer más grande o más pequeño, y moverlo
 * con desplazamientos en dp. Se guarda en SharedPreferences
 * como JSON; si falta un elemento o el JSON está roto, ese
 * elemento usa su valor por defecto (nunca se rompe la pantalla).
 *
 * El botón ⚙ NO es personalizable a propósito: si el usuario lo
 * ocultara, no habría forma de volver a Ajustes sin reinstalar.
 */
data class ButtonLayout(
    val visible: Boolean = true,
    val scale: Float = 1f,
    val offsetXdp: Int = 0,
    val offsetYdp: Int = 0,
    /** Opacidad del botón (1 = sólido, 0.3 = casi transparente). */
    val opacity: Float = 1f,
)

data class LayoutConfig(val elements: Map<String, ButtonLayout> = defaultElements()) {
    fun element(id: String): ButtonLayout = elements[id] ?: ButtonLayout()
    fun withElement(id: String, layout: ButtonLayout): LayoutConfig =
        copy(elements = elements + (id to layout))

    companion object {
        const val LEFT_STICK = "leftStick"
        const val DPAD = "dpad"
        const val ABXY = "abxy"
        const val RIGHT_STICK = "rightStick"
        const val XBOX = "xbox"
        const val VIEW = "view"
        const val SHARE = "share"
        const val MENU = "menu"
        const val SHOULDERS_LEFT = "shouldersLeft"
        const val SHOULDERS_RIGHT = "shouldersRight"
        /** Paletas traseras L4/R4 (Fase 4, GamepadExtraButtons). */
        const val L4 = "l4"
        const val R4 = "r4"

        /** Nombre en español de cada elemento para la lista de Ajustes. */
        val NAMES: Map<String, String> = mapOf(
            LEFT_STICK to "Stick izquierdo (L3)",
            DPAD to "Cruceta",
            ABXY to "Botones A / B / X / Y",
            RIGHT_STICK to "Stick derecho (R3)",
            XBOX to "Botón Xbox",
            VIEW to "VIEW",
            SHARE to "SHARE",
            MENU to "MENU",
            SHOULDERS_LEFT to "LT / LB",
            SHOULDERS_RIGHT to "RB / RT",
            L4 to "L4 (paleta)",
            R4 to "R4 (paleta)",
        )

        fun defaultElements(): Map<String, ButtonLayout> =
            NAMES.keys.associateWith { ButtonLayout() }
    }
}

object LayoutPrefs {
    private const val PREFS = "ultimate_gamepad_layout"
    private const val KEY_JSON = "layout_json"
    private const val KEY_DEADZONE = "dead_zone"

    /** Zona muerta de los sticks (0.05 = 5% del recorrido no hace nada). */
    fun loadDeadZone(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_DEADZONE, 0.05f).coerceIn(0f, 0.15f)

    fun saveDeadZone(context: Context, deadZone: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_DEADZONE, deadZone.coerceIn(0f, 0.15f)).apply()
    }

    fun load(context: Context): LayoutConfig {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null)
            ?: return LayoutConfig()
        return fromJson(json)
    }

    fun save(context: Context, config: LayoutConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, toJson(config)).apply()
    }

    /**
     * Serialización del diseño como JSON (Fase 3: los perfiles por
     * juego guardan el diseño dentro del perfil). Extraída de
     * load()/save() sin cambiar el formato: el JSON que guardaba
     * un perfil es el mismo que guarda el diseño global.
     */
    fun toJson(config: LayoutConfig): String {
        val root = JSONObject()
        for ((id, layout) in config.elements) {
            root.put(id, JSONObject().apply {
                put("visible", layout.visible)
                put("scale", layout.scale.toDouble())
                put("x", layout.offsetXdp)
                put("y", layout.offsetYdp)
                put("opacity", layout.opacity.toDouble())
            })
        }
        return root.toString()
    }

    fun fromJson(json: String?): LayoutConfig {
        if (json == null) return LayoutConfig()
        return runCatching {
            val root = JSONObject(json)
            val elements = LayoutConfig.NAMES.keys.associateWith { id ->
                val obj = root.optJSONObject(id) ?: JSONObject()
                ButtonLayout(
                    visible = obj.optBoolean("visible", true),
                    scale = obj.optDouble("scale", 1.0).toFloat().coerceIn(0.6f, 1.6f),
                    offsetXdp = obj.optInt("x", 0).coerceIn(-700, 700),
                    offsetYdp = obj.optInt("y", 0).coerceIn(-300, 300),
                    opacity = obj.optDouble("opacity", 1.0).toFloat().coerceIn(0.3f, 1f),
                )
            }
            LayoutConfig(elements)
        }.getOrDefault(LayoutConfig())
    }
}
