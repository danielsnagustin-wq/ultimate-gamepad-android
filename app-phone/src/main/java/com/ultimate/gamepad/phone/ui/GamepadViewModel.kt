package com.ultimate.gamepad.phone.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultimate.gamepad.core.DiscoveredTv
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.HidAvailability
import com.ultimate.gamepad.core.PairingInfo
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportSelector
import com.ultimate.gamepad.core.TransportStatus
import com.ultimate.gamepad.phone.network.BluetoothDataTransport
import com.ultimate.gamepad.phone.network.BluetoothHidService
import com.ultimate.gamepad.phone.network.DiscoveryClient
import com.ultimate.gamepad.phone.network.GamepadSender
import com.ultimate.gamepad.phone.network.GamepadSenderService
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
    // Descubrimiento de TVs en la red (Ajustes > Buscar TV).
    val discovering: Boolean = false,
    val discoveredTvs: List<DiscoveredTv> = emptyList(),
    /** ¿El teléfono está en Wi-Fi? Sin Wi-Fi no hay TV que descubrir. */
    val wifiConnected: Boolean = true,
    // Selector multi-transporte (Etapa 1).
    val transportMode: TransportMode = TransportMode.AUTO,
    val selectedMethod: TransportMethod = TransportMethod.WIFI_UDP,
    val hotspotMode: Boolean = false,
    val transportStatus: Map<TransportMethod, TransportStatus> = mapOf(
        TransportMethod.WIFI_UDP to TransportStatus.AVAILABLE,
        TransportMethod.WIFI_TCP to TransportStatus.AVAILABLE,
        TransportMethod.BLUETOOTH_DATA to TransportStatus.PENDING_STAGE2,
        TransportMethod.BLUETOOTH_HID to TransportStatus.UNAVAILABLE,
    ),
    val hidAvailability: HidAvailability = HidAvailability.UNKNOWN,
)

class GamepadViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(GamepadUiState())
    val ui: StateFlow<GamepadUiState> = _ui.asStateFlow()
    private val hidService = BluetoothHidService(application)

    /** Re-registro HID en segundo plano (caída de Bluetooth de la TV). */
    private var hidRetryJob: Job? = null

    init {
        // La caída de Bluetooth llegaba solo al servicio y nadie se
        // enteraba: ahora el ViewModel reacciona (fallback a Wi-Fi
        // + reintento en segundo plano). Ver onHidConnectionChanged().
        hidService.onConnectionChanged = { status -> onHidConnectionChanged(status) }
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
     */
    fun setActiveProfile(profileId: Int) {
        val profile = GamepadProfiles.fromId(profileId)
        GamepadSender.setActiveProfile(getApplication(), profile.id)
        if (hidService.appRegistered) {
            hidService.unregister()
            hidService.register(profile.id)
        }
        _ui.update { it.copy(info = "Perfil activo: ${profile.name} (${profile.deviceName}). La TV recreará el mando virtual con ese perfil; si OpenNOW no lo re-enumera, reinicia OpenNOW.") }
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
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private var leftTrigger = 0f
    private var rightTrigger = 0f

    fun setButton(button: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or button else buttons and button.inv()
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

    private fun publish() {
        // Sensibilidad: la zona muerta (ajustable en Ajustes,
        // 0.05 por defecto) es el % del recorrido que no hace
        // nada; la curva 0.85 deja el stick más vivo en el centro,
        // sin volverse brusco, para que responda desde el primer toque.
        val state = GamepadState(
            buttons = buttons, leftX = leftX, leftY = leftY,
            rightX = rightX, rightY = rightY,
            leftTrigger = leftTrigger, rightTrigger = rightTrigger,
        ).withDeadZone(_deadZone.value).withResponseCurve(0.85f)
        GamepadSender.currentState = state
        // Bluetooth HID es inyección directa: si está conectado, el
        // reporte va también por HID, sin pasar por la TV receptora.
        if (hidService.status == TransportStatus.CONNECTED) hidService.sendReport(state)
    }

    /** La pantalla del mando es solo el mando: todo lo de conexión vive en Ajustes (⚙). */
    fun openSettings() = _ui.update { it.copy(showSettings = true, error = null) }
    fun closeSettings() = _ui.update { it.copy(showSettings = false, showQrScanner = false, showLayoutEditor = false) }
    fun openQrScanner() = _ui.update { it.copy(showQrScanner = true) }

    /** Panel visual de botones: se abre desde Ajustes y se cierra volviendo al mando. */
    fun openLayoutEditor() = _ui.update { it.copy(showLayoutEditor = true, showSettings = false) }
    fun closeLayoutEditor() = _ui.update { it.copy(showLayoutEditor = false) }

    fun setTransportMode(mode: TransportMode) = _ui.update { it.copy(transportMode = mode) }
    fun setHotspotMode(enabled: Boolean) = _ui.update { it.copy(hotspotMode = enabled) }

    /** Forzar un método manualmente. BLE/SPP no se puede forzar: está en Etapa 2. */
    fun selectMethod(method: TransportMethod) {
        if (method == TransportMethod.BLUETOOTH_DATA) {
            _ui.update { it.copy(error = "Bluetooth datos (BLE/SPP): Pendiente Etapa 2, no se puede forzar todavía.") }
            return
        }
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
        // una comprobación cada 10 s (suficiente: el usuario puede
        // encender el Bluetooth y se re-detecta en el siguiente
        // cambio de estado). Con force=true (tras dar el permiso),
        // se salta el freno.
        val now = System.currentTimeMillis()
        if (!force && now - lastHidCheckAtMs < 10_000) return
        lastHidCheckAtMs = now
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
        viewModelScope.launch {
            delay(3000)
            if (hidService.availability == HidAvailability.UNKNOWN) {
                _ui.update {
                    it.copy(
                        hidAvailability = HidAvailability.UNAVAILABLE,
                        transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.UNAVAILABLE),
                    )
                }
            }
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
                if (_ui.value.status != ConnectionStatus.CONNECTED) {
                    // Estábamos en ERROR tras la caída y sin Wi-Fi: el mando vuelve a ser HID.
                    hidRetryJob?.cancel(); hidRetryJob = null
                    _ui.update { it.copy(status = ConnectionStatus.CONNECTED, attemptMethod = TransportMethod.BLUETOOTH_HID, error = null, info = "Bluetooth reconectado con la TV.") }
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
            // Sin IP guardada: buscar la TV por broadcast y conectar a la primera.
            viewModelScope.launch {
                val found = DiscoveryClient.discover()
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
                // queda colgado el bucle.
                val ok = withTimeoutOrNull(5000) {
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
        _ui.update { it.copy(wifiConnected = wifi) }
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
            val found = DiscoveryClient.discover()
            _ui.update {
                it.copy(
                    discovering = false,
                    discoveredTvs = found,
                    error = if (found.isEmpty()) "No se encontró ninguna TV. Abre Ultimate Gamepad en la TV (el receptor arranca solo) y revisa que esté en la misma Wi-Fi." else null,
                )
            }
        }
    }

    /** Conectar con un toque a una TV descubierta (ya trae IP y PIN). */
    fun connectToDiscovered(tv: DiscoveredTv) {
        connect(PairingInfo(tv.ip, tv.port, tv.pin), _ui.value.selectedMethod)
    }

    private fun connect(info: PairingInfo, method: TransportMethod) {
        if (method == TransportMethod.BLUETOOTH_HID) { connectHid(); return }
        if (method == TransportMethod.BLUETOOTH_DATA) {
            _ui.update { it.copy(error = "Bluetooth datos: Pendiente Etapa 2.") }; return
        }
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
        getApplication<Application>().startForegroundService(intent)
    }

    private fun connectHid() {
        if (hidService.availability == HidAvailability.PERMISSION_DENIED) {
            _ui.update {
                it.copy(error = "Falta el permiso de Bluetooth. Toca \"Dar permiso de Bluetooth\" en el selector de método.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED))
            }
            return
        }
        if (hidService.availability != HidAvailability.AVAILABLE) {
            _ui.update {
                it.copy(error = "Bluetooth HID no disponible en este teléfono (no expone HID_DEVICE o falta permiso). Prueba Wi-Fi.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED))
            }
            return
        }
        if (!hidService.register(GamepadSender.activeProfileId)) {
            _ui.update { it.copy(error = "No se pudo registrar el mando HID.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED)) }
            return
        }
        // Entrar en HID: limpiar los ecos viejos de Wi-Fi, que si no
        // harían saltar el fallback como si la TV no respondiera.
        GamepadSenderService.resetHealth()
        _ui.update {
            it.copy(status = ConnectionStatus.CONNECTED, attemptMethod = TransportMethod.BLUETOOTH_HID, transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.CONNECTED), error = null, info = "HID registrado como '${GamepadProfiles.fromId(GamepadSender.activeProfileId).deviceName}'. Empareja ese nombre en la TV (Ajustes > Mandos). Sin app receptora.")
        }
    }

    /**
     * Fallback automático (modo AUTO): si no hay eco de la TV en
     * 3 s con UDP, prueba TCP; si TCP también falla, el siguiente
     * es BLE/SPP (Etapa 2, se salta) y luego HID. Vale tanto en
     * CONECTANDO (sin primer eco) como en CONECTADO (se perdió el
     * eco): en ambos casos la TV no responde por ese método.
     */
    fun tryNextTransport() {
        if (_ui.value.transportMode != TransportMode.AUTO) return
        if (_ui.value.status != ConnectionStatus.CONNECTED && _ui.value.status != ConnectionStatus.CONNECTING) return
        if (GamepadSenderService.isTvResponding()) return
        val current = GamepadSenderService.activeMethod
        val order = TransportSelector.AUTO_ORDER
        val next = order.dropWhile { it != current }.drop(1).firstOrNull { it != TransportMethod.BLUETOOTH_DATA }
        if (next == null) {
            _ui.update { it.copy(status = ConnectionStatus.ERROR, error = "Sin respuesta de la TV por ningún método Wi-Fi. Revisa la red o prueba Hotspot / HID.") }
            return
        }
        val info = PairingInfo(_ui.value.host, _ui.value.port, _ui.value.pin)
        _ui.update { it.copy(error = "Sin eco por $current, probando $next…", transportStatus = it.transportStatus + (current to TransportStatus.FAILED)) }
        disconnect(silent = true)
        connect(info, next)
    }

    fun disconnect(silent: Boolean = false) {
        hidRetryJob?.cancel(); hidRetryJob = null
        GamepadSenderService.resetHealth()
        val intent = Intent(getApplication(), GamepadSenderService::class.java).apply { action = GamepadSenderService.ACTION_STOP }
        getApplication<Application>().startService(intent)
        if (!silent) _ui.update { it.copy(status = ConnectionStatus.DISCONNECTED, pingMs = null, tvResponding = false, info = null) }
    }

    fun refreshPing() {
        val responding = GamepadSenderService.isTvResponding()
        _ui.update {
            it.copy(
                pingMs = GamepadSenderService.lastPingMs,
                tvResponding = responding,
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
                    if (_ui.value.transportMode == TransportMode.AUTO) {
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
        if (up) buttons = buttons or GamepadButtons.DPAD_UP else buttons = buttons and GamepadButtons.DPAD_UP.inv()
        if (down) buttons = buttons or GamepadButtons.DPAD_DOWN else buttons = buttons and GamepadButtons.DPAD_DOWN.inv()
        if (left) buttons = buttons or GamepadButtons.DPAD_LEFT else buttons = buttons and GamepadButtons.DPAD_LEFT.inv()
        if (right) buttons = buttons or GamepadButtons.DPAD_RIGHT else buttons = buttons and GamepadButtons.DPAD_RIGHT.inv()
        publish()
    }
}
