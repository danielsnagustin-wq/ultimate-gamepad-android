package com.ultimate.gamepad.tv.receiver

/**
 * Interruptor de Fase 2 — Shizuku / uinput.
 *
 * Orden de prioridades (no cambia con el multi-transporte):
 *   Fase 1 = enlace Wi-Fi en la misma red, visible en pantalla.
 *   Fase 2 = Shizuku / uinput para que OpenNOW lo vea como mando real.
 *
 * Este flag NO apaga el receptor: la Fase 1 funciona sin Shizuku
 * (recibe, valida, muestra en pantalla y hace eco de ping).
 * Cuando es true, el receptor INTENTA la inyección en este orden:
 *   A. Shizuku + /dev/uinput mando virtual Xbox 360 (principal)
 *   B. Shizuku + InputManager.injectInputEvent SOURCE_GAMEPAD (respaldo)
 *   C. Bluetooth HID (no pasa por aquí: es inyección directa)
 * Si Shizuku no está activo, la inyección queda en "ninguno" y la
 * pantalla lo dice: "Shizuku: no conectado / segunda opción
 * (Fase 2)". Nunca se finge que OpenNOW ya reconoce el mando.
 *
 * Para la Etapa 0, poner en true y probar en la Hyundai; si
 * UinputGamepad.create(profileId) sigue en false (sin .so nativo), la
 * inyección cae a B y la UI lo muestra.
 */
object ReceiverConfig {
    const val SHIZUKU_PHASE2_ENABLED: Boolean = true
}
