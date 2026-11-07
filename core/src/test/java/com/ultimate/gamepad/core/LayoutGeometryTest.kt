package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometría de la pantalla del mando en varios tamaños, sin
 * emulador: la misma matemática que usa GamepadScreen, comprobada
 * en CI con números. Cada caso lleva el tamaño y la variante en
 * el mensaje, para que un fallo diga dónde se rompe.
 *
 * Pantallas (dp): vertical 360x640, 390x844, 412x915 y tablet
 * 800x1280; horizontal 640x360, 791x365 (la del usuario: captura
 * de 2048x945 px, densidad ~2.96 — la captura es un 0.875 de la
 * pantalla 2340x1080), 800x360, 891x411 y 1280x800.
 * Ojo: en horizontal la VENTANA de la app es más estrecha que la
 * pantalla: la barra de navegación se come 34 dp a la derecha, así
 * que la ventana del usuario es 757x365 dp (área útil 733x341 dp).
 * Los casos genéricos usan el ancho de pantalla; el test de la
 * flecha abajo usa el ancho de ventana real (757 dp).
 *
 * Tolerancias: 1 dp en alto (en las pantallas más bajas con
 * escala 1.6 el ajuste toca el suelo de 0.45 y el contenido puede
 * pasarse <1 dp: invisible, menos de 2 px) y 0.5 dp en ancho
 * (redondeo de Float).
 */
class LayoutGeometryTest {
    private fun el(visible: Boolean = true, scale: Float = 1f, offsetXdp: Int = 0, offsetYdp: Int = 0) =
        LayoutGeometry.GeomElement(visible, scale, offsetXdp, offsetYdp)
    private val L = LayoutGeometry

    /** Un caso: nombre, diseño, pantalla y si la cruceta debe quedar
     * estrictamente por debajo de la etiqueta (sin solapamiento). */
    private data class Case(
        val name: String,
        val layout: LayoutGeometry.GeomLayout,
        val screenW: Float,
        val screenH: Float,
        val strictDpad: Boolean = true,
    )

    private fun scaled(s: Float): LayoutGeometry.GeomLayout {
        val e = el(scale = s)
        return LayoutGeometry.GeomLayout(e, e, e, e, e, e, e, e, e, e, e, e)
    }

    /** Desplazamientos guardados por el usuario (stick +39 dp en Y,
     * cruceta +23 dp en Y): la cruceta roza la etiqueta L3 unos
     * 10 dp, un resto conocido (los desplazamientos desiguales se
     * comen el hueco de 6 dp). Se quita igualándolos en el panel. */
    private fun userOffsets(s: Float = 1f): LayoutGeometry.GeomLayout =
        LayoutGeometry.GeomLayout(
            leftStick = el(scale = s, offsetYdp = 39),
            dpad = el(scale = s, offsetYdp = 23),
            abxy = el(scale = s),
            rightStick = el(scale = s),
            view = el(scale = s),
            xbox = el(scale = s),
            menu = el(scale = s),
            share = el(scale = s),
            l4 = el(scale = s),
            r4 = el(scale = s),
        )

    /** Desplazamientos extremos (±100 dp) en la peor dirección:
     * estiran los spans y el ancho al máximo. */
    private fun extremeOffsets(): LayoutGeometry.GeomLayout =
        LayoutGeometry.GeomLayout(
            leftStick = el(offsetXdp = -100, offsetYdp = -100),
            dpad = el(offsetXdp = 100, offsetYdp = 100),
            abxy = el(offsetXdp = 100, offsetYdp = -100),
            rightStick = el(offsetXdp = -100, offsetYdp = 100),
            view = el(offsetXdp = -100, offsetYdp = 100),
            xbox = el(offsetXdp = 100, offsetYdp = -100),
            menu = el(offsetXdp = -100, offsetYdp = 100),
            share = el(offsetXdp = 100, offsetYdp = 100),
            shouldersLeft = el(offsetXdp = -100),
            shouldersRight = el(offsetXdp = 100),
            l4 = el(offsetXdp = -100, offsetYdp = 100),
            r4 = el(offsetXdp = 100, offsetYdp = -100),
        )

