package com.ultimate.gamepad.tv.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import com.ultimate.gamepad.core.GamepadProfiles
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.tv.IGamepadInputService
import rikka.shizuku.Shizuku
import java.net.InetAddress

/**
 * Puente con Shizuku — con la limitación REAL de la Hyundai Google TV.
 *
 * Dato del usuario (2026-11-07): esta Hyundai NO tiene la opción de
 * depuración inalámbrica en Opciones de desarrollador. Por tanto el
 * arranque de Shizuku por "Wireless Debugging" NO aplica aquí.
 *
 * Arranque válido para esta TV:
 *   1. En la TV: Ajustes > Opciones de desarrollador > Depuración USB
 *      (o "ADB") activada, y "ADB por red" / depuración por red en el
 *      puerto 5555, si la TV lo expone.
 *   2. En el teléfono: app Bugjaeger (gratis, sin root) como host ADB.
 *      Conectar a <IP-TV>:5555 y ejecutar:
 *        sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
 *   3. En la TV, abrir Shizuku y conceder permiso a Ultimate Gamepad TV.
 *   4. Shizuku debe re-arrancarse tras cada reinicio de la TV con el
 *      mismo comando ADB. No hay arranque automático sin root.
 *
 * Si Shizuku no está instalado, no está corriendo o no concedió el
 * permiso, [status] lo dice y [sendToGamepad] devuelve false. La UI
 * muestra esa verdad; nunca se finge un mando.
 */
object ShizukuHelper {
    // T11: motivo del estado, para decidir por estado y no por
    // texto (antes shizukuNextStep hacía label.contains(...)).
    enum class ShizukuState { READY, NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION }

    data class Status(val ready: Boolean, val label: String, val guidance: String, val state: ShizukuState)

    // T3: freno al permiso de Shizuku. Antes, sendToGamepad()
    // llamaba a Shizuku.requestPermission(0) por paquete (~120 Hz)
    // mientras no había permiso, sin observar el resultado: un
    // diálogo por paquete. Ahora se pide como mucho una vez cada
    // 10 s, y el listener observa el resultado: si se concede, el
    // freno se resetea para no esperar; si se deniega, no se
    // insiste hasta que pasen los 10 s.
    @Volatile private var lastPermissionRequestAtMs: Long = 0L
    private const val PERMISSION_REQUEST_MIN_INTERVAL_MS = 10_000L
    @Volatile private var permissionListenerRegistered: Boolean = false

