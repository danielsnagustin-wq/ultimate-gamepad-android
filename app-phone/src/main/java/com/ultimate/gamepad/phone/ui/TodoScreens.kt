package com.ultimate.gamepad.phone.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Etapa 1 — PANTALLAS PENDIENTES, NO FUNCIONES FALSAS.
 *
 * El editor de controles y los perfiles NO están implementados en
 * Etapa 1. Estas pantallas existen solo para reservar la navegación
 * y dejar claro qué falta. No se enlazan desde ningún botón que
 * prometa que ya funcionan. Llegan en Etapa 2 (editor) y Etapa 3
 * (perfiles por juego + import/export).
 */
@Composable fun EditorTodoScreen() = Text("TODO Etapa 2 — Editor de controles: mover, tamaño, transparencia, dead zones.")
@Composable fun ProfilesTodoScreen() = Text("TODO Etapa 3 — Perfiles por juego, guardar / importar / exportar.")
