package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimate.gamepad.core.GameProfile
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadProfiles

/**
 * Perfiles por juego (Fase 3) — pantalla real, sustituye al TODO
 * de TodoScreens.kt:20.
 *
 * Selección MANUAL por juego: no hay detección de app en primer
 * plano (en GeForce NOW el juego corre en la nube; el teléfono no
 * puede saber a qué se está jugando). Cada perfil guarda tipo de
 * mando, zona muerta, curva, sensibilidad de gatillos, vibración
 * (guardada, Fase 4), remapeo A/B/X/Y y el diseño de botones.
 *
 * "Aplicar" cambia todo a la vez (mando, zona muerta, curva,
 * gatillos, remapeo y diseño). El último perfil aplicado se
 * reaplica solo al arrancar la app. "Guardar layout actual" copia
 * el diseño de botones de ahora mismo dentro del perfil.
 */
@Composable
fun GameProfilesScreen(vm: GamepadViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(GameProfilePrefs.loadAll(context)) }
    var editing by remember { mutableStateOf<GameProfile?>(null) }
    val activeName by remember { mutableStateOf(GameProfilePrefs.loadActiveName(context)) }

    fun persist(next: List<GameProfile>) {
        profiles = next
        GameProfilePrefs.saveAll(context, next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("🗂️  Perfiles por juego", fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Un perfil por juego, elegido a mano (sin detección automática: en GeForce NOW el juego corre en la nube). \"Aplicar\" cambia el mando, la sensibilidad, el remapeo y el diseño a la vez.",
            color = Color(0xFF9AA0B0), fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        val draft = editing
        if (draft == null) {
            if (profiles.isEmpty()) {
                Text("Aún no hay perfiles. Crea uno con el botón de abajo.", color = Color(0xFF9AA0B0), fontSize = 14.sp)
            }
            for (p in profiles) {
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
                        if (p.name == activeName) Text("● ACTIVO", color = Color(0xFF9BF00B), fontSize = 12.sp)
                    }
                    Text(
                        "${GamepadProfiles.fromId(p.profileId).name} · zona muerta ${(p.deadZone * 100).toInt()}% · curva ${"%.2f".format(p.curve)} · gatillos x${"%.2f".format(p.triggerScale)}" +
                            (if (p.remap.isNotEmpty()) " · remap ${p.remap.entries.joinToString(", ") { "${GameProfilePrefs.buttonLabel(it.key)}→${GameProfilePrefs.buttonLabel(it.value)}" }}" else "") +
                            (if (p.layoutJson != null) " · diseño guardado" else ""),
                        color = Color(0xFF9AA0B0), fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Button(onClick = {
                            vm.applyGameProfile(p)
                            GameProfilePrefs.saveActiveName(context, p.name)
                        }) { Text("Aplicar") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { editing = p }) { Text("Editar") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { editing = GameProfile(name = "Juego nuevo") }, modifier = Modifier.fillMaxWidth()) {
                Text("＋  Nuevo perfil de juego")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        } else {
            GameProfileEditor(
                draft = draft,
                onDraftChange = { editing = it },
                onSave = {
                    val clean = it.sanitized()
                    val next = profiles.filter { p -> p.name != clean.name } + clean
                    persist(next.sortedBy { p -> p.name })
                    editing = null
                },
                onApply = {
                    val clean = it.sanitized()
                    val next = profiles.filter { p -> p.name != clean.name } + clean
                    persist(next.sortedBy { p -> p.name })
                    vm.applyGameProfile(clean)
                    editing = null
                },
                onDelete = {
                    persist(profiles.filter { p -> p.name != it.name })
                    if (GameProfilePrefs.loadActiveName(context) == it.name) GameProfilePrefs.saveActiveName(context, null)
                    editing = null
                },
                onCancel = { editing = null },
                onSaveLayout = { profile ->
                    val json = LayoutPrefs.toJson(vm.layoutConfig.value)
                    editing = profile.copy(layoutJson = json)
                },
            )
        }
    }
}

@Composable
private fun GameProfileEditor(
    draft: GameProfile,
    onDraftChange: (GameProfile) -> Unit,
    onSave: (GameProfile) -> Unit,
    onApply: (GameProfile) -> Unit,
    onDelete: (GameProfile) -> Unit,
    onCancel: () -> Unit,
    onSaveLayout: (GameProfile) -> Unit,
) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; onDraftChange(draft.copy(name = it)) },
            label = { Text("Nombre del juego") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("Tipo de mando", fontSize = 15.sp)
        for (profile in GamepadProfiles.ALL) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onDraftChange(draft.copy(profileId = profile.id)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text((if (draft.profileId == profile.id) "● " else "") + profile.name)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("Zona muerta de sticks: ${(draft.deadZone * 100).toInt()}%", fontSize = 15.sp)
        Slider(value = draft.deadZone, onValueChange = { onDraftChange(draft.copy(deadZone = it)) }, valueRange = 0f..0.15f)
        Text("Curva de respuesta: ${"%.2f".format(draft.curve)} (1.00 = lineal, menos = más vivo en el centro)", fontSize = 15.sp)
        Slider(value = draft.curve, onValueChange = { onDraftChange(draft.copy(curve = it)) }, valueRange = 0.5f..1.5f)
        Text("Sensibilidad de gatillos: x${"%.2f".format(draft.triggerScale)}", fontSize = 15.sp)
        Slider(value = draft.triggerScale, onValueChange = { onDraftChange(draft.copy(triggerScale = it)) }, valueRange = 0.5f..1.5f)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vibración al pulsar", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = draft.vibrationEnabled, onCheckedChange = { onDraftChange(draft.copy(vibrationEnabled = it)) })
        }
        Text(
            "La vibración solo se GUARDA por ahora: es Fase 4 y la app todavía no vibra. No se finge que funcione.",
            color = Color(0xFFFFC107), fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text("Remapeo (lo que se envía, no lo que se dibuja)", fontSize = 15.sp)
        Text("Toca \"Cambiar\" para ciclar el destino. Las etiquetas del mando no cambian.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        for (source in listOf(GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${GameProfilePrefs.buttonLabel(source)} → ${draft.remap[source]?.let { GameProfilePrefs.buttonLabel(it) } ?: "sin cambio"}",
                    fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    val options: List<Int?> = listOf(null, GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y)
                        .filter { it != source }
                    val current = draft.remap[source]
                    val next = options[(options.indexOf(current) + 1) % options.size]
                    val remap = draft.remap.toMutableMap()
                    if (next == null) remap.remove(source) else remap[source] = next
                    onDraftChange(draft.copy(remap = remap))
                }) { Text("Cambiar") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSaveLayout(draft.copy(name = name)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (draft.layoutJson != null) "✓ Diseño guardado — guardar el actual de nuevo" else "Guardar el diseño de botones actual en este perfil")
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onApply(draft.copy(name = name)) }, modifier = Modifier.fillMaxWidth()) { Text("Aplicar este perfil") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onSave(draft.copy(name = name)) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar cambios") }
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = { onDelete(draft) }, modifier = Modifier.weight(1f)) { Text("Eliminar") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
        }
    }
}
