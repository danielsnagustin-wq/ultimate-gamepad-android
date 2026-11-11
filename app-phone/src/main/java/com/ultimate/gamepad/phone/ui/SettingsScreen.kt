package com.ultimate.gamepad.phone.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.ultimate.gamepad.core.PairingInfo

/**
 * Ajustes y conexión (⚙). La pantalla del mando es solo el
 * mando; aquí dentro está el estado, la búsqueda de la TV en la
 * red, el selector de transporte, el QR y la IP/PIN, y el editor
 * para acomodar los botones a gusto.
 */
@Composable
fun SettingsScreen(vm: GamepadViewModel, ui: GamepadUiState) {
    val deadZone by vm.deadZone.collectAsState()
    val vibration by vm.vibrationEnabled.collectAsState()
    val hapticContext = LocalContext.current
    fun tick() = vibrateTick(hapticContext, vibration)
    // Arreglo 12: IP/PIN sobreviven a la rotación.
    var host by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    // Pantalla completa OPACA con scroll: el fondo opaco tapa el
    // mando y el clickable vacío se traga los toques para que no
    // lleguen al joystick de detrás.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .padding(16.dp),
    ) {
        Text("⚙  Configuración y conexión", fontSize = 20.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (ui.status) {
                ConnectionStatus.DISCONNECTED -> "● Desconectado"
                ConnectionStatus.CONNECTING -> "● Conectando por ${ui.attemptMethod.label} a ${ui.host}…"
                ConnectionStatus.CONNECTED -> "● Conectado a ${ui.host}"
                ConnectionStatus.ERROR -> "● Error: ${ui.error ?: ""}"
            },
            color = when (ui.status) {
                ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                ConnectionStatus.CONNECTING -> Color(0xFFFFC107)
                ConnectionStatus.ERROR -> Color(0xFFFF5252)
                else -> Color.White
            },
            fontSize = 15.sp,
        )
        Text(
            text = "Ping: ${ui.pingMs?.let { "$it ms" } ?: "—"}   ·   Método: ${ui.selectedMethod.label} (${ui.transportMode})",
            color = Color.White,
            fontSize = 13.sp,
        )
        // ¿La TV recibe paquetes de mando, o solo responde al ping?
        // Es la diferencia entre "no llega nada" (red) y "llega
        // pero no inyecta" (falta Shizuku en la TV): el ping solo
        // no la distingue.
        if ((ui.status == ConnectionStatus.CONNECTED || ui.status == ConnectionStatus.CONNECTING) && ui.tvResponding) {
            Text(
                text = if (ui.tvReceivingPackets) "TV recibe: ${ui.tvPacketsReceived} paquetes de mando" else "La TV no recibe paquetes de mando (responde al ping)",
                color = if (ui.tvReceivingPackets) Color(0xFF4CAF50) else Color(0xFFFF5252),
                fontSize = 13.sp,
            )
        }
        if (ui.status == ConnectionStatus.CONNECTED) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { tick(); vm.disconnect() }) { Text("Desconectar") }
        }
        Spacer(Modifier.height(16.dp))
        // --- Buscar TV en la red (sin escribir IP ni PIN) ---
        Text("Buscar TV en la red", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("El teléfono busca la TV con el receptor abierto en la misma Wi-Fi. Toca la TV en la lista y conecta directo, sin escribir IP ni PIN.", color = Color.White, fontSize = 12.sp)
        if (!ui.wifiConnected) {
            Spacer(Modifier.height(4.dp))
            Text("No estás en Wi-Fi. Conecta el teléfono a la misma red Wi-Fi que la TV.", color = Color(0xFFFF5252), fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.discoverTvs() }, enabled = !ui.discovering) {
            Text(if (ui.discovering) "Buscando TV…" else "🔍  Buscar TV en la red", color = Color.White)
        }
        for (tv in ui.discoveredTvs) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { tick(); vm.connectToDiscovered(tv) }, modifier = Modifier.fillMaxWidth()) {
                Text("${tv.name} — ${tv.ip}", color = Color.White)
            }
        }
        // El error de búsqueda ANTES no se veía aquí: discoverTvs()
        // pone ui.error con el estado en DESCONECTADO, y esta
        // pantalla solo mostraba ui.error cuando el estado era
        // ERROR. El usuario veía "Buscar TV" sin resultado y sin
        // explicación. Ahora el mensaje (con el plan B: QR o
        // IP+PIN manual, justo debajo) se pinta siempre que exista.
        if (ui.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(ui.error!!, color = Color(0xFFFF5252), fontSize = 13.sp)
        }
        if (ui.info != null) {
            Spacer(Modifier.height(8.dp))
            Text(ui.info!!, color = Color(0xFF9BF00B), fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text("Método de conexión", fontSize = 15.sp, color = Color.White)
        TransportSelectorRow(vm, ui)
        Spacer(Modifier.height(12.dp))
        Text("Conectar a la TV — misma red Wi-Fi", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("1) Abre Ultimate Gamepad en la TV y anota su IP y PIN, o escanea su QR.", color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Modo Sin router / Hotspot: activa el hotspot del teléfono, conecta la TV a esa red y escribe la IP del hotspot (normalmente 192.168.43.x). Es el mismo Wi-Fi UDP/TCP, sin router.", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { tick(); vm.openQrScanner() }) { Text("Escanear QR de la TV") }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP de la TV, ej. 192.168.1.20", color = Color.White) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("PIN de 4 dígitos", color = Color.White) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { tick(); vm.connectManual(host, 51999, pin) }) { Text("Conectar por IP / PIN") }
        Spacer(Modifier.height(16.dp))
        // --- Personalizar botones ---
        Text("Personalizar botones", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Abre el panel visual y arrastra cada botón con el dedo, como en los juegos: ahí lo mueves, lo haces más grande o más pequeño, más transparente o lo quitas.", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.openLayoutEditor() }, modifier = Modifier.fillMaxWidth()) {
            Text("🎮  Abrir panel de botones", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        // --- Sensibilidad de sticks ---
        Text("Sensibilidad de sticks", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Zona muerta: cuánto tienes que mover el stick para que empiece a responder. Más baja = responde desde el primer toque; más alta = no se mueve solo. Ahora: ${(deadZone * 100).toInt()}%.", color = Color.White, fontSize = 12.sp)
        Slider(
            value = deadZone,
            onValueChange = { vm.setDeadZone(it) },
            valueRange = 0f..0.15f,
        )
        Spacer(Modifier.height(16.dp))
        // --- Mando PRO (Fase 3) ---
        Text("Mando PRO", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Tipo de mando, perfiles por juego, prueba de mando y rendimiento.", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.openControllerType() }, modifier = Modifier.fillMaxWidth()) {
            Text("🎮  Tipo de mando (Xbox / PlayStation)", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.openGameProfiles() }, modifier = Modifier.fillMaxWidth()) {
            Text("🗂️  Perfiles por juego", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.openControllerTest() }, modifier = Modifier.fillMaxWidth()) {
            Text("🧪  Prueba de mando (qué se envía)", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.openPerformance() }, modifier = Modifier.fillMaxWidth()) {
            Text("📶  Rendimiento (latencia y red)", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        // --- Extras (Fase 4) ---
        Text("Extras (Fase 4)", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Turbo, macros, vibración, giroscopio, teclado virtual y modo ratón.", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.openExtras() }, modifier = Modifier.fillMaxWidth()) {
            Text("✨  Extras (turbo, macros, teclado, ratón…)", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        // --- Personalizar interfaz (ítem 3) ---
        Text("Personalizar interfaz", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Color de acento, tamaño del texto y fondo de la app.", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { tick(); vm.openUiCustomization() }, modifier = Modifier.fillMaxWidth()) {
            Text("🎨  Personalizar interfaz", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        // --- Notificaciones (A4) ---
        // En Android 13+ el permiso POST_NOTIFICATIONS está
        // bloqueado hasta que la app lo pida: el canal ya existe
        // desde el arranque (MainActivity), pero sin el permiso el
        // interruptor de Ajustes no se deja activar. Aquí se pide
        // (y si Android ya no vuelve a preguntar, se abre la
        // pantalla de notificaciones del sistema). La notificación
        // del mando en primer plano funciona igual sin este permiso.
        Text("Notificaciones", fontSize = 15.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Android 13+ las bloquea hasta que las actives. El mando funciona igual sin ellas, pero sin el permiso no verás avisos de conexión.", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        val notifContext = LocalContext.current
        var notificationsGranted by rememberSaveable {
            mutableStateOf(
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(notifContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
        }
        var notificationsAsked by rememberSaveable { mutableStateOf(false) }
        val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted = granted
            notificationsAsked = true
        }
        if (notificationsGranted) {
            Text("● Notificaciones activas", color = Color(0xFF4CAF50), fontSize = 13.sp)
        } else {
            Text("● Notificaciones bloqueadas", color = Color(0xFFFF5252), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    tick()
                    if (notificationsAsked) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, notifContext.packageName)
                        }
                        notifContext.startActivity(intent)
                    } else {
                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (notificationsAsked) "Abrir ajustes de notificaciones" else "Activar notificaciones", color = Color.White)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { tick(); vm.closeSettings() }) { Text("Cerrar ajustes") }
    }
}


/**
 * Escaneo QR real con CameraX + ML Kit Barcode Scanning.
 * Si la cámara no tiene permiso, se pide; si el usuario lo niega,
 * queda la entrada manual de IP/PIN. No hay QR simulado.
 * El resultado solo se entrega una vez: antes el escáner llamaba
 * a onResult en cada fotograma con el QR y se reiniciaba la
 * conexión una y otra vez.
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
    // Arreglo 09: la vibración llega por parámetro (por defecto
    // apagada) porque esta pantalla no recibe el ViewModel; el
    // parche del manifiesto pasa vm.vibrationEnabled desde
    // GamepadScreen.kt, el único sitio que la instancia.
    vibrationEnabled: Boolean = false,
) {
    val context = LocalContext.current
    fun tick() = vibrateTick(context, vibrationEnabled)
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var handled by rememberSaveable { mutableStateOf(false) }
    // P1 (auditoría): antes `handled` era one-shot y el error de
    // QR inválido iba a ui.error, que el escáner nunca pinta: un
    // QR no-UGP1 dejaba el escáner mudo y ningún QR posterior se
    // aceptaba. Ahora el QR se valida AQUÍ (mismo parser que el
    // ViewModel): si no es válido se muestra el error DENTRO del
    // escáner y `handled` NO se marca, así el siguiente QR sí se
    // lee. Solo un QR válido marca `handled` y entrega el resultado.
    var qrError by rememberSaveable { mutableStateOf<String?>(null) }
    // La cámara se ligaba al ciclo de vida de la actividad y nunca
    // se soltaba al cerrar el escáner: seguía corriendo (y gastando
    // batería) detrás del mando. Se guarda el proveedor y el escáner
    // para soltarlos en onRelease.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var barcodeScanner by remember { mutableStateOf<BarcodeScanner?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    // Igual que SettingsScreen: pantalla completa OPACA. Antes el
    // PreviewView y el botón se emitían sueltos dentro del Box del
    // mando y se dibujaban uno encima del otro y sobre el mando.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .padding(16.dp),
    ) {
        if (!hasPermission) {
            Text("Se necesita la cámara para escanear el QR de la TV.", color = Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { tick(); launcher.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso de cámara") }
            Spacer(Modifier.height(8.dp))
            // Arreglo 11: si la cámara ya se negó, Android no vuelve
            // a preguntar y el botón de arriba no hace nada. Este
            // botón abre Ajustes de la app para darlo a mano.
            Text("Si ya negaste el permiso, Android no vuelve a preguntar: dalo a mano en Ajustes de la app.", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                tick()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }) { Text("Abrir ajustes de la app") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { tick(); onClose() }) { Text("Volver a IP / PIN") }
            return@Column
        }
        AndroidView(factory = { ctx ->
            PreviewView(ctx).also { previewView ->
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val scanner = BarcodeScanning.getClient()
                    barcodeScanner = scanner
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) { imageProxy.close(); return@setAnalyzer }
                        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstOrNull()?.rawValue?.let {
                                    if (handled) return@let
                                    if (PairingInfo.parseQrPayload(it) == null) {
                                        // QR inválido: avisar aquí dentro y
                                        // seguir escaneando (handled sigue false).
                                        qrError = "QR no válido: debe ser el QR de Ultimate Gamepad de la TV (UGP1). Apunta a otro QR."
                                    } else {
                                        handled = true
                                        qrError = null
                                        onResult(it)
                                    }
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
            }
        }, onRelease = {
            runCatching { cameraProvider?.unbindAll() }
            runCatching { barcodeScanner?.close() }
            cameraProvider = null
            barcodeScanner = null
        }, modifier = Modifier.fillMaxWidth().height(320.dp))
        Spacer(Modifier.height(12.dp))
        // Error de QR visible dentro del escáner (P1): antes iba a
        // ui.error y aquí nunca se pintaba.
        if (qrError != null) {
            Text(qrError!!, color = Color(0xFFFF5252), fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { tick(); qrError = null }) { Text("Entendido, seguir escaneando") }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = { tick(); onClose() }) { Text("Cancelar escaneo") }
    }
}
