package com.ultimate.gamepad.phone.network

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.core.HidAvailability
import com.ultimate.gamepad.core.HidInputReport
import com.ultimate.gamepad.core.RumbleReport
import com.ultimate.gamepad.core.TransportStatus
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "BluetoothHid"

/**
 * Bluetooth HID — teléfono como mando real directo a la TV.
 * Etapa 1: estructura real + comprobación de disponibilidad.
 *
 * NO se finge: [checkAvailability] pregunta al sistema si el perfil
 * HID_DEVICE está expuesto en este teléfono. Si no lo está, la UI muestra
 * "No disponible en este teléfono" y el método queda en FAILED.
 * Si está expuesto, [register] registra el descriptor del perfil
 * activo (Fase 2) y [sendReport] envía reportes HID. La confirmación real (la TV
 * empareja y OpenNOW responde) solo se sabe probándolo en la
 * Hyundai — es parte de la Etapa 0 de Bluetooth.
 *
 * Descriptor (Fase 2 + rumble, ronda REA GFN+OpenNOW; ORDEN DE
 * BOTONES + GATILLOS corregidos en la ronda Bluetooth dos vías,
 * 2026-11-11): UN SOLO descriptor para todos los perfiles
 * (HidGamepadDescriptor.descriptorFor), 16 botones en el orden que
 * Android espera + gatillos analógicos Brake/Accelerator. Reporte
 * HID de 9 bytes DE DATOS + Report ID (1) = 10 bytes en el cable:
 *   byte 0: A, B, X, Y, LB, RB (usos 1, 2, 4, 5, 7, 8)
 *   byte 1: LT, RT digitales, SELECT, START, HOME, L3, R3, CAPTURE
 *           (usos 9-16)
 *   byte 2: hat switch (0=arriba, 1=arriba-derecha, 2=derecha,
 *           3=abajo-derecha, 4=abajo, 5=abajo-izquierda,
 *           6=izquierda, 7=arriba-izquierda, 8=centro)
 *   bytes 3-6: ejes X, Y, Z, Rz CON SIGNO (-127..127, centro 0),
 *   como declara el descriptor (Logical Minimum -127, Maximum 127).
 *   bytes 7-8: gatillos analógicos LT/RT (0..255).
 * Los valores se escalan desde GamepadState (-1..1 / 0..1).
 * La API de BluetoothHidDevice entrega/recibe los 9 datos SIN el ID
 * (el ID va aparte en sendReport/replyReport/onSetReport): por eso
 * HidInputReport.from devuelve 9 bytes y sendReport pasa el ID 1.
 * El reporte se construye en :core (HidInputReport, función pura
 * con tests); este servicio solo lo envía. El descriptor exacto se
 * valida en Etapa 0 contra la Hyundai (algunas TVs son estrictas
 * con el descriptor).
 *
 * Rumble (vibración del juego, ronda REA GFN+OpenNOW): el descriptor
 * añade una colección PID (Usage Page 0x0F, solo fuerza constante,
 * reportes 2-7, ver RumbleReport.kt). El host manda Set Effect +
 * Set Constant Force + Effect Operation (por control o por
 * interrupción); el servicio los parsea (handleHostReport) y avisa
 * con onRumble(fuerte, débil, duraciónMs). Create New Effect
 * (Feature) asigna siempre el bloque 1 y Block Load / Device Gain
 * se responden por onGetReport. El teléfono tiene UN motor: fuerte
 * y débil colapsan al mismo valor. Por Wi-Fi → uinput NO se puede:
 * leer EV_FF del dispositivo virtual exige /dev/uinput en lectura
 * y Shizuku está descartado (regla 6 del AGENTS.md del repo);
 * queda documentado como límite en docs/PLAN-GAMEPAD-PRO.md,
 * nunca como hecho.
 *
 * L4/R4 (Fase 4, ronda REA GFN+OpenNOW): el descriptor declara 16
 * botones y los 16 son botones estándar; no queda hueco para las
 * paletas como botones propios. En su lugar, el ViewModel las
 * remapea a L3/R3 en publish()
 * (GamepadButtons.extraRemapBits): por HID llegan como L3/R3, que
 * GeForce NOW y OpenNOW sí pasan al juego. Por Wi-Fi, L4/R4
 * siguen viajando además en el byte 29 (uinput en la TV,
 * BTN_TRIGGER_HAPPY2/3), aunque los clientes lo ignoran. El
 * turbo y las macros sí llegan por HID (son pulsaciones normales
 * en el teléfono antes de enviar) y el giroscopio también (mueve
 * el stick derecho como el táctil).
 */
