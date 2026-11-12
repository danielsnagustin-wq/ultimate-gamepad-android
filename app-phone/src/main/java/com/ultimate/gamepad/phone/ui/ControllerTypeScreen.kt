package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimate.gamepad.core.GamepadProfiles

/**
 * TIPO DE MANDO (Fase 3) — tarjetas visuales por perfil.
 *
 * Cambia el perfil VISUAL y el de EMULACIÓN a la vez
 * (GamepadViewModel.setActiveProfile): la pantalla del teléfono
 * dibuja las etiquetas de ese perfil y la TV recrea el mando
 * virtual con su nombre y VID/PID. Nunca se muestra un mando de
 * PlayStation mientras la TV expone un Xbox 360.
 *
 * Al elegir, el teléfono envía el profileId nuevo en el byte 28
 * de cada paquete y la TV destruye y recrea el dispositivo (Fase
 * 2). La comprobación de que Android lo reconoce se hace mirando
 * la TV: su diagnóstico debe decir "Parece mando real: SÍ" con el
 * nombre nuevo. No hay canal de estado TV → teléfono que lo
 * devuelva, así que la pantalla lo dice en vez de fingir una
 * comprobación. Si OpenNOW no re-enumera el mando nuevo, hay que
 * reiniciar OpenNOW (a verificar en la TV real).
 *
 * Touchpad (DS4/DualSense): NO implementado (opción (a) del plan)
 * y se dice en la tarjeta; el botón de captura se etiqueta como
 * no disponible / Create, no como touchpad.
 */
@Composable
fun ControllerTypeScreen(vm: GamepadViewModel, ui: GamepadUiState, onClose: () -> Unit) {
    val vibration by vm.vibrationEnabled.collectAsState()
    val hapticContext = LocalContext.current
    fun tick() = vibrateTick(hapticContext, vibration)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("🎮  Tipo de mando", fontSize = 20.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Cambiar el tipo cambia el mando que se dibuja Y el mando virtual que crea la TV (nombre y VID/PID). Al elegir, la TV recrea el dispositivo con ese perfil.",
            color = Color.White, fontSize = 13.sp,
        )
        // P7: ui.error/ui.info no se mostraban en esta pantalla.
        // Mismo patrón que PerformanceScreen.
        if (ui.error != null) {
            Spacer(Modifier.height(8.dp))
            Text("Último error: ${ui.error}", color = Color(0xFFFF5252), fontSize = 13.sp)
        }
        if (ui.info != null) {
            Spacer(Modifier.height(8.dp))
            Text(ui.info!!, color = Color(0xFF9BF00B), fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Activo: ${GamepadProfiles.fromId(ui.activeProfileId).name} (${GamepadProfiles.fromId(ui.activeProfileId).deviceName})",
            color = Color(0xFF4CAF50), fontSize = 15.sp,
        )
        Spacer(Modifier.height(12.dp))
        for (profile in GamepadProfiles.ALL) {
            val selected = profile.id == ui.activeProfileId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Color(0xFF1E2436) else Color(0xFF111111))
                    .border(
                        2.dp,
                        if (selected) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { tick(); vm.setActiveProfile(profile.id) }
                    .padding(14.dp),
            ) {
                Row {
                    Text(profile.name, fontSize = 18.sp, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    if (selected) Text("● ACTIVO", color = Color(0xFF9BF00B), fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("La TV expone: \"${profile.deviceName}\"", color = Color.White, fontSize = 14.sp)
                Text(
                    // Arreglo 10: VID/PID con ceros a la izquierda
                    // (0x045E, no 0x45E).
                    "VID 0x${profile.vendorId.toString(16).uppercase().padStart(4, '0')} · PID 0x${profile.productId.toString(16).uppercase().padStart(4, '0')}",
                    color = Color.White, fontSize = 13.sp,
                )
                Text(profileDescription(profile.id), color = Color.White, fontSize = 13.sp)
                if (profile.hasTouchpad) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⚠ Touchpad NO implementado en esta versión (no hace falta para jugar en GeForce NOW). El botón de captura es ${if (profile.id == GamepadProfiles.DUALSENSE_ID) "Create" else "no disponible como touchpad"}.",
                        color = Color(0xFFFFC107), fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        val previous = ui.previousProfileId?.let { GamepadProfiles.fromId(it) }
        if (previous != null && previous.id != ui.activeProfileId) {
            Button(onClick = { tick(); vm.revertProfile() }, modifier = Modifier.fillMaxWidth()) {
                Text("↩  Cambiar al perfil anterior: ${previous.name}", color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "Comprueba en la TV (diagnóstico \"¿Cómo me ve Android?\") que el nombre nuevo aparece y dice \"Parece mando real: SÍ\". Si OpenNOW sigue viendo el mando viejo, reinicia OpenNOW: puede no re-enumerar el mando nuevo sin reiniciarse (sin verificar en la TV real).",
            color = Color.White, fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { tick(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
    }
}

private fun profileDescription(id: Int): String = when (id) {
    GamepadProfiles.XBOX_360_ID -> "El clásico de Xbox 360: A/B/X/Y, LB/RB, LT/RT analógicos, botón Xbox y Share."
    GamepadProfiles.XBOX_ONE_ID -> "Xbox One: como el 360, con botones Menu y View en vez de Start y Atrás, y el mismo mapa de botones."
    GamepadProfiles.DS4_ID -> "DualShock 4 (Sony): Cross/Circle/Square/Triangle, L1/R1, Options/Share, botón PS."
    GamepadProfiles.DUALSENSE_ID -> "DualSense (Sony): como el DS4, con botón Create. Sin háptica avanzada ni gatillos adaptativos (uinput no los expone)."
    else -> "Mando genérico, sin identidad de Microsoft ni de Sony: por si un juego no acepta los perfiles con marca."
}