    private fun ensurePermissionResultListener() {
        if (permissionListenerRegistered) return
        permissionListenerRegistered = true
        runCatching {
            Shizuku.addRequestPermissionResultListener(
                object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != 0) return
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            lastPermissionRequestAtMs = 0L
                        }
                    }
                },
            )
        }
    }

    fun status(context: Context): Status {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess
        if (!installed) return Status(
            false, "NO INSTALADO",
            "Instala Shizuku (moe.shizuku.privileged.api) en la TV desde Play Store / APKMirror, y arráncalo por ADB por red :5555 con Bugjaeger desde el teléfono. Ver docs/SETUP-SIN-PC.md.",
            ShizukuState.NOT_INSTALLED,
        )
        if (!Shizuku.pingBinder()) return Status(
            false, "INSTALADO PERO NO ACTIVO",
            "Shizuku está instalado pero su servicio no corre. Ejecuta desde Bugjaeger: sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh contra <IP-TV>:5555, y vuelve a abrir esta app.",
            ShizukuState.NOT_RUNNING,
        )
        if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return Status(
            false, "SIN PERMISO",
            "Shizuku corre, pero no concediste permiso a Ultimate Gamepad TV. Abre Shizuku > Ultimate Gamepad TV > Permitir.",
            ShizukuState.NO_PERMISSION,
        )
        return Status(true, "ACTIVO", "Shizuku listo. El mando virtual puede intentarse.", ShizukuState.READY)
    }

    /**
     * Envía un paquete al UserService (proceso shell). Devuelve true
     * solo si el servicio confirmó la entrega. [profileId] es el
     * perfil de emulación del paquete (byte 28, Fase 2): si el
     * mando virtual no está creado, se crea con ese perfil.
     * En Etapa 1 el UserService usa injectInputEvent como fallback;
     * la creación uinput nativa se valida en Etapa 0 (ver
     * UinputGamepad.kt).
     */
    fun sendToGamepad(context: Context, rawPacket: ByteArray, state: GamepadState, profileId: Int = GamepadProfiles.XBOX_360_ID): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            // T3: pedir con freno (máx. 1 vez cada 10 s) y observar
            // el resultado con el listener registrado arriba.
            ensurePermissionResultListener()
            val now = System.currentTimeMillis()
            if (now - lastPermissionRequestAtMs >= PERMISSION_REQUEST_MIN_INTERVAL_MS) {
                lastPermissionRequestAtMs = now
                Shizuku.requestPermission(0)
            }
            return false
        }
        // El bind real al UserService se hace en GamepadUserService.bind();
        // aquí se delega al puente en memoria si ya está enlazado.
        val service: IGamepadInputService? = GamepadUserService.boundService
        if (service == null) {
            GamepadUserService.bind(context)
            return false
        }
        return runCatching {
            if (!service.isVirtualGamepadReady()) service.createVirtualGamepad(profileId)
            service.sendReport(rawPacket)
        }.getOrDefault(false)
    }

    /**
     * T1: destruye el mando virtual actual (sin crear otro).
     * Devuelve false si Shizuku no está listo o no hay servicio
     * enlazado. Lo usa UdpReceiverService al detener el receptor
     * y en onDestroy, para que el mando no quede listado sin
     * entrada y ningún botón quede atascado en el juego.
     */
    fun destroyVirtualGamepad(context: Context): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
        val service: IGamepadInputService? = GamepadUserService.boundService
        if (service == null) {
            GamepadUserService.bind(context)
            return false
        }
        return runCatching {
            service.destroyVirtualGamepad()
            true
        }.getOrDefault(false)
    }

    /**
     * Cambio de perfil en caliente (Fase 2): destruye el mando
     * virtual actual y crea uno nuevo con [profileId]. Devuelve
     * false si Shizuku no está listo o no hay servicio enlazado:
     * el llamador (UdpReceiverService) lo reintenta con el
     * siguiente paquete. Si OpenNOW no re-enumera el mando nuevo
     * sin reiniciarse, la UI debe decir "reinicia OpenNOW"
     * (a verificar en la Fase 0/2).
     */
    fun recreateVirtualGamepad(context: Context, profileId: Int): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
        val service: IGamepadInputService? = GamepadUserService.boundService
        if (service == null) {
            GamepadUserService.bind(context)
            return false
        }
        return runCatching {
            service.destroyVirtualGamepad()
            service.createVirtualGamepad(profileId)
        }.getOrDefault(false)
    }

    /**
     * Teclado virtual (Fase 4): tecla evdev al UserService
     * (proceso shell), que la escribe en el dispositivo de
     * teclado aparte. Devuelve false si Shizuku no está listo o
     * no hay servicio enlazado. [action]: 0 = pulsar, 1 =
     * soltar, 2 = crear el dispositivo, 3 = destruirlo.
     */
    fun sendKeyboardEvent(context: Context, keyCode: Int, action: Int): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
        val service: IGamepadInputService? = GamepadUserService.boundService
        if (service == null) {
            GamepadUserService.bind(context)
            return false
        }
        return runCatching { service.sendKeyboardEvent(keyCode, action) }.getOrDefault(false)
    }

    /**
     * Modo ratón (Fase 4): movimiento relativo + botones al
     * UserService, que los escribe en el dispositivo de ratón
     * aparte. [action]: 0 = mover/clic, 1 = crear, 2 = destruir.
     */
    fun sendMouseEvent(context: Context, dx: Int, dy: Int, buttons: Int, action: Int): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
        val service: IGamepadInputService? = GamepadUserService.boundService
        if (service == null) {
            GamepadUserService.bind(context)
            return false
        }
        return runCatching { service.sendMouseEvent(dx, dy, buttons, action) }.getOrDefault(false)
    }

    /** Destruye los dispositivos de teclado y ratón (Fase 4). El mando no se toca. */
    fun destroyExtraDevices(context: Context): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
        val service: IGamepadInputService? = GamepadUserService.boundService
        if (service == null) {
            GamepadUserService.bind(context)
            return false
        }
        return runCatching { service.destroyExtraDevices(); true }.getOrDefault(false)
    }

    /** Solo se usa desde MainActivity, que pasa el WifiManager real. */
    fun localIpFromWifi(wifi: WifiManager): String? {
        val ip = wifi.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return InetAddress.getByAddress(
            byteArrayOf((ip and 0xFF).toByte(), ((ip shr 8) and 0xFF).toByte(), ((ip shr 16) and 0xFF).toByte(), ((ip shr 24) and 0xFF).toByte()),
        ).hostAddress
    }
}
