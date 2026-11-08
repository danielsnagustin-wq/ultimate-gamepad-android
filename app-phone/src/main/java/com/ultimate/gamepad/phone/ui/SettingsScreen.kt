package com.ultimate.gamepad.phone.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/**
 * Ajustes y conexión (⚙). La pantalla del mando es solo el
 * mando; aquí dentro está el estado, la búsqueda de la TV en la
 * red, el selector de transporte, el QR y la IP/PIN, y el editor
 * para acomodar los botones a gusto.
 */
@Composable
fun SettingsScreen(vm: GamepadViewModel, ui: GamepadUiState) {
    val deadZone by vm.deadZone.collectAsState()
    var host by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    // Pantalla completa OPACA con scroll: el fondo opaco tapa el
    // mando y el clickable vacío se traga los toques para que no
    // lleguen al joystick de detrás.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .padding(16.dp),
    ) {
        Text("⚙  Configuración y conexión", fontSize = 20.sp)
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
                else -> Color(0xFFE8EAF0)
            },
            fontSize = 15.sp,
        )
        Text(
            text = "Ping: ${ui.pingMs?.let { "$it ms" } ?: "—"}   ·   Método: ${ui.selectedMethod.label} (${ui.transportMode})",
            color = Color(0xFF9AA0B0),
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
            Button(onClick = { vm.disconnect() }) { Text("Desconectar") }
        }
        Spacer(Modifier.height(16.dp))
        // --- Buscar TV en la red (sin escribir IP ni PIN) ---
        Text("Buscar TV en la red", fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("El teléfono busca la TV con el receptor abierto en la misma Wi-Fi. Toca la TV en la lista y conecta directo, sin escribir IP ni PIN.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        if (!ui.wifiConnected) {
            Spacer(Modifier.height(4.dp))
            Text("No estás en Wi-Fi. Conecta el teléfono a la misma red Wi-Fi que la TV.", color = Color(0xFFFF5252), fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.discoverTvs() }, enabled = !ui.discovering) {
            Text(if (ui.discovering) "Buscando TV…" else "🔍  Buscar TV en la red")
        }
        for (tv in ui.discoveredTvs) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.connectToDiscovered(tv) }, modifier = Modifier.fillMaxWidth()) {
                Text("${tv.name} — ${tv.ip}")
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
        Text("Método de conexión", fontSize = 15.sp)
        TransportSelectorRow(vm, ui)
        Spacer(Modifier.height(12.dp))
        Text("Conectar a la TV — misma red Wi-Fi", fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Text("1) Abre Ultimate Gamepad en la TV y anota su IP y PIN, o escanea su QR.")
        Spacer(Modifier.height(4.dp))
        Text("Modo Sin router / Hotspot: activa el hotspot del teléfono, conecta la TV a esa red y escribe la IP del hotspot (normalmente 192.168.43.x). Es el mismo Wi-Fi UDP/TCP, sin router.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.openQrScanner() }) { Text("Escanear QR de la TV") }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP de la TV, ej. 192.168.1.20") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = pin, onValueChange = { pin = it.take(4) }, label = { Text("PIN de 4 dígitos") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.connectManual(host, 51999, pin) }) { Text("Conectar por IP / PIN") }
        Spacer(Modifier.height(16.dp))
        // --- Personalizar botones ---
        Text("Personalizar botones", fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("Abre el panel visual y arrastra cada botón con el dedo, como en los juegos: ahí lo mueves, lo haces más grande o más pequeño, más transparente o lo quitas.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.openLayoutEditor() }, modifier = Modifier.fillMaxWidth()) {
            Text("🎮  Abrir panel de botones")
        }
        Spacer(Modifier.height(16.dp))
        // --- Sensibilidad de sticks ---
        Text("Sensibilidad de sticks", fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("Zona muerta: cuánto tienes que mover el stick para que empiece a responder. Más baja = responde desde el primer toque; más alta = no se mueve solo. Ahora: ${(deadZone * 100).toInt()}%.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        Slider(
            value = deadZone,
            onValueChange = { vm.setDeadZone(it) },
            valueRange = 0f..0.15f,
        )
        Spacer(Modifier.height(16.dp))
        // --- Mando PRO (Fase 3) ---
        Text("Mando PRO", fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("Tipo de mando, perfiles por juego, prueba de mando y rendimiento.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.openControllerType() }, modifier = Modifier.fillMaxWidth()) {
            Text("🎮  Tipo de mando (Xbox / PlayStation)")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.openGameProfiles() }, modifier = Modifier.fillMaxWidth()) {
            Text("🗂️  Perfiles por juego")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.openControllerTest() }, modifier = Modifier.fillMaxWidth()) {
            Text("🧪  Prueba de mando (qué se envía)")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.openPerformance() }, modifier = Modifier.fillMaxWidth()) {
            Text("📶  Rendimiento (latencia y red)")
        }
        Spacer(Modifier.height(16.dp))
        // --- Extras (Fase 4) ---
        Text("Extras (Fase 4)", fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("Turbo, macros, vibración, giroscopio, teclado virtual y modo ratón.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.openExtras() }, modifier = Modifier.fillMaxWidth()) {
            Text("✨  Extras (turbo, macros, teclado, ratón…)")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.closeSettings() }) { Text("Cerrar ajustes") }
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
fun QrScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var handled by remember { mutableStateOf(false) }
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
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .padding(16.dp),
    ) {
        if (!hasPermission) {
            Text("Se necesita la cámara para escanear el QR de la TV.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso de cámara") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClose) { Text("Volver a IP / PIN") }
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
                                    if (!handled) { handled = true; onResult(it) }
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
        Button(onClick = onClose) { Text("Cancelar escaneo") }
    }
}
