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
            val host = parts[1]
            val port = parts[2].toIntOrNull() ?: return null
            val pin = parts[3]
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
