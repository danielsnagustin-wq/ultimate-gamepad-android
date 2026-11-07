package com.ultimate.gamepad.core

/**
 * Descubrimiento automático de la TV en la red local.
 *
 * El problema que resuelve: conectar pedía escribir la IP y el
 * PIN a mano, y si la TV no estaba en la misma Wi-Fi o el receptor
 * no estaba abierto, el teléfono se quedaba "Conectando" sin
 * llegar a nada. Con discovery, el teléfono emite un broadcast
 * UDP y la TV responde con su nombre, IP, puerto y PIN: tocar la
 * TV en la lista conecta directo, sin escribir nada.
 *
 * Formato (texto UTF-8, sin CRC: no es un paquete de juego):
 * - Petición (teléfono -> broadcast 255.255.255.255:51999): "UGDISC1"
 * - Respuesta (TV -> teléfono): "UGRSP1|<nombre>|<ip>|<puerto>|<pin>"
 *   Ej: "UGRSP1|TV Ultimate Gamepad|192.168.1.20|51999|4821"
 *
 * No choca con los paquetes de juego de 32 bytes: esos empiezan
 * por el magic "UG" pero miden 32 bytes y nunca son exactamente
 * la cadena "UGDISC1".
 */
object Discovery {
    const val MAGIC_REQUEST: String = "UGDISC1"
    const val MAGIC_RESPONSE: String = "UGRSP1"

    fun buildResponse(name: String, ip: String, port: Int, pin: String): ByteArray =
        "$MAGIC_RESPONSE|$name|$ip|$port|$pin".toByteArray(Charsets.UTF_8)

    fun parseResponse(data: ByteArray): DiscoveredTv? {
        val text = String(data, Charsets.UTF_8)
        val parts = text.split("|")
        if (parts.size != 5 || parts[0] != MAGIC_RESPONSE) return null
        val port = parts[3].toIntOrNull() ?: return null
        if (parts[1].isBlank() || parts[2].isBlank() || parts[4].isBlank()) return null
        return DiscoveredTv(name = parts[1], ip = parts[2], port = port, pin = parts[4])
    }
}

/** Una TV encontrada en la red local, lista para conectar con un toque. */
data class DiscoveredTv(
    val name: String,
    val ip: String,
    val port: Int,
    val pin: String,
)
