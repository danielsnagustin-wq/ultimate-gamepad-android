// IGamepadInputService.aidl — servicio que corre como shell (uid 2000)
// gracias a Shizuku. Solo este proceso tiene INJECT_EVENTS y acceso
// a /dev/uinput. La app normal NUNCA llama a esto directamente.
package com.ultimate.gamepad.tv;

// Estado serializado como el paquete UDP de 32 bytes (GamepadProtocol).
// Se pasa como byte[] para no inventar un Parcelable nuevo.
interface IGamepadInputService {
    /** Intenta crear el mando virtual Xbox 360 en /dev/uinput. Devuelve true solo si el kernel lo aceptó. */
    boolean createVirtualGamepad();
    /** Envía un paquete de 32 bytes ya validado. Devuelve false si no hay mando virtual. */
    boolean sendReport(in byte[] packet32);
    /** Inyecta un KeyEvent/MotionEvent con source gamepad (fallback, ver docs). */
    boolean injectGamepadEvent(in byte[] packet32);
    boolean isVirtualGamepadReady();
    void destroyVirtualGamepad();
}
