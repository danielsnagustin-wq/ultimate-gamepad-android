package com.ultimate.gamepad.core

/**
 * Turbo (Fase 4) — generador de pulsos en el TELÉFONO.
 *
 * Mientras se mantiene pulsado un botón con turbo, el teléfono
 * alterna ese botón entre pulsado y soltado a [TurboConfig.rateHz]
 * (10 Hz por defecto: 50 ms pulsado + 50 ms soltado). Solo
 * teléfono + protocolo: la TV no cambia nada, recibe los pulsos
 * como si el usuario pulsara y soltara el botón a esa frecuencia.
 *
 * La lógica pura vive aquí (sin Android) para poder probarla en
 * CI; el ViewModel solo alterna la fase con un temporizador y
 * llama a [Turbo.apply] en cada publicación de estado.
 */
data class TurboConfig(
    /** Botones con turbo (bits de GamepadButtons). */
    val buttons: Set<Int> = emptySet(),
    /** Pulsos por segundo (1..30; 10 por defecto). */
    val rateHz: Int = 10,
) {
    fun sanitized(): TurboConfig = copy(
        buttons = buttons.filter { it in TURBOABLE_BUTTONS }.toSet(),
        rateHz = rateHz.coerceIn(1, 30),
    )

    companion object {
        /** Botones a los que se les puede poner turbo: los de acción y hombros (A/B/X/Y/LB/RB). Los gatillos son analógicos, la cruceta y los de sistema no tienen sentido con turbo. */
        val TURBOABLE_BUTTONS: Set<Int> = setOf(
            GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y,
            GamepadButtons.LB, GamepadButtons.RB,
        )
    }
}

object Turbo {
    /** Medio periodo en ms: a 10 Hz son 50 ms pulsado + 50 ms soltado. Mínimo 16 ms (el envío es a 120 Hz, más rápido no se vería). */
    fun halfPeriodMs(rateHz: Int): Long = (500L / rateHz.coerceIn(1, 30)).coerceAtLeast(16L)

    /**
     * Aplica el turbo a un bitmask de botones mantenidos: con la
     * fase en OFF, los botones con turbo se quitan del mask (como
     * si se hubieran soltado); con la fase en ON, el mask queda
     * tal cual. Los botones sin turbo nunca se tocan.
     */
    fun apply(heldButtons: Int, turboButtons: Set<Int>, phaseOn: Boolean): Int {
        if (phaseOn || turboButtons.isEmpty()) return heldButtons
        var out = heldButtons
        for (b in turboButtons) out = out and b.inv()
        return out
    }
}
