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
 * HID_DEVICE está expuesto en este teléfono. Si no lo está, la UI muestra
 * "No disponible en este teléfono" y el método queda en FAILED.
 * Si está expuesto, [register] registra el descriptor Xbox 360 y
 * [sendReport] envía reportes HID. La confirmación real (la TV
 * empareja y OpenNOW responde) solo se sabe probándolo en la
 * Hyundai — es parte de la Etapa 0 de Bluetooth.
 *
 * Descriptor: gamepad genérico Xbox 360 (vendor 0x045E, product
 * 0x028E). Reporte HID de 7 bytes, EXACTO según
 * HidGamepadDescriptor.BYTES:
 *   byte 0: botones 1-8 (A, B, X, Y, LB, RB, SELECT, START)
 *   byte 1: botones 9-12 (L3, R3, LT digital, RT digital) + relleno
 *   byte 2: hat switch (0=arriba, 1=arriba-derecha, 2=derecha,
 *           3=abajo-derecha, 4=abajo, 5=abajo-izquierda,
 *           6=izquierda, 7=arriba-izquierda, 8=centro)
 *   bytes 3-6: ejes X, Y, Z, Rz (0..255, centro 128; Y arriba = 0)
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

    /**
     * Aviso al ViewModel cuando el enlace HID cambia (conectado /
     * caído). Sin esto, una caída de Bluetooth quedaba invisible: el
     * callback ponía FAILED en el servicio y nadie se enteraba — la
     * UI seguía en "Conectado" y el modo AUTO nunca saltaba a Wi-Fi.
     */
    @Volatile var onConnectionChanged: ((TransportStatus) -> Unit)? = null

    /** ¿La app HID está registrada (registerApp devolvió true)? */
    @Volatile var appRegistered: Boolean = false
        private set

    private var hidDevice: BluetoothHidDevice? = null
    private var proxyReady = false

    /** Cierra el proxy de perfil anterior: cada getProfileProxy sin su closeProfileProxy acumulaba un listener y un proxy. */
    private fun closeProfileProxy() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val proxy = hidDevice
        if (adapter != null && proxy != null) {
            runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
        }
        hidDevice = null; proxyReady = false
    }

    /**
     * Pide el proxy HID_DEVICE, cerrando el anterior. Sin
     * BLUETOOTH_CONNECT (Android 12+) getProfileProxy lanza
     * SecurityException: se devuelve PERMISSION_DENIED en vez de
     * crashear (la app nunca pedía ese permiso en tiempo de
     * ejecución: abrir la pantalla de conexión sin él crasheaba).
     */
    private fun requestProxy(onResult: (HidAvailability) -> Unit) {
        closeProfileProxy()
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
        val result = runCatching {
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
        }
        val error = result.exceptionOrNull()
        if (error is SecurityException) {
            availability = HidAvailability.PERMISSION_DENIED
            status = TransportStatus.UNAVAILABLE
            onResult(availability)
            return
        }
        if (error != null || result.getOrDefault(false) == false) {
            availability = HidAvailability.UNAVAILABLE
            status = TransportStatus.UNAVAILABLE
            onResult(availability)
            return
        }
        // Si el firmware no expone el perfil, onServiceConnected no
        // llega nunca: el llamador debe tratar UNKNOWN tras 3 s como
        // UNAVAILABLE. Ver GamepadViewModel.refreshHidStatus().
    }

    /** Comprueba si el teléfono expone HID_DEVICE. No empareja nada. */
    fun checkAvailability(onResult: (HidAvailability) -> Unit) = requestProxy(onResult)

    /**
     * Vuelve a registrar la app HID (para la reconexión automática
     * con backoff del ViewModel). Si ya está registrada, no hace
     * nada: registrar dos veces seguidas puede fallar y no aporta
     * nada. Devuelve true si la app quedó registrada. Ojo: si el
     * firmware no expone el perfil, onResult puede no llegar nunca;
     * el llamador debe usar timeout.
     */
    fun reRegister(onResult: (Boolean) -> Unit) {
        if (appRegistered) { onResult(true); return }
        requestProxy { availability ->
            if (availability != HidAvailability.AVAILABLE) { onResult(false); return@requestProxy }
            onResult(register())
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                status = TransportStatus.CONNECTED
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                status = TransportStatus.FAILED
            } else {
                return
            }
            onConnectionChanged?.invoke(status)
        }
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            if (!registered) {
                status = TransportStatus.FAILED
                onConnectionChanged?.invoke(status)
            }
        }
    }

    /** Registra la app HID. Requiere BLUETOOTH_CONNECT (Android 12+). Devuelve false si el proxy no está listo.
     *  Nombre expuesto: "Xbox 360 Controller" (HidDescriptor). Descriptor: Game Pad
     *  (Usage Page 0x01, Usage 0x05) de HidGamepadDescriptor.BYTES. Sin servicios
     *  extra de teclado/ratón/remoto: solo el perfil HID de gamepad.
     *  Nota: la API real de BluetoothHidDeviceAppSdpSettings es
     *  (name, description, provider, subclass, descriptors); NO acepta
     *  vendorId/productId, así que por Bluetooth HID no se puede fijar el
     *  VID/PID 0x045E/0x028E — la identidad completa de Xbox 360 (con VID/PID)
     *  solo la da la ruta uinput de la fase 2 en la TV. Por Bluetooth la
     *  identidad es el nombre expuesto + el descriptor de Game Pad. */
    fun register(): Boolean {
        val hid = hidDevice ?: return false
        val sdp = BluetoothHidDeviceAppSdpSettings(
            HidDescriptor.DEVICE_NAME,
            HidDescriptor.DEVICE_DESCRIPTION,
            "Ultimate Gamepad",
            0,
            com.ultimate.gamepad.core.HidGamepadDescriptor.BYTES,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, 0,
        )
        return runCatching { hid.registerApp(sdp, qos, null, null, callback) }
            .getOrDefault(false)
            .also { appRegistered = it }
    }

    /** Envía un reporte HID. Solo tiene efecto si hay un dispositivo conectado; si no, devuelve false. */
    fun sendReport(state: GamepadState): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        val report = hidReportFrom(state)
        return runCatching { hid.sendReport(device, 0, report) }.getOrDefault(false)
    }

    /** Reporte de 7 bytes según el descriptor. La versión anterior
     * mandaba 13 bytes con otra disposición: el eje izquierdo caía
     * en el byte del hat (basura), los ejes salían revueltos
     * (X=leftY, Y=rightX, Z=rightY, Rz=LT), la cruceta nunca se
     * enviaba como hat y el RT se ignoraba. La TV leía botones y
     * sticks equivocados por Bluetooth HID. */
    fun hidReportFrom(state: GamepadState): ByteArray {
        fun axis(v: Float): Byte = ((v.coerceIn(-1f, 1f) * 127f) + 128f).toInt().toByte()
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
        // El descriptor no tiene ejes de gatillo: LT/RT van como
        // botones digitales 11/12 al pasar de la mitad.
        if (state.leftTrigger > 0.5f) b1 = b1 or 0x04
        if (state.rightTrigger > 0.5f) b1 = b1 or 0x08
        val up = state.isPressed(GamepadButtons.DPAD_UP)
        val down = state.isPressed(GamepadButtons.DPAD_DOWN)
        val left = state.isPressed(GamepadButtons.DPAD_LEFT)
        val right = state.isPressed(GamepadButtons.DPAD_RIGHT)
        val hat = when {
            up && right -> 1
            right && down -> 3
            down && left -> 5
            left && up -> 7
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> 8 // Centro: sin dirección (valor nulo del hat, estándar HID).
        }
        return byteArrayOf(
            b0.toByte(), b1.toByte(), hat.toByte(),
            axis(state.leftX), axis(state.leftY),
            axis(state.rightX), axis(state.rightY),
        )
    }

    fun unregister() {
        runCatching { hidDevice?.unregisterApp() }
        appRegistered = false
        closeProfileProxy()
    }
}
