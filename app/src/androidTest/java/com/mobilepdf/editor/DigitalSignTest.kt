package com.mobilepdf.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.pdf.AnnotBaker
import com.mobilepdf.editor.pdf.DigitalSign
import com.mobilepdf.editor.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DigitalSignTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(ctx.cacheDir, "dsig").apply { deleteRecursively(); mkdirs() } }
    @After fun tearDown() { DigitalSign.forget() }

    private fun contract(): File = File(dir, "contract.pdf").also { f ->
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(60f, 700f); cs.showText("I agree to pay 100 dollars."); cs.endText()
            }
            d.save(f)
        }
    }

    @Test fun signedDocumentVerifies() {
        val s = DigitalSign.createOwn("Ali Test")
        assertEquals("Ali Test", s.name)
        val out = File(dir, "signed.pdf")
        DigitalSign.sign(contract(), out, s, "I approve", "Karachi")
        val checks = DigitalSign.verify(out)
        assertEquals(1, checks.size)
        val c = checks.single()
        assertEquals(c.detail, DigitalSign.Status.VALID, c.status)
        assertEquals("Ali Test", c.signer)
        assertEquals("I approve", c.reason)
        assertTrue(c.selfSigned)
        // the signed text is untouched
        val t = File(dir, "t.txt").also { PdfOps.extractText(out, it) }.readText()
        assertTrue(t.contains("I agree to pay 100 dollars."))
    }

    @Test fun anEditAfterSigningIsReported() {
        val s = DigitalSign.createOwn("Ali Test")
        val signed = File(dir, "signed.pdf")
        DigitalSign.sign(contract(), signed, s, null, null)
        // a later incremental change: the signed revision is still intact, but more was added
        val changed = File(dir, "changed.pdf")
        PdfOps.load(signed).use { d ->
            d.documentInformation.title = "changed"
            d.documentInformation.cosObject.isNeedToBeUpdated = true
            d.documentCatalog.cosObject.isNeedToBeUpdated = true
            changed.outputStream().use { d.saveIncremental(it) }
        }
        assertEquals(DigitalSign.Status.MODIFIED_AFTER, DigitalSign.verify(changed).single().status)
        // the app's own edits rewrite the file: the signature no longer matches
        val rewritten = File(dir, "rewritten.pdf")
        AnnotBaker.bake(signed, mapOf(0 to listOf(Annot.Seal(1, 0, androidx.compose.ui.geometry.Rect(0.3f, 0.3f, 0.6f, 0.36f), "PAID"))), rewritten)
        val st = DigitalSign.verify(rewritten).single().status
        assertTrue("rewritten: $st", st != DigitalSign.Status.VALID)
    }

    @Test fun tamperedBytesMakeTheSignatureInvalid() {
        val s = DigitalSign.createOwn("Ali Test")
        val signed = File(dir, "signed.pdf")
        DigitalSign.sign(contract(), signed, s, null, null)
        val bytes = signed.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        // change a byte inside the signed range (the document ID in the first revision's trailer area)
        val i = text.indexOf("/Producer").takeIf { it > 0 } ?: text.indexOf("/Type /Catalog").takeIf { it > 0 } ?: text.indexOf("/Pages")
        bytes[i + 2] = (bytes[i + 2] + 1).toByte()
        val bad = File(dir, "bad.pdf").apply { writeBytes(bytes) }
        assertEquals(DigitalSign.Status.INVALID, DigitalSign.verify(bad).single().status)
    }
}