class BluetoothHidService(private val context: Context) {
    @Volatile var availability: HidAvailability = HidAvailability.UNKNOWN
        private set
    @Volatile var status: TransportStatus = TransportStatus.UNAVAILABLE
        private set
    @Volatile var connectedDevice: BluetoothDevice? = null
        private set

    /** Nombre del dispositivo HID conectado (la TV), o null si no hay ninguno. Con runCatching: en Android 12+ leer el nombre exige BLUETOOTH_CONNECT. */
    val connectedDeviceName: String?
        get() = runCatching { connectedDevice?.name }.getOrNull()

    /**
     * Aviso al ViewModel cuando el enlace HID cambia (conectado /
     * caído). Sin esto, una caída de Bluetooth quedaba invisible: el
     * callback ponía FAILED en el servicio y nadie se enteraba — la
     * UI seguía en "Conectado" y el modo AUTO nunca saltaba a Wi-Fi.
     */
    @Volatile var onConnectionChanged: ((TransportStatus) -> Unit)? = null

    /**
     * Aviso al ViewModel cuando el host arranca o para un efecto de
     * rumble: (fuerte 0..255, débil 0..255, duraciónMs; 0xFFFF =
     * infinita). El teléfono tiene un solo motor: el ViewModel usa
     * el máximo de los dos. (0, 0, 0) = parar.
     */
    @Volatile var onRumble: ((strong: Int, weak: Int, durationMs: Int) -> Unit)? = null

    /** Último reporte de entrada enviado (9 bytes de datos, sin el ID 1): para responder a un GET_REPORT de entrada del host. */
    @Volatile private var lastInputPayload: ByteArray = ByteArray(HidInputReport.SIZE_BYTES)
    /** Últimos reportes de salida del host (OpenCode P2-2): el host
     * puede pedirlos con GET_REPORT tras haberlos mandado; si nunca
     * llegaron, se responde con ceros del tamaño correcto, no con
     * ERROR_RSP_INVALID_RPT_ID. */
    @Volatile private var lastSetEffect: ByteArray = ByteArray(RumbleReport.SET_EFFECT_SIZE)
    @Volatile private var lastConstantForce: ByteArray = ByteArray(RumbleReport.SET_CONSTANT_FORCE_SIZE)
    @Volatile private var lastEffectOperation: ByteArray = ByteArray(RumbleReport.EFFECT_OPERATION_SIZE)
    @Volatile private var lastCreateNewEffect: ByteArray = ByteArray(RumbleReport.CREATE_NEW_EFFECT_SIZE)
    /** Estado del efecto de rumble en curso (un solo motor). */
    @Volatile private var rumbleMagnitude: Int = 0
    @Volatile private var rumbleDurationMs: Int = RumbleReport.DURATION_INFINITE
    @Volatile private var rumbleBlockIndex: Int = 0
    @Volatile private var deviceGain: Int = 255

    /** Envío de reportes fuera del hilo principal (OpenCode P1-1):
     * sendReport() es una llamada IPC al stack Bluetooth y publish()
     * + el latido la llaman desde el hilo Main. Un solo hilo en
     * orden (FIFO) mantiene el orden de los reportes y no bloquea
     * la UI. Vive mientras viva el servicio (una instancia por
     * ViewModel); el proceso lo cierra al morir la app. */
    private val sendExecutor = Executors.newSingleThreadExecutor()

    /** ¿La app HID está registrada (registerApp devolvió true)? */
    @Volatile var appRegistered: Boolean = false
        private set

    private var hidDevice: BluetoothHidDevice? = null
    private var proxyReady = false

    /**
     * Generación de la petición de proxy vigente: cada requestProxy
     * abre un listener nuevo y el anterior queda obsoleto. Sin
     * esto, el onServiceConnected de una petición vieja (p. ej. la
     * del arranque) podía llegar tarde y pisar la disponibilidad
     * con un proxy ya cerrado — el chip decía "Disponible" y el
     * registro fallaba, o al revés. Los callbacks con otra
     * generación se ignoran.
     */
    // Ronda 2 P2-6: el ++ sobre @Volatile no es atómico (leer +
    // escribir en dos pasos): dos hilos podían generar el mismo
    // id y resucitar un proxy cerrado. AtomicInteger lo hace
    // atómico de verdad.
    private val proxyRequestId = AtomicInteger(0)

    /** true mientras unregister() está en curso: el callback asíncrono no debe poner FAILED (P2-4 ronda 2). */
    @Volatile private var unregistering: Boolean = false

