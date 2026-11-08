package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimate.gamepad.core.ExtrasProtocol
import com.ultimate.gamepad.core.GamepadButtons
import kotlin.math.roundToInt

/**
 * Extras — Fase 4 (ULTIMATE GAMEPAD PRO).
 *
 * Todo es opcional: el mando funciona igual sin tocar nada aquí.
 * Secciones, en el orden del plan:
 * - Turbo: pulsos automáticos mientras se mantiene el botón.
 * - Macros: grabar una secuencia de botones y reproducirla.
 * - Vibración al pulsar (teléfono). La vibración DEL JUEGO no
 *   existe y la pantalla lo dice (no se promete).
 * - Giroscopio: inclinar el teléfono mueve el stick derecho.
 * - Teclado virtual: dispositivo aparte en la TV, para escribir
 *   en el chat de GeForce NOW.
 * - Modo ratón: dispositivo aparte en la TV, para mover el
 *   cursor en los menús.
 *
 * Teclado y ratón necesitan la TV conectada (van por Wi-Fi UDP a
 * la TV); sin conexión, los botones avisan y no fingen nada.
 */
@Composable
fun ExtrasScreen(vm: GamepadViewModel, ui: GamepadUiState, onClose: () -> Unit) {
    val turboButtons by vm.turboButtons.collectAsState()
    val turboRate by vm.turboRateHz.collectAsState()
    val vibration by vm.vibrationEnabled.collectAsState()
    val gyroOn by vm.gyroEnabled.collectAsState()
    val gyroSens by vm.gyroSensitivity.collectAsState()
    val macros by vm.macros.collectAsState()
    val recording by vm.recordingMacro.collectAsState()
    val playing by vm.playingMacro.collectAsState()
    var macroName by remember { mutableStateOf("") }
    var keyboardText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .padding(16.dp),
    ) {
        Text("✨  Extras — Fase 4", fontSize = 20.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Turbo, macros, vibración, giroscopio, teclado virtual y modo ratón. Todo es opcional: el mando funciona igual sin tocar nada aquí.",
            color = Color.White,
            fontSize = 12.sp,
        )
        // P7: antes ui.error/ui.info tenían 0 ocurrencias aquí:
        // los avisos de macros, teclado y ratón se perdían. Mismo
        // patrón que PerformanceScreen (error en rojo, info en verde).
        if (ui.error != null) {
            Spacer(Modifier.height(8.dp))
            Text("Último error: ${ui.error}", color = Color(0xFFFF5252), fontSize = 13.sp)
        }
        if (ui.info != null) {
            Spacer(Modifier.height(8.dp))
            Text(ui.info!!, color = Color(0xFF9BF00B), fontSize = 13.sp)
        }

        // --- Turbo ---
        Spacer(Modifier.height(16.dp))
        Text("Turbo (pulsos automáticos)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Mientras mantienes un botón con turbo, el teléfono lo pulsa y suelta solo a $turboRate Hz. Solo teléfono: la TV recibe los pulsos como pulsaciones normales, sin cambios en la TV.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((bit, label) in listOf(
                GamepadButtons.A to "A",
                GamepadButtons.B to "B",
                GamepadButtons.X to "X",
                GamepadButtons.Y to "Y",
                GamepadButtons.LB to "LB",
                GamepadButtons.RB to "RB",
            )) {
                Button(onClick = { vm.setTurbo(bit, bit !in turboButtons) }) {
                    Text(if (bit in turboButtons) "$label ✓" else label, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Velocidad: $turboRate Hz", fontSize = 12.sp, color = Color.White)
        Slider(
            value = turboRate.toFloat(),
            onValueChange = { vm.setTurboRate(it.roundToInt()) },
            valueRange = 5f..20f,
        )

        // --- Macros ---
        Spacer(Modifier.height(16.dp))
        Text("Macros (grabar y reproducir)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Graba una secuencia de botones con sus tiempos (máx. 30 s) y reprodúcela con un toque. Solo botones: no graba sticks ni gatillos. Solo teléfono.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = macroName,
            onValueChange = { macroName = it.take(40) },
            label = { Text("Nombre de la macro", color = Color.White) },
            modifier = Modifier.fillMaxWidth(),
            enabled = recording == null,
        )
        Spacer(Modifier.height(8.dp))
        if (recording == null) {
            Button(onClick = { vm.startMacroRecording(macroName) }) { Text("●  Grabar macro") }
        } else {
            Button(onClick = { vm.stopMacroRecording() }) { Text("■  Parar y guardar ($recording)") }
        }
        for (macro in macros) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${macro.name} — ${macro.durationMs} ms, ${macro.events.size} eventos",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.playMacro(macro.name) }, enabled = playing == null) {
                    Text(if (playing == macro.name) "▶…" else "▶", color = Color.White)
                }
                Button(onClick = { vm.deleteMacro(macro.name) }, enabled = playing == null) { Text("🗑") }
            }
        }
        if (playing != null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.stopMacroPlayback() }) { Text("■  Parar reproducción ($playing)") }
        }

        // --- Vibración ---
        Spacer(Modifier.height(16.dp))
        Text("Vibración al pulsar (teléfono)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Vibra un instante al pulsar un botón del teléfono. Respeta el ajuste del perfil de juego activo (Fase 3): al aplicar un perfil, su ajuste de vibración pisa este interruptor.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.setVibrationEnabled(!vibration) }) {
            Text(if (vibration) "Vibración: SÍ (toca para apagar)" else "Vibración: NO (toca para encender)", color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "La vibración DEL JUEGO (que el juego haga vibrar el mando) NO existe: necesitaría force feedback (EV_FF / FF_RUMBLE) en el mando virtual y no está implementado. Ninguna pantalla la promete.",
            color = Color(0xFFFFC107),
            fontSize = 12.sp,
        )

        // --- Giroscopio ---
        Spacer(Modifier.height(16.dp))
        Text("Giroscopio (inclinar = stick derecho)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Al activarlo, la inclinación del teléfono mueve el stick derecho y el stick táctil derecho queda ignorado. Desactívalo para volver al stick táctil. La dirección exacta está pendiente de verificar en el teléfono real.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.setGyroEnabled(!gyroOn) }) {
            Text(if (gyroOn) "Giroscopio: SÍ (toca para apagar)" else "Giroscopio: NO (toca para encender)", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text("Sensibilidad: ${"%.1f".format(gyroSens)}x (1.0 = 45° recorre todo el stick)", fontSize = 12.sp, color = Color.White)
        Slider(
            value = gyroSens,
            onValueChange = { vm.setGyroSensitivity(it) },
            valueRange = 0.5f..2f,
            enabled = gyroOn,
        )

        // --- Teclado virtual ---
        Spacer(Modifier.height(16.dp))
        Text("Teclado virtual (chat de GeForce NOW)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Crea un dispositivo de TECLADO aparte en la TV (uinput, \"Ultimate Gamepad Keyboard\"): nunca se mezcla con el mando. Actívalo, escribe y envía. Caracteres sin tecla (p. ej. ñ) se saltan y se avisa.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.setKeyboardEnabled(!ui.keyboardEnabled) }) {
            Text(if (ui.keyboardEnabled) "Teclado en la TV: SÍ (toca para apagar)" else "Teclado en la TV: NO (toca para encender)", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keyboardText,
            onValueChange = { keyboardText = it },
            label = { Text("Texto para la TV", color = Color.White) },
            modifier = Modifier.fillMaxWidth(),
            enabled = ui.keyboardEnabled,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.typeText(keyboardText) },
                enabled = ui.keyboardEnabled && keyboardText.isNotEmpty(),
            ) { Text("Enviar texto") }
            Button(onClick = { vm.sendSpecialKey("enter") }, enabled = ui.keyboardEnabled) { Text("Enter ⏎") }
            Button(onClick = { vm.sendSpecialKey("backspace") }, enabled = ui.keyboardEnabled) { Text("⌫ Borrar") }
            Button(onClick = { vm.sendSpecialKey("space") }, enabled = ui.keyboardEnabled) { Text("Espacio") }
        }

        // --- Modo ratón ---
        Spacer(Modifier.height(16.dp))
        Text("Modo ratón (mover el cursor)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Crea un dispositivo de RATÓN aparte en la TV (uinput, \"Ultimate Gamepad Mouse\"): mueve el cursor en los menús y haz clic. Arrastra en el panel de abajo para moverlo.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.setMouseEnabled(!ui.mouseEnabled) }) {
            Text(if (ui.mouseEnabled) "Ratón en la TV: SÍ (toca para apagar)" else "Ratón en la TV: NO (toca para encender)", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .pointerInput(ui.mouseEnabled) {
                    if (!ui.mouseEnabled) return@pointerInput
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        vm.mouseMove(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (ui.mouseEnabled) "Arrastra aquí para mover el cursor" else "Activa el ratón para usar el panel",
                color = Color.White,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.mouseClick(ExtrasProtocol.MOUSE_BUTTON_LEFT) },
                enabled = ui.mouseEnabled,
            ) { Text("Clic izquierdo") }
            Button(
                onClick = { vm.mouseClick(ExtrasProtocol.MOUSE_BUTTON_RIGHT) },
                enabled = ui.mouseEnabled,
            ) { Text("Clic derecho") }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose) { Text("Volver") }
    }
}