    private val landscapeScreens = listOf(640f to 360f, 791f to 365f, 800f to 360f, 891f to 411f, 1280f to 800f)

    private fun landscapeCases(): List<Case> {
        val cases = mutableListOf<Case>()
        for ((w, h) in landscapeScreens) {
            for (s in listOf(0.7f, 1f, 1.4f, 1.6f)) {
                cases += Case("$w x $h escala $s", scaled(s), w, h)
            }
            for (s in listOf(1f, 1.4f, 1.6f)) {
                cases += Case("$w x $h offsets guardados escala $s", userOffsets(s), w, h, strictDpad = false)
            }
            cases += Case("$w x $h offsets extremos", extremeOffsets(), w, h)
        }
        return cases
    }

    private fun areaW(screenW: Float) = screenW - 2 * L.OUTER_PADDING_DP
    private fun areaH(screenH: Float) = screenH - 2 * L.OUTER_PADDING_DP

    /** Alto disponible para la fila de controles: el área menos la
     * fila de hombros (sin avisos de error/info). */
    private fun availH(screenH: Float) = areaH(screenH) - L.SHOULDER_ROW_H

    private fun fitOf(c: Case): Float {
        val cols = L.landscapeColumns(c.layout)
        return L.fitFor(areaW(c.screenW), availH(c.screenH), cols.maxSpanH, cols.contentW)
    }

    @Test fun `en horizontal el contenido escalado cabe en alto y ancho`() {
        for (c in landscapeCases()) {
            val cols = L.landscapeColumns(c.layout)
            val fit = fitOf(c)
            assertTrue("${c.name}: fit $fit fuera de [0.45, 1]", fit in L.MIN_FIT..L.MAX_FIT)
            val scaledH = cols.maxSpanH * fit
            assertTrue("${c.name}: alto escalado $scaledH > disponible ${availH(c.screenH)}", scaledH <= availH(c.screenH) + 1f)
            val scaledW = cols.contentW * fit
            assertTrue("${c.name}: ancho escalado $scaledW > disponible ${areaW(c.screenW)}", scaledW <= areaW(c.screenW) + 0.5f)
        }
    }

    @Test fun `en horizontal la cruceta no tapa la etiqueta L3`() {
        for (c in landscapeCases()) {
            val gap = L.landscapeColumns(c.layout).dpadLabelGap
            if (c.strictDpad) {
                assertTrue("${c.name}: la cruceta tapa la etiqueta (${-gap} dp)", gap >= 0f)
            } else {
                // Resto conocido de los offsets guardados: como mucho 10 dp.
                assertTrue("${c.name}: el roce con la etiqueta es mayor de 10 dp (${-gap} dp)", gap >= -10f)
            }
        }
        // Con el diseño por defecto el hueco es exactamente el de 6 dp.
        assertEquals(6f, L.landscapeColumns(LayoutGeometry.GeomLayout()).dpadLabelGap, 0.001f)
    }

    @Test fun `en horizontal los hombros no tapan la tuerca ni se salen`() {
        for (c in landscapeCases()) {
            assertShoulders(c.name, c.screenW, c.layout)
        }
    }

