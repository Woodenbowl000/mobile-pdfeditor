package com.mobilepdf.editor.annot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Annotation model. Everything is stored in *normalized page space*:
 *  x in 0..1 of the displayed page width, y in 0..1 of the displayed page height (origin top-left),
 *  sizes/widths as a fraction of page width.
 * That makes annotations independent of zoom level and of the bitmap resolution, and lets the same
 * data be drawn on screen and baked into the PDF.
 *
 * Annotations are immutable; edits replace them, which makes undo/redo a cheap list-of-snapshots.
 */
/** CLOUD: a box with a scalloped edge (review clouds); AREA: a see-through filled box (highlight on scans). */
enum class ShapeKind { RECT, OVAL, LINE, ARROW, CLOUD, AREA, TICK, CROSS, DOT }

/** The strokes of a tick or a cross inside the box [a]-[b] (Fill & Sign marks), as polylines. */
fun markStrokes(kind: ShapeKind, a: Offset, b: Offset): List<List<Offset>> {
    val x0 = minOf(a.x, b.x); val y0 = minOf(a.y, b.y); val w = kotlin.math.abs(b.x - a.x); val h = kotlin.math.abs(b.y - a.y)
    fun p(u: Float, v: Float) = Offset(x0 + u * w, y0 + v * h)
    return when (kind) {
        ShapeKind.TICK -> listOf(listOf(p(0.08f, 0.55f), p(0.38f, 0.88f), p(0.95f, 0.1f)))
        ShapeKind.CROSS -> listOf(listOf(p(0.12f, 0.12f), p(0.88f, 0.88f)), listOf(p(0.88f, 0.12f), p(0.12f, 0.88f)))
        else -> emptyList()
    }
}

/**
 * The outline of a review cloud around the box [a]-[b]: half-circle bumps along each side, bulging out.
 * In normalized page coordinates; [aspect] = page height / width keeps the bumps round.
 */
fun cloudOutline(a: Offset, b: Offset, aspect: Float, width: Float): List<Offset> {
    val r = maxOf(0.012f, width * 2.5f)
    // work in isotropic units (y scaled by the aspect), corners clockwise on screen
    val x0 = minOf(a.x, b.x); val x1 = maxOf(a.x, b.x)
    val y0 = minOf(a.y, b.y) * aspect; val y1 = maxOf(a.y, b.y) * aspect
    val corners = listOf(Offset(x0, y0), Offset(x1, y0), Offset(x1, y1), Offset(x0, y1))
    val out = ArrayList<Offset>()
    for (i in 0 until 4) {
        val p0 = corners[i]; val p1 = corners[(i + 1) % 4]
        val len = hypot(p1.x - p0.x, p1.y - p0.y)
        if (len == 0f) continue
        val dx = (p1.x - p0.x) / len; val dy = (p1.y - p0.y) / len
        val nx = dy; val ny = -dx // outward for a clockwise outline
        val bumps = maxOf(1, Math.round(len / (2 * r)))
        val seg = len / bumps; val rad = seg / 2
        for (k in 0 until bumps) {
            val cx = p0.x + dx * (seg * k + rad); val cy = p0.y + dy * (seg * k + rad)
            for (s in 0..8) {
                val phi = Math.PI * s / 8
                val px = cx - dx * rad * kotlin.math.cos(phi).toFloat() + nx * rad * kotlin.math.sin(phi).toFloat()
                val py = cy - dy * rad * kotlin.math.cos(phi).toFloat() + ny * rad * kotlin.math.sin(phi).toFloat()
                out += Offset(px, py / aspect)
            }
        }
    }
    return out
}
enum class MarkKind { HIGHLIGHT, UNDERLINE, STRIKE }

sealed class Annot {
    abstract val id: Long
    abstract val color: Int // ARGB

    data class Ink(
        override val id: Long, override val color: Int,
        val width: Float, val marker: Boolean, val pts: List<Offset>,
    ) : Annot()

