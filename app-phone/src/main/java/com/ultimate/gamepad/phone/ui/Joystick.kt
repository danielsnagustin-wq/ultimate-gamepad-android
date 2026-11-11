package com.ultimate.gamepad.phone.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
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

/** Vibración corta (15 ms) al pulsar un botón de las pantallas
 * (Ajustes, Extras, perfiles, tipo de mando, prueba,
 * rendimiento, selector de transporte). Misma duración y mismo
 * respeto al ajuste que `vibrateOnPress()` del ViewModel (que ya
 * vibra en los botones del MANDO: setButton/setDpad/pulseButton).
 * Los sticks no vibran: son entrada continua, no pulsaciones.
 * minSdk 26, así VibrationEffect.createOneShot no necesita
 * guarda de versión. */
fun vibrateTick(context: Context, enabled: Boolean) {
    if (!enabled) return
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    runCatching {
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

/** Pulsación con liberación garantizada: si el gesto se cancela
 * (llamada, bloqueo de pantalla, otro gesto que se lo queda),
 * el botón no se queda pulsado para siempre. También cuenta
 * dedos: con dos dedos en el mismo botón, no se suelta hasta
 * que se levanten los dos. Antes, una cancelación dejaba el
 * botón pegado (el juego lo seguía recibiendo). */
fun Modifier.pointerPress(onPress: (Boolean) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        var presses = 0
        try {
            while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                    androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                        presses++
                        if (presses == 1) onPress(true)
                    }
                    androidx.compose.ui.input.pointer.PointerEventType.Release -> {
                        presses = (presses - 1).coerceAtLeast(0)
                        if (presses == 0) onPress(false)
                    }
                }
            }
        } finally {
            if (presses > 0) {
                presses = 0
                onPress(false)
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
 * viaja hasta 54 dp desde el centro (antes 80 px, que en ~400 dpi
 * eran solo ~32 dp), así el stick se puede mover más y llegar al
 * tope de verdad. Un toque
 * Tope geometrico: base 180 dp (radio 90), pomo radio 36 dp
 * -> 90 - 36 = 54 dp (con 80 dp el borde del pomo quedaba 26 dp
 * fuera del anillo).
 * corto sin arrastrar es pulsar el stick (L3/R3, como en un
 * mando real): el detector de toques y el de arrastre conviven —
 * el toque solo dispara si no hubo movimiento, y el arrastre
 * cancela el toque.
 */
@Composable
fun JoystickView(
    onMove: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    onClick: (() -> Unit)? = null,
    /** En el panel de botones el stick se dibuja igual pero no se
     * mueve ni pulsa L3/R3: el gesto es del editor (arrastrar el
     * stick entero), no del juego. Sin esto, el arrastre del stick
     * en el editor movería el pomo y se pelearía con el arrastre
     * del elemento. */
    interactive: Boolean = true,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    // P4 (auditoría): `radius` eran 80 PÍXELES, no dp, sobre una
    // base de 180 dp: en ~400 dpi el pomo daba deflexión máxima a
    // 1/5 del recorrido (hipersensible y dependiente de la
    // densidad). Ahora son 54 dp convertidos a px con la densidad
    // real (tope geometrico 90-36=54 dp); el arrastre (dragAmount,
    // en px) y el pomo (knob, en px)
    // se comparan con ese mismo radio en px, así la deflexión
    // (clamped / radius) es 0..1 en cualquier densidad.
    val density = LocalDensity.current
    val radius = with(density) { 54.dp.toPx() }
    Box(
        modifier = Modifier.size(180.dp)
            .then(
                if (interactive) {
                    Modifier
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
                                // Y: arriba en pantalla = -1 (adelante), como un
                                // mando real (AXIS_Y / ABS_Y: arriba es negativo).
                                // La versión anterior negaba el valor y mandaba
                                // +1 al subir: el personaje o el menú se movían
                                // al revés en el juego.
                                onMove(clamped.x / radius, clamped.y / radius)
                            }
                        }
                } else Modifier
            ),
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
fun DpadView(onChange: (Boolean, Boolean, Boolean, Boolean) -> Unit, interactive: Boolean = true) {
    var up by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    var right by remember { mutableStateOf(false) }
    fun emit() = onChange(up, down, left, right)
    Box(
        modifier = Modifier.size(196.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.5.dp, Color(0xFF7C4DFF).copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            Row {
                Box(Modifier.size(56.dp)); DpadKey("▲", interactive = interactive) { p -> up = p; emit() }; Box(Modifier.size(56.dp))
            }
            Row {
                DpadKey("◀", interactive = interactive) { p -> left = p; emit() }
                Box(Modifier.size(56.dp))
                DpadKey("▶", interactive = interactive) { p -> right = p; emit() }
            }
            Row {
                Box(Modifier.size(56.dp)); DpadKey("▼", interactive = interactive) { p -> down = p; emit() }; Box(Modifier.size(56.dp))
            }
        }
    }
}

/** Tecla de cruceta con flecha bien visible: antes era un Button
 * de Material con la flecha casi invisible; ahora es una tecla
 * oscura brillante con borde neón y flecha blanca grande. */
@Composable
fun DpadKey(label: String, interactive: Boolean = true, onPress: (Boolean) -> Unit) {
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
            .then(if (interactive) Modifier.pointerPress { p -> pressed = p; onPress(p) } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}