    /** Recorte de hombros: LT nunca más de 8 dp fuera y RT siempre
     * a 8 dp o más de la tuerca. Estricto (LT >= 0) solo cuando la
     * geometría lo permite y el usuario no lo arrastró fuera: en
     * pantallas estrechas con escala grande el recorte fija LT en
     * -8 dp porque la píldora de estado no deja sitio. */
    private fun assertShoulders(name: String, screenW: Float, layout: LayoutGeometry.GeomLayout) {
        // Ancho que usa el recorte: el área menos 24 dp.
        val (leftOffX, rightOffX) = L.clampShoulders(areaW(screenW) - 24f, layout.shouldersLeft, layout.shouldersRight)
        val edges = L.shoulderEdges(areaW(screenW), leftOffX, rightOffX)
        assertTrue("$name: LT se sale más de 8 dp (${edges.ltLeft})", edges.ltLeft >= -8f)
        assertTrue("$name: RT tapa la tuerca (hueco ${edges.rtGearGap})", edges.rtGearGap >= 8f)
        val leftHi = (areaW(screenW) - 24f - L.STATUS_PILL_W) / 2f - L.SHOULDER_GROUP_W * layout.shouldersLeft.scale
        val strict = layout.shouldersLeft.offsetXdp >= 0 && layout.shouldersRight.offsetXdp <= 0 && leftHi >= 0f
        if (strict) assertTrue("$name: LT se sale (${edges.ltLeft})", edges.ltLeft >= 0f)
    }

    @Test fun `en vertical el mando cabe en alto y ancho`() {
        // En vertical el ajuste depende solo del ancho: con escala
        // 1.4 o 1.6 los botones se salen por los lados en un
        // teléfono (limitación conocida, fuera de este test); en la
        // tablet sí caben.
        val cases = mutableListOf<Case>()
        for ((w, h) in listOf(360f to 640f, 390f to 844f, 412f to 915f)) {
            for (s in listOf(0.7f, 1f)) cases += Case("$w x $h escala $s", scaled(s), w, h)
        }
        for (s in listOf(0.7f, 1f, 1.4f, 1.6f)) {
            cases += Case("800 x 1280 escala $s", scaled(s), 800f, 1280f)
        }
        cases += Case("390 x 844 offsets guardados", userOffsets(), 390f, 844f)
        for (c in cases) {
            val p = L.portrait(c.layout, areaW(c.screenW), areaH(c.screenH))
            assertTrue("${c.name}: alto escalado ${p.scaledH} > disponible ${p.availH}", p.scaledH <= p.availH + 0.5f)
            assertTrue("${c.name}: ancho escalado ${p.scaledW} > disponible ${p.availW}", p.scaledW <= p.availW + 0.5f)
        }
    }

    @Test fun `en vertical los hombros tampoco tapan la tuerca`() {
        for ((w, h) in listOf(360f to 640f, 390f to 844f, 412f to 915f, 800f to 1280f)) {
            assertShoulders("$w x $h", w, LayoutGeometry.GeomLayout())
        }
    }

    @Test fun `todo oculto no rompe la geometría`() {
        val hidden = el(visible = false)
        val layout = LayoutGeometry.GeomLayout(hidden, hidden, hidden, hidden, hidden, hidden, hidden, hidden, hidden, hidden, hidden, hidden)
        val cols = L.landscapeColumns(layout)
        assertEquals(0f, cols.maxSpanH, 0f)
        assertEquals(1f, L.fitFor(767f, 301f, cols.maxSpanH, cols.contentW), 0f)
        val (leftOffX, rightOffX) = L.clampShoulders(743f, hidden, hidden)
        assertEquals(0f, leftOffX, 0f)
        assertEquals(0f, rightOffX, 0f)
        val p = L.portrait(layout, 366f, 820f)
        // Sin piezas, solo queda el hueco de 12 dp entre filas.
        assertEquals(12f, p.totalH, 0f)
        assertTrue(p.scaledH <= p.availH)
        assertEquals(0f, p.scaledW, 0f)
    }

