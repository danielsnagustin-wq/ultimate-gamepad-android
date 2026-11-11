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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Receptor TCP en la TV — Etapa 1, real y básico.
 * Puerto TCP 52000 (UDP sigue en 51999). Acepta una conexión,
 * lee paquetes de 32 bytes y los entrega al mismo punto que UDP:
 * UdpReceiverService.recordPacket() + deliver(), así TCP inyecta
 * en el mando virtual igual que UDP. Si el teléfono se desconecta,
 * acepta de nuevo sin reiniciar el servicio (reconexión automática).
 * Eco de ping TCP (Fase 1): los paquetes de ping se devuelven por
 * el mismo socket en vez de tratarse como entrada, para que el
 * teléfono mida un ping real por TCP y no "—".
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
                    localReceiver = runCatching { TcpGamepadReceiver(GamepadProtocol.DEFAULT_PORT + 1) { UdpReceiverService.packetsReceived } }.onFailure {
                        delay(1000L * (attempt + 1).coerceAtMost(5))
                    }.getOrNull()
                    attempt++
                }
                receiver = localReceiver
                val recv = localReceiver ?: return@launch
                while (isActive) {
                    val packet = runCatching { recv.receivePacket(1000) }.getOrNull()
                    if (packet == null) {
                        // Sin paquete o cliente muerto (receive ya
                        // soltó el socket): pequeña pausa para no
                        // girar en vacío.
                        delay(100)
                        continue
                    }
                    val (state, profileId) = packet
                    // Codex-1: solo se entrega si el paquete fue
                    // aceptado (secuencia nueva); igual que por UDP.
                    if (!UdpReceiverService.recordPacket(state, TransportMethod.WIFI_TCP)) continue
                    // H1 (Fase 1): antes solo se registraba el paquete;
                    // ahora también se entrega al mando virtual por el
                    // mismo punto único que UDP, así TCP inyecta de
                    // verdad y OpenNOW recibe la entrada por TCP igual
                    // que por UDP. El paquete se re-codifica desde el
                    // estado decodificado (byte a byte idéntico: misma
                    // secuencia, mismo timestamp, mismo CRC) y con el
                    // mismo profileId del byte 28 (Fase 2): sin él,
                    // por TCP todo se re-codificaba como Xbox 360.
                    UdpReceiverService.deliver(this@TcpReceiverService, state, GamepadProtocol.encode(state, profileId))
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
