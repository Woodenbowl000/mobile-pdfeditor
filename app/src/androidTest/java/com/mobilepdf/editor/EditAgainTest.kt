package com.mobilepdf.editor

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.util.outDir
import com.mobilepdf.editor.util.uriFor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Edit a line, then tap it again: the dialog must show the edited text (not the original), and editing
 * again must replace it, not bring the old line back. (The tap handler used to keep the text of the file
 * as it was when the tool was picked.)
 */
@RunWith(AndroidJUnit4::class)
class EditAgainTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    /** Screen centre of the red text, found in a screenshot. */
    private fun redText(): android.graphics.Point? {
        val f = File(ctx.cacheDir, "shot-edit.png")
        device.takeScreenshot(f)
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path)
        var sx = 0L; var sy = 0L; var n = 0
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) > 150 && android.graphics.Color.green(c) < 60 && android.graphics.Color.blue(c) < 60) { sx += x; sy += y; n++ }
        }
        return if (n < 10) null else android.graphics.Point((sx / n).toInt(), (sy / n).toInt())
    }

    private fun field() = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 6_000)

    @Test fun editingTheSameLineTwiceUsesTheEditedText() {
        File(ctx.filesDir, "drafts").deleteRecursively()
        // the UI is found by its English labels: whatever language was chosen before, test in English
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putString("lang", "en").commit()
        com.mobilepdf.editor.ui.I18n.lang = "en"
        val f = File(ctx.outDir(), "edit-again.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.setNonStrokingColor(0.85f, 0.05f, 0.05f)
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 28f); cs.newLineAtOffset(60f, 600f)
                cs.showText("Total due 120"); cs.endText()
            }
            d.save(f)
        }
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(ctx.uriFor(f), "application/pdf").setClassName(ctx, "com.mobilepdf.editor.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue("viewer opened", device.wait(Until.hasObject(By.textContains("/ 1")), 15_000))
        device.findObject(By.text("Edit text")).click()
        Thread.sleep(1_500)

        // first edit
        val at = redText(); assertNotNull("red line visible", at)
        device.click(at!!.x, at.y)
        val first = field(); assertNotNull("edit dialog", first)
        assertEquals("Total due 120", first.text)
        first.text = "Total due 95"
        device.findObject(By.text("Replace")).click()
        device.wait(Until.gone(By.clazz("android.widget.EditText")), 5_000)
        Thread.sleep(2_500) // edit applied, page re-rendered

        // tap the same line again: the dialog shows the EDITED text
        val at2 = redText(); assertNotNull("edited line visible", at2)
        device.click(at2!!.x, at2.y)
        val second = field(); assertNotNull("edit dialog again", second)
        assertEquals("the dialog shows what the line says now", "Total due 95", second.text)
        second.text = "Total due 95 paid"
        device.findObject(By.text("Replace")).click()
        device.wait(Until.gone(By.clazz("android.widget.EditText")), 5_000)
        Thread.sleep(2_500)

        // and the page holds exactly the new line: no old text, nothing doubled
        val work = File(ctx.cacheDir, "work").listFiles()!!.filter { it.extension == "pdf" }.maxByOrNull { it.lastModified() }!!
        val txt = File(ctx.cacheDir, "edit-again.txt").also { PdfOps.extractText(work, it) }.readText().trim()
        assertEquals("Total due 95 paid", txt)
        assertFalse(txt.contains("120"))
    }
}
