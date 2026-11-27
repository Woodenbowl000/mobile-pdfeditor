package com.mobilepdf.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.annot.MarkKind
import com.mobilepdf.editor.pdf.AnnotBaker
import com.mobilepdf.editor.pdf.FieldType
import com.mobilepdf.editor.pdf.Forms
import com.mobilepdf.editor.pdf.Navigation
import com.mobilepdf.editor.pdf.PageLink
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.pdf.PdfOps.PageItem
import com.mobilepdf.editor.pdf.TextIndexer
import com.mobilepdf.editor.pdf.TextEditor
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs every PDF operation on real files, on a real (emulated) device, and checks the output. */
@RunWith(AndroidJUnit4::class)
class PdfOpsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(ctx.cacheDir, "test").apply { deleteRecursively(); mkdirs() }
    }

    private fun f(name: String) = File(dir, name)

    /** A4 pages with a line of text each; [rotations] gives each page's /Rotate. */
    private fun makePdf(name: String, pages: Int, rotations: List<Int> = emptyList()): File {
        val out = f(name)
        PDDocument().use { d ->
            repeat(pages) { i ->
                val p = PDPage(PDRectangle.A4)
                p.rotation = rotations.getOrElse(i) { 0 }
                d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 24f); cs.newLineAtOffset(72f, 700f)
                    cs.showText("Hello page ${i + 1} invoice"); cs.endText()
                }
            }
            d.save(out)
        }
        return out
    }

    private fun pageCount(file: File) = PdfOps.load(file).use { it.numberOfPages }

    private fun <T> render(file: File, block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(block) }

    private fun bitmapOf(file: File, page: Int): Bitmap = render(file) { r ->
        r.openPage(page).use { p ->
            Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    @Test fun mergeSplitExtract() {
        val a = makePdf("a.pdf", 3); val b = makePdf("b.pdf", 2)
        val m = f("m.pdf"); PdfOps.merge(listOf(a, b), m)
        assertEquals(5, pageCount(m))

        val parts = PdfOps.splitEvery(m, 2, File(dir, "parts"), "m")
        assertEquals(listOf(2, 2, 1), parts.map(::pageCount))

        val e = f("e.pdf"); PdfOps.extractPages(m, listOf(4, 0), e)
        assertEquals(2, pageCount(e))
        val t = f("e.txt"); PdfOps.extractText(e, t)
        assertTrue(t.readText().indexOf("page 2") < t.readText().indexOf("page 1"))
    }

    @Test fun organizeReorderRotateDuplicateBlankDelete() {
        val src = makePdf("o.pdf", 3)
        val out = f("o2.pdf")
        PdfOps.applyPageEdits(
            src,
            listOf(PageItem(1, 2, rotation = 90), PageItem(2, 0), PageItem(3, 0, copy = true), PageItem(4, -1)),
            out,
        )
        PdfOps.load(out).use { d ->
            assertEquals(4, d.numberOfPages)
            assertEquals(90, d.getPage(0).rotation)
            assertEquals(0, d.getPage(1).rotation)
        }
        val t = f("o2.txt"); PdfOps.extractText(out, t)
        val text = t.readText()
        assertTrue(text.indexOf("page 3") < text.indexOf("page 1"))
        assertFalse(text.contains("page 2")) // deleted
        // the result must still open in the OS renderer
        assertEquals(4, render(out) { it.pageCount })
    }

    @Test fun cropIsRotationAware() {
        val src = makePdf("c.pdf", 2, rotations = listOf(0, 90))
        val out = f("c2.pdf")
        // cut 25% off the displayed left edge of both pages
        PdfOps.applyPageEdits(src, listOf(PageItem(1, 0, crop = listOf(0.25f, 0f, 0f, 0f)), PageItem(2, 1, crop = listOf(0.25f, 0f, 0f, 0f))), out)
        render(out) { r ->
            r.openPage(0).use { assertEquals(595 * 0.75, it.width.toDouble(), 2.0); assertEquals(842.0, it.height.toDouble(), 1.5) }
            r.openPage(1).use { assertEquals(842 * 0.75, it.width.toDouble(), 2.0); assertEquals(595.0, it.height.toDouble(), 1.5) }
        }
    }

    /** Bakes a red highlight at a displayed position on pages with every rotation; the pixel must be red there. */
    @Test fun bakedAnnotationsLandWhereTheyWereDrawnOnRotatedPages() {
        val rots = listOf(0, 90, 180, 270)
        val src = makePdf("r.pdf", 4, rotations = rots)
        val box = Rect(0.10f, 0.60f, 0.40f, 0.80f) // lower-left quadrant as the user sees the page
        val annots = rots.indices.associateWith { listOf<Annot>(Annot.Mark(it.toLong(), 0xFFFF0000.toInt(), MarkKind.HIGHLIGHT, listOf(box))) }
        val out = f("r2.pdf"); AnnotBaker.bake(src, annots, out)
        for (i in rots.indices) {
            val bmp = bitmapOf(out, i)
            val inside = bmp.getPixel((0.25f * bmp.width).toInt(), (0.70f * bmp.height).toInt())
            val outside = bmp.getPixel((0.75f * bmp.width).toInt(), (0.30f * bmp.height).toInt())
            assertTrue("page rot ${rots[i]}: inside should be red, was ${Integer.toHexString(inside)}", Color.red(inside) > 200 && Color.green(inside) < 190)
            assertEquals("page rot ${rots[i]}: outside untouched", Color.WHITE, outside)
        }
    }

    @Test fun bakeInkShapesTextStampDoesNotBreakTheFile() {
        val src = makePdf("i.pdf", 1, listOf(90))
        val annots = mapOf(0 to listOf(
            Annot.Ink(1, Color.BLUE, 0.01f, true, listOf(Offset(0.1f, 0.1f), Offset(0.5f, 0.2f))),
            Annot.Shape(2, Color.RED, 0.005f, com.mobilepdf.editor.annot.ShapeKind.ARROW, Offset(0.2f, 0.5f), Offset(0.6f, 0.7f)),
            Annot.Shape(3, Color.RED, 0.005f, com.mobilepdf.editor.annot.ShapeKind.OVAL, Offset(0.2f, 0.5f), Offset(0.6f, 0.7f)),
            Annot.Text(4, Color.BLACK, Offset(0.1f, 0.85f), "Approved – ok ✓\nline two", 0.04f),
            Annot.Stamp(5, Color.BLACK, Rect(0.5f, 0.85f, 0.9f, 0.95f), listOf(listOf(Offset(0f, 0f), Offset(1f, 1f)))),
        ))
        val out = f("i2.pdf"); AnnotBaker.bake(src, annots, out)
        assertEquals(1, render(out) { it.pageCount })
        val t = f("i2.txt"); PdfOps.extractText(out, t)
        assertTrue(t.readText().contains("Approved"))
    }

    /** Edit text: the old words leave the file, the new ones use the line's own font, size, colour and baseline. */
    @Test fun editExistingTextLine() {
        val src = f("t.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.setNonStrokingColor(0.8f, 0.1f, 0.1f) // red text: the colour must survive the edit
                cs.beginText(); cs.setFont(PDType1Font.TIMES_ROMAN, 24f); cs.newLineAtOffset(72f, 700f)
                cs.showText("Hello page 1 invoice"); cs.endText()
            }
            d.save(src)
        }
        val line = TextIndexer(src).use { idx -> runBlocking { idx.page(0) }.lineAt(Offset(0.2f, 0.165f)) }!!
        assertEquals("Hello page 1 invoice", line.first)
        assertEquals(24f / 595f, line.third, 0.0005f)

        val out = f("t2.pdf")
        val r = TextEditor.replaceLine(src, 0, line.second, "Goodbye page 1 receipt", out)
        assertEquals("source font reused for every letter", "", r.missing)
        val text = f("t2.txt").also { PdfOps.extractText(out, it) }.readText()
        assertTrue(text, text.contains("Goodbye page 1 receipt"))
        assertFalse("old words removed from the file", text.contains("invoice"))

        // same baseline and size: the edited line is found where the old one was
        val again = TextIndexer(out).use { idx -> runBlocking { idx.page(0) }.lineAt(Offset(0.2f, 0.165f)) }!!
        assertEquals("Goodbye page 1 receipt", again.first)
        assertEquals(line.second.bottom, again.second.bottom, 0.002f)
        assertEquals(line.third, again.third, 0.0005f)
        // same font: Times, not the Helvetica fallback
        PdfOps.load(out).use { d ->
            val names = d.getPage(0).resources.fontNames.map { d.getPage(0).resources.getFont(it).name }
            assertTrue(names.toString(), names.all { it.contains("Times") })
        }
        // same colour: the new glyphs are red
        val bmp = bitmapOf(out, 0)
        var red = 0
        for (x in (again.second.left * bmp.width).toInt() until (again.second.right * bmp.width).toInt())
            for (y in (again.second.top * bmp.height).toInt() until (again.second.bottom * bmp.height).toInt()) {
                val c = bmp.getPixel(x, y); if (Color.red(c) > 150 && Color.green(c) < 90) red++
            }
        assertTrue("new text is red ($red px)", red > 50)
    }

    /**
     * The case that failed on a real phone: lines written with T* (next line) in one text block, on a page
     * that already has an earlier edit appended (several content streams). Only the edited line may go.
     */
    @Test fun editTextOnMultiLineBlockWithEarlierEdits() {
        val src = f("ml.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 13f); cs.setLeading(20f); cs.newLineAtOffset(40f, 760f)
                listOf("First line stays", "Second line changes", "Third line stays").forEach { cs.showText(it); cs.newLine() }
                cs.endText()
            }
            d.save(src)
        }
        val inked = f("ml2.pdf")
        AnnotBaker.bake(src, mapOf(0 to listOf<Annot>(Annot.Ink(1, Color.RED, 0.005f, false, listOf(Offset(0.1f, 0.8f), Offset(0.5f, 0.9f))))), inked)
        val (text, rect, size) = TextIndexer(inked).use { idx -> runBlocking { idx.page(0) }.lineAt(Offset(0.2f, (842f - 740f) / 842f - 0.005f)) }!!
        assertEquals("Second line changes", text)
        assertEquals("font size read from the PDF", 13f / 595f, size, 0.0005f)
        val out = f("ml3.pdf")
        TextEditor.replaceLine(inked, 0, rect, "Second line was edited", out)
        val all = f("ml3.txt").also { PdfOps.extractText(out, it) }.readText()
        assertTrue(all, all.contains("First line stays") && all.contains("Third line stays") && all.contains("Second line was edited"))
        assertFalse("old line removed from the file: $all", all.contains("Second line changes"))
        // and it can be edited again
        val (t2, r2, _) = TextIndexer(out).use { idx -> runBlocking { idx.page(0) }.lineAt(Offset(0.2f, (842f - 740f) / 842f - 0.005f)) }!!
        assertEquals("Second line was edited", t2)
        val out2 = f("ml4.pdf")
        TextEditor.replaceLine(out, 0, r2, "Edited twice", out2)
        val all2 = f("ml4.txt").also { PdfOps.extractText(out2, it) }.readText()
        assertTrue(all2, all2.contains("Edited twice") && !all2.contains("was edited") && all2.contains("Third line stays"))
    }

    /** Signature photo: grey, unevenly lit paper with a dark scribble -> only the ink remains, placed correctly. */
    @Test fun signatureFromPhoto() {
        val photo = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(photo)
        // paper: light grey with a shadow gradient, like a phone photo
        c.drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 1200f, 900f, Color.rgb(235, 232, 225), Color.rgb(185, 182, 175), android.graphics.Shader.TileMode.CLAMP)
        })
        val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 30, 90); strokeWidth = 10f; style = android.graphics.Paint.Style.STROKE }
        c.drawPath(android.graphics.Path().apply { moveTo(300f, 500f); cubicTo(400f, 300f, 500f, 650f, 650f, 420f); lineTo(800f, 520f) }, ink)
        val jpg = f("sig.jpg"); jpg.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 85, it) }

        val (png, hw) = PdfOps.signatureFromPhoto(ctx, android.net.Uri.fromFile(jpg), f("sig.png"))
        val sig = android.graphics.BitmapFactory.decodeFile(png.path)
        assertTrue("cropped tight around the ink (${sig.width}x${sig.height})", sig.width < 700 && sig.height < 450)
        assertEquals(sig.height.toFloat() / sig.width, hw, 0.01f)
        assertEquals("paper corner is transparent", 0, Color.alpha(sig.getPixel(0, 0)))
        var opaque = 0
        for (x in 0 until sig.width) for (y in 0 until sig.height) if (Color.alpha(sig.getPixel(x, y)) > 200) opaque++
        assertTrue("ink kept ($opaque px)", opaque > 500)

        // placed on a rotated page, it must land where it is shown
        val src = makePdf("sigdoc.pdf", 1, listOf(90))
        val box = Rect(0.55f, 0.70f, 0.95f, 0.70f + 0.4f * hw / (595f / 842f))
        val out = f("sigdoc2.pdf")
        AnnotBaker.bake(src, mapOf(0 to listOf<Annot>(Annot.Stamp(1, Color.BLACK, box, emptyList(), png.path))), out)
        val page = bitmapOf(out, 0)
        var dark = 0
        for (x in (box.left * page.width).toInt() until (box.right * page.width).toInt())
            for (y in (box.top * page.height).toInt() until (box.bottom * page.height).toInt())
                if (Color.blue(page.getPixel(x, y)) < 160 && Color.red(page.getPixel(x, y)) < 100) dark++
        assertTrue("signature visible in its box ($dark px)", dark > 200)
        assertEquals("paper did not come along", Color.WHITE, page.getPixel((box.left * page.width).toInt() + 2, (box.top * page.height).toInt() + 2))
    }

    @Test fun protectThenUnlock() {
        val src = makePdf("p.pdf", 2)
        val locked = f("p2.pdf")
        PdfOps.protect(src, "s3cret", PdfOps.Permissions(print = true, copy = false, modify = false), locked)
        // detected by byte scan, never by handing it to the OS renderer (pdfium on Android 7 segfaults on AES-256)
        assertTrue(PdfOps.mentionsEncryption(locked))
        assertFalse(PdfOps.mentionsEncryption(src))
        assertEquals(null, PdfOps.unlock(locked, "", f("x0.pdf")))
        assertEquals(null, PdfOps.unlock(locked, "wrong", f("x.pdf")))
        val open = f("p3.pdf")
        val access = PdfOps.unlock(locked, "s3cret", open)!!
        // user password opens it, but the restrictions chosen when protecting are reported
        assertFalse(access.canModify); assertFalse(access.canCopy)
        assertFalse(PdfOps.mentionsEncryption(open))
        assertTrue(PdfOps.rendererAccepts(open))
        assertEquals(2, pageCount(open))
    }

    @Test fun watermarkNumbersMetadataCompress() {
        val src = makePdf("w.pdf", 3)
        val w = f("w2.pdf"); PdfOps.watermark(src, "DRAFT", 0.3f, w)
        val n = f("w3.pdf"); PdfOps.pageNumbers(w, PdfOps.NumberPos.BOTTOM_CENTER, 1, n)
        val t = f("w3.txt"); PdfOps.extractText(n, t)
        assertTrue(t.readText().contains("DRAFT")); assertTrue(t.readText().contains("2 / 3"))
        val md = f("w4.pdf"); PdfOps.setMetadata(n, "Quarterly", "Ali", "", md)
        assertEquals("Quarterly", PdfOps.info(md).title)
        // text-only file: nothing to shrink, compress must say so rather than grow the file
        assertFalse(PdfOps.compress(md, PdfOps.Level.BALANCED, f("w5.pdf")))
    }

    @Test fun imagesToPdfAndCompressShrinksBigImages() {
        val png = f("big.png")
        val bmp = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        for (x in 0 until 3000 step 7) for (y in 0 until 2000 step 5) bmp.setPixel(x, y, Color.rgb(x % 255, y % 255, (x + y) % 255))
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pdf = f("img.pdf")
        PdfOps.imagesToPdf(ctx, listOf(android.net.Uri.fromFile(png)), PdfOps.PageFit.A4, PdfOps.ScanFilter.DOCUMENT, pdf)
        PdfOps.load(pdf).use { d ->
            assertEquals(1, d.numberOfPages)
            assertTrue("landscape photo gets a landscape page", d.getPage(0).mediaBox.width > d.getPage(0).mediaBox.height)
        }
        val small = f("img_small.pdf")
        assertTrue(PdfOps.compress(pdf, PdfOps.Level.SMALLEST, small))
        assertTrue(small.length() < pdf.length())
    }

    @Test fun restrictedButOpenablePdfUnlocksWithEmptyPassword() {
        val src = makePdf("q.pdf", 1)
        val locked = f("q2.pdf")
        PdfOps.protect(src, "", PdfOps.Permissions(print = true, copy = true, modify = false), locked)
        assertTrue(PdfOps.mentionsEncryption(locked))
        val access = PdfOps.unlock(locked, "", f("q3.pdf"))!!
        assertFalse(access.canModify); assertTrue(access.canCopy)
    }

    @Test fun repairDamagedFile() {
        val src = makePdf("d.pdf", 2)
        // cut off the xref table and trailer: a classic broken download
        val bytes = src.readBytes()
        val cut = String(bytes, Charsets.ISO_8859_1).lastIndexOf("xref")
        val broken = f("broken.pdf").apply { writeBytes(bytes.copyOf(cut)) }
        val fixed = f("fixed.pdf")
        PdfOps.repair(broken, fixed)
        assertEquals(2, render(fixed) { it.pageCount })
    }

    @Test fun formsReadFillFlatten() {
        val src = f("form.pdf")
        PDDocument().use { d ->
            val page = PDPage(PDRectangle.A4); d.addPage(page)
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 0 Tf 0 g"
            val tf = PDTextField(form); tf.partialName = "fullName"; tf.defaultAppearance = "/Helv 12 Tf 0 g"
            form.fields.add(tf)
            val w = tf.widgets[0]; w.rectangle = PDRectangle(50f, 700f, 300f, 24f); w.page = page
            page.annotations.add(w)
            d.save(src)
        }
        val fields = PdfOps.load(src).use { Forms.read(it) }
        assertEquals(1, fields.size)
        val fld = fields[0]
        assertEquals(FieldType.TEXT, fld.type); assertEquals("fullName", fld.name)
        // widget at y=700..724 of 842 -> displayed top ≈ 0.14
        assertEquals(1 - 724f / 842f, fld.widgets[0].rect.top, 0.01f)

        val filled = f("form2.pdf"); Forms.fill(src, mapOf("fullName" to "Ali Afzal"), flatten = false, filled)
        assertEquals("Ali Afzal", PdfOps.load(filled).use { Forms.read(it)[0].value })

        // The filled value must be visible on screen. Field box: x 50..350, y 700..724 (PDF space).
        fun darkPixelsInField(file: File): Int {
            val bmp = bitmapOf(file, 0)
            val sx = bmp.width / 595f; val sy = bmp.height / 842f
            var dark = 0
            for (x in (55 * sx).toInt() until (345 * sx).toInt()) for (y in ((842 - 722) * sy).toInt() until ((842 - 702) * sy).toInt()) {
                if (Color.red(bmp.getPixel(x, y)) < 100) dark++
            }
            return dark
        }
        val raw = darkPixelsInField(filled)
        android.util.Log.i("PdfOpsTest", "OS renderer, filled field without display copy: $raw dark pixels")
        val view = f("form2.view.pdf")
        assertTrue("display copy needed: the form has an appearance to draw", PdfOps.makeDisplayCopy(filled, view))
        assertTrue("filled value visible in display copy", darkPixelsInField(view) > 40)
        assertFalse("plain pages need no display copy", PdfOps.makeDisplayCopy(makePdf("plain.pdf", 1), f("plain.view.pdf")))

        val flat = f("form3.pdf"); Forms.fill(src, mapOf("fullName" to "Ali Afzal"), flatten = true, flat)
        assertTrue(PdfOps.load(flat).use { Forms.read(it).isEmpty() })
        val t = f("form3.txt"); PdfOps.extractText(flat, t)
        assertTrue(t.readText().contains("Ali Afzal"))
    }

    @Test fun outlineLinksAndSearch() {
        val src = makePdf("n.pdf", 3)
        val out = f("n2.pdf")
        PdfOps.load(src).use { d ->
            val outline = PDDocumentOutline(); d.documentCatalog.documentOutline = outline
            listOf("Intro" to 0, "Results" to 2).forEach { (title, p) ->
                outline.addLast(PDOutlineItem().apply {
                    this.title = title
                    destination = PDPageFitWidthDestination().apply { page = d.getPage(p) }
                })
            }
            val link = PDAnnotationLink().apply {
                rectangle = PDRectangle(72f, 690f, 200f, 30f)
                action = PDActionURI().apply { uri = "https://example.com" }
            }
            d.getPage(1).annotations.add(link)
            d.save(out)
        }
        PdfOps.load(out).use { d ->
            assertEquals(listOf("Intro" to 0, "Results" to 2), Navigation.outline(d).map { it.title to it.page })
            val l = Navigation.links(d, 1).single() as PageLink.Uri
            assertEquals("https://example.com", l.uri)
        }
        TextIndexer(out).use { idx ->
            val hits = runBlocking { idx.page(2).find("INVOICE", matchCase = false) }
            assertEquals(1, hits.size)
            val r = hits[0][0]
            assertTrue("hit near the top of the page, was $r", r.top in 0.1f..0.2f && r.left > 0.1f)
        }
    }
}
