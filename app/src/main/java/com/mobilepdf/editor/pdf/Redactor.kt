package com.mobilepdf.editor.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Real redaction, after PdfCraft's `pdfcraft-redact` (MIT/Apache-2.0): mark, then apply. Applying removes
 * everything under the marks from the file, for good, not just from view:
 *  - text glyphs under a mark leave the content stream; the rest of each line keeps its position (the
 *    removed glyphs' advance is kept as spacing), including invisible OCR text;
 *  - drawings wholly under a mark are deleted; images under a mark are deleted, and images partly under
 *    one lose the covered pixels (re-encoded as a new image, so other pages using it keep theirs);
 *  - content grouped into form XObjects that a mark touches is unpacked into the page first;
 *  - comments, links and form fields overlapping a mark are removed;
 *  - the marks become filled boxes drawn into the page;
 *  - document metadata and tagged-PDF alternate texts that repeat the removed words are removed.
 * Then every redacted page is read again (PdfCraft's verification pass). A page that can't be handled
 * exactly, or where anything is still found under a mark, is replaced by a picture of itself with the
 * marked areas painted over (its text is no longer selectable; OCR can restore that). If even that fails
 * the whole operation fails and the document is left unchanged (fail-closed).
 */
object Redactor {

    class Report {
        var glyphs = 0; var images = 0; var drawings = 0; var annots = 0; var metadata = 0; var altTexts = 0
        val flattened = ArrayList<Int>()
        var bookmarksMention = 0
        internal val words = LinkedHashSet<String>()
        val nothing get() = glyphs + images + drawings + annots == 0 && flattened.isEmpty()
    }

    class Failed(msg: String) : Exception(msg)

    /** Axis-aligned box in PDF user space. */
    private data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val area get() = max(0f, x1 - x0) * max(0f, y1 - y0)
        fun inflate(d: Float) = Box(x0 - d, y0 - d, x1 + d, y1 + d)
        fun contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1
        fun contains(o: Box) = o.x0 >= x0 && o.x1 <= x1 && o.y0 >= y0 && o.y1 <= y1
        fun intersects(o: Box) = o.x0 < x1 && o.x1 > x0 && o.y0 < y1 && o.y1 > y0
        fun overlap(o: Box) = Box(max(x0, o.x0), max(y0, o.y0), min(x1, o.x1), min(y1, o.y1)).area
        companion object {
            fun of(pts: List<PointF>) = Box(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
    }

    private fun toUser(page: PDPage, r: Rect): Box {
        val a = PageGeom.toPdf(page, Offset(r.left, r.top)); val b = PageGeom.toPdf(page, Offset(r.right, r.bottom))
        return Box(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
    }

    /**
     * Applies [marks] (per page, displayed normalized rectangles) to [src] and writes [out].
     * [fill] is the colour of the boxes; [rasterWidth] the pixel width of a page that has to be flattened.
     */
    fun apply(src: File, marks: Map<Int, List<Rect>>, out: File, fill: Int = 0xFF000000.toInt(), rasterWidth: Int = 2000): Report {
        val marked = marks.filterValues { it.isNotEmpty() }
        if (marked.isEmpty()) throw Failed("There are no redaction marks to apply.")
        val report = Report()
        val tmp = File(out.parentFile, out.name + ".part")
        val needFlatten = LinkedHashSet<Int>()
        PdfOps.load(src).use { d ->
            for ((pi, rects) in marked) {
                if (pi >= d.numberOfPages) continue
                val page = d.getPage(pi)
                val boxes = rects.map { toUser(page, it) }
                val ok = try {
                    ownResources(page)
                    inlineForms(d, page, boxes)
                    redactContent(d, page, boxes, report, fill)
                } catch (e: OutOfMemoryError) {
                    false
                } catch (e: Exception) {
                    android.util.Log.w("Redactor", "page ${pi + 1}: exact redaction failed, flattening", e)
                    false
                }
                if (!ok) needFlatten += pi
                removeAnnotations(d, page, boxes, report, removeAll = !ok)
                if (ok) drawBoxes(d, page, boxes, fill)
            }
            for (pi in needFlatten) flatten(d, src, pi, marked.getValue(pi), fill, rasterWidth)
            scrubMetadata(d, report)
            d.save(tmp)
        }
        report.flattened += needFlatten
        // verification: nothing may be left under a mark (PdfCraft fails closed)
        val residue = verify(tmp, marked)
        if (residue.isNotEmpty()) {
            PdfOps.load(tmp).use { d ->
                for (pi in residue) {
                    removeAnnotations(d, d.getPage(pi), marked.getValue(pi).map { toUser(d.getPage(pi), it) }, report, removeAll = true)
                    flatten(d, src, pi, marked.getValue(pi), fill, rasterWidth)
                }
                d.save(out)
            }
            tmp.delete()
            report.flattened += residue.filter { it !in report.flattened }
            if (verify(out, marked).isNotEmpty()) { out.delete(); throw Failed("The redaction could not be verified, so nothing was changed.") }
        } else {
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        }
        return report
    }

    // ------------------------------------------------------------------------------------------ glyphs

    private class Glyph(
        val op: Int, val depth: Int, val code: Int, val font: PDFont, val box: Box,
        val w0: Float, val tc: Float, val tw: Float, val tfs: Float, val unicode: String?,
    ) {
        var hit = false
    }

    /** Every glyph drawn on a page, with the top-level content-stream operator that drew it. */
    private class GlyphScan : PDFTextStripper() {
        var depth = 0; var nested = 0; var current = -1; var count = 0
        val glyphs = ArrayList<Glyph>()

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            // T*, ' and " are run through nested operators: only real tokens are counted
            if (depth == 0 && nested == 0) current = count++
            nested++
            try { super.processOperator(operator, operands) } finally { nested-- }
        }

        override fun showForm(form: PDFormXObject) {
            val saved = nested; nested = 0; depth++
            try { super.showForm(form) } finally { depth--; nested = saved }
        }

        override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, unicode: String?, displacement: Vector) {
            val ts = graphicsState.textState
            val fd = runCatching { font.fontDescriptor }.getOrNull()
            val asc = ((fd?.ascent ?: 800f) / 1000f).coerceIn(0.5f, 1.2f)
            val desc = ((fd?.descent ?: -200f) / 1000f).coerceIn(-0.5f, 0f)
            val w = displacement.x
            val m = textRenderingMatrix
            val pts = listOf(m.transformPoint(0f, desc), m.transformPoint(w, desc), m.transformPoint(w, asc), m.transformPoint(0f, asc))
            glyphs += Glyph(current, depth, code, font, Box.of(pts), w, ts.characterSpacing, ts.wordSpacing, ts.fontSize, unicode)
            super.showGlyph(textRenderingMatrix, font, code, unicode, displacement)
        }

        fun run(d: PDDocument, pageIndex: Int) {
            startPage = pageIndex + 1; endPage = pageIndex + 1
            setSuppressDuplicateOverlappingText(false)
            sortByPosition = false
            getText(d)
        }
    }

    /** A glyph counts as under a mark if its centre is, or a fifth of it is covered. */
    private fun under(g: Box, marks: List<Box>) = marks.any { m ->
        m.contains((g.x0 + g.x1) / 2, (g.y0 + g.y1) / 2) || (g.area > 0f && m.overlap(g) >= g.area * 0.2f)
    }

    // ------------------------------------------------------------------------------- content stream

    private val CONSTRUCT = setOf("m", "l", "c", "v", "y", "re", "h")
    private val PAINT = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n")
    private val SHOW = setOf("Tj", "TJ", "'", "\"")

    private class Gs(var ctm: Matrix)

    /** Exact redaction of one page's content. False = can't be done exactly here: flatten the page. */
    private fun redactContent(d: PDDocument, page: PDPage, marks: List<Box>, report: Report, fill: Int): Boolean {
        val pageIndex = d.pages.indexOf(page)
        val scan = GlyphScan().apply { run(d, pageIndex) }
        val parser = PDFStreamParser(page); parser.parse()
        val tokens: List<Any> = parser.tokens
        if (scan.count != tokens.count { it is Operator }) return false
        for (g in scan.glyphs) g.hit = under(g.box, marks)
        if (scan.glyphs.any { it.hit && it.depth > 0 }) return false // still grouped after unpacking
        val byOp = scan.glyphs.filter { it.depth == 0 }.groupBy { it.op }

        val res = page.resources ?: PDResources().also { page.resources = it }
        val out = ArrayList<Any>(tokens.size)
        val operands = ArrayList<Any>()
        val stack = ArrayList<Gs>(); var gs = Gs(Matrix())
        var path = ArrayList<PointF>(); var pathStart = -1; var clip = false
        var opIndex = -1
        fun pt(x: Float, y: Float) = gs.ctm.transformPoint(x, y)
        fun num(i: Int) = (operands.getOrNull(i) as? COSNumber)?.floatValue() ?: 0f

        for (t in tokens) {
            if (t !is Operator) { operands += t; continue }
            opIndex++
            val name = t.name
            var emit = true
            when (name) {
                "q" -> stack += Gs(gs.ctm.clone())
                "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.lastIndex)
                "cm" -> if (operands.size == 6) gs.ctm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)).multiply(gs.ctm)
            }
            if (name in CONSTRUCT) {
                if (pathStart < 0) pathStart = out.size
                when (name) {
                    "m", "l" -> path += pt(num(0), num(1))
                    "c" -> { path += pt(num(0), num(1)); path += pt(num(2), num(3)); path += pt(num(4), num(5)) }
                    "v", "y" -> { path += pt(num(0), num(1)); path += pt(num(2), num(3)) }
                    "re" -> {
                        val x = num(0); val y = num(1); val w = num(2); val h = num(3)
                        path += pt(x, y); path += pt(x + w, y); path += pt(x + w, y + h); path += pt(x, y + h)
                    }
                }
            } else if (name == "W" || name == "W*") {
                clip = true
            } else if (name in PAINT) {
                if (name != "n" && !clip && path.isNotEmpty() && pathStart >= 0) {
                    val b = Box.of(path)
                    if (marks.any { it.inflate(1f).contains(b) }) {
                        while (out.size > pathStart) out.removeAt(out.lastIndex)
                        report.drawings++
                        emit = false
                    }
                }
                path = ArrayList(); pathStart = -1; clip = false
            } else if (name in SHOW) {
                val gl = byOp[opIndex].orEmpty()
                if (gl.any { it.hit }) {
                    val replaced = rewriteText(name, operands, gl, report) ?: return false
                    out.addAll(replaced)
                    operands.clear()
                    continue
                }
            } else if (name == "Do") {
                val xName = operands.lastOrNull() as? COSName
                val x = xName?.let { runCatching { res.getXObject(it) }.getOrNull() }
                if (x is PDImageXObject) {
                    val quad = listOf(pt(0f, 0f), pt(1f, 0f), pt(1f, 1f), pt(0f, 1f))
                    val b = Box.of(quad)
                    val touching = marks.filter { it.intersects(b) && it.overlap(b) > 0.25f }
                    if (touching.isNotEmpty()) {
                        report.images++
                        if (marks.any { it.inflate(1f).contains(b) } || x.isStencil) {
                            emit = false // wholly covered (or a stencil mask): the image goes
                        } else {
                            val newName = paintOver(d, res, x, gs.ctm, touching, fill) ?: return false
                            out.addAll(operands.dropLast(1)); out.add(newName); out.add(t)
                            operands.clear()
                            continue
                        }
                    }
                } else if (x is PDFormXObject) {
                    val b = formBox(x, gs.ctm)
                    if (marks.any { it.intersects(b) } && scan.glyphs.any { it.op == opIndex && it.hit }) return false
                    if (marks.any { it.intersects(b) } && hasPaint(x)) return false // could not be unpacked
                }
            } else if (name == "BI") {
                val b = Box.of(listOf(pt(0f, 0f), pt(1f, 0f), pt(1f, 1f), pt(0f, 1f)))
                if (marks.any { it.intersects(b) && it.overlap(b) > 0.25f }) { report.images++; emit = false }
            }
            if (emit) { out.addAll(operands); out.add(t) }
            operands.clear()
        }
        out.addAll(operands)

        val stream = PDStream(d)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
        page.setContents(stream)
        // images and groups this page no longer draws must not stay attached to it: a replaced image or an
        // unpacked form still holds the original content (other pages using them keep their own references)
        val used = HashSet<COSName>()
        var prev: Any? = null
        for (t in out) { if (t is Operator && t.name == "Do") (prev as? COSName)?.let(used::add); prev = t }
        (res.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.let { xd ->
            for (k in xd.keySet().toList()) if (k !in used) xd.removeItem(k)
        }
        return true
    }

    /** Gives [page] its own resource dictionary (and XObject list), so changes never touch other pages. */
    private fun ownResources(page: PDPage) {
        val res = page.resources ?: return
        val copy = COSDictionary()
        for (k in res.cosObject.keySet()) copy.setItem(k, res.cosObject.getItem(k))
        for (kind in RES_KINDS) {
            val sub = res.cosObject.getDictionaryObject(kind) as? COSDictionary ?: continue
            val c = COSDictionary(); for (k in sub.keySet()) c.setItem(k, sub.getItem(k))
            copy.setItem(kind, c)
        }
        // through the setter: PDPage caches its PDResources, and a stale cache would edit the shared original
        page.resources = PDResources(copy)
    }

    /** True if the form draws anything at all (an empty form under a mark is harmless). */
    private fun hasPaint(f: PDFormXObject): Boolean = runCatching {
        val p = PDFStreamParser(f); p.parse(); p.tokens.any { it is Operator && (it.name in PAINT || it.name in SHOW || it.name == "Do" || it.name == "BI" || it.name == "sh") }
    }.getOrDefault(true)

    private fun formBox(f: PDFormXObject, ctm: Matrix): Box {
        val bb = f.bBox ?: PDRectangle(0f, 0f, 1e5f, 1e5f)
        val m = f.matrix.multiply(ctm)
        return Box.of(listOf(
            m.transformPoint(bb.lowerLeftX, bb.lowerLeftY), m.transformPoint(bb.upperRightX, bb.lowerLeftY),
            m.transformPoint(bb.upperRightX, bb.upperRightY), m.transformPoint(bb.lowerLeftX, bb.upperRightY),
        ))
    }

    /**
     * The text operator with the glyphs under the marks taken out. Each removed glyph becomes a TJ
     * adjustment of exactly its advance, so everything after it stays where it was (PdfCraft: "the rest of
     * each line keeps its position"). Null = the operator could not be matched glyph for glyph.
     */
    private fun rewriteText(name: String, operands: List<Any>, gl: List<Glyph>, report: Report): List<Any>? {
        val font = gl.first().font
        val elements: List<COSBase> = when (name) {
            "Tj", "'" -> listOf(operands.lastOrNull() as? COSString ?: return null)
            "\"" -> listOf(operands.getOrNull(2) as? COSString ?: return null)
            else -> (operands.lastOrNull() as? COSArray ?: return null).toList()
        }
        val arr = COSArray()
        val removed = StringBuilder()
        if (font is PDType0Font && font.isVertical) {
            // vertical writing: no horizontal advance to keep; the whole operator's text goes
            gl.forEach { removed.append(it.unicode ?: "") }
            report.glyphs += gl.size
        } else {
            var cur = ByteArrayOutputStream()
            var gi = 0
            fun flush() { if (cur.size() > 0) { arr.add(COSString(cur.toByteArray())); cur = ByteArrayOutputStream() } }
            fun adjust(n: Float) {
                if (n == 0f) return
                flush()
                val last = if (arr.size() > 0) arr.get(arr.size() - 1) else null
                if (last is COSNumber) arr.set(arr.size() - 1, COSFloat(last.floatValue() + n)) else arr.add(COSFloat(n))
            }
            for (el in elements) {
                when (el) {
                    is COSNumber -> adjust(el.floatValue())
                    is COSString -> {
                        val bytes = el.bytes
                        val input = ByteArrayInputStream(bytes)
                        var pos = 0
                        while (input.available() > 0) {
                            val code = font.readCode(input)
                            val end = bytes.size - input.available()
                            val g = gl.getOrNull(gi++) ?: return null
                            if (g.code != code) return null
                            if (!g.hit) cur.write(bytes, pos, end - pos)
                            else {
                                if (g.tfs == 0f) return null
                                val tw = if (end - pos == 1 && code == 32) g.tw else 0f
                                adjust(-(g.w0 * 1000f + (g.tc + tw) * 1000f / g.tfs))
                                removed.append(g.unicode ?: "")
                                report.glyphs++
                            }
                            pos = end
                        }
                    }
                    else -> Unit
                }
            }
            flush()
            if (gi != gl.size) return null
        }
        removed.toString().split(Regex("\\s+")).filter { it.length >= 3 }.forEach { report.words += it }
        val tj = Operator.getOperator("TJ")
        return when (name) {
            "'" -> listOf(Operator.getOperator("T*"), arr, tj)
            "\"" -> listOf(operands[0], Operator.getOperator("Tw"), operands[1], Operator.getOperator("Tc"), Operator.getOperator("T*"), arr, tj)
            else -> listOf(arr, tj)
        }
    }

    // ---------------------------------------------------------------------------------------- images

    /** Paints the covered part of [img] in [fill]; returns the name of the new image in [res]. */
    private fun paintOver(d: PDDocument, res: PDResources, img: PDImageXObject, ctm: Matrix, marks: List<Box>, fill: Int): COSName? {
        val src = img.image ?: return null
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        // user space -> image unit square: inverse of the CTM
        val a = ctm.scaleX; val b = ctm.shearY; val c = ctm.shearX; val dd = ctm.scaleY; val e = ctm.translateX; val f = ctm.translateY
        val det = a * dd - b * c
        if (det == 0f) return null
        fun unit(x: Float, y: Float): PointF {
            val px = x - e; val py = y - f
            return PointF((dd * px - c * py) / det, (-b * px + a * py) / det)
        }
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = fill or 0xFF000000.toInt(); style = Paint.Style.FILL; isAntiAlias = false }
        for (m in marks) {
            val corners = listOf(unit(m.x0, m.y0), unit(m.x1, m.y0), unit(m.x1, m.y1), unit(m.x0, m.y1))
            val p = Path()
            corners.forEachIndexed { i, u ->
                // image rows run top to bottom; the unit square's y runs bottom to top
                val x = u.x * w; val y = (1f - u.y) * h
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            canvas.drawPath(p, paint)
            // one pixel more all round, so nothing survives at the edges
            canvas.drawPath(p, Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = 2f })
        }
        val jpeg = img.suffix == "jpg" && !bmp.hasAlpha()
        val fresh = if (jpeg) JPEGFactory.createFromImage(d, bmp, 0.92f) else LosslessFactory.createFromImage(d, bmp)
        bmp.recycle()
        return res.add(fresh)
    }

    // ------------------------------------------------------------------------------------ form unpacking

    private val RES_KINDS = listOf("Font", "XObject", "ExtGState", "ColorSpace", "Pattern", "Shading", "Properties").map { COSName.getPDFName(it) }

    /**
     * Form XObjects that a mark touches are unpacked into the page (q, its matrix, its bounding-box clip,
     * its content, Q), with their resources merged into the page's (renamed on a clash). Repeated for
     * nested forms. Pages sharing the form keep theirs: only this page's content changes.
     */
    private fun inlineForms(d: PDDocument, page: PDPage, marks: List<Box>) {
        repeat(4) {
            val res = page.resources ?: return
            val parser = PDFStreamParser(page); parser.parse()
            val tokens = parser.tokens
            val out = ArrayList<Any>(tokens.size)
            val operands = ArrayList<Any>()
            val stack = ArrayList<Matrix>(); var ctm = Matrix()
            var changed = false
            fun num(i: Int) = (operands.getOrNull(i) as? COSNumber)?.floatValue() ?: 0f
            for (t in tokens) {
                if (t !is Operator) { operands += t; continue }
                when (t.name) {
                    "q" -> stack += ctm.clone()
                    "Q" -> if (stack.isNotEmpty()) ctm = stack.removeAt(stack.lastIndex)
                    "cm" -> if (operands.size == 6) ctm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)).multiply(ctm)
                }
                if (t.name == "Do") {
                    val x = (operands.lastOrNull() as? COSName)?.let { runCatching { res.getXObject(it) }.getOrNull() }
                    if (x is PDFormXObject && marks.any { it.intersects(formBox(x, ctm)) }) {
                        val body = runCatching { PDFStreamParser(x).also { it.parse() }.tokens }.getOrNull()
                        if (body != null) {
                            val renames = mergeResources(res, x.resources)
                            val m = x.matrix
                            out.add(Operator.getOperator("q"))
                            out.addAll(listOf(m.scaleX, m.shearY, m.shearX, m.scaleY, m.translateX, m.translateY).map { COSFloat(it) })
                            out.add(Operator.getOperator("cm"))
                            x.bBox?.let { bb ->
                                out.addAll(listOf(bb.lowerLeftX, bb.lowerLeftY, bb.width, bb.height).map { COSFloat(it) })
                                out.add(Operator.getOperator("re")); out.add(Operator.getOperator("W")); out.add(Operator.getOperator("n"))
                            }
                            out.addAll(renamed(body, renames))
                            out.add(Operator.getOperator("Q"))
                            operands.clear()
                            changed = true
                            continue
                        }
                    }
                }
                out.addAll(operands); out.add(t)
                operands.clear()
            }
            out.addAll(operands)
            if (!changed) return
            val stream = PDStream(d)
            stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
            page.setContents(stream)
        }
    }

    /** Adds [from]'s resources to [to]; returns, per kind, the names that had to change. */
    private fun mergeResources(to: PDResources, from: PDResources?): Map<COSName, Map<COSName, COSName>> {
        val renames = HashMap<COSName, Map<COSName, COSName>>()
        val src = from?.cosObject ?: return renames
        val dst = to.cosObject
        if (src === dst) return renames
        for (kind in RES_KINDS) {
            val fd = src.getDictionaryObject(kind) as? COSDictionary ?: continue
            val pd = dst.getDictionaryObject(kind) as? COSDictionary ?: COSDictionary().also { dst.setItem(kind, it) }
            val map = HashMap<COSName, COSName>()
            for (key in fd.keySet()) {
                val obj = fd.getItem(key)
                val existing = pd.getItem(key)
                if (existing == null || existing === obj) { pd.setItem(key, obj); continue }
                var n = 1
                var nk: COSName
                do { nk = COSName.getPDFName("${key.name}_r${n++}") } while (pd.containsKey(nk))
                pd.setItem(nk, obj); map[key] = nk
            }
            if (map.isNotEmpty()) renames[kind] = map
        }
        return renames
    }

    /** [body] with resource names changed per [renames] (font in Tf, XObject in Do, ...). */
    private fun renamed(body: List<Any>, renames: Map<COSName, Map<COSName, COSName>>): List<Any> {
        if (renames.isEmpty()) return body
        fun kind(k: String) = renames[COSName.getPDFName(k)].orEmpty()
        val out = ArrayList<Any>(body.size)
        val operands = ArrayList<Any>()
        for (t in body) {
            if (t !is Operator) { operands += t; continue }
            fun swap(i: Int, k: String) {
                val n = operands.getOrNull(i) as? COSName ?: return
                kind(k)[n]?.let { operands[i] = it }
            }
            when (t.name) {
                "Tf" -> swap(0, "Font")
                "Do" -> swap(operands.lastIndex, "XObject")
                "gs" -> swap(0, "ExtGState")
                "cs", "CS" -> swap(0, "ColorSpace")
                "scn", "SCN" -> swap(operands.lastIndex, "Pattern")
                "sh" -> swap(0, "Shading")
                "BDC", "DP" -> swap(1, "Properties")
            }
            out.addAll(operands); out.add(t)
            operands.clear()
        }
        out.addAll(operands)
        return out
    }

    // ------------------------------------------------------------------------------ annotations, boxes

    private fun removeAnnotations(d: PDDocument, page: PDPage, marks: List<Box>, report: Report, removeAll: Boolean) {
        val annots = runCatching { page.annotations }.getOrNull() ?: return
        val keep = ArrayList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>()
        val gone = HashSet<COSDictionary>()
        for (a in annots) {
            val r = a.rectangle
            val box = r?.let { Box(min(it.lowerLeftX, it.upperRightX), min(it.lowerLeftY, it.upperRightY), max(it.lowerLeftX, it.upperRightX), max(it.lowerLeftY, it.upperRightY)) }
            if (removeAll || box == null || marks.any { it.intersects(box) }) { gone += a.cosObject; report.annots++ } else keep += a
        }
        if (gone.isEmpty()) return
        // pop-ups of removed comments go too
        keep.removeAll { a -> (a.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary)?.let { it in gone } == true }
        page.annotations = keep
        // form fields whose widgets were removed
        val form = d.documentCatalog.acroForm ?: return
        val fields = form.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray ?: return
        pruneFields(fields, gone)
    }

    private fun pruneFields(arr: COSArray, gone: Set<COSDictionary>) {
        var i = 0
        while (i < arr.size()) {
            val f = arr.getObject(i) as? COSDictionary
            if (f == null) { i++; continue }
            if (f in gone) { arr.remove(i); continue }
            val kids = f.getDictionaryObject(COSName.KIDS) as? COSArray
            if (kids != null) {
                pruneFields(kids, gone)
                if (kids.size() == 0) { arr.remove(i); continue }
            }
            i++
        }
    }

    private fun drawBoxes(d: PDDocument, page: PDPage, marks: List<Box>, fill: Int) {
        PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.setNonStrokingColor(((fill shr 16) and 255) / 255f, ((fill shr 8) and 255) / 255f, (fill and 255) / 255f)
            for (m in marks) cs.addRect(m.x0, m.y0, m.x1 - m.x0, m.y1 - m.y0)
            cs.fill()
        }
    }

    // ----------------------------------------------------------------------------------- the fallback

    /**
     * Replaces page [pi] with a picture of itself (rendered from [src]) with the marked areas painted over:
     * nothing of the original content stays in the file for that page.
     */
    private fun flatten(d: PDDocument, src: File, pi: Int, rects: List<Rect>, fill: Int, width: Int) {
        val page = d.getPage(pi)
        val rot = PageGeom.rotation(page)
        val crop = page.cropBox
        val dispW = if (rot == 90 || rot == 270) crop.height else crop.width
        val dispH = if (rot == 90 || rot == 270) crop.width else crop.height
        val wPx = width.coerceIn(600, 3000)
        val hPx = (wPx * dispH / dispW).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> r.openPage(pi).use { p -> p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } }
        }
        val c = Canvas(bmp)
        val paint = Paint().apply { color = fill or 0xFF000000.toInt() }
        for (r in rects) c.drawRect(r.left * wPx - 1, r.top * hPx - 1, r.right * wPx + 1, r.bottom * hPx + 1, paint)
        val img = JPEGFactory.createFromImage(d, bmp, 0.9f)
        bmp.recycle()
        val box = PDRectangle(dispW, dispH)
        page.rotation = 0
        page.mediaBox = box; page.cropBox = box
        page.cosObject.removeItem(COSName.TRIM_BOX); page.cosObject.removeItem(COSName.BLEED_BOX); page.cosObject.removeItem(COSName.ART_BOX)
        page.resources = PDResources()
        page.annotations = emptyList()
        PDPageContentStream(d, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
            cs.drawImage(img, 0f, 0f, dispW, dispH)
        }
    }

    // ------------------------------------------------------------------------------ metadata, checks

    private fun mentions(s: String?, words: Set<String>) = s != null && words.any { s.contains(it, ignoreCase = true) }

    private fun scrubMetadata(d: PDDocument, report: Report) {
        val words = report.words
        if (words.isEmpty()) return
        val info = d.documentInformation?.cosObject
        if (info != null) for (k in info.keySet().toList()) {
            if (mentions(info.getString(k), words)) { info.removeItem(k); report.metadata++ }
        }
        val cat = d.documentCatalog
        runCatching {
            val xmp = cat.metadata?.createInputStream()?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (mentions(xmp, words)) { cat.cosObject.removeItem(COSName.METADATA); report.metadata++ }
        }
        // tagged PDF: alternate / actual / expansion texts that repeat what was removed
        val keys = listOf("Alt", "ActualText", "E").map { COSName.getPDFName(it) }
        val seen = HashSet<COSBase>()
        fun walk(o: COSBase?, depth: Int) {
            val dict = (o as? com.tom_roush.pdfbox.cos.COSObject)?.`object` ?: o
            if (depth > 64 || dict == null || !seen.add(dict)) return
            when (dict) {
                is COSDictionary -> {
                    for (k in keys) if (mentions(dict.getString(k), words)) { dict.removeItem(k); report.altTexts++ }
                    walk(dict.getItem(COSName.K), depth + 1)
                }
                is COSArray -> for (i in 0 until dict.size()) walk(dict.get(i), depth + 1)
                else -> Unit
            }
        }
        walk(cat.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT), 0)
        // bookmarks are left alone (PdfCraft: only counted)
        runCatching {
            var item = cat.documentOutline?.firstChild
            val stack = ArrayDeque<com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem>()
            while (item != null || stack.isNotEmpty()) {
                if (item == null) item = stack.removeLast().nextSibling
                if (item == null) continue
                if (mentions(item.title, words)) report.bookmarksMention++
                item.firstChild?.let { stack.addLast(item); item = it } ?: run { item = item?.nextSibling }
            }
        }
    }

    /** Pages where a glyph still sits under a mark. */
    private fun verify(file: File, marks: Map<Int, List<Rect>>): List<Int> = PdfOps.load(file).use { d ->
        marks.keys.filter { pi ->
            if (pi >= d.numberOfPages) return@filter false
            val page = d.getPage(pi)
            val boxes = marks.getValue(pi).map { toUser(page, it).inflate(-0.5f) }
            val scan = runCatching { GlyphScan().apply { run(d, pi) } }.getOrNull() ?: return@filter true
            scan.glyphs.any { g -> boxes.any { it.contains((g.box.x0 + g.box.x1) / 2, (g.box.y0 + g.box.y1) / 2) } }
        }
    }
}

