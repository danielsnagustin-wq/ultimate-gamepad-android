package com.ultimate.gamepad.phone.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * PANTALLAS PENDIENTES, NO FUNCIONES FALSAS.
 *
 * Los PERFILES por juego YA están implementados (Fase 3):
 * GameProfilesScreen.kt, enlazada desde Ajustes > Mando PRO >
 * Perfiles por juego. El ProfilesTodoScreen de aquí es solo el
 * puente histórico y delega en la pantalla real.
 * El editor de controles SÍ existe ya (ui/LayoutEditorScreen.kt:
 * panel visual para mover, tamaño, transparencia, dead zones) y
 * se abre desde Ajustes > Personalizar botones; el
 * EditorTodoScreen de aquí es solo un marcador y no se enlaza
 * desde ningún botón.
 */
@Composable fun EditorTodoScreen() = Text("El editor de controles ya existe: Ajustes > Personalizar botones (panel visual).", color = Color.White)

/** Perfiles por juego (Fase 3): pantalla real, no TODO. */
@Composable fun ProfilesTodoScreen(vm: GamepadViewModel) = GameProfilesScreen(vm) {}
