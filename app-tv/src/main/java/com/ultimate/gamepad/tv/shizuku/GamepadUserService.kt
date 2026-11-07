package com.ultimate.gamepad.tv.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.view.InputManager
import com.ultimate.gamepad.core.GamepadProtocol
import com.ultimate.gamepad.core.GamepadState
import com.ultimate.gamepad.tv.IGamepadInputService
import com.ultimate.gamepad.tv.receiver.InputMapper
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuUserService

/**
 * UserService de Shizuku: este proceso corre como shell (uid 2000)
 * y por eso tiene android.permission.INJECT_EVENTS.
 *
 * Hereda de rikka.shizuku.ShizukuUserService (API Shizuku 13).
 * Shizuku lo lanza; la app normal solo se enlaza a él.
 *
 * Estado honesto en Etapa 1:
 * - createVirtualGamepad(): intenta la vía uinput nativa. Esa vía
 *   necesita una librería JNI (libuinput.so) que abre /dev/uinput
 *   y registra un Xbox 360. Esa librería NO está en Etapa 1: es el
 *   trabajo de la Etapa 0 en la Hyundai real (ver UinputGamepad.kt).
 *   Por eso este método devuelve false hasta que la Etapa 0 lo
 *   demuestre en la TV. No se devuelve true para "que parezca".
 * - sendReport(): usa injectInputEvent (fallback) mientras tanto,
 *   con el mapeo de InputMapper. Devuelve true solo si la inyección
 *   no lanzó excepción.
 */
class GamepadUserService : ShizukuUserService() {
    companion object {
        @Volatile var boundService: IGamepadInputService? = null
            private set

        fun bind(context: Context) {
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
    private val binder = object : IGamepadInputService.Stub() {
        override fun createVirtualGamepad(): Boolean {
            // Etapa 0 pendiente: UinputGamepad.create() aún no tiene
            // su .so nativo. Devolver false es correcto aquí.
            return UinputGamepad.create()
        }

        override fun sendReport(packet32: ByteArray): Boolean {
            val state = GamepadProtocol.decode(packet32) ?: return false
            return injectState(state)
        }

        override fun injectGamepadEvent(packet32: ByteArray): Boolean {
            val state = GamepadProtocol.decode(packet32) ?: return false
            return injectState(state)
        }

        override fun isVirtualGamepadReady(): Boolean = UinputGamepad.isReady()

        override fun destroyVirtualGamepad() { UinputGamepad.destroy() }
    }

    override fun onBind(intent: android.content.Intent?): IBinder = binder

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
    private fun inject(event: android.view.InputEvent) {
        val clazz = Class.forName("android.hardware.input.InputManagerGlobal")
        val getInstance = clazz.getDeclaredMethod("getInstance")
        val instance = getInstance.invoke(null)
        val inject = clazz.getDeclaredMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC (no bloquea el hilo shell).
        inject.invoke(instance, event, 0)
    }
}
