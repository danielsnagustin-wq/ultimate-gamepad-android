package com.ultimate.gamepad.phone.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultimate.gamepad.core.DiscoveredTv
import com.ultimate.gamepad.core.Discovery
import com.ultimate.gamepad.core.ExtrasProtocol
import com.ultimate.gamepad.core.GameProfile
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadExtraButtons
import com.ultimate.gamepad.core.GamepadMacro
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.GyroMapper
import com.ultimate.gamepad.core.HidAvailability
import com.ultimate.gamepad.core.KeyMap
import com.ultimate.gamepad.core.MacroRecorder
import com.ultimate.gamepad.core.PairingInfo
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportSelector
import com.ultimate.gamepad.core.TransportStatus
import com.ultimate.gamepad.core.Turbo
import com.ultimate.gamepad.core.TurboConfig
import com.ultimate.gamepad.phone.network.BluetoothDataTransport
import com.ultimate.gamepad.phone.network.BluetoothHidService
import com.ultimate.gamepad.phone.network.DiscoveryClient
import com.ultimate.gamepad.phone.network.NsdDiscovery
import kotlinx.coroutines.async
import com.ultimate.gamepad.phone.network.ExtrasSender
import com.ultimate.gamepad.phone.network.GamepadProfilePrefs
import com.ultimate.gamepad.phone.network.GamepadSender
import com.ultimate.gamepad.phone.network.GamepadSenderService
import com.ultimate.gamepad.phone.network.LastConnectionPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.sqrt

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Modo de selección: AUTO prueba UDP -> TCP -> BLE/SPP -> HID. Manual fuerza un método. */
enum class TransportMode { AUTO, MANUAL }

data class GamepadUiState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val host: String = "",
    val port: Int = 51999,
    val pin: String = "",
    val pingMs: Long? = null,
    val tvResponding: Boolean = false,
    /**
     * Paquetes de mando recibidos por la TV (del eco de ping
     * estampado, ver GamepadSenderService.tvPacketsReceived).
     * Es lo que distingue "conectado pero no envía nada" en dos
     * casos: TV no recibe (red) vs TV recibe pero no inyecta
     * (falta Shizuku). Se actualiza con refreshPing().
     */
    val tvPacketsReceived: Long = 0,
    val tvReceivingPackets: Boolean = false,
    /** Momento en que empezó el intento actual (para el timeout sin eco). */
    val connectingSinceMs: Long = 0L,
    /** Método que se está probando en este intento (se muestra en "Conectando por…"). */
    val attemptMethod: TransportMethod = TransportMethod.WIFI_UDP,
    val error: String? = null,
    /** Mensaje informativo (no es un error): se pinta en verde, no en rojo. */
    val info: String? = null,
    val showSettings: Boolean = false,
    val showLayoutEditor: Boolean = false,
    val showQrScanner: Boolean = false,
    // Pantallas Fase 3 (TIPO DE MANDO, Perfiles, Prueba, Rendimiento).
    val showControllerType: Boolean = false,
    val showGameProfiles: Boolean = false,
    val showControllerTest: Boolean = false,
    val showPerformance: Boolean = false,
    // Pantalla Fase 4 (Extras: turbo, macros, vibración, giroscopio, teclado, ratón).
    val showExtras: Boolean = false,
    // Pantalla "Personalizar interfaz" (ítem 3: acento, tamaño de texto, fondo).
    val showUiCustomization: Boolean = false,
    /** Teclado virtual pedido a la TV (dispositivo uinput aparte). P8: es "enviado, sin confirmar": la TV no devuelve acuse de los extras (ver setKeyboardEnabled). */
    val keyboardEnabled: Boolean = false,
    /** Modo ratón pedido a la TV (dispositivo uinput aparte). P8: "enviado, sin confirmar" (ver setMouseEnabled). */
    val mouseEnabled: Boolean = false,
    /** Perfil de emulación activo (visual + emulación a la vez). */
    val activeProfileId: Int = GamepadProfiles.XBOX_360_ID,
    /** Perfil anterior, para "Volver al perfil anterior". */
    val previousProfileId: Int? = null,
    /** Nombre del perfil de juego aplicado (si hay uno). */
    val activeGameProfileName: String? = null,
    // Rendimiento (Fase 3): se actualizan con refreshPerformance().
    val pingJitterMs: Long? = null,
    val packetsPerSec: Int = 0,
    val packetsSent: Long = 0,
    // Descubrimiento de TVs en la red (Ajustes > Buscar TV).
    val discovering: Boolean = false,
    val discoveredTvs: List<DiscoveredTv> = emptyList(),
    /** ¿El teléfono está en Wi-Fi? Sin Wi-Fi no hay TV que descubrir. */
    val wifiConnected: Boolean = true,
    // Selector multi-transporte (Etapa 1).
    val transportMode: TransportMode = TransportMode.AUTO,
    val selectedMethod: TransportMethod = TransportMethod.WIFI_UDP,
    val transportStatus: Map<TransportMethod, TransportStatus> = mapOf(
        // P-B: Wi-Fi nace SIN COMPROBAR (antes AVAILABLE sin
        // comprobar nada): refreshWifiState() lo resuelve a
        // AVAILABLE con Wi-Fi/hotspot o UNAVAILABLE sin red.
        TransportMethod.WIFI_UDP to TransportStatus.UNAVAILABLE,
        TransportMethod.WIFI_TCP to TransportStatus.UNAVAILABLE,
        TransportMethod.BLUETOOTH_DATA to TransportStatus.UNAVAILABLE,
        TransportMethod.BLUETOOTH_HID to TransportStatus.UNAVAILABLE,
    ),
    val hidAvailability: HidAvailability = HidAvailability.UNKNOWN,
    /** TVs emparejadas por Bluetooth (Fase 5): las rellena refreshBtDataDevices(). */
    val btDataDevices: List<BluetoothDataTransport.BondedBtDevice> = emptyList(),
    /** Motivo real del estado de Bluetooth datos (Fase 5): lo deja refreshBtDataStatus(). */
    val btDataStatusDetail: String? = null,
)

class GamepadViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(GamepadUiState())
    val ui: StateFlow<GamepadUiState> = _ui.asStateFlow()
    private val hidService = BluetoothHidService(application)

    /** Transporte Bluetooth datos (SPP/RFCOMM, Fase 5): instancia solo para disponibilidad/lista de emparejados; el envío lo hace la instancia del servicio. */
    private val btDataTransport = BluetoothDataTransport(application)

    /** Re-registro HID en segundo plano (caída de Bluetooth de la TV). */
    private var hidRetryJob: Job? = null

    /**
     * Espera de 3 s de connectHid() (comprobación HID en curso,
     * A6: igual al timeout de refreshHidStatus, para que un
     * firmware que responde entre 2,5-3 s no se vea como ERROR
     * aunque el HID esté disponible).
     * Sin guardar el Job, desconectar o cambiar de método no la
     * cancelaba y disparaba registerHid() o ERROR después de que
     * el intento ya había terminado: CONECTADO fantasma o ERROR
     * sobre un estado nuevo. Se cancela en disconnect() y antes
     * de cada connectHid() nuevo.
     */
    private var hidCheckJob: Job? = null

    /** Callback de red (cambio/pérdida de Wi-Fi): se desregistra en onCleared. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Receptor de encendido/apagado del Bluetooth: se desregistra en onCleared. */
    private var bluetoothReceiver: BroadcastReceiver? = null

    /** Reenvío HID periódico (10 Hz) del último estado mientras el HID está conectado (ver startHidHeartbeat). */
    private var hidHeartbeatJob: Job? = null

    // --- Extras Fase 4 (estado; van ANTES del init porque el init
    // los lee: en Kotlin los inicializadores corren en orden textual
    // y un init que leyera un flow declarado después lo encontraría
    // sin inicializar). ---
    /** Botones con turbo (Fase 4, ExtrasPrefs). */
    private val _turboButtons = MutableStateFlow(ExtrasPrefs.loadTurbo(application).buttons)
    val turboButtons: StateFlow<Set<Int>> = _turboButtons.asStateFlow()
    /** Velocidad del turbo en Hz (10 por defecto). */
    private val _turboRateHz = MutableStateFlow(ExtrasPrefs.loadTurbo(application).rateHz)
    val turboRateHz: StateFlow<Int> = _turboRateHz.asStateFlow()
    /** Vibrar al pulsar en el teléfono (Fase 4). */
    private val _vibrationEnabled = MutableStateFlow(ExtrasPrefs.loadVibration(application))
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()
    /** Macros guardadas (Fase 4). */
    private val _macros = MutableStateFlow(ExtrasPrefs.loadMacros(application))
    val macros: StateFlow<List<GamepadMacro>> = _macros.asStateFlow()
    /** Nombre de la macro que se está grabando (null = no se graba). */
    private val _recordingMacro = MutableStateFlow<String?>(null)
    val recordingMacro: StateFlow<String?> = _recordingMacro.asStateFlow()
    /** P-A: progreso en vivo de la grabacion (elapsedMs, eventos) para la UI. */
    private val _recordingMacroProgress = MutableStateFlow<Pair<Long, Int>?>(null)
    val recordingMacroProgress: StateFlow<Pair<Long, Int>?> = _recordingMacroProgress.asStateFlow()
    /** Nombre de la macro que se está reproduciendo (null = ninguna). */
    private val _playingMacro = MutableStateFlow<String?>(null)
    val playingMacro: StateFlow<String?> = _playingMacro.asStateFlow()
    /** Giroscopio activado (Fase 4): la inclinación mueve el stick derecho. */
    private val _gyroEnabled = MutableStateFlow(ExtrasPrefs.loadGyroEnabled(application))
    val gyroEnabled: StateFlow<Boolean> = _gyroEnabled.asStateFlow()
    /** Sensibilidad del giroscopio (0.5..2; 1.0 = 45° recorre el stick). */
    private val _gyroSensitivity = MutableStateFlow(ExtrasPrefs.loadGyroSensitivity(application))
    val gyroSensitivity: StateFlow<Float> = _gyroSensitivity.asStateFlow()

    /** Fase del pulso de turbo (true = botón activo, false = soltado). */
    private var turboPhaseOn: Boolean = true
    /** La app está en primer plano (RESUMED): en segundo plano el giroscopio no publica. */
    private var appForegrounded: Boolean = true
    private var turboJob: Job? = null
    private var macroRecorder: MacroRecorder? = null
    private var macroPlayJob: Job? = null
    /** P5: seguimiento del tope de macro en el ViewModel (el
     * MacroRecorder de core lo aplica en silencio: onState()
     * deja de anotar pasado 30 s / 200 eventos sin avisar).
     * Se espeja su conteo de cambios para avisar en ui.error
     * cuando se alcanza el tope; sin tocar Macros.kt. */
    private var macroRecordStartMs: Long = 0L
    private var macroRecordedChanges: Int = 0
    private var macroLastButtons: Int = 0
    private var macroLastExtra: Int = 0
    private var macroCapWarned: Boolean = false
    /** Stick derecho que manda el giroscopio (Fase 4); publish() lo usa en vez del táctil cuando el giro está activado. */
    private var gyroX: Float = 0f
    private var gyroY: Float = 0f
    private var gyroListener: SensorEventListener? = null
    /** Envío UDP de extras (teclado/ratón) a la TV. */
    private val extrasSender = ExtrasSender()
    /** Botones del ratón mantenidos (bitmask de ExtrasProtocol). */
    private var mouseButtons: Int = 0

    init {
        // La caída de Bluetooth llegaba solo al servicio y nadie se
        // enteraba: ahora el ViewModel reacciona (fallback a Wi-Fi
        // + reintento en segundo plano). Ver onHidConnectionChanged().
        hidService.onConnectionChanged = { status -> onHidConnectionChanged(status) }
        // Cambio de red (2026-11-07): sin esto, al cambiar de Wi-Fi
        // o al activar el hotspot, el estado de red solo se
        // refrescaba al reabrir la pantalla; ahora reacciona solo.
        registerNetworkCallback()
        // El Bluetooth puede encenderse después de abrir la app:
        // sin receptor, la comprobación HID se quedaba en
        // "No disponible" para siempre (foto del usuario 2026-11-07).
        registerBluetoothStateReceiver()
        // Perfil guardado (Fase 3): el emisor en proceso debe llevar
        // el mismo que el guardado, no el 0 por defecto, o el primer
        // paquete tras arrancar saldría como Xbox 360 con otro perfil
        // activo en la UI.
        GamepadSender.loadSavedProfile(application)
        val savedProfile = GamepadProfilePrefs.load(application)
        _ui.update {
            it.copy(
                activeProfileId = savedProfile,
                previousProfileId = GamepadProfilePrefs.loadPrevious(application),
            )
        }
        // Último perfil de juego aplicado: se reaplican sus ajustes
        // (mando, zona muerta, curva, gatillos, remap, diseño) en
        // silencio al arrancar, sin anunciar nada.
        GameProfilePrefs.loadActiveName(application)?.let { name ->
            GameProfilePrefs.loadAll(application)
                .firstOrNull { it.name == name }
                ?.let { applyGameProfile(it, announce = false) }
        }
        // Giroscopio (Fase 4): si quedó activado al cerrar la app,
        // el sensor vuelve a arrancar solo; si no, queda apagado.
        if (_gyroEnabled.value) startGyroSensor()
        // A3: si el proceso murió con el servicio enviando (START_STICKY
        // lo rearrancó y reanudó solo), la UI nace DESCONECTADO aunque la
        // TV sigue recibiendo. Restaurar CONECTANDO con lo guardado: el
        // bucle de ping verificará en segundos si la TV responde de
        // verdad (y lo resolverá a CONECTADO o a ERROR).
        if (LastConnectionPrefs.isRunning(getApplication())) {
            LastConnectionPrefs.load(getApplication())?.let { saved ->
                _ui.update {
                    it.copy(
                        status = ConnectionStatus.CONNECTING,
                        host = saved.host, port = saved.port, pin = saved.pin,
                        connectingSinceMs = System.currentTimeMillis(),
                        attemptMethod = saved.method,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        networkCallback?.let { cb ->
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        bluetoothReceiver?.let { runCatching { getApplication<Application>().unregisterReceiver(it) } }
        bluetoothReceiver = null
        turboJob?.cancel()
        macroPlayJob?.cancel()
        stopGyroSensor()
        extrasSender.close()
        super.onCleared()
    }

    /** Diseño del mando a gusto del usuario (tamaño/posición/quitar). */
    private val _layout = MutableStateFlow(LayoutPrefs.load(application))
    val layoutConfig: StateFlow<LayoutConfig> = _layout.asStateFlow()

    /** Zona muerta de los sticks (0.05 = 5%): se ajusta en Ajustes > Sensibilidad. */
    private val _deadZone = MutableStateFlow(LayoutPrefs.loadDeadZone(application))
    val deadZone: StateFlow<Float> = _deadZone.asStateFlow()

    fun setDeadZone(value: Float) {
        val next = value.coerceIn(0f, 0.15f)
        _deadZone.value = next
        LayoutPrefs.saveDeadZone(getApplication(), next)
    }

    /**
     * Perfil de emulación activo (Fase 2, mecanismo listo para la
     * pantalla TIPO DE MANDO de la Fase 3). Cambia el perfil visual
     * Y el de emulación a la vez: se guarda, el servicio de envío
     * lo mete en el byte 28 de cada paquete y la TV recrea el
     * dispositivo virtual con ese perfil. Si ya hay una sesión HID
     * registrada con otro perfil, se vuelve a registrar con el
     * nuevo descriptor; por Wi-Fi el cambio llega con el siguiente
     * paquete (la TV recrea el mando al ver el profileId nuevo).
     * El touchpad de DS4/DualSense NO se emula en esta versión
     * (opción (a) del plan) y la UI de la Fase 3 deberá decirlo.
     * El perfil anterior se guarda para poder volver atrás desde
     * TIPO DE MANDO sin tener que recordar cuál era.
     */
    fun setActiveProfile(profileId: Int) {
        val profile = GamepadProfiles.fromId(profileId)
        val previous = GamepadSender.activeProfileId
        GamepadSender.setActiveProfile(getApplication(), profile.id)
        if (previous != profile.id) GamepadProfilePrefs.savePrevious(getApplication(), previous)
        if (hidService.appRegistered) {
            hidService.unregister()
            hidService.register(profile.id)
        }
        _ui.update {
            it.copy(
                activeProfileId = profile.id,
                previousProfileId = if (previous != profile.id) previous else it.previousProfileId,
                info = "Perfil activo: ${profile.name} (${profile.deviceName}). La TV recreará el mando virtual con ese perfil; si OpenNOW no lo re-enumera, reinicia OpenNOW.",
            )
        }
    }

    /** Vuelve al perfil anterior al último cambio (TIPO DE MANDO). */
    fun revertProfile() {
        val prev = _ui.value.previousProfileId ?: return
        setActiveProfile(prev)
    }

    /** Curva de respuesta de los sticks (0.5..1.5; 0.85 por defecto). */
    private val _curve = MutableStateFlow(0.85f)
    val responseCurve: StateFlow<Float> = _curve.asStateFlow()

    fun setCurve(value: Float) {
        _curve.value = value.coerceIn(0.5f, 1.5f)
    }

    /** Sensibilidad de los gatillos (0.5..1.5; 1.0 = tal cual). */
    private val _triggerScale = MutableStateFlow(1f)
    val triggerScale: StateFlow<Float> = _triggerScale.asStateFlow()

    fun setTriggerScale(value: Float) {
        _triggerScale.value = value.coerceIn(0.5f, 1.5f)
    }

    /** Remapeo de botones activo (bit origen -> bit destino). */
    private val _remap = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val buttonRemap: StateFlow<Map<Int, Int>> = _remap.asStateFlow()

    fun setButtonRemap(remap: Map<Int, Int>) {
        _remap.value = remap
            .filter { (from, to) -> from in GameProfile.ALL_BUTTON_BITS && to in GameProfile.ALL_BUTTON_BITS && from != to }
    }

    /** Traduce un bitmask por el remapeo activo (bit a bit, así un intercambio A<->B vale en los dos sentidos). */
    private fun remapButtons(buttons: Int): Int {
        val remap = _remap.value
        if (remap.isEmpty()) return buttons
        var out = 0
        for (bit in GameProfile.ALL_BUTTON_BITS) {
            if ((buttons and bit) != 0) out = out or (remap[bit] ?: bit)
        }
        return out
    }

    /**
     * Aplica un perfil de juego completo (Fase 3): tipo de mando,
     * zona muerta, curva, sensibilidad de gatillos, remapeo y, si
     * el perfil trae uno guardado, el diseño de botones. Guarda el
     * nombre como perfil activo para reaplicarlo al arrancar.
     */
    fun applyGameProfile(profile: GameProfile, announce: Boolean = true) {
        val p = profile.sanitized()
        setActiveProfile(p.profileId)
        setDeadZone(p.deadZone)
        setCurve(p.curve)
        setTriggerScale(p.triggerScale)
        setButtonRemap(p.remap)
        // Vibración (Fase 4): el perfil de juego manda mientras
        // está activo; su ajuste (guardado en la Fase 3) se aplica
        // aquí y pisa el interruptor global de Extras.
        _vibrationEnabled.value = p.vibrationEnabled
        p.layoutJson?.let { json ->
            runCatching { LayoutPrefs.fromJson(json) }.getOrNull()?.let { layout ->
                _layout.value = layout
                LayoutPrefs.save(getApplication(), layout)
            }
        }
        GameProfilePrefs.saveActiveName(getApplication(), p.name)
        _ui.update {
            it.copy(
                activeGameProfileName = p.name,
                info = if (announce) "Perfil de juego aplicado: ${p.name} (${GamepadProfiles.fromId(p.profileId).name})." else it.info,
            )
        }
    }

    fun updateElement(id: String, layout: ButtonLayout) {
        val next = _layout.value.withElement(id, layout)
        _layout.value = next
        LayoutPrefs.save(getApplication(), next)
    }

    fun resetLayout() {
        val next = LayoutConfig()
        _layout.value = next
        LayoutPrefs.save(getApplication(), next)
    }

    private var buttons = 0
    /** Botones extra L4/R4 (Fase 4, GamepadExtraButtons, byte 29 del protocolo). */
    private var extraButtons = 0
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private var leftTrigger = 0f
    private var rightTrigger = 0f

    fun setButton(button: Int, pressed: Boolean) {
        // Los botones se guardan SIN mapear: el remapeo se aplica
        // una sola vez, en publish(). Si se mapeara aquí y en
        // publish(), un intercambio A<->B se desharía solo.
        val was = (buttons and button) != 0
        buttons = if (pressed) buttons or button else buttons and button.inv()
        if (pressed && !was) vibrateOnPress()
        publish()
    }

    /** L4/R4 (Fase 4): como setButton, pero en el bitmask extra del byte 29 (no pasan por remapeo ni por HID). */
    fun setExtraButton(button: Int, pressed: Boolean) {
        val was = (extraButtons and button) != 0
        extraButtons = if (pressed) extraButtons or button else extraButtons and button.inv()
        if (pressed && !was) vibrateOnPress()
        publish()
    }
    fun setLeftStick(x: Float, y: Float) { leftX = x; leftY = y; publish() }
    fun setRightStick(x: Float, y: Float) { rightX = x; rightY = y; publish() }
    fun setTriggers(l: Float, r: Float) { leftTrigger = l; rightTrigger = r; publish() }
    /** Gatillos analógicos por separado (LT/RT se mantienen pulsados). */
    fun setLeftTrigger(v: Float) { leftTrigger = v; publish() }
    fun setRightTrigger(v: Float) { rightTrigger = v; publish() }

    /** Pulso corto (120 ms): botón Xbox (HOME) y Compartir (CAPTURE). */
    fun pulseButton(button: Int) {
        viewModelScope.launch {
            setButton(button, true)
            delay(120)
            setButton(button, false)
        }
    }

    // ================= Extras — Fase 4 =================

    /** Turbo: activa o quita el turbo de un botón (A/B/X/Y/LB/RB). Se guarda en ExtrasPrefs. */
    fun setTurbo(button: Int, enabled: Boolean) {
        val next = if (enabled) _turboButtons.value + button else _turboButtons.value - button
        _turboButtons.value = next
        ExtrasPrefs.saveTurbo(getApplication(), TurboConfig(next, _turboRateHz.value))
        publish()
    }

    /** Velocidad del turbo en pulsos por segundo (5..20 en la UI; el núcleo admite 1..30). */
    fun setTurboRate(rateHz: Int) {
        _turboRateHz.value = rateHz.coerceIn(1, 30)
        ExtrasPrefs.saveTurbo(getApplication(), TurboConfig(_turboButtons.value, _turboRateHz.value))
    }

    /**
     * Arranca el generador de pulsos si hay algún botón con turbo
     * mantenido. Alterna la fase cada medio periodo y republica;
     * cuando ya no queda ningún botón con turbo pulsado, se para
     * solo y la fase vuelve a ON (la siguiente pulsación empieza
     * activa, como un botón normal).
     */
    private fun maybeStartTurbo() {
        if (turboJob != null) return
        val mask = _turboButtons.value.fold(0) { acc, b -> acc or b }
        if (mask == 0 || buttons and mask == 0) return
        turboPhaseOn = true
        turboJob = viewModelScope.launch {
            while (isActive) {
                delay(Turbo.halfPeriodMs(_turboRateHz.value))
                turboPhaseOn = !turboPhaseOn
                publish()
                val m = _turboButtons.value.fold(0) { acc, b -> acc or b }
                if (buttons and m == 0) {
                    turboPhaseOn = true
                    publish()
                    turboJob = null
                    break
                }
            }
        }
    }

    /** Vibración al pulsar (Fase 4): interruptor global de Extras. El perfil de juego activo la pisa en applyGameProfile(). */
    fun setVibrationEnabled(enabled: Boolean) {
        _vibrationEnabled.value = enabled
        ExtrasPrefs.saveVibration(getApplication(), enabled)
    }

    /**
     * Vibración corta (15 ms) al pulsar un botón del teléfono.
     * Solo teléfono: la vibración DEL JUEGO (que el juego haga
     * vibrar el mando, EV_FF/FF_RUMBLE en uinput) NO existe en
     * esta versión y ninguna pantalla la promete (ver
     * docs/PLAN-GAMEPAD-PRO.md, Fase 4). El permiso VIBRATE ya
     * estaba declarado en el manifest y no se usaba.
     */
    private fun vibrateOnPress() {
        if (!_vibrationEnabled.value) return
        val vibrator = getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching {
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    /** Empieza a grabar una macro (Fase 4). Si se estaba reproduciendo otra, se para. */
    fun startMacroRecording(name: String) {
        val clean = name.ifBlank { "Macro" }.take(40)
        stopMacroPlayback()
        val now = System.currentTimeMillis()
        macroRecorder = MacroRecorder().apply { start(now) }
        _recordingMacro.value = clean
        // P5: arranca el seguimiento del tope (30 s / 200 eventos).
        macroRecordStartMs = now
        macroRecordedChanges = 0
        macroLastButtons = 0
        macroLastExtra = 0
        macroCapWarned = false
        _recordingMacroProgress.value = 0L to 0
        _ui.update { it.copy(info = "Grabando macro \"$clean\": pulsa los botones; para con «Parar y guardar». Máx. 30 s / 200 eventos.") }
    }

    /** Para la grabación y guarda la macro (si se pulsó algo). Mismo nombre = se sustituye. */
    fun stopMacroRecording() {
        val recorder = macroRecorder ?: return
        val name = _recordingMacro.value ?: return
        val macro = recorder.stop(System.currentTimeMillis(), name)
        macroRecorder = null
        _recordingMacro.value = null
        macroRecordStartMs = 0L
        macroRecordedChanges = 0
        macroCapWarned = false
        _recordingMacroProgress.value = null
        if (macro == null) {
            _ui.update { it.copy(info = "Macro vacía: no se grabó ningún botón.") }
            return
        }
        val next = (_macros.value.filter { it.name != macro.name } + macro).sortedBy { it.name }
        _macros.value = next
        ExtrasPrefs.saveMacros(getApplication(), next)
        _ui.update { it.copy(info = "Macro guardada: ${macro.name} (${macro.events.size} eventos, ${macro.durationMs} ms).") }
    }

    /** Reproduce una macro (Fase 4): aplica cada evento en su momento, como si se pulsaran esos botones. */
    fun playMacro(name: String) {
        if (_recordingMacro.value != null) return // No reproducir mientras se graba: se grabaría la reproducción.
        val macro = _macros.value.firstOrNull { it.name == name } ?: return
        stopMacroPlayback()
        _playingMacro.value = name
        macroPlayJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            for (event in macro.events) {
                val wait = event.delayMs - (System.currentTimeMillis() - startMs)
                if (wait > 0) delay(wait)
                if (!isActive) break
                buttons = event.buttons
                extraButtons = event.extraButtons
                publish()
            }
            _playingMacro.value = null
            macroPlayJob = null
        }
    }

    /** Para la reproducción (y suelta todo para no dejar botones atascados). */
    fun stopMacroPlayback() {
        macroPlayJob?.cancel()
        macroPlayJob = null
        if (_playingMacro.value != null) {
            _playingMacro.value = null
            buttons = 0
            extraButtons = 0
            publish()
        }
    }

    fun deleteMacro(name: String) {
        val next = _macros.value.filter { it.name != name }
        _macros.value = next
        ExtrasPrefs.saveMacros(getApplication(), next)
    }

    // --- Giroscopio (Fase 4) ---

    fun setGyroEnabled(enabled: Boolean) {
        _gyroEnabled.value = enabled
        ExtrasPrefs.saveGyroEnabled(getApplication(), enabled)
        if (enabled) startGyroSensor() else stopGyroSensor()
    }

    fun setGyroSensitivity(value: Float) {
        _gyroSensitivity.value = value.coerceIn(0.5f, 2f)
        ExtrasPrefs.saveGyroSensitivity(getApplication(), _gyroSensitivity.value)
    }

    /**
     * Arranca el sensor de rotación (vector de rotación de juego,
     * sin brújula; si no existe, vector normal; si tampoco,
     * acelerómetro como respaldo aproximado). La inclinación se
     * convierte a stick derecho en cada evento del sensor
     * (GyroMapper) y publish() la envía como el stick normal.
     * Si no hay ningún sensor, el interruptor vuelve a NO y la UI
     * dice por qué (sin fingir que funciona).
     */
    private fun startGyroSensor() {
        if (gyroListener != null) return
        val sm = getApplication<Application>().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            _gyroEnabled.value = false
            _ui.update { it.copy(error = "Sin acceso a sensores en este teléfono: el giroscopio no está disponible.") }
            return
        }
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            _gyroEnabled.value = false
            _ui.update { it.copy(error = "Este teléfono no tiene sensor de rotación ni acelerómetro: el giroscopio no está disponible.") }
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // En segundo plano el sensor sigue entregando eventos:
                // sin esta guarda, el stick derecho se movía solo con
                // el teléfono en el bolsillo (ver onAppBackgrounded).
                if (!appForegrounded) return
                val (pitch, roll) = tiltFromEvent(event) ?: return
                val (x, y) = GyroMapper.mapTilt(pitch, roll, _gyroSensitivity.value)
                gyroX = x
                gyroY = y
                publish()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        gyroListener = listener
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGyroSensor() {
        val sm = getApplication<Application>().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        gyroListener?.let { sm?.unregisterListener(it) }
        gyroListener = null
        gyroX = 0f
        gyroY = 0f
        publish()
    }

    /**
     * Inclinación (pitch, roll) en radianes desde un evento del
     * sensor. Para el vector de rotación se reorienta la matriz
     * según la rotación actual de la pantalla, para que "hacia
     * adelante" sea siempre hacia adelante en la pantalla, juegue
     * el usuario en vertical o en horizontal. El acelerómetro es
     * un respaldo aproximado (gravedad, sin giroscopio).
     * La convención de signo exacta está pendiente de verificar en
     * el teléfono real (ver GyroMapper).
     */
    private fun tiltFromEvent(event: SensorEvent): Pair<Float, Float>? {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val pitch = atan2(-ax, sqrt(ay * ay + az * az))
            val roll = atan2(ay, az)
            return pitch to roll
        }
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val remapped = FloatArray(9)
        val (axisX, axisY) = when (displayRotation()) {
            Surface.ROTATION_0 -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            else -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        }
        if (!SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)) return null
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)
        return orientation[1] to orientation[2]
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getApplication<Application>().display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = getApplication<Application>().getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    }

    // --- Teclado virtual y modo ratón (Fase 4) ---

    /** Envía un paquete de extras a la TV por UDP. false si no hay TV conectada (la UI lo dice). */
    private fun sendExtras(packet: ByteArray, quiet: Boolean = false): Boolean {
        val host = _ui.value.host
        if (host.isBlank()) {
            if (!quiet) _ui.update { it.copy(error = "Conecta primero a la TV: los extras van por Wi-Fi a la TV.") }
            return false
        }
        return extrasSender.send(packet, host, _ui.value.port)
    }

    /** Activa o desactiva el teclado virtual en la TV (dispositivo uinput aparte, nunca parte del mando).
     * P8: send() es UDP fire-and-forget y la TV NO devuelve eco
     * ni acuse de los paquetes de extras (UdpReceiverService los
     * procesa y sigue, sin contador ni respuesta): el estado es
     * "enviado (sin confirmar)", nunca "activado en la TV". Un
     * ACK real exigiría eco en la TV + escucha en ExtrasSender
     * (ver manifiesto W3, parche TV). */
    fun setKeyboardEnabled(enabled: Boolean) {
        val action = if (enabled) ExtrasProtocol.ACTION_CREATE else ExtrasProtocol.ACTION_DESTROY
        if (!sendExtras(ExtrasProtocol.encodeKeyboard(0, action))) return
        _ui.update {
            it.copy(
                keyboardEnabled = enabled,
                info = if (enabled) "Teclado virtual: petición enviada a la TV (sin confirmar: la TV no devuelve acuse de los extras; si está apagada o sin receptor, no se habrá creado)." else "Petición de desactivar el teclado enviada a la TV (sin confirmar).",
            )
        }
    }

    /** Activa o desactiva el modo ratón en la TV (dispositivo uinput aparte, nunca parte del mando).
     * P8: como el teclado, "enviado (sin confirmar)" — no hay ACK de extras desde la TV. */
    fun setMouseEnabled(enabled: Boolean) {
        val action = if (enabled) ExtrasProtocol.ACTION_CREATE else ExtrasProtocol.ACTION_DESTROY
        if (!sendExtras(ExtrasProtocol.encodeMouse(0, 0, 0, action))) return
        _ui.update {
            it.copy(
                mouseEnabled = enabled,
                info = if (enabled) "Modo ratón: petición enviada a la TV (sin confirmar: la TV no devuelve acuse de los extras). Arrastra en el panel para mover el cursor." else "Petición de desactivar el modo ratón enviada a la TV (sin confirmar).",
            )
        }
    }

    /** Escribe un texto en la TV, tecla a tecla (SHIFT + tecla para mayúsculas y símbolos). Los caracteres sin tecla (p. ej. ñ) se saltan y se cuentan. */
    fun typeText(text: String) {
        if (_ui.value.host.isBlank()) {
            _ui.update { it.copy(error = "Conecta primero a la TV: el teclado va por Wi-Fi a la TV.") }
            return
        }
        viewModelScope.launch {
            var skipped = 0
            for (c in text) {
                // Sin Elvis + run { continue }: el `continue` dentro
                // de una lambda inline es experimental ("break
                // continue in inline lambdas") y el compilador de
                // CI lo rechaza (run 42421440729, paso 6). La forma
                // explícita compila siempre.
                val mapped = KeyMap.forChar(c)
                if (mapped == null) { skipped++; continue }
                val (code, shift) = mapped
                if (shift) sendExtras(ExtrasProtocol.encodeKeyboard(KeyMap.KEY_LEFTSHIFT, ExtrasProtocol.ACTION_PRESS), quiet = true)
                sendExtras(ExtrasProtocol.encodeKeyboard(code, ExtrasProtocol.ACTION_PRESS), quiet = true)
                delay(15)
                sendExtras(ExtrasProtocol.encodeKeyboard(code, ExtrasProtocol.ACTION_RELEASE), quiet = true)
                if (shift) sendExtras(ExtrasProtocol.encodeKeyboard(KeyMap.KEY_LEFTSHIFT, ExtrasProtocol.ACTION_RELEASE), quiet = true)
                delay(15)
            }
            if (skipped > 0) _ui.update { it.copy(info = "Texto enviado ($skipped caracteres sin tecla, p. ej. ñ, no se escribieron).") }
        }
    }

    /** Una tecla especial: "enter", "backspace", "space", "tab", "escape", "up", "down", "left", "right". */
    fun sendSpecialKey(name: String) {
        val code = KeyMap.forSpecial(name) ?: return
        viewModelScope.launch {
            sendExtras(ExtrasProtocol.encodeKeyboard(code, ExtrasProtocol.ACTION_PRESS), quiet = true)
            delay(30)
            sendExtras(ExtrasProtocol.encodeKeyboard(code, ExtrasProtocol.ACTION_RELEASE), quiet = true)
        }
    }

    /** Mueve el cursor del ratón en la TV (movimiento relativo en píxeles). */
    fun mouseMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        sendExtras(ExtrasProtocol.encodeMouse(dx, dy, mouseButtons), quiet = true)
    }

    /** Clic de ratón: pulsa y suelta el botón (izquierdo, derecho o central, ExtrasProtocol). */
    fun mouseClick(button: Int) {
        viewModelScope.launch {
            mouseButtons = mouseButtons or button
            sendExtras(ExtrasProtocol.encodeMouse(0, 0, mouseButtons), quiet = true)
            delay(80)
            mouseButtons = mouseButtons and button.inv()
            sendExtras(ExtrasProtocol.encodeMouse(0, 0, mouseButtons), quiet = true)
        }
    }

    private fun publish() {
        // Sensibilidad: la zona muerta (ajustable en Ajustes,
        // 0.05 por defecto) es el % del recorrido que no hace
        // nada; la curva (0.85 por defecto, ajustable por perfil
        // de juego) deja el stick más vivo en el centro, sin
        // volverse brusco, para que responda desde el primer toque.
        // Los gatillos se escalan por el perfil de juego (x1.0 por
        // defecto) y los botones pasan por el remapeo activo.
        // Turbo (Fase 4) ANTES del remapeo: el turbo es del botón
        // físico que se mantiene; el remapeo traduce después.
        // L4/R4 (Fase 4) viajan en extraButtons, fuera del bitmask.
        val turboed = Turbo.apply(buttons, _turboButtons.value, turboPhaseOn)
        val state = GamepadState(
            buttons = remapButtons(turboed), leftX = leftX, leftY = leftY,
            rightX = rightX, rightY = rightY,
            leftTrigger = (leftTrigger * _triggerScale.value).coerceIn(0f, 1f),
            rightTrigger = (rightTrigger * _triggerScale.value).coerceIn(0f, 1f),
            extraButtons = extraButtons,
        ).withDeadZone(_deadZone.value).withResponseCurve(_curve.value)
        // Giroscopio (Fase 4): si está activado, el stick derecho
        // lo manda la inclinación (gyroX/gyroY), no el táctil. Se
        // aplica DESPUÉS de zona muerta y curva para no distorsionar
        // la inclinación con la curva de los sticks.
        val finalState = if (_gyroEnabled.value) state.copy(rightX = gyroX, rightY = gyroY) else state
        GamepadSender.currentState = finalState
        // Macro grabando (Fase 4): se anota el estado de botones
        // SIN turbo ni remapeo (los botones físicos), para que la
        // reproducción pulse lo mismo que se grabó.
        // P5: el tope (30 s / 200 eventos) es silencioso en el
        // grabador; aquí se avisa UNA vez en ui.error cuando se
        // alcanza, para que la UI lo muestre (el botón ▶ quedaba
        // mudo grabando). El conteo espeja el del grabador: solo
        // cuentan los CAMBIOS de botones, igual que onState().
        macroRecorder?.let { recorder ->
            val nowMs = System.currentTimeMillis()
            recorder.onState(nowMs, buttons, extraButtons)
            if (buttons != macroLastButtons || extraButtons != macroLastExtra) {
                macroRecordedChanges++
                macroLastButtons = buttons
                macroLastExtra = extraButtons
            }
            val overTime = nowMs - macroRecordStartMs > GamepadMacro.MAX_DURATION_MS
            val overEvents = macroRecordedChanges >= GamepadMacro.MAX_EVENTS
            // P-A: progreso en vivo para la UI.
            _recordingMacroProgress.value = (nowMs - macroRecordStartMs) to macroRecordedChanges
            if ((overTime || overEvents) && !macroCapWarned) {
                macroCapWarned = true
                _ui.update {
                    it.copy(error = "Tope de macro alcanzado (${if (overTime) "30 s" else "200 eventos"}): lo grabado hasta aquí se guarda al parar; lo nuevo ya no se graba.")
                }
                // P-A: auto-stop al llegar al tope: se guarda lo
                // grabado hasta aqui (stopMacroRecording pone
                // "Macro guardada" en ui.info). Sin aviso
                // duplicado: el aviso de tope de arriba ya se puso
                // una vez en ui.error.
                stopMacroRecording()
            }
        }
        maybeStartTurbo()
        // Bluetooth HID es inyección directa: si está conectado, el
        // reporte va también por HID, sin pasar por la TV receptora.
        // L4/R4 no van por HID (el descriptor no tiene hueco).
        if (hidService.status == TransportStatus.CONNECTED) hidService.sendReport(finalState)
    }

    /**
     * La app pasa a segundo plano (pantalla apagada, cambio de app):
     * el dedo ya no está y el touch-up nunca llega. Sin esto, el
     * servicio seguía enviando el último estado a 120 Hz y el botón
     * quedaba atascado en el juego (el watchdog de la TV no dispara
     * porque siguen llegando paquetes). Se suelta todo y se para el
     * giroscopio (su listener publicaba en cada evento incluso en
     * segundo plano, moviendo el stick derecho con el teléfono en
     * el bolsillo).
     */
    fun onAppBackgrounded() {
        appForegrounded = false
        stopGyroSensor()
        releaseAllInputs()
        // A2 (resto, lo demás ya estaba en HEAD): la reproducción
        // de una macro seguía viva en segundo plano y su bucle
        // volvía a pulsar botones DESPUÉS del estado cero de
        // releaseAllInputs(), dejando botones atascados en el
        // juego; y la espera de connectHid (hidCheckJob) podía
        // registrar el HID con la app dormida. Ambas se paran
        // aquí; al volver, el usuario reanuda a mano.
        stopMacroPlayback()
        hidCheckJob?.cancel(); hidCheckJob = null
    }

    /** Vuelta a primer plano: el giroscopio vuelve solo si estaba activado. */
    fun onAppForegrounded() {
        appForegrounded = true
        if (_gyroEnabled.value) startGyroSensor()
    }

    /** Suelta todos los botones, sticks y gatillos y publica el estado cero. */
    private fun releaseAllInputs() {
        buttons = 0; extraButtons = 0
        leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f
        leftTrigger = 0f; rightTrigger = 0f
        turboPhaseOn = true
        publish()
    }

    /** La pantalla del mando es solo el mando: todo lo de conexión vive en Ajustes (⚙). */
    fun openSettings() = _ui.update { it.copy(showSettings = true, error = null) }
    fun closeSettings() = _ui.update { it.copy(showSettings = false, showQrScanner = false, showLayoutEditor = false, showControllerType = false, showGameProfiles = false, showControllerTest = false, showPerformance = false, showExtras = false, showUiCustomization = false) }
    fun openQrScanner() = _ui.update { it.copy(showQrScanner = true) }

    /** Pantallas Fase 3: se abren desde Ajustes y se vuelve a Ajustes al cerrar. */
    fun openControllerType() = _ui.update { it.copy(showControllerType = true, showSettings = false) }
    fun closeControllerType() = _ui.update { it.copy(showControllerType = false, showSettings = true) }
    fun openGameProfiles() = _ui.update { it.copy(showGameProfiles = true, showSettings = false) }
    fun closeGameProfiles() = _ui.update { it.copy(showGameProfiles = false, showSettings = true) }
    fun openControllerTest() = _ui.update { it.copy(showControllerTest = true, showSettings = false) }
    fun closeControllerTest() = _ui.update { it.copy(showControllerTest = false, showSettings = true) }
    fun openPerformance() = _ui.update { it.copy(showPerformance = true, showSettings = false) }
    fun closePerformance() = _ui.update { it.copy(showPerformance = false, showSettings = true) }

    /** Pantalla Fase 4 (Extras): se abre desde Ajustes y se vuelve a Ajustes al cerrar. */
    fun openExtras() = _ui.update { it.copy(showExtras = true, showSettings = false) }
    fun closeExtras() = _ui.update { it.copy(showExtras = false, showSettings = true) }
    fun openUiCustomization() = _ui.update { it.copy(showUiCustomization = true, showSettings = false) }
    fun closeUiCustomization() = _ui.update { it.copy(showUiCustomization = false, showSettings = true) }

    /** Panel visual de botones: se abre desde Ajustes y se cierra volviendo al mando. */
    fun openLayoutEditor() = _ui.update { it.copy(showLayoutEditor = true, showSettings = false) }
    fun closeLayoutEditor() = _ui.update { it.copy(showLayoutEditor = false) }

    fun setTransportMode(mode: TransportMode) = _ui.update { it.copy(transportMode = mode) }

    /** Forzar un método manualmente. */
    fun selectMethod(method: TransportMethod) {
        _ui.update { it.copy(selectedMethod = method, transportMode = TransportMode.MANUAL, error = null) }
        // Si ya hay conexión o intento en curso, reconectar con el método nuevo.
        val host = _ui.value.host
        if ((_ui.value.status == ConnectionStatus.CONNECTED || _ui.value.status == ConnectionStatus.CONNECTING) && host.isNotBlank()) {
            disconnect(silent = true)
            connect(PairingInfo(host, _ui.value.port, _ui.value.pin), method)
        }
    }

    /** Comprueba HID en el teléfono y actualiza su estado real. Llamar al abrir la pantalla de conexión. */
    private var lastHidCheckAtMs: Long = 0L

    fun refreshHidStatus(force: Boolean = false) {
        // Cada llamada registra otro listener de perfil Bluetooth:
        // sin freno, cada cambio de estado de conexión pedía el
        // proxy de nuevo y los listeners se acumulaban. Como mucho
        // una comprobación cada 10 s. Al encender el Bluetooth, el
        // receptor de estado (registerBluetoothStateReceiver)
        // re-comprueba de inmediato con force=true. Con force=true
        // (tras dar el permiso), se salta el freno.
        val now = System.currentTimeMillis()
        if (!force && now - lastHidCheckAtMs < 10_000) return
        lastHidCheckAtMs = now
        val checkStartedAt = now
        hidService.checkAvailability { availability ->
            val status = when (availability) {
                HidAvailability.AVAILABLE -> TransportStatus.AVAILABLE
                HidAvailability.UNKNOWN -> TransportStatus.UNAVAILABLE
                else -> TransportStatus.UNAVAILABLE
            }
            _ui.update {
                it.copy(
                    hidAvailability = availability,
                    transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to status),
                )
            }
        }
        // Si el firmware no expone el perfil, el callback de arriba
        // no llega nunca y el estado se quedaba en UNKNOWN para
        // siempre: a los 3 s sin respuesta, es NO DISPONIBLE.
        // Solo vale si no empezó otra comprobación después (si no,
        // el timeout viejo marcaría NO DISPONIBLE una comprobación
        // nueva que aún está en curso).
        viewModelScope.launch {
            delay(3000)
            if (lastHidCheckAtMs == checkStartedAt && hidService.availability == HidAvailability.UNKNOWN) {
                _ui.update {
                    it.copy(
                        hidAvailability = HidAvailability.UNAVAILABLE,
                        transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.UNAVAILABLE),
                    )
                }
            }
        }
    }

    /** Comprueba Bluetooth datos (SPP/RFCOMM, Fase 5) y actualiza su estado real con motivo. Espejo de refreshHidStatus(). */
    fun refreshBtDataStatus() {
        val status = btDataTransport.refreshStatus()
        _ui.update {
            it.copy(
                transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_DATA to status),
                btDataStatusDetail = btDataTransport.lastError,
            )
        }
    }

    /** Rellena la lista de TVs emparejadas por Bluetooth (Fase 5) para el diálogo "Elegir TV emparejada". */
    fun refreshBtDataDevices() {
        _ui.update { it.copy(btDataDevices = btDataTransport.bondedDevices()) }
    }

    /** Elige la TV emparejada para Bluetooth datos (Fase 5): persiste su MAC y marca el método como disponible si el BT está encendido. */
    fun selectBtDataDevice(address: String) {
        LastConnectionPrefs.save(getApplication(), _ui.value.host, _ui.value.port, _ui.value.pin, TransportMethod.BLUETOOTH_DATA, btAddress = address)
        if (btDataTransport.isBluetoothEnabled()) {
            _ui.update { it.copy(transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_DATA to TransportStatus.AVAILABLE), btDataStatusDetail = null) }
        } else {
            refreshBtDataStatus()
        }
    }

    /**
     * La TV cortó el Bluetooth (o volvió). Esto es lo que antes no
     * existía: el callback del HID ponía FAILED en el servicio y
     * nadie se enteraba — la UI seguía en "Conectado" y el modo
     * AUTO nunca saltaba a Wi-Fi. Ahora la caída dispara el
     * fallback inmediato a Wi-Fi y la reconexión en segundo plano.
     */
    private fun onHidConnectionChanged(status: TransportStatus) {
        _ui.update { it.copy(transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to status)) }
        when (status) {
            TransportStatus.CONNECTED -> {
                startHidHeartbeat()
                if (_ui.value.status != ConnectionStatus.CONNECTED) {
                    // Estábamos en ERROR tras la caída y sin Wi-Fi: el mando vuelve a ser HID.
                    hidRetryJob?.cancel(); hidRetryJob = null
                    _ui.update { it.copy(status = ConnectionStatus.CONNECTED, attemptMethod = TransportMethod.BLUETOOTH_HID, error = null, info = "Bluetooth HID conectado con la TV.") }
                } else {
                    _ui.update { it.copy(info = "Bluetooth disponible de nuevo (sigues por Wi-Fi).") }
                }
            }
            TransportStatus.FAILED -> {
                if (_ui.value.status == ConnectionStatus.CONNECTED && _ui.value.attemptMethod == TransportMethod.BLUETOOTH_HID) {
                    onHidDropped()
                }
            }
            else -> {}
        }
    }

    /** Bluetooth caído mientras jugábamos por HID: a Wi-Fi ahora mismo, sin tocar nada. */
    private fun onHidDropped() {
        stopHidHeartbeat()
        _ui.update {
            it.copy(
                error = "Bluetooth desconectado por la TV; cambiando a Wi-Fi…",
                transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED),
            )
        }
        startHidRetryLoop()
        if (_ui.value.transportMode != TransportMode.AUTO) {
            _ui.update { it.copy(status = ConnectionStatus.ERROR, error = "Bluetooth desconectado por la TV. Se sigue reintentando Bluetooth en segundo plano.") }
            return
        }
        val host = _ui.value.host
        if (host.isNotBlank()) {
            connect(PairingInfo(host, _ui.value.port, _ui.value.pin), TransportMethod.WIFI_UDP)
        } else {
            // Sin IP guardada: buscar la TV por broadcast y NSD y conectar a la primera.
            viewModelScope.launch {
                val found = mergeDiscovered(
                    DiscoveryClient.discover(),
                    NsdDiscovery.discover(getApplication()),
                )
                val tv = found.firstOrNull()
                if (tv != null) {
                    connectToDiscovered(tv)
                } else {
                    _ui.update { it.copy(status = ConnectionStatus.ERROR, error = "Bluetooth desconectado y no se encontró la TV por Wi-Fi. Se sigue reintentando Bluetooth en segundo plano.") }
                }
            }
        }
    }

    /**
     * Re-registro HID en segundo plano con backoff exponencial
     * (1 s, 2 s, 4 s, 8 s… tope 15 s). La radio de la TV se cae y
     * vuelve: la app no puede arreglar la radio, pero sí dejar la
     * app HID registrada para que la TV reconecte sola cuando
     * vuelva, sin que el usuario tenga que tocar nada. Se para
     * cuando el HID reconecta o el usuario desconecta.
     */
    private fun startHidRetryLoop() {
        hidRetryJob?.cancel()
        hidRetryJob = viewModelScope.launch {
            var delayMs = 1000L
            while (isActive && hidService.status != TransportStatus.CONNECTED) {
                // El callback del proxy puede no llegar nunca si el
                // firmware no expone el perfil: con timeout no se
                // queda colgado el bucle (3 s, como el timeout de
                // refreshHidStatus: antes 5 s, y cada intento fallido
                // tardaba 5 s en pasar al siguiente backoff).
                val ok = withTimeoutOrNull(3000) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        // La lambda NO puede ir como trailing lambda:
                        // el último parámetro es profileId (Int), no
                        // onResult; va explícita como primer argumento.
                        hidService.reRegister({ cont.resume(it) }, profileId = GamepadSender.activeProfileId)
                    }
                } ?: false
                if (ok) {
                    // Registrado: la TV puede conectar en cualquier momento.
                    // Esperar antes de volver a intentarlo (el registro
                    // repetido no ayuda y puede molestar a la TV).
                    delay(15_000)
                } else {
                    delay(delayMs)
                    delayMs = minOf(delayMs * 2, 15_000)
                }
            }
            hidRetryJob = null
        }
    }

    fun connectManual(host: String, port: Int, pin: String) {
        if (host.isBlank()) { _ui.update { it.copy(error = "Escribe la IP de la TV.") }; return }
        if (!PairingInfo.isValidPin(pin)) { _ui.update { it.copy(error = "El PIN son 4 dígitos.") }; return }
        connect(PairingInfo(host.trim(), port, pin), _ui.value.selectedMethod)
    }

    fun connectFromQr(payload: String) {
        val info = PairingInfo.parseQrPayload(payload)
        if (info == null) { _ui.update { it.copy(error = "QR no válido. Debe ser un QR de Ultimate Gamepad (UGP1).") }; return }
        connect(info, _ui.value.selectedMethod)
    }

    /** ¿El teléfono está en Wi-Fi? Sin Wi-Fi no hay TV que descubrir ni conectar. */
    fun refreshWifiState() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        // Hotspot (2026-11-07): cuando el teléfono ES el punto de
        // acceso, no hay TRANSPORT_WIFI activo (la TV está
        // conectada a ÉL) y la búsqueda se bloqueaba con "No estás
        // en Wi-Fi". Con una interfaz 192.168.43.x activa, hay red
        // local aunque no haya Wi-Fi como cliente.
        val hotspot = Discovery.hasHotspotInterface()
        val wifiUp = wifi || hotspot
        _ui.update { current ->
            // P-B: resolver el estado Wi-Fi verificado. Solo se
            // tocan UNAVAILABLE (sin comprobar) y AVAILABLE: un
            // CONNECTED/CONNECTING/FAILED lo manda el eco o el
            // intento, no este sondeo.
            fun resolved(old: TransportStatus): TransportStatus = when (old) {
                TransportStatus.UNAVAILABLE, TransportStatus.AVAILABLE ->
                    if (wifiUp) TransportStatus.AVAILABLE else TransportStatus.UNAVAILABLE
                else -> old
            }
            current.copy(
                wifiConnected = wifiUp,
                transportStatus = current.transportStatus +
                    (TransportMethod.WIFI_UDP to resolved(current.transportStatus[TransportMethod.WIFI_UDP] ?: TransportStatus.UNAVAILABLE)) +
                    (TransportMethod.WIFI_TCP to resolved(current.transportStatus[TransportMethod.WIFI_TCP] ?: TransportStatus.UNAVAILABLE)),
            )
        }
    }

    /**
     * Reacción al cambio de red (2026-11-07): antes, al cambiar de
     * Wi-Fi, al perderla o al activar el hotspot, nada reaccionaba
     * hasta que el ping fallara 3 s después (o hasta reabrir la
     * pantalla). Ahora:
     * - Al aparecer una red: se refresca el estado Wi-Fi/hotspot al
     *   momento (la búsqueda deja de decir "No estás en Wi-Fi"
     *   cuando acabas de activar el hotspot).
     * - Al perderse la red por defecto jugando en AUTO por Wi-Fi:
     *   1 s después, si el eco sigue sin volver (eco anterior a la
     *   pérdida más de 1 s), se fuerza el siguiente método
     *   (tryNextTransport con force). Sin el force, la ventana de
     *   eco de 3 s haría el cambio un no-op hasta los 3 s de
     *   siempre. Si la red volvió sola en ese segundo, el eco ya
     *   está de vuelta y no se cambia nada. Por HID no se cambia:
     *   el HID no usa la Wi-Fi. En MANUAL no se cambia solo: el
     *   error lo muestra refreshPing como siempre.
     * El socket UDP no ligado a interfaz sobrevive al cambio de
     * red solo; lo que cambia es la IP de la TV (DHCP nuevo): si
     * la TV cambia de IP, hay que redescubrirla o reconectar a
     * mano — eso no lo arregla ningún callback y la UI lo dice en
     * el error de "sin respuesta".
     */
    private fun registerNetworkCallback() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshWifiState()
            }

            override fun onLost(network: Network) {
                refreshWifiState()
                if (_ui.value.transportMode != TransportMode.AUTO) return
                if (_ui.value.attemptMethod == TransportMethod.BLUETOOTH_HID) return
                val st = _ui.value.status
                if (st != ConnectionStatus.CONNECTED && st != ConnectionStatus.CONNECTING) return
                viewModelScope.launch {
                    delay(1000)
                    val lastEcho = GamepadSenderService.lastEchoAtMs
                    val echoStale = lastEcho == 0L || System.currentTimeMillis() - lastEcho > 1000
                    if (echoStale) tryNextTransport(force = true)
                }
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    /**
     * Escucha el encendido/apagado del Bluetooth del teléfono.
     * Sin esto, si el Bluetooth estaba apagado al abrir la app,
     * la comprobación HID quedaba en "No disponible" y nada la
     * volvía a intentar al encenderlo (foto del usuario 2026-11-07:
     * chip en "No disponible" para siempre). Al encender:
     * re-comprobación forzada. Al apagar: HID pasa a NO DISPONIBLE
     * de inmediato (el proxy muere con el radio).
     */
    private fun registerBluetoothStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        refreshHidStatus(force = true)
                        refreshBtDataStatus()
                    }
                    BluetoothAdapter.STATE_OFF -> _ui.update {
                        it.copy(
                            hidAvailability = HidAvailability.UNAVAILABLE,
                            transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.UNAVAILABLE) + (TransportMethod.BLUETOOTH_DATA to TransportStatus.UNAVAILABLE),
                        )
                    }
                }
            }
        }
        bluetoothReceiver = receiver
        runCatching {
            getApplication<Application>().registerReceiver(
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            )
        }
    }

    /** Busca TVs con el receptor abierto en la misma Wi-Fi (~3 s). */
    fun discoverTvs() {
        if (_ui.value.discovering) return
        refreshWifiState()
        if (!_ui.value.wifiConnected) {
            _ui.update { it.copy(error = "No estás en Wi-Fi. Conecta el teléfono a la misma red Wi-Fi que la TV.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(discovering = true, error = null) }
            // NSD/mDNS (W6) + broadcast UDP en paralelo: el total es
            // max(3 s broadcast, 4 s NSD) ≈ 4 s, no 7 s en serie.
            // El broadcast queda como respaldo y es la única fuente
            // del PIN; NSD cubre routers que filtran el broadcast
            // o tienen aislamiento AP.
            val broadcastDeferred = async { DiscoveryClient.discover() }
            val nsdDeferred = async { NsdDiscovery.discover(getApplication()) }
            val found = mergeDiscovered(broadcastDeferred.await(), nsdDeferred.await())
            _ui.update {
                it.copy(
                    discovering = false,
                    discoveredTvs = found,
                    error = if (found.isEmpty()) "No se encontró ninguna TV. Comprueba que la TV tiene Ultimate Gamepad abierto y está en la misma Wi-Fi (o conectada a tu hotspot). Si el router bloquea el broadcast o tiene aislamiento AP, escanea el QR de la TV o escribe su IP + PIN abajo." else null,
                )
            }
        }
    }

    /** Conectar con un toque a una TV descubierta (ya trae IP y PIN). */
    fun connectToDiscovered(tv: DiscoveredTv) {
        if (tv.pin.isBlank()) {
            // TV vista SOLO por NSD: NSD nunca anuncia el PIN.
            // No conectar con PIN vacío: pre-rellenar IP/puerto y
            // pedir el PIN que muestra la TV (campos manuales de
            // Ajustes, justo debajo de la lista).
            _ui.update {
                it.copy(
                    host = tv.ip,
                    port = tv.port,
                    error = "Esta TV se encontró por NSD y no anunció su PIN. Escribe abajo el PIN que muestra la TV y pulsa Conectar.",
                )
            }
            return
        }
        connect(PairingInfo(tv.ip, tv.port, tv.pin), _ui.value.selectedMethod)
    }

    /**
     * Fusiona NSD + broadcast UDP sin duplicados: misma IP = una
     * sola entrada. NSD va primero (tiene el nombre real anunciado)
     * y el broadcast pisa la misma IP (trae el PIN, que NSD nunca
     * anuncia). Una TV vista SOLO por NSD queda con pin = "" (PIN
     * pendiente: hay que pedir el PIN que muestra la TV).
     */
    private fun mergeDiscovered(
        broadcast: List<DiscoveredTv>,
        nsd: List<DiscoveredTv>,
    ): List<DiscoveredTv> {
        val merged = LinkedHashMap<String, DiscoveredTv>()
        for (tv in nsd) merged[tv.ip] = tv
        for (tv in broadcast) merged[tv.ip] = tv
        return merged.values.toList()
    }

    private fun connect(info: PairingInfo, method: TransportMethod) {
        if (method == TransportMethod.BLUETOOTH_HID) { connectHid(); return }
        if (method == TransportMethod.BLUETOOTH_DATA) { connectBtData(info); return }
        // A3: persistir la última conexión para que el servicio pueda
        // reanudar solo tras la muerte del proceso (intent nulo).
        LastConnectionPrefs.save(getApplication(), info.host, info.port, info.pin, method)
        // CONECTANDO hasta que la TV devuelva el primer eco de ping:
        // antes se marcaba CONECTADO al instante aunque la TV no
        // respondiera, y la app se quedaba "tieso" en Conectado con
        // ping "—" sin poder avanzar. Nunca CONECTADO sin eco real.
        _ui.update {
            it.copy(status = ConnectionStatus.CONNECTING, host = info.host, port = info.port, pin = info.pin, connectingSinceMs = System.currentTimeMillis(), attemptMethod = method, showSettings = false, showQrScanner = false, error = null, info = null)
        }
        val intent = Intent(getApplication(), GamepadSenderService::class.java).apply {
            action = GamepadSenderService.ACTION_START
            putExtra(GamepadSenderService.EXTRA_HOST, info.host)
            putExtra(GamepadSenderService.EXTRA_PORT, info.port)
            putExtra(GamepadSenderService.EXTRA_METHOD, method.name)
        }
        // En segundo plano el sistema rechaza el arranque (ForegroundServiceStartNotAllowed):
        // antes crasheaba si el fallback de AUTO lo intentaba con la app dormida.
        runCatching { getApplication<Application>().startForegroundService(intent) }
            .onFailure {
                _ui.update {
                    it.copy(status = ConnectionStatus.ERROR, error = "No se pudo iniciar el servicio de envío. Inténtalo de nuevo con la app abierta.")
                }
            }
        // UI-2: el giroscopio vuelve solo si seguía activado:
        // disconnect() solo paró el sensor, sin tocar la
        // preferencia guardada (startGyroSensor es idempotente).
        if (_gyroEnabled.value) startGyroSensor()
    }

    /** Conexión por Bluetooth datos (SPP/RFCOMM, Fase 5), espejo de connectHid(). */
    private fun connectBtData(info: PairingInfo) {
        if (!btDataTransport.hasConnectPermission()) {
            _ui.update {
                it.copy(status = ConnectionStatus.ERROR, error = "Falta el permiso de Bluetooth. Toca \"Dar permiso de Bluetooth\" en el selector de método.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_DATA to TransportStatus.FAILED))
            }
            return
        }
        val mac = LastConnectionPrefs.load(getApplication())?.btAddress?.takeIf { it.isNotBlank() }
        if (mac.isNullOrBlank()) {
            _ui.update {
                it.copy(status = ConnectionStatus.ERROR, error = "Empareja la TV en Ajustes > Bluetooth y elígela en 'TV por Bluetooth' (Ajustes > Conexión).", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_DATA to TransportStatus.FAILED))
            }
            return
        }
        // A3: persistir la última conexión (con la MAC) para que el servicio pueda reanudar solo tras la muerte del proceso.
        LastConnectionPrefs.save(getApplication(), info.host, info.port, info.pin, TransportMethod.BLUETOOTH_DATA, btAddress = mac)
        _ui.update {
            it.copy(status = ConnectionStatus.CONNECTING, host = info.host, port = info.port, pin = info.pin, connectingSinceMs = System.currentTimeMillis(), attemptMethod = TransportMethod.BLUETOOTH_DATA, showSettings = false, showQrScanner = false, error = null, info = null)
        }
        val intent = Intent(getApplication(), GamepadSenderService::class.java).apply {
            action = GamepadSenderService.ACTION_START
            putExtra(GamepadSenderService.EXTRA_HOST, info.host)
            putExtra(GamepadSenderService.EXTRA_PORT, info.port)
            putExtra(GamepadSenderService.EXTRA_METHOD, TransportMethod.BLUETOOTH_DATA.name)
            putExtra(GamepadSenderService.EXTRA_BT_ADDRESS, mac)
        }
        runCatching { getApplication<Application>().startForegroundService(intent) }
            .onFailure {
                _ui.update {
                    it.copy(status = ConnectionStatus.ERROR, error = "No se pudo iniciar el servicio de envío. Inténtalo de nuevo con la app abierta.")
                }
            }
    }

    private fun connectHid() {
        if (hidService.availability == HidAvailability.PERMISSION_DENIED) {
            _ui.update {
                it.copy(status = ConnectionStatus.ERROR, error = "Falta el permiso de Bluetooth. Toca \"Dar permiso de Bluetooth\" en el selector de método.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED))
            }
            return
        }
        if (hidService.availability == HidAvailability.UNKNOWN) {
            // AUTO (TCP -> HID) llegaba aquí con la disponibilidad
            // sin comprobar (el usuario nunca abrió la pantalla de
            // conexión) y fallaba al instante con "no disponible"
            // sin haberlo intentado. Ahora se comprueba de verdad
            // (3 s, el mismo plazo que el timeout de
            // refreshHidStatus — A6 — para que AUTO no dispare
            // otro cambio mientras tanto) y luego se registra o
            // se da el error real.
            // La espera va guardada en hidCheckJob: si el usuario
            // desconecta o AUTO cambia de método antes, se cancela
            // y no resucita la conexión después (antes la espera
            // seguía viva y registraba el HID tras desconectar).
            // Y se limpia la salud de Wi-Fi ANTES de esperar: un
            // eco viejo hacía que refreshPing marcara CONECTADO
            // por HID sin haber registrado nada.
            hidCheckJob?.cancel()
            GamepadSenderService.resetHealth()
            _ui.update {
                it.copy(status = ConnectionStatus.CONNECTING, connectingSinceMs = System.currentTimeMillis(), attemptMethod = TransportMethod.BLUETOOTH_HID, info = "Comprobando Bluetooth HID…")
            }
            refreshHidStatus(force = true)
            hidCheckJob = viewModelScope.launch {
                delay(3000)
                if (hidService.availability == HidAvailability.AVAILABLE) {
                    registerHid()
                } else {
                    _ui.update {
                        it.copy(status = ConnectionStatus.ERROR, error = "Bluetooth HID no disponible en este teléfono (no expone HID_DEVICE o falta permiso). Prueba Wi-Fi.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED))
                    }
                }
            }
            return
        }
        if (hidService.availability != HidAvailability.AVAILABLE) {
            _ui.update {
                it.copy(status = ConnectionStatus.ERROR, error = "Bluetooth HID no disponible en este teléfono (no expone HID_DEVICE o falta permiso). Prueba Wi-Fi.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED))
            }
            return
        }
        registerHid()
    }

    /**
     * Registra la app HID y entra en modo HID. Los fallos ponen
     * estado ERROR de verdad: antes connectHid() dejaba el estado
     * anterior (CONNECTING o CONECTADO por Wi-Fi) y la UI se
     * quedaba en "Conectado" aunque el HID no existiera y AUTO
     * ya no tuviera a dónde ir.
     * El éxito del registro NO es conexión: se queda en CONECTANDO
     * hasta que la TV empareje y conecte, que es lo que marca
     * onHidConnectionChanged (STATE_CONNECTED). Registrar solo dice
     * que el teléfono aceptó el perfil local.
     */
    private fun registerHid() {
        if (!hidService.register(GamepadSender.activeProfileId)) {
            _ui.update {
                it.copy(status = ConnectionStatus.ERROR, error = "No se pudo registrar el mando HID.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED))
            }
            return
        }
        // Entrar en HID: limpiar los ecos viejos de Wi-Fi, que si no
        // harían saltar el fallback como si la TV no respondiera.
        // NO se marca CONECTADO aquí: registerApp() solo dice que el
        // teléfono aceptó el registro local; la TV aún no ha
        // emparejado ni conectado. Marcar CONECTADO aquí era mentir:
        // la UI decía "Conectado" sin que nada funcionara (publish()
        // no envía nada hasta STATE_CONNECTED) y AUTO nunca saltaba
        // de método porque resetHealth() dejaba los marcadores de
        // eco a cero y la rama CONECTADO de refreshPing no disparaba
        // el fallback. El latido HID lo arranca el callback al
        // conectar de verdad (onHidConnectionChanged).
        GamepadSenderService.resetHealth()
        // UI-2: el giroscopio vuelve solo si seguía activado
        // (disconnect() solo paró el sensor).
        if (_gyroEnabled.value) startGyroSensor()
        // H-B: sin visibilidad, la TV no puede descubrir el
        // teléfono para emparejarlo. Se pide al registrar (solo
        // aquí, nunca en el reintento en segundo plano).
        val deviceName = GamepadProfiles.fromId(GamepadSender.activeProfileId).deviceName
        val discoverable = hidService.requestDiscoverable()
        _ui.update {
            it.copy(
                status = ConnectionStatus.CONNECTING, connectingSinceMs = System.currentTimeMillis(), attemptMethod = TransportMethod.BLUETOOTH_HID, error = null,
                info = if (discoverable) "HID registrado como '$deviceName'. El teléfono está visible 5 min: empareja ese nombre en la TV (Ajustes > Bluetooth). Esperando a que la TV conecte…" else "HID registrado como '$deviceName'. Haz visible el teléfono (Ajustes > Bluetooth) y empareja ese nombre en la TV. Esperando a que la TV conecte…",
            )
        }
    }

    /**
     * Reenvío HID a 10 Hz del último estado mientras el HID está
     * conectado. Por HID los reportes solo se enviaban cuando
     * cambiaba la entrada (publish): si un reporte se perdía, el
     * botón quedaba en el estado viejo hasta el siguiente cambio
     * (H3 del plan). El reenvío es idempotente (mismo estado) y
     * barato (7 bytes a 10 Hz); no sustituye al envío por cambio,
     * lo refuerza. Se para al caer el HID o al desconectar.
     */
    private fun startHidHeartbeat() {
        hidHeartbeatJob?.cancel()
        hidHeartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                if (hidService.status == TransportStatus.CONNECTED) {
                    hidService.sendReport(GamepadSender.currentState)
                } else {
                    break
                }
            }
            hidHeartbeatJob = null
        }
    }

    private fun stopHidHeartbeat() {
        hidHeartbeatJob?.cancel()
        hidHeartbeatJob = null
    }

    /**
     * Fallback automático (modo AUTO): si no hay eco de la TV en
     * 3 s con UDP, prueba TCP; si TCP también falla, el siguiente
     * es BLE/SPP (Etapa 2, se salta) y luego HID. Vale tanto en
     * CONECTANDO (sin primer eco) como en CONECTADO (se perdió el
     * eco): en ambos casos la TV no responde por ese método.
     */
    fun tryNextTransport(force: Boolean = false) {
        if (_ui.value.transportMode != TransportMode.AUTO) return
        if (_ui.value.status != ConnectionStatus.CONNECTED && _ui.value.status != ConnectionStatus.CONNECTING) return
        if (!force && GamepadSenderService.isTvResponding()) return
        // A-1: el método actual es el que la UI está intentando
        // (attemptMethod), no el del servicio: tras entrar en HID el
        // servicio sigue con el último método Wi-Fi y la cadena se
        // calculaba mal (p. ej. desde HID volvía a probar TCP).
        // attemptMethod siempre está en AUTO_ORDER: nunca es
        // HOTSPOT (es un modo, no un método) ni un método del
        // servicio sin UI.
        val current = _ui.value.attemptMethod
        val order = TransportSelector.AUTO_ORDER
        // A-1: Bluetooth datos sin TV emparejada no se puede ni
        // intentar: connectBtData() da ERROR terminal ("Empareja la
        // TV…") y la cadena muere ahí sin llegar nunca a HID, que
        // era el último respaldo. En AUTO se salta ese paso cuando
        // no hay MAC guardada; en MANUAL sigue dando su error
        // honesto (el usuario lo eligió a propósito).
        val savedMac = LastConnectionPrefs.load(getApplication())?.btAddress?.takeIf { it.isNotBlank() }
        val next = order.dropWhile { it != current }.drop(1)
            .firstOrNull { it != TransportMethod.BLUETOOTH_DATA || !savedMac.isNullOrBlank() }
        if (next == null) {
            // A7: nombrar el método real del último fallo, no
            // "ningún método Wi-Fi": si se llegó aquí por HID, el
            // fallo fue del HID y decir Wi-Fi confunde.
            _ui.update { it.copy(status = ConnectionStatus.ERROR, error = "Sin respuesta de la TV por $current (último método probado). Revisa la red, el Bluetooth o prueba Hotspot / HID.") }
            return
        }
        val info = PairingInfo(_ui.value.host, _ui.value.port, _ui.value.pin)
        _ui.update { it.copy(error = "Sin eco por $current, probando $next…", transportStatus = it.transportStatus + (current to TransportStatus.FAILED)) }
        disconnect(silent = true)
        connect(info, next)
    }

    fun disconnect(silent: Boolean = false) {
        // H-D: dar de baja el HID. Sin esto, "Desconectar" dejaba el
        // teléfono registrado como mando: publish() seguía enviando
        // reportes a la TV si el enlace seguía activo (solo mira
        // hidService.status) y si la TV reconectaba, la UI volvía a
        // "Conectado" sola por el callback. El reintento en segundo
        // plano ya está cancelado aquí (hidRetryJob), así que nada
        // vuelve a registrarlo tras una desconexión explícita. En
        // caída de TV (onHidDropped) no se pasa por aquí: ahí el
        // HID se queda registrado a propósito para reconectar.
        hidService.unregister()
        hidRetryJob?.cancel(); hidRetryJob = null
        hidCheckJob?.cancel(); hidCheckJob = null
        stopHidHeartbeat()
        // Al desconectar, parar la macro y el turbo: si no, el
        // generador seguiría publicando estados que el servicio
        // ya no envía, y la siguiente conexión empezaría con la
        // macro a medias.
        stopMacroPlayback()
        turboJob?.cancel(); turboJob = null; turboPhaseOn = true
        // P8: al desconectar se resetean teclado y ratón: si no,
        // la UI los seguía mostrando en SÍ con la TV apagada o
        // sin conexión. El reseteo de los flags es SIEMPRE
        // (también en silent: tryNextTransport desconecta antes
        // de reconectar por otro método y el connect() siguiente
        // no toca estos flags, así que sin esto quedarían en SÍ
        // tras el cambio de método). El giroscopio SOLO para el
        // sensor (stopGyroSensor): su preferencia guardada NO se
        // toca — antes setGyroEnabled(false) la persistía en OFF
        // y cada fallback o desconexión apagaba el giro para
        // siempre. Al reconectar, connect()/registerHid() lo
        // rearrancan si seguía activado. Los botones del ratón
        // mantenidos también se sueltan.
        mouseButtons = 0
        stopGyroSensor()
        _ui.update { it.copy(keyboardEnabled = false, mouseEnabled = false) }
        GamepadSenderService.resetHealth()
        val intent = Intent(getApplication(), GamepadSenderService::class.java).apply { action = GamepadSenderService.ACTION_STOP }
        // Parar es mejor esfuerzo: en segundo plano el sistema puede rechazarlo y el
        // servicio ya estaría muerto o se parará al volver; nunca debe crashear.
        runCatching { getApplication<Application>().startService(intent) }
        if (!silent) _ui.update { it.copy(status = ConnectionStatus.DISCONNECTED, pingMs = null, tvResponding = false, info = null) }
    }

    /** Rendimiento (Fase 3): ping, jitter y paquetes del servicio de envío. */
    fun refreshPerformance() {
        val responding = GamepadSenderService.isTvResponding()
        _ui.update {
            it.copy(
                pingMs = GamepadSenderService.lastPingMs,
                pingJitterMs = GamepadSenderService.pingJitterMs,
                packetsPerSec = GamepadSenderService.packetsPerSec,
                packetsSent = GamepadSenderService.packetsSent,
                tvResponding = responding,
                tvPacketsReceived = GamepadSenderService.tvPacketsReceived,
                tvReceivingPackets = GamepadSenderService.tvReceivingPackets,
                error = GamepadSenderService.lastError,
            )
        }
    }

    fun refreshPing() {
        val responding = GamepadSenderService.isTvResponding()
        _ui.update {
            it.copy(
                pingMs = GamepadSenderService.lastPingMs,
                tvResponding = responding,
                tvPacketsReceived = GamepadSenderService.tvPacketsReceived,
                tvReceivingPackets = GamepadSenderService.tvReceivingPackets,
                error = GamepadSenderService.lastError,
            )
        }
        when (_ui.value.status) {
            ConnectionStatus.CONNECTING -> {
                if (responding) {
                    // Primer eco real: ahora sí CONECTADO.
                    _ui.update {
                        it.copy(
                            status = ConnectionStatus.CONNECTED,
                            error = null,
                            transportStatus = it.transportStatus + (it.attemptMethod to TransportStatus.CONNECTED),
                        )
                    }
                } else if (System.currentTimeMillis() - _ui.value.connectingSinceMs > 3000) {
                    // 3 s sin eco: en AUTO se prueba el siguiente
                    // método; en MANUAL se dice el error y se para,
                    // en vez de quedarse tieso en "Conectando".
                    // HID: el emparejado es manual (el usuario abre
                    // los ajustes Bluetooth de la TV, busca el
                    // teléfono y confirma) y tarda más de 3 s. No se
                    // corta ni se cambia de método: la conexión HID
                    // la marca el callback onHidConnectionChanged,
                    // no el eco. Vale en AUTO y en MANUAL.
                    if (_ui.value.attemptMethod == TransportMethod.BLUETOOTH_HID) {
                        // Seguir esperando a que la TV conecte.
                    } else if (_ui.value.transportMode == TransportMode.AUTO) {
                        tryNextTransport()
                    } else {
                        disconnect(silent = true)
                        _ui.update {
                            it.copy(
                                status = ConnectionStatus.ERROR,
                                error = "La TV no responde en ${it.host}. Revisa que la app de la TV esté abierta y en la misma Wi-Fi.",
                            )
                        }
                    }
                }
            }
            ConnectionStatus.CONNECTED -> {
                // En modo AUTO, la falta de eco dispara el fallback.
                // Vale para UDP (sin eco) y para TCP (sin envío
                // bueno reciente): si nunca hubo ni eco ni envío,
                // no se dispara nada (recién conectado).
                if (!responding && (GamepadSenderService.lastEchoAtMs > 0 || GamepadSenderService.lastTcpSendAtMs > 0)) {
                    tryNextTransport()
                }
            }
            else -> {}
        }
    }

    fun setDpad(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        // Igual que setButton: se guardan los bits sin mapear; el
        // remapeo se aplica una sola vez, en publish().
        val before = buttons
        if (up) buttons = buttons or GamepadButtons.DPAD_UP else buttons = buttons and GamepadButtons.DPAD_UP.inv()
        if (down) buttons = buttons or GamepadButtons.DPAD_DOWN else buttons = buttons and GamepadButtons.DPAD_DOWN.inv()
        if (left) buttons = buttons or GamepadButtons.DPAD_LEFT else buttons = buttons and GamepadButtons.DPAD_LEFT.inv()
        if (right) buttons = buttons or GamepadButtons.DPAD_RIGHT else buttons = buttons and GamepadButtons.DPAD_RIGHT.inv()
        // Vibración (Fase 4): si alguna dirección se acaba de pulsar.
        val dpadMask = GamepadButtons.DPAD_UP or GamepadButtons.DPAD_DOWN or GamepadButtons.DPAD_LEFT or GamepadButtons.DPAD_RIGHT
        if ((buttons and dpadMask) and before.inv() != 0) vibrateOnPress()
        publish()
    }
}
