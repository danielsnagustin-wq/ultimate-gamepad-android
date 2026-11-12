package com.ultimate.gamepad.core

import kotlin.math.abs

/**
 * Geometría pura de la pantalla del mando (GamepadScreen.kt), sin
 * Compose ni Android: solo Floats. La pantalla llama a estas
 * funciones y los tests unitarios las comprueban en CI para varios
 * tamaños de pantalla, sin emulador.
 *
 * Los errores que esta geometría impide (todos reales, medidos en
 * la captura del usuario, pantalla 791x365 dp):
 * - La flecha ▼ de la cruceta se cortaba: el contenido de la
 *   columna (410 dp) no cabía en su fila (301 dp) y el ajuste se
 *   calculaba con un fijo de 500 dp en vez de medir el contenido.
 *   Ahora el ajuste se calcula con la altura real del contenido.
 * - La fila L4/R4 (Fase 4) no estaba en la geometría del centro:
 *   la columna se calculaba con dos filas (140 dp) cuando la
 *   pantalla dibuja tres (182 dp con el Xbox a 72 dp). Ahora la
 *   tercera fila está en landscapeColumns, con su escala y su
 *   desplazamiento, como las demás.
 * - La escala era solo visual: un stick a 1.4x dibujaba 291 dp en
 *   un hueco de 208 dp y tapaba la etiqueta L3 y la cruceta. Ahora
 *   la caja crece con la escala y nada se tapa.
 * - La tuerca tapaba RT y LT se salía por el borde: los hombros
 *   se movían sin límite. Ahora el desplazamiento se recorta al
 *   dibujar (lo guardado no cambia).
 * - La fila central en vertical no contaba L4/R4: se calculaba
 *   con 322 dp (VIEW/Xbox/MENU/SHARE) cuando la pantalla dibuja
 *   458 dp con las paletas, y el ajuste solo dependía del ancho
 *   de pantalla. En un teléfono de 390 dp R4 quedaba fuera por la
 *   derecha. Ahora la fila cuenta las seis piezas, el ajuste
 *   tiene en cuenta el ancho del contenido y la fila va centrada:
 *   con la fila anclada a la izquierda el desbordamiento es de un
 *   solo lado y ningún ajuste centrado lo arregla.
 *
 * Límites conocidos (no los cubren los tests, a propósito):
 * - En vertical el ajuste tiene un suelo de 0.55: con escala 1.6
 *   en un teléfono vertical el contenido ya no cabe (y con 1.4
 *   tampoco en pantallas de 360 dp). En horizontal el suelo es 0.45.
 * - Con los desplazamientos guardados por el usuario (stick +39,
 *   cruceta +23) la cruceta roza la etiqueta L3 unos 10 dp: los
 *   desplazamientos desiguales se comen el hueco de 6 dp. Se quita
 *   igualando los desplazamientos en el panel, no es un error de
 *   esta matemática.
 */
object LayoutGeometry {
    /** Margen exterior de la pantalla (padding del fondo). */
    const val OUTER_PADDING_DP = 12f

    // Tamaños base de cada elemento (dp), los mismos que GamepadScreen.kt.
    const val STICK_JOYSTICK_H = 180f
    const val STICK_LABEL_H = 28f // hueco 4 + píldora de la etiqueta ~24
    const val STICK_W = 180f
    const val STICK_H = STICK_JOYSTICK_H + STICK_LABEL_H // 208
    const val DPAD_W = 196f
    const val DPAD_H = 196f
    const val ABXY_W = 200f
    const val ABXY_H = 200f
    const val VIEW_W = 76f
    const val VIEW_H = 48f
    /** Xbox en horizontal: 72 dp (antes 80). En la captura del
     * usuario el botón se veía desproporcionado frente a las
     * píldoras VIEW/MENU/SHARE (48 dp de alto): 72 dp es 1,5x la
     * píldora, como en el mando Xbox real. En vertical sigue en
     * 64 dp. */
    const val XBOX_W_LANDSCAPE = 72f
    const val XBOX_H_LANDSCAPE = 72f
    const val XBOX_W_PORTRAIT = 64f
    const val XBOX_H_PORTRAIT = 64f
    const val MENU_W = 76f
    const val MENU_H = 48f
    const val SHARE_W = 76f
    const val SHARE_H = 48f
    const val L4_W = 58f
    /** L4/R4: 58x48 dp (R-03: mínimo táctil 48 dp, igual que el botón dibujado). */
    const val L4_H = 48f
    const val R4_W = 58f
    const val R4_H = 48f

    /** Hueco entre piezas de una columna y entre centro y SHARE. */
    const val COLUMN_GAP = 6f
    const val CENTER_GAP = 12f

    /** Hueco entre botones del centro y entre filas en vertical. */
    const val CENTER_SPACING = 8f
    const val PORTRAIT_CENTER_SPACING = 10f
    const val PORTRAIT_MIDDLE_SPACING = 12f

