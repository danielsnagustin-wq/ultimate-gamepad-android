package com.ultimate.gamepad.phone.network

import android.content.Context
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.TransportMethod

/**
 * Última conexión Wi-Fi — A3 (auditoría).
 *
 * Host, puerto, PIN y método guardados al conectar. Sin esto, tras la
 * muerte del proceso el servicio volvía con START_STICKY e intent nulo
 * y se quedaba parado sin reanudar nada (el comentario "vuelve solo"
 * era falso): sin destino guardado no hay nada que reanudar.
 *
 * También guarda si el servicio estaba enviando (serviceRunning): al
 * reabrir la app, la UI puede mostrar CONECTANDO en vez de
 * DESCONECTADO aunque el ViewModel naciera de cero, y el bucle de
 * ping verifica en segundos si la TV responde de verdad.
 *
 * Solo se guardan los métodos Wi-Fi (el servicio solo envía por Wi-Fi;
 * HID no pasa por aquí). Un método guardado que no sea Wi-Fi se ignora
 * al cargar, nunca se crashea.
 */
object LastConnectionPrefs {
    private const val PREFS = "ultimate_gamepad_last_connection"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_PIN = "pin"
    private const val KEY_METHOD = "method"
    private const val KEY_RUNNING = "service_running"

    data class Saved(val host: String, val port: Int, val pin: String, val method: TransportMethod)

    fun save(context: Context, host: String, port: Int, pin: String, method: TransportMethod) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_PIN, pin)
            .putString(KEY_METHOD, method.name)
            .apply()
    }

    fun load(context: Context): Saved? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val method = runCatching {
            TransportMethod.valueOf(prefs.getString(KEY_METHOD, null) ?: "")
        }.getOrNull() ?: return null
        if (method != TransportMethod.WIFI_UDP && method != TransportMethod.WIFI_TCP) return null
        return Saved(
            host = host,
            port = prefs.getInt(KEY_PORT, GamepadProtocol.DEFAULT_PORT),
            pin = prefs.getString(KEY_PIN, "") ?: "",
            method = method,
        )
    }

    fun setRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, running).apply()
    }

    fun isRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RUNNING, false)
}