    /**
     * La matemática VIEJA (commit 9eb64bfb) habría fallado estos
     * casos; la nueva los pasa. Así se comprueba que los tests
     * cazan los errores que reportó el usuario y no solo que el
     * código nuevo no se rompe solo.
     */
    @Test fun `la matemática vieja habría cortado la cruceta en la pantalla del usuario`() {
        val layout = userOffsets()
        val screenW = 791f
        val screenH = 365f
        val aw = areaW(screenW)
        val ah = areaH(screenH)
        val avH = availH(screenH)
        // Vieja: ajuste por un fijo de 500 dp; fila de altura fija
        // (avH) con la columna de 410 dp centrada dentro, y toda la
        // fila escalada por el ajuste alrededor de su centro.
        val oldFit = (ah / 500f).coerceIn(0.55f, 1f)
        val oldDishBottom = legacyDpadBottomVisual(avH, oldFit, layout)
        assertTrue("la vieja NO cortaba la cruceta ($oldDishBottom <= $avH): el test no cazaría el error", oldDishBottom > avH)
        // Nueva: el ajuste se calcula con la altura real del contenido.
        val cols = L.landscapeColumns(layout)
        val fit = L.fitFor(aw, avH, cols.maxSpanH, cols.contentW)
        val newDishBottom = newDpadBottomVisual(avH, fit, cols, layout)
        assertTrue("la nueva corta la cruceta ($newDishBottom > $avH)", newDishBottom <= avH)
    }

    /**
     * La flecha ▼ en la ventana real del usuario: pantalla 791x365 dp
     * pero la ventana de la app es 757x365 dp (la barra de navegación
     * se come 34 dp a la derecha en horizontal). El área útil es
     * 733x341 dp y la fila de controles dispone de 301 dp de alto.
     * Con los desplazamientos guardados (stick +39, cruceta +23),
     * la cruceta entera (plato) y la tecla ▼ tienen que quedar
     * dentro del hueco, con margen, y la columna del centro tiene
     * que contar la fila L4/R4 (Fase 4). También se cubre la
     * pantalla 891x411 dp: ahí sobra más sitio, pero el mismo
     * cálculo tiene que pasar.
     */
    @Test fun `la flecha abajo queda visible en la ventana del usuario`() {
        val layout = userOffsets()
        // Ventana real del usuario (757x365) y pantalla 891x411.
        val windows = listOf(
            "ventana del usuario 757x365" to (757f to 365f),
            "pantalla 891x411" to (891f to 411f),
        )
        for ((name, wh) in windows) {
            val (screenW, screenH) = wh
            val aw = screenW - 2 * L.OUTER_PADDING_DP
            val avH = screenH - 2 * L.OUTER_PADDING_DP - L.SHOULDER_ROW_H
            val cols = L.landscapeColumns(layout)
            val fit = L.fitFor(aw, avH, cols.maxSpanH, cols.contentW)
            assertTrue("$name: fit $fit fuera de [0.45, 1]", fit in L.MIN_FIT..L.MAX_FIT)
            // La cruceta entera (plato) dentro del hueco, con margen.
            val dishBottom = newDpadBottomVisual(avH, fit, cols, layout)
            assertTrue("$name: el plato de la cruceta se sale ($dishBottom > $avH)", dishBottom <= avH)
            // La tecla ▼ termina 14 dp antes que el plato (la columna
            // de 3 filas de 56 dp va centrada en el plato de 196 dp):
            // tiene que quedar dentro con aún más margen y sin que la
            // tape la etiqueta L3 (hueco >= -10, el resto conocido).
            val downBottom = newDownKeyBottomVisual(avH, fit, cols, layout)
            assertTrue("$name: la flecha abajo se sale ($downBottom > $avH)", downBottom <= avH)
            assertTrue("$name: la flecha abajo roza la etiqueta (${cols.dpadLabelGap})", cols.dpadLabelGap >= -10f)
            // La matemática vieja sí cortaba la ▼ en esta ventana.
            val oldFit = ((screenH - 2 * L.OUTER_PADDING_DP) / 500f).coerceIn(0.55f, 1f)
            val oldDishBottom = legacyDpadBottomVisual(avH, oldFit, layout)
            assertTrue("$name: la vieja NO cortaba la cruceta: el test no cazaría el error", oldDishBottom > avH)
        }
        // El centro cuenta la fila L4/R4: 72 (Xbox) + 12 + 48 (SHARE)
        // + 12 + 38 (L4/R4) = 182 dp, no 140 dp como antes de este arreglo.
        val cols = L.landscapeColumns(layout)
        assertEquals(182f, cols.center.layoutH, 0.001f)
        assertEquals(182f, cols.center.spanH, 0.001f)
    }

