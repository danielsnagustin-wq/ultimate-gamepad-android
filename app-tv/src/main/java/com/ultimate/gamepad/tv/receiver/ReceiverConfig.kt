package com.ultimate.gamepad.tv.receiver

/**
 * Interruptor de Fase 2 — Shizuku / uinput.
 *
 * **Shizuku está DESCARTADO del todo en esta TV** (2026-11-07: no está
 * adaptado a TV y la TV no tiene depuración de red ni inalámbrica, solo
 * USB). La única vía para que el juego vea un mando es el **Bluetooth
 * HID directo del teléfono**. Por eso este flag es **false**: con él en
 * true, `UdpReceiverService.deliver()` intentaba la inyección Shizuku
 * en CADA paquete (120 Hz) y sobrescribía `lastError` con el mensaje de
 * Shizuku 120 veces por segundo, sin que nada pudiera funcionar.
 *
 * Con el flag en false, `deliver()` vuelve al instante con
 * `injectionMethod = "ninguno"` y la TV es solo receptora: recibe,
 * valida, muestra en pantalla y hace eco de ping (Fase 1). El flag
 * queda como interruptor por si algún día existe una vía real de
 * inyección en la TV; hasta entonces, NO se vuelve a poner en true.
 */
object ReceiverConfig {
    const val SHIZUKU_PHASE2_ENABLED: Boolean = false
}
