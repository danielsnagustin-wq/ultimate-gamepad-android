package com.ultimate.gamepad.phone.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

fun Modifier.pointerPress(onPress: (Boolean) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            when (event.type) {
                androidx.compose.ui.input.pointer.PointerEventType.Press -> onPress(true)
                androidx.compose.ui.input.pointer.PointerEventType.Release -> onPress(false)
            }
        }
    }
}

/** Efecto de pulsación: el botón se hunde un poco y se encoge,
 * con rebote al soltar, para que se vea que sí se mueve al
 * presionarlo. Se aplica a todos los botones del mando. */
@Composable
fun Modifier.pressEffect(pressed: Boolean): Modifier {
    val density = LocalDensity.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pressScale",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (pressed) with(density) { 4.dp.toPx() } else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pressOffset",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale; translationY = offsetY }
}

/**
 * Stick analógico, 180 dp con más recorrido (antes 160 dp): el
 * recorrido corto era parte de la mala sensibilidad. El pomo
 * viaja hasta 80 px desde el centro (antes 70 px), así el stick
 * se puede mover más y llegar al tope de verdad. Un toque
 * corto sin arrastrar es pulsar el stick (L3/R3, como en un
 * mando real): el detector de toques y el de arrastre conviven —
 * el toque solo dispara si no hubo movimiento, y el arrastre
 * cancela el toque.
 */
@Composable
fun JoystickView(onMove: (Float, Float) -> Unit, onRelease: () -> Unit, onClick: (() -> Unit)? = null) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val radius = 80f
    Box(
        modifier = Modifier.size(180.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick?.invoke() }) }
            .pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = { knob = Offset.Zero; onRelease() },
                onDragCancel = { knob = Offset.Zero; onRelease() },
            ) { change, dragAmount ->
                change.consume()
                val next = knob + dragAmount
                val len = sqrt(next.x * next.x + next.y * next.y)
                val clamped = if (len > radius) next * (radius / len) else next
                knob = clamped
                // Y invertida: arriba en pantalla = -1 (adelante), como un mando real.
                onMove(clamped.x / radius, -clamped.y / radius)
            }
        },
    ) {
        Canvas(modifier = Modifier.size(180.dp)) {
            val c = center
            // Halo neón suave detrás del stick.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF7C4DFF).copy(alpha = 0.22f), Color.Transparent),
                    center = c,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = c,
            )
            // Base AMOLED (negro puro) con doble anillo neón (morado + cian tenue).
            drawCircle(color = Color.Black, radius = size.minDimension / 2f - 4.dp.toPx(), center = c)
            drawCircle(
                brush = Brush.sweepGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF), Color(0xFF7C4DFF))),
                radius = size.minDimension / 2f - 4.dp.toPx(),
                center = c,
                style = Stroke(width = 2.5.dp.toPx()),
            )
            drawCircle(
                color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                radius = size.minDimension / 2f - 10.dp.toPx(),
                center = c,
                style = Stroke(width = 1.dp.toPx()),
            )
            // Pomo con textura: degradado radial + anillos concéntricos.
            val knobCenter = c + knob
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF3A3F52), Color(0xFF0B0B0B)),
                    center = knobCenter + Offset(-10.dp.toPx(), -10.dp.toPx()),
                    radius = 56.dp.toPx(),
                ),
                radius = 36.dp.toPx(),
                center = knobCenter,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.14f),
                radius = 29.dp.toPx(),
                center = knobCenter,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = 20.dp.toPx(),
                center = knobCenter,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(color = Color(0xFF7C4DFF), radius = 10.dp.toPx(), center = knobCenter)
        }
    }
}

/**
 * Cruceta sobre un plato circular, como el mando Xbox Series:
 * las 4 teclas van sobre un disco oscuro con anillo tenue, en
 * vez de flotar sueltas. */
@Composable
fun DpadView(onChange: (Boolean, Boolean, Boolean, Boolean) -> Unit) {
    var up by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    var right by remember { mutableStateOf(false) }
    fun emit() = onChange(up, down, left, right)
    Box(
        modifier = Modifier.size(196.dp)
            .clip(CircleShape)
            .background(Color.Black)
            .border(1.5.dp, Color(0xFF7C4DFF).copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            Row {
                Box(Modifier.size(56.dp)); DpadKey("▲") { p -> up = p; emit() }; Box(Modifier.size(56.dp))
            }
            Row {
                DpadKey("◀") { p -> left = p; emit() }
                Box(Modifier.size(56.dp))
                DpadKey("▶") { p -> right = p; emit() }
            }
            Row {
                Box(Modifier.size(56.dp)); DpadKey("▼") { p -> down = p; emit() }; Box(Modifier.size(56.dp))
            }
        }
    }
}

/** Tecla de cruceta con flecha bien visible: antes era un Button
 * de Material con la flecha casi invisible; ahora es una tecla
 * oscura brillante con borde neón y flecha blanca grande. */
@Composable
fun DpadKey(label: String, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(56.dp)
            .pressEffect(pressed)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFF7C4DFF), Color(0xFF5E35B1))
                    else listOf(Color(0xFF1A1D27), Color(0xFF000000)),
                ),
            )
            .border(1.5.dp, Color(0xFF7C4DFF).copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .pointerPress { p -> pressed = p; onPress(p) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}
