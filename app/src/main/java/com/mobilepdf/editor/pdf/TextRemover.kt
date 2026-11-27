package com.mobilepdf.editor.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Real removal of the page's own text for "Edit text": the text-showing operators (Tj, TJ, ', ")
 * whose glyphs all lie inside an edited line are emptied in the page content stream, so the old words
 * are gone from the file — not just painted over — and copy / search / screen readers see only the new text.
 *
 * Conservative on purpose: an operator that also draws text outside the edited area is left alone, as is
 * text inside nested forms. For those, the caller falls back to covering the old words.
 */
object TextRemover {
    private val SHOW_TEXT = setOf("Tj", "TJ", "'", "\"")

    /**
     * @param covers edited areas in displayed, normalized page coordinates
     * @return per cover: true if some of the original glyphs could not be removed (so cover them)
     */
    fun remove(doc: PDDocument, pageIndex: Int, covers: List<Rect>): BooleanArray {
        val keepCovering = BooleanArray(covers.size) { true }
        if (covers.isEmpty()) return keepCovering
        val page = doc.getPage(pageIndex)
        val crop = page.cropBox
        val rot = PageGeom.rotation(page)
        val dispW = if (rot == 90 || rot == 270) crop.height else crop.width
        val dispH = if (rot == 90 || rot == 270) crop.width else crop.height

        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens: MutableList<Any> = parser.tokens
        val opTokens = tokens.indices.filter { tokens[it] is Operator }

        // 1. where does each top-level operator put its glyphs? (same coordinates as PageText)
        val glyphs = HashMap<Int, MutableList<Offset>>()
        var depth = 0
        var current = -1
        var count = 0
        var nested = 0
        val seen = ArrayList<String>()
        val stripper = object : PDFTextStripper() {
            override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
                // PdfBox implements some operators by calling others (T*, ' and " run Td / Tj internally):
                // only the outermost call corresponds to a token in the content stream
                if (depth == 0 && nested == 0) { current = count++; seen += operator.name }
                nested++
                try { super.processOperator(operator, operands) } finally { nested-- }
            }

            override fun showForm(form: PDFormXObject) {
                // a form's own operators run nested inside its Do; reset the nesting count for them
                val saved = nested; nested = 0
                depth++
                try { super.showForm(form) } finally { depth--; nested = saved }
            }

            override fun processTextPosition(text: TextPosition) {
                if (depth == 0) glyphs.getOrPut(current) { ArrayList() } += Offset(text.xDirAdj / dispW, text.yDirAdj / dispH)
                super.processTextPosition(text)
            }
        }
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.setSuppressDuplicateOverlappingText(false)
        stripper.getText(doc)
        // operators seen while processing must line up 1:1 with the parsed tokens, or editing is unsafe
        android.util.Log.i("TextRemover", "page $pageIndex: ${opTokens.size} operators parsed, $count processed, ${glyphs.values.sumOf { it.size }} glyphs")
        if (count != opTokens.size) {
            val parsed = opTokens.map { (tokens[it] as Operator).name }
            val i = parsed.indices.firstOrNull { it >= seen.size || parsed[it] != seen[it] } ?: parsed.size
            android.util.Log.i("TextRemover", "diverge at $i parsed=${parsed.subList(maxOf(0, i - 4), minOf(parsed.size, i + 4))} seen=${seen.subList(maxOf(0, i - 4), minOf(seen.size, i + 4))}")
            return keepCovering
        }

        fun inside(p: Offset) = covers.any { it.inflate(0.002f).contains(p) }

        // 2. empty the operators whose glyphs are all inside the edited lines
        val removed = HashSet<Int>()
        for ((op, pts) in glyphs) {
            val name = (tokens[opTokens[op]] as Operator).name
            if (name !in SHOW_TEXT || pts.isEmpty() || !pts.all(::inside)) continue
            val operandIdx = opTokens[op] - 1 // the string (or TJ array) is always the last operand
            when (tokens.getOrNull(operandIdx)) {
                is COSString -> tokens[operandIdx] = COSString(ByteArray(0))
                is COSArray -> tokens[operandIdx] = COSArray()
                else -> continue
            }
            removed += op
        }
        android.util.Log.i("TextRemover", "page $pageIndex: removed ${removed.size} text operators for ${covers.size} edited lines")
        if (removed.isEmpty()) return keepCovering

        // 3. which edited lines still have visible original glyphs?
        for (i in covers.indices) {
            val c = covers[i].inflate(0.002f)
            keepCovering[i] = glyphs.any { (op, pts) -> op !in removed && pts.any { c.contains(it) } }
        }

        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        page.setContents(stream)
        return keepCovering
    }
}
