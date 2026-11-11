package com.ultimate.gamepad.phone.ui

import android.content.Context
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadMacro
import com.ultimate.gamepad.core.MacroEvent
import com.ultimate.gamepad.core.TurboConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ajustes de los extras (Fase 4) — guardado en SharedPreferences
 * JSON, como los perfiles por juego (GameProfilePrefs) y el
 * diseño (LayoutPrefs). Mismo patrón, sin DataStore: son valores
 * pequeños y síncronos, y DataStore no se usa en ningún archivo
 * del proyecto (ver GameProfilePrefs).
 *
 * - Turbo: botones con turbo + velocidad (Hz).
 * - Vibración: vibrar al pulsar en el teléfono (global; el
 *   perfil de juego activo la pisa al aplicarse, ver
 *   GamepadViewModel.applyGameProfile).
 * - Vibración del juego (rumble por Bluetooth HID, ronda REA
 *   GFN+OpenNOW): interruptor propio; ningún perfil lo pisa.
 * - Giroscopio: activado + sensibilidad.
 * - Macros: lista guardada (nombre + eventos con tiempos).
 * - Paletas L4/R4 (ronda REA GFN+OpenNOW): botón del mando que
 *   actúa al pulsar cada paleta (por defecto L4→L3, R4→R3;
 *   0 = la paleta no hace nada).
 *
 * Un JSON corrupto no rompe nada: se sanea o se descarta.
 */
object ExtrasPrefs {
    private const val PREFS = "ultimate_gamepad_extras"
    private const val KEY_TURBO_BUTTONS = "turbo_buttons"
    private const val KEY_TURBO_RATE = "turbo_rate_hz"
    private const val KEY_VIBRATION = "vibration"
    private const val KEY_GAME_RUMBLE = "game_rumble"
    private const val KEY_GYRO_ENABLED = "gyro_enabled"
    private const val KEY_GYRO_SENSITIVITY = "gyro_sensitivity"
    private const val KEY_MACROS = "macros_json"
    private const val KEY_L4_REMAP = "l4_remap"
    private const val KEY_R4_REMAP = "r4_remap"

    /** Solo valen 0 (nada), L3 o R3: cualquier otro valor guardado se sanea al defecto. */
    fun sanitizeRemap(target: Int, default: Int): Int =
        if (target == 0 || target == GamepadButtons.L3 || target == GamepadButtons.R3) target else default

    fun loadL4Remap(context: Context): Int =
        sanitizeRemap(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_L4_REMAP, GamepadButtons.L3),
            GamepadButtons.L3,
        )

    fun saveL4Remap(context: Context, target: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_L4_REMAP, sanitizeRemap(target, GamepadButtons.L3)).apply()
    }

    fun loadR4Remap(context: Context): Int =
        sanitizeRemap(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_R4_REMAP, GamepadButtons.R3),
            GamepadButtons.R3,
        )

    fun saveR4Remap(context: Context, target: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_R4_REMAP, sanitizeRemap(target, GamepadButtons.R3)).apply()
    }

    fun loadTurbo(context: Context): TurboConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val buttons = prefs.getString(KEY_TURBO_BUTTONS, "")
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()
        val rate = prefs.getInt(KEY_TURBO_RATE, 10)
        return TurboConfig(buttons, rate).sanitized()
    }

    fun saveTurbo(context: Context, config: TurboConfig) {
        val clean = config.sanitized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TURBO_BUTTONS, clean.buttons.joinToString(","))
            .putInt(KEY_TURBO_RATE, clean.rateHz)
            .apply()
    }

    fun loadVibration(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VIBRATION, false)

    fun saveVibration(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VIBRATION, enabled).apply()
    }

    fun loadGameRumble(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GAME_RUMBLE, true)

    fun saveGameRumble(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GAME_RUMBLE, enabled).apply()
    }

    fun loadGyroEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GYRO_ENABLED, false)

    fun saveGyroEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GYRO_ENABLED, enabled).apply()
    }

    fun loadGyroSensitivity(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_GYRO_SENSITIVITY, 1f).coerceIn(0.5f, 2f)

    fun saveGyroSensitivity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_GYRO_SENSITIVITY, value.coerceIn(0.5f, 2f)).apply()
    }

    fun loadMacros(context: Context): List<GamepadMacro> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MACROS, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { macroFromJson(arr.getJSONObject(i)) }.getOrNull()
            }.map { it.sanitized() }.filter { it.events.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    fun saveMacros(context: Context, macros: List<GamepadMacro>) {
        val arr = JSONArray()
        for (m in macros) arr.put(macroToJson(m.sanitized()))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MACROS, arr.toString()).apply()
    }

    private fun macroToJson(m: GamepadMacro): JSONObject = JSONObject().apply {
        put("name", m.name)
        val events = JSONArray()
        for (e in m.events) {
            events.put(JSONObject().apply {
                put("d", e.delayMs)
                put("b", e.buttons)
                put("e", e.extraButtons)
            })
        }
        put("events", events)
    }

    private fun macroFromJson(obj: JSONObject): GamepadMacro {
        val events = mutableListOf<MacroEvent>()
        val arr = obj.optJSONArray("events")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                events += MacroEvent(
                    delayMs = e.optLong("d", 0),
                    buttons = e.optInt("b", 0),
                    extraButtons = e.optInt("e", 0),
                )
            }
        }
        return GamepadMacro(name = obj.optString("name", "Sin nombre"), events = events)
    }
}
