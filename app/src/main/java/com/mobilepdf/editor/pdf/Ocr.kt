package com.mobilepdf.editor.pdf

import androidx.compose.ui.geometry.Offset
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * Scan & OCR (PdfCraft's "Recognize text" → searchable image output): each recognised word is written
 * as invisible text (rendering mode 3) exactly over its pixels, so the page looks the same but can be
 * searched, selected, highlighted and edited. Recognition runs on the phone (ML Kit); nothing is uploaded.
 */
object Ocr {
    class Result(val recognized: Int, val skipped: Int, val words: Int)

    class Unavailable(msg: String) : IOException(msg)

    /**
     * Recognises text on every page of [src] that has no text yet, rendering pages from [view] (the same
     * document as shown on screen), and writes the result to [out].
     */
    suspend fun recognize(
        src: File, view: File, out: File, budget: DeviceBudget, onProgress: (page: Int, of: Int) -> Unit = { _, _ -> },
    ): Result {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var recognized = 0; var skipped = 0; var words = 0
        try {
            PdfOps.load(src).use { d ->
                PageRenderer(view, budget).use { r ->
                    val n = d.numberOfPages
                    for (i in 0 until n) {
                        onProgress(i + 1, n)
                        if (hasText(d, i)) { skipped++; continue }
                        val page = d.getPage(i)
                        val crop = page.cropBox
                        val rot = PageGeom.rotation(page)
                        val dispW = if (rot == 90 || rot == 270) crop.height else crop.width
                        val dispH = if (rot == 90 || rot == 270) crop.width else crop.height
                        // ~2400 px on the long side (1600 on budget phones): enough for body text
                        val longSide = if (budget.lowRam) 1600f else 2400f
                        val bmp = r.renderForExport(i, longSide / max(dispW, dispH))
                        val text = try {
                            Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
                        } catch (e: Exception) {
                            throw Unavailable(
                                if ((e.message ?: "").contains("download", true)) "The text-recognition model is still downloading. Try again in a minute."
                                else "Text recognition isn't available on this phone (it needs Google Play services).",
                            )
                        }
                        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
                        bmp.recycle()
                        val font = PDType1Font.HELVETICA
                        PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            for (block in text.textBlocks) for (line in block.lines) {
                                val lb = line.boundingBox ?: continue
                                // one size per line (median of its words would vary; the line box is steadier)
                                val linePts = lb.height() / bh * dispH
                                val size = (linePts / 0.93f).coerceIn(2f, 200f) // Helvetica ascent+descent ≈ 0.93 em
                                for (el in line.elements) {
                                    val b = el.boundingBox ?: continue
                                    val s = el.text.map { c -> if (c.code in 32..255) c else '?' }.joinToString("")
                                    if (s.isBlank()) continue
                                    val boxW = b.width() / bw * dispW
                                    val natural = font.getStringWidth(s) / 1000f * size
                                    if (natural <= 0f) continue
                                    // baseline sits about one descent (0.21 em) above the bottom of the box
                                    val baseline = Offset(b.left / bw, (lb.bottom / bh) - (0.21f * size / dispH))
                                    val (x, y) = PageGeom.toPdf(page, baseline)
                                    cs.beginText()
                                    cs.setRenderingMode(RenderingMode.NEITHER)
                                    cs.setFont(font, size)
                                    cs.setHorizontalScaling((boxW / natural * 100f).coerceIn(30f, 300f)) // fit the word's width
                                    cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(rot.toDouble()), x, y))
                                    cs.showText(s)
                                    cs.endText()
                                    words++
                                }
                            }
                        }
                        recognized++
                    }
                }
                d.save(out)
            }
        } finally {
            recognizer.close()
        }
        return Result(recognized, skipped, words)
    }

    /** A page that already has real text needs no OCR (PdfCraft: "skip pages that already have text"). */
    private fun hasText(d: com.tom_roush.pdfbox.pdmodel.PDDocument, i: Int): Boolean = runCatching {
        PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(d).any { it.isLetterOrDigit() }
    }.getOrDefault(false)
}
