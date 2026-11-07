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
        // Re-ligar rápido tras reiniciar: sin SO_REUSEADDR, un
        // reinicio del receptor puede fallar con "Address already
        // in use" si el socket viejo aún no se liberó.
        runCatching { socket.reuseAddress = true }
        // Búferes de 64 KiB: a 120 Hz los paquetes son de 32 bytes,
        // pero una ráfaga (reconexión, eco de ping) no debe tirar
        // paquetes por búfer lleno. El sistema puede recortarlos;
        // si no deja, se ignora.
        runCatching { socket.sendBufferSize = 64 * 1024 }
        runCatching { socket.receiveBufferSize = 64 * 1024 }
    }

    companion object {
        /** Socket ligado a un puerto (receptor TV) con SO_REUSEADDR. */
        fun bound(port: Int): UdpTransport {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(java.net.InetSocketAddress(port))
            return UdpTransport(s)
        }
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
