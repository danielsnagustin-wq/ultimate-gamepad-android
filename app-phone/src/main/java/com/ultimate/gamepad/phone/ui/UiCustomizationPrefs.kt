package com.ultimate.gamepad.phone.ui

import android.content.Context

/**
 * Personalización de la interfaz (ítem 3 del usuario) — color de
 * acento, tamaño de texto y fondo. Guardado en SharedPreferences,
 * mismo patrón que GameProfilePrefs, LayoutPrefs y ExtrasPrefs.
 *
 * Decisión de almacenamiento (documentada, no olvido): la tarea
 * pedía DataStore "mismo patrón que GameProfilePrefs/LayoutPrefs
 * si existen; si no, SharedPreferences". GameProfilePrefs y
 * LayoutPrefs SÍ existen y usan SharedPreferences; su cabecera
 * explica por qué: DataStore está declarado como dependencia
 * (app-phone/build.gradle.kts) pero NO se usa en ningún archivo
 * del proyecto, y para tres valores pequeños y síncronos solo
 * añadiría plumbing asíncrono (Flow + corrutinas) sin ningún
 * beneficio. Se sigue el patrón real del proyecto, no la
 * dependencia sin usar.
 *
 * Valores por defecto (coherentes con el ítem 2 del usuario,
 * texto blanco sobre negro, que se hace en paralelo):
 * - Acento: morado actual 0xFF7C4DFF (el mismo primary de
 *   MainActivity.kt).
 * - Texto: normal (factor 1.0).
 * - Fondo: negro puro 0xFF000000.
 * Todo el texto es blanco sobre negro por defecto; no hay
 * grises oscuros en esta pantalla ni en sus valores.
 *
 * Un valor guardado corrupto o desconocido no rompe nada: se
 * sanea al valor por defecto más cercano.
 */
object UiCustomizationPrefs {
    private const val PREFS = "ultimate_gamepad_ui"
    private const val KEY_ACCENT_ARGB = "accent_argb"
    private const val KEY_TEXT_SCALE = "text_scale"
    private const val KEY_BACKGROUND_ARGB = "background_argb"

    /** Acento por defecto: el morado actual de la app. */
    const val DEFAULT_ACCENT_ARGB: Int = 0xFF7C4DFF.toInt()

    /** Tamaño de texto por defecto: normal. */
    const val DEFAULT_TEXT_SCALE: Float = 1.0f

    /** Fondo por defecto: negro puro. */
    const val DEFAULT_BACKGROUND_ARGB: Int = 0xFF000000.toInt()

    /** Colores de acento entre los que puede elegir el usuario. */
    data class AccentOption(val id: String, val label: String, val argb: Int)

    val ACCENT_OPTIONS: List<AccentOption> = listOf(
        AccentOption("purple", "Morado", 0xFF7C4DFF.toInt()),
        AccentOption("blue", "Azul", 0xFF2196F3.toInt()),
        AccentOption("green", "Verde", 0xFF4CAF50.toInt()),
        AccentOption("red", "Rojo", 0xFFF44336.toInt()),
        AccentOption("orange", "Naranja", 0xFFFF9800.toInt()),
        AccentOption("pink", "Rosa", 0xFFE91E63.toInt()),
    )

    /** Tamaños de texto entre los que puede elegir el usuario. */
    data class TextSizeOption(val id: String, val label: String, val scale: Float)

    val TEXT_SIZE_OPTIONS: List<TextSizeOption> = listOf(
        TextSizeOption("small", "Pequeño", 0.85f),
        TextSizeOption("normal", "Normal", 1.0f),
        TextSizeOption("large", "Grande", 1.15f),
    )

    /** Fondos entre los que puede elegir el usuario. */
    data class BackgroundOption(val id: String, val label: String, val argb: Int)

    val BACKGROUND_OPTIONS: List<BackgroundOption> = listOf(
        BackgroundOption("black", "Negro puro", 0xFF000000.toInt()),
        BackgroundOption("dark_grey", "Gris muy oscuro", 0xFF121212.toInt()),
    )

    /** Los tres ajustes juntos, como los lee el tema (UiTheme). */
    data class UiCustomization(
        val accentArgb: Int = DEFAULT_ACCENT_ARGB,
        val textScale: Float = DEFAULT_TEXT_SCALE,
        val backgroundArgb: Int = DEFAULT_BACKGROUND_ARGB,
    ) {
        /** Sanea valores guardados viejos o corruptos. */
        fun sanitized(): UiCustomization {
            val accent = ACCENT_OPTIONS.firstOrNull { it.argb == accentArgb }
                ?.argb ?: DEFAULT_ACCENT_ARGB
            val scale = TEXT_SIZE_OPTIONS.minByOrNull { kotlin.math.abs(it.scale - textScale) }
                ?.scale ?: DEFAULT_TEXT_SCALE
            val background = BACKGROUND_OPTIONS.firstOrNull { it.argb == backgroundArgb }
                ?.argb ?: DEFAULT_BACKGROUND_ARGB
            return UiCustomization(accent, scale, background)
        }
    }

    fun loadAll(context: Context): UiCustomization {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return UiCustomization(
            accentArgb = prefs.getInt(KEY_ACCENT_ARGB, DEFAULT_ACCENT_ARGB),
            textScale = prefs.getFloat(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE),
            backgroundArgb = prefs.getInt(KEY_BACKGROUND_ARGB, DEFAULT_BACKGROUND_ARGB),
        ).sanitized()
    }

    fun saveAll(context: Context, customization: UiCustomization) {
        val clean = customization.sanitized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACCENT_ARGB, clean.accentArgb)
            .putFloat(KEY_TEXT_SCALE, clean.textScale)
            .putInt(KEY_BACKGROUND_ARGB, clean.backgroundArgb)
            .apply()
    }

    /** Vuelve a los valores por defecto (morado, normal, negro puro). */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACCENT_ARGB)
            .remove(KEY_TEXT_SCALE)
            .remove(KEY_BACKGROUND_ARGB)
            .apply()
    }
}
