package com.ultimate.gamepad.core

/**
 * Emparejamiento por QR / PIN, sin servidores externos.
 *
 * Formato del QR que muestra la TV:
 *   UGP1|192.168.1.20|51999|4821
 *   ^     ^            ^     ^
 *   prefijo  IP de la TV  puerto UDP  PIN de 4 dígitos
 *
 * El PIN no cifra nada: solo evita conectarse por error a otra TV
 * de la misma red. La seguridad real es que todo ocurre solo en la
 * red Wi-Fi local del usuario. No se inventa ningún cifrado aquí;
 * si en una etapa posterior se quiere cifrado, se documentará
 * aparte y no se fingirá que ya existe.
 */
data class PairingInfo(val host: String, val port: Int, val pin: String) {
    fun toQrPayload(): String = "${GamepadProtocol.PAIRING_PREFIX}|$host|$port|$pin"

    companion object {
        fun parseQrPayload(payload: String): PairingInfo? {
            val parts = payload.trim().split("|")
            if (parts.size != 4) return null
            if (parts[0] != GamepadProtocol.PAIRING_PREFIX) return null
            // Ronda 2 P2-9: un espacio en el QR dejaba el host con
            // espacios (" 192.168.1.20 ") y la conexión fallaba.
            val host = parts[1].trim()
            val port = parts[2].trim().toIntOrNull() ?: return null
            val pin = parts[3].trim()
            if (host.isBlank() || port !in 1..65535) return null
            if (!isValidPin(pin)) return null
            // Validación mínima de IPv4/hostname, sin resolver DNS aquí.
            if (host.length > 253) return null
            return PairingInfo(host, port, pin)
        }

        fun isValidPin(pin: String): Boolean = pin.length == 4 && pin.all { it.isDigit() }

        fun generatePin(): String = (1000..9999).random().toString()
    }
}
