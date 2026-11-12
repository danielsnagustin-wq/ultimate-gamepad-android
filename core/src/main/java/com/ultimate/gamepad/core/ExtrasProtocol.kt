package com.ultimate.gamepad.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Paquetes de extras (Fase 4): teclado virtual y modo ratón.
 *
 * Son OTRO tipo de paquete, distinto del de mando de 32 bytes
 * (GamepadProtocol): el paquete de mando está lleno y el teclado
 * y el ratón son OTROS dispositivos virtuales, nunca parte del
 * gamepad. Por eso no se meten teclas en el paquete de 32 bytes:
 * cada extra tiene su propio paquete de 8 bytes, con su propio
 * magic, que el receptor de la TV distingue ANTES del ping y del
 * paquete de mando (un paquete de teclado con action=release
 * tiene el byte 3 a 1 y se confundiría con un ping si se
 * comprobara después).
 *
 * Teclado (8 bytes):
 *   0 : 'U' (0x55)    1 : 'K' (0x4B)
 *   2 : keycode evdev (uint8, linux/input-event-codes.h, ver KeyMap)
 *   3 : acción: 0 = pulsar, 1 = soltar, 2 = crear el dispositivo,
 *       3 = destruir el dispositivo
 *   4..7 : 0
 *
 * Ratón (8 bytes):
 *   0 : 'U' (0x55)    1 : 'M' (0x4D)
 *   2..3 : dx int16 LE (movimiento relativo, píxeles)
 *   4..5 : dy int16 LE
 *   6 : botones bitmask: bit 0 = izquierdo, bit 1 = derecho,
 *       bit 2 = central
 *   7 : acción: 0 = mover/clic, 2 = crear el dispositivo,
 *       3 = destruir el dispositivo
 *
 * Transporte: solo UDP al mismo puerto del receptor (51999). El
 * receptor crea los dispositivos uinput la primera vez que llega
 * un evento (o con action=create) y los destruye con
 * action=destroy o al parar el receptor. Sin CRC: son 8 bytes en
 * red local y un paquete corrupto solo mueve el cursor o pulsa
 * una tecla un instante; el magic de 2 bytes ya descarta la
 * basura que no es un paquete de extras.
 */
object ExtrasProtocol {
    const val PACKET_SIZE: Int = 8
    const val MAGIC_0: Byte = 0x55 // 'U'
    const val KEYBOARD_MAGIC_1: Byte = 0x4B // 'K'
    const val MOUSE_MAGIC_1: Byte = 0x4D // 'M'

    /** Teclado: pulsar la tecla. */
    const val ACTION_PRESS: Int = 0
    /** Teclado: soltar la tecla. */
    const val ACTION_RELEASE: Int = 1
    /** Crear el dispositivo virtual en la TV (teclado o ratón). */
    const val ACTION_CREATE: Int = 2
    /** Destruir el dispositivo virtual en la TV. */
    const val ACTION_DESTROY: Int = 3

    /** Ratón: botón izquierdo. */
    const val MOUSE_BUTTON_LEFT: Int = 1 shl 0
    /** Ratón: botón derecho. */
    const val MOUSE_BUTTON_RIGHT: Int = 1 shl 1
    /** Ratón: botón central. */
    const val MOUSE_BUTTON_MIDDLE: Int = 1 shl 2

    data class KeyboardEvent(val keyCode: Int, val action: Int)
    data class MouseEvent(val dx: Int, val dy: Int, val buttons: Int, val action: Int)

    fun encodeKeyboard(keyCode: Int, action: Int): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_0)
        buf.put(KEYBOARD_MAGIC_1)
        buf.put((keyCode and 0xFF).toByte())
        buf.put((action and 0xFF).toByte())
        return buf.array()
    }

    fun encodeMouse(dx: Int, dy: Int, buttons: Int, action: Int = ACTION_PRESS): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_0)
        buf.put(MOUSE_MAGIC_1)
        buf.putShort(dx.toShort())
        buf.putShort(dy.toShort())
        buf.put((buttons and 0xFF).toByte())
        buf.put((action and 0xFF).toByte())
        return buf.array()
    }

    fun isKeyboard(bytes: ByteArray, length: Int = bytes.size): Boolean =
        length >= PACKET_SIZE && bytes[0] == MAGIC_0 && bytes[1] == KEYBOARD_MAGIC_1

    fun isMouse(bytes: ByteArray, length: Int = bytes.size): Boolean =
        length >= PACKET_SIZE && bytes[0] == MAGIC_0 && bytes[1] == MOUSE_MAGIC_1

    /** Devuelve null si no es un paquete de teclado válido. Nunca lanza. */
    fun decodeKeyboard(bytes: ByteArray, length: Int = bytes.size): KeyboardEvent? {
        if (!isKeyboard(bytes, length)) return null
        val action = bytes[3].toInt() and 0xFF
        if (action !in ACTION_PRESS..ACTION_DESTROY) return null
        return KeyboardEvent(bytes[2].toInt() and 0xFF, action)
    }

    /** Devuelve null si no es un paquete de ratón válido. Nunca lanza. */
    fun decodeMouse(bytes: ByteArray, length: Int = bytes.size): MouseEvent? {
        if (!isMouse(bytes, length)) return null
        val buf = ByteBuffer.wrap(bytes, 0, PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(2)
        val dx = buf.short.toInt()
        val dy = buf.short.toInt()
        val buttons = buf.get().toInt() and 0xFF
        val action = buf.get().toInt() and 0xFF
        if (action != ACTION_PRESS && action != ACTION_CREATE && action != ACTION_DESTROY) return null
        return MouseEvent(dx, dy, buttons, action)
    }
}
