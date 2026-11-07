package com.ultimate.gamepad.phone.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.HidAvailability
import com.ultimate.gamepad.core.PairingInfo
import com.ultimate.gamepad.core.TransportMethod
import com.ultimate.gamepad.core.TransportSelector
import com.ultimate.gamepad.core.TransportStatus
import com.ultimate.gamepad.phone.network.BluetoothDataTransport
import com.ultimate.gamepad.phone.network.BluetoothHidService
import com.ultimate.gamepad.phone.network.GamepadSender
import com.ultimate.gamepad.phone.network.GamepadSenderService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val error: String? = null,
    val showConnectSheet: Boolean = false,
    val showQrScanner: Boolean = false,
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

    private fun publish() {
        val state = GamepadState(
            buttons = buttons, leftX = leftX, leftY = leftY,
            rightX = rightX, rightY = rightY,
            leftTrigger = leftTrigger, rightTrigger = rightTrigger,
        ).withDeadZone(0.12f)
        GamepadSender.currentState = state
        // Bluetooth HID es inyección directa: si está conectado, el
        // reporte va también por HID, sin pasar por la TV receptora.
        if (hidService.status == TransportStatus.CONNECTED) hidService.sendReport(state)
    }

    fun openConnect() = _ui.update { it.copy(showConnectSheet = true, error = null) }
    fun closeConnect() = _ui.update { it.copy(showConnectSheet = false, showQrScanner = false) }
    fun openQrScanner() = _ui.update { it.copy(showQrScanner = true) }

    fun setTransportMode(mode: TransportMode) = _ui.update { it.copy(transportMode = mode) }
    fun setHotspotMode(enabled: Boolean) = _ui.update { it.copy(hotspotMode = enabled) }

    /** Forzar un método manualmente. BLE/SPP no se puede forzar: está en Etapa 2. */
    fun selectMethod(method: TransportMethod) {
        if (method == TransportMethod.BLUETOOTH_DATA) {
            _ui.update { it.copy(error = "Bluetooth datos (BLE/SPP): Pendiente Etapa 2, no se puede forzar todavía.") }
            return
        }
        _ui.update { it.copy(selectedMethod = method, transportMode = TransportMode.MANUAL, error = null) }
        // Si ya hay conexión, reconectar con el método nuevo.
        val host = _ui.value.host
        if (_ui.value.status == ConnectionStatus.CONNECTED && host.isNotBlank()) {
            connect(PairingInfo(host, _ui.value.port, _ui.value.pin), method)
        }
    }

    /** Comprueba HID en el A17 y actualiza su estado real. Llamar al abrir la pantalla de conexión. */
    fun refreshHidStatus() {
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

    private fun connect(info: PairingInfo, method: TransportMethod) {
        if (method == TransportMethod.BLUETOOTH_HID) { connectHid(); return }
        if (method == TransportMethod.BLUETOOTH_DATA) {
            _ui.update { it.copy(error = "Bluetooth datos: Pendiente Etapa 2.") }; return
        }
        _ui.update {
            it.copy(status = ConnectionStatus.CONNECTING, host = info.host, port = info.port, pin = info.pin, showConnectSheet = false, showQrScanner = false, error = null)
        }
        val intent = Intent(getApplication(), GamepadSenderService::class.java).apply {
            action = GamepadSenderService.ACTION_START
            putExtra(GamepadSenderService.EXTRA_HOST, info.host)
            putExtra(GamepadSenderService.EXTRA_PORT, info.port)
            putExtra(GamepadSenderService.EXTRA_METHOD, method.name)
        }
        getApplication<Application>().startForegroundService(intent)
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    status = ConnectionStatus.CONNECTED,
                    transportStatus = it.transportStatus + (method to TransportStatus.CONNECTED),
                )
            }
        }
    }

    private fun connectHid() {
        if (hidService.availability != HidAvailability.AVAILABLE) {
            _ui.update {
                it.copy(error = "Bluetooth HID no disponible en este teléfono (el A17 no expone HID_DEVICE o falta permiso). Prueba Wi-Fi.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED))
            }
            return
        }
        if (!hidService.register()) {
            _ui.update { it.copy(error = "No se pudo registrar el mando HID.", transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.FAILED)) }
            return
        }
        _ui.update {
            it.copy(status = ConnectionStatus.CONNECTED, transportStatus = it.transportStatus + (TransportMethod.BLUETOOTH_HID to TransportStatus.CONNECTED), error = "HID registrado como 'Xbox 360 Controller'. Empareja ese nombre en la TV (Ajustes > Mandos). Sin app receptora.")
        }
    }

    /**
     * Fallback automático (modo AUTO): si no hay eco de la TV en
     * 3 s con UDP, prueba TCP; si TCP también falla, el siguiente
     * es BLE/SPP (Etapa 2, se salta) y luego HID. Solo se cambia si
     * el modo es AUTO y hay una conexión activa.
     */
    fun tryNextTransport() {
        if (_ui.value.transportMode != TransportMode.AUTO) return
        if (_ui.value.status != ConnectionStatus.CONNECTED) return
        if (GamepadSenderService.isTvResponding()) return
        val current = GamepadSenderService.activeMethod
        val order = TransportSelector.AUTO_ORDER
        val next = order.dropWhile { it != current }.drop(1).firstOrNull { it != TransportMethod.BLUETOOTH_DATA }
        if (next == null) {
            _ui.update { it.copy(status = ConnectionStatus.ERROR, error = "Sin respuesta de la TV por ningún método Wi-Fi. Revisa la red o prueba Hotspot / HID.") }
            return
        }
        val info = PairingInfo(_ui.value.host, _ui.value.port, _ui.value.pin)
        _ui.update { it.copy(error = "Sin eco por $current, probando $next…") }
        disconnect(silent = true)
        connect(info, next)
    }

    fun disconnect(silent: Boolean = false) {
        val intent = Intent(getApplication(), GamepadSenderService::class.java).apply { action = GamepadSenderService.ACTION_STOP }
        getApplication<Application>().startService(intent)
        if (!silent) _ui.update { it.copy(status = ConnectionStatus.DISCONNECTED, pingMs = null, tvResponding = false) }
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
        // En modo AUTO, la falta de eco dispara el fallback.
        if (_ui.value.status == ConnectionStatus.CONNECTED && !responding && GamepadSenderService.lastEchoAtMs > 0) {
            tryNextTransport()
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
