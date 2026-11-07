package com.ultimate.gamepad.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo UDP binario Ultimate Gamepad, versión 1.
 *
 * Paquete fijo de 32 bytes, little-endian, sin servidores externos.
 * Diseñado para enviarse a 120 Hz por Wi-Fi local; si se pierde un
 * paquete, el siguiente lo reemplaza (no hay retransmisión: un botón
 * tarde vale menos que un botón perdido y reenviado tarde).
 *
 * Layout (offset : tamaño : campo):
 *   0  : 2 : magic 0x55 0x47 ("UG")
 *   2  : 1 : version = 1
 *   3  : 1 : flags (bit 0 = 1 si es paquete de ping/eco, ver UdpTransport)
 *   4  : 2 : sequence (uint16, envuelve en 65535)
 *   6  : 8 : timestampMs del remitente (epoch ms, para medir ping/RTT)
 *  14  : 2 : buttons bitmask (GamepadButtons, uint16)
 *  16  : 2 : leftX  int16, -32767..32767  -> float -1..1
 *  18  : 2 : leftY  int16
 *  20  : 2 : rightX int16
 *  22  : 2 : rightY int16
 *  24  : 1 : leftTrigger  uint8, 0..255 -> float 0..1
 *  25  : 1 : rightTrigger uint8
 *  26  : 2 : checksum CRC-16/CCITT-FALSE sobre los bytes 0..25
 *  28  : 1 : profileId (Fase 2): 0 = Xbox 360, 1 = Xbox One,
 *             2 = DS4, 3 = DualSense, 4 = genérico (GamepadProfiles).
 *             Los paquetes viejos llevan 0 y siguen siendo Xbox 360.
 *             OJO: este byte NO va cubierto por el CRC (el CRC solo
 *             cubre 0..25, como en v1, para no romper la
 *             compatibilidad con los APK ya instalados); un id
 *             desconocido o corrupto se sanea a Xbox 360 en destino.
 *  29  : 1 : extraButtons (Fase 4, GamepadExtraButtons): bit 0 =
 *             L4, bit 1 = R4. El bitmask de 16 bits está lleno
 *             (16/16) y no se puede ampliar sin romper el
 *             protocolo, así que L4/R4 viajan aquí. Tampoco va
 *             cubierto por el CRC (igual que el profileId): un bit
 *             corrupto solo pulsa L4/R4 un instante y el siguiente
 *             paquete lo corrige. Los paquetes viejos llevan 0.
 *  30  : 2 : reservado, debe ser 0
 *
 * La TV descarta cualquier paquete con magic, versión o checksum
 * incorrectos, y nunca aplica un paquete con sequence más viejo que
 * el último aplicado (protección contra paquetes reordenados).
 */
object GamepadProtocol {
    const val PACKET_SIZE: Int = 32
    const val MAGIC_0: Byte = 0x55
    const val MAGIC_1: Byte = 0x47
    const val VERSION: Byte = 1
    const val FLAG_PING: Byte = 0x01
    const val DEFAULT_PORT: Int = 51999
    const val DISCOVERY_PORT: Int = 51998
    /** "UGP1|<ip>|<puerto>|<pin4>" — lo que contiene el QR de emparejamiento. */
    const val PAIRING_PREFIX: String = "UGP1"

    const val PROFILE_OFFSET: Int = 28

