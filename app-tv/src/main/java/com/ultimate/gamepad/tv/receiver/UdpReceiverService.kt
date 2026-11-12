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
import com.ultimate.gamepad.core.ExtrasProtocol
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.NetworkStats
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
 * Watchdog (Fase 1): pasados esos 2 s sin paquetes, el último estado
 * en pantalla vuelve a cero. No hay mando virtual que soltar: la
 * inyección por Shizuku está descartada (ver ReceiverConfig).
 *
 * Inyección: APAGADA (ReceiverConfig.SHIZUKU_PHASE2_ENABLED = false).
 * Shizuku está descartado en esta TV; la TV es solo receptora y la
 * única vía para que el juego vea un mando es el Bluetooth HID
 * directo del teléfono. [injectionMethod] siempre dice "ninguno".
 * Nunca se finge entrada a OpenNOW.
 */
class UdpReceiverService : Service() {
    companion object {
        const val CHANNEL_ID = "gamepad_receiver"
        /**
         * A4: igual que en el teléfono: el canal existe desde el
         * primer arranque de la app (MainActivity), no solo cuando
         * arranca el servicio.
         */
        fun createNotificationChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ultimate Gamepad TV", NotificationManager.IMPORTANCE_LOW))
        }
        @Volatile var virtualGamepadReady: Boolean = false
            private set
        @Volatile var lastSequence: Int = -1
            private set
        @Volatile var packetsReceived: Long = 0
            private set
        /**
         * Paquetes perdidos (Fase 3): huecos de secuencia entre dos
         * paquetes aceptados (NetworkStats.sequenceGap). Solo la TV
         * puede medirlo: el teléfono no ve lo que no llega por UDP.
         */
        @Volatile var packetsLost: Long = 0
            private set
        /** Paquetes/s recibidos (ventana deslizante de 1 s; objetivo ~120). */
        @Volatile var packetsPerSecond: Int = 0
            private set
        private var ppsWindowStartMs: Long = 0L
        private var ppsWindowCount: Int = 0
        @Volatile var lastPacketAtMs: Long = 0L
            private set
        @Volatile var lastState: GamepadState = GamepadState()
            private set
        @Volatile var activeTransport: TransportMethod = TransportMethod.WIFI_UDP
            private set
        /** "uinput" | "inject" | "ninguno" | "bluetooth-hid" (este último no pasa por aquí). */
        @Volatile var injectionMethod: String = "ninguno"
            private set
        /** Perfil de emulación del mando virtual creado (GamepadProfiles); 0 = Xbox 360. Lo fija el teléfono en el byte 28 de cada paquete. */
        @Volatile var activeProfileId: Int = GamepadProfiles.XBOX_360_ID
            private set
        @Volatile var lastError: String? = null
            private set

        // T1: el usuario puede detener el receptor y que NO vuelva
        // solo al abrir la app. Se guarda en las mismas
        // SharedPreferences que el PIN (TvPairing).
        private const val PREFS_NAME = "ultimate_gamepad_tv"
        private const val KEY_RECEIVER_STOPPED = "receiver_stopped"

        fun isReceiverStopped(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_RECEIVER_STOPPED, false)

