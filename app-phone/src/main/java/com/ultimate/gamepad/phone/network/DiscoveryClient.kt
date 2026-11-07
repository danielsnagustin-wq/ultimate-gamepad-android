package com.ultimate.gamepad.phone.network

import com.ultimate.gamepad.core.DiscoveredTv
import com.ultimate.gamepad.core.Discovery
import com.ultimate.gamepad.core.GamepadProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Busca TVs con Ultimate Gamepad en la red Wi-Fi local.
 *
 * Emite el broadcast "UGDISC1" al puerto 51999 y recoge las
 * respuestas "UGRSP1|nombre|ip|puerto|pin" durante ~3 s. La
 * petición se reenvía cada segundo porque el broadcast UDP se
 * puede perder. Sin Wi-Fi o sin ninguna TV con el receptor
 * abierto, la lista sale vacía (nunca se inventa una TV).
 */
object DiscoveryClient {
    suspend fun discover(timeoutMs: Long = 3000): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredTv>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { soTimeout = 250; broadcast = true }
            val request = Discovery.MAGIC_REQUEST.toByteArray(Charsets.UTF_8)
            val target = InetAddress.getByName("255.255.255.255")
            val endAt = System.currentTimeMillis() + timeoutMs
            var nextSendAt = 0L
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < endAt) {
                if (System.currentTimeMillis() >= nextSendAt) {
                    socket.send(DatagramPacket(request, request.size, target, GamepadProtocol.DEFAULT_PORT))
                    nextSendAt = System.currentTimeMillis() + 1000
                }
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    Discovery.parseResponse(packet.data.copyOf(packet.length))?.let { found[it.ip] = it }
                } catch (e: SocketTimeoutException) {
                    // Sin respuesta en este intervalo: se sigue esperando.
                }
            }
        } catch (e: Exception) {
            // Sin red / sin permiso de broadcast: lista vacía.
        } finally {
            runCatching { socket?.close() }
        }
        found.values.toList()
    }
}
