package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Personalizar interfaz (ítem 3 del usuario) — se abre desde
 * Ajustes, mismo patrón que las pantallas Fase 3 (vuelve a
 * Ajustes al cerrar; ver cableado en el manifiesto).
 *
 * - Color de acento: 6 círculos de color, el activo marcado con
 *   borde blanco y ✓. Cambia al instante en toda la app.
 * - Tamaño de texto: Pequeño / Normal / Grande, con vista previa
 *   en vivo: el texto de esta misma pantalla ya escala porque
 *   vive bajo UiCustomizationProviders (LocalDensity con el
 *   fontScale elegido).
 * - Fondo: Negro puro / Gris muy oscuro, con vista previa.
 * - Vista previa en vivo: un botón de ejemplo con el acento
 *   elegido sobre el fondo elegido.
 * - Restablecer valores: morado actual, texto normal, negro puro.
 *
 * Todo en blanco sobre negro: el texto secundario es blanco con
 * transparencia, nunca gris oscuro.
 * Los cambios se guardan al tocarlos (SharedPreferences) y se
 * aplican al instante; no hay botón "Guardar".
 */
@Composable
fun UiCustomizationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val holder = LocalUiCustomizationHolder.current
    // Sin holder (si MainActivity aún no cablea UiTheme), la
    // pantalla sigue funcionando: lee y guarda las prefs directa.
    var localCustomization by remember {
        mutableStateOf(UiCustomizationPrefs.loadAll(context))
    }
    val customization = holder?.current ?: localCustomization
    fun apply(next: UiCustomizationPrefs.UiCustomization) {
        if (holder != null) {
            holder.update(next)
        } else {
            UiCustomizationPrefs.saveAll(context, next)
            localCustomization = next.sanitized()
        }
    }
    fun reset() {
        if (holder != null) {
            holder.reset()
        } else {
            UiCustomizationPrefs.reset(context)
            localCustomization = UiCustomizationPrefs.UiCustomization()
        }
    }

    // Pantalla completa OPACA con scroll, mismo patrón que
    // SettingsScreen: el fondo opaco tapa el mando y el clickable
    // vacío se traga los toques para que no lleguen al joystick.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .padding(16.dp),
    ) {
        Text("🎨  Personalizar interfaz", fontSize = 20.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Elige el color de acento, el tamaño del texto y el fondo. Todo se aplica al instante en toda la app.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))

        // --- Color de acento ---
        Text("Color de acento", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Es el color de los botones y de lo destacado. Ahora: ${UiCustomizationPrefs.ACCENT_OPTIONS.firstOrNull { it.argb == customization.accentArgb }?.label ?: "Morado"}.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (option in UiCustomizationPrefs.ACCENT_OPTIONS) {
                val selected = option.argb == customization.accentArgb
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(option.argb))
                        .then(
                            if (selected) {
                                Modifier.border(3.dp, Color.White, CircleShape)
                            } else {
                                Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                            },
                        )
                        .clickable { apply(customization.copy(accentArgb = option.argb)) }
                        // R-39: el círculo es solo color (sin texto
                        // cuando no está seleccionado: la "✓" solo
                        // aparece si es el activo). Sin descripción,
                        // TalkBack lo anuncia como botón sin nombre.
                        .semantics { contentDescription = "Color de acento ${option.label}" },
                ) {
                    if (selected) {
                        Text("✓", color = Color.White, fontSize = 20.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (option in UiCustomizationPrefs.ACCENT_OPTIONS) {
                Text(
                    option.label,
                    color = if (option.argb == customization.accentArgb) {
                        Color.White
                    } else {
                        Color.White.copy(alpha = 0.7f)
                    },
                    fontSize = 11.sp,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // --- Tamaño de texto ---
        Text("Tamaño de texto", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "El texto de esta pantalla ya cambia al tocar cada opción: es la vista previa en vivo.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Ejemplo: Conectado · 10 ms: la TV recibe paquetes de mando.",
            color = Color.White,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in UiCustomizationPrefs.TEXT_SIZE_OPTIONS) {
                val selected = option.scale == customization.textScale
                Button(
                    onClick = { apply(customization.copy(textScale = option.scale)) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) {
                            Color(customization.accentArgb)
                        } else {
                            Color(0xFF222222)
                        },
                        contentColor = Color.White,
                    ),
                ) {
                    Text(option.label, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // --- Fondo ---
        Text("Fondo", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "El fondo de la app. Negro puro ahorra batería en pantallas AMOLED.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        for (option in UiCustomizationPrefs.BACKGROUND_OPTIONS) {
            val selected = option.argb == customization.backgroundArgb
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Color(0xFF222222) else Color.Transparent)
                    .border(
                        1.dp,
                        if (selected) Color.White else Color.White.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { apply(customization.copy(backgroundArgb = option.argb)) }
                    .padding(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(option.argb))
                        .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Text(option.label, color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Text("✓", color = Color.White, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(16.dp))

        // --- Vista previa en vivo ---
        Text("Vista previa", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(customization.backgroundArgb))
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Text("● Conectado a 192.168.1.20", color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Ping: 10 ms · Método: Wi-Fi UDP",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(customization.accentArgb),
                    contentColor = Color.White,
                ),
            ) {
                Text("Botón de ejemplo", color = Color.White)
            }
        }
        Spacer(Modifier.height(16.dp))

        // --- Restablecer + volver ---
        Button(
            onClick = { reset() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF222222),
                contentColor = Color.White,
            ),
        ) {
            Text("↺  Restablecer valores", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Restablece el morado actual, el texto normal y el negro puro.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Volver a Ajustes")
        }
    }
}