        fun setReceiverStopped(context: Context, stopped: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RECEIVER_STOPPED, stopped).apply()
        }

        /**
         * T1: suelta los controles y destruye el mando virtual (+
         * dispositivos extra de teclado/ratón) y resetea la
         * inyección a "ninguno". Solo envía el estado cero si había
         * algo que soltar y había inyección activa: sin Shizuku no
         * hay nada atascado que liberar. El lastState en pantalla
         * se resetea siempre (H-03), aunque no haya inyección: si
         * no, la TV seguía mostrando los botones viejos tras
         * Detener.
         */
        fun releaseControls(context: Context) {
            if (lastState != GamepadState() && (virtualGamepadReady || injectionMethod != "ninguno")) {
                val zero = GamepadState()
                deliver(context, zero, GamepadProtocol.encode(zero, activeProfileId))
            }
            // H-03: el estado en pantalla se resetea SIEMPRE, aunque
            // no haya inyección activa: con Shizuku apagado la
            // condición de arriba nunca se cumple y la TV seguía
            // mostrando los botones viejos tras Detener.
            lastState = GamepadState()
            ShizukuHelper.destroyVirtualGamepad(context)
            ShizukuHelper.destroyExtraDevices(context)
            virtualGamepadReady = false
            injectionMethod = "ninguno"
        }

        /** T1: parar a mano = soltar todo + recordar "detenido". */
        fun stopAndRelease(context: Context) {
            releaseControls(context)
            setReceiverStopped(context, true)
        }

        fun isConnected(): Boolean = ConnectionHealth.isAlive(lastPacketAtMs)

        /**
         * Punto único de entrada para UDP y TCP: mismo paquete, mismo registro.
         * Devuelve true si el paquete fue ACEPTADO (se registra y se puede
         * entregar) y false si se rechazó por secuencia vieja o duplicada:
         * el llamador solo debe llamar a deliver() cuando devuelve true
         * (antes se entregaba siempre y un duplicado inyectaba un botón
         * o un stick viejos mientras las estadísticas lo descartaban).
         */
        // H-05: lo llaman 3 hilos (bucle UDP, bucle TCP, hilo SPP
        // de BT): la comprobación-y-asignación de lastSequence y
        // los contadores tienen que ser atómicas. @Synchronized en
        // una función del companion bloquea sobre el companion, el
        // mismo monitor para los 3 llamadores.
        @Synchronized
        fun recordPacket(state: GamepadState, method: TransportMethod): Boolean {
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
            // secuencia). El hueco máximo de entrada queda en ~2 s
            // solo si el teléfono reconecta por el MISMO
            // transporte; al cambiar de método la sesión nueva se
            // acepta de inmediato (H-06).
            val forward = (state.sequence - lastSequence + 65536) % 65536
            // H-06: un cambio de transporte es sesión nueva: el
            // teléfono reinicia su secuencia en 0 al cambiar de
            // método y la TV no debe esperar 2 s para aceptarlo.
            // El valor inicial de activeTransport es WIFI_UDP,
            // pero el primer paquete ya es sesión nueva por
            // lastSequence == -1, así que no se rompe nada.
            val freshSession = lastSequence == -1 || now - lastPacketAtMs > ConnectionHealth.TIMEOUT_MS || method != activeTransport
            if (!freshSession && (forward == 0 || forward > 32768)) return false
            // Pérdida real (Fase 3): hueco entre la última secuencia
            // aceptada y esta. En sesión nueva no se cuenta (el
            // teléfono reinicia su secuencia en 0 al reconectar).
            if (!freshSession) packetsLost += NetworkStats.sequenceGap(lastSequence, state.sequence)
            lastSequence = state.sequence
            packetsReceived++
            // Paquetes/s: ventana de 1 s que se cierra al pasar el
            // segundo (el valor mostrado es el del segundo anterior
            // completo; a 120 Hz se actualiza solo).
            if (now - ppsWindowStartMs >= 1000) {
                packetsPerSecond = ppsWindowCount
                ppsWindowStartMs = now
                ppsWindowCount = 0
            }
            ppsWindowCount++
            lastPacketAtMs = now
            lastState = state
            activeTransport = method
            return true
        }

        /**
         * Punto único de entrega de paquetes (UDP y TCP): mismo
         * paquete, misma inyección. Es función del companion para
         * que TcpReceiverService pueda entregar sin duplicar la
         * lógica (H1: antes solo el bucle UDP inyectaba y por TCP
         * la TV mostraba la entrada pero OpenNOW no recibía nada).
         *
         * Perfil (Fase 2): el profileId viaja en el byte 28 del
         * paquete. Si es distinto del dispositivo virtual actual,
         * se destruye y se crea uno nuevo con ese perfil (cambio
         * en caliente) ANTES de entregar el reporte; si la
         * recreación falla, se entrega igualmente al dispositivo
         * actual y se reintenta con el siguiente paquete.
         * Si OpenNOW no re-enumera el mando nuevo sin reiniciarse,
         * la UI debe decir "reinicia OpenNOW" (a verificar en la
         * Fase 0/2).
         */
        fun deliver(context: Context, state: GamepadState, rawPacket: ByteArray) {
            if (!ReceiverConfig.SHIZUKU_PHASE2_ENABLED) {
                injectionMethod = "ninguno"
                return
            }
            val profileId = GamepadProfiles.fromId(GamepadProtocol.profileIdOf(rawPacket)).id
            if (profileId != activeProfileId) {
                val profile = GamepadProfiles.fromId(profileId)
                val recreated = ShizukuHelper.recreateVirtualGamepad(context, profileId)
                if (recreated) {
                    activeProfileId = profileId
                } else {
                    lastError = "No se pudo recrear el mando virtual como ${profile.name} (${profile.deviceName}); se sigue con el perfil anterior y se reintenta con el siguiente paquete."
                }
            }
            // Inyección A: uinput con el perfil activo. B: injectInputEvent SOURCE_GAMEPAD.
            // ShizukuHelper devuelve false si Shizuku no está activo;
            // en ese caso la inyección queda en "ninguno", no en "conectado".
            val ok = ShizukuHelper.sendToGamepad(context, rawPacket, state, activeProfileId)
            virtualGamepadReady = ok
            injectionMethod = when {
                com.ultimate.gamepad.tv.shizuku.UinputGamepad.isReady() -> "uinput"
                ok -> "inject"
                else -> "ninguno"
            }
            if (!ok) lastError = "Sin inyección: Shizuku no listo o uinput no creado (Fase 2, ver Etapa 0)."
        }

        /**
         * Watchdog (H2): si pasan más de TIMEOUT_MS sin paquetes y
         * el último estado no es cero, envía un estado cero al
         * mando virtual para que ningún botón/stick quede atascado,
         * y marca lastState como cero. Devuelve true si liberó algo.
         * Solo dispara una vez por episodio: tras liberar, lastState
         * ya es cero y no vuelve a enviar hasta que llegue otro
         * paquete con entrada.
         */
        fun releaseStaleControls(context: Context): Boolean {
            val now = System.currentTimeMillis()
            if (lastState == GamepadState()) return false
            if (now - lastPacketAtMs <= ConnectionHealth.TIMEOUT_MS) return false
            val zero = GamepadState()
            // El estado cero también lleva el profileId activo: sin
            // él, deliver() lo leería como Xbox 360 y recrearía el
            // mando equivocado al soltar los controles.
            deliver(context, zero, GamepadProtocol.encode(zero, activeProfileId))
            lastState = zero
            // T5: sin paquetes recientes no hay inyección activa:
            // que no quede "uinput"/"inject" obsoleto en pantalla.
            injectionMethod = "ninguno"
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var watchdogJob: Job? = null
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
        try {
            startForeground(1, notification("Receptor Ultimate Gamepad en puerto ${GamepadProtocol.DEFAULT_PORT}"))
        } catch (e: Exception) {
            // Ronda 2 P1: si el sistema rechaza el foreground, no
            // crashear: el receptor no arranca y la UI lo dirá.
            android.util.Log.e("UdpReceiver", "startForeground falló: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        startService(Intent(this, TcpReceiverService::class.java))
        // Cerrar el socket viejo ANTES de crear el nuevo: el bucle
        // viejo está bloqueado en receive() sin timeout y la
        // cancelación sola no lo despierta; sin este close, el nuevo
        // bind falla con "Address already in use" y el receptor
        // queda muerto con el servicio "vivo".
        job?.cancel()
        runCatching { socket?.close() }; socket = null
        wifiLock.acquire()
        // NSD/mDNS (W6): anunciar la TV en la red local mientras el
        // receptor escucha. Idempotente: onStartCommand puede
        // repetirse (START_STICKY) y start() no re-registra.
        NsdAdvertiser.start(this)
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
                            // A8: la IP de respuesta ya no es solo la de Wi-Fi:
                            // por Ethernet localIpFromWifi es null y la TV
                            // no respondía nunca. Primera IPv4 local de
                            // cualquier interfaz; Wi-Fi solo como respaldo.
                            val ip = Discovery.localIpv4Addresses().firstOrNull()?.hostAddress
                                ?: ShizukuHelper.localIpFromWifi(wifi)
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
                        // Extras (Fase 4): teclado virtual y modo
                        // ratón. Paquetes de 8 bytes con magic
                        // 'UK'/'UM' (ExtrasProtocol). Se comprueban
                        // ANTES del ping: un paquete de teclado con
                        // action=release tiene el byte 3 a 1 y se
                        // confundiría con un ping (FLAG_PING =
                        // 0x01) si se comprobara después — la tecla
                        // quedaría pulsada en la TV — y un paquete
                        // de ratón con dy=256 también. No son
                        // paquetes de mando: no tocan lastState,
                        // la secuencia ni el watchdog.
                        if (ExtrasProtocol.isKeyboard(data)) {
                            ExtrasProtocol.decodeKeyboard(data)?.let { ev ->
                                ShizukuHelper.sendKeyboardEvent(this@UdpReceiverService, ev.keyCode, ev.action)
                            }
                            continue
                        }
                        if (ExtrasProtocol.isMouse(data)) {
                            ExtrasProtocol.decodeMouse(data)?.let { ev ->
                                ShizukuHelper.sendMouseEvent(this@UdpReceiverService, ev.dx, ev.dy, ev.buttons, ev.action)
                            }
                            continue
                        }
                        if (GamepadProtocol.isPing(data)) {
                            // El eco lleva estampado cuántos paquetes
                            // de mando ha recibido la TV: así el
                            // teléfono distingue "la TV no recibe
                            // nada" de "recibe pero no inyecta (sin
                            // Shizuku)", en vez de fiarse solo del
                            // ping (ver GamepadProtocol
                            // .stampTvPacketsReceived).
                            val stamped = GamepadProtocol.stampTvPacketsReceived(data, packetsReceived)
                            sock.send(DatagramPacket(stamped, stamped.size, packet.address, packet.port))
                            continue
                        }
                        val state = GamepadProtocol.decode(data) ?: continue
                        // Codex-1: solo se entrega si el paquete fue
                        // aceptado (secuencia nueva); un duplicado o
                        // uno viejo no se inyecta.
                        if (recordPacket(state, TransportMethod.WIFI_UDP)) {
                            deliver(this@UdpReceiverService, state, data)
                        }
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
        // Watchdog (H2): si el teléfono deja de enviar (Wi-Fi caído,
        // app muerta, desconexión sin aviso), el último reporte
        // quedaría activo en el mando virtual. Cada 500 ms se
        // comprueba lastPacketAtMs y se suelta todo con un estado
        // cero. Corre en el mismo scope (Dispatchers.IO) que
        // deliver(); el estado compartido es @Volatile y el envío
        // cero es idempotente (solo dispara una vez por episodio).
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(500)
                // El watchdog nunca debe matar el proceso: un fallo en
                // deliver() (p. ej. el bind de Shizuku) se anota y se
                // sigue, igual que el bucle UDP hace por paquete.
                runCatching { releaseStaleControls(this@UdpReceiverService) }
                    .onFailure { lastError = it.message ?: it.javaClass.simpleName }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // T1: al morir el servicio no queda nada atascado: estado
        // cero + destruir el mando virtual. No se marca "detenido":
        // si el sistema lo mata (START_STICKY), no fue el usuario.
        releaseControls(this)
        job?.cancel(); watchdogJob?.cancel(); watchdogJob = null; runCatching { socket?.close() }; socket = null
        wifiLock.release()
        // NSD/mDNS (W6): dejar de anunciar al parar el receptor.
        NsdAdvertiser.stop()
        stopService(Intent(this, TcpReceiverService::class.java))
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        createNotificationChannel(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ultimate Gamepad TV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
    }
}
