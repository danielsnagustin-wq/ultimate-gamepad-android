package com.ultimate.gamepad.tv

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ultimate.gamepad.core.ControllerTestChecklist
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadExtraButtons
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
 * Pantalla de estado de la TV — rediseño premium (negro AMOLED,
 * acentos neón morado/cian, tarjetas, texto grande legible desde
 * el sofá). Misma información y mismas funciones que antes; el
 * diagnóstico detallado ("¿Cómo me ve Android?") queda colapsado
 * en "Ver detalles" en vez de volcado en la pantalla principal.
 *
 * Se actualiza cada 500 ms y muestra:
 * - Estado grande CONECTADO / DESCONECTADO con punto verde/rojo,
 *   transporte activo, último paquete hace X s, paquetes y perfil.
 *   (La TV no mide ping en ms: el ping lo mide el teléfono con el
 *   eco UDP. Aquí se muestra la frescura del último paquete y los
 *   paquetes/s, que es lo que la TV sí sabe.)
 * - Tarjeta "Conectar": IP y PIN grandes + QR real escaneable
 *   (payload UGP1|ip|puerto|pin, el mismo que lee el teléfono).
 * - Tarjeta Shizuku: estado + el paso siguiente en UNA frase.
 *   Sin Shizuku no hay mando virtual posible y la tarjeta lo dice
 *   claro. Shizuku NO puede ir dentro de la APK: su servidor corre
 *   como usuario shell (uid 2000) y solo arranca por ADB desde
 *   fuera; el cliente Shizuku 13.1.5 YA está dentro de la APK.
 * - Tarjeta "Parece mando real: SÍ / NO" con el motivo en claro.
 * - Última entrada EN VIVO: al pulsar A/B/X/Y o mover el stick en
 *   el teléfono, la TV lo muestra aquí. Si esto no cambia, el Wi-Fi
 *   no está llegando, aunque el teléfono diga "Conectado".
 * - Pasos 1-2-3: instala Shizuku → conecta el teléfono → juega.
 * - Botones INICIAR / DETENER receptor y PRUEBA DE MANDO (Fase 3).
 *
 * REGLA DE FALLO: si el Wi-Fi funciona pero ningún dispositivo
 * parece mando real, la pantalla dice "Inyección: FALLO — Wi-Fi
 * funciona pero no parece mando real". "Llegó el paquete" NO es
 * éxito. El éxito es que InputManager lo liste, getSources()
 * incluya GAMEPAD+JOYSTICK y OpenNOW lo liste como controller.
 */
class MainActivity : Activity() {

    // Paleta premium (igual lenguaje que el teléfono).
    private val black = Color.parseColor("#000000")
    private val cardBg = Color.parseColor("#12121C")
    private val cardStroke = Color.parseColor("#2E2E42")
    private val purple = Color.parseColor("#A855F7")
    private val purpleDark = Color.parseColor("#7C3AED")
    private val cyan = Color.parseColor("#22D3EE")
    private val green = Color.parseColor("#4ADE80")
    private val greenDark = Color.parseColor("#15803D")
    private val red = Color.parseColor("#F87171")
    private val amber = Color.parseColor("#FBBF24")
    private val greyText = Color.WHITE // Ítem 2 usuario: todo el texto blanco sobre negro.
    private val dimText = Color.WHITE // Ítem 2 usuario: también el texto secundario es blanco.

