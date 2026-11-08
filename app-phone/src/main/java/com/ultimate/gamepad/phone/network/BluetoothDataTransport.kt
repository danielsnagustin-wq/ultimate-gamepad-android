package com.ultimate.gamepad.phone.network

import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportStatus

/**
 * Bluetooth datos a la app receptora (BLE / SPP) — Fase 5 del plan.
 *
 * NO IMPLEMENTADO (2026-11-07): solo está esta interfaz y su estado
 * [TransportStatus.PENDING_STAGE2]. No hay GATT, no hay RFCOMM, no
 * hay emparejamiento. La UI muestra "No implementado", nunca
 * "Conectado" ni "Disponible", y el modo AUTO lo salta.
 *
 * Por qué no se implementa en este lote: es el transporte con más
 * trabajo (GATT server en la TV + cliente en el teléfono con
 * fragmentación del paquete de 32 bytes o MTU > 32, o SPP/RFCOMM
 * con emparejamiento clásico) y menos valor si el Wi-Fi es
 * estable; además no se puede verificar sin la TV real delante
 * (radio BT de la Hyundai, emparejamiento). Implementarlo a ciegas
 * sería fingir un respaldo. Trabajo documentado, no empezado:
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
    fun send(state: GamepadState): Boolean = false // Fase 5: no implementado.
}
