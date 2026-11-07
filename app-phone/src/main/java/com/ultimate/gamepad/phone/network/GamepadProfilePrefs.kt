package com.ultimate.gamepad.phone.network

import android.content.Context
import com.ultimate.gamepad.core.GamepadProfiles

/**
 * Perfil de emulación activo — Fase 2 (mecanismo listo para la
 * pantalla TIPO DE MANDO de la Fase 3).
 *
 * El perfil activo se guarda en SharedPreferences (sobrevive a
 * reinicios de la app y a la muerte del proceso) y se aplica en
 * los dos lados a la vez: el teléfono lo envía en el byte 28 de
 * cada paquete (GamepadSender.activeProfileId) y la TV recrea el
 * dispositivo virtual con ese perfil. La pantalla TIPO DE MANDO
 * (Fase 3) llamará a GamepadSender.setActiveProfile(); aquí solo
 * está el mecanismo, sin UI nueva.
 *
 * Un id guardado desconocido o corrupto se sanea a Xbox 360
 * (GamepadProfiles.fromId), nunca se crashea ni se inventa un
 * perfil.
 */
object GamepadProfilePrefs {
    private const val PREFS = "ultimate_gamepad_profile"
    private const val KEY_PROFILE_ID = "profile_id"

    fun load(context: Context): Int =
        GamepadProfiles.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PROFILE_ID, GamepadProfiles.XBOX_360_ID),
        ).id

    fun save(context: Context, profileId: Int) {
        val id = GamepadProfiles.fromId(profileId).id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PROFILE_ID, id).apply()
    }
}
