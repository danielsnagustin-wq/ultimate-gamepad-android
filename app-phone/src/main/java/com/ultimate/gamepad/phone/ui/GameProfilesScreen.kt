package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import java.util.Locale
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
    // P7: esta pantalla no recibía ui y nunca mostraba ui.error /
    // ui.info. Se lee el estado del ViewModel (sin tocar la
    // firma, que la usa GamepadScreen.kt).
    val ui by vm.ui.collectAsState()
    val vibration by vm.vibrationEnabled.collectAsState()
    val hapticContext = LocalContext.current
    fun tick() = vibrateTick(hapticContext, vibration)
    var profiles by remember { mutableStateOf(GameProfilePrefs.loadAll(context)) }
    var editing by remember { mutableStateOf<GameProfile?>(null) }
    // Nombre del perfil que se abrió en el editor (null = perfil
    // nuevo): para distinguir "editar sin renombrar" de
    // "renombrar a un nombre que ya existe" (P3).
    var editingOriginalName by rememberSaveable { mutableStateOf<String?>(null) }
    // P2: antes `activeName` era `remember` de la carga inicial y
    // "Aplicar" guardaba en prefs sin actualizarlo: la insignia
    // "● ACTIVO" no se movía hasta reabrir la pantalla. Ahora es
    // estado mutable y se actualiza en cada "Aplicar".
    var activeName by rememberSaveable { mutableStateOf(GameProfilePrefs.loadActiveName(context)) }
    // P3: renombrar a un nombre existente borraba el otro perfil
    // en silencio (filter + clean). Ahora se pide confirmación
    // explícita y nunca se sobrescribe sin aviso.
    var pendingOverwrite by remember { mutableStateOf<GameProfile?>(null) }
    var pendingOverwriteApply by rememberSaveable { mutableStateOf(false) }

    fun persist(next: List<GameProfile>) {
        profiles = next
        GameProfilePrefs.saveAll(context, next)
    }

    fun doSave(clean: GameProfile) {
        // UI-3: al renombrar también se quita el nombre ORIGINAL:
        // antes filter solo excluía clean.name y el perfil viejo
        // quedaba duplicado (renombrar copiaba, no renombraba).
        val next = profiles.filter { p -> p.name != clean.name && p.name != editingOriginalName } + clean
        persist(next.sortedBy { p -> p.name })
        pendingOverwrite = null
        editing = null
    }

    fun doApply(clean: GameProfile) {
        // UI-3: igual que doSave — quitar también el original.
        val next = profiles.filter { p -> p.name != clean.name && p.name != editingOriginalName } + clean
        persist(next.sortedBy { p -> p.name })
        vm.applyGameProfile(clean)
        GameProfilePrefs.saveActiveName(context, clean.name)
        activeName = clean.name
        pendingOverwrite = null
        editing = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("🗂️  Perfiles por juego", fontSize = 20.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Un perfil por juego, elegido a mano (sin detección automática: en GeForce NOW el juego corre en la nube). \"Aplicar\" cambia el mando, la sensibilidad, el remapeo y el diseño a la vez.",
            color = Color.White, fontSize = 13.sp,
        )
        // P7: error/info visibles, mismo patrón que PerformanceScreen.
        if (ui.error != null) {
            Spacer(Modifier.height(8.dp))
            Text("Último error: ${ui.error}", color = Color(0xFFFF5252), fontSize = 13.sp)
        }
        if (ui.info != null) {
            Spacer(Modifier.height(8.dp))
            Text(ui.info!!, color = Color(0xFF9BF00B), fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        val draft = editing
        if (draft == null) {
            if (profiles.isEmpty()) {
                Text("Aún no hay perfiles. Crea uno con el botón de abajo.", color = Color.White, fontSize = 14.sp)
            }
            for (p in profiles) {
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
                        if (p.name == activeName) Text("● ACTIVO", color = Color(0xFF9BF00B), fontSize = 12.sp)
                    }
                    Text(
                        "${GamepadProfiles.fromId(p.profileId).name} · zona muerta ${(p.deadZone * 100).toInt()}% · curva ${String.format(Locale.US, "%.2f", p.curve)} · gatillos x${String.format(Locale.US, "%.2f", p.triggerScale)}" +
                            (if (p.remap.isNotEmpty()) " · remap ${p.remap.entries.joinToString(", ") { "${GameProfilePrefs.buttonLabel(it.key)}→${GameProfilePrefs.buttonLabel(it.value)}" }}" else "") +
                            (if (p.layoutJson != null) " · con diseño de botones" else ""),
                        color = Color.White, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Button(onClick = {
                            tick(); vm.applyGameProfile(p)
                            GameProfilePrefs.saveActiveName(context, p.name)
                            activeName = p.name
                        }) { Text("Aplicar") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { tick(); editingOriginalName = p.name; editing = p }) { Text("Editar") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { tick(); editingOriginalName = null; editing = GameProfile(name = "Juego nuevo") }, modifier = Modifier.fillMaxWidth()) {
                Text("＋  Nuevo perfil de juego", color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { tick(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        } else if (pendingOverwrite != null) {
            // P3: confirmación de sobrescritura. Sin este paso, el
            // perfil con ese nombre se borraba en silencio.
            val clash = pendingOverwrite!!
            Text("⚠  Ya existe un perfil llamado «${clash.name}».", fontSize = 15.sp, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                "Si sigues, el perfil «${clash.name}» se sustituye por este. Si cancelas, no se borra nada y vuelves al editor.",
                fontSize = 13.sp,
                color = Color.White,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { tick(); if (pendingOverwriteApply) doApply(clash) else doSave(clash) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sobrescribir «${clash.name}»", color = Color.White) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { tick(); pendingOverwrite = null }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancelar, no borrar nada", color = Color.White)
            }
        } else {
            GameProfileEditor(
                draft = draft,
                vibrationEnabled = vibration,
                onDraftChange = { editing = it },
                onSave = {
                    val clean = it.sanitized()
                    val clash = profiles.any { p -> p.name == clean.name && p.name != editingOriginalName }
                    if (clash) {
                        pendingOverwrite = clean
                        pendingOverwriteApply = false
                    } else {
                        doSave(clean)
                    }
                },
                onApply = {
                    val clean = it.sanitized()
                    val clash = profiles.any { p -> p.name == clean.name && p.name != editingOriginalName }
                    if (clash) {
                        pendingOverwrite = clean
                        pendingOverwriteApply = true
                    } else {
                        doApply(clean)
                    }
                },
                onDelete = {
                    val wasActive = GameProfilePrefs.loadActiveName(context) == it.name
                    persist(profiles.filter { p -> p.name != it.name })
                    if (wasActive) {
                        // Ronda 2 P2-9: borrar el perfil activo
                        // revierte sus ajustes a los valores por
                        // defecto (antes quedaban aplicados sin perfil).
                        vm.clearActiveGameProfile()
                        activeName = null
                    }
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
    vibrationEnabled: Boolean,
    onDraftChange: (GameProfile) -> Unit,
    onSave: (GameProfile) -> Unit,
    onApply: (GameProfile) -> Unit,
    onDelete: (GameProfile) -> Unit,
    onCancel: () -> Unit,
    onSaveLayout: (GameProfile) -> Unit,
) {
    // Arreglo 09: los botones del editor también vibran.
    val hapticContext = LocalContext.current
    fun tick() = vibrateTick(hapticContext, vibrationEnabled)
    var name by rememberSaveable(draft) { mutableStateOf(draft.name) }
    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; onDraftChange(draft.copy(name = it)) },
            label = { Text("Nombre del juego") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("Tipo de mando", fontSize = 15.sp, color = Color.White)
        for (profile in GamepadProfiles.ALL) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Arreglo 10: el perfil elegido era indistinguible
                // (solo un "● " delante). Ahora el elegido es morado
                // sólido y los demás, gris oscuro.
                Button(
                    onClick = { tick(); onDraftChange(draft.copy(profileId = profile.id)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (draft.profileId == profile.id) MaterialTheme.colorScheme.primary else Color(0xFF1A1D27),
                        contentColor = Color.White,
                    ),
                ) {
                    Text((if (draft.profileId == profile.id) "● " else "") + profile.name, color = Color.White)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("Zona muerta de sticks: ${(draft.deadZone * 100).toInt()}%", fontSize = 15.sp, color = Color.White)
        Slider(value = draft.deadZone, onValueChange = { onDraftChange(draft.copy(deadZone = it)) }, valueRange = 0f..0.15f)
        Text("Curva de respuesta: ${String.format(Locale.US, "%.2f", draft.curve)} (1.00 = lineal, menos = más vivo en el centro)", fontSize = 15.sp, color = Color.White)
        Slider(value = draft.curve, onValueChange = { onDraftChange(draft.copy(curve = it)) }, valueRange = 0.5f..1.5f)
        Text("Sensibilidad de gatillos: x${String.format(Locale.US, "%.2f", draft.triggerScale)}", fontSize = 15.sp, color = Color.White)
        Slider(value = draft.triggerScale, onValueChange = { onDraftChange(draft.copy(triggerScale = it)) }, valueRange = 0.5f..1.5f)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vibración al pulsar", fontSize = 15.sp, modifier = Modifier.weight(1f), color = Color.White)
            Switch(checked = draft.vibrationEnabled, onCheckedChange = { onDraftChange(draft.copy(vibrationEnabled = it)) })
        }
        Text(
            "La vibración se aplica al pulsar los botones del mando y de estas pantallas. Este ajuste se guarda en el perfil: al aplicar un perfil, su ajuste de vibración pisa el interruptor de Extras.",
            color = Color(0xFFFFC107), fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text("Remapeo (lo que se envía, no lo que se dibuja)", fontSize = 15.sp, color = Color.White)
        Text("Toca \"Cambiar\" para ciclar el destino. Las etiquetas del mando no cambian.", color = Color.White, fontSize = 12.sp)
        for (source in listOf(GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${GameProfilePrefs.buttonLabel(source)} → ${draft.remap[source]?.let { GameProfilePrefs.buttonLabel(it) } ?: "sin cambio"}",
                    fontSize = 14.sp, modifier = Modifier.weight(1f), color = Color.White,
                )
                OutlinedButton(onClick = {
                    tick()
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
            onClick = { tick(); onSaveLayout(draft.copy(name = name)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Arreglo 10: "✓ Diseño guardado" mentía — esto solo
            // deja el diseño en el borrador; se confirma al pulsar
            // Guardar o Aplicar. El texto lo dice así.
            Text(if (draft.layoutJson != null) "✓ Diseño de botones en este perfil (borrador) — guardar el actual de nuevo" else "Guardar el diseño de botones actual en este perfil (borrador: se confirma con Guardar o Aplicar)", color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { tick(); onApply(draft.copy(name = name)) }, modifier = Modifier.fillMaxWidth()) { Text("Aplicar este perfil") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); onSave(draft.copy(name = name)) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar cambios") }
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = { tick(); onDelete(draft) }, modifier = Modifier.weight(1f)) { Text("Eliminar") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { tick(); onCancel() }, modifier = Modifier.weight(1f)) { Text("Cancelar") }
        }
    }
}
