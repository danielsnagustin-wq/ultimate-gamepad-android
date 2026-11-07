package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                Chip(
                    label = "${method.label} · ${statusText(status)}",
                    selected = ui.selectedMethod == method,
                    dotColor = statusColor(status),
                ) { vm.selectMethod(method) }
            }
        }
        if (ui.hotspotMode) {
            Text(
                "Modo Hotspot: activa el hotspot del Samsung y conecta la TV a esa red. " +
                    "Escribe la IP del hotspot (normalmente 192.168.43.x) en Conectar.",
                color = Color(0xFF9AA0B0), fontSize = 12.sp,
            )
        }
        if (ui.hidAvailability.name == "UNAVAILABLE") {
            Text("Bluetooth HID: no disponible en este teléfono o sin comprobar. Ver Etapa 0.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        }
    }
}

private fun statusText(status: TransportStatus): String = when (status) {
    TransportStatus.AVAILABLE -> "Disponible"
    TransportStatus.UNAVAILABLE -> "No disponible"
    TransportStatus.CONNECTED -> "Conectado"
    TransportStatus.FAILED -> "Fallo"
    TransportStatus.PENDING_STAGE2 -> "Pendiente Etapa 2"
}

private fun statusColor(status: TransportStatus): Color = when (status) {
    TransportStatus.AVAILABLE -> Color(0xFF4CAF50)
    TransportStatus.UNAVAILABLE -> Color(0xFF9AA0B0)
    TransportStatus.CONNECTED -> Color(0xFF4CAF50)
    TransportStatus.FAILED -> Color(0xFFFF5252)
    TransportStatus.PENDING_STAGE2 -> Color(0xFFFFC107)
}

@Composable
private fun Chip(label: String, selected: Boolean, dotColor: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF7C4DFF) else Color(0xFF2A3040),
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(36.dp),
    ) {
        Text("● ", color = dotColor, fontSize = 12.sp)
        Text(label, fontSize = 12.sp)
    }
}
