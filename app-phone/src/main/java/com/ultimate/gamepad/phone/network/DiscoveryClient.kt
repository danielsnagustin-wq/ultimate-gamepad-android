package com.ultimate.gamepad.phone.network

import com.ultimate.gamepad.core.DiscoveredTv
import com.ultimate.gamepad.core.Discovery
import com.ultimate.gamepad.core.GamepadProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Busca TVs con Ultimate Gamepad en la red Wi-Fi local.
 *
 * Emite el broadcast "UGDISC1" al puerto 51999 y recoge las
 * respuestas "UGRSP1|nombre|ip|puerto|pin" durante ~3 s. La
 * petición se reenvía cada segundo porque el broadcast UDP se
 * puede perder. Sin Wi-Fi o sin ninguna TV con el receptor
 * abierto, la lista sale vacía (nunca se inventa una TV).
 *
 * Destinos (2026-11-07): además del broadcast limitado
 * 255.255.255.255, la petición va al broadcast dirigido de cada
 * subred local (Discovery.localBroadcastAddresses): eso cubre la
 * Wi-Fi normal (192.168.1.255) y el hotspot del teléfono
 * (192.168.43.255, donde el teléfono es el punto de acceso y la
 * TV su cliente). Si el router tiene aislamiento AP, ningún
 * broadcast llega: la lista sale vacía y la UI debe mostrar el
 * plan B (QR o IP+PIN manual), no quedarse callada.
 */
object DiscoveryClient {
    suspend fun discover(timeoutMs: Long = 3000): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredTv>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { soTimeout = 250; broadcast = true }
            // Val no nulo para el bucle: `socket` es var (el finally
            // lo cierra) y dentro de las lambdas no hay smart-cast.
            val sock = socket ?: return@withContext emptyList()
            val request = Discovery.MAGIC_REQUEST.toByteArray(Charsets.UTF_8)
            val targets = LinkedHashSet<InetAddress>()
            runCatching { targets += InetAddress.getByName("255.255.255.255") }
            targets += Discovery.localBroadcastAddresses()
            val endAt = System.currentTimeMillis() + timeoutMs
            var nextSendAt = 0L
            val buf = ByteArray(256)
            // Ronda 2 P2-8: comprobar isActive en el bucle (si no,
            // la cancelación se retrasaba hasta 3 s) y no tragar
            // CancellationException en el catch general.
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < endAt) {
                if (System.currentTimeMillis() >= nextSendAt) {
                    for (target in targets) {
                        runCatching {
                            sock.send(DatagramPacket(request, request.size, target, GamepadProtocol.DEFAULT_PORT))
                        }
                    }
                    nextSendAt = System.currentTimeMillis() + 1000
                }
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    Discovery.parseResponse(packet.data.copyOf(packet.length))?.let { found[it.ip] = it }
                } catch (e: SocketTimeoutException) {
                    // Sin respuesta en este intervalo: se sigue esperando.
                }
            }
        } catch (e: CancellationException) {
            // La cancelación de la corrutina no es un error: se
            // re-lanza para que el llamador la vea (ronda 2 P2-8).
            throw e
        } catch (e: Exception) {
            // Sin red / sin permiso de broadcast: lista vacía.
        } finally {
            runCatching { socket?.close() }
        }
        found.values.toList()
    }
}
