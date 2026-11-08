package com.ultimate.gamepad.phone.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimate.gamepad.core.HidAvailability
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportStatus

/**
 * Selector multi-transporte con estado REAL por método.
 * Regla: nunca se muestra un método como "Conectado" sin paquetes
 * reales. "Pendiente Etapa 2" no es "Conectado" ni "Disponible".
 *
 * Es UNA sola fila horizontal con scroll: antes era una lista
 * vertical de 5 filas y en horizontal se comía toda la pantalla
 * (~300 dp de ~388 dp) y el mando (stick, cruceta, A/B/X/Y)
 * quedaba aplastado a 0 dp: el gamepad nunca aparecía aunque no
 * necesitara conexión para verse. Ahora la fila ocupa ~40 dp y
 * el mando tiene sitio siempre, conectado o no.
 */
@Composable
fun TransportSelectorRow(vm: GamepadViewModel, ui: GamepadUiState) {
    // El launcher se declara siempre (regla de Compose: no puede ir
    // dentro de un if); solo se usa cuando falta el permiso.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.refreshHidStatus(force = true)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(
                label = if (ui.transportMode == TransportMode.AUTO) "● Automático" else "Automático",
                selected = ui.transportMode == TransportMode.AUTO,
                dotColor = Color(0xFF4CAF50),
            ) { vm.setTransportMode(TransportMode.AUTO) }
            Chip(
                label = if (ui.hotspotMode) "● Hotspot" else "Sin router / Hotspot",
                selected = ui.hotspotMode,
                dotColor = Color(0xFF4CAF50),
            ) { vm.setHotspotMode(!ui.hotspotMode) }
            for ((method, status) in ui.transportStatus) {
                // Mientras la comprobación HID no ha resuelto, el
                // chip lo dice ("Comprobando…") en vez del "No
                // disponible" inicial, que fingía un veredicto
                // antes de comprobar nada.
                val label = if (method == TransportMethod.BLUETOOTH_HID && ui.hidAvailability == HidAvailability.UNKNOWN) {
                    "Bluetooth HID · Comprobando…"
                } else {
                    "${method.label} · ${statusText(status)}"
                }
                Chip(
                    label = label,
                    selected = ui.selectedMethod == method,
                    dotColor = statusColor(status),
                ) { vm.selectMethod(method) }
            }
        }
        if (ui.hotspotMode) {
            Text(
                "Modo Hotspot: activa el hotspot del teléfono y conecta la TV a esa red. " +
                    "Escribe la IP del hotspot (normalmente 192.168.43.x) en Conectar.",
                color = Color.White, fontSize = 12.sp,
            )
        }
        // Estado de la verificación HID, sin ambigüedad: antes el
        // chip decía "No disponible" desde el arranque, antes de
        // comprobar nada, y la nota mezclaba "no disponible" con
        // "sin comprobar". Ahora: UNKNOWN = comprobando (y se ve),
        // UNAVAILABLE = comprobado y no disponible, con botón para
        // volver a comprobar (p. ej. tras encender el Bluetooth).
        if (ui.hidAvailability == HidAvailability.UNKNOWN) {
            Text("Bluetooth HID: comprobando si este teléfono puede ser mando Bluetooth…", color = Color.White, fontSize = 12.sp)
        }
        if (ui.hidAvailability == HidAvailability.UNAVAILABLE) {
            Text("Bluetooth HID: no disponible en este teléfono (no expone HID_DEVICE o el Bluetooth está apagado).", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Button(onClick = { vm.refreshHidStatus(force = true) }) {
                Text("Volver a comprobar Bluetooth", color = Color.White)
            }
        }
        // BLE/SPP (2026-11-07): NO implementado, y hay que decirlo
        // así. Antes el chip decía "Pendiente Etapa 2", que suena a
        // que casi funciona; no es un respaldo real y no se puede
        // forzar. Respaldos reales: Wi-Fi UDP/TCP y Bluetooth HID.
        Text(
            "Bluetooth datos (BLE/SPP): no implementado todavía — no se puede usar ni forzar. Si falla todo el Wi-Fi, los respaldos reales son Bluetooth HID y el hotspot.",
            color = Color(0xFFFFC107), fontSize = 12.sp,
        )
        if (ui.selectedMethod == TransportMethod.BLUETOOTH_HID) {
            // H3 / Fase 1: no prometer lo que el HID no da. Por
            // Bluetooth HID los gatillos LT/RT son digitales
            // (pulsados o no), sin recorrido analógico.
            Text(
                "Por Bluetooth HID los gatillos LT/RT son digitales, sin recorrido analógico.",
                color = Color.White, fontSize = 12.sp,
            )
        }
        if (ui.hidAvailability.name == "PERMISSION_DENIED") {
            Spacer(Modifier.height(4.dp))
            Text("Bluetooth HID: falta el permiso de Bluetooth (Android 12+). Sin él, el teléfono no puede exponerse como mando.", color = Color(0xFFFFC107), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Dar permiso de Bluetooth", color = Color.White)
            }
        }
    }
}

private fun statusText(status: TransportStatus): String = when (status) {
    TransportStatus.AVAILABLE -> "Disponible"
    TransportStatus.UNAVAILABLE -> "No disponible"
    TransportStatus.CONNECTED -> "Conectado"
    TransportStatus.FAILED -> "Fallo"
    TransportStatus.PENDING_STAGE2 -> "No implementado"
}

private fun statusColor(status: TransportStatus): Color = when (status) {
    TransportStatus.AVAILABLE -> Color(0xFF4CAF50)
    // Punto de estado, no texto: gris apagado para "no
    // disponible" (el texto del chip sí es blanco, arreglo 01).
    TransportStatus.UNAVAILABLE -> Color(0xFF9AA0B0)
    TransportStatus.CONNECTED -> Color(0xFF4CAF50)
    TransportStatus.FAILED -> Color(0xFFFF5252)
    TransportStatus.PENDING_STAGE2 -> Color(0xFFFFC107)
}

@Composable
private fun Chip(label: String, selected: Boolean, dotColor: Color, onClick: () -> Unit) {
    // El texto no se veía: ButtonDefaults solo ponía el fondo
    // (morado si estaba elegido, gris si no) y el color del texto
    // lo ponía el tema (morado sobre gris = ilegible). Ahora el
    // texto es blanco siempre, con borde visible si no está elegido.
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF7C4DFF) else Color(0xFF1A1D27),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(36.dp)
            .border(
                1.dp,
                if (selected) Color(0xFF7C4DFF) else Color(0xFF7C4DFF).copy(alpha = 0.6f),
                RoundedCornerShape(18.dp),
            ),
    ) {
        Text("● ", color = dotColor, fontSize = 12.sp)
        Text(label, fontSize = 12.sp, color = Color.White)
    }
}
