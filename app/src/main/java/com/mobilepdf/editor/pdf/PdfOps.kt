package com.mobilepdf.editor.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix as GMatrix
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.exifinterface.media.ExifInterface
import android.util.Log
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class NotAllowedException(msg: String) : IOException(msg)

/**
 * All PDF-modifying operations. Every function is blocking and writes a NEW file; the caller runs them
 * on Dispatchers.IO and decides what to do with the result. The user's original is never touched here.
 *
 * Memory: PdfBox is always opened in temp-file mode so big documents spill to disk, not the 1 GB heap.
 */
object PdfOps {
    private fun mem() = MemoryUsageSetting.setupTempFileOnly()

    fun load(file: File, password: String = ""): PDDocument = PDDocument.load(file, password, mem())

    // ------------------------------------------------------------------ open / repair / unlock

    /*
     * Encrypted files must NEVER reach the OS PdfRenderer: the pdfium inside Android 7 dereferences
     * null and kills the whole process (SIGSEGV) on AES-256 documents instead of throwing.
     * So: detect /Encrypt by scanning bytes (the trailer / xref-stream dictionary is never encrypted
     * or compressed), and decrypt with PdfBox into a working copy first.
     */

    /** Streams [input] to [out] and reports whether the bytes "/Encrypt" occurred anywhere. */
    fun copyDetectingEncryption(input: InputStream, out: File): Boolean {
        val needle = "/Encrypt".toByteArray(Charsets.ISO_8859_1)
        var matched = 0
        var found = false
        out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                if (!found) for (i in 0 until n) {
                    val b = buf[i]
                    matched = if (b == needle[matched]) matched + 1 else if (b == needle[0]) 1 else 0
                    if (matched == needle.size) { found = true; break }
                }
            }
        }
        return found
    }

    fun mentionsEncryption(file: File): Boolean =
        file.inputStream().use { copyDetectingEncryption(it, File(file.path + ".scan")) }.also { File(file.path + ".scan").delete() }

    /** What the opener is allowed to do; null from [unlock] means the password was wrong. */
    data class Access(val canModify: Boolean, val canCopy: Boolean)

    /**
     * Decrypts [src] into [out] with [password] ("" = try the empty user password, which is how
     * "restricted but openable" PDFs work). Returns the opener's permissions, or null if a password is needed / wrong.
     * Files that turn out not to be encrypted are simply copied.
     */
    fun unlock(src: File, password: String, out: File): Access? = try {
        load(src, password).use { d ->
            val ap = d.currentAccessPermission
            val access = if (d.isEncrypted) Access(ap.isOwnerPermission || ap.canModify(), ap.isOwnerPermission || ap.canExtractContent()) else Access(true, true)
            d.setAllSecurityToBeRemoved(true)
            d.save(out)
            access
        }
    } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        null
    }

    /**
     * Android's PdfRenderer (pdfium without FPDF_ANNOT) never draws annotation appearances: filled form
     * fields, comments and stamps from other apps would look missing. For display only, this writes a
     * copy where every visible annotation's normal appearance is drawn into the page content (PDF spec
     * algorithm 8.1: appearance BBox × Matrix fitted onto the annotation Rect) and the annotation removed.
     * Returns false (and writes nothing) when there is nothing to draw — the common, fast case.
     */
    fun makeDisplayCopy(src: File, out: File): Boolean {
        var drew = false
        load(src).use { d ->
            for (page in d.pages) {
                val annots = runCatching { page.annotations }.getOrNull() ?: continue
                val toDraw = annots.filter { a ->
                    a !is com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink &&
                        a.subtype != "Popup" && !a.isHidden && !a.isNoView &&
                        a.rectangle != null && runCatching { a.normalAppearanceStream }.getOrNull()?.bBox != null
                }
                if (toDraw.isEmpty()) continue
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in toDraw) {
                        val ap = a.normalAppearanceStream
                        val bbox = ap.bBox
                        val m = ap.matrix
                        // bbox corners transformed by the form matrix
                        val xs = FloatArray(4); val ys = FloatArray(4)
                        listOf(bbox.lowerLeftX to bbox.lowerLeftY, bbox.upperRightX to bbox.lowerLeftY,
                            bbox.upperRightX to bbox.upperRightY, bbox.lowerLeftX to bbox.upperRightY).forEachIndexed { i, (x, y) ->
                            xs[i] = m.scaleX * x + m.shearX * y + m.translateX
                            ys[i] = m.shearY * x + m.scaleY * y + m.translateY
                        }
                        val tw = xs.max() - xs.min(); val th = ys.max() - ys.min()
                        if (tw <= 0f || th <= 0f) continue
                        val r = a.rectangle
                        val sx = r.width / tw; val sy = r.height / th
                        cs.saveGraphicsState()
                        cs.transform(Matrix(sx, 0f, 0f, sy, r.lowerLeftX - xs.min() * sx, r.lowerLeftY - ys.min() * sy))
                        cs.drawForm(ap) // Do applies the form's own /Matrix
                        cs.restoreGraphicsState()
                        drew = true
                    }
                }
                page.annotations = annots - toDraw.toSet()
            }
            if (drew) d.save(out)
        }
        return drew
    }

    /** PdfBox re-parses damaged files (rebuilds the xref table); saving it gives the OS renderer a clean file. */
    fun repair(src: File, out: File) {
        load(src).use { it.save(out) }
    }

    /** Opens [file] in the OS renderer once to make sure it can display it. Only call on unencrypted files. */
    fun rendererAccepts(file: File): Boolean = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount > 0 } }
    } catch (e: Exception) {
        false
    }

    data class Info(
        val pages: Int, val title: String, val author: String, val subject: String,
        val encrypted: Boolean, val version: Float, val sizeBytes: Long,
    )

    fun info(file: File): Info = load(file).use { d ->
        val i = d.documentInformation
        Info(d.numberOfPages, i?.title ?: "", i?.author ?: "", i?.subject ?: "", d.isEncrypted, d.version, file.length())
    }

    fun setMetadata(src: File, title: String, author: String, subject: String, out: File) {
        load(src).use { d ->
            val i = d.documentInformation
            i.title = title.ifBlank { null }
            i.author = author.ifBlank { null }
            i.subject = subject.ifBlank { null }
            d.save(out)
        }
    }

    // ------------------------------------------------------------------ organize

    /** One page of the result. [src] = index in the source file, or -1 for a blank page. */
    /**
     * One page of the result. [src] = index in the source file, or -1 for a blank page.
     * [crop] = margins to cut away as fractions of the *displayed* page: left, top, right, bottom.
     */
    data class PageItem(
        val id: Long, val src: Int, val rotation: Int = 0, val copy: Boolean = false,
        val crop: List<Float>? = null,
    )

    /** Sets the CropBox from margins given in displayed orientation (works for rotated pages). */
    fun applyCrop(page: PDPage, m: List<Float>) {
        val (l, t, r, b) = m
        // displayed margins -> margins of the unrotated page: (left, bottom, right, top)
        val (rl, rb, rr, rt) = when (PageGeom.rotation(page)) {
            90 -> listOf(t, l, b, r)
            180 -> listOf(r, t, l, b)
            270 -> listOf(b, r, t, l)
            else -> listOf(l, b, r, t)
        }
        val c = page.cropBox
        val w = c.width; val h = c.height
        page.cropBox = PDRectangle(
            c.lowerLeftX + rl * w, c.lowerLeftY + rb * h,
            (w * (1 - rl - rr)).coerceAtLeast(1f), (h * (1 - rb - rt)).coerceAtLeast(1f),
        )
    }

    /**
     * Rebuilds the page tree in place from [items] (reorder, delete, rotate, duplicate, insert blank).
     * Doing it inside the same document keeps links, bookmarks and forms; duplicates share their
     * content streams, so a duplicated page costs almost no memory or file size.
     */
    /** Inserts every page of [other] into [src] before page index [at] (PdfCraft: Insert pages from file). */
    fun insertPages(src: File, other: File, at: Int, out: File): Int {
        load(src).use { d ->
            load(other).use { o ->
                val tree = d.pages
                val anchor = if (at in 0 until d.numberOfPages) d.getPage(at) else null
                var n = 0
                for (p in o.pages) {
                    val copy = PDPage(p.cosObject) // shares its content and resources: kept alive until saved below
                    copy.resources = p.resources // inherited resources become the page's own
                    copy.mediaBox = p.mediaBox; copy.cropBox = p.cropBox; copy.rotation = p.rotation
                    if (anchor != null) tree.insertBefore(copy, anchor) else tree.add(copy)
                    n++
                }
                // links to the other document's pages would point nowhere: keep only web links
                d.save(out)
                return n
            }
        }
    }

    fun applyPageEdits(src: File, items: List<PageItem>, out: File) {
        load(src).use { d ->
            val orig = d.pages.toList()
            for (i in d.numberOfPages - 1 downTo 0) d.removePage(i)
            var last: PDPage? = null
            for (it in items) {
                val page: PDPage = if (it.src < 0) {
                    val size = last?.mediaBox ?: PDRectangle.A4
                    PDPage(PDRectangle(size.width, size.height))
                } else {
                    val o = orig[it.src]
                    if (it.copy) PDPage(COSDictionary(o.cosObject)) else o
                }
                if (it.rotation != 0) page.rotation = ((page.rotation + it.rotation) % 360 + 360) % 360
                it.crop?.let { m -> applyCrop(page, m) }
                d.addPage(page)
                last = page
            }
            d.save(out)
        }
    }

    fun extractPages(src: File, pages: List<Int>, out: File) {
        load(src).use { s ->
            PDDocument(mem()).use { dst ->
                for (p in pages) dst.importPage(s.getPage(p))
                dst.save(out)
            }
        }
    }

    /** "1-3, 5, 8-" -> [[0,1,2],[4],[7..last]] (zero-based, one list per comma group). */
    fun parseRanges(spec: String, pageCount: Int): List<List<Int>> {
        val groups = ArrayList<List<Int>>()
        for (part in spec.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val ends = part.split('-').map { it.trim() }
            val a = ends[0].toIntOrNull() ?: if (ends.size > 1 && ends[0].isEmpty()) 1 else throw IllegalArgumentException("'$part' is not a page range")
            val b = if (ends.size == 1) a else (ends[1].toIntOrNull() ?: pageCount)
            if (a < 1 || b < a || b > pageCount) throw IllegalArgumentException("'$part' is outside 1-$pageCount")
            groups += (a - 1 until b).toList()
        }
        if (groups.isEmpty()) throw IllegalArgumentException("Enter at least one page or range")
        return groups
    }

    fun splitEvery(src: File, n: Int, outDir: File, baseName: String): List<File> {
        outDir.mkdirs()
        val files = ArrayList<File>()
        load(src).use { d ->
            val splitter = Splitter()
            splitter.setSplitAtPage(max(1, n))
            val parts = splitter.split(d)
            try {
                parts.forEachIndexed { i, part ->
                    val f = File(outDir, "${baseName}_part${i + 1}.pdf")
                    part.save(f); files += f
                }
            } finally { parts.forEach { runCatching { it.close() } } }
        }
        return files
    }

    fun merge(files: List<File>, out: File) {
        val m = PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = out.absolutePath
        m.mergeDocuments(mem())
    }

    // ------------------------------------------------------------------ create

    enum class ScanFilter { ORIGINAL, GRAYSCALE, DOCUMENT }
    enum class PageFit { A4, FIT_IMAGE }

    /** Decodes with sub-sampling (never loads a 12 MP photo at full size) and honours EXIF rotation. */
    fun decodeImage(ctx: Context, uri: Uri, maxDim: Int = 2000): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > maxDim * 2) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o2) }
            ?: throw IOException("Cannot read image")
        val deg = ctx.contentResolver.openInputStream(uri)!!.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
        val scale = min(1f, maxDim.toFloat() / max(bmp.width, bmp.height))
        if (deg != 0 || scale < 1f) {
            val m = GMatrix().apply { postRotate(deg.toFloat()); postScale(scale, scale) }
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) bmp.recycle()
            bmp = t
        }
        return bmp
    }

    /**
     * One pass: scale to [maxDim], apply the scan [filter], flatten onto white and mark the result
     * opaque. Opaque matters: PdfBox stores an extra alpha mask for any bitmap that "has alpha",
     * which every ARGB_8888 bitmap does by default — bigger files for nothing.
     */
    fun opaque(src: Bitmap, maxDim: Int, f: ScanFilter = ScanFilter.ORIGINAL): Bitmap {
        val k = min(1f, maxDim.toFloat() / max(src.width, src.height))
        val w = (src.width * k).toInt().coerceAtLeast(1); val h = (src.height * k).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (f != ScanFilter.ORIGINAL) {
            val cm = ColorMatrix().apply { setSaturation(0f) }
            if (f == ScanFilter.DOCUMENT) {
                // push greys to white / black: a cheap "scanned document" look, no heavy maths
                val c = 1.8f; val t = (-0.5f * c + 0.5f) * 255f - 40f
                cm.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
            }
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), paint)
        out.setHasAlpha(false)
        return out
    }

    fun imagesToPdf(ctx: Context, images: List<Uri>, fit: PageFit, filter: ScanFilter, out: File, maxDim: Int = 2000) {
        PDDocument(mem()).use { d ->
            for (u in images) {
                val decoded = decodeImage(ctx, u, maxDim)
                val bmp = opaque(decoded, maxDim, filter)
                decoded.recycle()
                val img = JPEGFactory.createFromImage(d, bmp, 0.82f)
                val page: PDPage
                var x = 0f; var y = 0f; var w: Float; var h: Float
                if (fit == PageFit.FIT_IMAGE) {
                    // 72 dpi-ish: cap the longest side at A4's long side so files stay small
                    val k = min(1f, 842f / max(bmp.width, bmp.height))
                    w = bmp.width * k; h = bmp.height * k
                    page = PDPage(PDRectangle(w, h))
                } else {
                    val land = bmp.width > bmp.height
                    val a4 = if (land) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                    page = PDPage(a4)
                    val m = 24f
                    val k = min((a4.width - 2 * m) / bmp.width, (a4.height - 2 * m) / bmp.height)
                    w = bmp.width * k; h = bmp.height * k
                    x = (a4.width - w) / 2; y = (a4.height - h) / 2
                }
                d.addPage(page)
                PDPageContentStream(d, page).use { it.drawImage(img, x, y, w, h) }
                bmp.recycle()
            }
            d.save(out)
        }
    }

    /**
     * Photo of a signature on paper -> PNG with only the ink, paper made transparent, cropped tight.
     * Ink = pixels clearly darker than the paper (paper brightness estimated from the whole photo,
     * so shadows and grey paper are tolerated). Returns the PNG and its height / width.
     */
    fun signatureFromPhoto(ctx: Context, uri: Uri, out: File): Pair<File, Float> {
        val src = decodeImage(ctx, uri, 1600)
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        val lum = IntArray(px.size) { i ->
            val c = px[i]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
        }
        // paper level = 90th percentile brightness; ink = darker than 65% of it
        val hist = IntArray(256); lum.forEach { hist[it]++ }
        var acc = 0; var paper = 255
        for (v in 0..255) { acc += hist[v]; if (acc >= px.size * 0.9) { paper = v; break } }
        val thr = (paper * 0.65f).toInt().coerceAtLeast(40)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (lum[y * w + x] < thr) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        if (r < 0 || (r - l) < 8 || (b - t) < 4) throw IOException("No signature found in this photo. Use dark ink on light paper.")
        val pad = ((r - l) * 0.03f).toInt() + 2
        l = (l - pad).coerceAtLeast(0); t = (t - pad).coerceAtLeast(0)
        r = (r + pad).coerceAtMost(w - 1); b = (b + pad).coerceAtMost(h - 1)
        val cw = r - l + 1; val ch = b - t + 1
        val k = min(1f, 900f / max(cw, ch))
        val ow = (cw * k).toInt().coerceAtLeast(1); val oh = (ch * k).toInt().coerceAtLeast(1)
        val outPx = IntArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val sx = l + (x / k).toInt().coerceAtMost(cw - 1); val sy = t + (y / k).toInt().coerceAtMost(ch - 1)
            val v = lum[sy * w + sx]
            // soft edge: fully opaque well below the threshold, fading out towards it
            val a = (((thr - v).toFloat() / (thr * 0.5f)) * 255f).toInt().coerceIn(0, 255)
            outPx[y * ow + x] = (a shl 24) or (px[sy * w + sx] and 0xFFFFFF)
        }
        val bmp = Bitmap.createBitmap(outPx, ow, oh, Bitmap.Config.ARGB_8888)
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out to oh.toFloat() / ow
    }

    // ------------------------------------------------------------------ optimize

    enum class Level(val maxDim: Int, val quality: Float, val label: String) {
        GOOD(1800, 0.80f, "Good quality"),
        BALANCED(1300, 0.65f, "Balanced"),
        SMALLEST(900, 0.45f, "Smallest file"),
    }

    /** Re-encodes page images as downscaled JPEG. Returns false when it would not make the file smaller. */
    fun compress(src: File, level: Level, out: File): Boolean {
        load(src).use { d ->
            val done = HashSet<Any>()
            for (page in d.pages) {
                val res = page.resources ?: continue
                for (name in res.xObjectNames.toList()) {
                    val x = res.getXObject(name)
                    if (x !is PDImageXObject || !done.add(x.cosObject)) continue
                    if (x.isStencil || x.bitsPerComponent == 1) continue // masks / line art: JPEG would make them worse
                    val isJpeg = x.suffix.equals("jpg", true) || x.suffix.equals("jpeg", true)
                    val big = max(x.width, x.height) > level.maxDim
                    if (isJpeg && !big && level != Level.SMALLEST) continue
                    try {
                        val mask = x.softMask
                        val decoded = x.image
                        val bmp = opaque(decoded, level.maxDim)
                        decoded.recycle()
                        val img = JPEGFactory.createFromImage(d, bmp, level.quality)
                        bmp.recycle()
                        // keep transparency: re-attach the original soft mask (any size works per spec)
                        if (mask != null) img.cosObject.setItem(COSName.SMASK, mask.cosObject)
                        res.put(name, img)
                    } catch (e: Exception) {
                        Log.w("PdfOps", "compress: skipped image $name", e) // unsupported colour space etc.
                    } catch (e: OutOfMemoryError) {
                        Log.w("PdfOps", "compress: image $name too large for memory, skipped")
                    }
                }
            }
            d.save(out)
        }
        return out.length() < src.length()
    }

    // ------------------------------------------------------------------ protect

    data class Permissions(val print: Boolean = true, val copy: Boolean = true, val modify: Boolean = true)

    fun protect(src: File, userPw: String, perms: Permissions, out: File) {
        load(src).use { d ->
            val ap = AccessPermission().apply {
                setCanPrint(perms.print); setCanExtractContent(perms.copy); setCanModify(perms.modify)
                setCanModifyAnnotations(perms.modify); setCanAssembleDocument(perms.modify)
            }
            // A random owner password makes the restrictions real: nobody can lift them without it.
            val owner = UUID.randomUUID().toString()
            val policy = StandardProtectionPolicy(owner, userPw, ap).apply {
                encryptionKeyLength = 256
                setPreferAES(true)
            }
            d.protect(policy)
            d.save(out)
        }
    }

    // ------------------------------------------------------------------ stamping

    private fun latin(s: String): String = s.map { c -> if (c.code in 32..255) c else '?' }.joinToString("")

    fun watermark(src: File, text: String, opacity: Float, out: File) {
        load(src).use { d ->
            val font = PDType1Font.HELVETICA_BOLD
            val t = latin(text)
            val gs = PDExtendedGraphicsState().apply {
                setNonStrokingAlphaConstant(opacity); setStrokingAlphaConstant(opacity)
            }
            for (page in d.pages) {
                val box = page.cropBox
                val size = min(box.width, box.height) / max(4f, t.length * 0.55f) * 1.4f
                val tw = font.getStringWidth(t) / 1000f * size
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setGraphicsStateParameters(gs)
                    cs.setNonStrokingColor(0.5f, 0.5f, 0.5f)
                    cs.beginText()
                    cs.setFont(font, size)
                    val cx = box.lowerLeftX + box.width / 2; val cy = box.lowerLeftY + box.height / 2
                    val m = Matrix.getRotateInstance(Math.toRadians(45.0), cx, cy)
                    m.translate(-tw / 2, -size / 3)
                    cs.setTextMatrix(m)
                    cs.showText(t)
                    cs.endText()
                }
            }
            d.save(out)
        }
    }

    enum class NumberPos { BOTTOM_CENTER, BOTTOM_RIGHT, TOP_RIGHT }

    fun pageNumbers(src: File, pos: NumberPos, startAt: Int, out: File) {
        load(src).use { d ->
            val font = PDType1Font.HELVETICA
            val n = d.numberOfPages
            d.pages.forEachIndexed { i, page ->
                val box = page.cropBox
                val label = "${startAt + i} / ${startAt + n - 1}"
                val size = 10f
                val tw = font.getStringWidth(label) / 1000f * size
                val (x, y) = when (pos) {
                    NumberPos.BOTTOM_CENTER -> box.lowerLeftX + (box.width - tw) / 2 to box.lowerLeftY + 18f
                    NumberPos.BOTTOM_RIGHT -> box.lowerLeftX + box.width - tw - 28f to box.lowerLeftY + 18f
                    NumberPos.TOP_RIGHT -> box.lowerLeftX + box.width - tw - 28f to box.lowerLeftY + box.height - 28f
                }
                if (page.rotation == 0) {
                    PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setNonStrokingColor(0.235f, 0.235f, 0.235f)
                        cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(x, y); cs.showText(label); cs.endText()
                    }
                }
            }
            d.save(out)
        }
    }

    // ------------------------------------------------------------------ export

    fun extractText(src: File, out: File) {
        load(src).use { d ->
            val s = PDFTextStripper().apply { sortByPosition = true }
            out.writeText(s.getText(d))
        }
    }
}
