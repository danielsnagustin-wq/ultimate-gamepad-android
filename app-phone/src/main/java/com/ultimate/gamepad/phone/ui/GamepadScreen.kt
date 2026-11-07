package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimate.gamepad.core.GamepadButtons
import kotlinx.coroutines.delay

/**
 * Mando estilo Xbox, premium. Toda la pantalla es el mando:
 * dos sticks, cruceta, A/B/X/Y, LB/RB, LT/RT analógicos, L3/R3
 * (tocando el stick), VIEW, MENU, botón Xbox (HOME) y Compartir.
 * Nada es decorativo: cada botón envía su bit real por el
 * protocolo de 32 bytes (ver GamepadButtons).
 *
 * Lo de arriba (estado, métodos, conexión) ya no estorba: vive
 * en Ajustes (⚙, arriba a la derecha) y en la píldora de estado
 * del centro, que también abre Ajustes al tocarla.
 */
@Composable
fun GamepadScreen(vm: GamepadViewModel) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(ui.status) {
        vm.refreshHidStatus()
        // El ping se vigila también en CONECTANDO: si no, el estado
        // CONECTANDO nunca se resolvería y la pantalla se quedaría
        // tiesa esperando un eco que nadie comprueba.
        while (ui.status == ConnectionStatus.CONNECTED || ui.status == ConnectionStatus.CONNECTING) { vm.refreshPing(); delay(1000) }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF171B30), Color(0xFF0B0E14))))
            .padding(12.dp),
    ) {
        val isLandscape = maxWidth > maxHeight
        Column(modifier = Modifier.fillMaxSize()) {
            ShoulderRow(vm, ui)
            if (ui.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(ui.error!!, color = Color(0xFFFF5252), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            if (ui.status == ConnectionStatus.CONNECTED && !ui.tvResponding) {
                Spacer(Modifier.height(6.dp))
                Text("TV sin respuesta (sin eco de ping en 3 s). En modo Automático se probará el siguiente método.", color = Color(0xFFFFC107), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LeftControls(vm)
                    CenterControls(vm, horizontal = false)
                    RightControls(vm)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StickWithLabel(vm, left = true)
                    AbxyPad(vm)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DpadView(onChange = { up, down, left, right -> vm.setDpad(up, down, left, right) })
                    StickWithLabel(vm, left = false)
                }
                Spacer(Modifier.height(12.dp))
                CenterControls(vm, horizontal = true)
            }
        }
        if (ui.showSettings) SettingsScreen(vm, ui)
        if (ui.showQrScanner) QrScannerScreen(onResult = { vm.connectFromQr(it) }, onClose = { vm.closeSettings() })
    }
}

/** Hombros: LT/LB a la izquierda, RB/RT a la derecha, estado y ⚙ al centro/derecha. */
@Composable
private fun ShoulderRow(vm: GamepadViewModel, ui: GamepadUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShoulderButton("LT") { pressed -> vm.setLeftTrigger(if (pressed) 1f else 0f) }
            ShoulderButton("LB") { pressed -> vm.setButton(GamepadButtons.LB, pressed) }
        }
        StatusPill(vm, ui)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShoulderButton("RB") { pressed -> vm.setButton(GamepadButtons.RB, pressed) }
            ShoulderButton("RT") { pressed -> vm.setRightTrigger(if (pressed) 1f else 0f) }
            GearButton { vm.openSettings() }
        }
    }
}

/** Píldora de estado pequeña (tocarla abre Ajustes). */
@Composable
private fun StatusPill(vm: GamepadViewModel, ui: GamepadUiState) {
    val (dot, text) = when (ui.status) {
        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50) to "● Conectado${ui.pingMs?.let { " · $it ms" } ?: ""}"
        ConnectionStatus.CONNECTING -> Color(0xFFFFC107) to "● Conectando por ${ui.attemptMethod.label}…"
        ConnectionStatus.ERROR -> Color(0xFFFF5252) to "● Error"
        else -> Color(0xFF9AA0B0) to "● Desconectado"
    }
    Text(
        text = text,
        color = dot,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.clickable { vm.openSettings() }.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/** Botón redondo ⚙ que abre Ajustes y conexión. */
@Composable
private fun GearButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFF232839))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text("⚙", color = Color.White, fontSize = 20.sp) }
}

/** Izquierda: stick + cruceta. */
@Composable
private fun LeftControls(vm: GamepadViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StickWithLabel(vm, left = true)
        DpadView(onChange = { up, down, left, right -> vm.setDpad(up, down, left, right) })
    }
}

