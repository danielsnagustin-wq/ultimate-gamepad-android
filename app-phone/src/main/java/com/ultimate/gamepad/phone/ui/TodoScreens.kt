package com.ultimate.gamepad.phone.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * PANTALLAS PENDIENTES, NO FUNCIONES FALSAS.
 *
 * Los PERFILES por juego NO están implementados todavía. El editor
 * de controles SÍ existe ya (ui/LayoutEditorScreen.kt: panel visual
 * para mover, tamaño, transparencia, dead zones) y se abre desde
 * Ajustes > Personalizar botones; el EditorTodoScreen de aquí es
 * solo un marcador y no se enlaza desde ningún botón. Estas
 * pantallas existen para reservar la navegación y dejar claro qué
 * falta. No se enlazan desde ningún botón que prometa que ya
 * funcionan. Los perfiles llegan en Etapa 3 (perfiles por juego +
 * import/export).
 */
@Composable fun EditorTodoScreen() = Text("El editor de controles ya existe: Ajustes > Personalizar botones (panel visual).")
@Composable fun ProfilesTodoScreen() = Text("TODO Etapa 3 — Perfiles por juego, guardar / importar / exportar.")
