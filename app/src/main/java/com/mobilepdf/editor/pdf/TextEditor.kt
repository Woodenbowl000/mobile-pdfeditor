package com.mobilepdf.editor.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.hypot

/**
 * Edit existing text, modelled on PdfCraft's "Edit text" (docs/editing-existing-text.md):
 *
 *  - the glyph operators of the edited line are taken out of the page content (no cover rectangle, no
 *    second copy underneath), every other line keeps its original operators untouched;
 *  - the new text is written with the line's own font, size, colour, horizontal scaling and exact
 *    baseline/rotation (the first glyph's text rendering matrix);
 *  - only if the source font cannot draw a typed character (subset fonts keep only the glyphs they used)
 *    does the line fall back to Helvetica, keeping size, colour and position.
 *
 * Conservative like PdfCraft: text drawn inside nested forms, or operators that also draw text outside
 * the edited line, are refused rather than half-edited.
 */
object TextEditor {
    /** [missing] = typed characters the original font has no glyph for (drawn in a look-alike font). */
    class Result(val fontName: String, val missing: String, val fallbackName: String?, val wasScan: Boolean, val linesWritten: Int = 1)

    class NotEditable(msg: String) : Exception(msg)

    private val SHOW_TEXT = setOf("Tj", "TJ", "'", "\"")

    private class Glyph(val at: Offset, val trm: Matrix, val font: PDFont, val rgb: Int, val invisible: Boolean)

    /**
     * Replaces the text line whose glyphs lie inside [line] (displayed, normalized page coordinates)
     * on page [pageIndex] of [src] with [newText], writing the result to [out].
     */
    /**
     * [paper] / [ink]: colours sampled from the rendered page, used only for OCR text over a scan, where
     * the old words are pixels: they are covered with the paper colour and the new text is drawn in ink.
     */
    /**
     * [style]: null = keep the original font (missing letters fall back to a look-alike);
     * "sans" / "serif" / "mono" = the user chose a typeface for the whole run (bold/italic kept).
     */
    fun replaceLine(
        src: File, pageIndex: Int, line: Rect, newText: String, out: File,
        paper: Int? = null, ink: Int? = null, style: String? = null,
    ): Result = replaceBlock(src, pageIndex, listOf(line), newText, out, paper, ink, style)

