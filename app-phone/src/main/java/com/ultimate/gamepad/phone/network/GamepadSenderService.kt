package com.ultimate.gamepad.phone.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ultimate.gamepad.core.ConnectionHealth
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.TcpGamepadTransport
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Servicio en primer plano que envía el estado del mando.
 * Transportes de envío (Fase 1): Wi-Fi UDP (principal) y Wi-Fi TCP
 * (respaldo). Bluetooth HID tiene su propio servicio
 * (BluetoothHidService) y Bluetooth-datos queda para Etapa 2.
 *
 * Reconexión automática: si el envío falla, no se cierra el
 * servicio: se espera 1 s y se reintenta con el mismo método; si
 * el método es AUTO y no hay eco de ping en 3 s, el ViewModel
 * cambia a TCP (ver GamepadViewModel.tryNextTransport()). UDP no
 * tiene "conexión" que se caiga: si la TV vuelve, los paquetes
 * vuelven a llegar solos.
 *
 * El ping es real: cada medio segundo se envía un paquete
 * FLAG_PING y se mide el tiempo hasta que la TV lo devuelve
 * (eco). Si la TV no hace eco, el ping se muestra como "—" y
 * [isTvResponding] es false. Nunca se muestra 0 ms inventado.
 *
 * Latencia: el estado se envía a 120 Hz (cada 8 ms) por UDP,
 * no a 60 Hz: en red local 32 bytes a 120 Hz no cuestan nada y
 * el mando responde el doble de rápido.
 */
class GamepadSenderService : Service() {
    companion object {
        const val CHANNEL_ID = "gamepad_sender"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_METHOD = "method" // "WIFI_UDP" o "WIFI_TCP"
        const val ACTION_START = "com.ultimate.gamepad.phone.START"
        const val ACTION_STOP = "com.ultimate.gamepad.phone.STOP"
        @Volatile var lastError: String? = null
            private set
        @Volatile var lastPingMs: Long? = null
            private set
        @Volatile var lastEchoAtMs: Long = 0L
            private set
        @Volatile var activeMethod: TransportMethod = TransportMethod.WIFI_UDP
            private set

        fun isTvResponding(): Boolean = ConnectionHealth.isTvResponding(lastEchoAtMs)
    }

    private var job: Job? = null
    private var udp: UdpTransport? = null
    private var tcp: TcpGamepadTransport? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSending(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, GamepadProtocol.DEFAULT_PORT)
                val method = runCatching { TransportMethod.valueOf(intent.getStringExtra(EXTRA_METHOD) ?: "WIFI_UDP") }
                    .getOrDefault(TransportMethod.WIFI_UDP)
                activeMethod = method
                startSending(host, port, method)
            }
        }
        return START_NOT_STICKY
    }

    private fun startSending(host: String, port: Int, method: TransportMethod) {
        startForeground(1, notification("Enviando por $method a $host:$port…"))
        job?.cancel()
        lastError = null; lastPingMs = null; lastEchoAtMs = 0L
        job = CoroutineScope(Dispatchers.IO).launch {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (address == null) { lastError = "IP no válida: $host"; return@launch }
            var sequence = 0
            var lastPingSentAt = 0L
            var pingSequence = -1
            while (isActive) {
                try {
                    val state = GamepadSender.currentState.copy(
                        sequence = sequence++,
                        timestampMs = System.currentTimeMillis(),
                    )
                    when (method) {
                        TransportMethod.WIFI_UDP -> {
                            if (udp == null) udp = UdpTransport()
                            udp?.send(state, address, port)
                            val now = System.currentTimeMillis()
                            if (now - lastPingSentAt >= 500) {
                                lastPingSentAt = now; pingSequence = sequence
                                udp?.sendPing(pingSequence, now, address, port)
                            }
                            udp?.receive(1) { echoed, isPing, _ ->
                                if (isPing && echoed.sequence == pingSequence) {
                                    lastPingMs = System.currentTimeMillis() - echoed.timestampMs
                                    lastEchoAtMs = System.currentTimeMillis()
                                }
                            }
                        }
                        TransportMethod.WIFI_TCP -> {
                            if (tcp == null) {
                                tcp = TcpGamepadTransport(address, port + 1)
                                tcp?.connect()
                            }
                            tcp?.send(state)
                            // TCP no tiene eco de ping en Etapa 1: la
                            // respuesta se considera el propio envío sin
                            // excepción. El ping TCP queda como "—" hasta
                            // que la TV implemente eco TCP en Etapa 2.
                        }
                        else -> { lastError = "Método $method no es de este servicio"; delay(1000) }
                    }
                    lastError = null
                } catch (e: Exception) {
                    // Reconexión automática: no salir del bucle.
                    lastError = e.message ?: e.javaClass.simpleName
                    runCatching { tcp?.close() }; tcp = null
                    runCatching { udp?.close() }; udp = null
                    delay(1000)
                }
                delay(8) // ~120 Hz: el mando responde el doble de rápido que a 60 Hz.
            }
        }
    }

    private fun stopSending() {
        job?.cancel(); job = null
        runCatching { tcp?.close() }; tcp = null
        runCatching { udp?.close() }; udp = null
    }

    override fun onDestroy() { stopSending(); super.onDestroy() }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ultimate Gamepad", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ultimate Gamepad")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}

/** Estado compartido entre la UI y el servicio. Volátil por diseño, Etapa 1. */
object GamepadSender {
    @Volatile var currentState: GamepadState = GamepadState()
}
