package com.mobilepdf.editor.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Page size in PDF points, after rotation (i.e. as displayed). */
data class PageSize(val w: Float, val h: Float) { val aspect get() = h / w }

/** Set by the Application when Android warns that memory is running low while we are in front. */
object MemoryPressure {
    var underPressure by androidx.compose.runtime.mutableStateOf(false)
}

/** How capable the phone is. Picked automatically at start-up; see [DeviceBudget.classify]. */
enum class Tier { LOW, MID, HIGH }

/**
 * Device budget: the app detects what kind of phone it runs on and sizes everything to it, so the same
 * APK behaves on a 1 GB phone and an 8 GB one without the user doing anything.
 *
 * Detection uses the phone's real RAM, the per-app memory limit Android gives us, CPU core count,
 * Android's own "low-RAM device" flag (set on Android Go phones) and the Android version.
 * [underPressure]: Android reported memory running low while we are open, so we step down one tier.
 * [simulateLowEnd]: hidden tester override that forces the LOW tier (plus the memory meter).
 */
class DeviceBudget(ctx: Context, val simulateLowEnd: Boolean = false, val underPressure: Boolean = false) {
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val totalRamMb: Long = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024)
    val cores = Runtime.getRuntime().availableProcessors()
    val detectedTier = classify(totalRamMb, am.memoryClass, cores, android.os.Build.VERSION.SDK_INT, am.isLowRamDevice)
    val tier: Tier = when {
        simulateLowEnd -> Tier.LOW
        underPressure -> if (detectedTier == Tier.HIGH) Tier.MID else Tier.LOW
        else -> detectedTier
    }
    private val memoryClassMb = if (simulateLowEnd) 48 else am.memoryClass
    val lowRam = tier == Tier.LOW

    /**
     * Page bitmap cache. Budget phones: ~1/6 of the per-app Java heap. Strong phones on Android 8+
     * keep bitmap pixels in native memory, so the Java heap limit is the wrong yardstick there:
     * use 1/32 of the phone's RAM (8 GB -> 192 MB cap).
     */
    val cacheBytes: Int = when {
        tier == Tier.HIGH && android.os.Build.VERSION.SDK_INT >= 26 -> (totalRamMb * 1024L * 1024L / 32).coerceAtMost(192L shl 20)
        else -> (memoryClassMb * 1024L * 1024L / 6).coerceAtMost(when (tier) { Tier.LOW -> 12L; Tier.MID -> 32L; Tier.HIGH -> 48L } shl 20)
    }.toInt()
    /** Widest bitmap we ever render. Beyond this a page is upscaled instead of re-rendered. */
    val maxBitmapWidth = when (tier) { Tier.LOW -> 1280; Tier.MID -> 1800; Tier.HIGH -> 3240 } // HIGH: 3× a 1080p screen
    val maxZoom = when (tier) { Tier.LOW -> 3f; Tier.MID -> 4f; Tier.HIGH -> 6f }
    /** Longest side of photos put into PDFs (scan / images to PDF). */
    val imageMaxDim = when (tier) { Tier.LOW -> 1600; Tier.MID -> 2000; Tier.HIGH -> 3000 }
    /** Wait this long before rendering a page that just scrolled in (skips pages flung past). */
    val renderDebounceMs = when (tier) { Tier.LOW -> 180L; Tier.MID -> 100L; Tier.HIGH -> 50L }
    /** Render the neighbours of the visible page ahead of time (needs spare memory and CPU). */
    val prefetchPages = when (tier) { Tier.LOW -> 0; Tier.MID -> 1; Tier.HIGH -> 2 }

    override fun toString() =
        "tier=$tier (detected $detectedTier${if (simulateLowEnd) ", forced LOW" else ""}${if (underPressure) ", memory pressure" else ""}) " +
            "ram=${totalRamMb}MB memoryClass=${am.memoryClass}MB cores=$cores sdk=${android.os.Build.VERSION.SDK_INT} " +
            "-> cache=${cacheBytes shr 20}MB maxWidth=$maxBitmapWidth zoom=${maxZoom}x prefetch=$prefetchPages"

    companion object {
        /**
         * Pure, so it is unit-tested. totalMem reads below the box figure (a "2 GB" phone reports
         * ~1.8 GB, "4 GB" ~3.6 GB), hence the thresholds sit between the marketing sizes.
         */
        fun classify(totalRamMb: Long, memoryClassMb: Int, cores: Int, sdk: Int, isLowRamDevice: Boolean): Tier = when {
            isLowRamDevice || totalRamMb < 2600 || memoryClassMb <= 96 -> Tier.LOW   // Android Go, 1–2 GB
            sdk < 26 && totalRamMb < 3600 -> Tier.LOW                                 // old Android: bitmaps in Java heap
            totalRamMb < 4600 || cores <= 4 -> Tier.MID                               // 3–4 GB, or weak CPU
            else -> Tier.HIGH
        }
    }

    /**
     * What a 1 GB phone tolerates, used by the low-end meter: the Java heap limit of the 1 GB test
     * emulator (bitmaps live in the Java heap on Android 7) and a rough ceiling for everything the
     * app allocates (Java + native) before a 1 GB phone's low-memory killer starts picking on it.
     */
    val lowEndJavaHeapLimit = 96L * 1024 * 1024
    val lowEndTotalLimit = 160L * 1024 * 1024
}