    data class Shape(
        override val id: Long, override val color: Int,
        val width: Float, val kind: ShapeKind, val a: Offset, val b: Offset,
    ) : Annot()

    data class Mark(
        override val id: Long, override val color: Int,
        val kind: MarkKind, val rects: List<Rect>,
    ) : Annot()

    data class Text(
        override val id: Long, override val color: Int,
        val pos: Offset, val text: String, val size: Float,
        val font: FontChoice = FontChoice(),
    ) : Annot()

    /**
     * "Edit text": a line of the page's own text replaced by [text]. [rect] is where the original
     * words sit (bottom = their baseline); it is covered with [cover] and the new words are written
     * on the same baseline at [size] (fraction of page width).
     */
    data class Replace(
        override val id: Long, override val color: Int,
        val rect: Rect, val text: String, val size: Float, val original: String,
        val cover: Int = 0xFFFFFFFF.toInt(),
    ) : Annot()

    /**
     * Signature / initials: either hand-drawn [strokes] (in the unit square of [rect]) or a
     * photographed signature [image] (path of a PNG whose paper has been made transparent).
     */
    data class Stamp(
        override val id: Long, override val color: Int,
        val rect: Rect, val strokes: List<List<Offset>>, val image: String? = null,
    ) : Annot()

    /**
     * Redaction mark (PdfCraft: mark, then apply): the areas whose content will be removed for good when
     * the marks are applied. Until then nothing is changed and the mark is only shown.
     */
    data class Redact(
        override val id: Long, override val color: Int,
        val rects: List<Rect>, val label: String = "",
    ) : Annot()

    /** Rubber stamp (PdfCraft stamps): [text] in bold capitals inside a double frame, filling [rect]. */
    data class Seal(
        override val id: Long, override val color: Int,
        val rect: Rect, val text: String,
    ) : Annot()

    /** Sticky note: a comment pinned at [pos]; saved as a real PDF comment other apps can read. */
    data class Note(
        override val id: Long, override val color: Int,
        val pos: Offset, val text: String,
    ) : Annot()
}

/** Size of a sticky-note icon, as a fraction of page width. */
const val NOTE_SIZE = 0.05f

/** Approximate text box in normalized space. [aspect] = page height / page width. */
/**
 * How added text looks: a standard [family] ("sans", "serif", "mono") with [bold] / [italic]; or
 * [pdfFont], the name of a font the eyedropper copied from page [fromPage] of this PDF (written with that
 * very font when saved); or [file], a .ttf font the user uploaded.
 */
data class FontChoice(
    val family: String = "sans", val bold: Boolean = false, val italic: Boolean = false,
    val pdfFont: String? = null, val fromPage: Int = -1, val file: String? = null,
) {
    val label: String get() = pdfFont ?: file?.let { java.io.File(it).nameWithoutExtension }
        ?: (family.replaceFirstChar { it.uppercase() } + (if (bold) " bold" else "") + (if (italic) " italic" else ""))
}

fun textBounds(a: Annot.Text, aspect: Float): Rect {
    val lines = a.text.split('\n')
    val w = (lines.maxOfOrNull { it.length } ?: 1).coerceAtLeast(1) * a.size * 0.52f
    val h = lines.size * a.size * 1.2f / aspect
    return Rect(a.pos.x, a.pos.y, a.pos.x + w, a.pos.y + h)
}

fun Annot.bounds(aspect: Float): Rect = when (this) {
    is Annot.Ink -> pts.boundsOf().inflate(width / 2)
    is Annot.Shape -> Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)).inflate(width / 2)
    is Annot.Mark -> rects.fold(rects.first()) { acc, r -> acc.union(r) }
    is Annot.Text -> textBounds(this, aspect)
    is Annot.Stamp -> rect
    is Annot.Seal -> rect
    is Annot.Redact -> rects.fold(rects.first()) { acc, r -> acc.union(r) }
    is Annot.Note -> Rect(pos.x, pos.y, pos.x + NOTE_SIZE, pos.y + NOTE_SIZE / aspect)
    is Annot.Replace -> coverRect(this).let { c ->
        Rect(c.left, c.top, max(c.right, rect.left + text.length * size * 0.52f), c.bottom)
    }
}

