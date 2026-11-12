package com.ultimate.gamepad.phone.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ultimate.gamepad.core.ConnectionHealth
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.NetworkStats
import com.ultimate.gamepad.core.TcpGamepadTransport
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.UdpTransport
import com.ultimate.gamepad.core.WifiLatencyLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Servicio en primer plano que envía el estado del mando.
 * Transportes de envío (Fase 1 + Fase 5): Wi-Fi UDP (principal),
 * Wi-Fi TCP (respaldo) y Bluetooth datos SPP/RFCOMM (respaldo si
 * falla todo el Wi-Fi). Bluetooth HID tiene su propio servicio
 * (BluetoothHidService).
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
 * (eco). Si la TV no hace eco, el ping se muestra como "sin datos" y
 * [isTvResponding] es false. Nunca se muestra 0 ms inventado.
 * Por TCP el eco llega por el mismo socket y lo lee una
 * corrutina lectora (Fase 1); antes el ping TCP era "—".
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
        const val EXTRA_METHOD = "method" // "WIFI_UDP", "WIFI_TCP" o "BLUETOOTH_DATA"
        const val EXTRA_BT_ADDRESS = "bt_address" // MAC de la TV emparejada (solo BLUETOOTH_DATA)
        const val ACTION_START = "com.ultimate.gamepad.phone.START"
        const val ACTION_STOP = "com.ultimate.gamepad.phone.STOP"
        /**
         * A4: crea el canal al arrancar la app (MainActivity), para
         * que el interruptor de notificaciones exista en Ajustes
         * aunque el servicio aún no haya arrancado nunca. Antes el
         * canal solo se creaba dentro de notification(): en Android
         * 13+ la app aparecía sin canal y el interruptor parecía
         * bloqueado sin explicación.
         */
        fun createNotificationChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ultimate Gamepad", NotificationManager.IMPORTANCE_LOW),
            )
        }
        @Volatile var lastError: String? = null
            private set
        @Volatile var lastPingMs: Long? = null
            private set
        @Volatile var lastEchoAtMs: Long = 0L
            private set
        /** Último envío TCP sin excepción (prueba de vida por TCP; el ping con eco también actualiza lastEchoAtMs desde la Fase 1). */
        @Volatile var lastTcpSendAtMs: Long = 0L
            private set
        /** Inicio de la sesión TCP actual (se renueva en cada reconexión): los envíos solo valen como prueba de vida en los primeros 3 s sin eco (ver isTvResponding). */
        @Volatile var tcpSessionStartMs: Long = 0L
            private set
        /** Último envío por Bluetooth datos sin excepción (prueba de vida por BT, espejo de lastTcpSendAtMs). */
        @Volatile var lastBtSendAtMs: Long = 0L
            private set
        /** Inicio de la sesión Bluetooth datos actual (se renueva en cada reconexión): igual que tcpSessionStartMs, ver isTvResponding. */
        @Volatile var btSessionStartMs: Long = 0L
            private set
        /**
         * Paquetes de mando recibidos por la TV, leídos del eco de
         * ping estampado (GamepadProtocol.stampTvPacketsReceived:
         * el campo de botones del eco lleva packetsReceived).
         * Acumulado con deltas uint16: sobrevive al envoltorio de
         * la estampa en 65535. Es lo que distingue "la TV no
         * recibe nada" de "recibe pero no inyecta (sin Shizuku)".
         */
        @Volatile var tvPacketsReceived: Long = 0
            private set
        /** ¿La TV está recibiendo paquetes de mando ahora mismo (el contador subió en el último eco)? */
        @Volatile var tvReceivingPackets: Boolean = false
            private set
        /** Momento del último eco con estampa (para saber si el dato está fresco). */
        @Volatile var lastTvPacketsAtMs: Long = 0L
            private set
        /** Última estampa uint16 vista; -1 = aún no llegó ningún eco estampado. */
        @Volatile private var lastTvPacketsStamp: Int = -1

        /**
         * Lee el contador estampado por la TV en un eco de ping.
         * Solo se llama con ecos válidos (sequence coincidente en
         * UDP, cualquier eco en TCP). Si el contador no sube entre
         * dos ecos (~60 paquetes a 120 Hz), la TV no está
         * recibiendo: receiving=false.
         */
        fun recordTvPackets(echoed: GamepadState) {
            val stamp = echoed.buttons
            val now = System.currentTimeMillis()
            val prev = lastTvPacketsStamp
            if (prev >= 0) {
                val delta = (stamp - prev + 65536) % 65536
                if (delta in 1..1000) {
                    tvPacketsReceived += delta
                    tvReceivingPackets = true
                } else if (delta > 1000) {
                    // Ronda 2 P2-19: la TV reinició y su contador
                    // volvió a 0: re-sincronizar sin sumar un delta
                    // fantasma (entre dos ecos a 500 ms caben ~60
                    // paquetes a 120 Hz; más de 1000 no es real).
                    tvPacketsReceived = stamp.toLong()
                    tvReceivingPackets = stamp > 0
                } else {
                    tvReceivingPackets = false
                }
            } else {
                tvPacketsReceived = stamp.toLong()
                tvReceivingPackets = stamp > 0
            }
            lastTvPacketsStamp = stamp
            lastTvPacketsAtMs = now
        }
        @Volatile var activeMethod: TransportMethod = TransportMethod.WIFI_UDP
            private set
        // --- Estadísticas de rendimiento (Fase 3, pantalla Rendimiento) ---
        /** Paquetes de estado enviados desde que se conectó. */
        @Volatile var packetsSent: Long = 0
            private set
        /** Paquetes/s enviados (ventana deslizante de 1 s; objetivo ~120). */
        @Volatile var packetsPerSec: Int = 0
            private set
        /** Jitter del ping (ms, media de |ping[i]-ping[i-1]|, últimas 20 muestras); null con menos de 2 ecos. */
        @Volatile var pingJitterMs: Long? = null
            private set
        private val pingSamples = ArrayDeque<Long>()
        private val sentAtMs = ArrayDeque<Long>()

        fun recordPingSample(pingMs: Long) {
            synchronized(pingSamples) {
                pingSamples.addLast(pingMs)
                while (pingSamples.size > 20) pingSamples.removeFirst()
                pingJitterMs = NetworkStats.jitterMs(pingSamples.toList())
            }
        }

        fun recordSent() {
            val now = System.currentTimeMillis()
            packetsSent++
            synchronized(sentAtMs) {
                sentAtMs.addLast(now)
                while (sentAtMs.isNotEmpty() && now - sentAtMs.first() > 1000) sentAtMs.removeFirst()
                packetsPerSec = sentAtMs.size
            }
        }

        fun resetStats() {
            packetsSent = 0; packetsPerSec = 0; pingJitterMs = null
            synchronized(pingSamples) { pingSamples.clear() }
            synchronized(sentAtMs) { sentAtMs.clear() }
        }

        fun isTvResponding(): Boolean =
            if (activeMethod == TransportMethod.WIFI_TCP) {
                // TCP (2026-11-07): la prueba de vida es el ECO, no
                // el envío. Un write() TCP con éxito solo dice que
                // el kernel aceptó los bytes: si la TV muere sin
                // RST (red caída, app matada, TV dormida), el envío
                // sigue "funcionando" minutos mientras el juego no
                // recibe nada, y AUTO nunca saltaba a HID. El eco
                // (lector TCP, Fase 1) sí prueba que la TV vive.
                // Solo antes del primer eco valen los envíos, y
                // solo en los primeros 3 s de la sesión: pasado ese
                // plazo sin eco, la TV no responde aunque el envío
                // no falle, y AUTO debe probar el siguiente método.
                val now = System.currentTimeMillis()
                if (lastEchoAtMs > 0) {
                    ConnectionHealth.isTvResponding(lastEchoAtMs, now)
                } else {
                    tcpSessionStartMs > 0 &&
                        now - tcpSessionStartMs <= ConnectionHealth.PING_TIMEOUT_MS &&
                        lastTcpSendAtMs > 0 &&
                        now - lastTcpSendAtMs <= ConnectionHealth.PING_TIMEOUT_MS
                }
            } else if (activeMethod == TransportMethod.BLUETOOTH_DATA) {
                // BT (Fase 5): igual que TCP, el canal es un flujo.
                // El eco (lector BT) prueba que la TV vive; solo
                // antes del primer eco valen los envíos, y solo en
                // los primeros 3 s de la sesión.
                val now = System.currentTimeMillis()
                if (lastEchoAtMs > 0) {
                    ConnectionHealth.isTvResponding(lastEchoAtMs, now)
                } else {
                    btSessionStartMs > 0 &&
                        now - btSessionStartMs <= ConnectionHealth.PING_TIMEOUT_MS &&
                        lastBtSendAtMs > 0 &&
                        now - lastBtSendAtMs <= ConnectionHealth.PING_TIMEOUT_MS
                }
            } else {
                ConnectionHealth.isTvResponding(lastEchoAtMs)
            }

        /** Limpia los marcadores de salud (al entrar en HID o al desconectar): un eco viejo de Wi-Fi no debe fingir que la TV responde. */
        fun resetHealth() {
            // Ronda 2 P2-1: también se limpia lastError, o el
            // error viejo resucitaba en la UI cada 500 ms.
            lastError = null
            lastPingMs = null; lastEchoAtMs = 0L; lastTcpSendAtMs = 0L; tcpSessionStartMs = 0L; lastBtSendAtMs = 0L; btSessionStartMs = 0L
            tvPacketsReceived = 0; tvReceivingPackets = false; lastTvPacketsAtMs = 0L; lastTvPacketsStamp = -1
            resetStats()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** WifiLock de baja latencia: se toma al enviar y se suelta al parar. */
    private val wifiLock: WifiLatencyLock by lazy { WifiLatencyLock(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: si el sistema mata el servicio en mitad de
        // una partida, lo rearranca con intent nulo (sin action). Antes
        // se devolvía START_STICKY sin hacer nada: el servicio quedaba
        // parado, sin startForeground y sin enviar, y el mando no volvía
        // solo aunque el comentario lo prometiera. Ahora se restaura la
        // última conexión guardada (A3) y se reanuda el envío; si no hay
        // nada guardado, se para (no tiene sentido un servicio parado).
        val action = intent?.action
        if (action == null) {
            val saved = LastConnectionPrefs.load(this)
            if (saved == null) { stopSelf(); return START_NOT_STICKY }
            activeMethod = saved.method
            startSending(saved.host, saved.port, saved.method, saved.btAddress)
            return START_STICKY
        }
        when (action) {
            ACTION_STOP -> { stopSending(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                // Ronda 2 P2-16: si el servicio se arrancó con
                // startForegroundService y el intent viene sin host,
                // hay que pararse: sin stopSelf() el sistema lo mata
                // con ForegroundServiceDidNotStartInTimeException.
                val host = intent.getStringExtra(EXTRA_HOST) ?: run { stopSelf(); return START_NOT_STICKY }
                val port = intent.getIntExtra(EXTRA_PORT, GamepadProtocol.DEFAULT_PORT)
                val method = runCatching { TransportMethod.valueOf(intent.getStringExtra(EXTRA_METHOD) ?: "WIFI_UDP") }
                    .getOrDefault(TransportMethod.WIFI_UDP)
                val btAddress = intent.getStringExtra(EXTRA_BT_ADDRESS)
                activeMethod = method
                startSending(host, port, method, btAddress)
                return START_STICKY
            }
        }
        // Acción desconocida: pararse (mismo motivo que ACTION_START
        // sin host: un servicio arrancado en primer plano que no se
        // para lo mata el sistema a los ~5-10 s).
        stopSelf()
        return START_NOT_STICKY
    }

    private fun startSending(host: String, port: Int, method: TransportMethod, btAddress: String? = null) {
        try {
            startForeground(1, notification("Enviando por $method a $host:$port…"))
        } catch (e: Exception) {
            // Ronda 2 P1: con foregroundServiceType="dataSync" no
            // exige permiso en tiempo de ejecución, pero si el
            // sistema rechaza el foreground por cualquier motivo,
            // no crashear: error honesto y servicio parado.
            lastError = "No se pudo arrancar el servicio en primer plano (${e.message})"
            stopSelf()
            return
        }
        // A3: el servicio está enviando (la UI lo usa al reabrir la app
        // tras la muerte del proceso para no decir DESCONECTADO).
        LastConnectionPrefs.setRunning(this, true)
        // Perfil activo guardado (Fase 2): el servicio lo lee al
        // arrancar para que sobreviva a la muerte del proceso; cada
        // paquete lleva el profileId en el byte 28.
        GamepadSender.loadSavedProfile(this)
        // Los sockets viven como locales de la corrutina (no como
        // campos): así el finally del bucle viejo cierra solo SUS
        // sockets aunque se ejecute después de lanzar el nuevo bucle
        // — con campos compartidos, el finally viejo podía cerrar el
        // socket nuevo. Cancelar el job basta para que el bucle viejo
        // se deshaga de los suyos.
        job?.cancel()
        // WifiLock de baja latencia: solo para Wi-Fi (BT no lo necesita).
        if (method == TransportMethod.WIFI_UDP || method == TransportMethod.WIFI_TCP) wifiLock.acquire()
        lastError = null; lastPingMs = null; lastEchoAtMs = 0L
        job = scope.launch {
            var udp: UdpTransport? = null
            var tcp: TcpGamepadTransport? = null
            var tcpReaderJob: Job? = null
            var bt: BluetoothDataTransport? = null
            var btReaderJob: Job? = null
            // Fuera del try: el finally (estado cero al parar) usa
            // address y sequence, y lo declarado dentro del try no
            // es visible en el finally.
            var address: InetAddress? = null
            var sequence = 0
            try {
                address = runCatching { InetAddress.getByName(host) }.getOrNull()
                if (address == null) {
                    lastError = "IP no válida: $host"
                    // OpenCode P2-4: soltar el WifiLock antes de
                    // salir: si no, quedaba adquirido hasta
                    // stopSending/onDestroy. Solo se adquirió para
                    // Wi-Fi; soltar sin haberlo adquirido rompería.
                    if (method == TransportMethod.WIFI_UDP || method == TransportMethod.WIFI_TCP) wifiLock.release()
                    // H-07: pararse en vez de quedar en primer plano
                    // sin bucle (servicio zombi); y no dejar
                    // "enviando" guardado, para que un reinicio del
                    // proceso no reintente la misma IP inválida.
                    LastConnectionPrefs.setRunning(this@GamepadSenderService, false)
                    stopSelf()
                    return@launch
                }
                var lastPingSentAt = 0L
                var pingSequence = -1
                // Ronda 2 P1-2: últimas 3 secuencias de ping enviadas.
                // Con RTT > 500 ms el eco del ping anterior llega
                // después de enviar el siguiente: aceptar solo la
                // última secuencia descartaba todos los ecos y AUTO
                // saltaba aunque la TV respondiera.
                val recentPingSequences = ArrayDeque<Int>()
                lastTcpSendAtMs = 0L
                lastBtSendAtMs = 0L
                while (isActive) {
                    try {
                        val state = GamepadSender.currentState.copy(
                            sequence = sequence++,
                            timestampMs = System.currentTimeMillis(),
                        )
                        when (method) {
                            TransportMethod.WIFI_UDP -> {
                                if (udp == null) udp = UdpTransport()
                                udp.send(state, address, port, GamepadSender.activeProfileId)
                                recordSent()
                                val now = System.currentTimeMillis()
                                if (now - lastPingSentAt >= 500) {
                                    lastPingSentAt = now; pingSequence = sequence; recentPingSequences.addLast(pingSequence); while (recentPingSequences.size > 3) recentPingSequences.removeFirst()
                                    udp.sendPing(pingSequence, now, address, port)
                                }
                                udp.receive(1) { echoed, isPing, _ ->
                                    if (isPing && echoed.sequence in recentPingSequences) {
                                        lastPingMs = System.currentTimeMillis() - echoed.timestampMs
                                        recordPingSample(lastPingMs!!)
                                        lastEchoAtMs = System.currentTimeMillis()
                                        recordTvPackets(echoed)
                                    }
                                }
                            }
                            TransportMethod.WIFI_TCP -> {
                                if (tcp == null) {
                                    tcp = TcpGamepadTransport(address, port + 1)
                                    tcp.connect()
                                    // Sesión TCP nueva: los ecos viejos
                                    // de la sesión anterior no valen
                                    // (isTvResponding es por eco; un eco
                                    // de hace 2 s no debe fingir que la
                                    // TV nueva responde) y el plazo de
                                    // gracia de "envío sin eco" empieza
                                    // ahora, no en la sesión anterior.
                                    lastEchoAtMs = 0L; lastPingMs = null
                                    tcpSessionStartMs = System.currentTimeMillis()
                                    // Lector de ecos TCP (Fase 1): el eco del
                                    // ping llega por el mismo socket y hay
                                    // que leerlo en otra corrutina, o el
                                    // ping TCP no tendría valor. Hijo del
                                    // bucle: al cancelar el job, el lector
                                    // muere con él; al reconectar, se
                                    // cancela el viejo y se lanza otro.
                                    val connectedTcp = tcp
                                    tcpReaderJob?.cancel()
                                    tcpReaderJob = launch {
                                        while (isActive && tcp === connectedTcp) {
                                            val raw = try {
                                                connectedTcp.readRaw(1000)
                                            } catch (_: Exception) {
                                                break // Socket muerto: el bucle principal reconecta.
                                            } ?: continue
                                            if (!GamepadProtocol.isPing(raw)) continue
                                            val echoed = GamepadProtocol.decode(raw) ?: continue
                                            lastPingMs = System.currentTimeMillis() - echoed.timestampMs
                                            recordPingSample(lastPingMs!!)
                                            lastEchoAtMs = System.currentTimeMillis()
                                            recordTvPackets(echoed)
                                        }
                                    }
                                }
                                // La escritura TCP puede bloquearse para siempre si la TV
                                // deja de leer (ventana llena): con timeout, un envío
                                // atascado se trata como fallo y se reintenta, en vez de
                                // dejar el bucle tieso sin que salte el fallback.
                                // (val local: dentro de la lambda no vale el smart-cast del var.)
                                val currentTcp = tcp ?: throw java.io.IOException("TCP no conectado")
                                val sent = withTimeoutOrNull(500) { currentTcp.send(state, GamepadSender.activeProfileId); true } ?: false
                                if (!sent) throw java.io.IOException("TCP sin respuesta (escritura bloqueada)")
                                recordSent()
                                // Ping TCP con eco (Fase 1): cada 500 ms
                                // igual que por UDP. El lector de arriba
                                // mide el RTT real; el envío sin
                                // excepción sigue siendo la prueba de
                                // vida (ver isTvResponding()).
                                val now = System.currentTimeMillis()
                                if (now - lastPingSentAt >= 500) {
                                    lastPingSentAt = now; pingSequence = sequence; recentPingSequences.addLast(pingSequence); while (recentPingSequences.size > 3) recentPingSequences.removeFirst()
                                    runCatching { currentTcp.sendPing(pingSequence, now) }
                                }
                                lastTcpSendAtMs = System.currentTimeMillis()
                            }
                            TransportMethod.BLUETOOTH_DATA -> {
                                if (bt == null) {
                                    val transport = BluetoothDataTransport(this@GamepadSenderService)
                                    val address = btAddress ?: throw java.io.IOException("Sin TV Bluetooth elegida")
                                    if (!transport.connect(address)) throw java.io.IOException(transport.lastError ?: "Bluetooth SPP sin conexión")
                                    bt = transport
                                    // Sesión BT nueva: igual que TCP, los ecos
                                    // viejos no valen y el plazo de gracia de
                                    // "envío sin eco" empieza ahora.
                                    lastEchoAtMs = 0L; lastPingMs = null
                                    btSessionStartMs = System.currentTimeMillis()
                                    // Lector de ecos BT (Fase 5): el eco del
                                    // ping llega por el mismo socket RFCOMM y
                                    // hay que leerlo en otra corrutina, como
                                    // por TCP. Hijo del bucle: al cancelar el
                                    // job, el lector muere con él.
                                    val connectedBt = transport
                                    btReaderJob?.cancel()
                                    btReaderJob = launch {
                                        while (isActive && bt === connectedBt) {
                                            val raw = try {
                                                connectedBt.readRaw(1000)
                                            } catch (_: Exception) {
                                                break // Socket muerto: el bucle principal reconecta.
                                            } ?: continue
                                            if (!GamepadProtocol.isPing(raw)) continue
                                            val echoed = connectedBt.recordEcho(raw, recentPingSequences.toSet()) ?: continue
                                            lastPingMs = connectedBt.lastPingMs
                                            if (lastPingMs != null) recordPingSample(lastPingMs!!)
                                            lastEchoAtMs = System.currentTimeMillis()
                                            recordTvPackets(echoed)
                                        }
                                    }
                                }
                                // La escritura RFCOMM puede bloquearse si la TV
                                // deja de leer: con timeout, un envío atascado
                                // se trata como fallo y se reintenta, igual que TCP.
                                val currentBt = bt ?: throw java.io.IOException("Bluetooth SPP no conectado")
                                val sent = withTimeoutOrNull(500) { currentBt.send(state, GamepadSender.activeProfileId); true } ?: false
                                if (!sent) throw java.io.IOException("Bluetooth SPP sin respuesta (escritura bloqueada)")
                                recordSent()
                                // Ping BT con eco (Fase 5): cada 500 ms
                                // igual que por Wi-Fi/TCP.
                                val now = System.currentTimeMillis()
                                if (now - lastPingSentAt >= 500) {
                                    lastPingSentAt = now; pingSequence = sequence; recentPingSequences.addLast(pingSequence); while (recentPingSequences.size > 3) recentPingSequences.removeFirst()
                                    runCatching { currentBt.sendPing(pingSequence, now) }
                                }
                                lastBtSendAtMs = System.currentTimeMillis()
                            }
                            else -> { lastError = "Método $method no es de este servicio"; delay(1000) }
                        }
                        lastError = null
                    } catch (e: Exception) {
                        // La cancelación no es un error de red: no
                        // tragársela, o el bucle no terminaría nunca.
                        if (e is CancellationException) throw e
                        // Reconexión automática: no salir del bucle.
                        lastError = e.message ?: e.javaClass.simpleName
                        tcpReaderJob?.cancel(); tcpReaderJob = null
                        btReaderJob?.cancel(); btReaderJob = null
                        runCatching { tcp?.close() }; tcp = null
                        runCatching { udp?.close() }; udp = null
                        runCatching { bt?.close() }; bt = null
                        delay(1000)
                    }
                    delay(8) // ~120 Hz nominal: el bucle tarda 8 ms + el trabajo de envío; la cifra real que se muestra es la medida (recordSent, ventana deslizante de 1 s), no este 8.
                }
            } finally {
                // H2: al parar, soltar los controles en la TV con un
                // estado cero ANTES de cerrar los sockets. Es el
                // envío que evita el botón/stick atascado al
                // desconectar (también al cambiar de método en AUTO).
                // Mejor esfuerzo: si la red ya murió, el watchdog
                // de la TV lo suelta a los 2 s. Una escritura de
                // 32 bytes no se bloquea en la práctica (o el
                // kernel la acepta, o el socket roto lanza
                // excepción); va con runCatching para no alargar
                // el cierre.
                tcpReaderJob?.cancel(); tcpReaderJob = null
                btReaderJob?.cancel(); btReaderJob = null
                val zero = GamepadState(sequence = sequence, timestampMs = System.currentTimeMillis())
                val addr = address
                // El estado cero también lleva el profileId activo:
                // sin él, la TV lo leería como Xbox 360 y recrearía
                // el mando equivocado al desconectar con otro perfil.
                val profileId = GamepadSender.activeProfileId
                if (addr != null) runCatching { udp?.send(zero, addr, port, profileId) }
                runCatching { tcp?.send(zero, profileId) }
                runCatching { bt?.send(zero, profileId) }
                // Al cancelar o salir, los sockets no quedan abiertos.
                runCatching { tcp?.close() }; tcp = null
                runCatching { udp?.close() }; udp = null
                runCatching { bt?.close() }; bt = null
            }
        }
    }

    private fun stopSending() {
        job?.cancel(); job = null
        wifiLock.release()
        // A3: ya no está enviando.
        LastConnectionPrefs.setRunning(this, false)
    }

    override fun onDestroy() { stopSending(); scope.cancel(); super.onDestroy() }

    private fun notification(text: String): Notification {
        createNotificationChannel(this)
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

    /**
     * Perfil de emulación activo (Fase 2, GamepadProfiles).
     * Es el perfil VISUAL y el de EMULACIÓN a la vez: la pantalla
     * TIPO DE MANDO (Fase 3) lo cambia aquí y el servicio de envío
     * lo mete en el byte 28 de cada paquete; la TV recrea el
     * dispositivo virtual con ese perfil. Nunca se muestra un mando
     * de PlayStation mientras la TV expone un Xbox 360.
     */
    @Volatile var activeProfileId: Int = GamepadProfiles.XBOX_360_ID
        private set

    /** Cambia el perfil activo y lo guarda (sobrevive a reinicios). La Fase 3 lo llamará desde la pantalla TIPO DE MANDO. */
    fun setActiveProfile(context: android.content.Context, profileId: Int) {
        val id = GamepadProfiles.fromId(profileId).id
        activeProfileId = id
        GamepadProfilePrefs.save(context, id)
    }

    /** Carga el perfil guardado (al arrancar el servicio de envío). */
    fun loadSavedProfile(context: android.content.Context) {
        activeProfileId = GamepadProfilePrefs.load(context)
    }
}
