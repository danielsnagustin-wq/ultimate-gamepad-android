package com.ultimate.gamepad.core

/**
 * Capa de transporte multi-método — Etapa 1.
 *
 * TODOS los transportes llevan el MISMO paquete de 32 bytes de
 * [GamepadProtocol]. Cambiar de transporte no cambia el modelo de
 * entrada ([GamepadState]) ni el receptor: solo cambia cómo viajan
 * los bytes. Esa es la regla que permite el fallback automático.
 *
 * Orden de prioridad (decisión del usuario, 2026-11-07):
 *   1. WIFI_UDP   — Wi-Fi UDP, misma red. Principal, baja latencia.
 *   2. WIFI_TCP   — Wi-Fi TCP, misma red. Respaldo si UDP se pierde
 *                   o lo bloquea el router.
 *   3. HOTSPOT    — No es un transporte distinto: es WIFI_UDP/TCP
 *                   con la TV conectada al hotspot del teléfono,
 *                   sin router. Modo de conexión, no código nuevo.
 *   4. BLUETOOTH_DATA — BLE/SPP a la app receptora. Estructura en
 *                   Etapa 1, implementación completa en Etapa 2.
 *   5. BLUETOOTH_HID  — Teléfono como mando real directo a la TV,
 *                   sin app receptora y sin Shizuku.
 * El modo Automático prueba en orden 1 -> 2 -> 5 -> 4 en la
 * numeración de la tarea (UDP, TCP, BLE/SPP, HID). Ver
 * [TransportSelector.AUTO_ORDER].
 */
enum class TransportMethod(val label: String, val priority: Int) {
    WIFI_UDP("Wi-Fi UDP", 1),
    WIFI_TCP("Wi-Fi TCP", 2),
    HOTSPOT("Hotspot del teléfono", 3),
    BLUETOOTH_DATA("Bluetooth datos (BLE/SPP)", 5),
    BLUETOOTH_HID("Bluetooth HID", 4),
}

/** Estado REAL por método. Nunca "Conectado" sin paquetes reales. */
enum class TransportStatus {
    AVAILABLE,      // Disponible: se puede intentar (ej. Wi-Fi encendido, BT encendido).
    UNAVAILABLE,    // No disponible en este dispositivo / sin permiso / sin red.
    CONNECTED,      // Paquetes reales llegando en los últimos 2 s (ConnectionHealth).
    FAILED,         // Se intentó y falló (sin eco, socket roto, HID no expuesto).
    PENDING_STAGE2, // Estructura lista, implementación completa en Etapa 2.
}

data class TransportInfo(
    val method: TransportMethod,
    val status: TransportStatus,
    val detail: String = "",
)

object TransportSelector {
    /** Orden de prueba del modo Automático: UDP -> TCP -> BLE/SPP -> HID. */
    val AUTO_ORDER: List<TransportMethod> = listOf(
        TransportMethod.WIFI_UDP,
        TransportMethod.WIFI_TCP,
        TransportMethod.BLUETOOTH_DATA,
        TransportMethod.BLUETOOTH_HID,
    )

    /** El hotspot no se prueba como método aparte: usa UDP/TCP con la IP del hotspot. */
    fun hotspotUses(method: TransportMethod): Boolean =
        method == TransportMethod.WIFI_UDP || method == TransportMethod.WIFI_TCP
}

/** Contrato mínimo que debe cumplir cualquier transporte de envío. */
interface GamepadTransport {
    val method: TransportMethod
    /** Envía un estado. Lanza excepción si el socket/canal está roto; el llamador reintenta o cambia de método. */
    fun send(state: GamepadState)
    fun close()
}

/** Contrato mínimo del lado receptor (TV). */
interface GamepadReceiver {
    val method: TransportMethod
    /** Bloquea hasta un paquete válido o timeout; devuelve null por timeout. */
    fun receive(timeoutMs: Int): GamepadState?
    fun close()
}
