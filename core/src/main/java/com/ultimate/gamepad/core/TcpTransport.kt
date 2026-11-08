package com.ultimate.gamepad.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Transporte Wi-Fi TCP, misma red — Etapa 1, implementación real básica.
 *
 * Mismo paquete de 32 bytes que UDP, pero TCP es un flujo continuo:
 * no hay límites de datagrama. Por eso cada paquete se envía tal
 * cual (32 bytes exactos) y el receptor lee exactamente 32 bytes
 * con [DataInputStream.readFully]. Si la lectura es parcial o el
 * socket se cierra, el receptor lo detecta y el remitente debe
 * reconectar (no se rellenan bytes inventados).
 *
 * Cuándo usarlo: si UDP se pierde mucho o el router bloquea UDP.
 * Latencia algo mayor que UDP y con retransmisión de TCP (un
 * paquete tarde puede llegar tarde), pero fiable. Por eso es el
 * respaldo nº 2, no el principal.
 */
class TcpGamepadTransport(
    private val host: InetAddress,
    private val port: Int = GamepadProtocol.DEFAULT_PORT + 1, // TCP: 52000, UDP sigue en 51999.
) : GamepadTransport {
    override val method: TransportMethod = TransportMethod.WIFI_TCP
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var input: DataInputStream? = null

    fun connect(timeoutMs: Int = 3000) {
        close()
        val s = Socket()
        s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true
        socket = s
        out = DataOutputStream(s.getOutputStream())
        input = DataInputStream(s.getInputStream())
    }

    override fun send(state: GamepadState) = send(state, GamepadProfiles.XBOX_360_ID)

    /** [profileId] viaja en el byte 28 del paquete (Fase 2, GamepadProfiles). */
    fun send(state: GamepadState, profileId: Int) {
        val s = socket ?: throw IllegalStateException("TCP no conectado; llama a connect() primero")
        val o = out ?: throw IllegalStateException("TCP no conectado")
        o.write(GamepadProtocol.encode(state, profileId))
        o.flush()
    }

    /** Ping TCP (Fase 1): mismo paquete FLAG_PING que por UDP; la TV lo devuelve tal cual. */
    fun sendPing(sequence: Int, timestampMs: Long) {
        val o = out ?: throw IllegalStateException("TCP no conectado")
        o.write(GamepadProtocol.encodePing(sequence, timestampMs))
        o.flush()
    }

    /**
     * Lee exactamente 32 bytes con timeout; null solo por timeout
     * (no es un error). Lanza IOException si el socket murió: el
     * llamador debe cerrar y reconectar, no seguir leyendo de un
     * socket muerto.
     */
    fun readRaw(timeoutMs: Int): ByteArray? {
        val stream = input ?: throw java.io.IOException("TCP no conectado")
        socket?.soTimeout = timeoutMs
        val buf = ByteArray(GamepadProtocol.PACKET_SIZE)
        return try {
            stream.readFully(buf)
            buf
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    /** Reconexión: cierra y vuelve a conectar. Devuelve false si sigue sin haber TV. */
    fun reconnect(): Boolean = runCatching { connect(); true }.getOrDefault(false)

    override fun close() {
        runCatching { out?.close() }
        runCatching { input?.close() }
        runCatching { socket?.close() }
        out = null; input = null; socket = null
    }
}

/** Lado TV: acepta una conexión TCP y lee paquetes de 32 bytes. */
class TcpGamepadReceiver(
    private val port: Int = GamepadProtocol.DEFAULT_PORT + 1,
    /**
     * Contador de paquetes recibidos por la TV (para estamparlo
     * en el eco de ping, igual que por UDP). El receptor es de
     * :core y no puede leer UdpReceiverService: el servicio TCP
     * lo pasa como lambda.
     */
    private val tvPacketsReceived: () -> Long = { 0L },
) : GamepadReceiver {
    override val method: TransportMethod = TransportMethod.WIFI_TCP
    private val server = ServerSocket(port)
    private var client: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    override fun receive(timeoutMs: Int): GamepadState? = receivePacket(timeoutMs)?.first

    /**
     * Como [receive], pero devuelve también el profileId del byte 28
     * (Fase 2): el servicio TCP lo necesita para re-codificar el
     * paquete sin perder el perfil. Sin esto, por TCP todo paquete
     * se re-codificaba como Xbox 360 y la TV recreaba el mando
     * equivocado al cambiar de perfil.
     */
    fun receivePacket(timeoutMs: Int): Pair<GamepadState, Int>? {
        if (client == null || client?.isClosed == true) {
            server.soTimeout = timeoutMs
            try {
                client = server.accept()
                client?.tcpNoDelay = true
                input = DataInputStream(client?.getInputStream())
                output = DataOutputStream(client?.getOutputStream())
            } catch (_: SocketTimeoutException) {
                return null
            } catch (_: java.io.IOException) {
                // El ServerSocket murió (p. ej. close() en onDestroy):
                // no hay nada que aceptar; el servicio lo recrea.
                return null
            }
        }
        val stream = input ?: return null
        client?.soTimeout = timeoutMs
        val buf = ByteArray(GamepadProtocol.PACKET_SIZE)
        return try {
            stream.readFully(buf)
            if (GamepadProtocol.isPing(buf)) {
                // Eco TCP (Fase 1): el ping se devuelve tal cual por
                // el mismo socket y NO se trata como entrada. Sin
                // este cheque, un ping se decodificaría como un
                // estado todo-cero y borraría el mando 2 veces por
                // segundo. Si el eco falla, el cliente está muerto.
                // El eco lleva estampado el contador de paquetes
                // recibidos (igual que por UDP), para que el
                // teléfono sepa si la TV recibe o solo responde.
                try {
                    val o = output ?: throw java.io.IOException("Sin salida TCP")
                    o.write(GamepadProtocol.stampTvPacketsReceived(buf, tvPacketsReceived()))
                    o.flush()
                } catch (_: java.io.IOException) {
                    closeClient()
                }
                return null
            }
            val state = GamepadProtocol.decode(buf) ?: return null
            state to GamepadProtocol.profileIdOf(buf)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: java.io.EOFException) {
            // La TV / el teléfono cerró: la próxima receive() acepta de nuevo.
            closeClient()
            null
        } catch (_: java.io.IOException) {
            // Reset de conexión, red rota, etc.: el cliente murió.
            // Sin este catch, la excepción subía al servicio, que la
            // tragaba y volvía a llamar a receive() en bucle cerrado
            // sobre el mismo socket muerto (CPU al 100% y sin volver
            // a aceptar nunca).
            closeClient()
            null
        }
    }

    private fun closeClient() {
        runCatching { output?.close() }
        runCatching { client?.close() }
        client = null; input = null; output = null
    }

    override fun close() {
        runCatching { output?.close() }
        runCatching { client?.close() }
        runCatching { server.close() }
        client = null; input = null; output = null
    }
}
