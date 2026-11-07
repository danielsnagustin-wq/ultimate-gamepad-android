package com.ultimate.gamepad.tv.receiver

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.TcpGamepadReceiver
import com.ultimate.gamepad.core.TransportMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var receiver: TcpGamepadReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cerrar el receptor viejo ANTES de crear el nuevo: si no, el
        // ServerSocket viejo sigue ligado al 52000 y el nuevo bind
        // falla; la corrutina moría y el TCP dejaba de escuchar.
        job?.cancel()
        runCatching { receiver?.close() }; receiver = null
        job = scope.launch {
            // Igual que en UdpReceiverService: el receptor vive como
            // local y el campo solo es un espejo para cerrarlo desde
            // fuera; el finally no toca el receptor nuevo.
            var localReceiver: TcpGamepadReceiver? = null
            try {
                // Reintentar la creación: si el puerto sigue ocupado,
                // esperar y reintentar en vez de morir en silencio.
                var attempt = 0
                while (isActive && localReceiver == null) {
                    localReceiver = runCatching { TcpGamepadReceiver(GamepadProtocol.DEFAULT_PORT + 1) }.onFailure {
                        delay(1000L * (attempt + 1).coerceAtMost(5))
                    }.getOrNull()
                    attempt++
                }
                receiver = localReceiver
                val recv = localReceiver ?: return@launch
                while (isActive) {
                    val state = runCatching { recv.receive(1000) }.getOrNull()
                    if (state == null) {
                        // Sin paquete o cliente muerto (receive ya
                        // soltó el socket): pequeña pausa para no
                        // girar en vacío.
                        delay(100)
                        continue
                    }
                    UdpReceiverService.recordPacket(state, TransportMethod.WIFI_TCP)
                }
            } finally {
                val r = localReceiver; localReceiver = null
                runCatching { r?.close() }
                if (receiver === r) receiver = null
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { job?.cancel(); runCatching { receiver?.close() }; receiver = null; scope.cancel(); super.onDestroy() }
}
