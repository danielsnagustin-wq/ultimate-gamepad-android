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

    fun connect(timeoutMs: Int = 3000) {
        close()
        val s = Socket()
        s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true
        socket = s
        out = DataOutputStream(s.getOutputStream())
    }

    override fun send(state: GamepadState) {
        val s = socket ?: throw IllegalStateException("TCP no conectado; llama a connect() primero")
        val o = out ?: throw IllegalStateException("TCP no conectado")
        o.write(GamepadProtocol.encode(state))
        o.flush()
    }

    /** Reconexión: cierra y vuelve a conectar. Devuelve false si sigue sin haber TV. */
    fun reconnect(): Boolean = runCatching { connect(); true }.getOrDefault(false)

    override fun close() {
        runCatching { out?.close() }
        runCatching { socket?.close() }
        out = null; socket = null
    }
}

/** Lado TV: acepta una conexión TCP y lee paquetes de 32 bytes. */
class TcpGamepadReceiver(
    private val port: Int = GamepadProtocol.DEFAULT_PORT + 1,
) : GamepadReceiver {
    override val method: TransportMethod = TransportMethod.WIFI_TCP
    private val server = ServerSocket(port)
    private var client: Socket? = null
    private var input: DataInputStream? = null

    override fun receive(timeoutMs: Int): GamepadState? {
        if (client == null || client?.isClosed == true) {
            server.soTimeout = timeoutMs
            try {
                client = server.accept()
                client?.tcpNoDelay = true
                input = DataInputStream(client?.getInputStream())
            } catch (_: SocketTimeoutException) {
                return null
            }
        }
        val stream = input ?: return null
        client?.soTimeout = timeoutMs
        val buf = ByteArray(GamepadProtocol.PACKET_SIZE)
        return try {
            stream.readFully(buf)
            GamepadProtocol.decode(buf)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: java.io.EOFException) {
            // La TV / el teléfono cerró: la próxima receive() acepta de nuevo.
            runCatching { client?.close() }
            client = null; input = null
            null
        }
    }

    override fun close() {
        runCatching { client?.close() }
        runCatching { server.close() }
    }
}
