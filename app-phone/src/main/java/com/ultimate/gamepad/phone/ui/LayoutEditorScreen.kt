package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Panel visual de botones, como el editor de HUD de los juegos:
 * se abre todo el mando en miniatura y cada botón se puede
 * arrastrar con el dedo para moverlo, hacerlo más grande o más
 * pequeño, más o menos transparente, o quitarlo. Los cambios se
 * guardan solos (LayoutPrefs) y se ven al instante en el mando.
 * Se abre desde Ajustes > Personalizar botones.
 */
@Composable
fun LayoutEditorScreen(vm: GamepadViewModel, onClose: () -> Unit) {
    val layout by vm.layoutConfig.collectAsState()
    var draft by remember { mutableStateOf(layout) }
    val draftState = rememberUpdatedState(draft)
    var selected by remember { mutableStateOf<String?>(LayoutConfig.LEFT_STICK) }
    val density = LocalDensity.current

    // La vista previa NO va dentro del scroll: antes toda la
    // pantalla tenía scroll y al arrastrar un botón en vertical
    // el scroll se lo robaba y el botón no se movía (o se movía
    // a trompicones). Ahora la previa es fija y solo la barra de
    // herramientas de abajo tiene scroll.
    // En horizontal la pantalla mide ~360dp de alto: con la vista
    // previa fija en 300dp la barra de herramientas (tamaño y
    // transparencia) quedaba sin espacio. La altura de la vista
    // previa ahora se adapta a la pantalla (45% de la altura,
    // entre 180 y 300dp).
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
    ) {
        val previewHeight = (maxHeight * 0.45f).coerceIn(180.dp, 300.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("🎮  Panel de botones", fontSize = 20.sp)
            Button(onClick = onClose) { Text("Listo") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Toca un botón para elegirlo y arrástralo con el dedo para moverlo. Abajo lo haces más grande, más transparente o lo quitas. Los cambios se guardan solos.",
            color = Color(0xFF9AA0B0),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        // --- Vista previa del mando (arrastrable, fija) ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF050505))
                .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
        ) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            for ((id, base) in EDITOR_BASE) {
                val el = draft.element(id)
                if (!el.visible) continue
                val baseW = base.w
                val baseH = base.h
                val itemWPx = with(density) { baseW.dp.toPx() }
                val itemHPx = with(density) { baseH.dp.toPx() }
                val cx = base.fx * wPx - itemWPx / 2f + with(density) { el.offsetXdp.dp.toPx() }
                val cy = base.fy * hPx - itemHPx / 2f + with(density) { el.offsetYdp.dp.toPx() }
                val isSelected = selected == id
                Box(
                    modifier = Modifier
                        .offset { IntOffset(cx.roundToInt(), cy.roundToInt()) }
                        .size(baseW.dp, baseH.dp)
                        .graphicsLayer { scaleX = el.scale; scaleY = el.scale; alpha = el.opacity }
                        .pointerInput(id) { detectTapGestures(onTap = { selected = id }) }
                        .pointerInput(id) {
                            detectDragGestures(
                                onDragEnd = { vm.updateElement(id, draftState.value.element(id)) },
                                onDragCancel = { vm.updateElement(id, draftState.value.element(id)) },
                            ) { change, dragAmount ->
                                change.consume()
                                val current = draftState.value.element(id)
                                val dx = with(density) { dragAmount.x.toDp().value }.roundToInt()
                                val dy = with(density) { dragAmount.y.toDp().value }.roundToInt()
                                draft = draftState.value.withElement(
                                    id,
                                    current.copy(
                                        offsetXdp = (current.offsetXdp + dx).coerceIn(-120, 120),
                                        offsetYdp = (current.offsetYdp + dy).coerceIn(-120, 120),
                                    ),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    EditorItemShape(id, isSelected)
                }
            }
        }

        // --- Barra de herramientas (con scroll, la previa es fija) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Botones ocultos: volver a mostrarlos ---
            val hidden = LayoutConfig.NAMES.keys.filter { !draft.element(it).visible }
            if (hidden.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Ocultos (tócalos para volver a mostrarlos):", color = Color(0xFF9AA0B0), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in hidden) {
                        Button(onClick = {
                            val next = draft.element(id).copy(visible = true)
                            draft = draft.withElement(id, next)
                            vm.updateElement(id, next)
                            selected = id
                        }) { Text(LayoutConfig.NAMES[id] ?: id, fontSize = 12.sp) }
                    }
                }
            }

            // --- Ajustes del botón elegido ---
            val selId = selected
            if (selId != null) {
                val el = draft.element(selId)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(LayoutConfig.NAMES[selId] ?: selId, fontSize = 15.sp)
                    Switch(
                        checked = el.visible,
                        onCheckedChange = {
                            val next = el.copy(visible = it)
                            draft = draft.withElement(selId, next)
                            vm.updateElement(selId, next)
                        },
                    )
                }
                if (el.visible) {
                    Text("Tamaño: ${"%.2f".format(el.scale)}x", fontSize = 12.sp, color = Color(0xFF9AA0B0))
                    Slider(
                        value = el.scale,
                        onValueChange = { draft = draft.withElement(selId, el.copy(scale = it)) },
                        onValueChangeFinished = { vm.updateElement(selId, draft.element(selId)) },
                        valueRange = 0.7f..1.6f,
                    )
                    Text("Opacidad: ${(el.opacity * 100).roundToInt()}%", fontSize = 12.sp, color = Color(0xFF9AA0B0))
                    Slider(
                        value = el.opacity,
                        onValueChange = { draft = draft.withElement(selId, el.copy(opacity = it)) },
                        onValueChangeFinished = { vm.updateElement(selId, draft.element(selId)) },
                        valueRange = 0.3f..1f,
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        EditorStepper("← →", el.offsetXdp) {
                            val next = el.copy(offsetXdp = it)
                            draft = draft.withElement(selId, next)
                            vm.updateElement(selId, next)
                        }
                        EditorStepper("↑ ↓", el.offsetYdp) {
                            val next = el.copy(offsetYdp = it)
                            draft = draft.withElement(selId, next)
                            vm.updateElement(selId, next)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                draft = LayoutConfig()
                vm.resetLayout()
            }) { Text("Restablecer diseño") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClose) { Text("Volver al mando") }
        }
        }
    }
}

