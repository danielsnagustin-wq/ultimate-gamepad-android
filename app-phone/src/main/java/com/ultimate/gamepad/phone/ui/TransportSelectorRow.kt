package com.ultimate.gamepad.phone.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.MaterialTheme
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
import com.ultimate.gamepad.core.BluetoothDiscovery
import com.ultimate.gamepad.core.DiscoveredDevice
import com.ultimate.gamepad.core.HidAvailability
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportStatus

/**
 * Selector multi-transporte con estado REAL por método.
 * Regla: nunca se muestra un método como "Conectado" sin paquetes
 * reales. "Disponible" solo lo ponen las comprobaciones reales.
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
    // Permiso de escaneo Bluetooth (BLUETOOTH_SCAN en Android 12+,
    // ubicación en Android 11 y anteriores): al concederlo se
    // arranca el escaneo de una vez.
    val scanPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.startBtScan()
        }
    }
    var showBtDialog by remember { mutableStateOf(false) }
    var showBtScanDialog by remember { mutableStateOf(false) }
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
            // UI-6: el chip "Hotspot" se quitó: no hacía nada (nadie
            // leía hotspotMode). El hotspot sigue funcionando igual
            // sin él — es el mismo Wi-Fi UDP/TCP por la red del
            // hotspot, como explica Ajustes.
            for ((method, status) in ui.transportStatus) {
                // Mientras la comprobación HID no ha resuelto, el
                // chip lo dice ("Comprobando…") en vez del "No
                // disponible" inicial, que fingía un veredicto
                // antes de comprobar nada.
                // P1-6: "Disponible" solo cuando el estado está
                // verificado de verdad — y AVAILABLE solo lo ponen
                // las comprobaciones reales (refreshWifiState con
                // Wi-Fi, refreshHidStatus, refreshBtDataStatus,
                // selectBtDataDevice con BT encendido); el mapa
                // nace todo en UNAVAILABLE, así que un AVAILABLE
                // nunca es un "Sin comprobar" fingido.
                val isWifi = method == TransportMethod.WIFI_UDP || method == TransportMethod.WIFI_TCP
                val label = if (method == TransportMethod.BLUETOOTH_HID && ui.hidAvailability == HidAvailability.UNKNOWN) {
                    "Bluetooth HID · Comprobando…"
                } else if (isWifi && status == TransportStatus.UNAVAILABLE && !ui.wifiConnected) {
                    "${method.label} · Sin Wi-Fi"
                } else {
                    "${method.label} · ${statusText(status)}"
                }
                Chip(
                    label = label,
                    selected = ui.selectedMethod == method,
                    dotColor = statusColor(status),
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
                Text("Volver a comprobar Bluetooth HID", color = Color.White)
            }
        }
        // Bluetooth datos (SPP/RFCOMM, Fase 5): respaldo real si falla
        // todo el Wi-Fi. La TV se elige de la lista de emparejados
        // o se busca con el escáner; el PIN sigue siendo el que
        // muestra la TV.
        // P1-2: el botón de elegir TV está SIEMPRE disponible,
        // no solo cuando el método está en NO DISPONIBLE (antes,
        // con el método en DISPONIBLE, no había forma de cambiar
        // de TV sin que fallara antes).
        val btStatus = ui.transportStatus[TransportMethod.BLUETOOTH_DATA]
        if (btStatus == TransportStatus.UNAVAILABLE) {
            Text("Bluetooth datos: ${ui.btDataStatusDetail ?: "no disponible"}", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Button(onClick = { tick(); vm.refreshBtDataStatus() }) {
                Text("Volver a comprobar Bluetooth datos", color = Color.White)
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = { tick(); vm.refreshBtDataDevices(); showBtDialog = true }) {
            Text("Elegir TV emparejada", color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = { tick(); showBtScanDialog = true }) {
            Text("Buscar TV por Bluetooth", color = Color.White)
        }
        if (ui.selectedMethod == TransportMethod.BLUETOOTH_DATA) {
            Text(
                "Por Bluetooth datos los gatillos LT/RT son analógicos (0..255), como por Wi-Fi. La latencia real depende del enlace; sin eco, el ping muestra '—'.",
                color = Color.White, fontSize = 12.sp,
            )
        }
        if (ui.selectedMethod == TransportMethod.BLUETOOTH_HID) {
            // Ronda Bluetooth dos vías (2026-11-11): por HID los
            // gatillos LT/RT YA son analógicos (0..255, Brake/
            // Accelerator) y los botones siguen el orden estándar
            // de Android. El texto viejo ("digitales, sin
            // recorrido") quedó obsoleto con el nuevo descriptor.
            Text(
                "Por Bluetooth HID los gatillos LT/RT son analógicos (0..255). La TV inicia la conexión HID: el teléfono se registra, se hace visible y espera a que la TV empareje.",
                color = Color.White, fontSize = 12.sp,
            )
        }
        // Nombre del dispositivo HID conectado (P1-4): `host` es
        // la IP de Wi-Fi y no se sobrecarga con el nombre BT.
        if (ui.hidPeerName != null) {
            Text("Mando HID conectado a: ${ui.hidPeerName}", color = Color.White, fontSize = 12.sp)
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
        // Diálogo "Buscar TV por Bluetooth" (ronda Bluetooth dos
        // vías, 2026-11-11): el teléfono ESCANEA dispositivos
        // cercanos (~12 s) y los lista (emparejados + encontrados).
        // Al tocar uno sin emparejar, empareja (createBond); al
        // tocar uno emparejado, lo elige como TV de Bluetooth
        // datos. Límite honesto: en HID es la TV quien inicia la
        // conexión; desde el teléfono se empareja y se deja el HID
        // registrado + visible para que la TV lo complete.
        if (showBtScanDialog) {
            AlertDialog(
                onDismissRequest = { showBtScanDialog = false },
                title = { Text("Buscar TV por Bluetooth", color = Color.White) },
                text = {
                    Column {
                        Text(
                            "Para que la TV aparezca, abre sus ajustes de Bluetooth (visible) o empareja antes. Al elegirla, el teléfono empareja y deja el HID registrado + visible: es la TV quien completa la conexión HID.",
                            color = Color.White, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (ui.btScanError != null) {
                            Text(ui.btScanError!!, color = Color(0xFFFFC107), fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (ui.btScanDevices.isEmpty() && !ui.btScanning) {
                            Text("Aún no hay dispositivos. Toca \"Buscar\" para escanear.", color = Color.White, fontSize = 12.sp)
                        } else {
                            // Ronda 2 P2-14: probables TVs primero
                            // (con insignia 📺); el resto después.
                            // No es un filtro: cualquier dispositivo
                            // se puede elegir (los nombres de TV no
                            // son fiables).
                            // El comparador necesita el tipo explícito:
                            // sin él el compilador no infiere el
                            // parámetro de compareByDescending (#211).
                            val tvFirst = compareByDescending<DiscoveredDevice> { BluetoothDiscovery.looksLikeTv(it) }
                                .thenBy { it.name.orEmpty() }
                            val bonded = ui.btScanDevices.filter { it.bonded }
                                .sortedWith(tvFirst)
                            val found = ui.btScanDevices.filter { !it.bonded }
                                .sortedWith(tvFirst)
                            if (bonded.isNotEmpty()) {
                                Text("Emparejados", color = Color.White, fontSize = 12.sp)
                                for (device in bonded) {
                                    Button(
                                        onClick = { tick(); vm.selectBtScanDevice(device); showBtScanDialog = false },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("${if (BluetoothDiscovery.looksLikeTv(device)) "📺 " else ""}${device.name ?: "Sin nombre"} (${device.address})", color = Color.White)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                            if (found.isNotEmpty()) {
                                Text("Encontrados", color = Color.White, fontSize = 12.sp)
                                for (device in found) {
                                    Button(
                                        onClick = { tick(); vm.selectBtScanDevice(device); showBtScanDialog = false },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("${if (BluetoothDiscovery.looksLikeTv(device)) "📺 " else ""}${device.name ?: "Sin nombre"} (${device.address})${if (device.rssi != null) " · ${device.rssi} dBm" else ""}", color = Color.White)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ui.btScanning) {
                                Button(onClick = { tick(); vm.stopBtScan() }) {
                                    Text("Parar", color = Color.White)
                                }
                                Text("Buscando… (~12 s)", color = Color.White, fontSize = 12.sp)
                            } else {
                                Button(onClick = { tick(); vm.startBtScan() }) {
                                    Text("Buscar", color = Color.White)
                                }
                            }
                            Button(onClick = {
                                tick()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) scanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
                                else scanPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }) {
                                Text("Dar permiso de escaneo", color = Color.White)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showBtScanDialog = false }) {
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
            containerColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF1A1D27),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(36.dp)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                RoundedCornerShape(18.dp),
            ),
    ) {
        Text("● ", color = dotColor, fontSize = 12.sp)
        Text(label, fontSize = 12.sp, color = Color.White)
    }
}
