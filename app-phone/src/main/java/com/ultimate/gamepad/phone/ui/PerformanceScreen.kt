package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * RENDIMIENTO (Fase 3) — latencia, jitter, paquetes/s y método,
 * en vivo y sin gráficos complicados: tabla de valores.
 *
 * Datos reales del servicio de envío (GamepadSenderService):
 * ping por eco UDP/TCP, jitter (variación del ping, media de
 * |ping[i] - ping[i-1]| sobre las últimas 20 muestras),
 * paquetes/s enviados (ventana de 1 s) y total enviado.
 *
 * Pérdida de paquetes: el teléfono NO la puede medir — por UDP el
 * emisor no ve lo que no llega. La pérdida se mide en la TV por
 * huecos de secuencia y se muestra en la PRUEBA DE MANDO de la TV.
 * Aquí se dice así en vez de inventar un 0%.
 */
@Composable
fun PerformanceScreen(vm: GamepadViewModel, ui: GamepadUiState, onClose: () -> Unit) {
    val vibration by vm.vibrationEnabled.collectAsState()
    val hapticContext = LocalContext.current
    fun tick() = vibrateTick(hapticContext, vibration)
    LaunchedEffect(Unit) {
        while (true) { vm.refreshPerformance(); delay(500) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("📶  Rendimiento", fontSize = 20.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Valores en vivo del envío (Wi-Fi / Bluetooth). Sin gráficos: lo que importa es el número.",
            color = Color.White, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        PerformanceRow("Estado", when (ui.status) {
            ConnectionStatus.CONNECTED -> "Conectado"
            ConnectionStatus.CONNECTING -> "Conectando…"
            ConnectionStatus.ERROR -> "Error"
            else -> "Desconectado"
        })
        PerformanceRow("Método activo", "${ui.attemptMethod.label} (${ui.transportMode})")
        PerformanceRow("TV responde", if (ui.tvResponding) "SÍ" else "NO")
        PerformanceRow("Latencia (ping)", ui.pingMs?.let { "$it ms" } ?: "—")
        PerformanceRow(
            "Jitter (variación del ping)",
            ui.pingJitterMs?.let { "$it ms" } ?: "— (faltan muestras: hacen falta 2 ecos)",
        )
        // Arreglo 08: el objetivo depende del método activo
        // (Wi-Fi 120 /s, Bluetooth HID 10 /s), no siempre 120.
        val sendRateHz = transportSendRateHz(if (ui.status == ConnectionStatus.DISCONNECTED) ui.selectedMethod else ui.attemptMethod)
        PerformanceRow("Paquetes/s enviados", "${ui.packetsPerSec} /s (objetivo ~$sendRateHz /s)")
        PerformanceRow("Paquetes enviados (total)", "${ui.packetsSent}")
        PerformanceRow(
            "TV recibe paquetes",
            when {
                ui.tvReceivingPackets -> "SÍ — ${ui.tvPacketsReceived} recibidos por la TV"
                ui.tvResponding -> "NO — la TV responde al ping pero no recibe paquetes de mando"
                else -> "Sin datos (sin eco de la TV)"
            },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Pérdida de paquetes: no se puede medir en el teléfono (por UDP el emisor no ve lo que no llega). Se mide en la TV por huecos de secuencia y se muestra en la PRUEBA DE MANDO de la TV.",
            color = Color(0xFFFFC107), fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Referencia en red local (Wi-Fi): ping 1–10 ms, jitter 0–3 ms y ~120 paquetes/s. Por Bluetooth HID el envío es de 10 paquetes/s. Si el ping sube o los paquetes/s caen, la red está saturada o la TV no responde; el modo Automático cambia a TCP.",
            color = Color.White, fontSize = 12.sp,
        )
        if (ui.error != null) {
            Spacer(Modifier.height(8.dp))
            Text("Último error: ${ui.error}", color = Color(0xFFFF5252), fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { tick(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
    }
}

@Composable
private fun PerformanceRow(label: String, value: String) {
    Text(label, color = Color.White, fontSize = 13.sp)
    Text(value, fontSize = 17.sp, color = Color.White)
    Spacer(Modifier.height(10.dp))
}
