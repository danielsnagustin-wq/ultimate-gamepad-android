package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 */
@Composable
fun TransportSelectorRow(vm: GamepadViewModel, ui: GamepadUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Button(onClick = { vm.setTransportMode(TransportMode.AUTO) }) {
                Text(if (ui.transportMode == TransportMode.AUTO) "● Automático" else "Automático")
            }
            Button(onClick = { vm.setHotspotMode(!ui.hotspotMode) }) {
                Text(if (ui.hotspotMode) "● Sin router / Hotspot" else "Sin router / Hotspot")
            }
        }
        if (ui.hotspotMode) {
            Text(
                "Modo Hotspot: activa el hotspot del Samsung y conecta la TV a esa red. " +
                    "Es el mismo Wi-Fi UDP/TCP, sin router. Escribe la IP del hotspot de la TV " +
                    "(normalmente 192.168.43.x) en Conectar.",
                color = Color(0xFF9AA0B0), fontSize = 12.sp,
            )
        }
        Text("Método: ${ui.selectedMethod.label} (${ui.transportMode})", color = Color(0xFFE8EAF0), fontSize = 13.sp)
        for ((method, status) in ui.transportStatus) {
            Row {
                Button(onClick = { vm.selectMethod(method) }) { Text(method.label) }
                Text(
                    text = "  " + when (status) {
                        TransportStatus.AVAILABLE -> "Disponible"
                        TransportStatus.UNAVAILABLE -> "No disponible"
                        TransportStatus.CONNECTED -> "Conectado"
                        TransportStatus.FAILED -> "Fallo"
                        TransportStatus.PENDING_STAGE2 -> "Pendiente Etapa 2"
                    },
                    color = when (status) {
                        TransportStatus.CONNECTED -> Color(0xFF4CAF50)
                        TransportStatus.FAILED -> Color(0xFFFF5252)
                        TransportStatus.PENDING_STAGE2 -> Color(0xFFFFC107)
                        else -> Color(0xFF9AA0B0)
                    },
                    fontSize = 13.sp,
                )
            }
        }
        if (ui.hidAvailability.name == "UNAVAILABLE") {
            Text("Bluetooth HID: no disponible en este teléfono o sin comprobar. Ver Etapa 0.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        }
    }
}
