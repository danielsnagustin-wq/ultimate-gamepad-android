package com.ultimate.gamepad.tv.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ultimate.gamepad.core.ConnectionHealth
import com.ultimate.gamepad.core.Discovery
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.tv.TvPairing
import com.ultimate.gamepad.tv.shizuku.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Receptor UDP en primer plano (TV) — Fase 1, visible en pantalla.
 * Recibe paquetes de 32 bytes, los valida (magic/versión/CRC) y
 * los expone para que MainActivity los muestre en vivo: al pulsar
 * A/B/X/Y o mover el stick en el teléfono, la TV lo muestra.
 * Si llega un ping, devuelve el eco al teléfono para que el ping
 * sea real. Descarta paquetes viejos por secuencia.
 *
 * Pérdida de conexión: si no llega ningún paquete en 2 s
 * (ConnectionHealth.TIMEOUT_MS), la pantalla muestra "Sin
 * conexión". Cuando vuelve un paquete, se recupera solo.
 *
 * Inyección (Fase 2, opcional): si ReceiverConfig lo permite y
 * Shizuku está activo, intenta A (uinput Xbox 360) y luego B
 * (injectInputEvent SOURCE_GAMEPAD). Si Shizuku no está, la
 * inyección queda en "ninguno" y [injectionMethod] lo dice.
 * Nunca se finge entrada a OpenNOW.
 */
class UdpReceiverService : Service() {
    companion object {
        const val CHANNEL_ID = "gamepad_receiver"
        @Volatile var virtualGamepadReady: Boolean = false
            private set
        @Volatile var lastSequence: Int = -1
            private set
        @Volatile var packetsReceived: Long = 0
            private set
        @Volatile var lastPacketAtMs: Long = 0L
            private set
        @Volatile var lastState: GamepadState = GamepadState()
            private set
        @Volatile var activeTransport: TransportMethod = TransportMethod.WIFI_UDP
            private set
        /** "uinput" | "inject" | "ninguno" | "bluetooth-hid" (este último no pasa por aquí). */
        @Volatile var injectionMethod: String = "ninguno"
            private set
        @Volatile var lastError: String? = null
            private set

        fun isConnected(): Boolean = ConnectionHealth.isAlive(lastPacketAtMs)

        /** Punto único de entrada para UDP y TCP: mismo paquete, mismo registro. */
        fun recordPacket(state: GamepadState, method: TransportMethod) {
            if (state.sequence <= lastSequence && lastSequence != -1) return
            lastSequence = state.sequence
            packetsReceived++
            lastPacketAtMs = System.currentTimeMillis()
            lastState = state
            activeTransport = method
        }
    }

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Receptor Ultimate Gamepad en puerto ${GamepadProtocol.DEFAULT_PORT}"))
        startService(Intent(this, TcpReceiverService::class.java))
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = DatagramSocket(GamepadProtocol.DEFAULT_PORT)
                val buf = ByteArray(GamepadProtocol.PACKET_SIZE)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    // Descubrimiento (Discovery.kt): el teléfono emite
                    // "UGDISC1" por broadcast y la TV responde con su
                    // nombre, IP, puerto y PIN persistente, para que
                    // conectar sea tocar la TV en la lista. Se
                    // comprueba ANTES del ping: no es un paquete de
                    // juego de 32 bytes ni un ping.
                    if (String(data, Charsets.UTF_8) == Discovery.MAGIC_REQUEST) {
                        val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val ip = ShizukuHelper.localIpFromWifi(wifi)
                        if (ip != null) {
                            val response = Discovery.buildResponse(
                                Build.MODEL, ip, GamepadProtocol.DEFAULT_PORT,
                                TvPairing.getOrCreatePin(this@UdpReceiverService),
                            )
                            socket?.send(DatagramPacket(response, response.size, packet.address, packet.port))
                        }
                        continue
                    }
                    if (GamepadProtocol.isPing(data)) {
                        socket?.send(DatagramPacket(data, data.size, packet.address, packet.port))
                        continue
                    }
                    val state = GamepadProtocol.decode(data) ?: continue
                    recordPacket(state, TransportMethod.WIFI_UDP)
                    deliver(state, data)
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return START_STICKY
    }

    private fun deliver(state: GamepadState, rawPacket: ByteArray) {
        if (!ReceiverConfig.SHIZUKU_PHASE2_ENABLED) {
            injectionMethod = "ninguno"
            return // Fase 1: solo mostrar en pantalla, sin inyectar.
        }
        // Inyección A: uinput Xbox 360. B: injectInputEvent SOURCE_GAMEPAD.
        // ShizukuHelper devuelve false si Shizuku no está activo;
        // en ese caso la inyección queda en "ninguno", no en "conectado".
        val ok = ShizukuHelper.sendToGamepad(this, rawPacket, state)
        virtualGamepadReady = ok
        injectionMethod = when {
            com.ultimate.gamepad.tv.shizuku.UinputGamepad.isReady() -> "uinput"
            ok -> "inject"
            else -> "ninguno"
        }
        if (!ok) lastError = "Sin inyección: Shizuku no listo o uinput no creado (Fase 2, ver Etapa 0)."
    }

    override fun onDestroy() {
        job?.cancel(); socket?.close()
        stopService(Intent(this, TcpReceiverService::class.java))
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ultimate Gamepad TV", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ultimate Gamepad TV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
    }
}
