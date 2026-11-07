package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimate.gamepad.core.GamepadButtons
import kotlinx.coroutines.delay

/**
 * Mando estilo Xbox, neón (según los mockups del usuario).
 * Toda la pantalla es el mando: dos sticks, cruceta, A/B/X/Y,
 * LB/RB, LT/RT analógicos, L3/R3 (tocando el stick), VIEW, MENU,
 * botón Xbox (HOME) y Compartir. Nada es decorativo: cada botón
 * envía su bit real por el protocolo de 32 bytes.
 *
 * Lo de arriba (estado, métodos, conexión) no estorba: vive en
 * Ajustes (⚙, arriba a la derecha) y en la píldora de estado del
 * centro, que también abre Ajustes al tocarla.
 *
 * Personalización: cada elemento se puede ocultar, agrandar,
 * achicar o mover desde Ajustes > Personalizar botones
 * (LayoutConfig); el ⚙ siempre queda visible para poder volver.
 */
@Composable
fun GamepadScreen(vm: GamepadViewModel) {
    val ui by vm.ui.collectAsState()
    val layout by vm.layoutConfig.collectAsState()
    LaunchedEffect(ui.status) {
        vm.refreshHidStatus()
        vm.refreshWifiState()
        // El ping se vigila también en CONECTANDO: si no, el estado
        // CONECTANDO nunca se resolvería y la pantalla se quedaría
        // tiesa esperando un eco que nadie comprueba.
        while (ui.status == ConnectionStatus.CONNECTED || ui.status == ConnectionStatus.CONNECTING) { vm.refreshPing(); delay(1000) }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF141126), Color(0xFF0B0E14))))
            .border(
                1.5.dp,
                Brush.linearGradient(listOf(Color(0xFF00E5FF).copy(alpha = 0.35f), Color(0xFF7C4DFF).copy(alpha = 0.35f), Color(0xFF00E5FF).copy(alpha = 0.35f))),
                RoundedCornerShape(24.dp),
            )
            .padding(12.dp),
    ) {
        val isLandscape = maxWidth > maxHeight
        NeonBackground(Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            ShoulderRow(vm, ui, layout)
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
                    LeftControls(vm, layout)
                    CenterControls(vm, layout, horizontal = false)
                    RightControls(vm, layout)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LayoutElement(layout, LayoutConfig.LEFT_STICK) { StickWithLabel(vm, left = true) }
                    LayoutElement(layout, LayoutConfig.ABXY) { AbxyPad(vm) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LayoutElement(layout, LayoutConfig.DPAD) { DpadView(onChange = { up, down, left, right -> vm.setDpad(up, down, left, right) }) }
                    LayoutElement(layout, LayoutConfig.RIGHT_STICK) { StickWithLabel(vm, left = false) }
                }
                Spacer(Modifier.height(12.dp))
                CenterControls(vm, layout, horizontal = true)
            }
        }
        if (ui.showSettings) SettingsScreen(vm, ui)
        if (ui.showQrScanner) QrScannerScreen(onResult = { vm.connectFromQr(it) }, onClose = { vm.closeSettings() })
    }
}