/** The patch that hides the original words: their box, a little taller below for descenders (g, p, y). */
fun coverRect(a: Annot.Replace): Rect {
    val h = a.rect.height
    return Rect(a.rect.left - h * 0.08f, a.rect.top - h * 0.45f, a.rect.right + h * 0.08f, a.rect.bottom + h * 0.32f)
}

private fun Rect.union(o: Rect) = Rect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
private fun Rect.inflate(d: Float) = Rect(left - d, top - d, right + d, bottom + d)
private fun List<Offset>.boundsOf(): Rect {
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    for (p in this) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
    return Rect(l, t, r, b)
}

fun Annot.moved(d: Offset): Annot = when (this) {
    is Annot.Ink -> copy(pts = pts.map { it + d })
    is Annot.Shape -> copy(a = a + d, b = b + d)
    is Annot.Mark -> copy(rects = rects.map { it.translate(d.x, d.y) })
    is Annot.Text -> copy(pos = pos + d)
    is Annot.Stamp -> copy(rect = rect.translate(d.x, d.y))
    is Annot.Seal -> copy(rect = rect.translate(d.x, d.y))
    is Annot.Replace -> this // stays over the words it replaces
    is Annot.Redact -> copy(rects = rects.map { it.translate(d.x, d.y) })
    is Annot.Note -> copy(pos = pos + d)
}

/** Scale around the centre of the bounds. */
fun Annot.scaled(f: Float, aspect: Float): Annot {
    val c = bounds(aspect).center
    fun s(p: Offset) = c + (p - c) * f
    return when (this) {
        is Annot.Ink -> if (bounds(aspect).width * f !in 0.02f..1.2f) this else copy(pts = pts.map(::s), width = width * f)
        is Annot.Shape -> if (bounds(aspect).width * f !in 0.02f..1.2f) this else copy(a = s(a), b = s(b), width = width * f)
        is Annot.Mark -> this
        is Annot.Text -> copy(size = (size * f).coerceIn(0.01f, 0.2f))
        // a signature between 4% and 100% of the page width
        is Annot.Stamp -> if (rect.width * f !in 0.04f..1f) this else copy(rect = Rect(s(rect.topLeft), s(rect.bottomRight)))
        is Annot.Replace -> copy(size = (size * f).coerceIn(0.008f, 0.2f))
        is Annot.Redact -> if (rects.size != 1 || bounds(aspect).width * f !in 0.005f..1.2f) this
            else copy(rects = listOf(Rect(s(rects[0].topLeft), s(rects[0].bottomRight))))
        is Annot.Note -> this
        is Annot.Seal -> if (rect.width * f !in 0.08f..1f) this else copy(rect = Rect(s(rect.topLeft), s(rect.bottomRight)))
    }
}

private fun distToSegment(p: Offset, a: Offset, b: Offset, aspect: Float): Float {
    // work in "width units" so distances are isotropic
    val px = p.x; val py = p.y * aspect
    val ax = a.x; val ay = a.y * aspect
    val bx = b.x; val by = b.y * aspect
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

fun Annot.hit(p: Offset, tol: Float, aspect: Float): Boolean = when (this) {
    is Annot.Ink -> pts.zipWithNext().any { (a, b) -> distToSegment(p, a, b, aspect) <= tol + width } ||
        (pts.size == 1 && distToSegment(p, pts[0], pts[0], aspect) <= tol + width)
    is Annot.Shape -> when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> distToSegment(p, a, b, aspect) <= tol + width
        else -> bounds(aspect).inflate(tol).contains(p)
    }
    is Annot.Mark -> rects.any { it.inflate(tol).contains(p) }
    is Annot.Redact -> rects.any { it.inflate(tol).contains(p) }
    is Annot.Text, is Annot.Stamp, is Annot.Replace, is Annot.Note, is Annot.Seal -> bounds(aspect).inflate(tol).contains(p)
}
