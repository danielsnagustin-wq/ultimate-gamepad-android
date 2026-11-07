package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportStatus
import kotlinx.coroutines.delay

/**
 * Etapa 1: mando básico FUNCIONAL.
 * Joystick izquierdo + cruceta + A/B/X/Y + Start/Select.
 * Joystick derecho, gatillos LT/RT analógicos, LB/RB y L3/R3
 * llegan en Etapa 2; no se dibujan como botones falsos.
 *
 * Orientación: horizontal Y vertical (el manifest usa fullSensor).
 * En horizontal, el mando clásico de tres columnas; en vertical,
 * stick + cruceta a la izquierda, A/B/X/Y a la derecha y
 * SELECT/START abajo. Todo cabe en el A17 vertical (~412x914 dp).
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E14)).padding(12.dp)) {
        val isLandscape = maxWidth > maxHeight
        Column(modifier = Modifier.fillMaxSize()) {
            // Barra de estado real, no decorativa.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (ui.status) {
                        ConnectionStatus.DISCONNECTED -> "● Desconectado"
                        ConnectionStatus.CONNECTING -> "● Conectando…"
                        ConnectionStatus.CONNECTED -> "● Conectado a ${ui.host}"
                        ConnectionStatus.ERROR -> "● Error: ${ui.error ?: ""}"
                    },
                    color = if (ui.status == ConnectionStatus.CONNECTED) Color(0xFF4CAF50) else Color(0xFFE8EAF0),
                    fontSize = 14.sp,
                )
                Text(
                    text = "Ping: ${ui.pingMs?.let { "$it ms" } ?: "—"}",
                    color = Color(0xFF9AA0B0),
                    fontSize = 14.sp,
                )
                if (ui.status == ConnectionStatus.CONNECTED) {
                    Button(onClick = { vm.disconnect() }) { Text("Desconectar") }
                } else {
                    Button(onClick = { vm.openConnect() }) { Text("Conectar") }
                }
            }
            if (ui.error != null) Text(ui.error!!, color = Color(0xFFFF5252), fontSize = 13.sp)
            if (ui.status == ConnectionStatus.CONNECTED && !ui.tvResponding) {
                Text("TV sin respuesta (sin eco de ping en 3 s). En modo Automático se probará el siguiente método.", color = Color(0xFFFFC107), fontSize = 12.sp)
            }
            // Selector multi-transporte con estado REAL por método.
            TransportSelectorRow(vm, ui)

            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LeftControls(vm)
                    // Centro: Start / Select.
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SmallButton("SELECT") { pressed -> vm.setButton(GamepadButtons.SELECT, pressed) }
                        SmallButton("START") { pressed -> vm.setButton(GamepadButtons.START, pressed) }
                        Text("Etapa 1: stick derecho y gatillos en Etapa 2", color = Color(0xFF666C7A), fontSize = 11.sp)
                    }
                    AbxyPad(vm)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LeftControls(vm)
                    AbxyPad(vm)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallButton("SELECT") { pressed -> vm.setButton(GamepadButtons.SELECT, pressed) }
                    Spacer(Modifier.width(16.dp))
                    SmallButton("START") { pressed -> vm.setButton(GamepadButtons.START, pressed) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Etapa 1: stick derecho y gatillos en Etapa 2", color = Color(0xFF666C7A), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        if (ui.showConnectSheet) ConnectSheet(vm, ui)
        if (ui.showQrScanner) QrScannerScreen(onResult = { vm.connectFromQr(it) }, onClose = { vm.closeConnect() })
    }
}

/** Izquierda: joystick + cruceta. */
@Composable
private fun LeftControls(vm: GamepadViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JoystickView(onMove = { x, y -> vm.setLeftStick(x, y) }, onRelease = { vm.setLeftStick(0f, 0f) })
        DpadView(onChange = { up, down, left, right -> vm.setDpad(up, down, left, right) })
    }
}

/** Derecha: A/B/X/Y. */
@Composable
private fun AbxyPad(vm: GamepadViewModel) {
    Box(modifier = Modifier.size(180.dp)) {
        GamepadButton("Y", Color(0xFFFFC107), Modifier.align(Alignment.TopCenter)) { p -> vm.setButton(GamepadButtons.Y, p) }
        GamepadButton("X", Color(0xFF2196F3), Modifier.align(Alignment.CenterStart)) { p -> vm.setButton(GamepadButtons.X, p) }
        GamepadButton("B", Color(0xFFF44336), Modifier.align(Alignment.CenterEnd)) { p -> vm.setButton(GamepadButtons.B, p) }
        GamepadButton("A", Color(0xFF4CAF50), Modifier.align(Alignment.BottomCenter)) { p -> vm.setButton(GamepadButtons.A, p) }
    }
}

@Composable
fun GamepadButton(label: String, color: Color, modifier: Modifier = Modifier, onPress: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.size(56.dp).clip(CircleShape).background(color.copy(alpha = 0.85f))
            .border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .pointerPress(onPress),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 20.sp) }
}

@Composable
fun SmallButton(label: String, onPress: (Boolean) -> Unit) {
    Button(
        onClick = {},
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3040)),
        modifier = Modifier.pointerPress(onPress),
    ) { Text(label, fontSize = 12.sp) }
}
