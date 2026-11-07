package com.ultimate.gamepad.tv.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ultimate.gamepad.core.ConnectionHealth
import com.ultimate.gamepad.core.Discovery
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.WifiLatencyLock
import com.ultimate.gamepad.tv.TvPairing
import com.ultimate.gamepad.tv.shizuku.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

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
            val now = System.currentTimeMillis()
            // Secuencia con wrap uint16 + reinicio de sesión. La
            // comparación vieja (sequence <= lastSequence) tiraba
            // TODA la entrada cuando el teléfono reconectaba (su
            // sequence vuelve a 0) y cuando la secuencia envolvía
            // en 65535: la TV descartaba paquetes hasta que la
            // secuencia volviera a subir (hasta ~9 min a 120 Hz),
            // mientras el ping seguía funcionando y el teléfono
            // mostraba "Conectado" con los botones muertos. Ahora
            // se acepta si la distancia hacia adelante (módulo
            // 65536) está en 1..32768, o si no llegó ningún paquete
            // en más de 2 s (sesión nueva: se acepta cualquier
            // secuencia). El hueco máximo de entrada queda en ~2 s.
            val forward = (state.sequence - lastSequence + 65536) % 65536
            val freshSession = lastSequence == -1 || now - lastPacketAtMs > ConnectionHealth.TIMEOUT_MS
            if (!freshSession && (forward == 0 || forward > 32768)) return
            lastSequence = state.sequence
            packetsReceived++
            lastPacketAtMs = now
            lastState = state
            activeTransport = method
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var socket: DatagramSocket? = null
    /** WifiLock de baja latencia: se toma al escuchar y se suelta al parar. */
    private val wifiLock: WifiLatencyLock by lazy { WifiLatencyLock(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Socket UDP ligado con SO_REUSEADDR (re-ligar rápido tras reiniciar) y búferes amplios. */
    private fun bindReceiverSocket(): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        runCatching { s.receiveBufferSize = 64 * 1024 }
        runCatching { s.sendBufferSize = 64 * 1024 }
        runCatching { s.trafficClass = 0x10 }
        s.bind(InetSocketAddress(GamepadProtocol.DEFAULT_PORT))
        return s
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Receptor Ultimate Gamepad en puerto ${GamepadProtocol.DEFAULT_PORT}"))
        startService(Intent(this, TcpReceiverService::class.java))
        // Cerrar el socket viejo ANTES de crear el nuevo: el bucle
        // viejo está bloqueado en receive() sin timeout y la
        // cancelación sola no lo despierta; sin este close, el nuevo
        // bind falla con "Address already in use" y el receptor
        // queda muerto con el servicio "vivo".
        job?.cancel()
        runCatching { socket?.close() }; socket = null
        wifiLock.acquire()
        job = scope.launch {
            // El socket vive como local de la corrutina y el campo
            // solo es un espejo para poder cerrarlo desde fuera
            // (onStartCommand/onDestroy) y despertar el receive()
            // bloqueado. El finally cierra solo SU socket: con el
            // campo compartido, el finally del bucle viejo podía
            // cerrar el socket nuevo.
            var localSocket: DatagramSocket? = null
            try {
                // Reintentar el bind: si el puerto sigue ocupado por
                // el socket viejo o por otro proceso, esperar y
                // reintentar en vez de morir en silencio.
                var attempt = 0
                while (isActive && localSocket == null) {
                    localSocket = runCatching { bindReceiverSocket() }.onFailure {
                        lastError = "No se pudo abrir UDP ${GamepadProtocol.DEFAULT_PORT} (${it.message}); reintentando…"
                        delay(1000L * (attempt + 1).coerceAtMost(5))
                    }.getOrNull()
                    attempt++
                }
                socket = localSocket
                val sock = localSocket ?: return@launch
                val buf = ByteArray(GamepadProtocol.PACKET_SIZE)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
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
                                    // Nombre genérico, sin modelo del
                                    // dispositivo: la TV se identifica en
                                    // la lista por este nombre + su IP.
                                    "TV Ultimate Gamepad", ip, GamepadProtocol.DEFAULT_PORT,
                                    TvPairing.getOrCreatePin(this@UdpReceiverService),
                                )
                                sock.send(DatagramPacket(response, response.size, packet.address, packet.port))
                            }
                            continue
                        }
                        if (GamepadProtocol.isPing(data)) {
                            sock.send(DatagramPacket(data, data.size, packet.address, packet.port))
                            continue
                        }
                        val state = GamepadProtocol.decode(data) ?: continue
                        recordPacket(state, TransportMethod.WIFI_UDP)
                        deliver(state, data)
                    } catch (e: Exception) {
                        // Un error en un paquete no mata el receptor:
                        // se anota y se sigue. Si es nuestro propio
                        // close (cancelación), el bucle termina por
                        // isActive.
                        if (!isActive) break
                        lastError = e.message ?: e.javaClass.simpleName
                        delay(50)
                    }
                }
            } finally {
                val s = localSocket; localSocket = null
                runCatching { s?.close() }
                if (socket === s) socket = null
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
        job?.cancel(); runCatching { socket?.close() }; socket = null
        wifiLock.release()
        stopService(Intent(this, TcpReceiverService::class.java))
        scope.cancel()
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