    /** Alto de la fila de hombros (lo manda la tuerca, 48 dp; hombros también 48 dp). */
    const val SHOULDER_ROW_H = 48f

    // Hombros: dos píldoras de 58 dp + hueco de 8 dp = 124 dp.
    const val SHOULDER_GROUP_W = 124f
    /** Ancho estimado de la píldora de estado, centrada. */
    const val STATUS_PILL_W = 260f
    const val GEAR_W = 48f
    const val GEAR_GAP = 8f

    /** Ajuste mínimo/máximo del mando en horizontal. */
    const val MIN_FIT = 0.45f
    const val MAX_FIT = 1f

    /** Ajuste mínimo/máximo del mando en vertical y ancho de referencia. */
    const val MIN_FIT_SCALE_PORTRAIT = 0.55f
    const val PORTRAIT_REF_W = 440f

    /** Un elemento del mando, sin Compose: visible, escala y desplazamiento. */
    data class GeomElement(
        val visible: Boolean = true,
        val scale: Float = 1f,
        val offsetXdp: Int = 0,
        val offsetYdp: Int = 0,
    )

    /** Los doce elementos personalizables del mando. */
    data class GeomLayout(
        val leftStick: GeomElement = GeomElement(),
        val dpad: GeomElement = GeomElement(),
        val abxy: GeomElement = GeomElement(),
        val rightStick: GeomElement = GeomElement(),
        val view: GeomElement = GeomElement(),
        val xbox: GeomElement = GeomElement(),
        val menu: GeomElement = GeomElement(),
        val share: GeomElement = GeomElement(),
        val shouldersLeft: GeomElement = GeomElement(),
        val shouldersRight: GeomElement = GeomElement(),
        val l4: GeomElement = GeomElement(),
        val r4: GeomElement = GeomElement(),
    )

    /** Una pieza de una columna vertical: su altura de layout y su
     * extensión visible (arriba/abajo) respecto a la parte superior
     * de su hueco, con su desplazamiento ya sumado. */
    data class SpanItem(val visible: Boolean, val h: Float, val top: Float, val bottom: Float)

    /** Geometría de una columna: altura de layout, arriba/abajo
     * visibles, desplazamiento que centra el contenido y altura
     * del contenido visible. */
    data class ColumnGeometry(val layoutH: Float, val top: Float, val bottom: Float) {
        /** Desplazamiento que centra el contenido visible en su hueco. */
        val shift: Float get() = (layoutH - top - bottom) / 2f
        /** Altura del contenido visible (de arriba a abajo). */
        val spanH: Float get() = bottom - top
    }

    /** Columnas en horizontal, con el ajuste y el hueco cruceta-etiqueta. */
    data class LandscapeColumns(
        val left: ColumnGeometry,
        val right: ColumnGeometry,
        val center: ColumnGeometry,
        val maxSpanH: Float,
        val rowH: Float,
        val contentW: Float,
        /** Hueco vertical entre la parte superior de la cruceta y la
         * parte inferior de la etiqueta L3/R3: >= 0 no se tapa.
         * La escala se cancela (la caja del stick crece con ella),
         * así que solo depende de los desplazamientos: 6 + offY
         * de la cruceta - offY del stick. */
        val dpadLabelGap: Float,
    )

    /** Bordes de los hombros ya recortados, en coordenadas de la fila. */
    data class ShoulderEdges(
        /** Borde izquierdo de LT. */
        val ltLeft: Float,
        /** Borde derecho de RT. */
        val rtRight: Float,
        /** Borde izquierdo de la tuerca. */
        val gearLeft: Float,
    ) {
        /** Hueco libre entre RT y la tuerca (>= 0 no se tapa). */
        val rtGearGap: Float get() = gearLeft - rtRight
    }

    /** Geometría en vertical: alturas y anchos ya escalados. */
    data class PortraitGeometry(
        val totalH: Float,
        val contentW: Float,
        val fitScale: Float,
        val scaledH: Float,
        val scaledW: Float,
        val availH: Float,
        val availW: Float,
    )

    /** Extensión de una columna: altura de layout, arriba visible y
     * abajo visible, con los huecos entre piezas. Una pieza oculta
     * no ocupa nada ni deja hueco. */
    fun vSpan(items: List<SpanItem>, gap: Float): Triple<Float, Float, Float> {
        var y = 0f
        var layoutH = 0f
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var first = true
        for (it in items) {
            if (!it.visible) continue
            if (!first) {
                y += gap
                layoutH += gap
            }
            first = false
            top = minOf(top, y + it.top)
            bottom = maxOf(bottom, y + it.bottom)
            y += it.h
            layoutH += it.h
        }
        if (first) return Triple(0f, 0f, 0f)
        return Triple(layoutH, top, bottom)
    }

