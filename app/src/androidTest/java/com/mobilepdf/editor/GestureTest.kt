package com.mobilepdf.editor

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mobilepdf.editor.util.outDir
import com.mobilepdf.editor.util.uriFor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real two-finger gestures on the running app (UI Automator injects true multi-touch). */
@RunWith(AndroidJUnit4::class)
class GestureTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private fun openSample(): File {
        File(ctx.filesDir, "drafts").deleteRecursively() // no "restore unsaved work?" from an earlier run
        // the UI is found by its English labels: whatever language was chosen before, test in English
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putString("lang", "en").commit()
        com.mobilepdf.editor.ui.I18n.lang = "en"
        val f = File(ctx.outDir(), "pinch-test.pdf")
        PDDocument().use { d ->
            repeat(3) { i ->
                val p = PDPage(PDRectangle.A4); d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    // a light grid, so any zoomed-in view shows where it is
                    cs.setStrokingColor(0.75f, 0.8f, 0.9f); cs.setLineWidth(0.5f)
                    for (x in 0..595 step 25) { cs.moveTo(x.toFloat(), 0f); cs.lineTo(x.toFloat(), 842f) }
                    for (y in 0..842 step 25) { cs.moveTo(0f, y.toFloat()); cs.lineTo(595f, y.toFloat()) }
                    cs.stroke()
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f); cs.newLineAtOffset(60f, 760f)
                    cs.showText("Pinch test page ${i + 1}"); cs.endText()
                    // a target in the middle of the page: zooming there must keep it on screen
                    cs.setNonStrokingColor(0.72f, 0.01f, 0.16f)
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 40f); cs.newLineAtOffset(170f, 410f)
                    cs.showText("CENTER"); cs.endText()
                }
            }
            d.save(f)
        }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(ctx.uriFor(f), "application/pdf")
            .setClassName(ctx, "com.mobilepdf.editor.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
        assertTrue("viewer opened", device.wait(Until.hasObject(By.textContains("/ 3")), 15_000))
        return f
    }

    /** The page list: the biggest scrollable thing on screen (the tool bar is scrollable too). */
    private fun pageArea() = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }!!

    private fun zoomPercent(): Int? =
        device.findObject(By.textContains("%"))?.text?.let { Regex("(\\d+)%").find(it)?.groupValues?.get(1)?.toInt() }

    @Test fun pinchOpenZoomsInAndPinchCloseZoomsBackOut() {
        openSample()
        val pages = pageArea()
        assertNotNull("page list", pages)

        pages.pinchOpen(0.35f, 500)
        device.wait(Until.hasObject(By.textContains("%")), 3_000)
        val zoomed = zoomPercent()
        assertNotNull("zoom level shown after pinching open", zoomed)
        assertTrue("zoomed in ($zoomed%)", zoomed!! > 115)
        Thread.sleep(2_500) // time for a screenshot of the zoomed page

        pageArea().pinchClose(0.9f, 600)
        Thread.sleep(1_000)
        val after = zoomPercent()
        assertTrue("zoomed back out (was $zoomed%, now ${after ?: 100}%)", (after ?: 100) < zoomed)
    }

    /** Screen centre of the red "CENTER" word, found in a screenshot. */
    private fun redWordCenter(): android.graphics.Point? {
        val f = File(ctx.cacheDir, "shot-red.png")
        device.takeScreenshot(f)
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path)
        var sx = 0L; var sy = 0L; var n = 0
        for (y in 0 until bmp.height step 3) for (x in 0 until bmp.width step 3) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) > 150 && android.graphics.Color.green(c) < 40 && android.graphics.Color.blue(c) < 70) { sx += x; sy += y; n++ }
        }
        return if (n < 20) null else android.graphics.Point((sx / n).toInt(), (sy / n).toInt())
    }

    /**
     * Edit text after a pinch-zoom: a tap must still find the word under the finger (the tap handler used
     * the page size from before the zoom, so taps landed elsewhere on the page).
     */
    @Test fun editTextTapWorksAfterPinchZoom() {
        openSample()
        device.findObject(By.text("Edit text")).click()
        Thread.sleep(1_000)
        val cx = device.displayWidth / 2; val cy = device.displayHeight / 2
        device.findObject(androidx.test.uiautomator.UiSelector().textContains("/ 3")).performTwoPointerGesture(
            android.graphics.Point(cx - 60, cy - 40), android.graphics.Point(cx + 60, cy + 40),
            android.graphics.Point(cx - 300, cy - 200), android.graphics.Point(cx + 300, cy + 200), 40,
        )
        assertTrue("zoomed", device.wait(Until.hasObject(By.textContains("%")), 3_000))
        Thread.sleep(1_500)
        val at = redWordCenter()
        assertNotNull("CENTER visible after zoom", at)
        device.click(at!!.x, at.y)
        assertTrue("edit dialog for the tapped word", device.wait(Until.hasObject(By.text("CENTER")), 4_000))
        device.findObject(By.text("Cancel"))?.click()
    }

    /** Width of the dashed selection frame, measured from a screenshot (blue dashes, colour 0xFF1E88E5). */
    private fun signatureFrameWidth(): Int {
        val f = File(ctx.cacheDir, "shot.png")
        device.takeScreenshot(f)
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path)
        var minX = Int.MAX_VALUE; var maxX = -1
        for (y in bmp.height / 5 until bmp.height * 4 / 5 step 2) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.blue(c) > 200 && android.graphics.Color.red(c) < 60 && android.graphics.Color.green(c) in 110..160) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
            }
        }
        return if (maxX < 0) 0 else maxX - minX
    }

    /** With a signature selected, a pinch resizes the signature and leaves the page zoom alone. */
    @Test fun pinchResizesTheSelectedSignatureNotThePage() {
        openSample()
        device.findObject(By.text("Sign")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Draw your signature")), 5_000))
        val pad = device.findObject(By.text("Sign here with your finger")).parent
        val b = pad.visibleBounds
        device.swipe(b.left + b.width() / 5, b.centerY(), b.right - b.width() / 5, b.centerY() - b.height() / 4, 20)
        device.findObject(By.text("Done")).click()
        assertTrue("placed and selected", device.wait(Until.hasObject(By.textContains("pinch to resize")), 5_000))
        Thread.sleep(1_500) // screenshot: before
        val before = signatureFrameWidth()
        // two real fingers on the page itself (the page list is not "scrollable" while an item is selected,
        // so it can't be found that way): start close together mid-screen, move apart horizontally
        val cx = device.displayWidth / 2; val cy = device.displayHeight / 2
        device.findObject(androidx.test.uiautomator.UiSelector().textContains("/ 3")).performTwoPointerGesture(
            android.graphics.Point(cx - 60, cy), android.graphics.Point(cx + 60, cy),
            android.graphics.Point(cx - 330, cy), android.graphics.Point(cx + 330, cy), 40,
        )
        Thread.sleep(500)
        assertTrue("the page itself did not zoom", device.findObject(By.textContains("%")) == null)
        val after = signatureFrameWidth()
        assertTrue("signature grew ($before -> $after px)", after > before * 1.3)
        Thread.sleep(2_000) // screenshot: after
    }
}
