package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import java.util.Locale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimate.gamepad.core.ControllerTestChecklist
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.phone.network.GamepadSender
import kotlinx.coroutines.delay

/**
 * PRUEBA DE MANDO en el teléfono (Fase 3) — la misma prueba en
 * pequeño: qué se está ENVIANDO.
 *
 * La checklist se alimenta del estado que el teléfono envía
 * (GamepadSender.currentState, muestreado cada 100 ms): si un
 * botón no se ha pulsado aquí, no sale marcado. Los valores
 * analógicos en vivo son los que salen del teléfono, no los que
 * ve Android en la TV.
 *
 * Las 4 etapas, con honestidad sobre lo que el teléfono puede y
 * no puede comprobar:
 * 1. Teléfono envía: SÍ/NO según la conexión real.
 * 2. Receptor recibe: el teléfono NO lo puede saber (no hay canal
 *    TV → teléfono): la pantalla dice que se mire la prueba de la
 *    TV, no lo marca sola.
 * 3. Android registra: igual que la 2: se comprueba en el
 *    diagnóstico de la TV.
 * 4. OpenNOW recibe: SOLO se marca cuando el usuario confirma
 *    que lo ha probado en un juego (botón manual). Nunca se marca
 *    sola.
 */
@Composable
fun ControllerTestScreen(vm: GamepadViewModel, ui: GamepadUiState, onClose: () -> Unit) {
    val checklist = remember { ControllerTestChecklist() }
    var state by remember { mutableStateOf(GamepadSender.currentState) }
    var openNowConfirmed by rememberSaveable { mutableStateOf(false) }
    val profile = GamepadProfiles.fromId(ui.activeProfileId)
    val vibration by vm.vibrationEnabled.collectAsState()
    val hapticContext = LocalContext.current
    fun tick() = vibrateTick(hapticContext, vibration)
    LaunchedEffect(Unit) {
        while (true) {
            val current = GamepadSender.currentState
            state = current
            checklist.update(current)
            delay(100)
        }
    }
    // El ping (y con él, si la TV recibe paquetes) solo se
    // refrescaba en la pantalla del mando: al abrir la prueba, el
    // dato se quedaba viejo. Aquí se refresca cada 500 ms para que
    // la etapa 2 sea en vivo.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshPing()
            delay(500)
        }
    }
    // `state` es State: cada muestreo (100 ms) recompone y la
    // checklist se vuelve a leer con lo marcado hasta ahora.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("🧪  Prueba de mando (teléfono)", fontSize = 20.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Esto es lo que se está ENVIANDO desde el teléfono. Pulsa cada botón y mueve cada stick: la lista se marca sola. Lo que ve Android en la TV se comprueba en la prueba de la TV.",
            color = Color.White, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text("Perfil: ${profile.name} (${profile.deviceName})", fontSize = 14.sp, color = Color.White)
        Text(
            "Método: ${ui.attemptMethod.label} · Ping: ${ui.pingMs?.let { "$it ms" } ?: "—"}",
            color = Color.White, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "En vivo — Stick izq: (${String.format(Locale.US, "%.2f", state.leftX)}, ${String.format(Locale.US, "%.2f", state.leftY)})  " +
                "Stick der: (${String.format(Locale.US, "%.2f", state.rightX)}, ${String.format(Locale.US, "%.2f", state.rightY)})  " +
                "LT: ${String.format(Locale.US, "%.2f", state.leftTrigger)} RT: ${String.format(Locale.US, "%.2f", state.rightTrigger)}",
            fontSize = 14.sp,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text("Checklist — ${checklist.testedCount}/${checklist.totalCount} controles probados", fontSize = 15.sp, color = Color.White)
        for (item in checklist.items(ui.activeProfileId)) {
            Text(
                (if (item.tested) "✓ " else "○ ") + item.label,
                color = if (item.tested) Color(0xFF4CAF50) else Color.White,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { tick(); checklist.reset() }) { Text("Reiniciar prueba") }
        Spacer(Modifier.height(16.dp))
        Text("Las 4 etapas", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "1. Teléfono envía: " + when (ui.status) {
                ConnectionStatus.CONNECTED -> "SÍ — conectado, enviando a ${transportSendRateHz(ui.attemptMethod)} Hz por ${ui.attemptMethod.label}."
                ConnectionStatus.CONNECTING -> "Aún no — conectando…"
                else -> "NO — sin conexión."
            },
            fontSize = 14.sp,
            color = Color.White,
        )
        Text(
            "2. Receptor recibe: " + when {
                ui.tvReceivingPackets -> "SÍ — la TV ha recibido ${ui.tvPacketsReceived} paquetes de mando. Si el juego no responde con paquetes llegando, el juego solo ve el mando por Bluetooth HID: empareja el teléfono en Ajustes > Bluetooth de la TV (Shizuku está descartado en esta TV)."
                ui.tvResponding -> "NO — la TV responde al ping pero no recibe paquetes de mando."
                else -> "Sin datos — conecta primero (el teléfono aún no tiene eco de la TV)."
            },
            fontSize = 14.sp,
            color = Color.White,
        )
        Text(
            "3. Android registra: sin datos en el teléfono — míralo en el diagnóstico de la TV (\"Parece mando real\").",
            fontSize = 14.sp,
            color = Color.White,
        )
        Text(
            "4. OpenNOW recibe: " + if (openNowConfirmed) "SÍ — confirmado por ti tras probar un juego." else "PENDIENTE — solo se marca tras probar un juego.",
            color = if (openNowConfirmed) Color(0xFF4CAF50) else Color(0xFFFFC107),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { tick(); openNowConfirmed = !openNowConfirmed }, modifier = Modifier.fillMaxWidth()) {
            Text(if (openNowConfirmed) "✓ Confirmado en un juego (tocar para quitar)" else "Confirmo que funciona en un juego", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { tick(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
    }
}
