package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Color
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

@Composable
fun JoystickView(onMove: (Float, Float) -> Unit, onRelease: () -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val radius = 60f
    Box(
        modifier = Modifier.size(140.dp).pointerInput(Unit) {
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
            drawCircle(color = Color(0xFF1E2433), radius = size.minDimension / 2f, center = center)
            drawCircle(color = Color(0xFF2A3040), radius = 28.dp.toPx(), center = center + knob)
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
