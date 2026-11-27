package com.mobilepdf.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilepdf.editor.pdf.DeviceBudget
import com.mobilepdf.editor.pdf.Ocr
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.pdf.TextEditor
import com.mobilepdf.editor.pdf.TextIndexer
import com.mobilepdf.editor.pdf.TextFonts
import com.mobilepdf.editor.pdf.AnnotBaker
import com.mobilepdf.editor.pdf.displayFontName
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.annot.FontChoice
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextFontAndOcrTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(ctx.cacheDir, "fonttest").apply { deleteRecursively(); mkdirs() } }
    private fun f(n: String) = File(dir, n)
    private fun text(file: File) = f("${file.name}.txt").also { PdfOps.extractText(file, it) }.readText()

    /** A line in two fonts is two runs, each with its font; editing one leaves the other untouched. */
    @Test fun runsAreSplitByFontAndEditedSeparately() {
        val src = f("two-fonts.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.newLineAtOffset(60f, 700f)
                cs.setFont(PDType1Font.HELVETICA, 20f); cs.showText("Invoice total: ")
                cs.setFont(PDType1Font.TIMES_BOLD, 20f); cs.showText("1200 USD")
                cs.endText()
            }
            d.save(src)
        }
        val runs = TextIndexer(src).use { runBlocking { it.page(0) }.runs }
        assertEquals(runs.toString(), 2, runs.size)
        assertEquals("Helvetica", runs[0].font); assertEquals("Invoice total:", runs[0].text)
        assertEquals("Times-Bold", runs[1].font); assertEquals("1200 USD", runs[1].text)

        val out = f("two-fonts2.pdf")
        val r = TextEditor.replaceLine(src, 0, runs[1].rect, "950 USD", out)
        assertEquals("", r.missing)
        val t = text(out)
        assertTrue(t, t.contains("Invoice total:") && t.contains("950 USD") && !t.contains("1200"))
        val after = TextIndexer(out).use { runBlocking { it.page(0) }.runs }
        assertEquals("edited run keeps its font", "Times-Bold", after.first { it.text == "950 USD" }.font)
        assertEquals("untouched run keeps its font", "Helvetica", after.first { it.text.startsWith("Invoice") }.font)
    }

    /** A table row in one font: each cell is its own piece of text, and editing one cell leaves the others. */
    @Test fun tableCellsAreSeparateRunsAndEditedSeparately() {
        val src = f("table.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                // grid lines, like a real table
                for (x in listOf(50f, 250f, 370f, 520f)) { cs.moveTo(x, 720f); cs.lineTo(x, 660f) }
                for (y in listOf(720f, 690f, 660f)) { cs.moveTo(50f, y); cs.lineTo(520f, y) }
                cs.stroke()
                cs.setFont(PDType1Font.HELVETICA, 14f)
                fun cell(x: Float, y: Float, t: String) { cs.beginText(); cs.newLineAtOffset(x, y); cs.showText(t); cs.endText() }
                cell(58f, 700f, "Blue pen"); cell(258f, 700f, "12"); cell(378f, 700f, "4.50")
                cell(58f, 670f, "Red marker"); cell(258f, 670f, "3"); cell(378f, 670f, "2.10")
            }
            d.save(src)
        }
        val runs = TextIndexer(src).use { runBlocking { it.page(0) }.runs }
        assertEquals(runs.toString(), listOf("Blue pen", "12", "4.50", "Red marker", "3", "2.10"), runs.map { it.text })

        val out = f("table2.pdf")
        TextEditor.replaceLine(src, 0, runs[1].rect, "15", out)
        val after = TextIndexer(out).use { runBlocking { it.page(0) }.runs }.map { it.text }
        assertEquals(after.toString(), listOf("Blue pen", "15", "4.50", "Red marker", "3", "2.10"), after)
    }

    /**
     * Add text with the eyedropper's font: saved text uses the PDF's own font (even from another page);
     * with an uploaded .ttf it is embedded; letters a subset lacks fall back to the look-alike.
     */
    @Test fun addedTextUsesCopiedOrUploadedFont() {
        val ttf = File("/system/fonts/NotoSerif-Regular.ttf")
        val src = f("styled.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p); d.addPage(PDPage(PDRectangle.A4))
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.TIMES_BOLD_ITALIC, 18f); cs.newLineAtOffset(60f, 700f); cs.showText("Heading"); cs.endText()
            }
            d.save(src)
        }
        val run = TextIndexer(src).use { runBlocking { it.page(0) }.runs.single() }
        val picked = TextFonts.guess(run.font).copy(pdfFont = run.font, fromPage = 0)
        assertTrue("bold + italic + serif from the name: $picked", picked.bold && picked.italic && picked.family == "serif")

        fun fontsOn(file: File, page: Int) = PdfOps.load(file).use { d ->
            val r = d.getPage(page).resources; r.fontNames.map { displayFontName(r.getFont(it).name) }.toSet()
        }
        val out = f("styled2.pdf")
        AnnotBaker.bake(src, mapOf(1 to listOf(Annot.Text(1, 0, Offset(0.1f, 0.1f), "Copied style", 0.03f, picked))), out)
        assertEquals("page 2 written with the copied font", setOf("Times-BoldItalic"), fontsOn(out, 1))
        assertTrue(text(out).contains("Copied style"))

        assumeTrue("device has NotoSerif", ttf.exists())
        TextFonts.validate(ttf)
        val out2 = f("styled3.pdf")
        AnnotBaker.bake(src, mapOf(0 to listOf(Annot.Text(1, 0, Offset(0.1f, 0.3f), "Uploaded font", 0.03f, FontChoice(file = ttf.path)))), out2)
        assertTrue(fontsOn(out2, 0).toString(), fontsOn(out2, 0).any { it.contains("NotoSerif") })
        assertTrue(text(out2).contains("Uploaded font"))
    }

    /** Paragraph editing: the lines are found as one paragraph, re-wrapped to its width; the next paragraph stays. */
    @Test fun paragraphIsEditedAsOneAndRewrapped() {
        val src = f("para.pdf")
        val para = listOf("The quick brown fox jumps over the lazy", "dog while the farmer watches from the old", "wooden fence near the barn.")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.setLeading(15f); cs.newLineAtOffset(60f, 700f)
                for (l in para) { cs.showText(l); cs.newLine() }
                cs.endText()
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(60f, 600f); cs.showText("Next paragraph stays as it is."); cs.endText()
            }
            d.save(src)
        }
        val pt = TextIndexer(src).use { runBlocking { it.page(0) } }
        val tap = pt.runs.first { it.text.startsWith("dog") }.rect.center
        val lines = pt.paragraphAt(tap)!!
        assertEquals("three lines, not the next paragraph", 3, lines.size)
        assertEquals(para.joinToString(" "), pt.paragraphText(lines))

        val out = f("para2.pdf")
        val newText = "The quick brown fox jumps over the very lazy dog while the farmer and his daughter watch from the old wooden fence near the red barn."
        val r = TextEditor.replaceBlock(src, 0, lines.map { it.rect }, newText, out)
        assertTrue("wrapped into lines: ${r.linesWritten}", r.linesWritten >= 3)
        val after = TextIndexer(out).use { runBlocking { it.page(0) } }
        val t = after.text.replace('\n', ' ').replace(Regex(" +"), " ")
        assertTrue(t, t.contains(newText))
        assertTrue(t.contains("Next paragraph stays as it is."))
        // every new line fits the paragraph's width
        val width = lines.maxOf { it.rect.right } - lines.minOf { it.rect.left }
        val newRuns = after.runs.filter { !it.text.startsWith("Next") }
        assertTrue(newRuns.all { it.rect.width <= width * 1.02f })
    }

    /**
     * Subset fonts only contain the letters the PDF used. A typed letter outside the subset must not
     * switch the whole line to another font: only that letter does, to the closest look-alike (serif here).
     */
    @Test fun missingLettersFallBackOneByOneToALookAlike() {
        val ttf = File("/system/fonts/NotoSerif-Regular.ttf")
        assumeTrue("device has NotoSerif", ttf.exists())
        val src = f("subset.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            val font = PDType0Font.load(d, ttf) // embedded as a subset of the glyphs used below
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(font, 24f); cs.newLineAtOffset(60f, 700f); cs.showText("Hello world"); cs.endText()
            }
            d.save(src)
        }
        val run = TextIndexer(src).use { runBlocking { it.page(0) }.runs.single() }
        assertTrue(run.font, run.font.contains("NotoSerif"))

        val out = f("subset2.pdf")
        // 'H','e','l','o','w','r','d' are in the subset; 'J','y','!' are not
        val r = TextEditor.replaceLine(src, 0, run.rect, "Jelly world!", out)
        assertEquals("only the letters the subset lacks", setOf('J', 'y', '!'), r.missing.toSet())
        assertEquals("serif look-alike, not Helvetica", "Times-Roman", r.fallbackName)
        assertTrue(text(out).contains("Jelly world!"))
        PdfOps.load(out).use { d ->
            val names = d.getPage(0).resources.fontNames.map { d.getPage(0).resources.getFont(it).name }
            assertTrue("source font still used: $names", names.any { it.contains("NotoSerif") })
        }
    }

    /** OCR end to end: a photo-like page becomes searchable; a scanned word can then be replaced. */
    @Test fun ocrMakesAScanSearchableAndEditable() {
        // a "scan": text rendered into an image on off-white paper
        val bmp = Bitmap.createBitmap(1700, 2200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.rgb(246, 244, 238))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 35); textSize = 70f; typeface = Typeface.SERIF }
        c.drawText("INVOICE NUMBER 48213", 150f, 400f, p)
        c.drawText("Total due: 1200 dollars", 150f, 560f, p)
        c.drawText("Payment within thirty days", 150f, 720f, p)
        val png = f("scan.png"); png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val scan = f("scan.pdf")
        PdfOps.imagesToPdf(ctx, listOf(android.net.Uri.fromFile(png)), PdfOps.PageFit.A4, PdfOps.ScanFilter.ORIGINAL, scan)
        assertFalse("no text before OCR", text(scan).any { it.isLetter() })

        val out = f("scan-ocr.pdf")
        val res = try {
            runBlocking { Ocr.recognize(scan, scan, out, DeviceBudget(ctx)) }
        } catch (e: Ocr.Unavailable) {
            assumeTrue("OCR unavailable here: ${e.message}", false); return
        }
        assertEquals(1, res.recognized)
        val t = text(out).uppercase()
        assertTrue(t, t.contains("INVOICE") && t.contains("48213") && t.contains("TOTAL"))
        // the page still looks the same: OCR text is invisible
        assertEquals(1, ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } })

        // edit a scanned line: covered with the paper colour, retyped visibly
        val run = TextIndexer(out).use { runBlocking { it.page(0) }.runs.first { it.text.contains("1200") } }
        assertTrue("OCR text is marked as scanned", run.invisible)
        val edited = f("scan-edited.pdf")
        val r = TextEditor.replaceLine(out, 0, run.rect, "Total due: 950 dollars", edited, paper = Color.rgb(246, 244, 238), ink = Color.rgb(30, 30, 35))
        assertTrue(r.wasScan)
        val t2 = text(edited)
        assertTrue(t2, t2.contains("950") && !t2.contains("1200"))
    }

    @Suppress("unused") private val keep = Offset.Zero
}
