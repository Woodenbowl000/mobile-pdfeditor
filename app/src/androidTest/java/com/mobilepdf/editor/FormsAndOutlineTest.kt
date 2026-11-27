package com.mobilepdf.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilepdf.editor.pdf.Bookmark
import com.mobilepdf.editor.pdf.FormCalc
import com.mobilepdf.editor.pdf.Forms
import com.mobilepdf.editor.pdf.Navigation
import com.mobilepdf.editor.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FormsAndOutlineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(ctx.cacheDir, "formcalc").apply { deleteRecursively(); mkdirs() } }

    /** A form whose total is computed by a script: the app reads it, computes it, and saves the result. */
    @Test fun calculatedTotalIsReadComputedAndSaved() {
        val src = File(dir, "invoice.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 10 Tf 0 g"
            fun text(name: String, y: Float, calc: String? = null, fmt: String? = null): PDTextField {
                val f = PDTextField(form); f.partialName = name
                val w = f.widgets[0]; w.rectangle = PDRectangle(100f, y, 150f, 20f); w.page = p; p.annotations.add(w)
                form.fields.add(f)
                if (calc != null || fmt != null) {
                    val aa = COSDictionary()
                    calc?.let { aa.setItem(COSName.getPDFName("C"), PDActionJavaScript(it).cosObject) }
                    fmt?.let { aa.setItem(COSName.getPDFName("F"), PDActionJavaScript(it).cosObject) }
                    f.cosObject.setItem(COSName.AA, aa)
                }
                return f
            }
            text("Qty", 700f)
            text("Price", 670f, fmt = "AFNumber_Format(2, 0, 0, 0, \"$\", true);")
            val total = text(
                "Total", 640f,
                calc = "/** BVCALC Qty * Price EVCALC **/ event.value = AFMakeNumber(getField(\"Qty\").value)*AFMakeNumber(getField(\"Price\").value)",
                fmt = "AFNumber_Format(2, 0, 0, 0, \"$\", true);",
            )
            form.cosObject.setItem(COSName.getPDFName("CO"), COSArray().apply { add(total.cosObject) })
            d.save(src)
        }
        val fields = PdfOps.load(src).use { Forms.read(it) }
        val total = fields.single { it.name == "Total" }
        assertEquals("Qty * Price", total.calc?.expr)
        assertNotNull(total.format)
        assertEquals(0, total.calcOrder)

        val typed = mapOf("Qty" to "3", "Price" to "$12.50")
        val computed = FormCalc.recompute(fields) { typed[it.name] ?: it.value }
        assertEquals("$37.50", computed["Total"])

        val out = File(dir, "invoice-filled.pdf")
        Forms.fill(src, typed + computed, flatten = false, out = out)
        assertEquals("$37.50", PdfOps.load(out).use { Forms.read(it) }.single { it.name == "Total" }.value)
    }

    /** Edited bookmarks are written as the outline, nesting and pages included. */
    @Test fun editedBookmarksRoundTrip() {
        val src = File(dir, "book.pdf")
        PDDocument().use { d -> repeat(3) { d.addPage(PDPage(PDRectangle.A4)) }; d.save(src) }
        val items = listOf(Bookmark("Introduction", 0, 0), Bookmark("Background", 1, 1), Bookmark("Results", 0, 2))
        val out = File(dir, "book2.pdf")
        Navigation.setOutline(src, items, out)
        assertEquals(items, PdfOps.load(out).use { Navigation.outline(it) })
        val none = File(dir, "book3.pdf")
        Navigation.setOutline(out, emptyList(), none)
        assertEquals(emptyList<Bookmark>(), PdfOps.load(none).use { Navigation.outline(it) })
    }
}