    fun columnGeometry(items: List<SpanItem>, gap: Float): ColumnGeometry {
        val (layoutH, top, bottom) = vSpan(items, gap)
        return ColumnGeometry(layoutH, top, bottom)
    }

    /** Columnas, ajuste de ancho y hueco cruceta-etiqueta en
     * horizontal. Copia exacta de la matemática de GamepadScreen. */
    fun landscapeColumns(layout: GeomLayout): LandscapeColumns {
        val ls = layout.leftStick
        val dp = layout.dpad
        val ab = layout.abxy
        val rs = layout.rightStick
        val vx = layout.view
        val xb = layout.xbox
        val mn = layout.menu
        val sh = layout.share
        val l4 = layout.l4
        val r4 = layout.r4
        // Columna izquierda: stick (180 + etiqueta 28) + hueco 6 + cruceta 196.
        val left = columnGeometry(
            listOf(
                SpanItem(ls.visible, STICK_H * ls.scale, ls.offsetYdp.toFloat(), ls.offsetYdp + STICK_H * ls.scale),
                SpanItem(dp.visible, DPAD_H * dp.scale, dp.offsetYdp.toFloat(), dp.offsetYdp + DPAD_H * dp.scale),
            ),
            COLUMN_GAP,
        )
        // Columna derecha: ABXY 200 + hueco 6 + stick 208.
        val right = columnGeometry(
            listOf(
                SpanItem(ab.visible, ABXY_H * ab.scale, ab.offsetYdp.toFloat(), ab.offsetYdp + ABXY_H * ab.scale),
                SpanItem(rs.visible, STICK_H * rs.scale, rs.offsetYdp.toFloat(), rs.offsetYdp + STICK_H * rs.scale),
            ),
            COLUMN_GAP,
        )
        // Centro: fila VIEW/Xbox/MENU + hueco 12 + SHARE + hueco 12 + fila L4/R4.
        val centerRowH = maxOf(
            if (vx.visible) VIEW_H * vx.scale else 0f,
            if (xb.visible) XBOX_H_LANDSCAPE * xb.scale else 0f,
            if (mn.visible) MENU_H * mn.scale else 0f,
        )
        val centerRowTop = minOf(
            if (vx.visible) vx.offsetYdp.toFloat() else Float.MAX_VALUE,
            if (xb.visible) xb.offsetYdp.toFloat() else Float.MAX_VALUE,
            if (mn.visible) mn.offsetYdp.toFloat() else Float.MAX_VALUE,
        )
        val centerRowBottom = maxOf(
            if (vx.visible) vx.offsetYdp + VIEW_H * vx.scale else -Float.MAX_VALUE,
            if (xb.visible) xb.offsetYdp + XBOX_H_LANDSCAPE * xb.scale else -Float.MAX_VALUE,
            if (mn.visible) mn.offsetYdp + MENU_H * mn.scale else -Float.MAX_VALUE,
        )
        val centerRowVisible = vx.visible || xb.visible || mn.visible
        // Paletas traseras L4/R4 (Fase 4): tercera fila del centro,
        // debajo de SHARE. Antes no estaban en la geometría y la
        // columna del centro se calculaba con solo dos filas (140 dp
        // en vez de 182 dp con el Xbox a 72 dp): el ajuste y el
        // centrado del centro usaban una altura que no era la real.
        val rearRowH = maxOf(
            if (l4.visible) L4_H * l4.scale else 0f,
            if (r4.visible) R4_H * r4.scale else 0f,
        )
        val rearRowTop = minOf(
            if (l4.visible) l4.offsetYdp.toFloat() else Float.MAX_VALUE,
            if (r4.visible) r4.offsetYdp.toFloat() else Float.MAX_VALUE,
        )
        val rearRowBottom = maxOf(
            if (l4.visible) l4.offsetYdp + L4_H * l4.scale else -Float.MAX_VALUE,
            if (r4.visible) r4.offsetYdp + R4_H * r4.scale else -Float.MAX_VALUE,
        )
        val rearRowVisible = l4.visible || r4.visible
        val center = columnGeometry(
            listOf(
                SpanItem(centerRowVisible, centerRowH, centerRowTop, centerRowBottom),
                SpanItem(sh.visible, SHARE_H * sh.scale, sh.offsetYdp.toFloat(), sh.offsetYdp + SHARE_H * sh.scale),
                SpanItem(rearRowVisible, rearRowH, rearRowTop, rearRowBottom),
            ),
            CENTER_GAP,
        )
        val maxSpanH = maxOf(left.spanH, right.spanH, center.spanH)
        val rowH = maxOf(left.layoutH, right.layoutH, center.layoutH, maxSpanH)
        // Anchura del contenido (para el ajuste horizontal).
        val leftW = maxOf(if (ls.visible) STICK_W * ls.scale else 0f, if (dp.visible) DPAD_W * dp.scale else 0f)
        val rightW = maxOf(if (ab.visible) ABXY_W * ab.scale else 0f, if (rs.visible) STICK_W * rs.scale else 0f)
        val centerVisibleCount = listOf(vx, xb, mn).count { it.visible }
        val centerW = (if (vx.visible) VIEW_W * vx.scale else 0f) +
            (if (xb.visible) XBOX_W_LANDSCAPE * xb.scale else 0f) +
            (if (mn.visible) MENU_W * mn.scale else 0f) +
            CENTER_SPACING * (centerVisibleCount - 1).coerceAtLeast(0)
        val maxOffX = maxOf(
            abs(ls.offsetXdp), abs(dp.offsetXdp), abs(ab.offsetXdp), abs(rs.offsetXdp),
            abs(vx.offsetXdp), abs(xb.offsetXdp), abs(mn.offsetXdp), abs(sh.offsetXdp),
            abs(l4.offsetXdp), abs(r4.offsetXdp),
        ).toFloat()
        val contentW = leftW + centerW + rightW + 32f + 2f * maxOffX
        // La cruceta empieza tras el stick + hueco; la etiqueta está
        // al fondo del elemento stick. Las escalas se cancelan.
        val dpadTop = STICK_H * ls.scale + COLUMN_GAP + dp.offsetYdp
        val labelBottom = ls.offsetYdp + STICK_H * ls.scale
        return LandscapeColumns(left, right, center, maxSpanH, rowH, contentW, dpadTop - labelBottom)
    }

