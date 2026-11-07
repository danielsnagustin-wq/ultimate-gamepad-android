package com.ultimate.gamepad.core

/**
 * Salud de conexión, compartida por teléfono y TV.
 *
 * UDP no tiene "conexión" como TCP: no hay handshake que se caiga.
 * Por eso la pérdida se detecta por silencio: si no llega (TV) o
 * no vuelve (teléfono, eco de ping) ningún paquete en [TIMEOUT_MS],
 * se muestra "sin conexión". Cuando vuelve un paquete, la conexión
 * se recupera sola, sin que el usuario tenga que pulsar nada:
 * eso es la reconexión automática en esta Fase 1.
 */
object ConnectionHealth {
    /** Sin paquete en 2 s = pérdida de conexión (a 60 Hz es ~120 paquetes perdidos). */
    const val TIMEOUT_MS: Long = 2000L
    /** Sin eco de ping en 3 s = la TV no responde (teléfono). */
    const val PING_TIMEOUT_MS: Long = 3000L

    fun isAlive(lastPacketAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastPacketAtMs > 0 && nowMs - lastPacketAtMs <= TIMEOUT_MS

    fun isTvResponding(lastEchoAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastEchoAtMs > 0 && nowMs - lastEchoAtMs <= PING_TIMEOUT_MS

    fun secondsSince(lastMs: Long, nowMs: Long = System.currentTimeMillis()): Long =
        if (lastMs <= 0) -1 else (nowMs - lastMs) / 1000
}
