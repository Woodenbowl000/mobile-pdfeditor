package com.tom_roush.pdfbox.rendering

import android.graphics.Path
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector

/**
 * Works around a PdfBox-Android rendering bug: PageDrawer transforms the glyph outline it gets from the
 * font's cache *in place* (desktop PDFBox transforms a copy). The cached outline is then wrong for every
 * later use, so each letter is drawn only the first time it appears on a page ("Attention" came out as
 * "At en io"). Here every outline handed to the drawer is a fresh copy, so the cache is never touched.
 *
 * Lives in PdfBox's package because Glyph2D is package-private.
 */
class SafePDFRenderer(doc: PDDocument) : PDFRenderer(doc) {
    override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer = SafePageDrawer(parameters)
}

private class CopyingGlyph2D(private val inner: Glyph2D) : Glyph2D {
    override fun getPathForCharacterCode(code: Int): Path? = inner.getPathForCharacterCode(code)?.let { Path(it) }
    override fun dispose() = inner.dispose()
}

class SafePageDrawer(parameters: PageDrawerParameters) : PageDrawer(parameters) {
    @Suppress("UNCHECKED_CAST")
    private val glyphs: MutableMap<PDFont, Glyph2D> = FIELD.get(this) as MutableMap<PDFont, Glyph2D>

    override fun showFontGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val g = glyphs[font]
        if (g !is CopyingGlyph2D) {
            val inner = g ?: CREATE.invoke(this, font) as Glyph2D?
            if (inner != null) glyphs[font] = CopyingGlyph2D(inner)
        }
        super.showFontGlyph(textRenderingMatrix, font, code, displacement)
    }

    companion object {
        private val FIELD = PageDrawer::class.java.getDeclaredField("fontGlyph2D").apply { isAccessible = true }
        private val CREATE = PageDrawer::class.java.getDeclaredMethod("createGlyph2D", PDFont::class.java).apply { isAccessible = true }
    }
}