/** Derecha: A/B/X/Y + stick derecho. */
@Composable
private fun RightControls(vm: GamepadViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AbxyPad(vm)
        StickWithLabel(vm, left = false)
    }
}

/** Stick con su etiqueta L3/R3 (tocar el stick = pulsar L3/R3, como en un mando real). */
@Composable
private fun StickWithLabel(vm: GamepadViewModel, left: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (left) {
            JoystickView(
                onMove = { x, y -> vm.setLeftStick(x, y) },
                onRelease = { vm.setLeftStick(0f, 0f) },
                onClick = { vm.pulseButton(GamepadButtons.L3) },
            )
            Text("L3 · toca el stick", color = Color(0xFF666C7A), fontSize = 10.sp)
        } else {
            JoystickView(
                onMove = { x, y -> vm.setRightStick(x, y) },
                onRelease = { vm.setRightStick(0f, 0f) },
                onClick = { vm.pulseButton(GamepadButtons.R3) },
            )
            Text("R3 · toca el stick", color = Color(0xFF666C7A), fontSize = 10.sp)
        }
    }
}

/** Centro: botón Xbox, VIEW, Compartir y MENU. */
@Composable
private fun CenterControls(vm: GamepadViewModel, horizontal: Boolean) {
    if (horizontal) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.weight(1f))
            XboxButton(vm, sizeDp = 56)
            SmallButton("VIEW") { pressed -> vm.setButton(GamepadButtons.SELECT, pressed) }
            PulsePill("SHARE") { vm.pulseButton(GamepadButtons.CAPTURE) }
            SmallButton("MENU") { pressed -> vm.setButton(GamepadButtons.START, pressed) }
            Spacer(Modifier.weight(1f))
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            XboxButton(vm, sizeDp = 72)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("VIEW") { pressed -> vm.setButton(GamepadButtons.SELECT, pressed) }
                PulsePill("SHARE") { vm.pulseButton(GamepadButtons.CAPTURE) }
                SmallButton("MENU") { pressed -> vm.setButton(GamepadButtons.START, pressed) }
            }
        }
    }
}

/** Botón Xbox (HOME): círculo verde con brillo. Un toque = pulso HOME. */
@Composable
private fun XboxButton(vm: GamepadViewModel, sizeDp: Int) {
    Box(
        modifier = Modifier.size(sizeDp.dp)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF43A047), Color(0xFF1B5E20))))
            .border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .clickable { vm.pulseButton(GamepadButtons.HOME) },
        contentAlignment = Alignment.Center,
    ) { Text("✕", color = Color.White, fontSize = (sizeDp / 2).sp, fontWeight = FontWeight.Bold) }
}

/** A/B/X/Y en rombo. */
@Composable
private fun AbxyPad(vm: GamepadViewModel) {
    Box(modifier = Modifier.size(190.dp)) {
        GamepadButton("Y", Color(0xFFFFC107), Modifier.align(Alignment.TopCenter)) { p -> vm.setButton(GamepadButtons.Y, p) }
        GamepadButton("X", Color(0xFF2196F3), Modifier.align(Alignment.CenterStart)) { p -> vm.setButton(GamepadButtons.X, p) }
        GamepadButton("B", Color(0xFFF44336), Modifier.align(Alignment.CenterEnd)) { p -> vm.setButton(GamepadButtons.B, p) }
        GamepadButton("A", Color(0xFF4CAF50), Modifier.align(Alignment.BottomCenter)) { p -> vm.setButton(GamepadButtons.A, p) }
    }
}

@Composable
fun GamepadButton(label: String, color: Color, modifier: Modifier = Modifier, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.size(58.dp)
            .graphicsLayer {
                scaleX = if (pressed) 0.92f else 1f
                scaleY = if (pressed) 0.92f else 1f
            }
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(if (pressed) color else color.copy(alpha = 0.88f))
            .border(2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
            .pointerPress { p -> pressed = p; onPress(p) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

/** Botón de hombro (LB/RB) o gatillo (LT/RT): píldora con estado pulsado. */
@Composable
private fun ShoulderButton(label: String, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(width = 56.dp, height = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) Color(0xFF7C4DFF) else Color(0xFF232839))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
            .pointerPress { p -> pressed = p; onPress(p) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SmallButton(label: String, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(width = 64.dp, height = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) Color(0xFF7C4DFF) else Color(0xFF232839))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
            .pointerPress { p -> pressed = p; onPress(p) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

/** Píldora de un toque (SHARE): pulso corto, no mantener. */
@Composable
private fun PulsePill(label: String, onTap: () -> Unit) {
    Box(
        modifier = Modifier.size(width = 64.dp, height = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF232839))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
            .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}
