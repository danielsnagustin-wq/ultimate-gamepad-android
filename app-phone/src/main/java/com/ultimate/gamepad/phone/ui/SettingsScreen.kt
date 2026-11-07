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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/**
 * Ajustes y conexión (⚙). Antes todo esto vivía en la barra de
 * arriba del mando y estorbaba toda la pantalla: ahora la pantalla
 * del mando es solo el mando, y aquí dentro está el estado, el
 * selector de transporte, el QR y la IP/PIN.
 */
@Composable
fun SettingsScreen(vm: GamepadViewModel, ui: GamepadUiState) {
    var host by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    // Pantalla completa OPACA con scroll: el fondo opaco tapa el
    // mando y el clickable vacío se traga los toques para que no
    // lleguen al joystick de detrás.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
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
        if (ui.status == ConnectionStatus.CONNECTED) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.disconnect() }) { Text("Desconectar") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Método de conexión", fontSize = 15.sp)
        TransportSelectorRow(vm, ui)
        Spacer(Modifier.height(12.dp))
        Text("Conectar a la TV — misma red Wi-Fi", fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Text("1) Abre Ultimate Gamepad en la TV y anota su IP y PIN, o escanea su QR.")
        Spacer(Modifier.height(4.dp))
        Text("Modo Sin router / Hotspot: activa el hotspot del Samsung, conecta la TV a esa red y escribe la IP del hotspot (normalmente 192.168.43.x). Es el mismo Wi-Fi UDP/TCP, sin router.", color = Color(0xFF9AA0B0), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.openQrScanner() }) { Text("Escanear QR de la TV") }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP de la TV, ej. 192.168.1.20") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = pin, onValueChange = { pin = it.take(4) }, label = { Text("PIN de 4 dígitos") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.connectManual(host, 51999, pin) }) { Text("Conectar por IP / PIN") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.closeSettings() }) { Text("Cerrar ajustes") }
    }
}

/**
 * Escaneo QR real con CameraX + ML Kit Barcode Scanning.
 * Si la cámara no tiene permiso, se pide; si el usuario lo niega,
 * queda la entrada manual de IP/PIN. No hay QR simulado.
 */
@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    // Igual que SettingsScreen: pantalla completa OPACA. Antes el
    // PreviewView y el botón se emitían sueltos dentro del Box del
    // mando y se dibujaban uno encima del otro y sobre el mando.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
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
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) { imageProxy.close(); return@setAnalyzer }
                        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstOrNull()?.rawValue?.let { onResult(it) }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
            }
        }, modifier = Modifier.fillMaxWidth().height(320.dp))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose) { Text("Cancelar escaneo") }
    }
}