    /** Ajuste en horizontal: que el contenido visible quepa en el
     * hueco disponible, sin pasar de 1 y sin bajar de 0.45. */
    fun fitFor(availW: Float, availH: Float, maxSpanH: Float, contentW: Float): Float =
        minOf(availH / maxSpanH, availW / contentW, MAX_FIT).coerceIn(MIN_FIT, MAX_FIT)

    /** Ajuste en vertical: solo depende del ancho de pantalla. */
    fun portraitFitScale(areaW: Float): Float =
        (areaW / PORTRAIT_REF_W).coerceIn(MIN_FIT_SCALE_PORTRAIT, MAX_FIT)

    /** Recorte de los hombros al dibujar (lo guardado no cambia):
     * cada grupo puede moverse hacia el centro todo lo que quiera,
     * pero nunca hacia la tuerca ni fuera de la pantalla. Devuelve
     * (desplazamiento X izquierdo, desplazamiento X derecho). */
    fun clampShoulders(clampWidth: Float, left: GeomElement, right: GeomElement): Pair<Float, Float> {
        // Izquierda: que LT no se salga por el borde (mínimo -8 dp) y
        // que no tape la píldora de estado (ancho estimado 260 dp).
        val leftLo = -8f
        val leftHi = (clampWidth - STATUS_PILL_W) / 2f - SHOULDER_GROUP_W * left.scale
        val leftOffX = if (leftLo > leftHi) leftLo else left.offsetXdp.toFloat().coerceIn(leftLo, leftHi)
        // Derecha: que RT no llegue a la tuerca (máximo 0 dp, así
        // queda el hueco de 8 dp) y que no tape la píldora de estado.
        val rightLo = SHOULDER_GROUP_W * right.scale + 178f - clampWidth / 2f
        val rightHi = 0f
        val rightOffX = if (rightLo > rightHi) rightHi else right.offsetXdp.toFloat().coerceIn(rightLo, rightHi)
        return leftOffX to rightOffX
    }

    /** Bordes de LT, RT y la tuerca en una fila de ancho [rowW]. */
    fun shoulderEdges(rowW: Float, leftOffX: Float, rightOffX: Float): ShoulderEdges =
        ShoulderEdges(
            ltLeft = leftOffX,
            rtRight = rowW - GEAR_W - GEAR_GAP + rightOffX,
            gearLeft = rowW - GEAR_W,
        )

