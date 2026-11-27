package com.mobilepdf.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilepdf.editor.pdf.Attachments
import com.mobilepdf.editor.pdf.HeaderFooter
import com.mobilepdf.editor.pdf.ImageExtractor
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.pdf.Sanitizer
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocToolsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(ctx.cacheDir, "doctools").apply { deleteRecursively(); mkdirs() } }
    private fun f(n: String) = File(dir, n)
    private fun text(file: File) = f("${file.name}.txt").also { PdfOps.extractText(file, it) }.readText()

    @Test fun removeHiddenInformationFindsAndRemovesEveryCategory() {
        val src = f("hidden.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(60f, 700f); cs.showText("Visible text stays"); cs.endText()
                cs.beginText(); cs.setRenderingMode(RenderingMode.NEITHER); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(60f, 600f); cs.showText("Hidden words"); cs.endText()
            }
            p.annotations.add(PDAnnotationLink().apply { rectangle = PDRectangle(60f, 690f, 100f, 20f) })
            p.annotations.add(PDAnnotationText().apply { rectangle = PDRectangle(300f, 700f, 20f, 20f); contents = "A comment" })
            d.documentInformation.title = "Secret title"; d.documentInformation.author = "Someone"
            d.documentCatalog.openAction = PDActionJavaScript("app.alert('hi')")
            val outline = PDDocumentOutline(); d.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply { title = "Chapter 1" })
            p.cosObject.setItem(COSName.getPDFName("PieceInfo"), com.tom_roush.pdfbox.cos.COSDictionary())
            d.save(src)
        }
        val att = f("hidden-att.pdf")
        f("note.txt").writeText("attached secret")
        Attachments.add(src, f("note.txt"), "note.txt", "text/plain", att)

        val before = Sanitizer.scan(att)
        for (h in listOf(Sanitizer.Hidden.METADATA, Sanitizer.Hidden.ATTACHMENTS, Sanitizer.Hidden.COMMENTS, Sanitizer.Hidden.HIDDEN_TEXT,
            Sanitizer.Hidden.LINKS_SCRIPTS, Sanitizer.Hidden.BOOKMARKS, Sanitizer.Hidden.PRIVATE)) {
            assertTrue("$h found: $before", (before[h] ?: 0) > 0)
        }
        val out = f("clean.pdf")
        Sanitizer.remove(att, Sanitizer.Hidden.entries.toSet(), out)
        val after = Sanitizer.scan(out)
        assertTrue("everything removed: $after", after.values.all { it == 0 })
        val t = text(out)
        assertTrue(t, t.contains("Visible text stays"))
        assertFalse(t, t.contains("Hidden words"))
        assertTrue(Attachments.list(out).isEmpty())
    }

    @Test fun extractImagesSavesEachPictureOnce() {
        val src = f("pics.pdf")
        PDDocument().use { d ->
            val red = LosslessFactory.createFromImage(d, Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
            val blue = LosslessFactory.createFromImage(d, Bitmap.createBitmap(50, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })
            repeat(2) { i ->
                val p = PDPage(PDRectangle.A4); d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.drawImage(red, 50f, 500f, 120f, 90f) // the same image on both pages
                    if (i == 1) cs.drawImage(blue, 50f, 300f, 150f, 60f)
                }
            }
            d.save(src)
        }
        val out = File(dir, "pics").apply { mkdirs() }
        val files = ImageExtractor.extract(src, out, "pics")
        assertEquals(files.map { it.name }.toString(), 2, files.size)
        val sizes = files.map { android.graphics.BitmapFactory.decodeFile(it.path).let { b -> b.width to b.height } }.toSet()
        assertEquals(setOf(40 to 30, 50 to 20), sizes)
    }

    @Test fun attachmentsRoundTrip() {
        val src = f("plain.pdf")
        PDDocument().use { d -> d.addPage(PDPage()); d.save(src) }
        val data = f("data.bin").apply { writeBytes(ByteArray(3000) { (it * 7).toByte() }) }
        val out = f("with-att.pdf")
        Attachments.add(src, data, "data.bin", "application/octet-stream", out)
        val list = Attachments.list(out)
        assertEquals(listOf("data.bin"), list.map { it.name })
        val back = f("back.bin")
        assertTrue(Attachments.save(out, "data.bin", back))
        assertArrayEquals(data.readBytes(), back.readBytes())
    }

    @Test fun compareFindsChangedLinesAndWritesAReport() {
        fun doc(f: File, lines: List<String>) = PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                lines.forEachIndexed { i, t -> cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(60f, 760f - i * 20f); cs.showText(t); cs.endText() }
            }
            d.save(f)
        }
        val a = f("v1.pdf"); val b = f("v2.pdf")
        doc(a, listOf("Quote for Sara", "Price 100 dollars", "Valid 30 days"))
        doc(b, listOf("Quote for Sara", "Price 120 dollars", "Valid 30 days", "Signed by Ali"))
        val r = com.mobilepdf.editor.pdf.Compare.compare(a, b)
        assertEquals(1, r.removed); assertEquals(2, r.added); assertEquals(listOf(0), r.changedPages)
        val out = f("report.pdf")
        com.mobilepdf.editor.pdf.Compare.report(r, "v1.pdf", "v2.pdf", out)
        val t = text(out)
        assertTrue(t, t.contains("- Price 100 dollars") && t.contains("+ Price 120 dollars") && t.contains("+ Signed by Ali"))
    }

    @Test fun stampsAreSavedIntoThePage() {
        val src = f("stampme.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle.A4)); d.addPage(PDPage(PDRectangle.A4).apply { rotation = 90 }); d.save(src) }
        val out = f("stamped.pdf")
        com.mobilepdf.editor.pdf.AnnotBaker.bake(src, mapOf(
            0 to listOf(com.mobilepdf.editor.annot.Annot.Seal(1, 0xFF2E7D32.toInt(), androidx.compose.ui.geometry.Rect(0.3f, 0.4f, 0.64f, 0.47f), "APPROVED")),
            1 to listOf(com.mobilepdf.editor.annot.Annot.Seal(2, 0xFFC62828.toInt(), androidx.compose.ui.geometry.Rect(0.3f, 0.4f, 0.64f, 0.5f), "PAID")),
        ), out)
        val t = text(out)
        assertTrue(t, t.contains("APPROVED") && t.contains("PAID"))
    }

    @Test fun insertPagesFromAnotherPdfInPlace() {
        fun doc(f: File, names: List<String>) = PDDocument().use { d ->
            for (n in names) {
                val p = PDPage(PDRectangle.A4); d.addPage(p)
                PDPageContentStream(d, p).use { cs -> cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 20f); cs.newLineAtOffset(60f, 700f); cs.showText(n); cs.endText() }
            }
            d.save(f)
        }
        val a = f("a.pdf"); val b = f("b.pdf")
        doc(a, listOf("Page A1", "Page A2")); doc(b, listOf("Page B1", "Page B2"))
        val out = f("ab.pdf")
        assertEquals(2, PdfOps.insertPages(a, b, 1, out))
        val order = PdfOps.load(out).use { d ->
            (1..d.numberOfPages).map { i -> com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = i; endPage = i }.getText(d).trim() }
        }
        assertEquals(listOf("Page A1", "Page B1", "Page B2", "Page A2"), order)
    }

    @Test fun tickCrossAndDotAreSavedAsInk() {
        val src = f("form.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle.A4)); d.save(src) }
        val aspect = 842f / 595f
        fun mark(id: Long, kind: com.mobilepdf.editor.annot.ShapeKind, cx: Float) = com.mobilepdf.editor.annot.Annot.Shape(
            id, 0xFF000000.toInt(), 0.004f, kind,
            androidx.compose.ui.geometry.Offset(cx - 0.02f, 0.3f - 0.02f / aspect), androidx.compose.ui.geometry.Offset(cx + 0.02f, 0.3f + 0.02f / aspect))
        val out = f("form-marked.pdf")
        com.mobilepdf.editor.pdf.AnnotBaker.bake(src, mapOf(0 to listOf(
            mark(1, com.mobilepdf.editor.annot.ShapeKind.TICK, 0.2f), mark(2, com.mobilepdf.editor.annot.ShapeKind.CROSS, 0.5f), mark(3, com.mobilepdf.editor.annot.ShapeKind.DOT, 0.8f),
        )), out)
        val bmp = android.os.ParcelFileDescriptor.open(out, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r -> r.openPage(0).use { p ->
                Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(Color.WHITE); p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            } }
        }
        fun inkIn(cx: Float): Int { var n = 0
            for (y in (0.3f * 842 - 14).toInt()..(0.3f * 842 + 14).toInt()) for (x in (cx * 595 - 14).toInt()..(cx * 595 + 14).toInt())
                if (Color.red(bmp.getPixel(x, y)) < 100) n++
            return n }
        assertTrue("tick drawn: ${inkIn(0.2f)}", inkIn(0.2f) > 15)
        assertTrue("cross drawn: ${inkIn(0.5f)}", inkIn(0.5f) > 15)
        assertTrue("dot drawn: ${inkIn(0.8f)}", inkIn(0.8f) > 40)
        assertEquals("nothing elsewhere", 0, inkIn(0.35f))
    }

    @Test fun headerAndFooterOnEveryPageEvenRotated() {
        val src = f("hf.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage(PDRectangle.A4))
            d.addPage(PDPage(PDRectangle.A4).apply { rotation = 90 })
            d.save(src)
        }
        val out = f("hf2.pdf")
        HeaderFooter.apply(src, "Report {file}", "Page {page} of {pages}", HeaderFooter.Align.CENTER, 10f, "hf", out)
        val t = text(out)
        assertTrue(t, t.contains("Page 1 of 2") && t.contains("Page 2 of 2") && t.contains("Report hf"))
    }
}