    private lateinit var statusCardBg: GradientDrawable
    private lateinit var statusDotView: TextView
    private lateinit var statusTitleView: TextView
    private lateinit var statusSubView: TextView
    private lateinit var connectIpView: TextView
    private lateinit var connectPinView: TextView
    private lateinit var qrImageView: ImageView
    private lateinit var qrPayloadView: TextView
    private lateinit var qrHintView: TextView
    private lateinit var shizukuStatusView: TextView
    private lateinit var shizukuNextView: TextView
    private lateinit var shizukuNoteView: TextView
    private lateinit var realTitleView: TextView
    private lateinit var realReasonView: TextView
    private lateinit var realInjectionView: TextView
    private lateinit var liveView: TextView
    private lateinit var step1StatusView: TextView
    private lateinit var step2StatusView: TextView
    private lateinit var step3StatusView: TextView
    private lateinit var detailsCard: LinearLayout
    private lateinit var detailsToggleBtn: Button
    private lateinit var diagView: TextView
    private var detailsVisible = false
    private var lastQrPayload: String? = null
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

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    /** Tarjeta redondeada oscura con borde fino. */
    private fun card(): LinearLayout {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(cardBg)
            setStroke(dp(2), cardStroke)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = bg
        }
    }

    private fun cardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(20) }

    private fun sectionLabel(text: String, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(0, 0, 0, dp(10))
        }

    private fun bigButton(text: String, tint: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(tint)
            minHeight = dp(76)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }

    private fun stepRow(number: String, title: String, statusView: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            val num = TextView(this@MainActivity).apply {
                text = number
                textSize = 26f
                setTypeface(null, Typeface.BOLD)
                setTextColor(purple)
                setPadding(0, 0, dp(18), 0)
            }
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 22f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                addView(statusView.apply { textSize = 20f })
            }
            addView(num)
            addView(col)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A4: el canal de notificación existe desde el primer arranque
        // (ver GamepadSenderService en el teléfono).
        UdpReceiverService.createNotificationChannel(this)
        pin = TvPairing.getOrCreatePin(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(28), dp(40), dp(28))
            setBackgroundColor(black)
        }

        // Cabecera.
        layout.addView(TextView(this).apply {
            text = "ULTIMATE GAMEPAD"
            textSize = 40f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        layout.addView(TextView(this).apply {
            text = "TV  ·  Tu teléfono es el mando  ·  GeForce NOW / OpenNOW"
            textSize = 20f
            setTextColor(greyText)
            setPadding(0, dp(4), 0, dp(20))
        })

        // ——— Estado grande ———
        val statusCard = card()
        statusCardBg = statusCard.background as GradientDrawable
        statusDotView = TextView(this).apply { text = "●"; textSize = 44f }
        statusTitleView = TextView(this).apply {
            textSize = 44f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, 0, 0)
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statusDotView)
            addView(statusTitleView)
        }
        statusSubView = TextView(this).apply { textSize = 20f; setTextColor(greyText) }
        statusCard.addView(statusRow)
        statusCard.addView(statusSubView)
        layout.addView(statusCard, cardParams())

        // ——— Fila: Conectar + Shizuku ———
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rowParams = cardParams()

        val connectCard = card().apply {
            addView(sectionLabel("CONECTAR", cyan))
            connectIpView = TextView(this@MainActivity).apply {
                textSize = 34f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            addView(connectIpView)
            connectPinView = TextView(this@MainActivity).apply {
                textSize = 34f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            addView(connectPinView)
            qrImageView = ImageView(this@MainActivity).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(Color.WHITE)
            }
            addView(qrImageView, LinearLayout.LayoutParams(dp(280), dp(280)).apply {
                topMargin = dp(16)
                bottomMargin = dp(12)
            })
            qrHintView = TextView(this@MainActivity).apply {
                text = "Escanea el QR con el teléfono, o escribe la IP y el PIN a mano."
                textSize = 18f
                setTextColor(greyText)
            }
            addView(qrHintView)
            qrPayloadView = TextView(this@MainActivity).apply {
                textSize = 16f
                setTextColor(dimText)
                setPadding(0, dp(8), 0, 0)
            }
            addView(qrPayloadView)
            addView(TextView(this@MainActivity).apply {
                text = "UDP ${GamepadProtocol.DEFAULT_PORT}  ·  TCP ${GamepadProtocol.DEFAULT_PORT + 1}"
                textSize = 18f
                setTextColor(greyText)
                setPadding(0, dp(8), 0, 0)
            })
        }
        row.addView(connectCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(10)
        })

        val shizukuCard = card().apply {
            addView(sectionLabel("SHIZUKU", purple))
            shizukuStatusView = TextView(this@MainActivity).apply {
                textSize = 32f
                setTypeface(null, Typeface.BOLD)
            }
            addView(shizukuStatusView)
            shizukuNextView = TextView(this@MainActivity).apply {
                textSize = 21f
                setTextColor(Color.WHITE)
                setPadding(0, dp(12), 0, 0)
            }
            addView(shizukuNextView)
            shizukuNoteView = TextView(this@MainActivity).apply {
                text = "Sin Shizuku no hay mando virtual posible: OpenNOW no verá ningún mando. No es un fallo de tu teléfono."
                textSize = 20f
                setTextColor(Color.WHITE) // Ítem 2 usuario: texto blanco; el ámbar queda solo para estados.
                setPadding(0, dp(12), 0, 0)
            }
            addView(shizukuNoteView)
        }
        row.addView(shizukuCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        layout.addView(row, rowParams)

        // ——— Parece mando real ———
        val realCard = card().apply {
            addView(sectionLabel("MANDO VIRTUAL", purple))
            realTitleView = TextView(this@MainActivity).apply {
                textSize = 44f
                setTypeface(null, Typeface.BOLD)
            }
            addView(realTitleView)
            realReasonView = TextView(this@MainActivity).apply {
                textSize = 21f
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, 0)
            }
            addView(realReasonView)
            realInjectionView = TextView(this@MainActivity).apply {
                textSize = 20f
                setTextColor(greyText)
                setPadding(0, dp(8), 0, 0)
            }
            addView(realInjectionView)
        }
        layout.addView(realCard, cardParams())

        // ——— Última entrada en vivo ———
        val liveCard = card().apply {
            addView(sectionLabel("ÚLTIMA ENTRADA — EN VIVO", cyan))
            liveView = TextView(this@MainActivity).apply {
                textSize = 22f
                setTextColor(Color.WHITE)
            }
            addView(liveView)
        }
        layout.addView(liveCard, cardParams())

        // ——— Pasos 1-2-3 ———
        step1StatusView = TextView(this)
        step2StatusView = TextView(this)
        step3StatusView = TextView(this)
        val stepsCard = card().apply {
            addView(sectionLabel("PARA JUGAR — 3 PASOS", purple))
            addView(stepRow("1", "Instala y arranca Shizuku en la TV", step1StatusView))
            addView(stepRow("2", "Conecta el teléfono con el QR o con IP + PIN", step2StatusView))
            addView(stepRow("3", "Abre OpenNOW y juega", step3StatusView))
        }
        layout.addView(stepsCard, cardParams())

        // ——— Botones grandes ———
        val buttonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startBtn = bigButton("▶  Iniciar receptor", greenDark) { startReceiver() }
        val stopBtn = bigButton("■  Detener receptor", Color.parseColor("#3A3A4A")) {
            // T1: soltar controles + destruir el mando virtual y
            // recordar "detenido" antes de parar los servicios.
            UdpReceiverService.stopAndRelease(this@MainActivity)
            stopService(Intent(this@MainActivity, UdpReceiverService::class.java))
            stopService(Intent(this@MainActivity, TcpReceiverService::class.java))
            refresh()
        }
        val testBtn = bigButton("🧪  Prueba de mando", purpleDark) { showTest() }
        buttonsRow.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        buttonsRow.addView(stopBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8); rightMargin = dp(8)
        })
        buttonsRow.addView(testBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        layout.addView(buttonsRow, cardParams())

        // ——— Diagnóstico detallado, colapsable ———
        detailsToggleBtn = bigButton("▸  Ver detalles: cómo me ve Android", Color.parseColor("#23232F")) {
            detailsVisible = !detailsVisible
            detailsCard.visibility = if (detailsVisible) View.VISIBLE else View.GONE
            detailsToggleBtn.text = if (detailsVisible) "▾  Ocultar detalles" else "▸  Ver detalles: cómo me ve Android"
            if (detailsVisible) refresh()
        }
        layout.addView(detailsToggleBtn, cardParams())
        detailsCard = card()
        diagView = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        detailsCard.addView(diagView)
        detailsCard.visibility = View.GONE
        layout.addView(detailsCard, cardParams())

        mainScroll = ScrollView(this).apply { addView(layout) }
        testScroll = buildTestLayout()
        setContentView(mainScroll)
        // Auto-arranque: al abrir la app de la TV el receptor ya
        // está escuchando (UDP 51999 + TCP 52000) y el teléfono
        // puede descubrirla por broadcast sin pulsar el botón.
        // T1: salvo que el usuario lo detuviera a mano: entonces
        // no vuelve solo hasta pulsar "Iniciar receptor".
        if (!UdpReceiverService.isReceiverStopped(this)) startReceiver()
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun startReceiver() {
        // T1: pulsar "Iniciar receptor" borra el "detenido".
        UdpReceiverService.setReceiverStopped(this, false)
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
            setPadding(dp(40), dp(28), dp(40), dp(28))
            setBackgroundColor(black)
        }
        val title = TextView(this).apply {
            text = "Prueba de mando — TV"
            textSize = 32f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(16))
        }
        testDeviceView = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        testLiveView = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE) }
        testChecklistView = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        testStagesView = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        testConfirmBtn = bigButton("Confirmo que funciona en un juego", purpleDark) {
            if (TvTestPrefs.getOpenNowConfirmedAtMs(this@MainActivity) > 0) {
                TvTestPrefs.clearOpenNowConfirmation(this@MainActivity)
            } else {
                TvTestPrefs.setOpenNowConfirmed(this@MainActivity, System.currentTimeMillis())
            }
            refreshTest()
        }
        val resetBtn = bigButton("Reiniciar prueba", Color.parseColor("#3A3A4A")) {
            testChecklist.reset(); refreshTest()
        }
        val backBtn = bigButton("Volver", Color.parseColor("#3A3A4A")) { showMain() }
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
        val stage2 = if (connected) "2. Receptor recibe: SÍ — último paquete decodificado hace ${if (agoSec < 0) "nunca" else "$agoSec s"}."
        else "2. Receptor recibe: NO — sin paquetes recientes (último hace ${if (agoSec < 0) "nunca" else "$agoSec s"})."
        val stage3 = if (Diagnostics.anyRealGamepad()) "3. Android registra: SÍ — ${reports.firstOrNull { it.looksLikeRealGamepad }?.name}."
        else "3. Android registra: NO — ningún dispositivo parece un mando real del perfil ${profile.name}."
        val confirmedAt = TvTestPrefs.getOpenNowConfirmedAtMs(this)
        val stage4 = if (confirmedAt > 0) {
            val whenText = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(confirmedAt))
            "4. OpenNOW recibe: SÍ — confirmado por ti el $whenText tras probar un juego."
        } else {
            "4. OpenNOW recibe: PENDIENTE — solo se marca tras probar un juego. Nada en la app puede comprobarlo desde dentro."
        }
        testStagesView.text = "Las 4 etapas\n$stage1\n$stage2\n(Las etapas 1 y 2 miran lo mismo: paquetes recientes, no el contador acumulado. Si hace más de 2 s que no llega nada, las dos pasan a NO.)\n$stage3\n$stage4"
        testConfirmBtn.text = if (confirmedAt > 0) "✓ Confirmado en un juego (tocar para quitar)" else "Confirmo que funciona en un juego"
    }

    private fun pressedNames(state: com.ultimate.gamepad.core.GamepadState): String {
        val names = mutableListOf<String>()
        if (state.isPressed(GamepadButtons.A)) names += "A"
        if (state.isPressed(GamepadButtons.B)) names += "B"
        if (state.isPressed(GamepadButtons.X)) names += "X"
        if (state.isPressed(GamepadButtons.Y)) names += "Y"
        if (state.isPressed(GamepadButtons.LB)) names += "LB"
        if (state.isPressed(GamepadButtons.RB)) names += "RB"
        if (state.isPressed(GamepadButtons.L3)) names += "L3"
        if (state.isPressed(GamepadButtons.R3)) names += "R3"
        if (state.isPressed(GamepadButtons.HOME)) names += "HOME"
        if (state.isPressed(GamepadButtons.CAPTURE)) names += "CAPTURE"
        if (state.isExtraPressed(GamepadExtraButtons.L4)) names += "L4"
        if (state.isExtraPressed(GamepadExtraButtons.R4)) names += "R4"
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

    /** El paso siguiente con Shizuku, en UNA frase clara. */
    private fun shizukuNextStep(status: ShizukuHelper.Status): String = when {
        status.ready -> "Listo: el mando virtual ya se puede crear. No tienes que hacer nada más aquí."
        status.label.contains("NO INSTALADO") -> "Siguiente paso: instala la app Shizuku (gratis) en la TV y arráncala una vez con Bugjaeger por ADB :5555 desde el teléfono. Dura hasta reiniciar la TV."
        status.label.contains("NO ACTIVO") -> "Siguiente paso: arranca Shizuku con Bugjaeger por ADB :5555 desde el teléfono. Dura hasta reiniciar la TV."
        status.label.contains("SIN PERMISO") -> "Siguiente paso: abre Shizuku en la TV y permite Ultimate Gamepad TV."
        else -> status.guidance
    }

    /** QR real (ZXing) del payload UGP1|ip|puerto|pin; null si no se puede generar. */
    private fun makeQrBitmap(payload: String, sizePx: Int): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()

    private fun refresh() {
        val wifi = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ip = ShizukuHelper.localIpFromWifi(wifi) ?: "sin Wi-Fi"
        val connected = UdpReceiverService.isConnected()
        val agoSec = if (UdpReceiverService.lastPacketAtMs > 0) (System.currentTimeMillis() - UdpReceiverService.lastPacketAtMs) / 1000 else -1
        val profile = GamepadProfiles.fromId(UdpReceiverService.activeProfileId)
        val reports = Diagnostics.scan()
        val real = reports.any { it.looksLikeRealGamepad }
        val shizuku = shizukuStatusCached()

        // ——— Estado grande ———
        statusDotView.setTextColor(if (connected) green else red)
        statusTitleView.text = if (connected) "CONECTADO" else "DESCONECTADO"
        statusCardBg.setStroke(dp(2), if (connected) green else red)
        statusSubView.text = "${UdpReceiverService.activeTransport.label}  ·  " +
            "Último paquete: ${if (agoSec < 0) "nunca" else "hace $agoSec s"}  ·  " +
            "${UdpReceiverService.packetsReceived} paquetes  ·  ${UdpReceiverService.packetsPerSecond}/s\n" +
            "Perfil: ${profile.name} (${profile.deviceName})"

        // ——— Conectar: IP, PIN y QR real ———
        connectIpView.text = "IP  $ip"
        connectPinView.text = "PIN  $pin"
        val qrPayload = "${GamepadProtocol.PAIRING_PREFIX}|$ip|${GamepadProtocol.DEFAULT_PORT}|$pin"
        if (ip == "sin Wi-Fi") {
            qrImageView.visibility = View.GONE
            qrHintView.text = "Conecta la TV al Wi-Fi para ver el QR."
            qrPayloadView.text = ""
            lastQrPayload = null
        } else {
            qrHintView.text = "Escanea el QR con el teléfono, o escribe la IP y el PIN a mano."
            qrPayloadView.text = qrPayload
            if (qrPayload != lastQrPayload) {
                lastQrPayload = qrPayload
                val bmp = makeQrBitmap(qrPayload, 360)
                if (bmp != null) {
                    qrImageView.setImageBitmap(bmp)
                    qrImageView.visibility = View.VISIBLE
                } else {
                    qrImageView.visibility = View.GONE
                }
            }
        }

        // ——— Shizuku ———
        if (ReceiverConfig.SHIZUKU_PHASE2_ENABLED) {
            shizukuStatusView.text = "Shizuku: ${shizuku.label}"
            shizukuStatusView.setTextColor(if (shizuku.ready) green else amber)
            shizukuNextView.text = shizukuNextStep(shizuku)
            shizukuNoteView.visibility = if (shizuku.ready) View.GONE else View.VISIBLE
        } else {
            shizukuStatusView.text = "Shizuku: no conectado / segunda opción (Fase 2)"
            shizukuStatusView.setTextColor(amber)
            shizukuNextView.text = ""
            shizukuNoteView.visibility = View.GONE
        }

        // ——— Parece mando real ———
        val device = reports.firstOrNull { it.looksLikeRealGamepad }
            ?: reports.firstOrNull { it.name.equals(profile.deviceName, ignoreCase = true) }
            ?: reports.firstOrNull()
        realTitleView.text = "Parece mando real: ${if (real) "SÍ" else "NO"}"
        realTitleView.setTextColor(if (real) green else red)
        realReasonView.text = when {
            device == null -> "Motivo: Android no detecta ningún dispositivo de entrada."
            else -> "Motivo: ${device.looksLikeRealReason}"
        }
        val injection = UdpReceiverService.injectionMethod
        realInjectionView.text = when {
            real -> "Inyección activa: $injection"
            connected -> "Inyección: FALLO. Wi-Fi funciona pero Android no ve un mando real (ver detalles). OpenNOW no lo usará para jugar."
            else -> "Inyección: $injection (sin conexión Wi-Fi todavía)"
        }

        // ——— Última entrada en vivo ———
        val s = UdpReceiverService.lastState
        liveView.text = "Botones: ${pressedNames(s)}\n" +
            "Stick izq: (${"%.2f".format(s.leftX)}, ${"%.2f".format(s.leftY)})   " +
            "Stick der: (${"%.2f".format(s.rightX)}, ${"%.2f".format(s.rightY)})   " +
            "LT: ${"%.2f".format(s.leftTrigger)}  RT: ${"%.2f".format(s.rightTrigger)}\n" +
            "Paquetes: ${UdpReceiverService.packetsReceived} · ${UdpReceiverService.packetsPerSecond}/s · perdidos (huecos de secuencia): ${UdpReceiverService.packetsLost}"

        // ——— Pasos 1-2-3 ———
        step1StatusView.text = if (shizuku.ready) "✓ Listo" else "○ Pendiente"
        step1StatusView.setTextColor(if (shizuku.ready) green else amber)
        step2StatusView.text = if (connected) "✓ Conectado" else "○ Pendiente"
        step2StatusView.setTextColor(if (connected) green else amber)
        step3StatusView.text = if (real) "✓ Android ve el mando" else "○ Pendiente: Android aún no ve el mando"
        step3StatusView.setTextColor(if (real) green else amber)

        // ——— Detalles (solo se renderizan abiertos) ———
        if (detailsVisible) {
            val details = StringBuilder(Diagnostics.render())
            details.append("\n\nShizuku: ${shizuku.label}\n${shizuku.guidance}")
            val lastError = UdpReceiverService.lastError
            if (!lastError.isNullOrBlank()) details.append("\n\nÚltimo error: $lastError")
            diagView.text = details.toString()
        }
        if (showingTest) refreshTest()
    }
}
