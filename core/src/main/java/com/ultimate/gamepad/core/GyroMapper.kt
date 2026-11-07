package com.ultimate.gamepad.core

/**
 * Giroscopio (Fase 4) — inclinación del teléfono al stick derecho.
 *
 * El teléfono lee su sensor de rotación y el ViewModel convierte
 * la inclinación en valores del stick derecho (rightX/rightY),
 * que viajan por el protocolo NORMAL de 32 bytes: no se inventan
 * ejes nuevos de protocolo (los bytes reservados son pocos y ya
 * los usan el profileId y L4/R4) y la TV no cambia nada.
 *
 * Mapeo puro (sin Android) para probarlo en CI: a sensibilidad
 * 1.0, una inclinación de ~45° (π/4 rad) recorre todo el stick.
 * Signos: inclinar la parte superior del teléfono hacia adelante
 * (alejándola de ti) = stick hacia arriba (Y negativa, igual que
 * el stick táctil, donde arriba es Y negativa); inclinar el
 * teléfono hacia la derecha = stick a la derecha (X positiva).
 * La convención de signo exacta depende de la orientación de la
 * pantalla (el ViewModel reorienta los ejes según la rotación) y
 * está PENDIENTE de verificar en el teléfono real: si se siente
 * al revés, se invierte aquí y solo aquí.
 *
 * Cuando el giroscopio está activado, el stick derecho táctil
 * queda ignorado (el giroscopio manda); al desactivarlo, el
 * stick táctil vuelve a mandar. La UI lo dice así.
 */
object GyroMapper {
    /** ~45° en radianes: a sensibilidad 1.0, esa inclinación es todo el recorrido del stick. */
    const val FULL_TILT_RAD: Float = 0.7853982f

    /**
     * [pitchRad]: inclinación adelante/atrás (rad, positivo =
     * parte superior hacia el usuario). [rollRad]: inclinación
     * izquierda/derecha (rad, positivo = lado derecho hacia el
     * usuario). Devuelve (rightX, rightY) en [-1, 1].
     */
    fun mapTilt(pitchRad: Float, rollRad: Float, sensitivity: Float): Pair<Float, Float> {
        val s = sensitivity.coerceIn(0.5f, 2f)
        val x = (rollRad / FULL_TILT_RAD * s).coerceIn(-1f, 1f)
        val y = (-pitchRad / FULL_TILT_RAD * s).coerceIn(-1f, 1f)
        return x to y
    }
}
