package com.ultimate.gamepad.tv.receiver

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ultimate.gamepad.core.BluetoothDataReceiver
import com.ultimate.gamepad.core.ConnectionHealth
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportStatus
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Receptor Bluetooth-datos por SPP/RFCOMM en la TV — Fase 5, implementación real.
 *
 * La TV es el SERVIDOR: escucha con listenUsingRfcommWithServiceRecord
 * (UUID SPP estándar, el mismo que usa el teléfono) y acepta la
 * conexión que inicia el teléfono. El socket es un flujo continuo de
 * bytes, igual que TCP: cada paquete son exactamente 32 bytes leídos
 * con readFully, sin fragmentación ni MTU (ver la decisión SPP
 * frente a BLE/GATT en BluetoothDataTransport, app-phone).
 *
 * MISMO TRATO QUE UDP/TCP: cada paquete válido pasa por el mismo
 * punto único — UdpReceiverService.recordPacket(state,
 * BLUETOOTH_DATA) + UdpReceiverService.deliver(context, state, raw)
 * — así la inyección (uinput/inject vía Shizuku), el perfil del
 * byte 28, la secuencia compartida y el watchdog de 2 s son los
 * mismos que por Wi-Fi. Los pings se devuelven con el contador de
 * paquetes estampado, igual que por UDP/TCP, para que el teléfono
 * mida un ping real y sepa si la TV recibe. NO se toca
 * UdpReceiverService.kt: este receptor solo LLAMA a sus funciones
 * del companion (ya públicas). Los paquetes de teclado/ratón
 * (ExtrasProtocol, Fase 4) NO viajan por Bluetooth en esta fase:
 * solo el paquete de mando de 32 bytes; teclado/ratón siguen por
 * Wi-Fi (ver manifiesto w5.md).
 *
 * HONESTIDAD (reglas duras del proyecto):
 * - [status] solo es CONNECTED con paquetes reales en los últimos
 *   2 s (ConnectionHealth.isAlive, igual que la TV decide
 *   "Conectado" por Wi-Fi). Socket abierto sin paquetes es
 *   AVAILABLE; escuchando sin cliente también es AVAILABLE.
 * - Si el Bluetooth está apagado, falta BLUETOOTH_CONNECT o
 *   BLUETOOTH_ADVERTISE, o no hay adapter, el estado es
 *   UNAVAILABLE con el motivo real en [statusDetail]; si el listen
 *   falla, FAILED con el motivo. Nunca PENDING_STAGE2: la Fase 5
 *   existe. Nunca "Conectado" sin paquetes.
 * - Sin RTT no hay ping en ms: este receptor no inventa latencia;
 *   el eco es el mismo paquete de ping devuelto y el teléfono mide
 *   el RTT.
 *
 * HILO: start() abre el servidor y lanza un hilo daemon de accept;
 * cada cliente se sirve en ese mismo hilo (un teléfono a la vez:
 * un segundo teléfono que conecte mientras hay uno jugando queda
 * esperando en accept hasta que el primero suelte). stop() cierra
 * los sockets — eso despierta el accept() y el readFully()
 * bloqueados — y espera como mucho 500 ms al hilo. Nunca se
 * cuelga: stop() no espera más de ~500 ms.
 *
 * PERMISOS (TV): hacen falta BLUETOOTH_CONNECT (usar el adapter y
 * leer el nombre del dispositivo) y BLUETOOTH_ADVERTISE
 * (publicar el registro SDP al escuchar) en Android 12+. El
 * manifest de la TV aún NO los declara (ver cableado en el
 * manifiesto w5.md): sin ellos, start() deja UNAVAILABLE con el
 * motivo y no crashea. Se comprueba el permiso ANTES de tocar el
 * adapter (A1) y todo acceso al adapter va en runCatching.
 */
class BluetoothDataReceiverImpl(private val context: Context) : BluetoothDataReceiver {

    companion object {
        /**
         * UUID SPP estándar (Serial Port Profile, Bluetooth SIG).
         * DEBE SER EL MISMO que en el teléfono
         * (BluetoothDataTransport.SPP_UUID): es lo que une el
         * servidor de la TV con el socket del teléfono.
         */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Nombre del servicio SDP que publica la TV al escuchar. Nombre de app, no de mando: nunca es el nombre del dispositivo Bluetooth. */
        const val SERVICE_NAME: String = "Ultimate Gamepad"
    }