/** Fondo neón: curvas luminosas + brillos suaves, como los mockups. */
@Composable
private fun NeonBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF7C4DFF).copy(alpha = 0.16f), Color.Transparent),
                center = Offset(w * 0.15f, h * 0.2f),
                radius = w * 0.35f,
            ),
            radius = w * 0.35f,
            center = Offset(w * 0.15f, h * 0.2f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.10f), Color.Transparent),
                center = Offset(w * 0.85f, h * 0.85f),
                radius = w * 0.35f,
            ),
            radius = w * 0.35f,
            center = Offset(w * 0.85f, h * 0.85f),
        )
        val curve1 = Path().apply {
            moveTo(0f, h * 0.78f)
            quadraticBezierTo(w * 0.5f, h * 0.35f, w, h * 0.62f)
        }
        drawPath(curve1, Color(0xFF7C4DFF).copy(alpha = 0.20f), style = Stroke(width = 2.dp.toPx()))
        val curve2 = Path().apply {
            moveTo(0f, h * 0.22f)
            quadraticBezierTo(w * 0.5f, h * 0.62f, w, h * 0.18f)
        }
        drawPath(curve2, Color(0xFF00E5FF).copy(alpha = 0.12f), style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** Un elemento personalizable: oculto no se dibuja; si no, se
 * mueve y se escala según el diseño guardado en Ajustes. */
@Composable
private fun LayoutElement(layout: LayoutConfig, id: String, content: @Composable () -> Unit) {
    val el = layout.element(id)
    if (!el.visible) return
    Box(
        modifier = Modifier
            .offset(el.offsetXdp.dp, el.offsetYdp.dp)
            .graphicsLayer { scaleX = el.scale; scaleY = el.scale },
    ) { content() }
}

/** Hombros: LT/LB a la izquierda, RB/RT a la derecha, estado y ⚙ al centro/derecha. */
@Composable
private fun ShoulderRow(vm: GamepadViewModel, ui: GamepadUiState, layout: LayoutConfig) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayoutElement(layout, LayoutConfig.SHOULDERS_LEFT) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoulderButton("LT") { pressed -> vm.setLeftTrigger(if (pressed) 1f else 0f) }
                ShoulderButton("LB") { pressed -> vm.setButton(GamepadButtons.LB, pressed) }
            }
        }
        StatusPill(vm, ui)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LayoutElement(layout, LayoutConfig.SHOULDERS_RIGHT) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShoulderButton("RB") { pressed -> vm.setButton(GamepadButtons.RB, pressed) }
                    ShoulderButton("RT") { pressed -> vm.setRightTrigger(if (pressed) 1f else 0f) }
                }
            }
            GearButton { vm.openSettings() }
        }
    }
}

/** Píldora de estado con borde neón (tocarla abre Ajustes). */
@Composable
private fun StatusPill(vm: GamepadViewModel, ui: GamepadUiState) {
    val (dot, text) = when (ui.status) {
        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50) to "Conectado${ui.pingMs?.let { " · $it ms" } ?: ""}"
        ConnectionStatus.CONNECTING -> Color(0xFFFFC107) to "Conectando por ${ui.attemptMethod.label}…"
        ConnectionStatus.ERROR -> Color(0xFFFF5252) to "Error"
        else -> Color(0xFF9AA0B0) to "Desconectado"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF151A26).copy(alpha = 0.92f))
            .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .clickable { vm.openSettings() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Botón redondo ⚙ que abre Ajustes y conexión. Siempre visible. */
@Composable
private fun GearButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(40.dp)
            .pressEffect(pressed)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A3040), Color(0xFF171B26))))
            .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.6f), CircleShape)
            .pointerPress { p -> pressed = p; if (p) onClick() },
        contentAlignment = Alignment.Center,
    ) { Text("⚙", color = Color.White, fontSize = 20.sp) }
}

/** Izquierda: stick + cruceta. */
@Composable
private fun LeftControls(vm: GamepadViewModel, layout: LayoutConfig) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LayoutElement(layout, LayoutConfig.LEFT_STICK) { StickWithLabel(vm, left = true) }
        LayoutElement(layout, LayoutConfig.DPAD) { DpadView(onChange = { up, down, left, right -> vm.setDpad(up, down, left, right) }) }
    }
}

/** Derecha: A/B/X/Y + stick derecho. */
@Composable
private fun RightControls(vm: GamepadViewModel, layout: LayoutConfig) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LayoutElement(layout, LayoutConfig.ABXY) { AbxyPad(vm) }
        LayoutElement(layout, LayoutConfig.RIGHT_STICK) { StickWithLabel(vm, left = false) }
    }
}