    /**
     * Codifica un estado. [profileId] es el perfil de emulación
     * activo (GamepadProfiles): viaja en el byte reservado del
     * offset 28; por defecto 0 (Xbox 360, paquetes viejos).
     * GamepadState NO cambia: el perfil no es parte del estado.
     */
    fun encode(state: GamepadState, profileId: Int = GamepadProfiles.XBOX_360_ID): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.put(VERSION)
        buf.put(0) // flags: 0 = datos de mando
        buf.putShort((state.sequence and 0xFFFF).toShort())
        buf.putLong(state.timestampMs)
        buf.putShort((state.buttons and 0xFFFF).toShort())
        buf.putShort(floatToI16(state.leftX))
        buf.putShort(floatToI16(state.leftY))
        buf.putShort(floatToI16(state.rightX))
        buf.putShort(floatToI16(state.rightY))
        buf.put((state.leftTrigger.coerceIn(0f, 1f) * 255f).toInt().toByte())
        buf.put((state.rightTrigger.coerceIn(0f, 1f) * 255f).toInt().toByte())
        val crc = crc16(buf.array(), 0, 26)
        buf.putShort(crc.toShort())
        buf.put((profileId and 0xFF).toByte()) // offset 28: profileId
        buf.put((state.extraButtons and 0xFF).toByte()) // offset 29: L4/R4 (Fase 4)
        buf.put(0) // offset 30: reservado
        buf.put(0) // offset 31: reservado
        return buf.array()
    }

    /** Lee el profileId del byte 28 de un paquete en bruto (0 si no hay). Sin validar: el llamador debe haber decodificado el paquete antes; el id se sanea con GamepadProfiles.fromId(). */
    fun profileIdOf(bytes: ByteArray, length: Int = bytes.size): Int =
        if (length > PROFILE_OFFSET) bytes[PROFILE_OFFSET].toInt() and 0xFF else GamepadProfiles.XBOX_360_ID

    const val EXTRA_BUTTONS_OFFSET: Int = 29

    /** Lee los botones extra (L4/R4, GamepadExtraButtons) del byte 29 de un paquete en bruto (0 si no hay). */
    fun extraButtonsOf(bytes: ByteArray, length: Int = bytes.size): Int =
        if (length > EXTRA_BUTTONS_OFFSET) bytes[EXTRA_BUTTONS_OFFSET].toInt() and 0xFF else 0

    /** Devuelve null si el paquete no es válido. Nunca lanza por datos corruptos. */
    fun decode(bytes: ByteArray, length: Int = bytes.size): GamepadState? {
        if (length < PACKET_SIZE) return null
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) return null
        if (bytes[2] != VERSION) return null
        val expectedCrc = crc16(bytes, 0, 26)
        val actualCrc = ByteBuffer.wrap(bytes, 26, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        if (expectedCrc != actualCrc) return null
        val buf = ByteBuffer.wrap(bytes, 0, PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        val sequence = buf.short.toInt() and 0xFFFF
        val timestampMs = buf.long
        val buttons = buf.short.toInt() and 0xFFFF
        val leftX = i16ToFloat(buf.short)
        val leftY = i16ToFloat(buf.short)
        val rightX = i16ToFloat(buf.short)
        val rightY = i16ToFloat(buf.short)
        val leftTrigger = (buf.get().toInt() and 0xFF) / 255f
        val rightTrigger = (buf.get().toInt() and 0xFF) / 255f
        // Bytes 26-27 = CRC (ya validado), 28 = profileId (se lee
        // aparte con profileIdOf, no es parte del estado), 29 =
        // extraButtons (L4/R4, Fase 4).
        buf.position(EXTRA_BUTTONS_OFFSET)
        val extraButtons = buf.get().toInt() and 0xFF
        return GamepadState(
            buttons = buttons,
            leftX = leftX, leftY = leftY,
            rightX = rightX, rightY = rightY,
            leftTrigger = leftTrigger, rightTrigger = rightTrigger,
            sequence = sequence, timestampMs = timestampMs,
            extraButtons = extraButtons,
        )
    }

    fun floatToI16(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    fun i16ToFloat(v: Short): Float = (v.toInt() / 32767f).coerceIn(-1f, 1f)

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF. */
    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    /** Paquete de ping: mismo header, flags = FLAG_PING, resto a 0 salvo checksum. */
    fun encodePing(sequence: Int, timestampMs: Long): ByteArray {
        val state = GamepadState(sequence = sequence, timestampMs = timestampMs)
        val bytes = encode(state)
        bytes[3] = FLAG_PING
        val crc = crc16(bytes, 0, 26)
        bytes[26] = (crc and 0xFF).toByte()
        bytes[27] = ((crc shr 8) and 0xFF).toByte()
        return bytes
    }

    fun isPing(bytes: ByteArray): Boolean = bytes.size >= 4 && (bytes[3].toInt() and FLAG_PING.toInt()) != 0
}
