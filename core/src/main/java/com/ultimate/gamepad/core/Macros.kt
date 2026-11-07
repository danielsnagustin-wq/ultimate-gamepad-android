package com.ultimate.gamepad.core

/**
 * Macros (Fase 4) — grabar una secuencia de botones con tiempos
 * y reproducirla desde el TELÉFONO.
 *
 * Una macro es una lista de eventos: en cada evento cambia el
 * conjunto de botones pulsados (bitmask de 16 bits + botones
 * extra L4/R4) y el tiempo en ms desde el inicio de la
 * grabación. Solo se graban los CAMBIOS de botones (no los
 * sticks ni los gatillos): una macro es una secuencia de
 * pulsaciones, no una grabación de partida. Al reproducir, el
 * teléfono aplica cada evento en su momento, como si el usuario
 * pulsara esos botones.
 *
 * Límites honestos: máximo 30 s y 200 eventos por macro (una
 * secuencia más larga no es una macro, es una grabación). Si al
 * parar la grabación queda algún botón pulsado, se añade un
 * evento final con todo soltado para que la reproducción no deje
 * botones atascados.
 *
 * La lógica pura (sin Android) vive aquí para probarla en CI; el
 * ViewModel solo alimenta el grabador desde publish() y reproduce
 * con un temporizador. El guardado en disco vive en ExtrasPrefs
 * (teléfono, SharedPreferences JSON, como los perfiles por juego).
 */
data class MacroEvent(val delayMs: Long, val buttons: Int, val extraButtons: Int)

data class GamepadMacro(val name: String, val events: List<MacroEvent>) {
    /** Duración total (tiempo del último evento). */
    val durationMs: Long get() = events.lastOrNull()?.delayMs ?: 0L

    fun sanitized(): GamepadMacro {
        val cleanName = name.ifBlank { "Sin nombre" }.take(40)
        val cleanEvents = events
            .filter { it.delayMs in 0..MAX_DURATION_MS }
            .take(MAX_EVENTS)
        return copy(name = cleanName, events = cleanEvents)
    }

    companion object {
        const val MAX_DURATION_MS: Long = 30_000L
        const val MAX_EVENTS: Int = 200
    }
}

class MacroRecorder {
    var recording: Boolean = false
        private set

    private val events = mutableListOf<MacroEvent>()
    private var startMs: Long = 0L
    private var lastButtons: Int = 0
    private var lastExtra: Int = 0

    fun start(nowMs: Long) {
        events.clear()
        startMs = nowMs
        lastButtons = 0
        lastExtra = 0
        recording = true
    }

    /** Alimentar con el estado actual de botones en cada publicación. Solo anota los cambios. */
    fun onState(nowMs: Long, buttons: Int, extraButtons: Int) {
        if (!recording) return
        val elapsed = nowMs - startMs
        if (elapsed > GamepadMacro.MAX_DURATION_MS || events.size >= GamepadMacro.MAX_EVENTS) return
        if (buttons != lastButtons || extraButtons != lastExtra) {
            events += MacroEvent(elapsed, buttons, extraButtons)
            lastButtons = buttons
            lastExtra = extraButtons
        }
    }

    /**
     * Para la grabación y devuelve la macro, o null si no se grabó
     * ningún cambio de botón. Si quedaba algún botón pulsado, se
     * añade el evento final con todo soltado.
     */
    fun stop(nowMs: Long, name: String): GamepadMacro? {
        if (!recording) return null
        recording = false
        if (lastButtons != 0 || lastExtra != 0) {
            events += MacroEvent(nowMs - startMs, 0, 0)
        }
        if (events.isEmpty()) return null
        return GamepadMacro(name, events.toList()).sanitized()
    }
}
