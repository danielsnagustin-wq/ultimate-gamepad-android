package com.ultimate.gamepad.core

/**
 * Checklist de la PRUEBA DE MANDO (Fase 3) — lógica pura, sin Android.
 *
 * Marca cada control cuando se usa de verdad: un botón cuando se
 * pulsa, un stick cuando se mueve más de la mitad del recorrido,
 * un gatillo cuando pasa de 0.5. La usan la pantalla de prueba de
 * la TV (contra el último estado recibido por red) y la del
 * teléfono (contra lo que se está enviando). No inventa nada: si
 * un control no se ha movido, sale sin marcar.
 *
 * Las etiquetas de los botones salen del perfil activo
 * (GamepadProfiles.labelFor): en un DualShock 4, A se llama
 * "Cross", no "A". L4/R4 (Fase 4) son paletas traseras y se llaman
 * igual en todos los perfiles: no pasan por labelFor porque no
 * son bits del bitmask de 16.
 */
class ControllerTestChecklist {
    data class Item(val id: String, val label: String, val tested: Boolean)

    private val tested = mutableSetOf<String>()

    companion object {
        const val STICK_LEFT_ID = "stick_left"
        const val STICK_RIGHT_ID = "stick_right"
        const val TRIGGER_LEFT_ID = "trigger_left"
        const val TRIGGER_RIGHT_ID = "trigger_right"

        /** Umbral para dar un stick/gatillo por probado: mitad del recorrido. */
        const val MOVE_THRESHOLD = 0.5f

        /** Los 16 botones, en el orden en que se muestran en la checklist. */
        val BUTTON_BITS: List<Int> = listOf(
            GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y,
            GamepadButtons.LB, GamepadButtons.RB,
            GamepadButtons.START, GamepadButtons.SELECT,
            GamepadButtons.L3, GamepadButtons.R3,
            GamepadButtons.DPAD_UP, GamepadButtons.DPAD_DOWN,
            GamepadButtons.DPAD_LEFT, GamepadButtons.DPAD_RIGHT,
            GamepadButtons.HOME, GamepadButtons.CAPTURE,
        )

        fun buttonId(bit: Int): String = "btn_$bit"

        /** Botones extra L4/R4 (Fase 4, GamepadExtraButtons, byte 29 del protocolo). */
        val EXTRA_BUTTON_BITS: List<Int> = listOf(GamepadExtraButtons.L4, GamepadExtraButtons.R4)

        fun extraButtonId(bit: Int): String = "extra_$bit"

        fun extraButtonLabel(bit: Int): String = when (bit) {
            GamepadExtraButtons.L4 -> "L4"
            GamepadExtraButtons.R4 -> "R4"
            else -> "Extra $bit"
        }

        /** Total de controles de la checklist: 16 botones + L4/R4 + 2 sticks + 2 gatillos. */
        const val TOTAL_COUNT: Int = 22
    }

    /** Alimenta la checklist con un estado (el último recibido o enviado). */
    fun update(state: GamepadState) {
        for (bit in BUTTON_BITS) {
            if (state.isPressed(bit)) tested += buttonId(bit)
        }
        for (bit in EXTRA_BUTTON_BITS) {
            if (state.isExtraPressed(bit)) tested += extraButtonId(bit)
        }
        if (kotlin.math.abs(state.leftX) > MOVE_THRESHOLD || kotlin.math.abs(state.leftY) > MOVE_THRESHOLD) tested += STICK_LEFT_ID
        if (kotlin.math.abs(state.rightX) > MOVE_THRESHOLD || kotlin.math.abs(state.rightY) > MOVE_THRESHOLD) tested += STICK_RIGHT_ID
        if (state.leftTrigger > MOVE_THRESHOLD) tested += TRIGGER_LEFT_ID
        if (state.rightTrigger > MOVE_THRESHOLD) tested += TRIGGER_RIGHT_ID
    }

    /** La checklist completa, con el estado actual de cada control. */
    fun items(profileId: Int = GamepadProfiles.XBOX_360_ID): List<Item> {
        val out = mutableListOf<Item>()
        for (bit in BUTTON_BITS) {
            out += Item(buttonId(bit), GamepadProfiles.labelFor(profileId, bit), buttonId(bit) in tested)
        }
        for (bit in EXTRA_BUTTON_BITS) {
            out += Item(extraButtonId(bit), extraButtonLabel(bit), extraButtonId(bit) in tested)
        }
        out += Item(STICK_LEFT_ID, "Stick izquierdo (mover)", STICK_LEFT_ID in tested)
        out += Item(STICK_RIGHT_ID, "Stick derecho (mover)", STICK_RIGHT_ID in tested)
        out += Item(TRIGGER_LEFT_ID, "Gatillo LT (pulsar)", TRIGGER_LEFT_ID in tested)
        out += Item(TRIGGER_RIGHT_ID, "Gatillo RT (pulsar)", TRIGGER_RIGHT_ID in tested)
        return out
    }

    val testedCount: Int get() = tested.size
    val totalCount: Int get() = TOTAL_COUNT
    val allDone: Boolean get() = tested.size >= TOTAL_COUNT

    fun reset() { tested.clear() }
}
