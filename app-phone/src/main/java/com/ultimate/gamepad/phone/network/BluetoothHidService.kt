package com.ultimate.gamepad.phone.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.HidAvailability
import com.ultimate.gamepad.core.HidDescriptor
import com.ultimate.gamepad.core.TransportStatus

/**
 * Bluetooth HID — teléfono como mando real directo a la TV.
 * Etapa 1: estructura real + comprobación de disponibilidad.
 *
 * NO se finge: [checkAvailability] pregunta al sistema si el perfil
 * HID_DEVICE está expuesto en este A17. Si no lo está, la UI muestra
 * "No disponible en este teléfono" y el método queda en FAILED.
 * Si está expuesto, [register] registra el descriptor Xbox 360 y
 * [sendReport] envía reportes HID. La confirmación real (la TV
 * empareja y OpenNOW responde) solo se sabe probándolo en la
 * Hyundai — es parte de la Etapa 0 de Bluetooth.
 *
 * Descriptor: gamepad genérico Xbox 360 (vendor 0x045E, product
 * 0x028E). Reporte HID de 13 bytes, formato estándar de gamepad:
 *   byte 0: botones A/B/X/Y/LB/RB/Back/Start
 *   byte 1: L3/R3/D-pad (4 bits) + resto
 *   bytes 2-3: stick izquierdo X/Y (0..255, centro 128)
 *   bytes 4-5: stick derecho X/Y
 *   bytes 6-7: gatillos LT/RT (0..255)
 * Los valores se escalan desde GamepadState (-1..1 / 0..1).
 * Este descriptor es el que usan los proyectos open-source de
 * BluetoothHidDevice; el descriptor exacto se valida en Etapa 0
 * contra la Hyundai (algunas TVs son estrictas con el descriptor).
 */
class BluetoothHidService(private val context: Context) {
    @Volatile var availability: HidAvailability = HidAvailability.UNKNOWN
        private set
    @Volatile var status: TransportStatus = TransportStatus.UNAVAILABLE
        private set
    @Volatile var connectedDevice: BluetoothDevice? = null
        private set

    private var hidDevice: BluetoothHidDevice? = null
    private var proxyReady = false

    /** Comprueba si el A17 expone HID_DEVICE. No empareja nada. */
    fun checkAvailability(onResult: (HidAvailability) -> Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            availability = HidAvailability.UNAVAILABLE
            status = TransportStatus.UNAVAILABLE
            onResult(availability); return
        }
        if (Build.VERSION.SDK_INT < 28) {
            availability = HidAvailability.UNAVAILABLE
            status = TransportStatus.UNAVAILABLE
            onResult(availability); return
        }
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    proxyReady = true
                    availability = HidAvailability.AVAILABLE
                    status = TransportStatus.AVAILABLE
                    onResult(availability)
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    proxyReady = false
                    availability = HidAvailability.UNAVAILABLE
                    status = TransportStatus.UNAVAILABLE
                    onResult(availability)
                }
            }
        }, BluetoothProfile.HID_DEVICE)
        // Si el firmware no expone el perfil, onServiceConnected no
        // llega nunca: el llamador debe tratar UNKNOWN tras 3 s como
        // UNAVAILABLE. Ver GamepadViewModel.refreshHidStatus().
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                status = TransportStatus.CONNECTED
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                status = TransportStatus.FAILED
            }
        }
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!registered) status = TransportStatus.FAILED
        }
    }

    /** Registra la app HID. Requiere BLUETOOTH_CONNECT (Android 12+). Devuelve false si el proxy no está listo.
     *  Nombre expuesto: "Xbox 360 Controller" (HidDescriptor). Descriptor: Game Pad
     *  (Usage Page 0x01, Usage 0x05) de HidGamepadDescriptor.BYTES. Sin servicios
     *  extra de teclado/ratón/remoto: solo el perfil HID de gamepad. */
    fun register(): Boolean {
        val hid = hidDevice ?: return false
        val sdp = BluetoothHidDeviceAppSdpSettings(
            HidDescriptor.DEVICE_NAME,
            HidDescriptor.DEVICE_DESCRIPTION,
            HidDescriptor.VENDOR_ID,
            HidDescriptor.PRODUCT_ID,
            com.ultimate.gamepad.core.HidGamepadDescriptor.BYTES,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, 0,
        )
        return runCatching { hid.registerApp(sdp, qos, null, null, callback) }.getOrDefault(false)
    }

    /** Envía un reporte HID. Solo tiene efecto si hay un dispositivo conectado; si no, devuelve false. */
    fun sendReport(state: GamepadState): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        val report = hidReportFrom(state)
        return runCatching { hid.sendReport(device, 0, report) }.getOrDefault(false)
    }

    fun hidReportFrom(state: GamepadState): ByteArray {
        fun axis(v: Float): Byte = ((v.coerceIn(-1f, 1f) * 127f) + 128f).toInt().toByte()
        fun trig(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()
        var b0 = 0
        if (state.isPressed(GamepadButtons.A)) b0 = b0 or 0x01
        if (state.isPressed(GamepadButtons.B)) b0 = b0 or 0x02
        if (state.isPressed(GamepadButtons.X)) b0 = b0 or 0x04
        if (state.isPressed(GamepadButtons.Y)) b0 = b0 or 0x08
        if (state.isPressed(GamepadButtons.LB)) b0 = b0 or 0x10
        if (state.isPressed(GamepadButtons.RB)) b0 = b0 or 0x20
        if (state.isPressed(GamepadButtons.SELECT)) b0 = b0 or 0x40
        if (state.isPressed(GamepadButtons.START)) b0 = b0 or 0x80
        var b1 = 0
        if (state.isPressed(GamepadButtons.L3)) b1 = b1 or 0x01
        if (state.isPressed(GamepadButtons.R3)) b1 = b1 or 0x02
        return byteArrayOf(
            b0.toByte(), b1.toByte(),
            axis(state.leftX), axis(-state.leftY),
            axis(state.rightX), axis(-state.rightY),
            trig(state.leftTrigger), trig(state.rightTrigger),
            0, 0, 0, 0, 0,
        )
    }

    fun unregister() {
        runCatching { hidDevice?.unregisterApp() }
        hidDevice = null; proxyReady = false
    }
}
