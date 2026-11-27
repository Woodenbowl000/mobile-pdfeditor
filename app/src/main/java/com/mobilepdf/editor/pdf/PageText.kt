package com.mobilepdf.editor.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Text of one page plus a normalized (0..1, top-left origin, rotation-aware) box per character.
 * Powers Find, and the "drag over words to highlight/underline/strike" tools.
 * Space/newline entries have a null box.
 */
/**
 * A piece of one line drawn in one font: the unit "Edit text" works on, and what Edit-text mode outlines
 * (one colour per font). [invisible] = text drawn invisibly over a scan (an OCR layer).
 */
data class TextRun(val text: String, val rect: Rect, val font: String, val size: Float, val invisible: Boolean)

/** "ABCDEF+Calibri-Bold" -> "Calibri-Bold": subset tags mean nothing to people. */
fun displayFontName(raw: String?): String = raw?.substringAfter('+')?.ifBlank { null } ?: "Unknown font"

class PageText(
    val text: String, private val boxes: List<Rect?>, private val sizes: List<Float> = emptyList(),
    private val fonts: List<String?> = emptyList(), private val hidden: List<Boolean> = emptyList(),
) {
    val isEmpty get() = boxes.none { it != null }

    /**
     * Every line split where the font (or visibility) changes, and where a wide gap separates words:
     * table cells and columns are separate pieces of text even in the same font (a normal word space is
     * ~0.25-0.35 em; cells are much further apart). Scanned (OCR) text uses PdfCraft's 0.6 em.
     */
    val runs: List<TextRun> by lazy {
        val out = ArrayList<TextRun>()
        var start = -1
        var lastBox = -1 // index of the previous character that has a box
        fun key(i: Int) = (fonts.getOrNull(i) ?: "") + "|" + (hidden.getOrNull(i) ?: false)
        fun flush(end: Int) {
            if (start < 0) return
            val r = rectsFor(start, end).firstOrNull()
            val t = text.substring(start, end).trim()
            if (r != null && t.isNotEmpty()) {
                val sz = (start until end).mapNotNull { sizes.getOrNull(it)?.takeIf { v -> v > 0f } }.sorted()
                out += TextRun(t, r, displayFontName(fonts.getOrNull(start)), if (sz.isEmpty()) 0f else sz[sz.size / 2], hidden.getOrNull(start) ?: false)
            }
            start = -1
        }
        fun wideGap(i: Int): Boolean {
            if (lastBox < 0) return false
            val prev = boxes[lastBox] ?: return false
            val cur = boxes[i] ?: return false
            val em = sizes.getOrNull(i)?.takeIf { it > 0f } ?: return false
            val limit = if (hidden.getOrNull(i) == true) 0.6f else 0.8f
            return cur.left - prev.right > limit * em
        }
        for (i in text.indices) {
            when {
                text[i] == '\n' -> { flush(i); lastBox = -1 }
                boxes.getOrNull(i) == null -> Unit // word space: stays inside the current run
                start < 0 -> start = i
                key(i) != key(start) -> { flush(i); start = i }
                wideGap(i) -> { flush(lastBox + 1); start = i } // next table cell / column
            }
            if (boxes.getOrNull(i) != null) lastBox = i
        }
        flush(text.length)
        out
    }

    /** The run under (or nearest to, on the same line) [p], or null if no text is close. */
    /**
     * The run under a tap at [p]. Small text (table cells, footnotes) is shorter than a fingertip, so a tap
     * counts within [slop] (fraction of page width / height) of a run; the nearest one wins.
     */
    fun runAt(p: Offset, slop: Offset = Offset(0.02f, 0.015f)): TextRun? {
        var best: TextRun? = null; var bestD = Float.MAX_VALUE
        for (r in runs) {
            val tx = maxOf(slop.x, 0.01f); val ty = maxOf(slop.y, r.rect.height * 0.6f)
            val dx = maxOf(0f, r.rect.left - p.x, p.x - r.rect.right)
            val dy = maxOf(0f, r.rect.top - p.y, p.y - r.rect.bottom)
            if (dx > tx || dy > ty) continue
            val d = (dx / tx) * (dx / tx) + (dy / ty) * (dy / ty)
            // overlapping hits (d = 0 for several): the smaller run is the more specific one
            val dd = if (d == 0f) -1f / (1f + r.rect.width * r.rect.height * 1e4f) else d
            if (dd < bestD) { bestD = dd; best = r }
        }
        return best
    }

    /** Every match of [re] that [accept]s: its text and its per-line rectangles (find & redact). */
    fun findRegex(re: Regex, accept: (String) -> Boolean = { true }): List<Pair<String, List<Rect>>> =
        re.findAll(text).filter { accept(it.value) }.mapNotNull { m ->
            rectsFor(m.range.first, m.range.last + 1).takeIf { it.isNotEmpty() }?.let { m.value to it }
        }.toList()

    /** The characters between two touch points (text selection / copy), line breaks kept. */
    fun textBetween(a: Offset, b: Offset): String {
        val ia = nearest(a); val ib = nearest(b)
        if (ia < 0 || ib < 0) return ""
        return text.substring(min(ia, ib), max(ia, ib) + 1).trim()
    }

    /** Touch points of the page's first and last character (select all). */
    fun ends(): Pair<Offset, Offset>? {
        val f = boxes.firstOrNull { it != null } ?: return null
        val l = boxes.lastOrNull { it != null } ?: return null
        return f.center to l.center
    }

    /** The word under [p]: its start and end touch points, for a long-press selection. */
    fun wordAt(p: Offset): Pair<Offset, Offset>? {
        val i = nearest(p)
        if (i < 0) return null
        val box = boxes[i]!!
        if (p.y < box.top - box.height || p.y > box.bottom + box.height) return null
        var s = i; while (s > 0 && boxes[s - 1] != null && !text[s - 1].isWhitespace()) s--
        var e = i; while (e + 1 < text.length && boxes[e + 1] != null && !text[e + 1].isWhitespace()) e++
        val a = boxes[s]!!; val b = boxes[e]!!
        return Offset(a.left + a.width * 0.3f, (a.top + a.bottom) / 2) to Offset(b.right - b.width * 0.3f, (b.top + b.bottom) / 2)
    }

    /**
     * The paragraph around the run at [p]: consecutive lines below and above it in the same font and size,
     * starting near the same left edge (first lines may be indented) and evenly spaced. Null if it's one line.
     */
    fun paragraphAt(p: Offset, slop: Offset = Offset(0.02f, 0.015f)): List<TextRun>? {
        val run = runAt(p, slop) ?: return null
        val em = run.rect.height.takeIf { it > 0f } ?: return null
        val vis = runs.filter { it.invisible == run.invisible }
        fun sameBase(a: TextRun, b: TextRun) = kotlin.math.abs(a.rect.bottom - b.rect.bottom) <= em * 0.35f
        // a visual line: every piece on one baseline (any font: italics, numbers, symbols), as long as the
        // pieces follow each other closely (a wide gap is the next column)
        fun lineFrom(seed: TextRun, l: Float, r: Float): List<TextRun> {
            val onBase = vis.filter { sameBase(it, seed) && it.rect.right > l - em * 2f && it.rect.left < r + em * 2f }.sortedBy { it.rect.left }
            val i = onBase.indexOf(seed).takeIf { it >= 0 } ?: 0
            var a = i; var b = i
            while (a > 0 && onBase[a].rect.left - onBase[a - 1].rect.right < em * 2.5f) a--
            while (b < onBase.lastIndex && onBase[b + 1].rect.left - onBase[b].rect.right < em * 2.5f) b++
            return onBase.subList(a, b + 1)
        }
        val tapped = lineFrom(run, run.rect.left, run.rect.right)
        val left = tapped.minOf { it.rect.left }; val right = tapped.maxOf { it.rect.right }
        // the other lines of the column, top to bottom (one seed per baseline)
        val seeds = vis.filter { it.rect.right > left && it.rect.left < right }.sortedBy { it.rect.bottom }
        val lines = ArrayList<List<TextRun>>()
        for (sd in seeds) if (lines.none { sameBase(it.first(), sd) }) lines += lineFrom(sd, left, right)
        val at = lines.indexOfFirst { l -> l.any { it === run } || sameBase(l.first(), run) }.takeIf { it >= 0 } ?: return null
        // body text of one size, starting near the same left edge (first lines may be indented)
        fun fits(l: List<TextRun>) = kotlin.math.abs(l.minOf { it.rect.left } - left) <= em * 3f &&
            // the same text height (font size may be stretched a little per line by justification, e.g. LaTeX microtype)
            l.any { kotlin.math.abs(it.rect.height - em) <= em * 0.15f }
        fun base(l: List<TextRun>) = l.maxOf { it.rect.bottom }
        val maxGap = em * 3f
        fun gap(a: List<TextRun>, b: List<TextRun>) = (base(b) - base(a)).takeIf { it > 0f && it <= maxGap && fits(a) && fits(b) }
        // the line spacing: the closer of the lines just above and below (the other side may be a paragraph gap)
        val leading = listOfNotNull(lines.getOrNull(at - 1)?.let { gap(it, lines[at]) }, lines.getOrNull(at + 1)?.let { gap(lines[at], it) })
            .minOrNull() ?: return null
        fun same(d: Float?) = d != null && kotlin.math.abs(d - leading) <= leading * 0.15f
        var top = at; var bottom = at
        while (bottom + 1 < lines.size && same(gap(lines[bottom], lines[bottom + 1]))) bottom++
        while (top - 1 >= 0 && same(gap(lines[top - 1], lines[top]))) top--
        if (bottom == top) return null
        return lines.subList(top, bottom + 1).map { l ->
            val r = l.drop(1).fold(l.first().rect) { acc, t -> Rect(minOf(acc.left, t.rect.left), minOf(acc.top, t.rect.top), maxOf(acc.right, t.rect.right), maxOf(acc.bottom, t.rect.bottom)) }
            TextRun(l.joinToString(" ") { it.text }, r, run.font, run.size, run.invisible)
        }
    }

    /**
     * The paragraph's words as one text. A line ending in a hyphen is joined without a space and the hyphen
     * kept: it may be a compound ("English-to-German"), which must not be changed silently.
     */
    fun paragraphText(lines: List<TextRun>): String {
        val sb = StringBuilder()
        for (l in lines) {
            val t = l.text.trim()
            if (sb.isNotEmpty() && !sb.endsWith("-")) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    /** Every match as a list of per-line rectangles. */
    fun find(query: String, matchCase: Boolean): List<List<Rect>> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<List<Rect>>()
        var from = 0
        while (true) {
            val i = text.indexOf(query, from, ignoreCase = !matchCase)
            if (i < 0) break
            val r = rectsFor(i, i + query.length)
            if (r.isNotEmpty()) out += r
            from = i + max(1, query.length)
        }
        return out
    }

    /**
     * The text line under (or right next to) [p]: its words, its box (bottom = baseline) and its font
     * size as a fraction of page width (0 if unknown). Null if no text is close enough.
     */
    fun lineAt(p: Offset): Triple<String, Rect, Float>? {
        val i = nearest(p)
        if (i < 0) return null
        val box = boxes[i]!!
        val slack = box.height * 1.5f
        if (p.y < box.top - slack || p.y > box.bottom + slack) return null
        var s = i; while (s > 0 && text[s - 1] != '\n') s--
        var e = i; while (e < text.length && text[e] != '\n') e++
        val r = rectsFor(s, e).firstOrNull() ?: return null
        val sz = (s until e).mapNotNull { sizes.getOrNull(it)?.takeIf { v -> v > 0f } }.sorted()
        return Triple(text.substring(s, e).trim(), r, if (sz.isEmpty()) 0f else sz[sz.size / 2])
    }

    /** Characters between the two touch points, merged into one rectangle per text line. */
    fun select(a: Offset, b: Offset): List<Rect> {
        val ia = nearest(a); val ib = nearest(b)
        if (ia < 0 || ib < 0) return emptyList()
        return rectsFor(min(ia, ib), max(ia, ib) + 1)
    }

    private fun nearest(p: Offset): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in boxes.indices) {
            val r = boxes[i] ?: continue
            val dx = max(max(r.left - p.x, p.x - r.right), 0f)
            val dy = max(max(r.top - p.y, p.y - r.bottom), 0f)
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun rectsFor(start: Int, end: Int): List<Rect> {
        val lines = ArrayList<Rect>()
        var cur: Rect? = null
        for (i in start until min(end, boxes.size)) {
            val r = boxes[i] ?: continue
            val c = cur
            if (c != null && abs(((c.top + c.bottom) - (r.top + r.bottom)) / 2f) < (c.bottom - c.top) * 0.6f) {
                cur = Rect(min(c.left, r.left), min(c.top, r.top), max(c.right, r.right), max(c.bottom, r.bottom))
            } else {
                if (c != null) lines += c
                cur = r
            }
        }
        cur?.let { lines += it }
        return lines
    }
}

/**
 * Lazily extracts [PageText] with PdfBox. The PDDocument is only loaded the first time text is needed,
 * so plain reading never pays for it. Close it when the viewer closes.
 */
class TextIndexer(private val file: File) : Closeable {
    private var doc: PDDocument? = null
    private val mutex = Mutex()
    private val cache = HashMap<Int, PageText>()

    /** Extraction is PdfBox work: always off the main thread. */
    suspend fun page(index: Int): PageText = mutex.withLock {
        cache[index] ?: withContext(Dispatchers.IO) {
            val d = doc ?: PdfOps.load(file).also { doc = it }
            extract(d, index)
        }.also { cache[index] = it }
    }

    private fun extract(d: PDDocument, index: Int): PageText {
        val page = d.getPage(index)
        val crop = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val dispW = if (rot == 90 || rot == 270) crop.height else crop.width
        val dispH = if (rot == 90 || rot == 270) crop.width else crop.height

        val sb = StringBuilder()
        val boxes = ArrayList<Rect?>()
        val sizes = ArrayList<Float>()
        val fonts = ArrayList<String?>()
        val hidden = ArrayList<Boolean>()
        // rendering mode is only known while the page is processed, not later in writeString
        val invisible = java.util.IdentityHashMap<TextPosition, Boolean>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                invisible[text] = runCatching {
                    graphicsState.textState.renderingMode == com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER
                }.getOrDefault(false)
                super.processTextPosition(text)
            }

            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                for (tp in textPositions) {
                    val u = tp.unicode ?: continue
                    val l = (tp.xDirAdj / dispW).coerceIn(0f, 1f)
                    val r = ((tp.xDirAdj + tp.widthDirAdj) / dispW).coerceIn(0f, 1f)
                    val b = (tp.yDirAdj / dispH).coerceIn(0f, 1f)
                    val t = ((tp.yDirAdj - tp.heightDir) / dispH).coerceIn(0f, 1f)
                    val box = if (r > l && b > t) Rect(l, t, r, b) else null
                    val size = tp.fontSizeInPt / dispW // exact size the PDF uses, for "Edit text"
                    val font = runCatching { tp.font?.name }.getOrNull()
                    val inv = invisible[tp] ?: false
                    for (ch in u) { sb.append(ch); boxes += box; sizes += size; fonts += font; hidden += inv }
                }
            }
            override fun writeWordSeparator() { sb.append(' '); boxes += null; sizes += 0f; fonts += null; hidden += false }
            override fun writeLineSeparator() { sb.append('\n'); boxes += null; sizes += 0f; fonts += null; hidden += false }
        }
        stripper.sortByPosition = true
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        runCatching { stripper.getText(d) } // output ignored: we collect in the overrides
        return PageText(sb.toString(), boxes, sizes, fonts, hidden)
    }

    private val links = HashMap<Int, List<PageLink>>()

    private suspend fun <T> withDoc(block: (PDDocument) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block(doc ?: PdfOps.load(file).also { doc = it }) }
    }

    /** Opens the PdfBox document ahead of time so the first tap/search does not wait for parsing. */
    suspend fun warmUp() { withDoc { } }

    suspend fun fields(): List<FormField> = withDoc { runCatching { Forms.read(it) }.getOrDefault(emptyList()) }
    suspend fun xfaOnly(): Boolean = withDoc { runCatching { Forms.hasXfaOnly(it) }.getOrDefault(false) }
    suspend fun outline(): List<Bookmark> = withDoc { runCatching { Navigation.outline(it) }.getOrDefault(emptyList()) }
    suspend fun links(index: Int): List<PageLink> {
        links[index]?.let { return it }
        return withDoc { runCatching { Navigation.links(it, index) }.getOrDefault(emptyList()) }.also { links[index] = it }
    }

    override fun close() { runCatching { doc?.close() }; doc = null; cache.clear() }
}