/**
 * Thin wrapper over the OS PdfRenderer (pdfium inside the platform: zero APK size, native speed).
 * Only one page may be open at a time, so all access is serialised with a mutex; pages are
 * rendered one by one on demand, which keeps memory flat regardless of document length.
 */
class PageRenderer(private val file: File, val budget: DeviceBudget) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(budget.cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    val pageCount: Int = renderer.pageCount

    init { synchronized(live) { live += this } }

    companion object {
        private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<PageRenderer, Boolean>())

        /** Android says memory is short: drop every cached page (visible pages keep their own bitmaps). */
        fun trimAll() = synchronized(live) { live.toList() }.forEach { it.cache.evictAll() }
    }

    suspend fun sizes(): List<PageSize> = mutex.withLock {
        withContext(Dispatchers.IO) {
            List(pageCount) { i -> renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) } }
        }
    }

    fun cached(index: Int, width: Int): Bitmap? = cache.get("$index@$width")

    /** Renders page [index] to a bitmap [width] px wide (clamped to the device budget). */
    suspend fun render(index: Int, width: Int): Bitmap {
        val w = width.coerceIn(64, budget.maxBitmapWidth)
        cache.get("$index@$w")?.let { return it }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                cache.get("$index@$w")?.let { return@withContext it }
                val bmp = try {
                    draw(index, w)
                } catch (oom: OutOfMemoryError) {
                    cache.evictAll()
                    draw(index, w * 2 / 3)
                }
                cache.put("$index@$w", bmp)
                bmp
            }
        }
    }

    // ---- fallback for older Androids whose built-in renderer can't draw some fonts
    private var boxDoc: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
    private var boxRenderer: com.tom_roush.pdfbox.rendering.PDFRenderer? = null
    /** True when this document is drawn by PdfBox instead of the system renderer (see [checkFonts]). */
    var usingPdfBox = false; private set
    /** Bumped when every page must be drawn again (the renderer changed). */
    var version by androidx.compose.runtime.mutableIntStateOf(0); private set

    /**
     * Android 7's built-in renderer leaves embedded Type 1 text out (measured: LaTeX papers render as empty
     * pages; Android 16's renders them). On older Androids, one text page is drawn small and its glyph
     * positions are checked for ink; if most are blank, this document is drawn by PdfBox's renderer
     * instead: slower, but complete. Returns true if it switched.
     */
    suspend fun checkFonts(): Boolean = withContext(Dispatchers.IO) {
        if (android.os.Build.VERSION.SDK_INT >= 36 || usingPdfBox) return@withContext false
        // parsing and text extraction happen outside the lock, so pages keep rendering meanwhile
        val d = runCatching { PdfOps.load(file) }.getOrNull() ?: return@withContext false
        var switch = false
        for (i in 0 until minOf(3, pageCount)) {
            val boxes = runCatching { glyphBoxes(d, i) }.getOrDefault(emptyList())
            if (boxes.size < 20) continue
            val bmp = mutex.withLock { draw(i, 600) }
            val inked = boxes.count { hasInk(bmp, it) }
            bmp.recycle()
            switch = inked < boxes.size * 0.35f
            break
        }
        if (switch) mutex.withLock {
            boxDoc = d; boxRenderer = com.tom_roush.pdfbox.rendering.SafePDFRenderer(d)
            usingPdfBox = true; cache.evictAll(); version++
        } else d.close()
        switch
    }

    /** A sample of visible glyph boxes on page [i], as fractions of the displayed page. */
    private fun glyphBoxes(d: com.tom_roush.pdfbox.pdmodel.PDDocument, i: Int): List<android.graphics.RectF> {
        val page = d.getPage(i)
        val rot = PageGeom.rotation(page)
        val dw = if (rot == 90 || rot == 270) page.cropBox.height else page.cropBox.width
        val dh = if (rot == 90 || rot == 270) page.cropBox.width else page.cropBox.height
        val out = ArrayList<android.graphics.RectF>()
        var n = 0
        val stripper = object : com.tom_roush.pdfbox.text.PDFTextStripper() {
            override fun processTextPosition(t: com.tom_roush.pdfbox.text.TextPosition) {
                val visible = runCatching { graphicsState.textState.renderingMode.isFill || graphicsState.textState.renderingMode.isStroke }.getOrDefault(true)
                if (visible && (t.unicode?.isNotBlank() == true) && n++ % 4 == 0 && out.size < 200) {
                    out += android.graphics.RectF(t.xDirAdj / dw, (t.yDirAdj - t.heightDir) / dh, (t.xDirAdj + t.widthDirAdj) / dw, t.yDirAdj / dh)
                }
            }
        }
        stripper.startPage = i + 1; stripper.endPage = i + 1
        runCatching { stripper.getText(d) }
        return out.filter { it.width() > 0f && it.height() > 0f }
    }

    private fun hasInk(bmp: Bitmap, r: android.graphics.RectF): Boolean {
        for (yy in 0..4) for (xx in 0..4) {
            val x = ((r.left + (r.right - r.left) * xx / 4f) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = ((r.top + (r.bottom - r.top) * yy / 4f) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(x, y)
            if ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 215) return true
        }
        return false
    }

    private fun drawPdfBox(index: Int, w: Int, print: Boolean = false): Bitmap {
        val d = boxDoc!!
        val page = d.getPage(index)
        val rot = PageGeom.rotation(page)
        val dw = if (rot == 90 || rot == 270) page.cropBox.height else page.cropBox.width
        return boxRenderer!!.renderImage(index, w / dw, com.tom_roush.pdfbox.rendering.ImageType.RGB)
    }

    private fun draw(index: Int, w: Int): Bitmap = if (usingPdfBox) drawPdfBox(index, w) else renderer.openPage(index).use { p ->
        val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE) // PdfRenderer leaves it transparent
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }

    /** Full-quality bitmap for export (not cached). */
    suspend fun renderForExport(index: Int, scale: Float): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (usingPdfBox) return@withContext renderer.openPage(index).use { p -> drawPdfBox(index, (p.width * scale).toInt().coerceAtLeast(1)) }
            renderer.openPage(index).use { p ->
                val w = (p.width * scale).toInt().coerceAtLeast(1)
                val h = (p.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                bmp
            }
        }
    }

    override fun close() {
        synchronized(live) { live -= this }
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { boxDoc?.close() }
        runCatching { pfd.close() }
    }
}
