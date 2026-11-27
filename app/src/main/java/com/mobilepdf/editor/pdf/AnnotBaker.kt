package com.mobilepdf.editor.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.annot.MarkKind
import com.mobilepdf.editor.annot.ShapeKind
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Writes on-screen annotations into the PDF as page content (appended, so the original page content is
 * untouched). They are visible in every PDF viewer, but become part of the page: they can no longer be
 * edited as separate objects once saved. (Roadmap: emit real /Annots with appearance streams.)
 */
object AnnotBaker {

    fun bake(src: File, annots: Map<Int, List<Annot>>, out: File) {
        PdfOps.load(src).use { d ->
            // each chosen font is looked up (an uploaded one embedded) once per document
            val fonts = HashMap<com.mobilepdf.editor.annot.FontChoice, com.tom_roush.pdfbox.pdmodel.font.PDFont?>()
            for ((pageIndex, list) in annots) {
                if (list.isEmpty() || pageIndex >= d.numberOfPages) continue
                val page = d.getPage(pageIndex)
                // Edit text: take the old words out of the page first; cover only what could not be removed
                val replaces = list.filterIsInstance<Annot.Replace>()
                val needsCover = HashSet<Long>()
                if (replaces.isNotEmpty()) {
                    val keep = try {
                        TextRemover.remove(d, pageIndex, replaces.map { com.mobilepdf.editor.annot.coverRect(it) })
                    } catch (e: Exception) {
                        android.util.Log.w("AnnotBaker", "text removal failed, covering instead", e)
                        BooleanArray(replaces.size) { true }
                    }
                    replaces.forEachIndexed { i, r -> if (keep[i]) needsCover += r.id }
                }
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    val m = PageMap(page)
                    for (a in list) draw(d, cs, m, a, needsCover, fonts)
                }
                // sticky notes: real PDF comments, so any PDF app shows and lists them
                for (n in list.filterIsInstance<Annot.Note>()) page.annotations.add(noteAnnotation(d, PageMap(page), n))
            }
            d.save(out)
        }
    }

    private fun noteAnnotation(d: com.tom_roush.pdfbox.pdmodel.PDDocument, m: PageMap, n: Annot.Note): com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText {
        val size = 22f
        val x = m.x(n.pos.x, n.pos.y); val y = m.y(n.pos.x, n.pos.y)
        val (r, g, b) = rgb(n.color)
        val a = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
        a.rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(x, y - size, size, size)
        a.contents = n.text
        a.name = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText.NAME_COMMENT
        a.color = com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(floatArrayOf(r / 255f, g / 255f, b / 255f), com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE)
        a.titlePopup = "Note"
        a.isPrinted = true
        a.setModifiedDate(java.util.Calendar.getInstance())
        // an appearance of its own: some viewers draw nothing for a comment without one
        val ap = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(d)
        ap.bBox = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(size, size)
        ap.resources = com.tom_roush.pdfbox.pdmodel.PDResources()
        ap.cosObject.createOutputStream().use { o ->
            val f = { v: Int -> "%.3f".format(java.util.Locale.US, v / 255f) }
            o.write(("${f(r)} ${f(g)} ${f(b)} rg 0.2 0.2 0.2 RG 0.8 w 1 1 20 20 re B " +
                "0.2 g 5 15 m 17 15 l 5 11 m 17 11 l 5 7 m 13 7 l S").toByteArray())
        }
        a.appearance = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary().apply { setNormalAppearance(ap) }
        return a
    }

    /** Maps displayed normalized coordinates (top-left origin) to unrotated PDF user space. */
    private class PageMap(page: PDPage) {
        private val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val w = box.width
        val h = box.height
        val dispW = if (rot == 90 || rot == 270) h else w
        val dispH = if (rot == 90 || rot == 270) w else h
        val aspect = dispH / dispW
        fun x(u: Float, v: Float) = box.lowerLeftX + when (rot) { 0 -> u * w; 90 -> v * w; 180 -> (1 - u) * w; else -> (1 - v) * w }
        fun y(u: Float, v: Float) = box.lowerLeftY + when (rot) { 0 -> (1 - v) * h; 90 -> u * h; 180 -> v * h; else -> (1 - u) * h }
    }

    private fun rgb(c: Int) = Triple((c shr 16) and 255, (c shr 8) and 255, c and 255)

    private fun PDPageContentStream.strokeColor(c: Int) { val (r, g, b) = rgb(c); setStrokingColor(r / 255f, g / 255f, b / 255f) }
    private fun PDPageContentStream.fillColor(c: Int) { val (r, g, b) = rgb(c); setNonStrokingColor(r / 255f, g / 255f, b / 255f) }

    private fun gs(alpha: Float, multiply: Boolean) = PDExtendedGraphicsState().apply {
        setStrokingAlphaConstant(alpha); setNonStrokingAlphaConstant(alpha)
        if (multiply) blendMode = BlendMode.MULTIPLY
    }

    private fun PDPageContentStream.polyline(m: PageMap, pts: List<Offset>, closed: Boolean = false) {
        if (pts.isEmpty()) return
        moveTo(m.x(pts[0].x, pts[0].y), m.y(pts[0].x, pts[0].y))
        if (pts.size == 1) lineTo(m.x(pts[0].x, pts[0].y) + 0.01f, m.y(pts[0].x, pts[0].y))
        for (i in 1 until pts.size) lineTo(m.x(pts[i].x, pts[i].y), m.y(pts[i].x, pts[i].y))
        if (closed) closePath()
    }

    private fun draw(
        doc: com.tom_roush.pdfbox.pdmodel.PDDocument, cs: PDPageContentStream, m: PageMap, a: Annot, needsCover: Set<Long>,
        fonts: MutableMap<com.mobilepdf.editor.annot.FontChoice, com.tom_roush.pdfbox.pdmodel.font.PDFont?>,
    ) {
        cs.saveGraphicsState()
        try {
            when (a) {
                is Annot.Ink -> {
                    cs.setGraphicsStateParameters(if (a.marker) gs(0.38f, true) else gs(1f, false))
                    cs.strokeColor(a.color); cs.setLineWidth(a.width * m.dispW)
                    cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                    cs.polyline(m, a.pts); cs.stroke()
                }
                is Annot.Shape -> {
                    cs.setGraphicsStateParameters(gs(1f, false))
                    cs.strokeColor(a.color); cs.setLineWidth(a.width * m.dispW)
                    cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                    when (a.kind) {
                        ShapeKind.RECT -> cs.polyline(m, listOf(a.a, Offset(a.b.x, a.a.y), a.b, Offset(a.a.x, a.b.y)), true)
                        ShapeKind.OVAL -> {
                            val cx = (a.a.x + a.b.x) / 2; val cy = (a.a.y + a.b.y) / 2
                            val rx = (a.b.x - a.a.x) / 2; val ry = (a.b.y - a.a.y) / 2
                            cs.polyline(m, List(49) { i ->
                                val t = i * 2 * Math.PI / 48
                                Offset(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat())
                            }, true)
                        }
                        ShapeKind.LINE -> cs.polyline(m, listOf(a.a, a.b))
                        ShapeKind.CLOUD -> cs.polyline(m, com.mobilepdf.editor.annot.cloudOutline(a.a, a.b, m.aspect, a.width), true)
                        ShapeKind.TICK, ShapeKind.CROSS -> for (st in com.mobilepdf.editor.annot.markStrokes(a.kind, a.a, a.b)) cs.polyline(m, st)
                        ShapeKind.DOT -> {
                            val cx = (a.a.x + a.b.x) / 2; val cy = (a.a.y + a.b.y) / 2
                            val rx = kotlin.math.abs(a.b.x - a.a.x) * 0.3f; val ry = kotlin.math.abs(a.b.y - a.a.y) * 0.3f
                            cs.fillColor(a.color)
                            cs.polyline(m, List(33) { i -> val t = i * 2 * Math.PI / 32; Offset(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat()) }, true)
                            cs.fill()
                        }
                        ShapeKind.AREA -> {
                            cs.setGraphicsStateParameters(gs(0.38f, true))
                            cs.fillColor(a.color)
                            cs.polyline(m, listOf(a.a, Offset(a.b.x, a.a.y), a.b, Offset(a.a.x, a.b.y)), true)
                            cs.fill()
                        }
                        ShapeKind.ARROW -> {
                            cs.polyline(m, listOf(a.a, a.b)); cs.stroke()
                            // head drawn in isotropic (page-width) units, then converted back
                            val ang = atan2((a.b.y - a.a.y) * m.aspect, a.b.x - a.a.x)
                            val len = 0.035f + a.width * 2
                            fun tip(d: Double) = Offset(
                                a.b.x - len * cos(ang + d).toFloat(),
                                a.b.y - len * sin(ang + d).toFloat() / m.aspect,
                            )
                            cs.polyline(m, listOf(tip(0.45), a.b, tip(-0.45)))
                        }
                    }
                    if (a.kind != ShapeKind.AREA && a.kind != ShapeKind.DOT) cs.stroke() // areas and dots are filled, not outlined
                }
                is Annot.Mark -> {
                    for (r in a.rects) when (a.kind) {
                        MarkKind.HIGHLIGHT -> {
                            cs.setGraphicsStateParameters(gs(0.45f, true))
                            cs.fillColor(a.color)
                            cs.polyline(m, corners(r), true); cs.fill()
                        }
                        MarkKind.UNDERLINE, MarkKind.STRIKE -> {
                            val y = if (a.kind == MarkKind.UNDERLINE) r.bottom else (r.top + r.bottom) / 2
                            cs.setGraphicsStateParameters(gs(1f, false))
                            cs.strokeColor(a.color); cs.setLineWidth(((r.bottom - r.top) * m.dispH * 0.07f).coerceAtLeast(0.7f))
                            cs.polyline(m, listOf(Offset(r.left, y), Offset(r.right, y))); cs.stroke()
                        }
                    }
                }
                is Annot.Text -> {
                    // the chosen font (copied from the page, or uploaded); letters it lacks use the look-alike
                    val primary = fonts.getOrPut(a.font) { TextFonts.resolve(doc, a.font) }
                    val fallback = TextFonts.standard(a.font)
                    val size = a.size * m.dispW
                    cs.fillColor(a.color)
                    a.text.split('\n').forEachIndexed { i, line ->
                        if (line.isEmpty()) return@forEachIndexed
                        // baseline of line i, measured from the top-left of the text box
                        val v = a.pos.y + (a.size * (0.95f + 1.2f * i)) / m.aspect
                        cs.beginText()
                        cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(m.rot.toDouble()), m.x(a.pos.x, v), m.y(a.pos.x, v)))
                        for ((f, s) in TextFonts.segments(line, primary, fallback)) { cs.setFont(f, size); cs.showText(s) }
                        cs.endText()
                    }
                }
                is Annot.Replace -> {
                    // 1. hide the old words  2. write the new ones on the same baseline
                    cs.setGraphicsStateParameters(gs(1f, false))
                    if (a.id in needsCover) {
                        cs.fillColor(a.cover)
                        cs.polyline(m, corners(com.mobilepdf.editor.annot.coverRect(a)), true); cs.fill()
                    }
                    val font = PDType1Font.HELVETICA
                    val safe = a.text.map { c -> if (c.code in 32..255) c else '?' }.joinToString("")
                    if (safe.isNotBlank()) {
                        cs.fillColor(a.color)
                        cs.beginText()
                        cs.setFont(font, a.size * m.dispW)
                        cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(m.rot.toDouble()), m.x(a.rect.left, a.rect.bottom), m.y(a.rect.left, a.rect.bottom)))
                        cs.showText(safe)
                        cs.endText()
                    }
                }
                is Annot.Redact, is Annot.Note -> Unit // marks are applied separately; notes become PDF comments
                is Annot.Seal -> {
                    // double frame, then the words centred, as large as fit (in the page's displayed orientation)
                    val r = a.rect
                    val wPt = r.width * m.dispW; val hPt = r.height * m.dispH
                    val o = Offset(r.left, r.bottom)
                    val ox = m.x(o.x, o.y); val oy = m.y(o.x, o.y)
                    val ux = (m.x(r.right, r.bottom) - ox) / wPt; val uy = (m.y(r.right, r.bottom) - oy) / wPt
                    val vx = (m.x(r.left, r.top) - ox) / hPt; val vy = (m.y(r.left, r.top) - oy) / hPt
                    cs.transform(Matrix(ux, uy, vx, vy, ox, oy)) // now 1 unit = 1 pt along the stamp, origin bottom-left
                    cs.setGraphicsStateParameters(gs(0.9f, false))
                    cs.strokeColor(a.color)
                    val lw = (hPt * 0.06f).coerceIn(0.8f, 4f)
                    cs.setLineWidth(lw); cs.addRect(lw / 2, lw / 2, wPt - lw, hPt - lw); cs.stroke()
                    val inset = lw * 2.6f
                    cs.setLineWidth(lw * 0.45f); cs.addRect(inset, inset, wPt - 2 * inset, hPt - 2 * inset); cs.stroke()
                    val font = PDType1Font.HELVETICA_BOLD
                    val text = a.text.map { c -> if (c.code in 32..255) c else '?' }.joinToString("")
                    val tw = font.getStringWidth(text) / 1000f
                    val size = minOf(hPt * 0.5f, (wPt - 4 * inset) / tw.coerceAtLeast(0.1f))
                    cs.fillColor(a.color)
                    cs.beginText(); cs.setFont(font, size)
                    cs.newLineAtOffset((wPt - tw * size) / 2, (hPt - size * 0.7f) / 2)
                    cs.showText(text); cs.endText()
                }
                is Annot.Stamp -> if (a.image != null) {
                    val bmp = android.graphics.BitmapFactory.decodeFile(a.image)
                    if (bmp != null) {
                        val img = if (a.image.endsWith(".jpg") && !bmp.hasAlpha()) com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, bmp, 0.9f)
                            else com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bmp)
                        val ox = m.x(a.rect.left, a.rect.bottom); val oy = m.y(a.rect.left, a.rect.bottom)
                        val xx = m.x(a.rect.right, a.rect.bottom) - ox; val xy = m.y(a.rect.right, a.rect.bottom) - oy
                        val yx = m.x(a.rect.left, a.rect.top) - ox; val yy = m.y(a.rect.left, a.rect.top) - oy
                        cs.drawImage(img, Matrix(xx, xy, yx, yy, ox, oy))
                        bmp.recycle()
                    }
                } else {
                    cs.setGraphicsStateParameters(gs(1f, false))
                    cs.strokeColor(a.color); cs.setLineWidth((a.rect.width * m.dispW * 0.012f).coerceAtLeast(0.8f))
                    cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                    for (s in a.strokes) {
                        cs.polyline(m, s.map { Offset(a.rect.left + it.x * a.rect.width, a.rect.top + it.y * a.rect.height) })
                        cs.stroke()
                    }
                }
            }
        } finally {
            cs.restoreGraphicsState()
        }
    }

    private fun corners(r: Rect) = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
}
