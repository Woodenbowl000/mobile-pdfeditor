package com.mobilepdf.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilepdf.editor.pdf.PageText
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.pdf.Redactor
import com.mobilepdf.editor.pdf.TextIndexer
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RedactionTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(ctx.cacheDir, "redact").apply { deleteRecursively(); mkdirs() } }
    private fun f(n: String) = File(dir, n)
    private fun text(file: File) = f("${file.name}.txt").also { PdfOps.extractText(file, it) }.readText()
    private fun page(file: File): PageText = TextIndexer(file).use { runBlocking { it.page(0) } }
    private fun Rect.grow(dx: Float, up: Float, down: Float) = Rect(left - dx, top - up, right + dx, bottom + down)

    /** Text under a mark leaves the file; the rest of the line keeps its place; link and metadata go too. */
    @Test fun textUnderMarkIsGoneAndTheRestKeepsItsPlace() {
        val src = f("letter.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(60f, 700f)
                cs.showText("Customer: John Smith, account 4417"); cs.newLineAtOffset(0f, -24f)
                cs.showText("Thank you for your order"); cs.endText()
                // an underline beneath the name: a drawing wholly under the mark
                cs.moveTo(132f, 697f); cs.lineTo(195f, 697f); cs.stroke()
            }
            p.annotations.add(PDAnnotationLink().apply { rectangle = PDRectangle(130f, 695f, 80f, 16f) })
            d.documentInformation.title = "Invoice for John Smith"
            d.documentInformation.subject = "Orders"
            d.save(src)
        }
        val pt = page(src)
        val name = pt.find("John Smith", true).single().single()
        val accountBefore = pt.find("account", true).single().single()
        val mark = Rect(name.left - 0.004f, name.top - 0.006f, name.right + 0.004f, name.bottom + 0.008f)

        val out = f("letter-redacted.pdf")
        val r = Redactor.apply(src, mapOf(0 to listOf(mark)), out)
        val t = text(out)
        assertFalse(t, t.contains("John") || t.contains("Smith"))
        assertTrue(t, t.contains("Customer:") && t.contains("account 4417") && t.contains("Thank you for your order"))
        assertTrue("exact, not a picture", r.flattened.isEmpty())
        assertEquals("link under the mark removed", 1, r.annots)
        assertTrue("underline removed", r.drawings >= 1)
        val accountAfter = page(out).find("account", true).single().single()
        assertEquals("the rest of the line did not move", accountBefore.left, accountAfter.left, 0.002f)
        PdfOps.load(out).use { d ->
            assertEquals(0, d.getPage(0).annotations.size)
            val title = d.documentInformation.title
            assertTrue("title repeating the name removed: $title", title == null || !title.contains("Smith"))
            assertEquals("unrelated metadata kept", "Orders", d.documentInformation.subject)
        }
        // nothing of the name anywhere in the file's bytes either (content streams are compressed, so
        // check by re-reading every string the text extractor can see, done above, and the raw file here)
        assertFalse(anyStreamContains(out, "Smith"))
    }

    /** An image partly under a mark keeps its uncovered pixels and loses the covered ones. */
    @Test fun imagePartlyUnderMarkLosesOnlyTheCoveredPixels() {
        val src = f("photo.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val img = LosslessFactory.createFromImage(d, bmp)
            PDPageContentStream(d, p).use { cs -> cs.drawImage(img, 100f, 500f, 200f, 100f) }
            d.save(src)
        }
        // left half of the image, in displayed page coordinates (A4 = 595 x 842)
        val mark = Rect(100f / 595f, (842f - 600f) / 842f, 200f / 595f, (842f - 500f) / 842f)
        val out = f("photo-redacted.pdf")
        val r = Redactor.apply(src, mapOf(0 to listOf(mark)), out)
        assertEquals(1, r.images)
        assertTrue("exact, not a picture: flattened ${r.flattened}", r.flattened.isEmpty())
        PdfOps.load(out).use { d ->
            val res = d.getPage(0).resources
            val images = res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>()
            assertEquals("only the edited image is left on the page", 1, images.size)
            val px = images.single().image
            val covered = px.getPixel(20, 50); val open = px.getPixel(180, 50)
            assertTrue("covered pixels painted over (${px.width}x${px.height}, ${Integer.toHexString(covered)}) ${dump(d)}", Color.red(covered) < 40)
            assertTrue("uncovered pixels kept", Color.red(open) > 200 && Color.green(open) < 40)
        }
    }

    /** Text grouped in a form XObject is redacted exactly: the group is unpacked into the page. */
    @Test fun textInsideAGroupIsRedactedAndItsNeighboursKept() {
        val src = f("group.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            val form = PDFormXObject(d)
            form.bBox = PDRectangle(0f, 0f, 400f, 100f)
            form.resources = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
            form.cosObject.createOutputStream().use {
                it.write("BT /F1 14 Tf 10 60 Td (Secret code 1234) Tj 0 -30 Td (Public note) Tj ET".toByteArray())
            }
            PDPageContentStream(d, p).use { cs ->
                cs.saveGraphicsState(); cs.transform(Matrix.getTranslateInstance(60f, 600f)); cs.drawForm(form); cs.restoreGraphicsState()
            }
            d.save(src)
        }
        val code = page(src).find("1234", true).single().single()
        val out = f("group-redacted.pdf")
        val r = Redactor.apply(src, mapOf(0 to listOf(code.grow(0.004f, 0.006f, 0.006f))), out)
        val t = text(out)
        assertFalse(t, t.contains("1234"))
        assertTrue(t, t.contains("Secret code") && t.contains("Public note"))
        assertTrue("exact, not a picture", r.flattened.isEmpty())
        assertFalse("the original group is not left in the file: ${PdfOps.load(out).use { dump(it) }} ${where(out, "1234")}", anyStreamContains(out, "1234"))
    }

    /** Invisible text (an OCR layer) under a mark is removed too. */
    @Test fun invisibleOcrTextUnderMarkIsRemoved() {
        val src = f("ocr.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setRenderingMode(RenderingMode.NEITHER); cs.setFont(PDType1Font.HELVETICA, 16f)
                cs.newLineAtOffset(60f, 500f); cs.showText("Passport 98765 issued"); cs.endText()
            }
            d.save(src)
        }
        val num = page(src).find("98765", true).single().single()
        val out = f("ocr-redacted.pdf")
        Redactor.apply(src, mapOf(0 to listOf(num.grow(0.003f, 0.005f, 0.005f))), out)
        val t = text(out)
        assertFalse(t, t.contains("98765"))
        assertTrue(t, t.contains("Passport") && t.contains("issued"))
    }

    private fun dump(d: PDDocument): String {
        val p = d.getPage(0)
        val content = p.contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }.take(600)
        return "xobjects=${p.resources.xObjectNames.toList()} content=[$content]"
    }

    private fun where(file: File, s: String): String = PdfOps.load(file).use { d ->
        d.document.objects.filter { o ->
            val b = o.`object`
            b is com.tom_roush.pdfbox.cos.COSStream && runCatching { b.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1).contains(s) } }.getOrDefault(false)
        }.joinToString { o -> "${o.objectNumber}: " + (o.`object` as com.tom_roush.pdfbox.cos.COSStream).let { st -> st.keySet().joinToString(",") { it.name } + " " + st.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1).take(200) } } }
    }

    /** Whether any object in the file (streams decoded) still holds [s]. */
    private fun anyStreamContains(file: File, s: String): Boolean = PdfOps.load(file).use { d ->
        d.document.objects.any { o ->
            when (val b = o.`object`) {
                is com.tom_roush.pdfbox.cos.COSStream -> runCatching { b.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1).contains(s) } }.getOrDefault(false)
                is com.tom_roush.pdfbox.cos.COSString -> b.string.contains(s)
                is com.tom_roush.pdfbox.cos.COSDictionary -> b.values.any { it is com.tom_roush.pdfbox.cos.COSString && it.string.contains(s) }
                else -> false
            }
        }
    }

    @Suppress("unused") private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
