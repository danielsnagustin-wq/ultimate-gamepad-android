package com.ultimate.gamepad.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ultimate.gamepad.core.ControllerTestChecklist
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.tv.receiver.Diagnostics
import com.ultimate.gamepad.tv.receiver.ReceiverConfig
import com.ultimate.gamepad.tv.receiver.TcpReceiverService
import com.ultimate.gamepad.tv.receiver.TvTestPrefs
import com.ultimate.gamepad.tv.receiver.UdpReceiverService
import com.ultimate.gamepad.tv.shizuku.ShizukuHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de estado de la TV — Fase 1 visible + diagnóstico de mando real.
 *
 * Se actualiza cada 500 ms y muestra:
 * - IP, puerto UDP 51999 / TCP 52000, PIN/QR en texto.
 * - Transporte activo, conectado sí/no (por silencio > 2 s), último
 *   paquete hace X s, paquetes recibidos, ping (eco UDP).
 * - Última entrada EN VIVO: al pulsar A/B/X/Y o mover el stick en
 *   el teléfono, la TV lo muestra aquí. Si esto no cambia, el Wi-Fi
 *   no está llegando, aunque el teléfono diga "Conectado".
 * - Shizuku: no conectado / segunda opción (Fase 2) cuando no está
 *   activo. Inyección activa: uinput / inject / ninguno.
 * - Diagnóstico "¿Cómo me ve Android?" con "Parece mando real:
 *   SÍ / NO" por dispositivo.
 *
 * REGLA DE FALLO: si el Wi-Fi funciona pero ningún dispositivo
 * parece mando real, la pantalla dice "Inyección: FALLO — Wi-Fi
 * funciona pero no parece mando real". "Llegó el paquete" NO es
 * éxito. El éxito es que InputManager lo liste, getSources()
 * incluya GAMEPAD+JOYSTICK y OpenNOW lo liste como controller.
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var liveView: TextView
    private lateinit var shizukuView: TextView
    private lateinit var gamepadView: TextView
    private lateinit var diagView: TextView
    private lateinit var pin: String // Persistente (TvPairing): el mismo en cada apertura.
    // PRUEBA DE MANDO (Fase 3): segunda vista conmutable desde
    // esta misma Activity (la TV no usa Compose). La checklist se
    // alimenta del último estado recibido por red, no de
    // /dev/input: Android no expone valores en vivo del
    // dispositivo por API y la pantalla lo dice así.
    private val testChecklist = ControllerTestChecklist()
    private var showingTest = false
    private lateinit var mainScroll: ScrollView
    private lateinit var testScroll: ScrollView
    private lateinit var testDeviceView: TextView
    private lateinit var testLiveView: TextView
    private lateinit var testChecklistView: TextView
    private lateinit var testStagesView: TextView
    private lateinit var testConfirmBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pin = TvPairing.getOrCreatePin(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#0B0E14"))
        }
        val title = TextView(this).apply { text = "Ultimate Gamepad — TV"; textSize = 28f; setTextColor(Color.WHITE) }
        statusView = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        liveView = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE) }
        shizukuView = TextView(this).apply { textSize = 18f; setTextColor(Color.YELLOW) }
        gamepadView = TextView(this).apply { textSize = 20f; setTextColor(Color.YELLOW) }
        diagView = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        val startBtn = Button(this).apply {
            text = "Iniciar receptor (UDP 51999 + TCP 52000)"
            setOnClickListener { startReceiver() }
        }
        val stopBtn = Button(this).apply {
            text = "Detener receptor"
            setOnClickListener {
                stopService(Intent(this@MainActivity, UdpReceiverService::class.java))
                stopService(Intent(this@MainActivity, TcpReceiverService::class.java))
                refresh()
            }
        }
        val testBtn = Button(this).apply {
            text = "🧪  Prueba de mando"
            setOnClickListener { showTest() }
        }
        layout.addView(title); layout.addView(statusView); layout.addView(liveView)
        layout.addView(shizukuView); layout.addView(gamepadView); layout.addView(diagView)
        layout.addView(startBtn); layout.addView(stopBtn); layout.addView(testBtn)
        mainScroll = ScrollView(this).apply { addView(layout) }
        testScroll = buildTestLayout()
        setContentView(mainScroll)
        // Auto-arranque: al abrir la app de la TV el receptor ya
        // está escuchando (UDP 51999 + TCP 52000) y el teléfono
        // puede descubrirla por broadcast sin pulsar el botón.
        startReceiver()
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun startReceiver() {
        startForegroundService(Intent(this, UdpReceiverService::class.java))
        refresh()
    }

    private fun showTest() {
        showingTest = true
        setContentView(testScroll)
        refreshTest()
    }

    private fun showMain() {
        showingTest = false
        setContentView(mainScroll)
        refresh()
    }

    /**
     * PRUEBA DE MANDO (Fase 3) — vista de la TV.
     *
     * - Dispositivo virtual actual leído del sistema (nombre, id,
     *   vendor/product, ejes y botones con hasKeys).
     * - Checklist interactiva por control, con valores analógicos
     *   en vivo del ÚLTIMO PAQUETE RECIBIDO POR RED (no del
     *   dispositivo: Android no expone valores en vivo por API y
     *   no se finge una lectura de /dev/input).
     * - Las 4 etapas con veredicto propio. La 4ª (OpenNOW) SOLO
     *   se marca con el botón manual tras probar un juego; la
     *   confirmación se guarda (TvTestPrefs) y nunca se marca sola.
     */
    private fun buildTestLayout(): ScrollView {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#0B0E14"))
        }
        val title = TextView(this).apply { text = "Prueba de mando — TV"; textSize = 28f; setTextColor(Color.WHITE) }
        testDeviceView = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        testLiveView = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE) }
        testChecklistView = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        testStagesView = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        testConfirmBtn = Button(this).apply {
            text = "Confirmo que funciona en un juego"
            setOnClickListener {
                if (TvTestPrefs.getOpenNowConfirmedAtMs(this@MainActivity) > 0) {
                    TvTestPrefs.clearOpenNowConfirmation(this@MainActivity)
                } else {
                    TvTestPrefs.setOpenNowConfirmed(this@MainActivity, System.currentTimeMillis())
                }
                refreshTest()
            }
        }
        val resetBtn = Button(this).apply {
            text = "Reiniciar prueba"
            setOnClickListener { testChecklist.reset(); refreshTest() }
        }
        val backBtn = Button(this).apply {
            text = "Volver"
            setOnClickListener { showMain() }
        }
        layout.addView(title); layout.addView(testDeviceView); layout.addView(testLiveView)
        layout.addView(testChecklistView); layout.addView(testStagesView)
        layout.addView(testConfirmBtn); layout.addView(resetBtn); layout.addView(backBtn)
        return ScrollView(this).apply { addView(layout) }
    }

    private fun refreshTest() {
        testChecklist.update(UdpReceiverService.lastState)
        val profile = GamepadProfiles.fromId(UdpReceiverService.activeProfileId)
        val reports = Diagnostics.scan()
        val device = reports.firstOrNull { it.looksLikeRealGamepad }
            ?: reports.firstOrNull { it.name.equals(profile.deviceName, ignoreCase = true) }
        testDeviceView.text = if (device == null) {
            "Dispositivo virtual actual: ninguno detectado como ${profile.name} (${profile.deviceName}).\n" +
                "Conecta el teléfono y revisa el diagnóstico de la pantalla principal."
        } else {
            "Dispositivo virtual actual\n" +
                "[${device.id}] ${device.name}\n" +
                "vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)}\n" +
                "sources: ${device.sourcesText}\n" +
                "ejes: ${if (device.axes.isEmpty()) "ninguno" else device.axes.joinToString(", ")}\n" +
                "botones (según el sistema): ${if (device.buttons.isEmpty()) "ninguno detectado" else device.buttons.joinToString(", ")}\n" +
                "${device.verdict}\n" +
                "Parece mando real: ${if (device.looksLikeRealGamepad) "SÍ" else "NO"} — ${device.looksLikeRealReason}"
        }
        val s = UdpReceiverService.lastState
        testLiveView.text = "En vivo — valores del ÚLTIMO PAQUETE RECIBIDO POR RED\n" +
            "(Android no expone valores en vivo del dispositivo por API; no se lee /dev/input)\n" +
            "Botones: ${pressedNames(s)}  " +
            "Stick izq: (${"%.2f".format(s.leftX)}, ${"%.2f".format(s.leftY)})  " +
            "Stick der: (${"%.2f".format(s.rightX)}, ${"%.2f".format(s.rightY)})  " +
            "LT: ${"%.2f".format(s.leftTrigger)} RT: ${"%.2f".format(s.rightTrigger)}\n" +
            "Paquetes: ${UdpReceiverService.packetsReceived} · ${UdpReceiverService.packetsPerSecond}/s · perdidos (huecos de secuencia): ${UdpReceiverService.packetsLost}"
        val items = testChecklist.items(UdpReceiverService.activeProfileId)
        testChecklistView.text = "Checklist — ${testChecklist.testedCount}/${testChecklist.totalCount} controles probados\n" +
            items.joinToString("\n") { (if (it.tested) "✓ " else "○ ") + it.label }
        val connected = UdpReceiverService.isConnected()
        val agoSec = if (UdpReceiverService.lastPacketAtMs > 0) (System.currentTimeMillis() - UdpReceiverService.lastPacketAtMs) / 1000 else -1
        val stage1 = if (connected) "1. Teléfono envía: SÍ — ${UdpReceiverService.packetsReceived} paquetes, último hace $agoSec s."
        else "1. Teléfono envía: NO — sin paquetes recientes."
        val stage2 = if (UdpReceiverService.packetsReceived > 0) "2. Receptor recibe: SÍ — último paquete decodificado hace ${if (agoSec < 0) "nunca" else "$agoSec s"}."
        else "2. Receptor recibe: NO — sin paquetes todavía."
        val stage3 = if (Diagnostics.anyRealGamepad()) "3. Android registra: SÍ — ${reports.firstOrNull { it.looksLikeRealGamepad }?.name}."
        else "3. Android registra: NO — ningún dispositivo parece un mando real del perfil ${profile.name}."
        val confirmedAt = TvTestPrefs.getOpenNowConfirmedAtMs(this)
        val stage4 = if (confirmedAt > 0) {
            val whenText = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(confirmedAt))
            "4. OpenNOW recibe: SÍ — confirmado por ti el $whenText tras probar un juego."
        } else {
            "4. OpenNOW recibe: PENDIENTE — solo se marca tras probar un juego. Nada en la app puede comprobarlo desde dentro."
        }
        testStagesView.text = "Las 4 etapas\n$stage1\n$stage2\n(Las etapas 1 y 2 comparten el mismo contador: si la TV ve paquetes decodificados, el teléfono envía y el receptor recibe.)\n$stage3\n$stage4"
        testConfirmBtn.text = if (confirmedAt > 0) "✓ Confirmado en un juego (tocar para quitar)" else "Confirmo que funciona en un juego"
    }

    private fun pressedNames(state: com.ultimate.gamepad.core.GamepadState): String {
        val names = mutableListOf<String>()
        if (state.isPressed(GamepadButtons.A)) names += "A"
        if (state.isPressed(GamepadButtons.B)) names += "B"
        if (state.isPressed(GamepadButtons.X)) names += "X"
        if (state.isPressed(GamepadButtons.Y)) names += "Y"
        if (state.isPressed(GamepadButtons.START)) names += "START"
        if (state.isPressed(GamepadButtons.SELECT)) names += "SELECT"
        if (state.isPressed(GamepadButtons.DPAD_UP)) names += "↑"
        if (state.isPressed(GamepadButtons.DPAD_DOWN)) names += "↓"
        if (state.isPressed(GamepadButtons.DPAD_LEFT)) names += "←"
        if (state.isPressed(GamepadButtons.DPAD_RIGHT)) names += "→"
        return if (names.isEmpty()) "ninguno" else names.joinToString(" ")
    }

    // Estado de Shizuku cacheado: refresh() corre cada 500 ms y
    // ShizukuHelper.status() hace PackageManager + ping al binder
    // en cada llamada. El estado cambia pocas veces (el usuario
    // arranca o para Shizuku a mano), así que basta con mirarlo
    // cada 5 s.
    private var lastShizukuStatus: ShizukuHelper.Status? = null
    private var lastShizukuCheckAtMs: Long = 0L

    private fun shizukuStatusCached(): ShizukuHelper.Status {
        val now = System.currentTimeMillis()
        val cached = lastShizukuStatus
        if (cached != null && now - lastShizukuCheckAtMs < 5000) return cached
        val fresh = ShizukuHelper.status(this)
        lastShizukuStatus = fresh
        lastShizukuCheckAtMs = now
        return fresh
    }

    private fun refresh() {
        val wifi = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ip = ShizukuHelper.localIpFromWifi(wifi) ?: "sin Wi-Fi"
        val connected = UdpReceiverService.isConnected()
        val agoSec = if (UdpReceiverService.lastPacketAtMs > 0) (System.currentTimeMillis() - UdpReceiverService.lastPacketAtMs) / 1000 else -1
        statusView.text = "IP: $ip  UDP: ${GamepadProtocol.DEFAULT_PORT}  TCP: ${GamepadProtocol.DEFAULT_PORT + 1}\n" +
            "PIN: $pin  QR: UGP1|$ip|${GamepadProtocol.DEFAULT_PORT}|$pin\n" +
            "Transporte activo: ${UdpReceiverService.activeTransport.label}\n" +
            "Conectado: ${if (connected) "SÍ" else "NO"}  Último paquete: ${if (agoSec < 0) "nunca" else "hace $agoSec s"}  Paquetes: ${UdpReceiverService.packetsReceived}"
        val s = UdpReceiverService.lastState
        liveView.text = "Última entrada — botones: ${pressedNames(s)}  " +
            "Stick izq: (${"%.2f".format(s.leftX)}, ${"%.2f".format(s.leftY)})  " +
            "Stick der: (${"%.2f".format(s.rightX)}, ${"%.2f".format(s.rightY)})  " +
            "LT: ${"%.2f".format(s.leftTrigger)} RT: ${"%.2f".format(s.rightTrigger)}"
        val shizuku = shizukuStatusCached()
        shizukuView.text = if (ReceiverConfig.SHIZUKU_PHASE2_ENABLED) {
            "Shizuku: ${shizuku.label}\n${shizuku.guidance}"
        } else {
            "Shizuku: no conectado / segunda opción (Fase 2)"
        }
        shizukuView.setTextColor(if (shizuku.ready) Color.GREEN else Color.YELLOW)
        val real = Diagnostics.anyRealGamepad()
        val injection = UdpReceiverService.injectionMethod
        gamepadView.text = when {
            real -> "Parece mando real: SÍ — Inyección activa: $injection"
            connected -> "Parece mando real: NO — Inyección: FALLO. Wi-Fi funciona pero Android no ve un mando real (ver diagnóstico). OpenNOW no lo usará para jugar."
            else -> "Parece mando real: NO — Inyección: $injection (sin conexión Wi-Fi todavía)"
        }
        gamepadView.setTextColor(if (real) Color.GREEN else Color.RED)
        diagView.text = Diagnostics.render()
        if (showingTest) refreshTest()
    }
}
