package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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

/**
 * Stick analógico. Un toque corto sin arrastrar es pulsar el
 * stick (L3/R3, como en un mando real): el detector de toques y
 * el de arrastre conviven — el toque solo dispara si no hubo
 * movimiento, y el arrastre cancela el toque.
 */
@Composable
fun JoystickView(onMove: (Float, Float) -> Unit, onRelease: () -> Unit, onClick: (() -> Unit)? = null) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val radius = 60f
    Box(
        modifier = Modifier.size(140.dp)
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
        Canvas(modifier = Modifier.size(140.dp)) {
            // Base con anillo morado tenue y pomo con brillo radial.
            drawCircle(color = Color(0xFF1E2433), radius = size.minDimension / 2f, center = center)
            drawCircle(
                color = Color(0xFF7C4DFF).copy(alpha = 0.45f),
                radius = size.minDimension / 2f - 2.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF4A5268), Color(0xFF2A3040)),
                    center = center + knob + Offset(-8.dp.toPx(), -8.dp.toPx()),
                    radius = 44.dp.toPx(),
                ),
                radius = 28.dp.toPx(),
                center = center + knob,
            )
            drawCircle(color = Color(0xFF7C4DFF), radius = 10.dp.toPx(), center = center + knob)
        }
    }
}

@Composable
fun DpadView(onChange: (Boolean, Boolean, Boolean, Boolean) -> Unit) {
    var up by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    var right by remember { mutableStateOf(false) }
    fun emit() = onChange(up, down, left, right)
    Column {
        Row {
            Box(Modifier.size(48.dp)); DpadKey("▲") { p -> up = p; emit() }; Box(Modifier.size(48.dp))
        }
        Row {
            DpadKey("◀") { p -> left = p; emit() }
            Box(Modifier.size(48.dp))
            DpadKey("▶") { p -> right = p; emit() }
        }
        Row {
            Box(Modifier.size(48.dp)); DpadKey("▼") { p -> down = p; emit() }; Box(Modifier.size(48.dp))
        }
    }
}

@Composable
fun DpadKey(label: String, onPress: (Boolean) -> Unit) {
    Button(onClick = {}, modifier = Modifier.size(48.dp).pointerPress(onPress)) { Text(label) }
}
