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
 * escala 1.6 el ajuste toca el suelo de 0.43 y el contenido puede
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
            assertTrue("${c.name}: fit $fit fuera de [0.43, 1]", fit in L.MIN_FIT..L.MAX_FIT)
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

    /**
     * La fila central en vertical con L4/R4 (Fase 4): la pantalla
     * dibuja seis piezas (L4, VIEW, Xbox, MENU, SHARE, R4) = 458 dp,
     * pero la geometría contaba solo cuatro (322 dp) y el ajuste
     * solo dependía del ancho de pantalla. En un teléfono de 390 dp
     * R4 quedaba fuera por la derecha. Ahora la fila cuenta las
     * seis piezas, el ajuste tiene en cuenta el ancho del contenido
     * y la fila va centrada (con la fila anclada a la izquierda el
     * desbordamiento es de un solo lado y ningún ajuste centrado
     * lo arregla: con el ajuste viejo R4 terminaba en 411,8 dp de
     * un área de 366 dp).
     */
    @Test fun `en vertical la fila central con L4 y R4 cabe en el ancho`() {
        // Ancho real de la fila que dibuja la pantalla en vertical.
        val rowW = L.L4_W + L.VIEW_W + L.XBOX_W_PORTRAIT + L.MENU_W + L.SHARE_W + L.R4_W +
            5 * L.PORTRAIT_CENTER_SPACING
        assertEquals(458f, rowW, 0.001f)
        val layout = LayoutGeometry.GeomLayout()
        // La geometría cuenta la fila entera: es la más ancha.
        val p390 = L.portrait(layout, 366f, 820f)
        assertEquals(458f, p390.contentW, 0.001f)
        // El ajuste hace caber el contenido: 366 / 458 = 0,799.
        assertEquals(366f / 458f, p390.fitScale, 0.001f)
        assertTrue("ancho escalado ${p390.scaledW} > disponible ${p390.availW}", p390.scaledW <= p390.availW + 0.5f)
        // R4, última pieza de la fila centrada: su borde derecho
        // visual queda justo en el borde del área (366 dp).
        val r4Right = 366f / 2f + rowW / 2f * p390.fitScale
        assertTrue("R4 se sale ($r4Right > 366)", r4Right <= 366f + 0.5f)
        // Y L4, primera pieza: su borde izquierdo visual queda en 0.
        val l4Left = 366f / 2f - rowW / 2f * p390.fitScale
        assertTrue("L4 se sale ($l4Left < 0)", l4Left >= -0.5f)
        // La matemática vieja NO lo cazaba: centro de 322 dp y
        // ajuste solo por pantalla (0,832) con la fila anclada a
        // la izquierda dejaban R4 en 411,8 dp, fuera del área.
        val oldFit = L.portraitFitScale(366f)
        val oldR4Right = 366f / 2f + (rowW - 366f / 2f) * oldFit
        assertTrue("la vieja NO dejaba R4 fuera ($oldR4Right <= 366): el test no cazaría el error", oldR4Right > 366f)
        // En el resto de teléfonos verticales también cabe (escala 1).
        for ((w, h) in listOf(360f to 640f, 412f to 915f)) {
            val p = L.portrait(layout, areaW(w), areaH(h))
            assertEquals(458f, p.contentW, 0.001f)
            assertTrue("$w x $h: ancho escalado ${p.scaledW} > disponible ${p.availW}", p.scaledW <= p.availW + 0.5f)
        }
        // Con escala 1.4 cabe en 390 y 412 dp, pero no en 360 dp:
        // ahí el ajuste tocaría el suelo de 0.55 (límite conocido).
        for ((w, h) in listOf(390f to 844f, 412f to 915f)) {
            val p = L.portrait(scaled(1.4f), areaW(w), areaH(h))
            assertTrue("$w x $h escala 1.4: ancho escalado ${p.scaledW} > disponible ${p.availW}", p.scaledW <= p.availW + 0.5f)
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
            assertTrue("$name: fit $fit fuera de [0.43, 1]", fit in L.MIN_FIT..L.MAX_FIT)
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
        // + 12 + 48 (L4/R4) = 192 dp, no 140 dp como antes de este arreglo.
        val cols = L.landscapeColumns(layout)
        assertEquals(192f, cols.center.layoutH, 0.001f)
        assertEquals(192f, cols.center.spanH, 0.001f)
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

    private fun allRects(rects: LayoutGeometry.ElementRects): List<Pair<String, LayoutGeometry.ElementRect>> =
        listOfNotNull(
            rects.shouldersLeft?.let { "LT/LB" to it },
            rects.shouldersRight?.let { "RB/RT" to it },
            rects.leftStick?.let { "stick izquierdo" to it },
            rects.dpad?.let { "cruceta" to it },
            rects.abxy?.let { "ABXY" to it },
            rects.rightStick?.let { "stick derecho" to it },
            rects.view?.let { "VIEW" to it },
            rects.xbox?.let { "Xbox" to it },
            rects.menu?.let { "MENU" to it },
            rects.share?.let { "SHARE" to it },
            rects.l4?.let { "L4" to it },
            rects.r4?.let { "R4" to it },
        )

    /**
     * La vista previa del panel de botones usa la misma matemática
     * que la pantalla real (LayoutGeometry.elementRects): lo que se
     * ve en el editor es lo que se juega. Antes el editor dibujaba
     * una miniatura de un panel ficticio de 800x300 dp con sus
     * propias posiciones base y la cruceta se cortaba por el borde
     * (foto del usuario 50220_0_fp5r.jpg); con la matemática real
     * todo cabe en el área, incluso con la cruceta arrastrada al
     * máximo que permitía el recorte viejo (+58 dp abajo).
     */
    @Test fun `la vista previa real no recorta nada en la ventana del usuario`() {
        val windows = listOf(
            "ventana del usuario 757x365" to (757f to 365f),
            "pantalla 791x365" to (791f to 365f),
            "pantalla 891x411" to (891f to 411f),
            "pantalla 1280x800" to (1280f to 800f),
        )
        val layouts = listOf(
            "por defecto" to LayoutGeometry.GeomLayout(),
            "offsets guardados" to userOffsets(),
            "escala 1.4" to scaled(1.4f),
        )
        for ((wName, wh) in windows) {
            val (screenW, screenH) = wh
            val aw = areaW(screenW)
            val ah = areaH(screenH)
            for ((lName, layout) in layouts) {
                for ((name, r) in allRects(L.elementRects(layout, aw, ah))) {
                    assertTrue("$wName $lName: $name se sale por la izquierda (${r.left})", r.left >= -0.5f)
                    assertTrue("$wName $lName: $name se sale por arriba (${r.top})", r.top >= -0.5f)
                    assertTrue("$wName $lName: $name se sale por la derecha (${r.right} > $aw)", r.right <= aw + 0.5f)
                    assertTrue("$wName $lName: $name se sale por abajo (${r.bottom} > $ah)", r.bottom <= ah + 0.5f)
                }
            }
        }
        // La cruceta arrastrada +58 dp abajo (el máximo que dejaba
        // el recorte viejo del panel): en la miniatura vieja se
        // cortaba por el borde inferior de la vista previa; con la
        // matemática real queda dentro del área.
        val dragged = LayoutGeometry.GeomLayout(dpad = el(offsetYdp = 58))
        val dpad = L.elementRects(dragged, areaW(757f), areaH(365f)).dpad!!
        assertTrue("cruceta arrastrada +58 se sale (${dpad.bottom} > ${areaH(365f)})", dpad.bottom <= areaH(365f) + 0.5f)
    }

    /**
     * Round-trip editor -> pantalla real: arrastrar un botón en la
     * vista previa cambia su desplazamiento guardado y la pantalla
     * real lo dibuja en el sitio que corresponde a ese
     * desplazamiento, sin factores de escala de por medio. En X el
     * centro se mueve exactamente dx * fit; en Y, lo mismo para un
     * elemento que no está en el borde del span (VIEW: el Xbox, más
     * alto, manda en la fila y el span no cambia). La miniatura
     * vieja duplicaba el movimiento (dividía por miniScale lo que
     * Compose ya había invertido): el botón volaba 1,7x.
     */
    @Test fun `arrastrar en el editor mueve el botón lo mismo en la pantalla real`() {
        // Ventana del usuario en horizontal (área 733x341) y
        // teléfono en vertical (área 366x820, ajuste 0,799).
        val cases = listOf(
            "ventana del usuario" to (733f to 341f),
            "vertical 390" to (366f to 820f),
        )
        for ((name, wh) in cases) {
            val (aw, ah) = wh
            val layout = LayoutGeometry.GeomLayout()
            val before = L.elementRects(layout, aw, ah)
            val fit = if (aw > ah) {
                val cols = L.landscapeColumns(layout)
                L.fitFor(aw, ah - L.SHOULDER_ROW_H, cols.maxSpanH, cols.contentW)
            } else {
                L.portrait(layout, aw, ah).fitScale
            }
            // Arrastre en X de la cruceta (+20 dp): el centro se
            // mueve exactamente 20 * fit (el ajuste no cambia: el
            // ancho lo manda el alto en esta ventana y el ancho en
            // vertical no depende de los desplazamientos).
            val dragX = layout.copy(dpad = el(offsetXdp = 20))
            val fitX = if (aw > ah) {
                val cols = L.landscapeColumns(dragX)
                L.fitFor(aw, ah - L.SHOULDER_ROW_H, cols.maxSpanH, cols.contentW)
            } else {
                L.portrait(dragX, aw, ah).fitScale
            }
            assertEquals("$name: el ajuste cambió al arrastrar en X", fit, fitX, 0.001f)
            val afterX = L.elementRects(dragX, aw, ah)
            assertEquals("$name: la cruceta no se movió dx * fit en X", 20f * fit, afterX.dpad!!.centerX - before.dpad!!.centerX, 0.01f)
            assertEquals("$name: la cruceta se movió en Y al arrastrar solo en X", 0f, afterX.dpad!!.centerY - before.dpad!!.centerY, 0.01f)
            // Arrastre en Y de VIEW (+10 dp): VIEW no manda en la
            // fila (el Xbox es más alto), así que el span no cambia
            // y el centro se mueve exactamente 10 * fit.
            val dragY = layout.copy(view = el(offsetYdp = 10))
            val afterY = L.elementRects(dragY, aw, ah)
            assertEquals("$name: VIEW no se movió dy * fit en Y", 10f * fit, afterY.view!!.centerY - before.view!!.centerY, 0.01f)
            assertEquals("$name: VIEW se movió en X al arrastrar solo en Y", 0f, afterY.view!!.centerX - before.view!!.centerX, 0.01f)
        }
    }

    /**
     * Anclas de la vista previa en la ventana del usuario: los
     * centros que dibuja la matemática real, comprobados con una
     * simulación independiente en Python con las mismas fórmulas.
     * Si alguien vuelve a poner una miniatura con posiciones
     * propias, estos números se rompen.
     */
    @Test fun `la vista previa pone cada botón donde lo pone la pantalla real`() {
        // Horizontal, ventana del usuario (área 733x341), diseño
        // por defecto: ajuste 0,7077.
        val rects = L.elementRects(LayoutGeometry.GeomLayout(), 733f, 341f)
        assertEquals(193.64f, rects.leftStick!!.centerX, 0.05f)
        assertEquals(123.02f, rects.leftStick!!.centerY, 0.05f)
        assertEquals(193.64f, rects.dpad!!.centerX, 0.05f)
        assertEquals(270.23f, rects.dpad!!.centerY, 0.05f)
        assertEquals(537.95f, rects.abxy!!.centerX, 0.05f)
        assertEquals(118.77f, rects.abxy!!.centerY, 0.05f)
        assertEquals(365.08f, rects.xbox!!.centerX, 0.05f)
        assertEquals(152.04f, rects.xbox!!.centerY, 0.05f)
        // Vertical, teléfono 390x844 (área 366x820): ajuste 0,799,
        // la fila central queda justo dentro (L4 en 0, R4 en 366).
        val layout = LayoutGeometry.GeomLayout()
        assertEquals(0.7991f, L.portrait(layout, 366f, 820f).fitScale, 0.001f)
        val portrait = L.elementRects(layout, 366f, 820f)
        assertEquals(0f, portrait.l4!!.left, 0.05f)
        assertEquals(366f, portrait.r4!!.right, 0.05f)
        assertEquals(23.17f, portrait.l4!!.centerX, 0.05f)
        assertEquals(342.83f, portrait.r4!!.centerX, 0.05f)
    }
}