    /** Cierra el proxy de perfil anterior: cada getProfileProxy sin su closeProfileProxy acumulaba un listener y un proxy. */
    private fun closeProfileProxy() {
        // Invalidar los callbacks pendientes de la petición vieja:
        // si su onServiceConnected llega tarde, se ignora (ver
        // proxyRequestId) en vez de resucitar un proxy cerrado.
        proxyRequestId.incrementAndGet()
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
        val myId = proxyRequestId.incrementAndGet()
        // Sin BLUETOOTH_CONNECT (Android 12+) adapter.isEnabled y
        // getProfileProxy lanzan SecurityException: antes se llegaba
        // a isEnabled sin comprobar el permiso y fuera de
        // runCatching, así que en un primer arranque sin el permiso
        // la app podía crashear al abrir la pantalla de conexión.
        // Ahora el permiso se comprueba primero y se devuelve
        // PERMISSION_DENIED, para que la UI enseñe el botón "Dar
        // permiso de Bluetooth" en vez de romperse.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            availability = HidAvailability.PERMISSION_DENIED
            status = TransportStatus.UNAVAILABLE
            onResult(availability); return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        // Cinturón y tirantes: aunque el permiso esté concedido,
        // isEnabled no debe poder tumbar la comprobación.
        val enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        if (adapter == null || !enabled) {
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
                    if (myId != proxyRequestId.get()) return
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as BluetoothHidDevice
                        proxyReady = true
                        availability = HidAvailability.AVAILABLE
                        status = TransportStatus.AVAILABLE
                        onResult(availability)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (myId != proxyRequestId.get()) return
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
    fun reRegister(onResult: (Boolean) -> Unit, profileId: Int = GamepadProfiles.XBOX_360_ID) {
        if (appRegistered) { onResult(true); return }
        requestProxy { availability ->
            if (availability != HidAvailability.AVAILABLE) { onResult(false); return@requestProxy }
            onResult(register(profileId))
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
                // Sin host no hay rumble: parar la vibración.
                onRumble?.invoke(0, 0, 0)
            } else {
                return
            }
            onConnectionChanged?.invoke(status)
        }
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            // Ronda 2 P2-4: tras unregister() limpio, este callback
            // llegaba con registered=false y pisaba el UNAVAILABLE
            // con FAILED. Si se está des-registrando a propósito,
            // no es un fallo.
            if (!registered && !unregistering) {
                status = TransportStatus.FAILED
                onConnectionChanged?.invoke(status)
            }
        }
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            handleHostReport(type.toInt(), id.toInt(), data)
        }
        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Algunos hosts mandan el rumble por el canal de
            // interrupción en vez de por control: mismo parseo.
            handleHostReport(BluetoothHidDevice.REPORT_TYPE_OUTPUT.toInt(), reportId.toInt(), data)
        }
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val hid = hidDevice ?: return
            val reply: ByteArray? = when {
                type == BluetoothHidDevice.REPORT_TYPE_FEATURE &&
                    id.toInt() == RumbleReport.REPORT_ID_BLOCK_LOAD ->
                    RumbleReport.blockLoadReply(rumbleBlockIndex)
                type == BluetoothHidDevice.REPORT_TYPE_FEATURE &&
                    id.toInt() == RumbleReport.REPORT_ID_DEVICE_GAIN ->
                    RumbleReport.deviceGainReply(deviceGain)
                type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                    id.toInt() == RumbleReport.REPORT_ID_INPUT ->
                    lastInputPayload.copyOf()
                // Tolerancia GET_REPORT (OpenCode P2-2): el host puede
                // pedir los reportes de salida que ya mandó; se le
                // devuelve el último recibido (o ceros del tamaño
                // correcto si nunca llegó), no un error.
                type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                    id.toInt() == RumbleReport.REPORT_ID_SET_EFFECT ->
                    lastSetEffect.copyOf()
                type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                    id.toInt() == RumbleReport.REPORT_ID_SET_CONSTANT_FORCE ->
                    lastConstantForce.copyOf()
                type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                    id.toInt() == RumbleReport.REPORT_ID_EFFECT_OPERATION ->
                    lastEffectOperation.copyOf()
                type == BluetoothHidDevice.REPORT_TYPE_FEATURE &&
                    id.toInt() == RumbleReport.REPORT_ID_CREATE_NEW_EFFECT ->
                    lastCreateNewEffect.copyOf()
                else -> null
            }
            if (reply != null) {
                runCatching { hid.replyReport(device, type, id, reply) }
            } else {
                runCatching { hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID) }
            }
        }
    }

    /**
     * Rumble del host (USB PID 1.0): parsea los reportes de salida
     * y Feature y actualiza el estado del efecto. Llega por
     * onSetReport (control) o por onInterruptData (interrupción):
     * el parseo es el mismo y `data` nunca trae el Report ID.
     * Solo fuerza constante (el descriptor no declara otra): un
     * Set Effect de otro tipo se ignora. Un solo motor: todos los
     * efectos colapsan al bloque que se esté tocando; Create New
     * Effect siempre asigna el bloque 1.
     */
    private fun handleHostReport(type: Int, reportId: Int, data: ByteArray) {
        if (type != BluetoothHidDevice.REPORT_TYPE_OUTPUT.toInt() &&
            type != BluetoothHidDevice.REPORT_TYPE_FEATURE.toInt()
        ) return
        val event = RumbleReport.parseOutput(reportId, data) ?: return
        when (event) {
            is RumbleReport.Event.EffectSet -> {
                if (event.effectType != RumbleReport.EFFECT_TYPE_CONSTANT_FORCE) {
                    Log.d(TAG, "Rumble: Set Effect tipo ${event.effectType} ignorado (solo fuerza constante)")
                    return
                }
                lastSetEffect = data.copyOf()
                rumbleBlockIndex = event.blockIndex
                rumbleDurationMs = event.durationMs
            }
            is RumbleReport.Event.ConstantForceSet -> {
                lastConstantForce = data.copyOf()
                rumbleMagnitude = event.magnitude
            }
            is RumbleReport.Event.Operation -> {
                lastEffectOperation = data.copyOf()
                when (event.operation) {
                    RumbleReport.OP_START, RumbleReport.OP_START_SOLO -> {
                        Log.d(TAG, "Rumble: START bloque ${event.blockIndex} magnitud $rumbleMagnitude dur ${rumbleDurationMs}ms")
                        onRumble?.invoke(rumbleMagnitude, rumbleMagnitude, rumbleDurationMs)
                    }
                    RumbleReport.OP_STOP -> {
                        Log.d(TAG, "Rumble: STOP bloque ${event.blockIndex}")
                        onRumble?.invoke(0, 0, 0)
                    }
                }
            }
            is RumbleReport.Event.CreateNewEffect -> {
                lastCreateNewEffect = data.copyOf()
                rumbleBlockIndex = 1
                Log.d(TAG, "Rumble: Create New Effect tipo ${event.effectType} -> bloque 1")
            }
            is RumbleReport.Event.DeviceGainSet -> {
                deviceGain = event.gain
            }
        }
    }

    /**
     * Registra la app HID con el perfil activo. Requiere BLUETOOTH_CONNECT (Android 12+). Devuelve false si el proxy no está listo.
     *  Nombre expuesto: el deviceName del perfil (GamepadProfiles:
     *  "Xbox 360 Controller", "Xbox One Controller", "Wireless
     *  Controller", "DualSense Wireless Controller" o "Generic
     *  Gamepad"). Descriptor: Game Pad (Usage Page 0x01, Usage 0x05)
     *  de HidGamepadDescriptor.descriptorFor(profileId): UN SOLO
     *  descriptor de 16 botones en el orden Android + gatillos
     *  analógicos Brake/Accelerator, igual para todos los perfiles.
     *  Sin servicios extra de teclado/ratón/remoto: solo el perfil
     *  HID de gamepad. El touchpad de DS4/DualSense NO se emula
     *  (opción (a) de la Fase 2).
     *  Nota: la API real de BluetoothHidDeviceAppSdpSettings es
     *  (name, description, provider, subclass, descriptors); NO acepta
     *  vendorId/productId, así que por Bluetooth HID no se puede fijar el
     *  VID/PID — la identidad completa (con VID/PID) solo la da la
     *  ruta uinput de la TV. Por Bluetooth la identidad es el nombre
     *  expuesto + el descriptor de Game Pad. */
    fun register(profileId: Int = GamepadProfiles.XBOX_360_ID): Boolean {
        unregistering = false
        val profile = GamepadProfiles.fromId(profileId)
        val hid = hidDevice ?: return false
        val sdp = BluetoothHidDeviceAppSdpSettings(
            profile.deviceName,
            profile.deviceName,
            "Ultimate Gamepad",
            0,
            com.ultimate.gamepad.core.HidGamepadDescriptor.descriptorFor(profile.id),
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, 0,
        )
        return runCatching { hid.registerApp(sdp, qos, null, null, callback) }
            .getOrDefault(false)
            .also { appRegistered = it }
    }

    /**
     * H-B: hace visible el teléfono por Bluetooth (300 s) para que
     * la TV pueda descubrirlo y emparejar el mando ("Xbox 360
     * Controller" o el nombre del perfil activo). Sin esto, la TV
     * no ve el teléfono salvo que el usuario tenga abierta la
     * pantalla de ajustes Bluetooth, o que ya estuvieran
     * emparejados: el HID "no conecta para nada" aunque el
     * registro fuera bien. Es un intent del sistema
     * (ACTION_REQUEST_DISCOVERABLE): no necesita ningún permiso
     * nuevo en tiempo de ejecución (BLUETOOTH_ADVERTISE está
     * declarado pero nunca se pide, así que setScanMode no es
     * opción: lanzaría SecurityException). Se llama SOLO al
     * registrar el HID a mano o por fallback AUTO, nunca desde el
     * reintento en segundo plano (reRegister): no se puede abrir
     * un diálogo del sistema cada 15 s. Devuelve false si no se
     * pudo lanzar (p. ej. app en segundo plano): la UI dice
     * entonces que se haga visible a mano.
     */
    fun requestDiscoverable(durationSec: Int = 300): Boolean {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSec)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /**
     * ¿Está el teléfono visible por Bluetooth AHORA? (Gemini P1-1)
     * requestDiscoverable() solo LANZA el diálogo del sistema: si
     * el usuario lo cancela, la TV no ve el mando y nada lo decía.
     * Se comprueba con adapter.scanMode: SCAN_MODE_CONNECTABLE_
     * DISCOVERABLE solo está activo mientras dura la ventana de
     * visibilidad. Requiere BLUETOOTH_CONNECT (Android 12+).
     */
    fun isDiscoverable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return runCatching { adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }.getOrDefault(false)
    }

    /**
     * Envía un reporte HID. Solo tiene efecto si hay un dispositivo conectado; si no, devuelve false.
     * El envío va por el ejecutor de un solo hilo (OpenCode P1-1):
     * sendReport() es IPC al stack Bluetooth y los llamadores
     * (publish y el latido HID) van en el hilo Main; encolar
     * mantiene el orden y no bloquea la UI. Devuelve true si el
     * reporte quedó encolado (el envío real puede fallar en
     * silencio, como antes); los llamadores ignoran el valor.
     */
    fun sendReport(state: GamepadState): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        val report = HidInputReport.from(state)
        lastInputPayload = report.copyOf()
        // Report ID (1) aparte: los datos NO incluyen el ID
        // ("Report data, not including Report Id"); el stack lo
        // pone primero en el cable.
        sendExecutor.execute {
            runCatching { hid.sendReport(device, RumbleReport.REPORT_ID_INPUT, report) }
        }
        return true
    }

    /**
     * Reporte de 9 bytes DE DATOS según el descriptor (sin el
     * Report ID 1: lo pone el stack en sendReport). La construcción
     * vive en :core (HidInputReport.from, función pura con tests):
     * botones en el orden Android (A, B, X, Y, LB, RB, LT/RT
     * digitales, SELECT, START, HOME, L3, R3, CAPTURE), hat,
     * ejes con signo (-127..127, centro 0) y gatillos analógicos
     * (0..255). Se mantiene aquí como delegación para no romper a
     * los llamadores (GamepadViewModel.publish y el latido HID).
     */
    fun hidReportFrom(state: GamepadState): ByteArray = HidInputReport.from(state)

    /**
     * Da de baja la app HID (la TV deja de ver el mando) SIN cerrar
     * el proxy del perfil. registerApp/unregisterApp se pueden llamar
     * varias veces sobre el mismo proxy, y cerrarlo aquí dejaba
     * hidDevice en null: el register() inmediato de setActiveProfile
     * (cambio de perfil) fallaba en silencio y el mando moría al
     * cambiar de perfil sin ningún error en pantalla. El proxy se
     * cierra solo en requestProxy (al pedir uno nuevo) o al morir el
     * proceso. Se marca UNAVAILABLE de inmediato para que publish()
     * deje de enviar reportes en este mismo instante (el callback
     * onAppStatusChanged lo pondría en FAILED después).
     */
    fun unregister() {
        unregistering = true
        runCatching { hidDevice?.unregisterApp() }
        appRegistered = false
        connectedDevice = null
        status = TransportStatus.UNAVAILABLE
    }
}
