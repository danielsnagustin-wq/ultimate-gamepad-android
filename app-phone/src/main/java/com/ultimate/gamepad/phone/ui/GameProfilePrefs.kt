package com.ultimate.gamepad.phone.ui

import android.content.Context
import com.ultimate.gamepad.core.GameProfile
import com.ultimate.gamepad.core.GamepadButtons
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perfiles por juego (Fase 3) — guardado en SharedPreferences JSON.
 *
 * Decisión de almacenamiento (documentada, no olvido): DataStore
 * está declarado como dependencia pero NO se usa en ningún archivo
 * del proyecto. Para una lista pequeña de perfiles JSON, el patrón
 * SharedPreferences + JSONObject que ya usan LayoutPrefs (diseño)
 * y GamepadProfilePrefs (perfil activo) es suficiente, síncrono y
 * sobrevive a reinicios; DataStore solo añadiría plumbing
 * asíncrono (Flow + corrutinas) sin ningún beneficio aquí. Si el
 * día de mañana los perfiles crecen (import/export de archivos),
 * se migra entonces.
 *
 * Un JSON corrupto o un perfil inválido no rompe la lista: ese
 * perfil se sanea (GameProfile.sanitized()) o se descarta.
 */
object GameProfilePrefs {
    private const val PREFS = "ultimate_gamepad_game_profiles"
    private const val KEY_JSON = "profiles_json"
    private const val KEY_ACTIVE = "active_profile_name"

    fun loadAll(context: Context): List<GameProfile> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }.map { it.sanitized() }
        }.getOrDefault(emptyList())
    }

    fun saveAll(context: Context, profiles: List<GameProfile>) {
        val arr = JSONArray()
        for (p in profiles) arr.put(toJson(p.sanitized()))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, arr.toString()).apply()
    }

    /** Nombre del último perfil de juego aplicado (se reaplica al arrancar). */
    fun loadActiveName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)

    fun saveActiveName(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, name).apply()
    }

    private fun toJson(p: GameProfile): JSONObject = JSONObject().apply {
        put("name", p.name)
        put("profileId", p.profileId)
        put("deadZone", p.deadZone.toDouble())
        put("curve", p.curve.toDouble())
        put("triggerScale", p.triggerScale.toDouble())
        put("vibration", p.vibrationEnabled)
        put("remap", p.remap.entries.joinToString(",") { "${it.key}:${it.value}" })
        if (p.layoutJson != null) put("layoutJson", p.layoutJson)
    }

    private fun fromJson(obj: JSONObject): GameProfile {
        val remap = obj.optString("remap", "").split(",")
            .mapNotNull { part ->
                val kv = part.split(":")
                if (kv.size == 2) {
                    val from = kv[0].toIntOrNull()
                    val to = kv[1].toIntOrNull()
                    if (from != null && to != null) from to to else null
                } else null
            }.toMap()
        return GameProfile(
            name = obj.optString("name", "Sin nombre"),
            profileId = obj.optInt("profileId", 0),
            deadZone = obj.optDouble("deadZone", 0.05).toFloat(),
            curve = obj.optDouble("curve", 0.85).toFloat(),
            triggerScale = obj.optDouble("triggerScale", 1.0).toFloat(),
            vibrationEnabled = obj.optBoolean("vibration", false),
            remap = remap,
            layoutJson = obj.optString("layoutJson", null),
        )
    }

    /** Etiqueta corta de un botón para el editor de remapeo. */
    fun buttonLabel(bit: Int): String = when (bit) {
        GamepadButtons.A -> "A"
        GamepadButtons.B -> "B"
        GamepadButtons.X -> "X"
        GamepadButtons.Y -> "Y"
        GamepadButtons.LB -> "LB"
        GamepadButtons.RB -> "RB"
        GamepadButtons.START -> "Start"
        GamepadButtons.SELECT -> "Select"
        GamepadButtons.L3 -> "L3"
        GamepadButtons.R3 -> "R3"
        GamepadButtons.DPAD_UP -> "Arriba"
        GamepadButtons.DPAD_DOWN -> "Abajo"
        GamepadButtons.DPAD_LEFT -> "Izquierda"
        GamepadButtons.DPAD_RIGHT -> "Derecha"
        GamepadButtons.HOME -> "Xbox/PS"
        GamepadButtons.CAPTURE -> "Share"
        else -> "Botón $bit"
    }
}
