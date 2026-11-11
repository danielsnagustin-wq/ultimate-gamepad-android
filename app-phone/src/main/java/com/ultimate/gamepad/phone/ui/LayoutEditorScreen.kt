package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ultimate.gamepad.core.LayoutGeometry
import kotlin.math.roundToInt

/**
 * Panel visual de botones, como el editor de HUD de los juegos:
 * se abre el mando A TAMAÑO REAL (el mismo cuerpo que la pantalla
 * real, GamepadBody, con la misma matemática de LayoutGeometry) y
 * cada botón se puede arrastrar con el dedo para moverlo, hacerlo
 * más grande o más pequeño, más o menos transparente, o quitarlo.
 * Lo que se ve aquí es lo que se juega: mismos tamaños, mismas
 * posiciones, sin recortes y sin miniatura. Los cambios se guardan
 * solos (LayoutPrefs). Se abre desde Ajustes > Personalizar botones.
 */
@Composable
fun LayoutEditorScreen(vm: GamepadViewModel, onClose: () -> Unit) {
    val layout by vm.layoutConfig.collectAsState()
    val ui by vm.ui.collectAsState()
    var draft by remember { mutableStateOf(layout) }
    val draftState = rememberUpdatedState(draft)
    var selected by remember { mutableStateOf<String?>(LayoutConfig.LEFT_STICK) }
    var showDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Tamaño real de la vista previa en dp (tras el padding de
    // 12dp): lo necesita el recorte de los arrastres (clampToPreview).
    var previewArea by remember { mutableStateOf(Size.Zero) }

    // La vista previa NO va dentro de ningún scroll: antes toda
    // la pantalla tenía scroll y al arrastrar un botón en vertical
    // el scroll se lo robaba y el botón no se movía (o se movía
    // a trompicones). Ahora la previa es fija y el ajuste de cada
    // botón se abre tocándolo, no en una barra de abajo.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
    ) {
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
            "Toca un botón para ajustarlo; arrástralo con el dedo para moverlo. Lo que ves es el mando tal como se juega: mismos tamaños y posiciones. Los cambios se guardan solos.",
            color = Color(0xFF9AA0B0),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        // --- Vista previa REAL del mando (sin miniatura) ---
        // La previa ES el mando: el mismo cuerpo (GamepadBody) que
        // la pantalla real, con la misma matemática (LayoutGeometry)
        // y los mismos botones. Lo que se ve aquí es lo que se
        // juega: sin recortes, con las letras de A/B/X/Y y la
        // cruceta completa. Tocar un botón abre su ajuste;
        // arrastrarlo lo mueve (y al soltar se guarda).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF050505))
                .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            GamepadBody(
                vm = vm,
                ui = ui,
                layout = draft,
                interaction = GamepadInteraction.Edit(
                    onTapElement = { id -> selected = id; showDialog = true },
                    onDragElement = { id, dx, dy ->
                        val current = draftState.value.element(id)
                        val next = clampToPreview(
                            draftState.value,
                            previewArea.width, previewArea.height,
                            id, current.offsetXdp + dx, current.offsetYdp + dy,
                        )
                        draft = draftState.value.withElement(
                            id,
                            current.copy(offsetXdp = next.first, offsetYdp = next.second),
                        )
                    },
                    onDragEnd = { id -> vm.updateElement(id, draftState.value.element(id)) },
                    selectedId = selected,
                ),
                modifier = Modifier.fillMaxSize().onSizeChanged {
                    previewArea = with(density) { Size(it.width.toDp().value, it.height.toDp().value) }
                },
            )
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
                    Text("Tamaño: ${"%.2f".format(java.util.Locale.US, el.scale)}x", fontSize = 12.sp, color = Color(0xFF9AA0B0))
                    Slider(
                        value = el.scale,
                        onValueChange = { draft = draft.withElement(dialogId, el.copy(scale = it)) },
                        onValueChangeFinished = { vm.updateElement(dialogId, draft.element(dialogId)) },
                        valueRange = 0.6f..1.6f,
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
                            val next = clampToPreview(draft, previewArea.width, previewArea.height, dialogId, it, el.offsetYdp)
                            val updated = el.copy(offsetXdp = next.first)
                            draft = draft.withElement(dialogId, updated)
                            vm.updateElement(dialogId, updated)
                        }
                        EditorStepper("↑ ↓", el.offsetYdp) {
                            val next = clampToPreview(draft, previewArea.width, previewArea.height, dialogId, el.offsetXdp, it)
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

/** Rectángulo de un elemento por su id de LayoutConfig (null si
 * está oculto o el id no existe). */
private fun LayoutGeometry.ElementRects.forId(id: String): LayoutGeometry.ElementRect? = when (id) {
    LayoutConfig.SHOULDERS_LEFT -> shouldersLeft
    LayoutConfig.SHOULDERS_RIGHT -> shouldersRight
    LayoutConfig.LEFT_STICK -> leftStick
    LayoutConfig.DPAD -> dpad
    LayoutConfig.ABXY -> abxy
    LayoutConfig.RIGHT_STICK -> rightStick
    LayoutConfig.VIEW -> view
    LayoutConfig.XBOX -> xbox
    LayoutConfig.MENU -> menu
    LayoutConfig.SHARE -> share
    LayoutConfig.L4 -> l4
    LayoutConfig.R4 -> r4
    else -> null
}

/**
 * Limita el desplazamiento de un botón para que su CENTRO no salga
 * de la vista previa real (margen 8 dp): se puede mover a cualquier
 * sitio del mando sin perderlo fuera de la vista. La rectificación
 * se calcula con la misma matemática que la pantalla real
 * (LayoutGeometry.elementRects): el centro del elemento en la
 * previa es centro + desplazamiento × ajuste, así que el
 * desplazamiento que lo devuelve dentro es (límite − centro) /
 * ajuste. Los hombros no pasan por el ajuste global (ajuste 1.0).
 * Dos pasadas bastan: la segunda corrige el redondeo y el
 * recentrado de la columna al cambiar el vano visible (al mover un
 * elemento en Y, la columna se recentra y el centro se mueve solo
 * la mitad del arrastre: es el comportamiento real del mando, no
 * un error del editor).
 */
private fun clampToPreview(
    layout: LayoutConfig,
    areaW: Float,
    areaH: Float,
    id: String,
    offsetXdp: Int,
    offsetYdp: Int,
): Pair<Int, Int> {
    if (areaW <= 0f || areaH <= 0f) return offsetXdp to offsetYdp
    var ox = offsetXdp.toFloat()
    var oy = offsetYdp.toFloat()
    repeat(2) {
        val trial = layout.withElement(id, layout.element(id).copy(offsetXdp = ox.roundToInt(), offsetYdp = oy.roundToInt()))
        val geom = trial.toGeomLayout()
        val rect = LayoutGeometry.elementRects(geom, areaW, areaH).forId(id)
            ?: return offsetXdp to offsetYdp
        // Ajuste global de la previa real: en horizontal es el
        // ajuste de la fila (fitFor); en vertical, el de portrait.
        // Los hombros no se escalan: su ajuste es 1.0.
        val fit = if (id == LayoutConfig.SHOULDERS_LEFT || id == LayoutConfig.SHOULDERS_RIGHT) {
            1f
        } else if (areaW > areaH) {
            val cols = LayoutGeometry.landscapeColumns(geom)
            LayoutGeometry.fitFor(areaW, areaH - LayoutGeometry.SHOULDER_ROW_H, cols.maxSpanH, cols.contentW)
        } else {
            LayoutGeometry.portrait(geom, areaW, areaH).fitScale
        }
        val margin = 8f
        ox += when {
            rect.centerX < margin -> (margin - rect.centerX) / fit
            rect.centerX > areaW - margin -> (areaW - margin - rect.centerX) / fit
            else -> 0f
        }
        oy += when {
            rect.centerY < margin -> (margin - rect.centerY) / fit
            rect.centerY > areaH - margin -> (areaH - margin - rect.centerY) / fit
            else -> 0f
        }
    }
    return ox.roundToInt() to oy.roundToInt()
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