    /**
     * Paragraph editing: the text of every line in [lines] (top to bottom) is taken out and [newText] is
     * written re-wrapped to the paragraph's width, with the first line's font, size, colour and the
     * paragraph's own line spacing. With one line it is [replaceLine] (no wrapping).
     */
    fun replaceBlock(
        src: File, pageIndex: Int, lines: List<Rect>, newText: String, out: File,
        paper: Int? = null, ink: Int? = null, style: String? = null,
    ): Result {
        require(lines.isNotEmpty())
        val line = lines.first()
        PdfOps.load(src).use { d ->
            val page = d.getPage(pageIndex)
            val crop = page.cropBox
            val rot = PageGeom.rotation(page)
            val dispW = if (rot == 90 || rot == 270) crop.height else crop.width
            val dispH = if (rot == 90 || rot == 270) crop.width else crop.height

            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens: MutableList<Any> = parser.tokens
            val opTokens = tokens.indices.filter { tokens[it] is Operator }

            // 1. every glyph: which top-level operator drew it, where, with what font / matrix / colour
            val glyphs = HashMap<Int, MutableList<Glyph>>()
            var depth = 0; var nested = 0; var current = -1; var count = 0
            val stripper = object : PDFTextStripper() {
                init {
                    // the text stripper ignores colour by default; we need the line's colour
                    addOperator(SetNonStrokingColorSpace()); addOperator(SetNonStrokingColor()); addOperator(SetNonStrokingColorN())
                    addOperator(SetNonStrokingDeviceRGBColor()); addOperator(SetNonStrokingDeviceGrayColor()); addOperator(SetNonStrokingDeviceCMYKColor())
                }

                override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
                    // T*, ' and " are run through nested Td/Tj calls: count only real tokens
                    if (depth == 0 && nested == 0) current = count++
                    nested++
                    try { super.processOperator(operator, operands) } finally { nested-- }
                }

                override fun showForm(form: PDFormXObject) {
                    val saved = nested; nested = 0; depth++
                    try { super.showForm(form) } finally { depth--; nested = saved }
                }

                override fun processTextPosition(text: TextPosition) {
                    val rgb = runCatching { graphicsState.nonStrokingColor.toRGB() }.getOrDefault(0)
                    val inv = runCatching {
                        graphicsState.textState.renderingMode == com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER
                    }.getOrDefault(false)
                    val g = Glyph(Offset(text.xDirAdj / dispW, text.yDirAdj / dispH), text.textMatrix.clone(), text.font, rgb, inv)
                    glyphs.getOrPut(if (depth == 0) current else -1 - current) { ArrayList() } += g
                    super.processTextPosition(text)
                }
            }
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.setSuppressDuplicateOverlappingText(false)
            stripper.sortByPosition = false
            stripper.getText(d)
            if (count != opTokens.size) throw NotEditable("This page's text is laid out in a way that can't be edited safely.")

            val areas = lines.map { l -> Rect(l.left - 0.002f, l.top - l.height * 0.25f, l.right + 0.002f, l.bottom + l.height * 0.25f) }
            fun inside(g: Glyph) = areas.any { it.contains(g.at) }
            val lineGlyphs = glyphs.filterKeys { it >= 0 }.values.flatten().filter(::inside)
            if (lineGlyphs.isEmpty()) {
                if (glyphs.filterKeys { it < 0 }.values.flatten().any(::inside))
                    throw NotEditable("This text is part of grouped artwork and can't be edited here.")
                throw NotEditable("No editable text found on that line.")
            }

            // 2. take the line's operators out (only those whose glyphs are all on this line)
            var removed = 0
            for ((op, list) in glyphs) {
                if (op < 0 || list.none(::inside)) continue
                val name = (tokens[opTokens[op]] as Operator).name
                if (name !in SHOW_TEXT || !list.all(::inside)) {
                    throw NotEditable("This line shares its drawing with other text, so it can't be edited separately.")
                }
                val operandIdx = opTokens[op] - 1
                when (tokens.getOrNull(operandIdx)) {
                    is COSString -> tokens[operandIdx] = COSString(ByteArray(0))
                    is COSArray -> tokens[operandIdx] = COSArray()
                    else -> throw NotEditable("Unexpected text drawing on that line.")
                }
                removed++
            }
            val stream = PDStream(d)
            stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
            page.setContents(stream)

            // 3. write the new text where the old line started, in its own style
            val first = lineGlyphs.filter { areas[0].contains(it.at) }.minByOrNull { it.at.x } ?: lineGlyphs.minByOrNull { it.at.x }!!
            val trm = first.trm
            val size = hypot(trm.shearX.toDouble(), trm.scaleY.toDouble()).toFloat().coerceAtLeast(1f)
            val source = first.font
            val scan = lineGlyphs.all { it.invisible } // OCR layer: the visible words are pixels of a scan
            // Each character keeps the source font if it has the glyph (PdfCraft: "Keep source fonts");
            // only the missing ones go to the closest standard font, so the line changes as little as possible.
            // "file:<path>": a font the user uploaded, embedded as a subset; letters it lacks use the look-alike
            val uploaded: PDFont? = style?.takeIf { it.startsWith("file:") }
                ?.let { runCatching { com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(d, File(it.removePrefix("file:"))) }.getOrNull() }
            val fallback = lookAlike(source, style?.takeUnless { it.startsWith("file:") })
            val keepSource = style == null && !scan
            val segments = ArrayList<Pair<PDFont, String>>()
            val missing = StringBuilder()
            for (ch in newText) {
                val f = if (keepSource && canDraw(source, ch.toString())) source
                    else if (uploaded != null && canDraw(uploaded, ch.toString())) uploaded else fallback
                if (keepSource && f !== source && ch != ' ') missing.append(ch)
                val c = if (f === source || f === uploaded || ch.code in 32..255) ch else '?'
                if (segments.isNotEmpty() && segments.last().first === f) segments[segments.lastIndex] = f to segments.last().second + c
                else segments += f to c.toString()
            }
            if (scan) {
                // cover the scanned words with the paper colour
                // the OCR box is about the cap height; scanned letters reach further (ascenders, serifs,
                // descenders), so the patch extends well above and below the box
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    val p = paper ?: 0xFFFFFFFF.toInt()
                    cs.setNonStrokingColor(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f)
                    for (l in lines) {
                        val hh = l.height
                        val box = Rect(l.left - hh * 0.15f, l.top - hh * 0.5f, l.right + hh * 0.25f, l.bottom + hh * 0.55f)
                        val pts = listOf(box.topLeft, box.topRight, box.bottomRight, box.bottomLeft).map { o -> PageGeom.toPdf(page, o) }
                        cs.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { cs.lineTo(it.first, it.second) }
                        cs.closePath()
                    }
                    cs.fill()
                }
            }
            // several lines: wrap the words to the paragraph's width, at its line spacing
            var written = 1
            if (lines.size > 1 && segments.isNotEmpty()) {
                val s = hypot((trm.scaleX / size).toDouble(), (trm.shearY / size).toDouble()).toFloat().coerceAtLeast(0.01f)
                val widthTs = (lines.maxOf { it.right } - lines.minOf { it.left }) * dispW / s
                val leadingTs = lines.zipWithNext { a, b -> b.bottom - a.bottom }.average().toFloat() * dispH / s
                fun charFont(ch: Char): PDFont = segments.firstOrNull { (f, t) -> ch in t }?.first ?: fallback
                fun w(t: String): Float = t.sumOf { ch ->
                    (runCatching { charFont(ch).getStringWidth(ch.toString()) }.getOrDefault(500f) / 1000f * size).toDouble()
                }.toFloat()
                val words = segments.joinToString("") { it.second }.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val wrapped = ArrayList<String>(); var cur = ""
                for (word in words) {
                    val next = if (cur.isEmpty()) word else "$cur $word"
                    if (cur.isNotEmpty() && w(next) > widthTs) { wrapped += cur; cur = word } else cur = next
                }
                if (cur.isNotEmpty()) wrapped += cur
                written = wrapped.size
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    val c = if (scan) (ink ?: 0xFF000000.toInt()) else first.rgb
                    cs.setNonStrokingColor(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
                    cs.setTextMatrix(Matrix(
                        trm.scaleX / size, trm.shearY / size, trm.shearX / size, trm.scaleY / size,
                        trm.translateX + crop.lowerLeftX, trm.translateY + crop.lowerLeftY,
                    ))
                    // a justified paragraph (all lines but the last end at the same right edge) stays justified:
                    // the spaces of each line but the last are widened to fill the width (TJ adjustments)
                    val maxR = lines.maxOf { it.right }; val minL = lines.minOf { it.left }
                    val justified = lines.size >= 3 && lines.dropLast(1).all { kotlin.math.abs(it.right - maxR) <= (maxR - minL) * 0.012f }
                    wrapped.forEachIndexed { i, l ->
                        if (i > 0) cs.newLineAtOffset(0f, -leadingTs)
                        val spaces = l.count { it == ' ' }
                        val extra = if (justified && i < wrapped.lastIndex && spaces > 0) ((widthTs - w(l)) / spaces).coerceIn(0f, size * 1.5f) else 0f
                        // the line, cut into runs per font again
                        var runFont: PDFont? = null
                        val items = ArrayList<Any>(); val sb = StringBuilder()
                        fun flushStr() { if (sb.isNotEmpty()) { items += sb.toString(); sb.clear() } }
                        fun flush() {
                            val f = runFont; flushStr()
                            if (f != null && items.isNotEmpty()) {
                                cs.setFont(f, size)
                                if (extra > 0f) cs.showTextWithPositioning(items.toTypedArray()) else items.forEach { cs.showText(it as String) }
                            }
                            items.clear()
                        }
                        for (ch in l) {
                            val f = charFont(ch)
                            if (f !== runFont) { flush(); runFont = f }
                            sb.append(ch)
                            if (ch == ' ' && extra > 0f) { flushStr(); items += -extra * 1000f / size }
                        }
                        flush()
                    }
                    cs.endText()
                }
                segments.clear()
            }
            if (segments.isNotEmpty()) {
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    val c = if (scan) (ink ?: 0xFF000000.toInt()) else first.rgb
                    cs.setNonStrokingColor(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
                    // Trm = [size 0 0 size 0 0] x Tm with an identity CTM: Tm = Trm with its size factored out,
                    // translated back from crop-box space (where the stripper reports it) to page space
                    cs.setTextMatrix(Matrix(
                        trm.scaleX / size, trm.shearY / size, trm.shearX / size, trm.scaleY / size,
                        trm.translateX + crop.lowerLeftX, trm.translateY + crop.lowerLeftY,
                    ))
                    for ((f, s) in segments) { cs.setFont(f, size); cs.showText(s) }
                    cs.endText()
                }
            }
            d.save(out)
            return Result(displayFontName(source.name), missing.toString(), if (missing.isNotEmpty() || !keepSource) (uploaded?.let { displayFontName(it.name) } ?: fallback.name) else null, scan, written)
        }
    }

    /**
     * The standard PDF font that looks most like [f]: serif / sans / monospace, bold, italic — from the
     * font's descriptor flags and its name.
     */
    private fun lookAlike(f: PDFont, family: String? = null): PDType1Font {
        val name = (f.name ?: "").lowercase()
        val d = runCatching { f.fontDescriptor }.getOrNull()
        val mono = if (family != null) family == "mono" else d?.isFixedPitch == true || listOf("courier", "mono", "consol").any { it in name }
        val serif = if (family != null) family == "serif"
            else !mono && (d?.isSerif == true || listOf("times", "serif", "roman", "georgia", "garamond", "cambria", "book").any { it in name } && "sans" !in name)
        val bold = d?.isForceBold == true || (d?.fontWeight ?: 0f) >= 600f || listOf("bold", "black", "heavy", "semibold", "demi").any { it in name }
        val italic = d?.isItalic == true || listOf("italic", "oblique").any { it in name }
        return when {
            mono -> if (bold && italic) PDType1Font.COURIER_BOLD_OBLIQUE else if (bold) PDType1Font.COURIER_BOLD else if (italic) PDType1Font.COURIER_OBLIQUE else PDType1Font.COURIER
            serif -> if (bold && italic) PDType1Font.TIMES_BOLD_ITALIC else if (bold) PDType1Font.TIMES_BOLD else if (italic) PDType1Font.TIMES_ITALIC else PDType1Font.TIMES_ROMAN
            else -> if (bold && italic) PDType1Font.HELVETICA_BOLD_OBLIQUE else if (bold) PDType1Font.HELVETICA_BOLD else if (italic) PDType1Font.HELVETICA_OBLIQUE else PDType1Font.HELVETICA
        }
    }

    /** True if [font] can encode every character and actually contains each glyph (subsets often don't). */
    internal fun canDraw(font: PDFont, s: String): Boolean = try {
        val bytes = font.encode(s)
        val input = ByteArrayInputStream(bytes)
        var ok = true
        while (input.available() > 0 && ok) {
            val code = font.readCode(input)
            ok = (font as? PDVectorFont)?.hasGlyph(code) ?: true
        }
        ok
    } catch (e: Exception) {
        false
    }
}
