package com.mobilepdf.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilepdf.editor.pdf.Compare
import com.mobilepdf.editor.pdf.DigitalSign
import com.mobilepdf.editor.pdf.Forms
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.pdf.Redactor
import com.mobilepdf.editor.pdf.Sanitizer
import com.mobilepdf.editor.pdf.TextEditor
import com.mobilepdf.editor.pdf.TextIndexer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The tools on real-world files (an arXiv paper: LaTeX Type 1 subset fonts, ligatures; the IRS W-9:
 * a government AcroForm). Put the PDFs in androidTest/assets/real/ to run; skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class RealWorldTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(ctx.cacheDir, "real").apply { deleteRecursively(); mkdirs() } }

    private fun asset(name: String): File {
        val ok = runCatching { inst.context.assets.open("real/$name").close() }.isSuccess
        assumeTrue("asset real/$name not present", ok)
        return File(dir, name).also { f -> inst.context.assets.open("real/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }
    }

    private fun text(f: File) = File(dir, f.name + ".txt").also { PdfOps.extractText(f, it) }.readText()
    private fun pageText(f: File, p: Int) = TextIndexer(f).use { runBlocking { it.page(p) } }

    @Test fun paperEditText() {
        val src = asset("paper.pdf")
        val runs = pageText(src, 0).runs
        assertTrue("runs found: ${runs.size}", runs.size > 20)
        // the title: the biggest text on page 1
        val title = runs.maxByOrNull { it.size }!!
        val out = File(dir, "paper-edit.pdf")
        val r = TextEditor.replaceLine(src, 0, title.rect, "Attention Is Still All You Need", out)
        val after = pageText(out, 0)
        assertTrue("new title present: ${title.text} -> ${r.fontName}", after.text.contains("Attention Is Still All You Need"))
        assertEquals("page count kept", PdfOps.load(src).use { it.numberOfPages }, PdfOps.load(out).use { it.numberOfPages })
    }

    @Test fun paperAbstractIsOneParagraph() {
        val src = asset("paper.pdf")
        val pt = pageText(src, 0)
        val line = pt.runs.first { it.text.contains("Experiments on two machine") }
        val para = pt.paragraphAt(line.rect.center)
        val dump = pt.runs.filter { it.rect.top > line.rect.top - 0.15f && it.rect.top < line.rect.top + 0.15f }
            .joinToString(" ## ") { "%.4f-%.4f x%.3f-%.3f s%.4f %s | %s".format(it.rect.top, it.rect.bottom, it.rect.left, it.rect.right, it.size, it.font, it.text.take(30)) }
        assertTrue("found ${para?.size}: ${para?.joinToString(" ## ") { it.text.take(40) }} --- $dump", (para?.size ?: 0) >= 12)
        assertTrue(pt.paragraphText(para!!).contains("English-to-German"))
    }

    @Test fun paperRedactWordEverywhereOnPage() {
        val src = asset("paper.pdf")
        val pt = pageText(src, 0)
        val hits = pt.find("Transformer", true)
        assertTrue("matches: ${hits.size}", hits.isNotEmpty())
        val marks = hits.flatten().map { r ->
            val h = r.height
            androidx.compose.ui.geometry.Rect(r.left - h * 0.06f, r.top - h * 0.25f, r.right + h * 0.06f, r.bottom + h * 0.3f)
        }
        val out = File(dir, "paper-redacted.pdf")
        val rep = Redactor.apply(src, mapOf(0 to marks), out)
        val after = pageText(out, 0).text
        assertFalse("no 'Transformer' left on page 1", after.contains("Transformer"))
        assertTrue("the rest stays: ${after.take(200)}", after.contains("Abstract") || after.contains("attention"))
        assertTrue("redacted exactly, not as a picture: ${rep.flattened}", rep.flattened.isEmpty())
        // page 2 untouched
        assertEquals(pageText(src, 1).text, pageText(out, 1).text)
    }

    @Test fun paperCompareSanitizeSign() {
        val src = asset("paper.pdf")
        assertTrue("same file: no differences", Compare.compare(src, src).changedPages.isEmpty())
        val scan = Sanitizer.scan(src)
        val clean = File(dir, "paper-clean.pdf")
        Sanitizer.remove(src, setOf(Sanitizer.Hidden.METADATA, Sanitizer.Hidden.LINKS_SCRIPTS), clean)
        assertTrue("metadata gone: $scan", Sanitizer.scan(clean)[Sanitizer.Hidden.METADATA] == 0)
        val t = text(clean)
        assertTrue(t.contains("Attention Is All You Need"))
        val s = DigitalSign.createOwn("Real World Test")
        try {
            val signed = File(dir, "paper-signed.pdf")
            DigitalSign.sign(clean, signed, s, null, null)
            assertEquals(DigitalSign.Status.VALID, DigitalSign.verify(signed).single().status)
        } finally { DigitalSign.forget() }
    }

    @Test fun w9FormFillsAndSaves() {
        val src = asset("fw9.pdf")
        val fields = PdfOps.load(src).use { Forms.read(it) }
        assertTrue("fields: ${fields.size}", fields.size > 10)
        val first = fields.first { it.type == com.mobilepdf.editor.pdf.FieldType.TEXT && !it.readOnly }
        val out = File(dir, "fw9-filled.pdf")
        Forms.fill(src, mapOf(first.name to "Sara Malik"), flatten = false, out = out)
        assertEquals("Sara Malik", PdfOps.load(out).use { Forms.read(it) }.first { it.name == first.name }.value)
        val flat = File(dir, "fw9-flat.pdf")
        Forms.fill(src, mapOf(first.name to "Sara Malik"), flatten = true, out = flat)
        assertTrue(text(flat).contains("Sara Malik"))
    }
}
