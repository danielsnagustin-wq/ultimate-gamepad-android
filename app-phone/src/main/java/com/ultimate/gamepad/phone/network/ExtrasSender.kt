package com.ultimate.gamepad.phone.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Envío UDP de paquetes de extras (Fase 4: teclado virtual y
 * modo ratón, ExtrasProtocol) a la TV.
 *
 * Socket propio, distinto del de GamepadSenderService: el servicio
 * de envío manda el estado del mando a 120 Hz en un bucle, y los
 * extras son eventos esporádicos (una tecla, un movimiento de
 * ratón) que no deben pasar por ese bucle ni esperar a él. El
 * socket se abre en el primer envío y se cierra al cerrar la app
 * (GamepadViewModel.onCleared); si la red falla, send() devuelve
 * false sin lanzar.
 */
class ExtrasSender {
    private var socket: DatagramSocket? = null

    @Synchronized
    fun send(packet: ByteArray, host: String, port: Int): Boolean {
        return runCatching {
            val s = socket ?: DatagramSocket().also { socket = it }
            val addr = InetAddress.getByName(host)
            s.send(DatagramPacket(packet, packet.size, addr, port))
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
