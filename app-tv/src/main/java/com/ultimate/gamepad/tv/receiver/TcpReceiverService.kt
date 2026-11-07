package com.ultimate.gamepad.tv.receiver

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.TcpGamepadReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Receptor TCP en la TV — Etapa 1, real y básico.
 * Puerto TCP 52000 (UDP sigue en 51999). Acepta una conexión,
 * lee paquetes de 32 bytes y los entrega al mismo punto que UDP:
 * UdpReceiverService.recordPacket(). Si el teléfono se desconecta,
 * acepta de nuevo sin reiniciar el servicio (reconexión automática).
 * El eco de ping TCP queda para Etapa 2; en Etapa 1 el ping por
 * TCP se muestra como "—" en el teléfono y no se inventa.
 */
class TcpReceiverService : Service() {
    private var job: Job? = null
    private var receiver: TcpGamepadReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            receiver = TcpGamepadReceiver(GamepadProtocol.DEFAULT_PORT + 1)
            while (isActive) {
                val state = runCatching { receiver?.receive(1000) }.getOrNull() ?: continue
                UdpReceiverService.recordPacket(state, com.ultimate.gamepad.core.TransportMethod.WIFI_TCP)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { job?.cancel(); receiver?.close(); super.onDestroy() }
}