/** Stick con su etiqueta L3/R3 (tocar el stick = pulsar L3/R3, como en un mando real). */
@Composable
private fun StickWithLabel(vm: GamepadViewModel, left: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (left) {
            JoystickView(
                onMove = { x, y -> vm.setLeftStick(x, y) },
                onRelease = { vm.setLeftStick(0f, 0f) },
                onClick = { vm.pulseButton(GamepadButtons.L3) },
            )
            StickLabel("L3")
        } else {
            JoystickView(
                onMove = { x, y -> vm.setRightStick(x, y) },
                onRelease = { vm.setRightStick(0f, 0f) },
                onClick = { vm.pulseButton(GamepadButtons.R3) },
            )
            StickLabel("R3")
        }
    }
}

/** Etiqueta pequeña con píldora (como en los mockups). */
@Composable
private fun StickLabel(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF232839))
                .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Text("Toca el stick", color = Color(0xFF9AA0B0), fontSize = 11.sp)
    }
}

/** Centro: botón Xbox, VIEW, Compartir y MENU. */
@Composable
private fun CenterControls(vm: GamepadViewModel, layout: LayoutConfig, horizontal: Boolean) {
    if (horizontal) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.weight(1f))
            LayoutElement(layout, LayoutConfig.XBOX) { XboxButton(vm, sizeDp = 56) }
            LayoutElement(layout, LayoutConfig.VIEW) { SmallButton("VIEW", { ViewIcon() }) { pressed -> vm.setButton(GamepadButtons.SELECT, pressed) } }
            LayoutElement(layout, LayoutConfig.SHARE) { PulsePill("SHARE", { ShareIcon() }) { vm.pulseButton(GamepadButtons.CAPTURE) } }
            LayoutElement(layout, LayoutConfig.MENU) { SmallButton("MENU", { MenuIcon() }) { pressed -> vm.setButton(GamepadButtons.START, pressed) } }
            Spacer(Modifier.weight(1f))
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LayoutElement(layout, LayoutConfig.XBOX) { XboxButton(vm, sizeDp = 72) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutElement(layout, LayoutConfig.VIEW) { SmallButton("VIEW", { ViewIcon() }) { pressed -> vm.setButton(GamepadButtons.SELECT, pressed) } }
                LayoutElement(layout, LayoutConfig.SHARE) { PulsePill("SHARE", { ShareIcon() }) { vm.pulseButton(GamepadButtons.CAPTURE) } }
                LayoutElement(layout, LayoutConfig.MENU) { SmallButton("MENU", { MenuIcon() }) { pressed -> vm.setButton(GamepadButtons.START, pressed) } }
            }
        }
    }
}

/** Botón Xbox (HOME): oscuro brillante con anillo neón cian-magenta. Un toque = pulso HOME. */
@Composable
private fun XboxButton(vm: GamepadViewModel, sizeDp: Int) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(sizeDp.dp)
            .pressEffect(pressed)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A3040), Color(0xFF141824))))
            .border(2.5.dp, Brush.sweepGradient(listOf(Color(0xFF00E5FF), Color(0xFFE040FB), Color(0xFF00E5FF))), CircleShape)
            .pointerPress { p -> pressed = p; if (p) vm.pulseButton(GamepadButtons.HOME) },
        contentAlignment = Alignment.Center,
    ) { Text("✕", color = Color.White, fontSize = (sizeDp / 2).sp, fontWeight = FontWeight.Bold) }
}

/** A/B/X/Y en rombo, oscuros con anillo neón de su color + halo. */
@Composable
private fun AbxyPad(vm: GamepadViewModel) {
    Box(modifier = Modifier.size(200.dp)) {
        GamepadButton("Y", Color(0xFFFFC107), Modifier.align(Alignment.TopCenter)) { p -> vm.setButton(GamepadButtons.Y, p) }
        GamepadButton("X", Color(0xFF2196F3), Modifier.align(Alignment.CenterStart)) { p -> vm.setButton(GamepadButtons.X, p) }
        GamepadButton("B", Color(0xFFF44336), Modifier.align(Alignment.CenterEnd)) { p -> vm.setButton(GamepadButtons.B, p) }
        GamepadButton("A", Color(0xFF4CAF50), Modifier.align(Alignment.BottomCenter)) { p -> vm.setButton(GamepadButtons.A, p) }
    }
}

