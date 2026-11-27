package com.mobilepdf.editor.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Compare two versions of a document (PdfCraft `pdfcraft-compare`, text comparison): page by page, which
 * lines were removed and which were added, written as a report PDF.
 */
object Compare {
    enum class Kind { SAME, REMOVED, ADDED }
    data class Line(val kind: Kind, val text: String)
    data class Result(val pages: List<List<Line>>, val removed: Int, val added: Int) {
        val changedPages get() = pages.indices.filter { i -> pages[i].any { it.kind != Kind.SAME } }
    }

    /** The text of every page as trimmed lines (spacing normalised, so re-flowed spaces are not changes). */
    fun pageLines(file: File): List<List<String>> = PdfOps.load(file).use { d ->
        val s = PDFTextStripper().apply { sortByPosition = true }
        (1..d.numberOfPages).map { p ->
            s.startPage = p; s.endPage = p
            runCatching { s.getText(d) }.getOrDefault("").lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        }
    }

    /** Line diff (longest common subsequence): what [b] removed from [a] and added to it, in reading order. */
    fun diff(a: List<String>, b: List<String>): List<Line> {
        val n = a.size.coerceAtMost(3000); val m = b.size.coerceAtMost(3000)
        val l = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            l[i][j] = if (a[i] == b[j]) l[i + 1][j + 1] + 1 else maxOf(l[i + 1][j], l[i][j + 1])
        val out = ArrayList<Line>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += Line(Kind.SAME, a[i]); i++; j++ }
                l[i + 1][j] >= l[i][j + 1] -> out += Line(Kind.REMOVED, a[i++])
                else -> out += Line(Kind.ADDED, b[j++])
            }
        }
        while (i < n) out += Line(Kind.REMOVED, a[i++])
        while (j < m) out += Line(Kind.ADDED, b[j++])
        return out
    }

    fun compare(a: File, b: File): Result {
        val pa = pageLines(a); val pb = pageLines(b)
        val pages = (0 until maxOf(pa.size, pb.size)).map { diff(pa.getOrElse(it) { emptyList() }, pb.getOrElse(it) { emptyList() }) }
        return Result(pages, pages.sumOf { p -> p.count { it.kind == Kind.REMOVED } }, pages.sumOf { p -> p.count { it.kind == Kind.ADDED } })
    }

    /** A readable report: a summary, then each changed page with its removed (red) and added (green) lines. */
    fun report(r: Result, nameA: String, nameB: String, out: File) {
        PDDocument().use { d ->
            val font = PDType1Font.HELVETICA; val bold = PDType1Font.HELVETICA_BOLD
            val margin = 48f; val width = PDRectangle.A4.width - 2 * margin
            var page: PDPage? = null; var cs: PDPageContentStream? = null; var y = 0f
            fun newPage() {
                cs?.close()
                page = PDPage(PDRectangle.A4).also { d.addPage(it) }
                cs = PDPageContentStream(d, page)
                y = PDRectangle.A4.height - margin
            }
            fun safe(t: String) = t.map { c -> if (c.code in 32..255) c else '?' }.joinToString("")
            fun wrap(t: String, f: PDType1Font, size: Float): List<String> {
                val words = safe(t).split(' ')
                val lines = ArrayList<String>(); var cur = ""
                for (w in words) {
                    val next = if (cur.isEmpty()) w else "$cur $w"
                    if (f.getStringWidth(next) / 1000f * size > width && cur.isNotEmpty()) { lines += cur; cur = w } else cur = next
                }
                if (cur.isNotEmpty()) lines += cur
                return lines
            }
            fun write(t: String, f: PDType1Font, size: Float, rgb: Triple<Float, Float, Float> = Triple(0.1f, 0.1f, 0.1f), indent: Float = 0f, bg: Triple<Float, Float, Float>? = null) {
                for (line in wrap(t, f, size)) {
                    if (y < margin + size) newPage()
                    val c = cs!!
                    if (bg != null) {
                        c.setNonStrokingColor(bg.first, bg.second, bg.third)
                        c.addRect(margin + indent - 3f, y - size * 0.28f, width - indent + 6f, size * 1.28f); c.fill()
                    }
                    c.setNonStrokingColor(rgb.first, rgb.second, rgb.third)
                    c.beginText(); c.setFont(f, size); c.newLineAtOffset(margin + indent, y); c.showText(line); c.endText()
                    y -= size * 1.45f
                }
            }
            newPage()
            write("Comparison report", bold, 20f)
            write("Original: $nameA", font, 11f)
            write("Changed:  $nameB", font, 11f)
            val changed = r.changedPages
            write(
                if (changed.isEmpty()) "No differences in the text." else
                    "${r.removed} line(s) removed and ${r.added} added, on ${changed.size} page(s): ${changed.joinToString { (it + 1).toString() }}",
                bold, 12f,
            )
            y -= 8f
            for (p in changed) {
                y -= 6f
                write("Page ${p + 1}", bold, 14f)
                val lines = r.pages[p]
                lines.forEachIndexed { i, l ->
                    when (l.kind) {
                        Kind.REMOVED -> write("- " + l.text, font, 10.5f, Triple(0.65f, 0.08f, 0.08f), 8f, Triple(1f, 0.92f, 0.92f))
                        Kind.ADDED -> write("+ " + l.text, font, 10.5f, Triple(0.05f, 0.45f, 0.15f), 8f, Triple(0.9f, 0.98f, 0.91f))
                        // a line of context around each change, so it can be found in the document
                        Kind.SAME -> if (lines.getOrNull(i - 1)?.kind?.let { it != Kind.SAME } == true || lines.getOrNull(i + 1)?.kind?.let { it != Kind.SAME } == true)
                            write("  " + l.text, font, 10f, Triple(0.45f, 0.45f, 0.45f), 8f)
                    }
                }
            }
            cs?.close()
            d.save(out)
        }
    }
}
