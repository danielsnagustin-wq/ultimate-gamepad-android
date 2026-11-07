package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
    var showDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // La vista previa NO va dentro de ningún scroll: antes toda
    // la pantalla tenía scroll y al arrastrar un botón en vertical
    // el scroll se lo robaba y el botón no se movía (o se movía
    // a trompicones). Ahora la previa es fija y el ajuste de cada
    // botón se abre tocándolo, no en una barra de abajo.
    // En horizontal la pantalla mide ~360dp de alto: con la vista
    // previa fija en 300dp no quedaba espacio para nada más. La
    // altura de la vista previa ahora se adapta a la pantalla
    // (45% de la altura, entre 180 y 300dp) y dentro va una
    // miniatura a escala, así que nada se amontona.
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    draft = LayoutConfig()
                    vm.resetLayout()
                }) { Text("Restablecer", color = Color(0xFF9AA0B0)) }
                Button(onClick = onClose) { Text("Listo") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Toca un botón para ajustarlo; arrástralo con el dedo para moverlo a cualquier sitio del panel. Los cambios se guardan solos.",
            color = Color(0xFF9AA0B0),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        // --- Vista previa del mando (miniatura a escala, nada se amontona) ---
        // Antes los botones se dibujaban a tamaño real dentro de una
        // previa corta (180dp en horizontal) y se tapaban entre sí.
        // Ahora la previa es una miniatura a escala de un panel de
        // diseño de 800x300dp: todo cabe proporcional y el arrastre
        // sigue al dedo 1:1 (se divide por la escala de la miniatura).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF050505))
                .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Escala uniforme para que el diseño 800x300 quepa entero.
            val miniScale = minOf(maxWidth.value / 800f, maxHeight.value / 300f).coerceIn(0.2f, 1f)
            Box(
                modifier = Modifier
                    .size(800.dp, 300.dp)
                    .graphicsLayer {
                        scaleX = miniScale
                        scaleY = miniScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
            ) {
                // En el espacio de diseño la previa mide 800x300dp.
                val wPx = with(density) { 800.dp.toPx() }
                val hPx = with(density) { 300.dp.toPx() }
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
                            .pointerInput(id) {
                                detectTapGestures(onTap = { selected = id; showDialog = true })
                            }
                            .pointerInput(id) {
                                detectDragGestures(
                                    onDragEnd = { vm.updateElement(id, draftState.value.element(id)) },
                                    onDragCancel = { vm.updateElement(id, draftState.value.element(id)) },
                                ) { change, dragAmount ->
                                    change.consume()
                                    val current = draftState.value.element(id)
                                    // El arrastre ya llega en píxeles locales
                                    // del espacio de diseño: Compose invierte
                                    // la escala de la miniatura al entregar
                                    // el gesto, así que NO se divide por
                                    // miniScale (hacerlo duplicaba el
                                    // movimiento y el botón volaba 1,7x).
                                    val dx = with(density) { dragAmount.x.toDp().value }.roundToInt()
                                    val dy = with(density) { dragAmount.y.toDp().value }.roundToInt()
                                    val next = clampEditorOffset(
                                        base,
                                        current.offsetXdp + dx,
                                        current.offsetYdp + dy,
                                    )
                                    draft = draftState.value.withElement(
                                        id,
                                        current.copy(offsetXdp = next.first, offsetYdp = next.second),
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        EditorItemShape(id, isSelected)
                    }
                }
            }
        }

        // --- Botones ocultos: tócalos para volver a mostrarlos ---
        // (El ajuste de cada botón ya no va aquí abajo: toca un
        // botón en el panel y se abre su propio ajuste.)
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

        // --- Ajuste del botón tocado (en el propio panel) ---
        // Tocar un botón del panel abre su ajuste aquí mismo:
        // tamaño, transparencia, mover y eliminar.
        val dialogId = selected
        if (showDialog && dialogId != null) {
            val el = draft.element(dialogId)
            Dialog(onDismissRequest = { showDialog = false }) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0B0B0F))
                        .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(20.dp),
                ) {
                    Text(LayoutConfig.NAMES[dialogId] ?: dialogId, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Arrastra el botón en el panel para moverlo a cualquier sitio, o usa los botones de abajo.",
                        color = Color(0xFF9AA0B0),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Tamaño: ${"%.2f".format(el.scale)}x", fontSize = 12.sp, color = Color(0xFF9AA0B0))
                    Slider(
                        value = el.scale,
                        onValueChange = { draft = draft.withElement(dialogId, el.copy(scale = it)) },
                        onValueChangeFinished = { vm.updateElement(dialogId, draft.element(dialogId)) },
                        valueRange = 0.7f..1.6f,
                    )
                    Text("Opacidad: ${(el.opacity * 100).roundToInt()}%", fontSize = 12.sp, color = Color(0xFF9AA0B0))
                    Slider(
                        value = el.opacity,
                        onValueChange = { draft = draft.withElement(dialogId, el.copy(opacity = it)) },
                        onValueChangeFinished = { vm.updateElement(dialogId, draft.element(dialogId)) },
                        valueRange = 0.3f..1f,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        EditorStepper("← →", el.offsetXdp) {
                            val base = EDITOR_BASE[dialogId] ?: EditorBase(0.5f, 0.5f, 100, 100)
                            val next = clampEditorOffset(base, it, el.offsetYdp)
                            val updated = el.copy(offsetXdp = next.first)
                            draft = draft.withElement(dialogId, updated)
                            vm.updateElement(dialogId, updated)
                        }
                        EditorStepper("↑ ↓", el.offsetYdp) {
                            val base = EDITOR_BASE[dialogId] ?: EditorBase(0.5f, 0.5f, 100, 100)
                            val next = clampEditorOffset(base, el.offsetXdp, it)
                            val updated = el.copy(offsetYdp = next.second)
                            draft = draft.withElement(dialogId, updated)
                            vm.updateElement(dialogId, updated)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Button(onClick = {
                            val next = el.copy(visible = false)
                            draft = draft.withElement(dialogId, next)
                            vm.updateElement(dialogId, next)
                            showDialog = false
                            selected = null
                        }) { Text("Eliminar") }
                        TextButton(onClick = { showDialog = false }) { Text("Listo") }
                    }
                }
            }
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
    // Paletas L4/R4 (Fase 4): a los lados de SHARE, como en el
    // mando (debajo de VIEW/MENU). Solo se añaden dos entradas:
    // ningún elemento existente se mueve ni se toca.
    LayoutConfig.L4 to EditorBase(0.36f, 0.60f, 60, 36),
    LayoutConfig.R4 to EditorBase(0.64f, 0.60f, 60, 36),
)

/**
 * Limita el desplazamiento de un botón para que su centro no salga
 * del panel de diseño (800x300dp): se puede mover a cualquier
 * sitio del panel sin perderlo fuera de la vista.
 */
private fun clampEditorOffset(base: EditorBase, offsetXdp: Int, offsetYdp: Int): Pair<Int, Int> {
    val minX = (-(base.fx * 800f) + 30).roundToInt()
    val maxX = ((1f - base.fx) * 800f - 30).roundToInt()
    val minY = (-(base.fy * 300f) + 20).roundToInt()
    val maxY = ((1f - base.fy) * 300f - 20).roundToInt()
    return offsetXdp.coerceIn(minX, maxX) to offsetYdp.coerceIn(minY, maxY)
}

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
        LayoutConfig.VIEW, LayoutConfig.MENU, LayoutConfig.SHARE,
        LayoutConfig.L4, LayoutConfig.R4 -> {
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
        Button(onClick = { onChange((value - 8).coerceIn(-700, 700)) }) { Text("−") }
        Text("${value}dp", fontSize = 12.sp)
        Button(onClick = { onChange((value + 8).coerceIn(-700, 700)) }) { Text("+") }
    }
}
