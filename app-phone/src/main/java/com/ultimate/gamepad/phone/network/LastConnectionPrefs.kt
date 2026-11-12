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
 * Fase 5 (Bluetooth datos SPP/RFCOMM): también guarda la MAC de la
 * TV emparejada (btAddress). Si save() se llama con btAddress = null
 * (p. ej. al conectar por Wi-Fi), se conserva la MAC guardada antes:
 * cambiar de método no debe borrar la TV elegida. Solo se guardan
 * los métodos Wi-Fi y BLUETOOTH_DATA (HID no pasa por aquí).
 */
object LastConnectionPrefs {
    private const val PREFS = "ultimate_gamepad_last_connection"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_PIN = "pin"
    private const val KEY_METHOD = "method"
    private const val KEY_RUNNING = "service_running"
    private const val KEY_BT_ADDRESS = "bt_address"

    data class Saved(val host: String, val port: Int, val pin: String, val method: TransportMethod, val btAddress: String? = null)

    fun save(context: Context, host: String, port: Int, pin: String, method: TransportMethod, btAddress: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Con btAddress = null se conserva la MAC guardada antes.
        val address = btAddress ?: prefs.getString(KEY_BT_ADDRESS, null)
        prefs.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_PIN, pin)
            .putString(KEY_METHOD, method.name)
            .putString(KEY_BT_ADDRESS, address)
            .apply()
    }

    fun load(context: Context): Saved? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val method = runCatching {
            TransportMethod.valueOf(prefs.getString(KEY_METHOD, null) ?: "")
        }.getOrNull() ?: return null
        if (method != TransportMethod.WIFI_UDP && method != TransportMethod.WIFI_TCP && method != TransportMethod.BLUETOOTH_DATA) return null
        val btAddress = prefs.getString(KEY_BT_ADDRESS, null)?.takeIf { it.isNotBlank() }
        // Ronda 2 P1-2: por Bluetooth datos no hay IP: un host en
        // blanco con MAC guardada es válido (antes devolvía null y
        // SPP era imposible sin una Wi-Fi previa).
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }
            ?: if (method == TransportMethod.BLUETOOTH_DATA && btAddress != null) "" else return null
        return Saved(
            host = host,
            port = prefs.getInt(KEY_PORT, GamepadProtocol.DEFAULT_PORT),
            pin = prefs.getString(KEY_PIN, "") ?: "",
            method = method,
            btAddress = btAddress,
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
