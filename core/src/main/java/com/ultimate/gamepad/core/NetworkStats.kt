package com.ultimate.gamepad.core

import kotlin.math.abs

/**
 * Estadísticas de red para la pantalla RENDIMIENTO (Fase 3) —
 * lógica pura, sin Android, comprobada con tests en CI.
 *
 * - Jitter: variación del ping. Media de |ping[i] - ping[i-1]|
 *   sobre las últimas muestras; null con menos de 2 muestras
 *   (sin datos, no se inventa un 0).
 * - Hueco de secuencia: cuántos paquetes se perdieron entre dos
 *   secuencias consecutivas (uint16 con wrap en 65535). La TV lo
 *   usa en recordPacket(); el teléfono NO puede medir pérdida por
 *   UDP (el emisor no ve lo que no llega), así que la pérdida solo
 *   se muestra en la TV, donde se mide de verdad.
 */
object NetworkStats {
    /** Jitter (ms) a partir de las muestras de ping; null si hay menos de 2. */
    fun jitterMs(samples: List<Long>): Long? {
        if (samples.size < 2) return null
        var sum = 0L
        for (i in 1 until samples.size) sum += abs(samples[i] - samples[i - 1])
        return sum / (samples.size - 1)
    }

    /**
     * Paquetes perdidos entre [lastSequence] y [newSequence]
     * (secuencias uint16). 0 si es sesión nueva (lastSequence < 0),
     * si la secuencia se repite o va hacia atrás (duplicado o
     * reordenado: no es pérdida, se descarta en el receptor) o si
     * son consecutivas. Un salto de exactamente 32768 tampoco se
     * cuenta: a la mitad del espacio uint16 no se distingue un
     * avance de un retroceso, y un salto real de 32768 paquetes a
     * 120 Hz exigiría 273 s sin paquetes — con más de 2 s de
     * silencio el receptor abre sesión nueva, así que en la
     * práctica es un duplicado viejo, no pérdida (ronda 2 P2-2).
     */
    fun sequenceGap(lastSequence: Int, newSequence: Int): Int {
        if (lastSequence < 0) return 0
        val forward = (newSequence - lastSequence + 65536) % 65536
        if (forward == 0 || forward >= 32768) return 0
        return forward - 1
    }
}
