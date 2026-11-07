package com.ultimate.gamepad.tv.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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
 *   2. En el Samsung: app Bugjaeger (gratis, sin root) como host ADB.
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
    data class Status(val ready: Boolean, val label: String, val guidance: String)

    fun status(context: Context): Status {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess
        if (!installed) return Status(
            false, "NO INSTALADO",
            "Instala Shizuku (moe.shizuku.privileged.api) en la TV desde Play Store / APKMirror, y arráncalo por ADB por red :5555 con Bugjaeger desde el Samsung. Ver docs/SETUP-SIN-PC.md.",
        )
        if (!Shizuku.pingBinder()) return Status(
            false, "INSTALADO PERO NO ACTIVO",
            "Shizuku está instalado pero su servicio no corre. Ejecuta desde Bugjaeger: sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh contra <IP-TV>:5555, y vuelve a abrir esta app.",
        )
        if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return Status(
            false, "SIN PERMISO",
            "Shizuku corre, pero no concediste permiso a Ultimate Gamepad TV. Abre Shizuku > Ultimate Gamepad TV > Permitir.",
        )
        return Status(true, "ACTIVO", "Shizuku listo. El mando virtual puede intentarse.")
    }

    /**
     * Envía un paquete al UserService (proceso shell). Devuelve true
     * solo si el servicio confirmó la entrega. En Etapa 1 el
     * UserService usa injectInputEvent como fallback; la creación
     * uinput nativa se valida en Etapa 0 (ver UinputGamepad.kt).
     */
    fun sendToGamepad(context: Context, rawPacket: ByteArray, state: GamepadState): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
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
            if (!service.isVirtualGamepadReady()) service.createVirtualGamepad()
            service.sendReport(rawPacket)
        }.getOrDefault(false)
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
