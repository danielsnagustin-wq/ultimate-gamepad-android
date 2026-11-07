package com.ultimate.gamepad.phone.network

import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportStatus

/**
 * Bluetooth datos a la app receptora (BLE / SPP) — Etapa 2.
 *
 * En Etapa 1 SOLO está esta interfaz y su estado
 * [TransportStatus.PENDING_STAGE2]. No hay GATT, no hay RFCOMM,
 * no hay emparejamiento. La UI debe mostrar "Pendiente Etapa 2",
 * nunca "Conectado".
 *
 * Trabajo de Etapa 2 (documentado, no empezado):
 * - BLE: GATT server en la TV + cliente en el teléfono, característica
 *   de 32 bytes (el paquete GamepadProtocol entero no cabe en una
 *   notificación BLE de 20 bytes sin fragmentar: hay que fragmentar
 *   en 2 notificaciones o negociar MTU > 32).
 * - SPP/RFCOMM: socket clásico, flujo continuo como TCP, más simple
 *   pero con emparejamiento Bluetooth clásico.
 * - En ambos casos, el receptor TV reutiliza GamepadProtocol.decode,
 *   igual que UDP/TCP.
 */
object BluetoothDataTransport {
    val method: TransportMethod = TransportMethod.BLUETOOTH_DATA
    val status: TransportStatus = TransportStatus.PENDING_STAGE2
    fun send(state: GamepadState): Boolean = false // Etapa 2: no implementado.
}