/** Posición base (fracción del panel) y tamaño base de cada elemento en el panel. */
private data class EditorBase(val fx: Float, val fy: Float, val w: Int, val h: Int)

private val EDITOR_BASE: Map<String, EditorBase> = mapOf(
    LayoutConfig.SHOULDERS_LEFT to EditorBase(0.18f, 0.10f, 100, 28),
    LayoutConfig.SHOULDERS_RIGHT to EditorBase(0.82f, 0.10f, 100, 28),
    LayoutConfig.LEFT_STICK to EditorBase(0.18f, 0.34f, 110, 110),
    LayoutConfig.DPAD to EditorBase(0.18f, 0.74f, 110, 110),
    LayoutConfig.ABXY to EditorBase(0.82f, 0.34f, 120, 120),
    LayoutConfig.RIGHT_STICK to EditorBase(0.82f, 0.74f, 110, 110),
    LayoutConfig.VIEW to EditorBase(0.36f, 0.42f, 60, 36),
    LayoutConfig.XBOX to EditorBase(0.50f, 0.42f, 56, 56),
    LayoutConfig.MENU to EditorBase(0.64f, 0.42f, 60, 36),
    LayoutConfig.SHARE to EditorBase(0.50f, 0.60f, 60, 36),
)

/** Miniatura del botón dentro del panel (forma parecida a la real, sin enviar nada). */
@Composable
private fun EditorItemShape(id: String, isSelected: Boolean) {
    val borderColor = if (isSelected) Color(0xFF9BF00B) else Color(0xFF555A6A)
    val borderWidth = if (isSelected) 2.5.dp else 1.dp
    when (id) {
        LayoutConfig.LEFT_STICK, LayoutConfig.RIGHT_STICK -> {
            Box(
                Modifier.size(72.dp).clip(CircleShape)
                    .background(Color.Black)
                    .border(borderWidth, borderColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF7C4DFF)))
                Text(if (id == LayoutConfig.LEFT_STICK) "L3" else "R3", color = Color.White, fontSize = 10.sp)
            }
        }
        LayoutConfig.DPAD -> {
            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(Color.Black)
                    .border(borderWidth, borderColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(48.dp)) {
                    val c = center
                    drawLine(Color.White, Offset(c.x, c.y - 20.dp.toPx()), Offset(c.x, c.y + 20.dp.toPx()), strokeWidth = 4.dp.toPx())
                    drawLine(Color.White, Offset(c.x - 20.dp.toPx(), c.y), Offset(c.x + 20.dp.toPx(), c.y), strokeWidth = 4.dp.toPx())
                }
            }
        }
        LayoutConfig.ABXY -> {
            Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).align(Alignment.TopCenter).clip(CircleShape).background(Color(0xFFFFB900)))
                Box(Modifier.size(22.dp).align(Alignment.CenterStart).clip(CircleShape).background(Color(0xFF0078D7)))
                Box(Modifier.size(22.dp).align(Alignment.CenterEnd).clip(CircleShape).background(Color(0xFFE81123)))
                Box(Modifier.size(22.dp).align(Alignment.BottomCenter).clip(CircleShape).background(Color(0xFF107C10)))
            }
        }
        LayoutConfig.XBOX -> {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(Color.Black)
                    .border(borderWidth, Color(0xFF107C10), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 22.sp) }
        }
        LayoutConfig.VIEW, LayoutConfig.MENU, LayoutConfig.SHARE -> {
            Box(
                Modifier.size(60.dp, 36.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    .border(borderWidth, borderColor, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(LayoutConfig.NAMES[id] ?: id, color = Color.White, fontSize = 10.sp) }
        }
        LayoutConfig.SHOULDERS_LEFT, LayoutConfig.SHOULDERS_RIGHT -> {
            Box(
                Modifier.size(100.dp, 28.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(borderWidth, borderColor, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(if (id == LayoutConfig.SHOULDERS_LEFT) "LT / LB" else "RB / RT", color = Color.White, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun EditorStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF9AA0B0))
        Button(onClick = { onChange((value - 8).coerceIn(-120, 120)) }) { Text("−") }
        Text("${value}dp", fontSize = 12.sp)
        Button(onClick = { onChange((value + 8).coerceIn(-120, 120)) }) { Text("+") }
    }
}
