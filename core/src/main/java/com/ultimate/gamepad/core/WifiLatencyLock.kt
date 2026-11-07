package com.ultimate.gamepad.core

import android.content.Context
import android.net.wifi.WifiManager

/**
 * WifiLock de baja latencia para la conexión del mando.
 *
 * Sin él, Android puede poner la tarjeta Wi-Fi en reposo entre
 * paquetes (o bajarle la prioridad) y el mando se siente con
 * tirones: paquetes que llegan tarde o en ráfagas. Con
 * WIFI_MODE_FULL_LOW_LATENCY la radio se mantiene despierta y con
 * prioridad de baja latencia mientras hay conexión activa.
 *
 * Se toma al conectar (teléfono y TV) y se suelta al desconectar.
 * Si algo falla (sin permiso WAKE_LOCK, sin Wi-Fi), el lock queda
 * en null y todo sigue funcionando sin él: nunca se crashea por esto.
 *
 * setReferenceCounted(false): un acquire/release desequilibrado
 * (p. ej. dos arranques seguidos del servicio) no deja el lock
 * colgado ni lo suelta antes de tiempo.
 */
class WifiLatencyLock(context: Context) {
    private val lock: WifiManager.WifiLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "UltimateGamepad:LowLatency").apply {
            setReferenceCounted(false)
        }
    }.getOrNull()

    @Synchronized
    fun acquire() {
        runCatching { if (lock?.isHeld != true) lock?.acquire() }
    }

    @Synchronized
    fun release() {
        runCatching { if (lock?.isHeld == true) lock?.release() }
    }

    val isHeld: Boolean get() = lock?.isHeld == true
}
