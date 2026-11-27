package com.mobilepdf.editor.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.text.DecimalFormat

fun Context.workDir(): File = File(cacheDir, "work").apply { mkdirs() }
fun Context.outDir(): File = File(cacheDir, "out").apply { mkdirs() }
fun Context.cameraDir(): File = File(cacheDir, "camera").apply { mkdirs() }

fun Context.newWorkFile(ext: String = "pdf"): File = File(workDir(), "w${System.nanoTime()}.$ext")

fun Context.displayName(uri: Uri): String {
    if (uri.scheme == "content") {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
}

/** Streams the document into app cache: PdfRenderer and PdfBox both need a seekable local file. */
fun Context.copyToCache(uri: Uri, dest: File = newWorkFile()): File {
    contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
    return dest
}

fun Context.copyFileTo(src: File, uri: Uri) {
    contentResolver.openOutputStream(uri, "wt")!!.use { o -> src.inputStream().use { it.copyTo(o, 64 * 1024) } }
}

fun Context.uriFor(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

fun Context.shareFiles(files: List<File>, mime: String) {
    val uris = ArrayList(files.map { uriFor(it) })
    val i = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    i.type = mime
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
    startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Delete scratch files older than a day so the cache never grows without bound on small storage. */
fun Context.cleanOldCache(keep: Set<File> = emptySet()) {
    val limit = System.currentTimeMillis() - 24L * 3600 * 1000
    listOf(workDir(), outDir(), cameraDir()).forEach { dir ->
        dir.walkTopDown().filter { it.isFile && it !in keep && it.lastModified() < limit }.forEach { it.delete() }
    }
}

fun formatSize(bytes: Long): String {
    val df = DecimalFormat("#.#")
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> df.format(bytes / 1024.0) + " KB"
        else -> df.format(bytes / 1048576.0) + " MB"
    }
}

fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)

/** "1 page", "3 pages" */
fun pages(n: Int) = if (n == 1) "1 page" else "$n pages"
