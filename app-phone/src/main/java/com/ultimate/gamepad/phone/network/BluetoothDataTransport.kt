package com.ultimate.gamepad.phone.network

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.GamepadTransport
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportStatus
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bluetooth datos a la app receptora por SPP/RFCOMM — Fase 5, implementación real.
 *
 * DECISIÓN DE DISEÑO (2026-11-07): SPP/RFCOMM clásico, NO BLE/GATT.
 * - El paquete es de 32 bytes y cabe en UNA escritura RFCOMM: el
 *   socket es un flujo continuo de bytes, igual que TCP (misma
 *   lectura con readFully(32), mismo decode, sin fragmentación).
 * - BLE no cabe: la MTU ATT por defecto es 23 (20 bytes útiles);
 *   haría falta negociar MTU > 32 (no garantizado en todas las
 *   TVs) o fragmentar en 2 notificaciones con reensamblado y
 *   orden. Además la TV tendría que ser servidor GATT + anunciar
 *   (el anuncio BLE en Android TV es poco fiable) y el teléfono
 *   escanear + descubrir servicios + escribir descriptores: más
 *   piezas, más modos de fallo, y el intervalo de conexión BLE
 *   (7,5–30 ms+) limita la latencia más que SPP.
 * - SPP es el perfil serie estándar: la TV escucha con
 *   listenUsingRfcommWithServiceRecord y el teléfono conecta con
 *   createRfcommSocketToServiceRecord, con el UUID SPP universal.
 *   El emparejamiento clásico es el flujo que el usuario ya
 *   conoce (Ajustes > Bluetooth) y el primer connect() lo dispara
 *   solo si la TV aún no está emparejada.
 * - El teléfono es el CLIENTE (inicia la conexión) y la TV el
 *   SERVIDOR (escucha/acepta): la TV siempre está encendida y
 *   escuchando; el teléfono se conecta cuando el usuario juega.
 * - Si la pila BT de la Hyundai rechazara el listen RFCOMM (algunas
 *   TVs bloquean el registro SDP a apps no de sistema), el
 *   síntoma sería listen() fallando en la TV o connect() fallando
 *   con "Service discovery failed" estando emparejados: en ese
 *   caso el plan B es BLE/GATT (servidor GATT en la TV +
 *   fragmentación o MTU > 32). Este archivo no lo implementa.
 *
 * HONESTIDAD (reglas duras del proyecto):
 * - [status] solo es CONNECTED tras el PRIMER ECO DE PING válido:
 *   un socket abierto sin eco es AVAILABLE ("socket abierto,
 *   esperando eco"), nunca "Conectado". Sin paquetes reales no
 *   hay "Conectado".
 * - Sin RTT no hay ping en ms: [lastPingMs] es null hasta que
 *   llega un eco con la secuencia pedida; la UI debe mostrar "—".
 * - Si el Bluetooth está apagado, falta BLUETOOTH_CONNECT o la
 *   dirección no es válida, el estado es UNAVAILABLE/FAILED con
 *   el motivo real en [lastError], nunca un "Conectado" fingido.
 * - [connect] nunca se cuelga: el connect() bloqueante corre en
 *   un hilo aparte con latch y plazo; al agotar el plazo se cierra
 *   el socket (eso aborta el connect) y se devuelve false.
 * - [send] lanza si el canal está roto (como TcpGamepadTransport):
 *   el llamador (GamepadSenderService) reintenta o cambia de
 *   método; nunca se traga el fallo en silencio.
 *
 * HILO: connect()/send()/readRaw() deben llamarse desde un hilo
 * de fondo (Dispatchers.IO), como el bucle de GamepadSenderService.
 * readRaw() sondea available() con plazo: no hay otro hilo lector.
 * El envío a 120 Hz por RFCOMM son 32 B × 120 = 3,8 KB/s, trivial
 * para SPP; la escritura va envuelta por el llamador en
 * withTimeoutOrNull(500) igual que TCP (ver cableado en el
 * manifiesto w5.md), por si la TV deja de leer y la ventana se
 * llena.
 *
 * La TV receptora es BluetoothDataReceiverImpl (app-tv): mismo
 * UUID, mismo paquete, mismo recordPacket/deliver que UDP/TCP.
 */
class BluetoothDataTransport(private val context: Context) : GamepadTransport {

    companion object {
        /**
         * UUID SPP estándar (Serial Port Profile, Bluetooth SIG).
         * DEBE SER EL MISMO en la TV (BluetoothDataReceiverImpl):
         * es lo que une el socket del teléfono con el servidor de
         * la TV. No es un UUID propio: cualquier app SPP lo usa.
         */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Nombre del servicio SDP que ve la TV al escuchar. Nombre de app, no de mando: nunca es el nombre del dispositivo Bluetooth. */
        const val SERVICE_NAME: String = "Ultimate Gamepad"

        /** Plazo por defecto de connect(): BluetoothSocket.connect() no admite timeout propio. */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 5000

        /** Tope del backoff de reintento (mismo 1–15 s que el reintento HID). */
        const val MAX_BACKOFF_MS: Long = 15_000L

        /** Pasos del backoff entre intentos: 1 s, 2 s, 4 s, 8 s, 15 s… */
        private val BACKOFF_STEPS_MS: LongArray = longArrayOf(1000, 2000, 4000, 8000, 15000)
    }

    /** Un dispositivo Bluetooth emparejado, listo para conectar por SPP (nombre + MAC). */
    data class BondedBtDevice(val name: String, val address: String)

    override val method: TransportMethod = TransportMethod.BLUETOOTH_DATA

    /**
     * Estado REAL del transporte. UNAVAILABLE = no se puede intentar
     * (BT apagado, sin permiso, sin adapter); AVAILABLE = socket
     * abierto esperando el primer eco; CONNECTED = primer eco válido
     * recibido (hay paquetes reales); FAILED = se intentó y falló
     * (ver [lastError]). Nunca PENDING_STAGE2: la Fase 5 existe.
     */
    @Volatile var status: TransportStatus = TransportStatus.UNAVAILABLE
        private set

    /** Motivo real del último fallo / por qué no se puede intentar. Null si no hay nada que decir. */
    @Volatile var lastError: String? = null
        private set

    /** MAC de la TV conectada (o null si no hay socket). Es lo que hay que persistir para el AUTO (ver manifiesto). */
    @Volatile var connectedDeviceAddress: String? = null
        private set

    /** Nombre Bluetooth de la TV conectada (o null). Solo informativo: nunca se usa como identidad del mando. */
    @Volatile var connectedDeviceName: String? = null
        private set

    /** Momento del último eco de ping válido (para la prueba de vida del llamador). */
    @Volatile var lastEchoAtMs: Long = 0L
        private set

    /** RTT real del último eco (ms). Null = aún no llegó ningún eco: la UI muestra "—", nunca un número inventado. */
    @Volatile var lastPingMs: Long? = null
        private set

    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null
    private var input: DataInputStream? = null

    /** true tras close(): cancela el backoff y las lecturas en curso. connect() lo pone a false (nuevo intento explícito). */
    @Volatile private var closed = false

    // ---------------------------------------------------------------
    // Disponibilidad (sin conectar): permiso, adapter, radio.
    // ---------------------------------------------------------------

    /** BLUETOOTH_CONNECT (Android 12+): sin él, adapter.isEnabled, getBondedDevices y connect() lanzan SecurityException. En API < 31 el permiso es de instalación y siempre está. */
    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(): BluetoothAdapter? =
        runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }.getOrNull()

    /** ¿El Bluetooth está encendido? Permiso primero (A1): isEnabled sin permiso puede lanzar SecurityException en API 31+. */
    fun isBluetoothEnabled(): Boolean {
        if (!hasConnectPermission()) return false
        val adapter = adapter() ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    /**
     * Comprueba si se puede intentar el SPP y deja [status]/[lastError]
     * con el veredicto real. Es lo que el ViewModel llama al abrir la
     * pantalla de conexión (igual que refreshHidStatus). No conecta.
     */
    fun refreshStatus(): TransportStatus {
        if (!hasConnectPermission()) {
            status = TransportStatus.UNAVAILABLE
            lastError = "Falta el permiso de Bluetooth (BLUETOOTH_CONNECT). Toca \"Dar permiso de Bluetooth\"."
            return status
        }
        val adapter = adapter()
        if (adapter == null) {
            status = TransportStatus.UNAVAILABLE
            lastError = "Sin Bluetooth en este teléfono."
            return status
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            status = TransportStatus.UNAVAILABLE
            lastError = "Bluetooth apagado. Enciéndelo en Ajustes > Bluetooth."
            return status
        }
        // Radio encendida y permiso concedido: se puede intentar.
        // No es "Disponible" con TV: sin TV emparejada no hay a
        // quién conectar (ver bondedDevices/connect).
        if (status != TransportStatus.CONNECTED && status != TransportStatus.AVAILABLE) {
            status = TransportStatus.AVAILABLE
            lastError = null
        }
        return status
    }

    /**
     * TVs (y demás dispositivos) ya emparejados en Ajustes >
     * Bluetooth. La TV se empareja UNA vez en los ajustes del
     * sistema; después el teléfono conecta por su MAC sin escanear
     * (sin BLUETOOTH_SCAN, sin 12 s de descubrimiento). Si la lista
     * está vacía, el motivo va en [lastError]: emparejar primero.
     */
    fun bondedDevices(): List<BondedBtDevice> {
        if (!hasConnectPermission()) {
            lastError = "Falta el permiso de Bluetooth (BLUETOOTH_CONNECT)."
            return emptyList()
        }
        val adapter = adapter() ?: run {
            lastError = "Sin Bluetooth en este teléfono."
            return emptyList()
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            lastError = "Bluetooth apagado. Enciéndelo en Ajustes > Bluetooth."
            return emptyList()
        }
        return runCatching {
            adapter.bondedDevices
                .mapNotNull { device ->
                    val name = runCatching { device.name }.getOrNull() ?: "Dispositivo Bluetooth"
                    BondedBtDevice(name, device.address)
                }
                .sortedBy { it.name.lowercase() }
        }.onFailure {
            lastError = "No se pudo leer los dispositivos emparejados (${it.message})."
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------
    // Conexión SPP/RFCOMM (teléfono = cliente, TV = servidor).
    // ---------------------------------------------------------------

    /**
     * Conecta por SPP a la TV ([deviceAddress] = MAC, p. ej. la
     * elegida de [bondedDevices] y persistida para el AUTO).
     * Nunca se cuelga: el connect() bloqueante corre en un hilo
     * aparte y el plazo [timeoutMs] se cumple con latch; al
     * agotar, cerrar el socket aborta el connect() y se devuelve
     * false con el motivo en [lastError]. Devuelve true con el
     * socket abierto (estado AVAILABLE: el CONNECTED real llega
     * con el primer eco, ver [recordEcho]).
     *
     * Primer connect() con una TV no emparejada: Android dispara el
     * diálogo de emparejamiento en el teléfono (y la TV pide
     * confirmar); si el usuario lo rechaza, connect() falla con
     * IOException y el motivo lo dice.
     */
    fun connect(deviceAddress: String, timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): Boolean {
        closed = false
        closeSocket()
        lastError = null
        if (!hasConnectPermission()) {
            status = TransportStatus.UNAVAILABLE
            lastError = "Falta el permiso de Bluetooth (BLUETOOTH_CONNECT). Toca \"Dar permiso de Bluetooth\"."
            return false
        }
        val adapter = adapter()
        if (adapter == null) {
            status = TransportStatus.UNAVAILABLE
            lastError = "Sin Bluetooth en este teléfono."
            return false
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            status = TransportStatus.UNAVAILABLE
            lastError = "Bluetooth apagado. Enciéndelo en Ajustes > Bluetooth."
            return false
        }
        if (!BluetoothAdapter.checkBluetoothAddress(deviceAddress)) {
            status = TransportStatus.FAILED
            lastError = "Dirección Bluetooth no válida: \"$deviceAddress\". Vuelve a elegir la TV emparejada."
            return false
        }
        status = TransportStatus.AVAILABLE // Intentando: aún no hay socket ni eco.
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(deviceAddress) }.getOrNull() ?: run {
            status = TransportStatus.FAILED
            lastError = "No se pudo resolver la TV ($deviceAddress)."
            return false
        }
        val sock: BluetoothSocket = runCatching { device.createRfcommSocketToServiceRecord(SPP_UUID) }.onFailure {
            status = TransportStatus.FAILED
            lastError = if (it is SecurityException) {
                "Falta el permiso de Bluetooth (BLUETOOTH_CONNECT)."
            } else {
                "No se pudo abrir el socket SPP (${it.message})."
            }
            return false
        }.getOrNull() ?: return false

        // BluetoothSocket.connect() no tiene timeout: hilo aparte +
        // latch con plazo. Si el plazo se agota, close() aborta el
        // connect() bloqueado; el hilo es daemon y no retiene nada.
        var connectError: Exception? = null
        val latch = CountDownLatch(1)
        val connectThread = Thread({
            try {
                sock.connect()
            } catch (e: IOException) {
                connectError = e
            } catch (e: SecurityException) {
                connectError = e
            } finally {
                latch.countDown()
            }
        }, "bt-spp-connect").apply { isDaemon = true }
        connectThread.start()
        val finished = runCatching { latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { sock.close() }
            runCatching { connectThread.join(500) }
            status = TransportStatus.FAILED
            lastError = "Tiempo agotado conectando por Bluetooth a $deviceAddress (${timeoutMs} ms). La TV no acepta SPP o está fuera de alcance."
            return false
        }
        if (connectError != null || !runCatching { sock.isConnected }.getOrDefault(false)) {
            runCatching { sock.close() }
            status = TransportStatus.FAILED
            lastError = when (val e = connectError) {
                is SecurityException -> "Falta el permiso de Bluetooth (BLUETOOTH_CONNECT)."
                null -> "La TV rechazó la conexión SPP."
                else -> "No se pudo conectar por Bluetooth SPP (${e.message}). Si la TV no está emparejada, empárejala en Ajustes > Bluetooth y reintenta."
            }
            return false
        }
        synchronized(lock) {
            socket = sock
            out = runCatching { DataOutputStream(sock.outputStream) }.getOrNull()
            input = runCatching { DataInputStream(sock.inputStream) }.getOrNull()
            if (out == null || input == null) {
                runCatching { sock.close() }
                socket = null; out = null; input = null
                status = TransportStatus.FAILED
                lastError = "Socket SPP abierto pero sin flujo de entrada/salida."
                return false
            }
        }
        connectedDeviceAddress = deviceAddress
        connectedDeviceName = runCatching { device.name }.getOrNull()
        // Socket abierto, pero SIN eco aún: AVAILABLE, no CONNECTED.
        // El primer eco válido (recordEcho) es lo que marca CONNECTED.
        status = TransportStatus.AVAILABLE
        lastError = null
        return true
    }

    /**
     * Conecta con reintento y backoff (1 s, 2 s, 4 s, 8 s, 15 s…,
     * tope 15 s). Se cancela con [close()]: el bucle comprueba
     * [closed] entre intentos y durante la espera. Devuelve false
     * si se agotan los intentos o se canceló; el motivo del último
     * fallo queda en [lastError].
     */
    fun connectWithRetry(deviceAddress: String, maxAttempts: Int = 5): Boolean {
        var attempt = 0
        while (!closed && attempt < maxAttempts) {
            if (connect(deviceAddress)) return true
            attempt++
            if (attempt >= maxAttempts || closed) break
            val waitMs = BACKOFF_STEPS_MS.getOrElse(attempt - 1) { MAX_BACKOFF_MS }
            val deadline = System.currentTimeMillis() + waitMs
            while (!closed && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(100) }
            }
        }
        return false
    }

    /** ¿Hay un socket SPP abierto ahora mismo? No es "la TV recibe": eso solo lo prueba el eco (ver [recordEcho]). */
    fun isConnected(): Boolean = synchronized(lock) {
        !closed && socket?.let { runCatching { it.isConnected }.getOrDefault(false) } == true
    }

    // ---------------------------------------------------------------
    // Envío (mismo paquete de 32 bytes que UDP/TCP) y eco de ping.
    // ---------------------------------------------------------------

    override fun send(state: GamepadState) = send(state, GamepadProfiles.XBOX_360_ID)

    /** [profileId] viaja en el byte 28 del paquete (Fase 2, GamepadProfiles). Lanza si el canal está roto: el llamador reintenta o cambia de método. */
    fun send(state: GamepadState, profileId: Int) {
        val o: DataOutputStream
        val s: BluetoothSocket
        synchronized(lock) {
            o = out ?: throw IllegalStateException("Bluetooth no conectado; llama a connect() primero")
            s = socket ?: throw IllegalStateException("Bluetooth no conectado; llama a connect() primero")
        }
        if (closed || !runCatching { s.isConnected }.getOrDefault(false)) {
            throw IOException("Bluetooth desconectado")
        }
        o.write(GamepadProtocol.encode(state, profileId))
        o.flush()
    }

    /** Ping SPP (Fase 1): mismo paquete FLAG_PING que por UDP/TCP; la TV lo devuelve tal cual por el mismo socket. */
    fun sendPing(sequence: Int, timestampMs: Long) {
        val o: DataOutputStream
        synchronized(lock) {
            o = out ?: throw IllegalStateException("Bluetooth no conectado; llama a connect() primero")
        }
        o.write(GamepadProtocol.encodePing(sequence, timestampMs))
        o.flush()
    }

    /**
     * Lee exactamente 32 bytes con plazo; null solo por timeout (no
     * es un error). Lanza IOException si el socket murió: el
     * llamador debe cerrar y reconectar, no seguir leyendo de un
     * socket muerto.
     *
     * BluetoothSocket no tiene soTimeout: la lectura se hace
     * sondeando available() en el hilo de fondo (el lector de ecos
     * del servicio, Dispatchers.IO). A 2 ecos/s el coste es nulo;
     * el camino de datos (120 Hz) solo escribe, nunca lee.
     */
    fun readRaw(timeoutMs: Int): ByteArray? {
        val stream: DataInputStream = synchronized(lock) {
            input ?: throw IOException("Bluetooth no conectado")
        }
        val buf = ByteArray(GamepadProtocol.PACKET_SIZE)
        var read = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (read < GamepadProtocol.PACKET_SIZE) {
            if (closed) throw IOException("Bluetooth cerrado")
            val available = runCatching { stream.available() }.getOrDefault(0)
            if (available > 0) {
                val n = stream.read(buf, read, minOf(available, GamepadProtocol.PACKET_SIZE - read))
                if (n < 0) throw IOException("La TV cerró la conexión Bluetooth")
                if (n == 0) {
                    if (System.currentTimeMillis() >= deadline) return null
                    runCatching { Thread.sleep(5) }
                } else {
                    read += n
                }
            } else {
                if (System.currentTimeMillis() >= deadline) return null
                runCatching { Thread.sleep(5) }
            }
        }
        return buf
    }

    /**
     * Valida un eco de ping leído con [readRaw]: tiene que ser ping
     * y llevar la secuencia pedida. Si lo es, actualiza [lastPingMs]
     * (RTT real) y [lastEchoAtMs], marca CONNECTED (primer eco
     * válido = paquetes reales) y devuelve el estado decodificado
     * para que el llamador estampe el contador de la TV
     * (GamepadSenderService.recordTvPackets). Si no lo es, devuelve
     * null y no toca nada: un eco viejo o corrupto no finge salud.
     */
    fun recordEcho(raw: ByteArray, pingSequence: Int): GamepadState? {
        if (!GamepadProtocol.isPing(raw)) return null
        val echoed = GamepadProtocol.decode(raw) ?: return null
        if (echoed.sequence != pingSequence) return null
        lastPingMs = System.currentTimeMillis() - echoed.timestampMs
        lastEchoAtMs = System.currentTimeMillis()
        status = TransportStatus.CONNECTED
        return echoed
    }

    // ---------------------------------------------------------------
    // Cierre limpio.
    // ---------------------------------------------------------------

    /** Cierra solo el socket y sus flujos (sin tocar [closed] ni [status]): lo usan connect() y close(). */
    private fun closeSocket() {
        synchronized(lock) {
            runCatching { out?.close() }
            runCatching { input?.close() }
            runCatching { socket?.close() }
            out = null; input = null; socket = null
        }
    }

    /**
     * Cierre limpio: aborta un connect() en curso, despierta un
     * readRaw() bloqueado (lanza IOException), cancela el backoff
     * de [connectWithRetry] y deja el estado en UNAVAILABLE sin
     * motivo pendiente (un cierre limpio no es un fallo).
     */
    override fun close() {
        closed = true
        closeSocket()
        connectedDeviceAddress = null
        connectedDeviceName = null
        lastEchoAtMs = 0L
        lastPingMs = null
        status = TransportStatus.UNAVAILABLE
        lastError = null
    }
}