    /** Estado REAL, calculado de los campos volátiles (nunca guardado a mano para no mentir). */
    override val status: TransportStatus
        get() = when {
            !listening && lastError != null -> TransportStatus.FAILED
            !listening -> TransportStatus.UNAVAILABLE
            clientConnected && ConnectionHealth.isAlive(lastPacketAtMs) -> TransportStatus.CONNECTED
            else -> TransportStatus.AVAILABLE
        }

    /**
     * Motivo legible del estado actual, para la pantalla de la TV:
     * "Escuchando por Bluetooth SPP, esperando al teléfono",
     * "Conectado a <nombre> por Bluetooth", "Bluetooth apagado en
     * la TV", "Falta el permiso …", o el error del listen. Nunca
     * vacío: siempre hay algo que enseñar.
     */
    val statusDetail: String
        get() = when {
            !listening && lastError != null -> lastError ?: "Error de Bluetooth."
            !listening -> "Receptor Bluetooth parado."
            clientConnected && ConnectionHealth.isAlive(lastPacketAtMs) ->
                "Conectado por Bluetooth a ${connectedDeviceName ?: connectedDeviceAddress ?: "teléfono"} · $packetsReceived paquetes."
            clientConnected -> "Teléfono conectado por Bluetooth sin paquetes (más de 2 s sin entrada)."
            else -> "Escuchando por Bluetooth SPP, esperando al teléfono."
        }

    /** Paquetes de mando recibidos SOLO por Bluetooth (sin contar UDP/TCP). El eco de ping estampa el contador TOTAL de UdpReceiverService.packetsReceived, igual que por Wi-Fi. */
    @Volatile var packetsReceived: Long = 0
        private set

    /** Nombre Bluetooth del teléfono conectado (o null). Solo informativo. */
    @Volatile var connectedDeviceName: String? = null
        private set

    /** MAC del teléfono conectado (o null). Solo informativa. */
    @Volatile var connectedDeviceAddress: String? = null
        private set

    /** Último error real (o null). Si no se está escuchando y hay error, [status] es FAILED. */
    @Volatile var lastError: String? = null
        private set

    @Volatile private var listening: Boolean = false
    @Volatile private var clientConnected: Boolean = false
    @Volatile private var lastPacketAtMs: Long = 0L
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var acceptThread: Thread? = null

    /** ¿Está el servidor SPP escuchando ahora mismo? */
    fun isListening(): Boolean = listening

