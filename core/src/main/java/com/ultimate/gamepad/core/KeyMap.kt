package com.ultimate.gamepad.core

/**
 * Teclas del teclado virtual (Fase 4) — caracteres a códigos
 * evdev (linux/input-event-codes.h), los mismos que el dispositivo
 * uinput de teclado de la TV escribe con EV_KEY.
 *
 * Solo se cubre lo necesario para escribir en un chat (letras,
 * dígitos, espacio, puntuación común, Enter, retroceso, flechas):
 * no es un teclado completo de sistema. Un carácter no cubierto
 * (p. ej. 'ñ', '¿', emojis) devuelve null y el teléfono lo salta
 * sin fingir que lo escribió.
 *
 * KEY_LEFTSHIFT = 42: las mayúsculas y los símbolos de la fila
 * superior se envían como SHIFT + tecla (el teléfono pulsa
 * SHIFT, pulsa la tecla, suelta la tecla y suelta SHIFT).
 */
object KeyMap {
    const val KEY_ESC: Int = 1
    const val KEY_1: Int = 2
    const val KEY_2: Int = 3
    const val KEY_3: Int = 4
    const val KEY_4: Int = 5
    const val KEY_5: Int = 6
    const val KEY_6: Int = 7
    const val KEY_7: Int = 8
    const val KEY_8: Int = 9
    const val KEY_9: Int = 10
    const val KEY_0: Int = 11
    const val KEY_MINUS: Int = 12
    const val KEY_EQUAL: Int = 13
    const val KEY_BACKSPACE: Int = 14
    const val KEY_TAB: Int = 15
    const val KEY_Q: Int = 16
    const val KEY_W: Int = 17
    const val KEY_E: Int = 18
    const val KEY_R: Int = 19
    const val KEY_T: Int = 20
    const val KEY_Y: Int = 21
    const val KEY_U: Int = 22
    const val KEY_I: Int = 23
    const val KEY_O: Int = 24
    const val KEY_P: Int = 25
    const val KEY_LEFTBRACE: Int = 26
    const val KEY_RIGHTBRACE: Int = 27
    const val KEY_ENTER: Int = 28
    const val KEY_A: Int = 30
    const val KEY_S: Int = 31
    const val KEY_D: Int = 32
    const val KEY_F: Int = 33
    const val KEY_G: Int = 34
    const val KEY_H: Int = 35
    const val KEY_J: Int = 36
    const val KEY_K: Int = 37
    const val KEY_L: Int = 38
    const val KEY_SEMICOLON: Int = 39
    const val KEY_APOSTROPHE: Int = 40
    const val KEY_GRAVE: Int = 41
    const val KEY_LEFTSHIFT: Int = 42
    const val KEY_BACKSLASH: Int = 43
    const val KEY_Z: Int = 44
    const val KEY_X: Int = 45
    const val KEY_C: Int = 46
    const val KEY_V: Int = 47
    const val KEY_B: Int = 48
    const val KEY_N: Int = 49
    const val KEY_M: Int = 50
    const val KEY_COMMA: Int = 51
    const val KEY_DOT: Int = 52
    const val KEY_SLASH: Int = 53
    const val KEY_SPACE: Int = 57
    const val KEY_UP: Int = 103
    const val KEY_DOWN: Int = 108
    const val KEY_LEFT: Int = 105
    const val KEY_RIGHT: Int = 106

    private val letters: Map<Char, Int> = mapOf(
        'a' to KEY_A, 'b' to KEY_B, 'c' to KEY_C, 'd' to KEY_D,
        'e' to KEY_E, 'f' to KEY_F, 'g' to KEY_G, 'h' to KEY_H,
        'i' to KEY_I, 'j' to KEY_J, 'k' to KEY_K, 'l' to KEY_L,
        'm' to KEY_M, 'n' to KEY_N, 'o' to KEY_O, 'p' to KEY_P,
        'q' to KEY_Q, 'r' to KEY_R, 's' to KEY_S, 't' to KEY_T,
        'u' to KEY_U, 'v' to KEY_V, 'w' to KEY_W, 'x' to KEY_X,
        'y' to KEY_Y, 'z' to KEY_Z,
    )

    private val digits: Map<Char, Int> = mapOf(
        '1' to KEY_1, '2' to KEY_2, '3' to KEY_3, '4' to KEY_4,
        '5' to KEY_5, '6' to KEY_6, '7' to KEY_7, '8' to KEY_8,
        '9' to KEY_9, '0' to KEY_0,
    )

    /** Símbolos sin SHIFT. */
    private val plain: Map<Char, Int> = mapOf(
        ' ' to KEY_SPACE, '-' to KEY_MINUS, '=' to KEY_EQUAL,
        ',' to KEY_COMMA, '.' to KEY_DOT, '/' to KEY_SLASH,
        ';' to KEY_SEMICOLON, '\'' to KEY_APOSTROPHE, '`' to KEY_GRAVE,
        '\\' to KEY_BACKSLASH, '[' to KEY_LEFTBRACE, ']' to KEY_RIGHTBRACE,
        '\n' to KEY_ENTER,
    )

    /** Símbolos con SHIFT (tecla base + SHIFT). */
    private val shifted: Map<Char, Int> = mapOf(
        '!' to KEY_1, '@' to KEY_2, '#' to KEY_3, '$' to KEY_4,
        '%' to KEY_5, '^' to KEY_6, '&' to KEY_7, '*' to KEY_8,
        '(' to KEY_9, ')' to KEY_0, '_' to KEY_MINUS, '+' to KEY_EQUAL,
        '?' to KEY_SLASH, ':' to KEY_SEMICOLON, '"' to KEY_APOSTROPHE,
        '~' to KEY_GRAVE, '|' to KEY_BACKSLASH, '{' to KEY_LEFTBRACE,
        '}' to KEY_RIGHTBRACE, '<' to KEY_COMMA, '>' to KEY_DOT,
    )

    /**
     * Devuelve (código evdev, necesita SHIFT) o null si el carácter
     * no se puede escribir con este teclado. Las mayúsculas usan
     * SHIFT + su minúscula.
     */
    fun forChar(c: Char): Pair<Int, Boolean>? {
        letters[c.lowercaseChar()]?.let { return it to c.isUpperCase() }
        digits[c]?.let { return it to false }
        plain[c]?.let { return it to false }
        shifted[c]?.let { return it to true }
        return null
    }

    /** Teclas especiales por nombre ("enter", "backspace", "space", "tab", "escape", "up", "down", "left", "right"). */
    fun forSpecial(name: String): Int? = when (name.lowercase()) {
        "enter" -> KEY_ENTER
        "backspace" -> KEY_BACKSPACE
        "space" -> KEY_SPACE
        "tab" -> KEY_TAB
        "escape" -> KEY_ESC
        "up" -> KEY_UP
        "down" -> KEY_DOWN
        "left" -> KEY_LEFT
        "right" -> KEY_RIGHT
        else -> null
    }

    /** Todos los códigos que el teclado virtual puede enviar (el dispositivo uinput de la TV solo acepta estos). */
    val ALL_CODES: Set<Int> = (letters.values + digits.values + plain.values + shifted.values +
        listOf(KEY_ENTER, KEY_BACKSPACE, KEY_TAB, KEY_ESC, KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_LEFTSHIFT)).toSet()
}
