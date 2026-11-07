package com.ultimate.gamepad.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultimate.gamepad.phone.ui.GamepadScreen
import com.ultimate.gamepad.phone.ui.GamepadViewModel

/**
 * Etapa 1: una sola pantalla de mando + conexión.
 * El editor y los perfiles existen como destinos TODO (ver ui/TodoScreens.kt)
 * y NO se presentan como funciones que ya funcionan.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Modo inmersivo: la barra de botones del teléfono (atrás,
        // inicio, recientes) y la barra de estado se ocultan y el
        // mando usa toda la pantalla. Vuelven con un deslizamiento
        // desde el borde y se vuelven a ocultar solas.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7C4DFF),
                    background = Color(0xFF0B0E14),
                    surface = Color(0xFF151A26),
                    onBackground = Color(0xFFE8EAF0),
                    onSurface = Color(0xFFE8EAF0),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: GamepadViewModel = viewModel()
                    GamepadScreen(vm)
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
