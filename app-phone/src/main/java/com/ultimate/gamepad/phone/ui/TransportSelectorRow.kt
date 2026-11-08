package com.ultimate.gamepad.phone.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
    val vibration by vm.vibrationEnabled.collectAsState()
    val hapticContext = LocalContext.current
    fun tick() = vibrateTick(hapticContext, vibration)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.refreshHidStatus(force = true)
            vm.refreshBtDataStatus()
        }
    }
    var showBtDialog by remember { mutableStateOf(false) }
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
            ) { tick(); vm.setTransportMode(TransportMode.AUTO) }
            Chip(
                label = if (ui.hotspotMode) "● Hotspot" else "Sin router / Hotspot",
                selected = ui.hotspotMode,
                dotColor = Color(0xFF4CAF50),
            ) { tick(); vm.setHotspotMode(!ui.hotspotMode) }
            for ((method, status) in ui.transportStatus) {
                // Mientras la comprobación HID no ha resuelto, el
                // chip lo dice ("Comprobando…") en vez del "No
                // disponible" inicial, que fingía un veredicto
                // antes de comprobar nada.
                // P6: "Disponible" sin comprobar nada mentía (el
                // mapa inicial marca Wi-Fi como AVAILABLE aunque
                // el Wi-Fi esté apagado). "Disponible" solo cuando
                // hay estado real verificado: conectado por ese
                // método, o la TV responde por él. Si no, el chip
                // dice "Sin comprobar" (o "Sin Wi-Fi", que sí está
                // verificado). El parche del ViewModel (manifiesto)
                // debe dejar de inicializar Wi-Fi como AVAILABLE.
                val isWifi = method == TransportMethod.WIFI_UDP || method == TransportMethod.WIFI_TCP
                val verifiedAvailable = status == TransportStatus.CONNECTED ||
                    (status == TransportStatus.AVAILABLE && ui.status == ConnectionStatus.CONNECTED && ui.attemptMethod == method)
                val label = if (method == TransportMethod.BLUETOOTH_HID && ui.hidAvailability == HidAvailability.UNKNOWN) {
                    "Bluetooth HID · Comprobando…"
                } else if (status == TransportStatus.AVAILABLE && !verifiedAvailable) {
                    if (isWifi && !ui.wifiConnected) "${method.label} · Sin Wi-Fi" else "${method.label} · Sin comprobar"
                } else {
                    "${method.label} · ${statusText(status)}"
                }
                val dot = if (status == TransportStatus.AVAILABLE && !verifiedAvailable) Color(0xFF9AA0B0) else statusColor(status)
                Chip(
                    label = label,
                    selected = ui.selectedMethod == method,
                    dotColor = dot,
                ) { tick(); vm.selectMethod(method) }
            }
        }
        // Arreglo 10: el texto del hotspot estaba duplicado aquí y
        // en SettingsScreen (junto a IP/PIN, donde hace falta). Se
        // quita de aquí y queda solo el de Ajustes.
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
            Button(onClick = { tick(); vm.refreshHidStatus(force = true) }) {
                Text("Volver a comprobar Bluetooth", color = Color.White)
            }
        }
        // Bluetooth datos (SPP/RFCOMM, Fase 5): respaldo real si falla
        // todo el Wi-Fi. La TV se elige de la lista de emparejados
        // (sin escaneo); el PIN sigue siendo el que muestra la TV.
        val btStatus = ui.transportStatus[TransportMethod.BLUETOOTH_DATA]
        if (btStatus == TransportStatus.UNAVAILABLE) {
            Text("Bluetooth datos: ${ui.btDataStatusDetail ?: "no disponible"}", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Button(onClick = { tick(); vm.refreshBtDataDevices(); showBtDialog = true }) {
                Text("Elegir TV emparejada", color = Color.White)
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { tick(); vm.refreshBtDataStatus() }) {
                Text("Volver a comprobar Bluetooth", color = Color.White)
            }
        }
        if (ui.selectedMethod == TransportMethod.BLUETOOTH_DATA) {
            Text(
                "Por Bluetooth datos los gatillos LT/RT son analógicos (0..255), como por Wi-Fi. La latencia real depende del enlace; sin eco, el ping muestra '—'.",
                color = Color.White, fontSize = 12.sp,
            )
        }
        if (ui.selectedMethod == TransportMethod.BLUETOOTH_HID) {
            // H3 / Fase 1: no prometer lo que el HID no da. Por
            // Bluetooth HID los gatillos LT/RT son digitales
            // (pulsados o no), sin recorrido analógico.
            Text(
                "Por Bluetooth HID los gatillos LT/RT son digitales, sin recorrido analógico.",
                color = Color.White, fontSize = 12.sp,
            )
        }
        if (ui.hidAvailability == HidAvailability.PERMISSION_DENIED) {
            Spacer(Modifier.height(4.dp))
            Text("Bluetooth HID: falta el permiso de Bluetooth (Android 12+). Sin él, el teléfono no puede exponerse como mando.", color = Color(0xFFFFC107), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Button(onClick = { tick(); permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Dar permiso de Bluetooth", color = Color.White)
            }
            // Arreglo 11: si el permiso ya se negó, Android no
            // vuelve a preguntar y el botón de arriba no hace nada.
            // Este botón abre Ajustes de la app, donde el permiso
            // sí se puede dar a mano.
            Spacer(Modifier.height(4.dp))
            Text("Si ya negaste el permiso, Android no vuelve a preguntar: dalo a mano en Ajustes de la app.", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                tick()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", hapticContext.packageName, null)
                }
                hapticContext.startActivity(intent)
            }) {
                Text("Abrir ajustes de la app")
            }
        }
        // Diálogo "Elegir TV emparejada" (Fase 5): sin escaneo, solo
        // la lista de emparejados. Al tocar una TV se persiste su MAC.
        if (showBtDialog) {
            AlertDialog(
                onDismissRequest = { showBtDialog = false },
                title = { Text("TV por Bluetooth", color = Color.White) },
                text = {
                    Column {
                        if (ui.btDataDevices.isEmpty()) {
                            Text("No hay TVs emparejadas. Empareja la TV en Ajustes > Bluetooth y vuelve a intentarlo.", color = Color.White, fontSize = 12.sp)
                        } else {
                            for (device in ui.btDataDevices) {
                                Button(
                                    onClick = { tick(); vm.selectBtDataDevice(device.address); showBtDialog = false },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("${device.name} (${device.address})", color = Color.White)
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showBtDialog = false }) {
                        Text("Cerrar", color = Color.White)
                    }
                },
            )
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

/**
 * Frecuencia real de envío según el método (arreglo 08): Wi-Fi
 * UDP/TCP envía el estado completo a 120 Hz, pero por Bluetooth
 * HID el reenvío es a 10 Hz (GamepadViewModel, startHidHeartbeat:
 * delay 100 ms). El texto "enviando a 120 Hz" mentía para HID.
 */
internal fun transportSendRateHz(method: TransportMethod): Int =
    if (method == TransportMethod.BLUETOOTH_HID) 10 else 120

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
