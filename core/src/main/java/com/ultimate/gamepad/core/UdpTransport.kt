package com.ultimate.gamepad.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Transporte UDP mínimo, compartido por teléfono y TV.
 * No es un socket "mágico": si no hay red local, [send] falla y
 * el llamador debe mostrar "sin conexión", nunca fingir envío.
 */
class UdpTransport(private val socket: DatagramSocket = DatagramSocket()) {

    init {
        // Baja latencia: marca los paquetes como "low delay"
        // (IPTOS_LOWDELAY) para que el router no los ponga en cola
        // detrás de otro tráfico. Si el sistema no lo permite, se
        // ignora sin romper nada.
        runCatching { socket.trafficClass = 0x10 }
    }

    fun send(state: GamepadState, host: InetAddress, port: Int) {
        val bytes = GamepadProtocol.encode(state)
        socket.send(DatagramPacket(bytes, bytes.size, host, port))
    }

    fun sendPing(sequence: Int, timestampMs: Long, host: InetAddress, port: Int) {
        val bytes = GamepadProtocol.encodePing(sequence, timestampMs)
        socket.send(DatagramPacket(bytes, bytes.size, host, port))
    }

    /**
     * Recibe un paquete con timeout. Devuelve null por timeout
     * (no es un error) y descarta paquetes corruptos en silencio.
     * [onPacket] recibe el estado ya decodificado y si era ping.
     */
    fun receive(timeoutMs: Int, onPacket: (state: GamepadState, isPing: Boolean, sender: InetAddress) -> Unit) {
        socket.soTimeout = timeoutMs
        val buf = ByteArray(GamepadProtocol.PACKET_SIZE)
        val packet = DatagramPacket(buf, buf.size)
        try {
            socket.receive(packet)
        } catch (_: SocketTimeoutException) {
            return
        }
        val state = GamepadProtocol.decode(packet.data, packet.length) ?: return
        onPacket(state, GamepadProtocol.isPing(packet.data), packet.address)
    }

    fun close() {
        runCatching { socket.close() }
    }
}
