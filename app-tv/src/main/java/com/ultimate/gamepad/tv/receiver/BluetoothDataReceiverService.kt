package com.ultimate.gamepad.tv.receiver

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.TransportStatus

/**
 * Servicio del receptor Bluetooth datos (SPP/RFCOMM, Fase 5).
 * Espejo de TcpReceiverService: escucha por SPP y entrega cada
 * paquete de 32 bytes por el mismo punto único (recordPacket +
 * deliver) que UDP/TCP, así que la inyección, el perfil, el
 * watchdog y PRUEBA DE MANDO funcionan igual por BT.
 * No se toca UdpReceiverService.kt: su onStartCommand sigue
 * arrancando solo el TCP; el punto único de arranque es MainActivity.
 */
class BluetoothDataReceiverService : Service() {
    companion object {
        @Volatile var btStatus: TransportStatus = TransportStatus.UNAVAILABLE
            private set
        @Volatile var btStatusDetail: String = "Receptor Bluetooth parado."
            private set
        @Volatile var btPacketsReceived: Long = 0
            private set
    }

    private var receiver: BluetoothDataReceiverImpl? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recv = BluetoothDataReceiverImpl(applicationContext)
        receiver = recv
        recv.start { _ ->
            btStatus = recv.status; btStatusDetail = recv.statusDetail
            btPacketsReceived = recv.packetsReceived
        }
        btStatus = recv.status; btStatusDetail = recv.statusDetail
        return START_STICKY
    }

    override fun onDestroy() {
        // Soltar controles explícito (T1): parar el servicio UDP mata
        // su watchdog; si el último estado no es cero, enviar el cero
        // por el punto único antes de parar (solo si había entrada).
        if (UdpReceiverService.lastState != GamepadState()) {
            UdpReceiverService.deliver(
                this, GamepadState(),
                GamepadProtocol.encode(GamepadState(), UdpReceiverService.activeProfileId),
            )
        }
        receiver?.stop(); receiver = null
        super.onDestroy()
    }
}
