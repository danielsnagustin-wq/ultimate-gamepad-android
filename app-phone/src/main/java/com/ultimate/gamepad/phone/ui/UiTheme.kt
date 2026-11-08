package com.ultimate.gamepad.phone.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Tema personalizable (ítem 3 del usuario) — lee
 * UiCustomizationPrefs y expone el acento, el fondo y el factor
 * de texto al resto de la app.
 *
 * Cómo se aplica el tamaño de texto SIN tocar cada pantalla:
 * UiCustomizationProviders re-proporciona LocalDensity con un
 * fontScale multiplicado por el factor elegido (0.85 / 1.0 /
 * 1.15). Todos los tamaños en `sp` de todas las pantallas
 * escalan automáticamente porque Compose los resuelve contra
 * LocalDensity; los tamaños en `dp` (botones, joysticks) no
 * cambian, que es lo correcto: el mando no debe encoger ni
 * crecer, solo el texto. Ninguna pantalla necesita cambios.
 *
 * Cómo obtienen las demás pantallas el acento y el fondo SIN
 * tocar cada archivo: el esquema de colores se construye UNA
 * vez en MainActivity (ver cableado en el manifiesto) con
 * uiColorSchemeFrom() y se pasa al MaterialTheme raíz; todas
 * las pantallas que usan MaterialTheme.colorScheme (Button,
 * Surface, etc.) lo heredan solas. Las pantallas que dibujan
 * colores a mano (p. ej. SettingsScreen pone Color.Black y
 * grises 0xFF9AA0B0 literales) NO heredan nada: eso lo arregla
 * el ítem 2 del usuario (texto blanco sobre negro), que se hace
 * en paralelo y es quien debe sustituir esos literales por
 * colores del tema. Esta pieza no toca ninguna pantalla.
 */

/** Los ajustes activos, disponibles en toda la composición. */
val LocalUiCustomization = staticCompositionLocalOf {
    UiCustomizationPrefs.UiCustomization()
}

/**
 * Titular en memoria de los ajustes, con estado observable por
 * Compose. MainActivity crea UNO con rememberUiCustomizationHolder()
 * y lo pasa a UiCustomizationProviders; la pantalla de
 * personalización lo lee de LocalUiCustomizationHolder y llama a
 * update(): el cambio se guarda en SharedPreferences Y se
 * refleja en toda la app al instante (el tema lee holder.current,
 * que es snapshot state), sin reiniciar nada ni tocar el ViewModel.
 */
class UiCustomizationHolder(private val appContext: Context) {
    var current by mutableStateOf(UiCustomizationPrefs.loadAll(appContext))
        private set

    /** Guarda y aplica un cambio (acento, texto o fondo). */
    fun update(customization: UiCustomizationPrefs.UiCustomization) {
        val clean = customization.sanitized()
        UiCustomizationPrefs.saveAll(appContext, clean)
        current = clean
    }

    /** Relee de disco (por si algo cambió las prefs por fuera). */
    fun reload() {
        current = UiCustomizationPrefs.loadAll(appContext)
    }

    /** Vuelve a los valores por defecto y los aplica. */
    fun reset() {
        UiCustomizationPrefs.reset(appContext)
        current = UiCustomizationPrefs.UiCustomization()
    }
}

val LocalUiCustomizationHolder = staticCompositionLocalOf<UiCustomizationHolder?> { null }

@Composable
fun rememberUiCustomizationHolder(): UiCustomizationHolder {
    val context = LocalContext.current
    return remember { UiCustomizationHolder(context.applicationContext) }
}

/**
 * Esquema de color con el acento y el fondo elegidos por el
 * usuario. `base` es el darkColorScheme actual de MainActivity:
 * se copian sus valores y solo se sustituyen el acento, el
 * fondo y los textos. onPrimary es SIEMPRE blanco (el arreglo
 * del texto morado: texto oscuro sobre acento brillante no se
 * lee), y onBackground/onSurface son blanco casi puro sobre
 * negro, coherente con el ítem 2.
 */
fun uiColorSchemeFrom(
    base: ColorScheme,
    accentArgb: Int,
    backgroundArgb: Int,
): ColorScheme = base.copy(
    primary = Color(accentArgb),
    onPrimary = Color.White,
    background = Color(backgroundArgb),
    onBackground = Color(0xFFE8EAF0),
    onSurface = Color(0xFFE8EAF0),
)

/** Atajo: mismo esquema construido desde el darkColorScheme base. */
fun uiColorScheme(accentArgb: Int, backgroundArgb: Int): ColorScheme =
    uiColorSchemeFrom(
        base = darkColorScheme(
            primary = Color(UiCustomizationPrefs.DEFAULT_ACCENT_ARGB),
            onPrimary = Color.White,
            background = Color(0xFF0B0E14),
            surface = Color(0xFF151A26),
            onBackground = Color(0xFFE8EAF0),
            onSurface = Color(0xFFE8EAF0),
        ),
        accentArgb = accentArgb,
        backgroundArgb = backgroundArgb,
    )

/**
 * Envuelve el contenido raíz de la app: proporciona los ajustes
 * (LocalUiCustomization + LocalUiCustomizationHolder) y el
 * LocalDensity con el fontScale del usuario. NO crea el
 * MaterialTheme: MainActivity lo sigue creando con
 * uiColorSchemeFrom() (ver cableado en el manifiesto), porque
 * la tipografía seminegrita (appTypography) vive allí.
 */
@Composable
fun UiCustomizationProviders(
    holder: UiCustomizationHolder = rememberUiCustomizationHolder(),
    content: @Composable () -> Unit,
) {
    val customization = holder.current
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalUiCustomizationHolder provides holder,
        LocalUiCustomization provides customization,
        LocalDensity provides Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * customization.textScale,
        ),
    ) {
        content()
    }
}
