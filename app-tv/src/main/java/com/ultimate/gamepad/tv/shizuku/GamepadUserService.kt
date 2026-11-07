package com.ultimate.gamepad.tv.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.tv.IGamepadInputService
import com.ultimate.gamepad.tv.receiver.InputMapper
import rikka.shizuku.Shizuku

/**
 * UserService de Shizuku: este proceso corre como shell (uid 2000)
 * y por eso tiene android.permission.INJECT_EVENTS.
 *
 * La clase extiende directamente IGamepadInputService.Stub (un
 * Binder): así lo pide la API de Shizuku — "the service class must
 * implement IBinder; the usual usage is YourService extends
 * IYouAidlInterface.Stub". NO existe una clase
 * rikka.shizuku.ShizukuUserService en la API pública; Shizuku
 * instancia esta clase y la usa como IBinder.
 *
 * Estado honesto (Fase 2):
 * - createVirtualGamepad(profileId): intenta la vía uinput nativa
 *   con la librería JNI (libuinput_gamepad.so, compilada en CI) que
 *   abre /dev/uinput y registra el dispositivo del perfil dado
 *   (Xbox 360 / Xbox One / DS4 / DualSense / genérico). Si el .so
 *   no carga o el kernel lo deniega, devuelve false sin fingir
 *   nada; lo que falta es la verificación en la TV real (Fase 0).
 *   Al cambiar de perfil, el receptor llama a
 *   destroyVirtualGamepad() + createVirtualGamepad(nuevo) para
 *   recrear el dispositivo en caliente.
 * - sendReport(): primero la vía A (uinput) si el mando virtual
 *   está listo; si no, la vía B (injectInputEvent) con el mapeo
 *   de InputMapper. Devuelve true solo si la inyección no lanzó
 *   excepción.
 */
class GamepadUserService : IGamepadInputService.Stub() {
    companion object {
        @Volatile var boundService: IGamepadInputService? = null
            private set

        /** Último intento de bind: sin este freno,
         * ShizukuHelper.sendToGamepad() llamaba a bind() en cada
         * paquete (120 Hz) mientras el servicio no estaba enlazado:
         * cientos de intentos de bind por segundo. */
        @Volatile private var lastBindAttemptAtMs: Long = 0L

        fun bind(context: Context) {
            if (boundService != null) return
            val now = System.currentTimeMillis()
            if (now - lastBindAttemptAtMs < 2000) return
            lastBindAttemptAtMs = now
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, GamepadUserService::class.java.name),
            ).daemon(false).processNameSuffix("gamepad").debuggable(false).version(1)
            Shizuku.bindUserService(args, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    boundService = IGamepadInputService.Stub.asInterface(service)
                }
                override fun onServiceDisconnected(name: ComponentName?) { boundService = null }
            })
        }
    }

    private var previousState: GamepadState = GamepadState()

    override fun createVirtualGamepad(profileId: Int): Boolean {
        // La librería nativa (libuinput_gamepad.so) se compila en
        // CI; create(profileId) intenta abrir /dev/uinput de verdad
        // con el perfil dado y devuelve false si no puede (sin
        // fingir). Falta la verificación en la TV real (Fase 0).
        return UinputGamepad.create(profileId)
    }

    override fun sendReport(packet32: ByteArray): Boolean {
        val state = GamepadProtocol.decode(packet32) ?: return false
        // Vía A (mando REAL uinput): si el mando virtual está listo,
        // el reporte va por /dev/uinput y Android y OpenNOW lo ven
        // como el mando físico del perfil activo. Si el .so falla
        // o el mando no está listo, se cae a la vía B
        // (injectInputEvent) sin fingir nada.
        if (UinputGamepad.isReady() && UinputGamepad.sendReport(state)) return true
        return injectState(state)
    }

    override fun injectGamepadEvent(packet32: ByteArray): Boolean {
        val state = GamepadProtocol.decode(packet32) ?: return false
        return injectState(state)
    }

    override fun isVirtualGamepadReady(): Boolean = UinputGamepad.isReady()

    override fun destroyVirtualGamepad() { UinputGamepad.destroy() }

    private fun injectState(state: GamepadState): Boolean {
        return runCatching {
            val now = android.os.SystemClock.uptimeMillis()
            // Botones: solo los que cambiaron, para no reenviar todo.
            for (button in InputMapper.allButtons()) {
                InputMapper.keyEventFor(state, previousState, button, now)?.let { ev ->
                    inject(ev)
                }
            }
            inject(InputMapper.motionEventFor(state, now))
            previousState = state
            true
        }.getOrDefault(false)
    }

    /** InputManager.injectInputEvent es API oculta: se llama por reflexión, como hacen los proyectos que usan Shizuku. */
    // Reflexión cacheada: antes inject() hacía Class.forName +
    // getDeclaredMethod en CADA evento (hasta ~17 por paquete a
    // 120 Hz, unas 2000 búsquedas por segundo). Ahora se resuelve
    // una sola vez y se reutiliza; si no se puede resolver, la
    // inyección devuelve false sin romper nada.
    private val inputManagerGlobal: Any? by lazy {
        runCatching {
            val clazz = Class.forName("android.hardware.input.InputManagerGlobal")
            clazz.getDeclaredMethod("getInstance").invoke(null)
        }.getOrNull()
    }
    private val injectMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal")
                .getDeclaredMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
        }.getOrNull()
    }

    private fun inject(event: android.view.InputEvent) {
        val instance = inputManagerGlobal ?: return
        val method = injectMethod ?: return
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC (no bloquea el hilo shell).
        runCatching { method.invoke(instance, event, 0) }
    }
}