    /**
     * Arranca el servidor SPP y el hilo de accept. Si ya está
     * escuchando, no hace nada. Si no se puede (sin permiso, BT
     * apagado, listen fallido), deja [status] en UNAVAILABLE o
     * FAILED con el motivo en [statusDetail] y no lanza.
     *
     * [onPacket] se llama con cada estado decodificado DESPUÉS de
     * registrarlo y entregarlo (recordPacket + deliver): es solo
     * para observar (p. ej. refrescar la UI); la inyección ya está
     * hecha cuando se llama.
     */
    override fun start(onPacket: (GamepadState) -> Unit) {
        if (listening) return
        lastError = null
        // 1. Permisos primero (A1): en API 31+ isEnabled y listen
        // lanzan SecurityException sin BLUETOOTH_CONNECT /
        // BLUETOOTH_ADVERTISE. En API < 31 son de instalación.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .firstOrNull { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing != null) {
                lastError = "Falta el permiso de Bluetooth ($missing). Actívalo en Ajustes > Apps > Ultimate Gamepad TV > Permisos."
                return
            }
        }
        // 2. Adapter + radio, sin poder tumbar el arranque.
        val adapter: BluetoothAdapter? =
            runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }.getOrNull()
        val enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        if (adapter == null) {
            lastError = "Sin Bluetooth en esta TV."
            return
        }
        if (!enabled) {
            lastError = "Bluetooth apagado en la TV. Enciéndelo en Ajustes > Bluetooth."
            return
        }
        // 3. Escuchar: publica el servicio SDP "Ultimate Gamepad"
        // con el UUID SPP. Si la pila BT de la TV lo rechaza, el
        // motivo queda en lastError (FAILED) y la UI lo dice.
        val server: BluetoothServerSocket = runCatching {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
        }.onFailure {
            lastError = if (it is SecurityException) {
                "Falta el permiso de Bluetooth para escuchar (BLUETOOTH_ADVERTISE)."
            } else {
                "No se pudo escuchar por Bluetooth SPP (${it.message}). Esta TV puede no admitir RFCOMM; el respaldo es Wi-Fi."
            }
        }.getOrNull() ?: return
        serverSocket = server
        listening = true
        val thread = Thread({
            while (listening) {
                val client: BluetoothSocket = try {
                    server.accept()
                } catch (_: IOException) {
                    // stop() cerró el servidor: el accept se
                    // despierta con IOException y el hilo termina.
                    break
                }
                if (!listening) {
                    runCatching { client.close() }
                    break
                }
                // Un teléfono a la vez: se sirve en este hilo hasta
                // que suelte; al soltar, el accept vuelve a esperar.
                serveClient(client, onPacket)
            }
        }, "bt-spp-accept").apply { isDaemon = true }
        acceptThread = thread
        thread.start()
    }

    /**
     * Sirve a un teléfono conectado: lee paquetes de 32 bytes hasta
     * que suelte o se pare el receptor. Los pings se devuelven con
     * el contador estampado (eco real); cada paquete de mando válido
     * pasa por recordPacket + deliver, exactamente igual que por
     * UDP/TCP. Al salir, suelta el socket y deja el servidor
     * esperando al siguiente teléfono.
     */
    private fun serveClient(client: BluetoothSocket, onPacket: (GamepadState) -> Unit) {
        clientSocket = client
        clientConnected = true
        connectedDeviceName = runCatching { client.remoteDevice.name }.getOrNull()
        connectedDeviceAddress = runCatching { client.remoteDevice.address }.getOrNull()
        try {
            val input = DataInputStream(client.inputStream)
            val output = DataOutputStream(client.outputStream)
            val buf = ByteArray(GamepadProtocol.PACKET_SIZE)
            while (listening && runCatching { client.isConnected }.getOrDefault(false)) {
                try {
                    input.readFully(buf)
                } catch (_: IOException) {
                    // EOF o cierre (teléfono desconectado o stop()):
                    // se suelta el cliente y se vuelve a aceptar.
                    break
                }
                val data = buf.copyOf()
                if (GamepadProtocol.isPing(data)) {
                    // Eco con el contador TOTAL estampado, igual que
                    // UDP/TCP: el teléfono distingue "la TV no
                    // recibe nada" de "recibe pero no inyecta".
                    val stamped = GamepadProtocol.stampTvPacketsReceived(data, UdpReceiverService.packetsReceived)
                    runCatching { output.write(stamped); output.flush() }
                    continue
                }
                // Paquete corrupto o con magic/versión/CRC mal: se
                // descarta en silencio, igual que UDP/TCP.
                val state = GamepadProtocol.decode(data) ?: continue
                // Punto único UDP/TCP: mismo registro, misma
                // entrega, mismo watchdog, mismo perfil (byte 28).
                UdpReceiverService.recordPacket(state, TransportMethod.BLUETOOTH_DATA)
                UdpReceiverService.deliver(context, state, data)
                packetsReceived++
                lastPacketAtMs = System.currentTimeMillis()
                runCatching { onPacket(state) }
            }
        } catch (_: IOException) {
            // El socket murió al abrir los flujos: se suelta igual.
        } finally {
            clientConnected = false
            connectedDeviceName = null
            connectedDeviceAddress = null
            runCatching { client.close() }
            if (clientSocket === client) clientSocket = null
        }
    }

    /**
     * Para el receptor: cierra el socket del cliente (despierta el
     * readFully) y el servidor (despierta el accept), espera como
     * mucho 500 ms al hilo y deja el estado en UNAVAILABLE sin
     * error pendiente (una parada limpia no es un fallo). No
     * suelta los controles: eso lo hace el watchdog de
     * UdpReceiverService con un estado cero a los 2 s, igual que
     * por Wi-Fi (ver cableado en el manifiesto w5.md: si el
     * servicio UDP también se para, el cableado debe enviar el
     * cero explícito).
     */
    override fun stop() {
        listening = false
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null
        serverSocket = null
        lastError = null
        runCatching { acceptThread?.join(500) }
        acceptThread = null
    }
}