/**
 * Find & redact patterns (PdfCraft `patterns.rs`): phone numbers, e-mail addresses, card numbers
 * (Luhn-checked), US social security numbers, dates and IBANs (mod-97 checked).
 */
enum class RedactPattern(val label: String) {
    EMAIL("E-mail addresses"), PHONE("Phone numbers"), CARD("Card numbers"), DATE("Dates"), IBAN("IBAN numbers"), SSN("Social security numbers");

    private val months = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"
    val regex: Regex
        get() = when (this) {
            EMAIL -> Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
            PHONE -> Regex("(?<![\\w+])(?:\\+\\d{1,3}[ .-]?)?(?:\\(\\d{1,4}\\)[ .-]?)?\\d{2,5}(?:[ .-]?\\d{2,5}){1,4}(?![\\w])")
            CARD -> Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
            DATE -> Regex(
                "(?i)(?<!\\d)(?:\\d{1,2}[./-]\\d{1,2}[./-](?:\\d{4}|\\d{2})|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}\\s+(?:$months)[a-z]*\\.?,?\\s+\\d{4}|(?:$months)[a-z]*\\.?\\s+\\d{1,2},?\\s+\\d{4})(?!\\d)",
            )
            IBAN -> Regex("(?<![A-Z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}(?![A-Z0-9])")
            SSN -> Regex("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")
        }

    /** Extra checks that cut false positives (PdfCraft does the same). */
    fun accept(s: String): Boolean {
        val digits = s.filter { it.isDigit() }
        return when (this) {
            PHONE -> digits.length in 7..15 && !Regex("^\\d{4}$").matches(s) && !DATE.regex.matches(s)
            CARD -> digits.length in 13..19 && luhn(digits)
            IBAN -> iban(s.replace(" ", ""))
            else -> true
        }
    }

    companion object {
        fun luhn(d: String): Boolean {
            var sum = 0; var alt = false
            for (i in d.indices.reversed()) {
                var n = d[i] - '0'
                if (alt) { n *= 2; if (n > 9) n -= 9 }
                sum += n; alt = !alt
            }
            return sum % 10 == 0
        }

        fun iban(s: String): Boolean {
            if (s.length !in 15..34) return false
            val r = s.substring(4) + s.substring(0, 4)
            var mod = 0
            for (ch in r) {
                val v = if (ch.isDigit()) ch - '0' else if (ch in 'A'..'Z') ch - 'A' + 10 else return false
                mod = if (v >= 10) (mod * 100 + v) % 97 else (mod * 10 + v) % 97
            }
            return mod == 1
        }
    }
}
