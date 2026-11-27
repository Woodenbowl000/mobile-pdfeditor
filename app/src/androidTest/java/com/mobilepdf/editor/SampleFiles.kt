package com.mobilepdf.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a check: writes a realistic sample PDF into the app's external files dir for manual / screenshot
 * testing (the app can read its own external dir without any storage permission).
 */
@RunWith(AndroidJUnit4::class)
class SampleFiles {
    /** Stress file: 120 pages, each with a photo-like image and text (~10 MB). */
    @Test fun writeLargeSample() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "stress-120-pages.pdf")
        val bmp = android.graphics.Bitmap.createBitmap(1400, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        PDDocument().use { d ->
            for (i in 0 until 120) {
                // a different "photo" per page: gradient sky, circles, noise
                c.drawColor(android.graphics.Color.rgb(40 + i % 120, 90, 160 - i % 100))
                val r = java.util.Random(i.toLong())
                repeat(60) {
                    paint.color = android.graphics.Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256))
                    c.drawCircle(r.nextFloat() * 1400, r.nextFloat() * 1000, 20 + r.nextFloat() * 120, paint)
                }
                bmp.setHasAlpha(false)
                val img = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(d, bmp, 0.8f)
                val p = PDPage(PDRectangle.A4); d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.drawImage(img, 40f, 380f, 515f, 368f)
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 20f); cs.newLineAtOffset(40f, 790f)
                    cs.showText("Stress test - page ${i + 1} of 120"); cs.endText()
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 11f); cs.setLeading(15f); cs.newLineAtOffset(40f, 350f)
                    repeat(18) { cs.showText("Line ${it + 1}: scrolling through big image-heavy documents must stay smooth on budget phones."); cs.newLine() }
                    cs.endText()
                }
            }
            d.save(out)
        }
    }

    @Test fun writeSample() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "sample-report.pdf")
        PDDocument().use { d ->
            val outline = PDDocumentOutline(); d.documentCatalog.documentOutline = outline
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 0 Tf 0 g"
            val body = listOf(
                "Mobile PDF editors must run well on phones with 1 GB of memory.",
                "Pages are rendered one at a time by the system renderer, so memory",
                "stays flat however long the document is. Annotations are stored as",
                "vectors and written into the file only when you save. The invoice",
                "total due is listed in section 3; search for 'invoice' to find it.",
            )
            for (i in 0 until 8) {
                val p = PDPage(PDRectangle.A4)
                if (i == 5) p.rotation = 90
                d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.setNonStrokingColor(0.72f, 0.01f, 0.16f)
                    cs.addRect(0f, 800f, 595f, 42f); cs.fill()
                    cs.setNonStrokingColor(1f, 1f, 1f)
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 18f); cs.newLineAtOffset(40f, 814f)
                    cs.showText("Sample report  -  section ${i + 1}"); cs.endText()
                    cs.setNonStrokingColor(0.1f, 0.1f, 0.1f)
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 13f); cs.setLeading(20f); cs.newLineAtOffset(40f, 760f)
                    body.forEach { cs.showText(it); cs.newLine() }
                    cs.endText()
                    if (i == 0) {
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 13f); cs.newLineAtOffset(40f, 560f)
                        cs.showText("Full name:"); cs.endText()
                        cs.setNonStrokingColor(0.15f, 0.35f, 0.8f)
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 13f); cs.newLineAtOffset(40f, 500f)
                        cs.showText("Visit example.com (link)"); cs.endText()
                    }
                }
                outline.addLast(PDOutlineItem().apply {
                    title = "Section ${i + 1}" + if (i == 5) " (landscape)" else ""
                    destination = PDPageFitWidthDestination().apply { page = p }
                })
                if (i == 0) {
                    val tf = PDTextField(form); tf.partialName = "fullName"; tf.defaultAppearance = "/Helv 12 Tf 0 g"
                    form.fields.add(tf)
                    val w = tf.widgets[0]; w.rectangle = PDRectangle(120f, 552f, 300f, 24f); w.page = p
                    p.annotations.add(w)
                    p.annotations.add(PDAnnotationLink().apply {
                        rectangle = PDRectangle(40f, 495f, 170f, 18f)
                        action = PDActionURI().apply { uri = "https://example.com" }
                    })
                }
            }
            d.save(out)
        }
    }
}
