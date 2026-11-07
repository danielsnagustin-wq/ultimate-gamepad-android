package com.ultimate.gamepad.core

/**
 * Perfil por juego (Fase 3) — datos puros, sin Android.
 *
 * Un perfil por juego, elegido A MANO por el usuario (sin
 * detección de app en primer plano: en GeForce NOW el juego corre
 * en la nube y el teléfono no puede saber a qué se está jugando).
 * Cada perfil guarda:
 * - [profileId]: tipo de mando (emulación + visual, GamepadProfiles).
 * - [deadZone]: zona muerta de los sticks (0..0.15).
 * - [curve]: curva de respuesta de los sticks (0.5..1.5; 1.0 =
 *   lineal, < 1 = más vivo en el centro, > 1 = más suave).
 * - [triggerScale]: sensibilidad de los gatillos (0.5..1.5; 1.0 =
 *   tal cual; multiplica el valor analógico, recortado a 0..1).
 * - [vibrationEnabled]: vibración al pulsar (GUARDADO, NO aplicado
 *   todavía: la vibración es Fase 4; la pantalla lo dice así y no
 *   finge que vibre).
 * - [remap]: remapeo de botones (bit origen -> bit destino). Se
 *   aplica al enviar: pulsar A con remap A->B envía B. Las
 *   etiquetas del mando no cambian; el remapeo afecta a lo que se
 *   envía, no a lo que se dibuja.
 * - [layoutJson]: diseño de botones guardado (JSON de LayoutConfig,
 *   opaco aquí; null = usar el diseño actual).
 *
 * El guardado en disco vive en GameProfilePrefs (teléfono,
 * SharedPreferences JSON, como LayoutPrefs): DataStore está
 * declarado como dependencia pero no se usa en ningún archivo del
 * proyecto, y para una lista pequeña de JSON no aporta nada frente
 * al patrón SharedPreferences que ya usan el layout y el perfil
 * activo — añadir DataStore aquí solo sumaría plumbing asíncrono
 * sin beneficio. Decisión documentada, no olvido.
 */
data class GameProfile(
    val name: String,
    val profileId: Int = GamepadProfiles.XBOX_360_ID,
    val deadZone: Float = 0.05f,
    val curve: Float = 0.85f,
    val triggerScale: Float = 1f,
    val vibrationEnabled: Boolean = false,
    val remap: Map<Int, Int> = emptyMap(),
    val layoutJson: String? = null,
) {
    /** Sanea valores corruptos o fuera de rango; nunca lanza. */
    fun sanitized(): GameProfile {
        val cleanRemap = remap.entries
            .filter { (from, to) -> from in ALL_BUTTON_BITS && to in ALL_BUTTON_BITS && from != to }
            .associate { (from, to) -> from to to }
        return copy(
            name = name.ifBlank { "Sin nombre" }.take(40),
            profileId = GamepadProfiles.fromId(profileId).id,
            deadZone = deadZone.coerceIn(0f, 0.15f),
            curve = curve.coerceIn(0.5f, 1.5f),
            triggerScale = triggerScale.coerceIn(0.5f, 1.5f),
            remap = cleanRemap,
            layoutJson = layoutJson?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Aplica el remapeo a un bitmask de botones: cada bit pulsado
     * se traduce por el mapa (o queda igual si no está mapeado).
     * Se reconstruye el mask bit a bit, así un intercambio A<->B
     * funciona en los dos sentidos a la vez.
     */
    fun applyRemap(buttons: Int): Int {
        if (remap.isEmpty()) return buttons
        var out = 0
        for (bit in ALL_BUTTON_BITS) {
            if ((buttons and bit) != 0) out = out or (remap[bit] ?: bit)
        }
        return out
    }

    companion object {
        val ALL_BUTTON_BITS: List<Int> = listOf(
            GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y,
            GamepadButtons.LB, GamepadButtons.RB,
            GamepadButtons.START, GamepadButtons.SELECT,
            GamepadButtons.L3, GamepadButtons.R3,
            GamepadButtons.DPAD_UP, GamepadButtons.DPAD_DOWN,
            GamepadButtons.DPAD_LEFT, GamepadButtons.DPAD_RIGHT,
            GamepadButtons.HOME, GamepadButtons.CAPTURE,
        )
    }
}