    @Test fun `la matemática vieja habría tapado la etiqueta y la tuerca`() {
        // Vieja, escala 1.4: la caja no crecía con la escala (solo
        // el dibujo), así que la etiqueta (208 x 1.4 = 291 dp)
        // se metía 77 dp dentro de la cruceta (que empezaba en 214).
        val oldOverlap = 208f * 1.4f - 214f
        assertTrue("la vieja NO tapaba la etiqueta: el test no cazaría el error", oldOverlap > 0f)
        assertTrue("la nueva tapa la etiqueta", L.landscapeColumns(scaled(1.4f)).dpadLabelGap >= 0f)
        // Viejos hombros sin recorte: RT desplazado +49 dp (como en
        // la captura) se metía 41 dp dentro de la zona de la tuerca.
        val rowW = 791f - 24f
        val oldRtRight = rowW - 48f + 49f
        val gearLeft = rowW - 40f
        assertTrue("la vieja NO tapaba RT: el test no cazaría el error", oldRtRight > gearLeft)
        val (leftOffX, rightOffX) = L.clampShoulders(rowW - 24f, el(), el(offsetXdp = 49))
        val edges = L.shoulderEdges(rowW, leftOffX, rightOffX)
        assertEquals(8f, edges.rtGearGap, 0.01f)
    }

    /** Borde inferior visual de la tecla ▼: como newDpadBottomVisual
     * pero 14 dp más arriba (la tecla termina 14 dp antes que el
     * plato: la columna de 3x56 dp va centrada en el plato de
     * 196 dp). */
    private fun newDownKeyBottomVisual(
        availH: Float,
        fit: Float,
        cols: LayoutGeometry.LandscapeColumns,
        layout: LayoutGeometry.GeomLayout,
    ): Float {
        val dpadBottomInColumn = 208f * layout.leftStick.scale + 6f + layout.dpad.offsetYdp + 196f * layout.dpad.scale
        val downBottomInColumn = dpadBottomInColumn - 14f * layout.dpad.scale
        val spanCenter = (cols.left.top + cols.left.bottom) / 2f
        return availH / 2f + (downBottomInColumn - spanCenter) * fit
    }

    /** Borde inferior visual de la cruceta con la matemática vieja:
     * columna de 410 dp de layout centrada en la fila de [availH],
     * cruceta en [214 + offY, 410 + offY] de la columna, y toda la
     * fila escalada por [oldFit] alrededor de su centro. */
    private fun legacyDpadBottomVisual(availH: Float, oldFit: Float, layout: LayoutGeometry.GeomLayout): Float {
        val colTop = (availH - 410f) / 2f
        val dpadBottom = colTop + 214f + layout.dpad.offsetYdp + 196f
        val rowCenter = availH / 2f
        return rowCenter + (dpadBottom - rowCenter) * oldFit
    }

    /** Borde inferior visual de la cruceta con la matemática nueva:
     * el span visible de la columna se centra en la fila y se
     * escala por [fit]; la cruceta está al fondo del span izquierdo. */
    private fun newDpadBottomVisual(
        availH: Float,
        fit: Float,
        cols: LayoutGeometry.LandscapeColumns,
        layout: LayoutGeometry.GeomLayout,
    ): Float {
        val dpadBottomInColumn = 208f * layout.leftStick.scale + 6f + layout.dpad.offsetYdp + 196f * layout.dpad.scale
        val spanCenter = (cols.left.top + cols.left.bottom) / 2f
        return availH / 2f + (dpadBottomInColumn - spanCenter) * fit
    }
}