    /** Geometría en vertical: el contenido son dos filas (stick +
     * ABXY arriba, cruceta + stick abajo), un hueco de 12 dp y la
     * fila del centro, todo escalado por el ancho de pantalla. */
    fun portrait(layout: GeomLayout, areaW: Float, areaH: Float): PortraitGeometry {
        val ls = layout.leftStick
        val dp = layout.dpad
        val ab = layout.abxy
        val rs = layout.rightStick
        val vx = layout.view
        val xb = layout.xbox
        val mn = layout.menu
        val sh = layout.share
        val l4 = layout.l4
        val r4 = layout.r4
        val row1H = maxOf(if (ls.visible) STICK_H * ls.scale else 0f, if (ab.visible) ABXY_H * ab.scale else 0f)
        val row2H = maxOf(if (dp.visible) DPAD_H * dp.scale else 0f, if (rs.visible) STICK_H * rs.scale else 0f)
        val centerH = maxOf(
            if (vx.visible) VIEW_H * vx.scale else 0f,
            if (xb.visible) XBOX_H_PORTRAIT * xb.scale else 0f,
            if (mn.visible) MENU_H * mn.scale else 0f,
            if (sh.visible) SHARE_H * sh.scale else 0f,
            if (l4.visible) L4_H * l4.scale else 0f,
            if (r4.visible) R4_H * r4.scale else 0f,
        )
        val row1W = (if (ls.visible) STICK_W * ls.scale else 0f) + (if (ab.visible) ABXY_W * ab.scale else 0f)
        val row2W = (if (dp.visible) DPAD_W * dp.scale else 0f) + (if (rs.visible) STICK_W * rs.scale else 0f)
        // La fila central en vertical son SEIS piezas (L4, VIEW,
        // Xbox, MENU, SHARE, R4): 458 dp con el diseño por
        // defecto. Antes solo se contaban cuatro (322 dp) y R4
        // quedaba fuera por la derecha en teléfonos de 390 dp.
        val centerCount = listOf(vx, xb, mn, sh, l4, r4).count { it.visible }
        val centerW = (if (vx.visible) VIEW_W * vx.scale else 0f) +
            (if (xb.visible) XBOX_W_PORTRAIT * xb.scale else 0f) +
            (if (mn.visible) MENU_W * mn.scale else 0f) +
            (if (sh.visible) SHARE_W * sh.scale else 0f) +
            (if (l4.visible) L4_W * l4.scale else 0f) +
            (if (r4.visible) R4_W * r4.scale else 0f) +
            PORTRAIT_CENTER_SPACING * (centerCount - 1).coerceAtLeast(0)
        val totalH = row1H + row2H + PORTRAIT_MIDDLE_SPACING + centerH
        val contentW = maxOf(row1W, row2W, centerW)
        // El ajuste tiene en cuenta también el ancho del
        // contenido, no solo el de la pantalla: con la fila de
        // 458 dp en un área de 366 dp el ajuste es 366/458 =
        // 0,799 (antes era 366/440 = 0,832 y la fila se salía).
        // Con el suelo de 0.55, a escala 1.6 el contenido ya no
        // cabe en un teléfono vertical (límite conocido).
        val widthFit = if (contentW > 0f) areaW / contentW else Float.MAX_VALUE
        val fitScale = minOf(portraitFitScale(areaW), widthFit).coerceAtLeast(MIN_FIT_SCALE_PORTRAIT)
        val availH = areaH - SHOULDER_ROW_H
        return PortraitGeometry(totalH, contentW, fitScale, totalH * fitScale, contentW * fitScale, availH, areaW)
    }

    /** Rectángulo de un elemento tal y como lo dibuja GamepadScreen,
     * en coordenadas del área (dentro del padding exterior, dp):
     * x de 0 a [areaW], y de 0 a [areaH]; la fila de hombros ocupa
     * y 0..40. Es la misma matemática que usa la pantalla real y la
     * vista previa del panel de botones: un desplazamiento guardado
     * en el editor produce exactamente este rectángulo en el mando.
     * Sin avisos de error/info: con un aviso visible el cuerpo
     * dispone de menos alto y el ajuste cambia (como en la real). */
    data class ElementRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** Los doce elementos del mando con su rectángulo en el área
     * (null = oculto, no se dibuja). */
    data class ElementRects(
        val shouldersLeft: ElementRect?,
        val shouldersRight: ElementRect?,
        val leftStick: ElementRect?,
        val dpad: ElementRect?,
        val abxy: ElementRect?,
        val rightStick: ElementRect?,
        val view: ElementRect?,
        val xbox: ElementRect?,
        val menu: ElementRect?,
        val share: ElementRect?,
        val l4: ElementRect?,
        val r4: ElementRect?,
    )

