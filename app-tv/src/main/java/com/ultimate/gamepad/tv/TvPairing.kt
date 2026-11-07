package com.ultimate.gamepad.tv

import android.content.Context
import com.ultimate.gamepad.core.PairingInfo

/**
 * PIN persistente de la TV.
 *
 * Antes el PIN se generaba nuevo en cada apertura de MainActivity
 * (PairingInfo.generatePin()), así que el QR de la pantalla y el
 * PIN que el usuario escribía en el teléfono quedaban obsoletos
 * en cuanto la app se cerraba y se volvía a abrir — y el
 * discovery (Discovery.kt) respondería con un PIN distinto en
 * cada arranque. Guardado en SharedPreferences, el PIN es el
 * mismo en cada apertura: el QR, la IP/PIN escritos y el
 * discovery siempre coinciden.
 */
object TvPairing {
    private const val PREFS_NAME = "ultimate_gamepad_tv"
    private const val KEY_PIN = "pin"

    fun getOrCreatePin(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PIN, null)
        if (!existing.isNullOrBlank()) return existing
        val pin = PairingInfo.generatePin()
        prefs.edit().putString(KEY_PIN, pin).apply()
        return pin
    }
}
