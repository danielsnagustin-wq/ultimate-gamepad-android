package com.ultimate.gamepad.core

import java.net.InetAddress
import java.net.NetworkInterface

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
 *
 * Broadcast dirigido (2026-11-07): además del 255.255.255.255, la
 * petición se envía al broadcast dirigido de cada subred local
 * (p. ej. 192.168.1.255). Algunos routers filtran el broadcast
 * limitado pero dejan pasar el dirigido a la subred, y con el
 * hotspot del teléfono como punto de acceso la TV está en la
 * subred 192.168.43.0/24: sin el dirigido a esa subred, el
 * teléfono como AP no la encontraba. Ojo, honestidad: si el
 * router tiene aislamiento AP (los clientes no se ven entre sí),
 * NINGÚN broadcast llega y solo queda el plan B (QR o IP+PIN
 * manual); el dirigido no es magia contra el aislamiento.
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
        val name = parts[1].trim()
        val ip = parts[2].trim()
        val port = parts[3].trim().toIntOrNull() ?: return null
        val pin = parts[4].trim()
        if (name.isBlank() || pin.isBlank()) return null
        if (port !in 1..65535) return null
        // Ronda 2 P2-9: sin validar la IP, "UGRSP1|TV|no-es-ip|99999|1234"
        // parseaba como TV válida y el intento de conexión fallaba tarde.
        if (!isValidIpv4(ip)) return null
        return DiscoveredTv(name = name, ip = ip, port = port, pin = pin)
    }

    /** IPv4 con 4 octetos decimales 0..255 (sin resolver DNS). */
    fun isValidIpv4(ip: String): Boolean {
        val octets = ip.split(".")
        if (octets.size != 4) return false
        for (o in octets) {
            if (o.isEmpty() || o.length > 3 || !o.all { it.isDigit() }) return false
            val n = o.toIntOrNull() ?: return false
            if (n !in 0..255) return false
        }
        return true
    }

    /**
     * Broadcast dirigido de una subred: pone a 1 los bits de host
     * según el prefijo (p. ej. 192.168.1.20/24 -> 192.168.1.255).
     * null si la dirección no es IPv4 o el prefijo es inválido.
     * Lógica pura, comprobada con tests en CI.
     */
    fun broadcastFor(address: InetAddress, prefixLength: Int): InetAddress? {
        val raw = address.address
        if (raw.size != 4 || prefixLength < 0 || prefixLength > 32) return null
        var ip = 0
        for (b in raw) ip = (ip shl 8) or (b.toInt() and 0xFF)
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        val bcast = ip or mask.inv()
        return InetAddress.getByAddress(
            byteArrayOf(
                ((bcast ushr 24) and 0xFF).toByte(),
                ((bcast ushr 16) and 0xFF).toByte(),
                ((bcast ushr 8) and 0xFF).toByte(),
                (bcast and 0xFF).toByte(),
            ),
        )
    }

    /** ¿Es una IPv4 privada (RFC 1918)? Solo esas subredes se usan para el broadcast dirigido. */
    fun isPrivateIpv4(address: InetAddress): Boolean {
        val b = address.address
        if (b.size != 4) return false
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        return o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168)
    }

    /**
     * ¿Es una dirección de hotspot típica de Android (el teléfono
     * como punto de acceso)? Android usa 192.168.43.0/24 por
     * defecto para el hotspot. Cuando el teléfono es el AP, NO
     * está en Wi-Fi (no hay TRANSPORT_WIFI activo) y la búsqueda
     * se bloqueaba con "No estás en Wi-Fi" aunque la TV estuviera
     * conectada a su hotspot: este chequeo es lo que la desbloquea.
     */
    fun isHotspotAddress(address: InetAddress): Boolean {
        val b = address.address
        return b.size == 4 && (b[0].toInt() and 0xFF) == 192 &&
            (b[1].toInt() and 0xFF) == 168 && (b[2].toInt() and 0xFF) == 43
    }

    /**
     * Broadcasts dirigidos de todas las interfaces locales útiles
     * (Wi-Fi normal y hotspot del teléfono). Solo subredes privadas
     * (RFC 1918): la interfaz de datos móviles (IP pública o CGNAT
     * 100.64/10) nunca es una red local con una TV. Sin interfaces
     * (o sin permiso), lista vacía: el llamador sigue teniendo el
     * 255.255.255.255.
     */
    fun localBroadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr.isLoopbackAddress || !isPrivateIpv4(addr)) continue
                    broadcastFor(addr, ia.networkPrefixLength.toInt())?.let { out += it }
                }
            }
        } catch (_: Exception) {
            // Sin acceso a las interfaces: lista vacía, sin crashear.
        }
        return out.toList()
    }

    /** ¿Hay una interfaz de hotspot activa (el teléfono como punto de acceso)? */
    fun hasHotspotInterface(): Boolean {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    if (isHotspotAddress(ia.address)) return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Todas las IPv4 locales útiles de este dispositivo (Wi-Fi,
     * Ethernet, hotspot…), no solo la de Wi-Fi. A8: la TV no
     * respondía al descubrimiento si `localIpFromWifi` era null
     * (p. ej. TV por Ethernet, sin Wi-Fi): el receptor de la TV
     * debe usar la primera de esta lista como IP de respuesta,
     * con la de Wi-Fi solo como respaldo. Se recorren TODAS las
     * interfaces activas, no solo wlan0: una TV por cable (eth0)
     * tiene aquí su IP aunque no tenga Wi-Fi. Solo privadas
     * (RFC 1918) y sin loopback, como localBroadcastAddresses().
     */
    fun localIpv4Addresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr.isLoopbackAddress || !isPrivateIpv4(addr)) continue
                    if (addr !in out) out += addr
                }
            }
        } catch (_: Exception) {
            // Sin acceso a las interfaces: lista vacía, sin crashear.
        }
        return out
    }
}

/** Una TV encontrada en la red local, lista para conectar con un toque. */
data class DiscoveredTv(
    val name: String,
    val ip: String,
    val port: Int,
    val pin: String,
)
