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
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.tv.receiver.Diagnostics
import com.ultimate.gamepad.tv.receiver.ReceiverConfig
import com.ultimate.gamepad.tv.receiver.TcpReceiverService
import com.ultimate.gamepad.tv.receiver.UdpReceiverService
import com.ultimate.gamepad.tv.shizuku.ShizukuHelper

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
        layout.addView(title); layout.addView(statusView); layout.addView(liveView)
        layout.addView(shizukuView); layout.addView(gamepadView); layout.addView(diagView)
        layout.addView(startBtn); layout.addView(stopBtn)
        setContentView(ScrollView(this).apply { addView(layout) })
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
    }
}
