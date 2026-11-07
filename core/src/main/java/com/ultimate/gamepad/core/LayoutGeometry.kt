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
 * - La escala era solo visual: un stick a 1.4x dibujaba 291 dp en
 *   un hueco de 208 dp y tapaba la etiqueta L3 y la cruceta. Ahora
 *   la caja crece con la escala y nada se tapa.
 * - La tuerca tapaba RT y LT se salía por el borde: los hombros
 *   se movían sin límite. Ahora el desplazamiento se recorta al
 *   dibujar (lo guardado no cambia).
 *
 * Límites conocidos (no los cubren los tests, a propósito):
 * - En vertical el ajuste depende solo del ancho de pantalla, no
 *   de la escala de los elementos: con escala 1.4 o 1.6 en un
 *   teléfono vertical los botones se salen por los lados. En
 *   horizontal el ajuste sí tiene en cuenta la escala.
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
    const val XBOX_W_LANDSCAPE = 80f
    const val XBOX_H_LANDSCAPE = 80f
    const val XBOX_W_PORTRAIT = 64f
    const val XBOX_H_PORTRAIT = 64f
    const val MENU_W = 76f
    const val MENU_H = 48f
    const val SHARE_W = 76f
    const val SHARE_H = 48f

    /** Hueco entre piezas de una columna y entre centro y SHARE. */
    const val COLUMN_GAP = 6f
    const val CENTER_GAP = 12f

    /** Hueco entre botones del centro y entre filas en vertical. */
    const val CENTER_SPACING = 8f
    const val PORTRAIT_CENTER_SPACING = 10f
    const val PORTRAIT_MIDDLE_SPACING = 12f

    /** Alto de la fila de hombros (lo manda la tuerca, 40 dp). */
    const val SHOULDER_ROW_H = 40f

    // Hombros: dos píldoras de 58 dp + hueco de 8 dp = 124 dp.
    const val SHOULDER_GROUP_W = 124f
    /** Ancho estimado de la píldora de estado, centrada. */
    const val STATUS_PILL_W = 260f
    const val GEAR_W = 40f
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

    /** Los diez elementos personalizables del mando. */
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
        // Centro: fila VIEW/Xbox/MENU + hueco 12 + SHARE.
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
        val center = columnGeometry(
            listOf(
                SpanItem(centerRowVisible, centerRowH, centerRowTop, centerRowBottom),
                SpanItem(sh.visible, SHARE_H * sh.scale, sh.offsetYdp.toFloat(), sh.offsetYdp + SHARE_H * sh.scale),
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
        val row1H = maxOf(if (ls.visible) STICK_H * ls.scale else 0f, if (ab.visible) ABXY_H * ab.scale else 0f)
        val row2H = maxOf(if (dp.visible) DPAD_H * dp.scale else 0f, if (rs.visible) STICK_H * rs.scale else 0f)
        val centerH = maxOf(
            if (vx.visible) VIEW_H * vx.scale else 0f,
            if (xb.visible) XBOX_H_PORTRAIT * xb.scale else 0f,
            if (mn.visible) MENU_H * mn.scale else 0f,
            if (sh.visible) SHARE_H * sh.scale else 0f,
        )
        val row1W = (if (ls.visible) STICK_W * ls.scale else 0f) + (if (ab.visible) ABXY_W * ab.scale else 0f)
        val row2W = (if (dp.visible) DPAD_W * dp.scale else 0f) + (if (rs.visible) STICK_W * rs.scale else 0f)
        val centerCount = listOf(vx, xb, mn, sh).count { it.visible }
        val centerW = (if (vx.visible) VIEW_W * vx.scale else 0f) +
            (if (xb.visible) XBOX_W_PORTRAIT * xb.scale else 0f) +
            (if (mn.visible) MENU_W * mn.scale else 0f) +
            (if (sh.visible) SHARE_W * sh.scale else 0f) +
            PORTRAIT_CENTER_SPACING * (centerCount - 1).coerceAtLeast(0)
        val totalH = row1H + row2H + PORTRAIT_MIDDLE_SPACING + centerH
        val contentW = maxOf(row1W, row2W, centerW)
        val fitScale = portraitFitScale(areaW)
        val availH = areaH - SHOULDER_ROW_H
        return PortraitGeometry(totalH, contentW, fitScale, totalH * fitScale, contentW * fitScale, availH, areaW)
    }
}
