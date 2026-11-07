package com.ultimate.gamepad.tv.receiver

import com.ultimate.gamepad.core.BluetoothDataReceiver
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.TransportStatus

/**
 * Receptor Bluetooth-datos (BLE/SPP) en la TV — Etapa 2.
 * En Etapa 1 solo existe esta estructura: el receptor está listo
 * para recibir el mismo paquete de 32 bytes (ver onPacket), pero
 * no hay GATT ni RFCOMM. Estado: PENDING_STAGE2. La UI de la TV
 * debe mostrar "Pendiente Etapa 2", nunca "Conectado".
 */
class BluetoothDataReceiverStub : BluetoothDataReceiver {
    override val status: TransportStatus = TransportStatus.PENDING_STAGE2
    override fun start(onPacket: (GamepadState) -> Unit) { /* Etapa 2: GATT/RFCOMM. */ }
    override fun stop() { /* Etapa 2. */ }
}