@Composable
fun GamepadButton(label: String, color: Color, modifier: Modifier = Modifier, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(modifier = modifier.size(78.dp), contentAlignment = Alignment.Center) {
        // Halo neón del color del botón.
        Box(
            Modifier.size(78.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent))),
        )
        Box(
            modifier = Modifier.size(62.dp)
                .pressEffect(pressed)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    if (pressed) Brush.linearGradient(listOf(color, color))
                    else Brush.verticalGradient(listOf(Color(0xFF2E3444), Color(0xFF141824))),
                )
                .border(2.dp, color, CircleShape)
                .pointerPress { p -> pressed = p; onPress(p) },
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Botón de hombro (LB/RB) o gatillo (LT/RT): píldora brillante con borde neón. */
@Composable
private fun ShoulderButton(label: String, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(width = 58.dp, height = 38.dp)
            .pressEffect(pressed)
            .clip(RoundedCornerShape(19.dp))
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFF7C4DFF), Color(0xFF5E35B1))
                    else listOf(Color(0xFF2A3040), Color(0xFF171B26)),
                ),
            )
            .border(1.dp, Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF))), RoundedCornerShape(19.dp))
            .pointerPress { p -> pressed = p; onPress(p) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

/** Píldora con icono arriba y texto abajo (VIEW / MENU). */
@Composable
fun SmallButton(label: String, icon: @Composable () -> Unit, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(width = 76.dp, height = 48.dp)
            .pressEffect(pressed)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFF7C4DFF), Color(0xFF5E35B1))
                    else listOf(Color(0xFF232839), Color(0xFF171B26)),
                ),
            )
            .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .pointerPress { p -> pressed = p; onPress(p) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            icon()
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Píldora de un toque con icono (SHARE): pulso corto, no mantener. */
@Composable
private fun PulsePill(label: String, icon: @Composable () -> Unit, onTap: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(width = 76.dp, height = 48.dp)
            .pressEffect(pressed)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF232839), Color(0xFF171B26))))
            .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .pointerPress { p -> pressed = p; if (p) onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            icon()
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Icono VIEW: dos ventanas solapadas. */
@Composable
private fun ViewIcon() {
    Box(Modifier.size(16.dp, 14.dp)) {
        Box(
            Modifier.size(11.dp, 9.dp).align(Alignment.TopStart)
                .border(1.5.dp, Color.White, RoundedCornerShape(2.dp)),
        )
        Box(
            Modifier.size(11.dp, 9.dp).align(Alignment.BottomEnd)
                .background(Color(0xFF232839), RoundedCornerShape(2.dp))
                .border(1.5.dp, Color.White, RoundedCornerShape(2.dp)),
        )
    }
}

/** Icono SHARE: flecha saliendo de una bandeja. */
@Composable
private fun ShareIcon() {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        drawLine(Color.White, Offset(w / 2, h - 2.dp.toPx()), Offset(w / 2, 2.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w / 2, 2.dp.toPx()), Offset(w / 2 - 4.dp.toPx(), 6.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w / 2, 2.dp.toPx()), Offset(w / 2 + 4.dp.toPx(), 6.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(2.dp.toPx(), h * 0.55f), Offset(2.dp.toPx(), h - 2.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(2.dp.toPx(), h - 2.dp.toPx()), Offset(w - 2.dp.toPx(), h - 2.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w - 2.dp.toPx(), h * 0.55f), Offset(w - 2.dp.toPx(), h - 2.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Icono MENU: tres líneas. */
@Composable
private fun MenuIcon() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) {
            Box(Modifier.size(14.dp, 2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White))
        }
    }
}
