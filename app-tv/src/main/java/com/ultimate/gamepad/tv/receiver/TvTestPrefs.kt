package com.ultimate.gamepad.tv.receiver

import android.content.Context

/**
 * Confirmación manual de la etapa 4 de la PRUEBA DE MANDO (Fase 3).
 *
 * La etapa 4 ("OpenNOW recibe el mando") SOLO la puede confirmar
 * una persona que haya probado un juego: nada en la app puede
 * comprobar desde dentro que OpenNOW responde. Se guarda el
 * momento de la confirmación (epoch ms, 0 = nunca confirmado) en
 * SharedPreferences, para que la pantalla no lo vuelva a pedir en
 * cada apertura pero tampoco lo marque sola.
 */
object TvTestPrefs {
    private const val PREFS_NAME = "ultimate_gamepad_tv_test"
    private const val KEY_OPENNOW_CONFIRMED_AT = "opennow_confirmed_at_ms"

    fun getOpenNowConfirmedAtMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_OPENNOW_CONFIRMED_AT, 0L)

    fun setOpenNowConfirmed(context: Context, atMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_OPENNOW_CONFIRMED_AT, atMs).apply()
    }

    fun clearOpenNowConfirmation(context: Context) = setOpenNowConfirmed(context, 0L)
}
