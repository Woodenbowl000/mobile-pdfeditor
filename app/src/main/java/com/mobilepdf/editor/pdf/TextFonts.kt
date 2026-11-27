package com.mobilepdf.editor.pdf

import com.mobilepdf.editor.annot.FontChoice
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/** Fonts for added text: standard look-alikes, fonts copied from the page (eyedropper), uploaded .ttf files. */
object TextFonts {

    /** Family / bold / italic of a PDF font, from its name ("ABCDEF+Calibri-BoldItalic" -> sans, bold, italic). */
    fun guess(fontName: String): FontChoice {
        val n = fontName.lowercase()
        val mono = listOf("courier", "mono", "consol").any { it in n }
        val serif = !mono && "sans" !in n &&
            listOf("times", "serif", "roman", "georgia", "garamond", "cambria", "book", "minion", "palatino", "baskerville").any { it in n }
        val bold = listOf("bold", "black", "heavy", "semibold", "demi").any { it in n }
        val italic = listOf("italic", "oblique").any { it in n }
        return FontChoice(if (mono) "mono" else if (serif) "serif" else "sans", bold, italic)
    }

    /** The standard PDF font for [c]'s family and weight (always available, Latin letters only). */
    fun standard(c: FontChoice): PDType1Font = when (c.family) {
        "mono" -> if (c.bold && c.italic) PDType1Font.COURIER_BOLD_OBLIQUE else if (c.bold) PDType1Font.COURIER_BOLD else if (c.italic) PDType1Font.COURIER_OBLIQUE else PDType1Font.COURIER
        "serif" -> if (c.bold && c.italic) PDType1Font.TIMES_BOLD_ITALIC else if (c.bold) PDType1Font.TIMES_BOLD else if (c.italic) PDType1Font.TIMES_ITALIC else PDType1Font.TIMES_ROMAN
        else -> if (c.bold && c.italic) PDType1Font.HELVETICA_BOLD_OBLIQUE else if (c.bold) PDType1Font.HELVETICA_BOLD else if (c.italic) PDType1Font.HELVETICA_OBLIQUE else PDType1Font.HELVETICA
    }

    /**
     * The exact font [c] asks for, usable in [doc]: an uploaded font file (embedded as a subset), or the
     * PDF's own font that the eyedropper copied (looked up by name, on its page first). Null = standard.
     */
    fun resolve(doc: PDDocument, c: FontChoice): PDFont? {
        c.file?.let { f -> return runCatching { PDType0Font.load(doc, File(f)) }.getOrNull() }
        val want = c.pdfFont ?: return null
        val n = doc.numberOfPages
        val order = (listOf(c.fromPage) + (0 until n)).filter { it in 0 until n }.distinct().take(60)
        for (i in order) {
            val res = runCatching { doc.getPage(i).resources }.getOrNull() ?: continue
            for (name in res.fontNames) {
                val f = runCatching { res.getFont(name) }.getOrNull() ?: continue
                if (displayFontName(f.name) == want) return f
            }
        }
        return null
    }

    /**
     * [text] cut into pieces per font: each character in [primary] if it has that glyph (fonts copied from a
     * PDF are often subsets), otherwise in [fallback].
     */
    fun segments(text: String, primary: PDFont?, fallback: PDType1Font): List<Pair<PDFont, String>> {
        val out = ArrayList<Pair<PDFont, String>>()
        for (ch in text) {
            val f: PDFont = if (primary != null && TextEditor.canDraw(primary, ch.toString())) primary else fallback
            val c = if (f === primary || ch.code in 32..255) ch else '?'
            if (out.isNotEmpty() && out.last().first === f) out[out.lastIndex] = f to out.last().second + c
            else out += f to c.toString()
        }
        return out
    }

    /** Throws if PdfBox can't embed [file] (only TrueType outlines can be; CFF-based .otf can't). */
    fun validate(file: File) {
        PDDocument().use { d ->
            val f = PDType0Font.load(d, file)
            require(TextEditor.canDraw(f, "Aa")) { "This font has no Latin letters" }
        }
    }
}
