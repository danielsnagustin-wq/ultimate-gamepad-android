package com.ultimate.gamepad.tv

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * IPC con el UserService de Shizuku (proceso shell, uid 2000).
 *
 * Antes esto era un .aidl; se pasó a Binder escrito a mano porque la
 * generación AIDL no resolvía la clase en la compilación de CI y
 * bloqueaba :app-tv:compileDebugKotlin. El contrato es el mismo:
 * paquete de 32 bytes (GamepadProtocol) por Binder, sin Parcelable
 * nuevo. La app normal NUNCA llama a esto directamente: solo
 * ShizukuHelper/GamepadUserService a través de Shizuku.
 *
 * Equivalente exacto del .aidl anterior: mismos 5 métodos, mismo
 * DESCRIPTOR, mismos códigos de transacción en el mismo orden.
 */
interface IGamepadInputService : IInterface {
    /** Intenta crear el mando virtual en /dev/uinput con el perfil dado (GamepadProfiles: 0 = Xbox 360). Devuelve true solo si el kernel lo aceptó. */
    fun createVirtualGamepad(profileId: Int): Boolean
    /** Envía un paquete de 32 bytes ya validado. Devuelve false si no hay mando virtual. */
    fun sendReport(packet32: ByteArray): Boolean
    /** Inyecta un KeyEvent/MotionEvent con source gamepad (fallback, ver docs). */
    fun injectGamepadEvent(packet32: ByteArray): Boolean
    fun isVirtualGamepadReady(): Boolean
    fun destroyVirtualGamepad()

    abstract class Stub : Binder(), IGamepadInputService {
        companion object {
            const val DESCRIPTOR = "com.ultimate.gamepad.tv.IGamepadInputService"
            const val TRANSACTION_createVirtualGamepad = IBinder.FIRST_CALL_TRANSACTION + 0
            const val TRANSACTION_sendReport = IBinder.FIRST_CALL_TRANSACTION + 1
            const val TRANSACTION_injectGamepadEvent = IBinder.FIRST_CALL_TRANSACTION + 2
            const val TRANSACTION_isVirtualGamepadReady = IBinder.FIRST_CALL_TRANSACTION + 3
            const val TRANSACTION_destroyVirtualGamepad = IBinder.FIRST_CALL_TRANSACTION + 4

            fun asInterface(obj: IBinder?): IGamepadInputService? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface(DESCRIPTOR)
                if (iin is IGamepadInputService) return iin
                return Proxy(obj)
            }
        }

        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }
                TRANSACTION_createVirtualGamepad -> {
                    data.enforceInterface(DESCRIPTOR)
                    val profileId = data.readInt()
                    val result = createVirtualGamepad(profileId)
                    reply?.writeNoException()
                    reply?.writeInt(if (result) 1 else 0)
                    return true
                }
                TRANSACTION_sendReport -> {
                    data.enforceInterface(DESCRIPTOR)
                    val packet = data.createByteArray() ?: ByteArray(0)
                    val result = sendReport(packet)
                    reply?.writeNoException()
                    reply?.writeInt(if (result) 1 else 0)
                    return true
                }
                TRANSACTION_injectGamepadEvent -> {
                    data.enforceInterface(DESCRIPTOR)
                    val packet = data.createByteArray() ?: ByteArray(0)
                    val result = injectGamepadEvent(packet)
                    reply?.writeNoException()
                    reply?.writeInt(if (result) 1 else 0)
                    return true
                }
                TRANSACTION_isVirtualGamepadReady -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = isVirtualGamepadReady()
                    reply?.writeNoException()
                    reply?.writeInt(if (result) 1 else 0)
                    return true
                }
                TRANSACTION_destroyVirtualGamepad -> {
                    data.enforceInterface(DESCRIPTOR)
                    destroyVirtualGamepad()
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(private val remote: IBinder) : IGamepadInputService {
            override fun asBinder(): IBinder = remote

            override fun createVirtualGamepad(profileId: Int): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(profileId)
                    remote.transact(TRANSACTION_createVirtualGamepad, data, reply, 0)
                    reply.readException()
                    return reply.readInt() != 0
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun sendReport(packet32: ByteArray): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeByteArray(packet32)
                    remote.transact(TRANSACTION_sendReport, data, reply, 0)
                    reply.readException()
                    return reply.readInt() != 0
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun injectGamepadEvent(packet32: ByteArray): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeByteArray(packet32)
                    remote.transact(TRANSACTION_injectGamepadEvent, data, reply, 0)
                    reply.readException()
                    return reply.readInt() != 0
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun isVirtualGamepadReady(): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_isVirtualGamepadReady, data, reply, 0)
                    reply.readException()
                    return reply.readInt() != 0
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun destroyVirtualGamepad() {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_destroyVirtualGamepad, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
    }
}