    /** Dónde cae cada elemento en la pantalla real, con el diseño
     * [layout] y un área de [areaW] x [areaH] dp (dentro del padding
     * exterior). Sigue la estructura de GamepadScreen paso a paso:
     * fila de hombros arriba (40 dp), cuerpo debajo; en horizontal
     * tres columnas en un Row con SpaceEvenly y ajuste [fitFor],
     * en vertical dos filas + fila central con ajuste de ancho.
     * La vista previa del panel usa esta misma función para
     * recortar los arrastres: así lo que se ve en el editor es lo
     * que se juega, sin la miniatura ficticia de 800x300 dp que
     * cortaba la cruceta y no coincidía con el mando. */
    fun elementRects(layout: GeomLayout, areaW: Float, areaH: Float): ElementRects {
        val ls = layout.leftStick
        val dp = layout.dpad
        val ab = layout.abxy
        val rs = layout.rightStick
        val vx = layout.view
        val xb = layout.xbox
        val mn = layout.menu
        val sh = layout.share
        val l4 = layout.l4
        val r4 = layout.r4
        val sl = layout.shouldersLeft
        val sr = layout.shouldersRight
        // Hombros: no pasan por el ajuste global; su desplazamiento
        // dibujado es el recortado (clampShoulders), como en la real.
        val (leftOffX, rightOffX) = clampShoulders(areaW - 24f, sl, sr)
        val shoulderRowH = maxOf(48f * sl.scale, 48f * sr.scale, 48f)
        val shouldersLeftRect = if (sl.visible) {
            val top = (shoulderRowH - 48f * sl.scale) / 2f + sl.offsetYdp
            ElementRect(leftOffX, top, leftOffX + SHOULDER_GROUP_W * sl.scale, top + 48f * sl.scale)
        } else null
        val shouldersRightRect = if (sr.visible) {
            val rightRowW = SHOULDER_GROUP_W * sr.scale + 8f + GEAR_W
            val left = areaW - rightRowW + rightOffX
            val top = (shoulderRowH - 48f * sr.scale) / 2f + sr.offsetYdp
            ElementRect(left, top, left + SHOULDER_GROUP_W * sr.scale, top + 48f * sr.scale)
        } else null
        if (areaW > areaH) {
            // --- Horizontal: tres columnas, Row SpaceEvenly, ajuste fit. ---
            val cols = landscapeColumns(layout)
            val bodyH = areaH - SHOULDER_ROW_H
            val fit = fitFor(areaW, bodyH, cols.maxSpanH, cols.contentW)
            val leftW = maxOf(if (ls.visible) STICK_W * ls.scale else 0f, if (dp.visible) DPAD_W * dp.scale else 0f)
            val rightW = maxOf(if (ab.visible) ABXY_W * ab.scale else 0f, if (rs.visible) STICK_W * rs.scale else 0f)
            val centerRowW = (if (vx.visible) VIEW_W * vx.scale else 0f) +
                (if (xb.visible) XBOX_W_LANDSCAPE * xb.scale else 0f) +
                (if (mn.visible) MENU_W * mn.scale else 0f) +
                CENTER_SPACING * (listOf(vx, xb, mn).count { it.visible } - 1).coerceAtLeast(0)
            val shareW = if (sh.visible) SHARE_W * sh.scale else 0f
            val rearW = (if (l4.visible) L4_W * l4.scale else 0f) +
                (if (r4.visible) R4_W * r4.scale else 0f) +
                CENTER_SPACING * (listOf(l4, r4).count { it.visible } - 1).coerceAtLeast(0)
            val centerW = maxOf(centerRowW, shareW, rearW)
            val gap = (areaW - leftW - centerW - rightW) / 4f
            val leftX = gap
            val centerX = gap + leftW + gap
            val rightX = gap + leftW + gap + centerW + gap
            val rowCenterY = SHOULDER_ROW_H + bodyH / 2f
            // La fila entera se escala por [fit] alrededor de su centro.
            fun mapX(localX: Float) = areaW / 2f + (localX - areaW / 2f) * fit
            fun mapY(localY: Float) = rowCenterY + (localY - cols.rowH / 2f) * fit
            fun rect(localLeft: Float, localTop: Float, w: Float, h: Float) =
                ElementRect(mapX(localLeft), mapY(localTop), mapX(localLeft + w), mapY(localTop + h))
            // Columna izquierda: stick arriba, cruceta abajo.
            val leftTop = (cols.rowH - cols.left.layoutH) / 2f + cols.left.shift
            val dpadSlot = if (ls.visible) STICK_H * ls.scale + COLUMN_GAP else 0f
            val leftStickRect = if (ls.visible) rect(
                leftX + (leftW - STICK_W * ls.scale) / 2f + ls.offsetXdp,
                leftTop + ls.offsetYdp, STICK_W * ls.scale, STICK_H * ls.scale,
            ) else null
            val dpadRect = if (dp.visible) rect(
                leftX + (leftW - DPAD_W * dp.scale) / 2f + dp.offsetXdp,
                leftTop + dpadSlot + dp.offsetYdp, DPAD_W * dp.scale, DPAD_H * dp.scale,
            ) else null
            // Columna derecha: ABXY arriba, stick abajo.
            val rightTop = (cols.rowH - cols.right.layoutH) / 2f + cols.right.shift
            val rsSlot = if (ab.visible) ABXY_H * ab.scale + COLUMN_GAP else 0f
            val abxyRect = if (ab.visible) rect(
                rightX + (rightW - ABXY_W * ab.scale) / 2f + ab.offsetXdp,
                rightTop + ab.offsetYdp, ABXY_W * ab.scale, ABXY_H * ab.scale,
            ) else null
            val rightStickRect = if (rs.visible) rect(
                rightX + (rightW - STICK_W * rs.scale) / 2f + rs.offsetXdp,
                rightTop + rsSlot + rs.offsetYdp, STICK_W * rs.scale, STICK_H * rs.scale,
            ) else null
            // Columna central: fila VIEW/Xbox/MENU + SHARE + fila L4/R4.
            val centerRowH = maxOf(
                if (vx.visible) VIEW_H * vx.scale else 0f,
                if (xb.visible) XBOX_H_LANDSCAPE * xb.scale else 0f,
                if (mn.visible) MENU_H * mn.scale else 0f,
            )
            val rearRowH = maxOf(if (l4.visible) L4_H * l4.scale else 0f, if (r4.visible) R4_H * r4.scale else 0f)
            val centerRowVisible = vx.visible || xb.visible || mn.visible
            val shareSlot = if (centerRowVisible) centerRowH + CENTER_GAP else 0f
            val rearSlot = shareSlot + (if (sh.visible) SHARE_H * sh.scale + CENTER_GAP else 0f)
            val centerTop = (cols.rowH - cols.center.layoutH) / 2f + cols.center.shift
            val row1X0 = centerX + (centerW - centerRowW) / 2f
            var acc = 0f
            val viewRect = if (vx.visible) {
                val w = VIEW_W * vx.scale
                val r = rect(row1X0 + acc + vx.offsetXdp, centerTop + (centerRowH - VIEW_H * vx.scale) / 2f + vx.offsetYdp, w, VIEW_H * vx.scale)
                acc += w + CENTER_SPACING
                r
            } else null
            val xboxRect = if (xb.visible) {
                val w = XBOX_W_LANDSCAPE * xb.scale
                val r = rect(row1X0 + acc + xb.offsetXdp, centerTop + (centerRowH - XBOX_H_LANDSCAPE * xb.scale) / 2f + xb.offsetYdp, w, XBOX_H_LANDSCAPE * xb.scale)
                acc += w + CENTER_SPACING
                r
            } else null
            val menuRect = if (mn.visible) {
                val w = MENU_W * mn.scale
                val r = rect(row1X0 + acc + mn.offsetXdp, centerTop + (centerRowH - MENU_H * mn.scale) / 2f + mn.offsetYdp, w, MENU_H * mn.scale)
                acc += w + CENTER_SPACING
                r
            } else null
            val shareRect = if (sh.visible) rect(
                centerX + (centerW - shareW) / 2f + sh.offsetXdp,
                centerTop + shareSlot + sh.offsetYdp, SHARE_W * sh.scale, SHARE_H * sh.scale,
            ) else null
            val rearX0 = centerX + (centerW - rearW) / 2f
            var accR = 0f
            val l4Rect = if (l4.visible) {
                val w = L4_W * l4.scale
                val r = rect(rearX0 + accR + l4.offsetXdp, centerTop + rearSlot + (rearRowH - L4_H * l4.scale) / 2f + l4.offsetYdp, w, L4_H * l4.scale)
                accR += w + CENTER_SPACING
                r
            } else null
            val r4Rect = if (r4.visible) {
                val w = R4_W * r4.scale
                val r = rect(rearX0 + accR + r4.offsetXdp, centerTop + rearSlot + (rearRowH - R4_H * r4.scale) / 2f + r4.offsetYdp, w, R4_H * r4.scale)
                accR += w + CENTER_SPACING
                r
            } else null
            return ElementRects(
                shouldersLeftRect, shouldersRightRect, leftStickRect, dpadRect,
                abxyRect, rightStickRect, viewRect, xboxRect, menuRect,
                shareRect, l4Rect, r4Rect,
            )
        } else {
            // --- Vertical: dos filas + fila central, ajuste de ancho. ---
            val p = portrait(layout, areaW, areaH)
            val fit = p.fitScale
            val bodyH = areaH - SHOULDER_ROW_H
            val row2H = maxOf(if (dp.visible) DPAD_H * dp.scale else 0f, if (rs.visible) STICK_H * rs.scale else 0f)
            val centerH = maxOf(
                if (vx.visible) VIEW_H * vx.scale else 0f,
                if (xb.visible) XBOX_H_PORTRAIT * xb.scale else 0f,
                if (mn.visible) MENU_H * mn.scale else 0f,
                if (sh.visible) SHARE_H * sh.scale else 0f,
                if (l4.visible) L4_H * l4.scale else 0f,
                if (r4.visible) R4_H * r4.scale else 0f,
            )
            val row1H = (bodyH - row2H - PORTRAIT_MIDDLE_SPACING - centerH).coerceAtLeast(0f)
            // Toda la columna se escala por [fit] alrededor del centro
            // superior (TransformOrigin 0.5, 0) y empieza bajo los hombros.
            fun mapP(x: Float, y: Float, w: Float, h: Float) = ElementRect(
                areaW / 2f + (x - areaW / 2f) * fit,
                SHOULDER_ROW_H + y * fit,
                areaW / 2f + (x + w - areaW / 2f) * fit,
                SHOULDER_ROW_H + (y + h) * fit,
            )
            // X de cada pieza en un Row con SpaceEvenly.
            fun evenlyXs(widths: List<Float>): List<Float> {
                if (widths.isEmpty()) return emptyList()
                val gap = (areaW - widths.sum()) / (widths.size + 1)
                var x = gap
                return widths.map { w -> val r = x; x += w + gap; r }
            }
            // Fila 1: stick izquierdo + ABXY.
            val row1Widths = listOfNotNull(
                if (ls.visible) STICK_W * ls.scale else null,
                if (ab.visible) ABXY_W * ab.scale else null,
            )
            val row1Xs = evenlyXs(row1Widths)
            var i1 = 0
            val leftStickRect = if (ls.visible) {
                mapP(row1Xs[i1++] + ls.offsetXdp, (row1H - STICK_H * ls.scale) / 2f + ls.offsetYdp, STICK_W * ls.scale, STICK_H * ls.scale)
            } else null
            val abxyRect = if (ab.visible) {
                mapP(row1Xs[i1++] + ab.offsetXdp, (row1H - ABXY_H * ab.scale) / 2f + ab.offsetYdp, ABXY_W * ab.scale, ABXY_H * ab.scale)
            } else null
            // Fila 2: cruceta + stick derecho.
            val row2Widths = listOfNotNull(
                if (dp.visible) DPAD_W * dp.scale else null,
                if (rs.visible) STICK_W * rs.scale else null,
            )
            val row2Xs = evenlyXs(row2Widths)
            var i2 = 0
            val dpadRect = if (dp.visible) {
                mapP(row2Xs[i2++] + dp.offsetXdp, row1H + (row2H - DPAD_H * dp.scale) / 2f + dp.offsetYdp, DPAD_W * dp.scale, DPAD_H * dp.scale)
            } else null
            val rightStickRect = if (rs.visible) {
                mapP(row2Xs[i2++] + rs.offsetXdp, row1H + (row2H - STICK_H * rs.scale) / 2f + rs.offsetYdp, STICK_W * rs.scale, STICK_H * rs.scale)
            } else null
            // Fila central: seis piezas en una fila centrada.
            val centerItems = listOfNotNull(
                if (l4.visible) Triple(l4, L4_W * l4.scale, L4_H * l4.scale) else null,
                if (vx.visible) Triple(vx, VIEW_W * vx.scale, VIEW_H * vx.scale) else null,
                if (xb.visible) Triple(xb, XBOX_W_PORTRAIT * xb.scale, XBOX_H_PORTRAIT * xb.scale) else null,
                if (mn.visible) Triple(mn, MENU_W * mn.scale, MENU_H * mn.scale) else null,
                if (sh.visible) Triple(sh, SHARE_W * sh.scale, SHARE_H * sh.scale) else null,
                if (r4.visible) Triple(r4, R4_W * r4.scale, R4_H * r4.scale) else null,
            )
            val centerGroupW = centerItems.sumOf { it.second.toDouble() }.toFloat() +
                PORTRAIT_CENTER_SPACING * (centerItems.size - 1).coerceAtLeast(0)
            val centerY0 = row1H + row2H + PORTRAIT_MIDDLE_SPACING
            var accC = (areaW - centerGroupW) / 2f
            // Rectángulos en el mismo orden que centerItems (L4,
            // VIEW, Xbox, MENU, SHARE, R4): se recogen por índice,
            // no por elemento (dos elementos por defecto son
            // iguales como clave de mapa y se pisarían).
            val centerRectList = mutableListOf<ElementRect>()
            for ((el, w, h) in centerItems) {
                centerRectList += mapP(accC + el.offsetXdp, centerY0 + (centerH - h) / 2f + el.offsetYdp, w, h)
                accC += w + PORTRAIT_CENTER_SPACING
            }
            var ci = 0
            fun nextCenterRect(visible: Boolean): ElementRect? {
                if (!visible) return null
                return centerRectList[ci++]
            }
            val l4RectP = nextCenterRect(l4.visible)
            val viewRectP = nextCenterRect(vx.visible)
            val xboxRectP = nextCenterRect(xb.visible)
            val menuRectP = nextCenterRect(mn.visible)
            val shareRectP = nextCenterRect(sh.visible)
            val r4RectP = nextCenterRect(r4.visible)
            return ElementRects(
                shouldersLeftRect, shouldersRightRect, leftStickRect, dpadRect,
                abxyRect, rightStickRect,
                viewRectP, xboxRectP, menuRectP, shareRectP, l4RectP, r4RectP,
            )
        }
    }
}
