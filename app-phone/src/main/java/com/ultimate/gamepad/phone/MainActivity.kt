package com.ultimate.gamepad.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultimate.gamepad.phone.network.GamepadSenderService
import com.ultimate.gamepad.phone.ui.GamepadScreen
import com.ultimate.gamepad.phone.ui.GamepadViewModel
import com.ultimate.gamepad.phone.ui.UiCustomizationProviders
import com.ultimate.gamepad.phone.ui.rememberUiCustomizationHolder
import com.ultimate.gamepad.phone.ui.uiColorSchemeFrom

/**
 * Pantalla principal: mando + conexión, con Ajustes, editor de
 * botones y perfiles por juego (Fases 3-4) como funciones reales.
 * La tipografía de los botones (labelLarge) va en seminegrita:
 * el texto blanco sobre las píldoras moradas se lee mejor.
 */
private val baseTypography = Typography()
private val appTypography = baseTypography.copy(
    labelLarge = baseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A4: el canal de notificación existe desde el primer arranque,
        // no solo cuando arranca el servicio: en Android 13+ el
        // interruptor de notificaciones de la app aparece bloqueado
        // si no hay ningún canal creado.
        GamepadSenderService.createNotificationChannel(this)
        // Modo inmersivo: la barra de botones del teléfono (atrás,
        // inicio, recientes) y la barra de estado se ocultan y el
        // mando usa toda la pantalla. Vuelven con un deslizamiento
        // desde el borde y se vuelven a ocultar solas.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            val uiHolder = rememberUiCustomizationHolder()
            val customization = uiHolder.current
            UiCustomizationProviders(holder = uiHolder) {
                MaterialTheme(
                    colorScheme = uiColorSchemeFrom(
                        base = darkColorScheme(
                            primary = Color(0xFF7C4DFF),
                            // Sin onPrimary, Material3 usa su valor por
                            // defecto oscuro (0xFF381E72): texto casi negro
                            // sobre las píldoras moradas y no se lee. Blanco,
                            // como el resto del texto de la app. Esto arregla
                            // de una vez todos los Button de la app, que usan
                            // primary de fondo y onPrimary de texto.
                            onPrimary = Color.White,
                            background = Color(0xFF0B0E14),
                            surface = Color(0xFF151A26),
                            onBackground = Color(0xFFE8EAF0),
                            onSurface = Color(0xFFE8EAF0),
                        ),
                        accentArgb = customization.accentArgb,
                        backgroundArgb = customization.backgroundArgb,
                    ),
                    // Los Button dibujan su texto con labelLarge (Medium
                    // por defecto). Seminegrita para el texto sobre morado.
                    typography = appTypography,
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val vm: GamepadViewModel = viewModel()
                        GamepadScreen(vm)
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }
}
