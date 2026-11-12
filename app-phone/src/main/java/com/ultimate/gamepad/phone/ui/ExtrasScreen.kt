package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import java.util.Locale
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
 * - Vibración al pulsar (teléfono). La vibración DEL JUEGO
 *   (rumble) existe por Bluetooth HID (ronda REA GFN+OpenNOW);
 *   por Wi-Fi no se puede y la pantalla lo dice.
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
    val gameRumble by vm.gameRumbleEnabled.collectAsState()
    val gyroOn by vm.gyroEnabled.collectAsState()
    val gyroSens by vm.gyroSensitivity.collectAsState()
    val l4Remap by vm.l4Remap.collectAsState()
    val r4Remap by vm.r4Remap.collectAsState()
    val macros by vm.macros.collectAsState()
    val recording by vm.recordingMacro.collectAsState()
    val recordingProgress by vm.recordingMacroProgress.collectAsState()
    val playing by vm.playingMacro.collectAsState()
    val hapticContext = LocalContext.current
    fun tick() = vibrateTick(hapticContext, vibration)
    var macroName by rememberSaveable { mutableStateOf("") }
    var keyboardText by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
        // Arreglo 10: 6 botones en una sola fila desbordaban en
        // 360 dp. Dos filas de 3, con peso igual.
        for (row in listOf(
            GamepadButtons.A to "A",
            GamepadButtons.B to "B",
            GamepadButtons.X to "X",
            GamepadButtons.Y to "Y",
            GamepadButtons.LB to "LB",
            GamepadButtons.RB to "RB",
        ).chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((bit, label) in row) {
                    Button(onClick = { tick(); vm.setTurbo(bit, bit !in turboButtons) }, modifier = Modifier.weight(1f)) {
                        Text(if (bit in turboButtons) "$label ✓" else label, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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
            "Graba una secuencia de botones con sus tiempos (máx. 30 s y 200 eventos) y reprodúcela con un toque. Solo botones: no graba sticks ni gatillos. Solo teléfono. Lo que pase del tope no se graba.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        // P5/P6: estado de grabación visible. Antes el botón ▶
        // quedaba mudo mientras se grababa (playMacro no hace nada
        // si recording != null) y el tope era silencioso. Ahora la
        // grabación se ve, ▶ y 🗑 se desactivan mientras se graba y
        // el tope se anuncia (el aviso al llegar al tope necesita
        // estado del ViewModel: parche en el manifiesto).
        if (recording != null) {
            Text(
                "● Grabando macro «$recording» — pulsa los botones del mando; para con «Parar y guardar». " +
                    (recordingProgress?.let { (elapsedMs, events) -> "${elapsedMs / 1000} s / 30 s - $events/200 eventos" } ?: "Límite: 30 s / 200 eventos") + ".",
                color = Color(0xFFFF5252),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = macroName,
            onValueChange = { macroName = it.take(40) },
            label = { Text("Nombre de la macro", color = Color.White) },
            modifier = Modifier.fillMaxWidth(),
            enabled = recording == null,
        )
        Spacer(Modifier.height(8.dp))
        if (recording == null) {
            Button(onClick = { tick(); vm.startMacroRecording(macroName) }) { Text("●  Grabar macro") }
        } else {
            Button(onClick = { tick(); vm.stopMacroRecording(); macroName = "" }) { Text("■  Parar y guardar ($recording)") }
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
                Button(onClick = { tick(); vm.playMacro(macro.name) }, enabled = playing == null && recording == null) {
                    Text(if (playing == macro.name) "▶…" else "▶", color = Color.White)
                }
                Button(onClick = { tick(); vm.deleteMacro(macro.name) }, enabled = playing == null && recording == null) { Text("🗑") }
            }
        }
        if (playing != null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { tick(); vm.stopMacroPlayback() }) { Text("■  Parar reproducción ($playing)") }
        }

        // --- Paletas L4/R4 ---
        Spacer(Modifier.height(16.dp))
        Text("Paletas L4/R4 (actúan como L3/R3)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "GeForce NOW y OpenNOW ignoran las paletas como botones propios y el mando Bluetooth no tiene hueco para ellas: cada paleta actúa como un botón del mando (por defecto L4→L3, R4→R3). En el juego, pulsar L4 es lo mismo que pulsar L3. Funciona por Bluetooth, Wi-Fi y en macros.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text("L4 actúa como:", fontSize = 12.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((bit, label) in listOf(0 to "Ninguna", GamepadButtons.L3 to "L3", GamepadButtons.R3 to "R3")) {
                Button(onClick = { tick(); vm.setL4Remap(bit) }, modifier = Modifier.weight(1f)) {
                    Text(if (l4Remap == bit) "$label ✓" else label, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("R4 actúa como:", fontSize = 12.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((bit, label) in listOf(0 to "Ninguna", GamepadButtons.L3 to "L3", GamepadButtons.R3 to "R3")) {
                Button(onClick = { tick(); vm.setR4Remap(bit) }, modifier = Modifier.weight(1f)) {
                    Text(if (r4Remap == bit) "$label ✓" else label, color = Color.White)
                }
            }
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
        Button(onClick = { tick(); vm.setVibrationEnabled(!vibration) }) {
            Text(if (vibration) "Vibración: SÍ (toca para apagar)" else "Vibración: NO (toca para encender)", color = Color.White)
        }

        // --- Vibración del juego (rumble por Bluetooth HID) ---
        Spacer(Modifier.height(16.dp))
        Text("Vibración del juego (rumble)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Cuando el juego hace vibrar el mando (explosiones, choques, disparos), el teléfono vibra. Solo por Bluetooth HID: el mando declara force feedback y la TV le manda los efectos. Por Wi-Fi no se puede (la TV no puede leer los efectos del mando virtual sin Shizuku). El teléfono tiene un solo motor: fuerte y débil vibran igual.",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.setGameRumbleEnabled(!gameRumble) }) {
            Text(if (gameRumble) "Rumble del juego: SÍ (toca para apagar)" else "Rumble del juego: NO (toca para encender)", color = Color.White)
        }

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
        Button(onClick = { tick(); vm.setGyroEnabled(!gyroOn) }) {
            Text(if (gyroOn) "Giroscopio: SÍ (toca para apagar)" else "Giroscopio: NO (toca para encender)", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text("Sensibilidad: ${String.format(Locale.US, "%.1f", gyroSens)}x (1.0 = 45° recorre todo el stick)", fontSize = 12.sp, color = Color.White)
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
        Button(onClick = { tick(); vm.setKeyboardEnabled(!ui.keyboardEnabled) }) {
            Text(if (ui.keyboardEnabled) "Teclado en la TV: enviado, sin confirmar (toca para apagar)" else "Teclado en la TV: NO (toca para encender)", color = Color.White)
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
        // Arreglo 10: 4 botones en una fila desbordaban en 360 dp.
        // Dos filas de 2, con peso igual.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { tick(); vm.typeText(keyboardText) },
                enabled = ui.keyboardEnabled && keyboardText.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Enviar texto") }
            Button(onClick = { tick(); vm.sendSpecialKey("enter") }, enabled = ui.keyboardEnabled, modifier = Modifier.weight(1f)) { Text("Enter ⏎") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { tick(); vm.sendSpecialKey("backspace") }, enabled = ui.keyboardEnabled, modifier = Modifier.weight(1f)) { Text("⌫ Borrar") }
            Button(onClick = { tick(); vm.sendSpecialKey("space") }, enabled = ui.keyboardEnabled, modifier = Modifier.weight(1f)) { Text("Espacio") }
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
        Button(onClick = { tick(); vm.setMouseEnabled(!ui.mouseEnabled) }) {
            Text(if (ui.mouseEnabled) "Ratón en la TV: enviado, sin confirmar (toca para apagar)" else "Ratón en la TV: NO (toca para encender)", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111111))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
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
                onClick = { tick(); vm.mouseClick(ExtrasProtocol.MOUSE_BUTTON_LEFT) },
                enabled = ui.mouseEnabled,
                modifier = Modifier.weight(1f),
            ) { Text("Clic izquierdo") }
            Button(
                onClick = { tick(); vm.mouseClick(ExtrasProtocol.MOUSE_BUTTON_RIGHT) },
                enabled = ui.mouseEnabled,
                modifier = Modifier.weight(1f),
            ) { Text("Clic derecho") }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { tick(); onClose() }) { Text("Volver") }
    }
}
