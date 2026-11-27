@file:OptIn(ExperimentalMaterial3Api::class)

package com.mobilepdf.editor.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ViewDay
import com.mobilepdf.editor.pdf.Attachments
import com.mobilepdf.editor.pdf.HeaderFooter
import com.mobilepdf.editor.pdf.ImageExtractor
import com.mobilepdf.editor.pdf.Sanitizer
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import com.mobilepdf.editor.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.mobilepdf.editor.AppViewModel
import com.mobilepdf.editor.OutFile
import com.mobilepdf.editor.Screen
import com.mobilepdf.editor.ToolId
import com.mobilepdf.editor.pdf.PageRenderer
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.util.cameraDir
import com.mobilepdf.editor.util.copyToCache
import com.mobilepdf.editor.util.displayName
import com.mobilepdf.editor.util.formatSize
import com.mobilepdf.editor.util.outDir
import com.mobilepdf.editor.util.safeName
import com.mobilepdf.editor.ui.rememberDocumentScanner
import com.mobilepdf.editor.util.pages
import com.mobilepdf.editor.util.uriFor
import com.mobilepdf.editor.util.workDir
import java.io.File

private data class ToolInfo(val id: ToolId?, val title: String, val sub: String, val icon: ImageVector, val needsDoc: Boolean)

private val allTools = listOf(
    ToolInfo(ToolId.SCAN, "Scan to PDF", "Auto-crop, filters, OCR", Icons.Filled.CameraAlt, false),
    ToolInfo(ToolId.IMAGES, "Images to PDF", "From your gallery", Icons.Filled.PhotoLibrary, false),
    ToolInfo(ToolId.MERGE, "Merge PDFs", "Combine files", Icons.AutoMirrored.Filled.CallMerge, false),
    ToolInfo(ToolId.SPLIT, "Split", "Every N pages or ranges", Icons.AutoMirrored.Filled.CallSplit, true),
    ToolInfo(ToolId.EXTRACT, "Extract pages", "Keep only some pages", Icons.Filled.ContentCut, true),
    ToolInfo(ToolId.COMPRESS, "Compress", "Make the file smaller", Icons.Filled.Compress, true),
    ToolInfo(ToolId.PROTECT, "Protect", "Password & permissions", Icons.Filled.Lock, true),
    ToolInfo(ToolId.WATERMARK, "Watermark", "Text across pages", Icons.Filled.Opacity, true),
    ToolInfo(ToolId.NUMBERS, "Page numbers", "Add 1 / N", Icons.Filled.FormatListNumbered, true),
    ToolInfo(ToolId.HEADER, "Header & footer", "Text on every page", Icons.Filled.ViewDay, true),
    ToolInfo(ToolId.TO_IMAGES, "PDF to images", "PNG or JPG", Icons.Filled.Image, true),
    ToolInfo(ToolId.TEXT, "Extract text", "Save as .txt", Icons.Filled.Description, true),
    ToolInfo(ToolId.IMAGES_OUT, "Extract images", "Every picture as a file", Icons.Filled.Collections, true),
    ToolInfo(ToolId.ATTACH, "Attachments", "Files inside the PDF", Icons.Filled.AttachFile, true),
    ToolInfo(ToolId.COMPARE, "Compare", "What changed between two PDFs", Icons.Filled.Difference, true),
    ToolInfo(ToolId.HIDDEN, "Remove hidden info", "Metadata, scripts, hidden text", Icons.Filled.CleaningServices, true),
    ToolInfo(ToolId.INFO, "Properties", "Title, author, size", Icons.Filled.Info, true),
)

@Composable
fun ToolsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var active by rememberSaveable { mutableStateOf<ToolId?>(null) }
    val doc = vm.doc

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.openUri(uri, showViewer = false) // becomes the working file; stay on Tools
    }

    LaunchedEffect(vm.launchTool) {
        vm.launchTool?.let { t -> active = t; vm.launchTool = null }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Tools", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        ) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Working file", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(doc?.name ?: "None selected", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (doc != null) TextButton({ vm.screen = Screen.VIEWER }) { Text("View") }
                TextButton({ pickPdf.launch(arrayOf("application/pdf")) }) { Text(if (doc == null) "Choose" else "Change") }
            }
        }
        LazyVerticalGrid(
            GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(allTools) { t ->
                val enabled = !t.needsDoc || doc != null
                Card(
                    Modifier.clickable(enabled = enabled) {
                        // honour the original document's permissions (its working copy is decrypted)
                        val a = doc?.access
                        val blocked = when (t.id) {
                            ToolId.TEXT, ToolId.TO_IMAGES, ToolId.IMAGES_OUT -> a?.canCopy == false
                            ToolId.ATTACH, ToolId.COMPARE -> a?.canCopy == false
                            ToolId.MERGE, ToolId.IMAGES, ToolId.SCAN -> false
                            else -> a?.canModify == false
                        }
                        if (blocked) vm.message = "The author of this PDF does not allow that." else active = t.id
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Icon(t.icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(10.dp))
                        Text(t.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (enabled) t.sub else "Choose a PDF first", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    var scanFallback by rememberSaveable { mutableStateOf(false) }
    val close = { active = null }
    when (active) {
        ToolId.MERGE -> MergeSheet(vm, close)
        ToolId.IMAGES -> ImagesSheet(vm, startWithCamera = false, onClose = close)
        ToolId.SCAN -> if (scanFallback) ImagesSheet(vm, startWithCamera = true, onClose = { scanFallback = false; close() })
            else ScanFlow(vm, onClose = close, onFallback = { scanFallback = true })
        ToolId.SPLIT -> SplitSheet(vm, extract = false, onClose = close)
        ToolId.EXTRACT -> SplitSheet(vm, extract = true, onClose = close)
        ToolId.COMPRESS -> CompressSheet(vm, close)
        ToolId.PROTECT -> ProtectSheet(vm, close)
        ToolId.WATERMARK -> WatermarkSheet(vm, close)
        ToolId.NUMBERS -> NumbersSheet(vm, close)
        ToolId.TO_IMAGES -> ToImagesSheet(vm, close)
        ToolId.TEXT -> {
            val d = vm.doc
            if (d != null) {
                LaunchedEffect(Unit) {
                    close()
                    vm.launchOp("Extracting text…", onDone = { r: OutFile -> vm.showResult(r) }) {
                        val out = File(ctx.outDir(), safeName(d.name.removeSuffix(".pdf")) + ".txt")
                        PdfOps.extractText(d.file, out)
                        OutFile(listOf(out), "Text extracted", mime = "text/plain")
                    }
                }
            }
        }
        ToolId.INFO -> InfoSheet(vm, close)
        ToolId.HEADER -> HeaderFooterSheet(vm, close)
        ToolId.HIDDEN -> HiddenInfoSheet(vm, close)
        ToolId.ATTACH -> AttachmentsSheet(vm, close)
        ToolId.COMPARE -> CompareSheet(vm, close)
        ToolId.IMAGES_OUT -> {
            val d = vm.doc
            if (d != null) LaunchedEffect(Unit) {
                close()
                vm.launchOp("Finding pictures…", onDone = { r: OutFile -> if (r.files.isEmpty()) vm.message = "This PDF has no pictures" else vm.showResult(r) }) {
                    val dir = File(ctx.outDir(), "pictures_${System.currentTimeMillis()}").apply { mkdirs() }
                    val files = ImageExtractor.extract(d.file, dir, safeName(d.name.removeSuffix(".pdf")))
                    OutFile(files, "${files.size} picture(s) saved", mime = "image/*")
                }
            }
        }
        null -> Unit
    }
}

// ---------------------------------------------------------------------------------------- shared

@Composable
private fun ToolSheet(
    title: String, onDismiss: () -> Unit, actionLabel: String,
    actionEnabled: Boolean = true, onAction: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
            Button(onAction, Modifier.fillMaxWidth().height(52.dp), enabled = actionEnabled) { Text(actionLabel) }
        }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> FilterChip(selected == v, { onSelect(v) }, { Text(label) }) }
    }
}

private fun outFile(ctx: android.content.Context, name: String) = File(ctx.outDir(), safeName(name))

private val uriListSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Uri>, String>(
    save = { l -> l.map { it.toString() } },
    restore = { s -> mutableStateListOf<Uri>().apply { addAll(s.map(Uri::parse)) } },
)

private val uriNameListSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Pair<Uri, String>>, String>(
    save = { l -> l.flatMap { listOf(it.first.toString(), it.second) } },
    restore = { s -> mutableStateListOf<Pair<Uri, String>>().apply { s.chunked(2).forEach { add(Uri.parse(it[0]) to it[1]) } } },
)

// ---------------------------------------------------------------------------------------- merge

@Composable
private fun MergeSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val items = rememberSaveable(saver = uriNameListSaver) { mutableStateListOf<Pair<Uri, String>>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { items += it to ctx.displayName(it) }
    }
    ToolSheet("Merge PDFs", onClose, "Merge ${items.size} files", actionEnabled = items.size >= 2, onAction = {
        val list = items.toList()
        onClose()
        vm.launchOp("Merging…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val tmp = list.map { ctx.copyToCache(it.first) }
            val out = outFile(ctx, "merged.pdf")
            try { PdfOps.merge(tmp, out) } finally { tmp.forEach { it.delete() } }
            OutFile(listOf(out), "Merged ${list.size} files", pages(PdfOps.info(out).pages))
        }
    }) {
        Text("Files are merged in this order.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEachIndexed { i, (_, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", Modifier.padding(end = 8.dp))
                Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ if (i > 0) { val x = items.removeAt(i); items.add(i - 1, x) } }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                IconButton({ if (i < items.lastIndex) { val x = items.removeAt(i); items.add(i + 1, x) } }, enabled = i < items.lastIndex) { Icon(Icons.Filled.ArrowDownward, "Move down") }
                IconButton({ items.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove") }
            }
        }
        OutlinedButton({ picker.launch(arrayOf("application/pdf")) }, Modifier.fillMaxWidth()) { Text(if (items.isEmpty()) "Choose PDFs" else "Add more") }
    }
}

// ---------------------------------------------------------------------------------------- scan (CamScanner-style)

/**
 * Scan to PDF: opens the document scanner straight away (edge detection, auto-capture, crop, filters,
 * clean-up, many pages). The result is then run through OCR so its text is searchable, selectable and
 * editable at once — like CamScanner's "scan + OCR". Falls back to the camera sheet without Play services.
 */
@Composable
private fun ScanFlow(vm: AppViewModel, onClose: () -> Unit, onFallback: () -> Unit) {
    val ctx = LocalContext.current
    var started by rememberSaveable { mutableStateOf(false) }
    val scan = rememberDocumentScanner(
        onResult = { r ->
            onClose()
            vm.launchOp("Preparing scan…", onDone = { o: OutFile -> vm.showResult(o) }) {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
                val pdf = outFile(ctx, "Scan $stamp.pdf")
                if (r.pdf != null) ctx.contentResolver.openInputStream(r.pdf)!!.use { i -> pdf.outputStream().use { i.copyTo(it) } }
                else PdfOps.imagesToPdf(ctx, r.pages, PdfOps.PageFit.A4, PdfOps.ScanFilter.ORIGINAL, pdf, vm.budget.imageMaxDim)
                // OCR right away; if this phone can't, the scan is still fine, just not searchable
                vm.busy = "Recognizing text…"
                val searchable = runCatching {
                    val ocr = File(ctx.cacheDir, "scan-ocr-${System.nanoTime()}.pdf")
                    val res = com.mobilepdf.editor.pdf.Ocr.recognize(pdf, pdf, ocr, vm.budget) { p, n -> vm.busy = "Recognizing text… page $p of $n" }
                    if (res.words > 0) { ocr.copyTo(pdf, overwrite = true); ocr.delete(); true } else { ocr.delete(); false }
                }.getOrDefault(false)
                OutFile(listOf(pdf), "Scan ready", pages(r.pages.size) + if (searchable) " · text searchable & editable" else "")
            }
        },
        onUnavailable = { msg -> vm.message = "Document scanner unavailable here, using the camera instead"; onFallback() },
        onCancel = onClose,
    )
    LaunchedEffect(Unit) { if (!started) { started = true; scan() } }
}

// ---------------------------------------------------------------------------------------- images / scan

@Composable
private fun ImagesSheet(vm: AppViewModel, startWithCamera: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val images = rememberSaveable(saver = uriListSaver) { mutableStateListOf<Uri>() }
    var fit by rememberSaveable { mutableStateOf(PdfOps.PageFit.A4) }
    var filter by rememberSaveable { mutableStateOf(PdfOps.ScanFilter.ORIGINAL) }
    // the photo the camera is writing right now; must survive our process dying meanwhile
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    var cameraStarted by rememberSaveable { mutableStateOf(false) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { images.addAll(it) }
    // "Camera" = the document scanner (edges, crop, clean-up); plain camera only if it isn't available
    var useScanner by rememberSaveable { mutableStateOf(true) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pending?.let { images += it }
        pending = null
    }
    fun shoot() {
        val f = File(ctx.cameraDir(), "scan_${System.nanoTime()}.jpg")
        val u = ctx.uriFor(f)
        pending = u
        camera.launch(u)
    }
    val docScan = rememberDocumentScanner(
        onResult = { r -> images.addAll(r.pages) },
        onUnavailable = { useScanner = false; shoot() },
    )
    LaunchedEffect(Unit) { if (startWithCamera && !cameraStarted) { cameraStarted = true; shoot() } }

    ToolSheet(if (startWithCamera) "Scan to PDF" else "Images to PDF", onClose, "Create PDF (${images.size})", images.isNotEmpty(), onAction = {
        val list = images.toList(); val f = fit; val flt = filter
        onClose()
        vm.launchOp("Creating PDF…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val out = outFile(ctx, "scan_${System.currentTimeMillis() / 1000}.pdf")
            PdfOps.imagesToPdf(ctx, list, f, flt, out, vm.budget.imageMaxDim)
            OutFile(listOf(out), "PDF created", pages(list.size))
        }
    }) {
        Text(
            if (images.isEmpty()) "Add photos from the camera or your gallery." else "${images.size} page(s) added",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ if (useScanner) docScan() else shoot() }, Modifier.weight(1f)) { Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.size(6.dp)); Text("Scan") }
            OutlinedButton({ gallery.launch("image/*") }, Modifier.weight(1f)) { Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.size(6.dp)); Text("Gallery") }
        }
        if (images.isNotEmpty()) TextButton({ images.removeAt(images.lastIndex) }) { Text("Remove last page") }
        Text("Page size", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(listOf(PdfOps.PageFit.A4 to "A4", PdfOps.PageFit.FIT_IMAGE to "Fit image"), fit) { fit = it }
        Text("Look", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            listOf(PdfOps.ScanFilter.ORIGINAL to "Original", PdfOps.ScanFilter.GRAYSCALE to "Gray", PdfOps.ScanFilter.DOCUMENT to "Document"),
            filter,
        ) { filter = it }
    }
}

// ---------------------------------------------------------------------------------------- split / extract

@Composable
private fun SplitSheet(vm: AppViewModel, extract: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    val pages = remember(d.file) { runCatching { PdfOps.info(d.file).pages }.getOrDefault(0) }
    var everyMode by remember { mutableStateOf(false) }
    var spec by remember { mutableStateOf("") }
    var n by remember { mutableStateOf("1") }
    val base = safeName(d.name.removeSuffix(".pdf"))

    ToolSheet(if (extract) "Extract pages" else "Split", onClose, if (extract) "Extract" else "Split", onAction = {
        onClose()
        if (extract) {
            vm.launchOp("Extracting…", onDone = { r: OutFile -> vm.showResult(r) }) {
                val pageList = PdfOps.parseRanges(spec, pages).flatten()
                val out = outFile(ctx, "${base}_extract.pdf")
                PdfOps.extractPages(d.file, pageList, out)
                OutFile(listOf(out), "Pages extracted", pages(pageList.size))
            }
        } else {
            val every = everyMode; val nn = n.toIntOrNull() ?: 1; val sp = spec
            vm.launchOp("Splitting…", onDone = { r: OutFile -> vm.showResult(r) }) {
                val dir = File(ctx.outDir(), "split_${System.currentTimeMillis()}")
                val files = if (every) PdfOps.splitEvery(d.file, nn, dir, base) else {
                    dir.mkdirs()
                    PdfOps.parseRanges(sp, pages).mapIndexed { i, g ->
                        File(dir, "${base}_part${i + 1}.pdf").also { PdfOps.extractPages(d.file, g, it) }
                    }
                }
                OutFile(files, "Split into ${files.size} files")
            }
        }
    }) {
        Text("This document has $pages pages.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!extract) ChoiceRow(listOf(false to "By ranges", true to "Every N pages"), everyMode) { everyMode = it }
        if (!extract && everyMode) {
            OutlinedTextField(n, { n = it.filter(Char::isDigit).take(4) }, label = { Text("Pages per file") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(
                spec, { spec = it }, label = { Text(if (extract) "Pages to keep" else "Ranges (one file each)") },
                placeholder = { Text("e.g. 1-3, 5, 8-") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------- compress

@Composable
private fun CompressSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    var level by remember { mutableStateOf(PdfOps.Level.BALANCED) }
    ToolSheet("Compress", onClose, "Compress", onAction = {
        val lv = level
        onClose()
        vm.launchOp("Compressing…", onDone = { r: OutFile? -> if (r != null) vm.showResult(r) else vm.message = "This PDF is already as small as it can get." }) {
            val out = outFile(ctx, safeName(d.name.removeSuffix(".pdf")) + "_small.pdf")
            if (PdfOps.compress(d.file, lv, out)) {
                val pct = 100 - (out.length() * 100 / d.file.length()).toInt()
                OutFile(listOf(out), "Compressed", "${formatSize(d.file.length())} → ${formatSize(out.length())} (−$pct%)")
            } else { out.delete(); null }
        }
    }) {
        Text("Current size: ${formatSize(d.file.length())}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PdfOps.Level.entries.forEach { l ->
            Row(Modifier.fillMaxWidth().clickable { level = l }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(level == l, { level = l })
                Text(l.label)
            }
        }
        Text("Images are re-encoded smaller; text and vectors stay sharp.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------- protect

@Composable
private fun ProtectSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    var copy by remember { mutableStateOf(true) }
    var modify by remember { mutableStateOf(false) }
    ToolSheet("Protect with password", onClose, "Protect", pw.length >= 4 && pw == pw2, onAction = {
        val perms = PdfOps.Permissions(print, copy, modify); val p = pw
        onClose()
        vm.launchOp("Encrypting…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val out = outFile(ctx, safeName(d.name.removeSuffix(".pdf")) + "_protected.pdf")
            PdfOps.protect(d.file, p, perms, out)
            OutFile(listOf(out), "Password set", "AES-256")
        }
    }) {
        OutlinedTextField(pw, { pw = it }, label = { Text("Password (min. 4 characters)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true, isError = pw2.isNotEmpty() && pw != pw2,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Text("Allow people who open it to:", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(print, { print = it }); Text("Print") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(copy, { copy = it }); Text("Copy text") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(modify, { modify = it }); Text("Edit and annotate") }
        Text(
            "There is no way to recover a forgotten password. Keep an unprotected copy somewhere safe.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    }
}

// ---------------------------------------------------------------------------------------- watermark / numbers

@Composable
private fun WatermarkSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableFloatStateOf(0.25f) }
    ToolSheet("Watermark", onClose, "Apply to all pages", text.isNotBlank(), onAction = {
        val t = text; val o = opacity
        onClose()
        vm.launchOp("Adding watermark…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val out = outFile(ctx, safeName(d.name.removeSuffix(".pdf")) + "_watermark.pdf")
            PdfOps.watermark(d.file, t, o, out)
            OutFile(listOf(out), "Watermark added")
        }
    }) {
        OutlinedTextField(text, { text = it }, label = { Text("Text (Latin characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Opacity ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(opacity, { opacity = it }, valueRange = 0.05f..0.8f)
    }
}

@Composable
private fun NumbersSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    var pos by remember { mutableStateOf(PdfOps.NumberPos.BOTTOM_CENTER) }
    var start by remember { mutableIntStateOf(1) }
    ToolSheet("Page numbers", onClose, "Add numbers", onAction = {
        val p = pos; val s = start
        onClose()
        vm.launchOp("Numbering pages…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val out = outFile(ctx, safeName(d.name.removeSuffix(".pdf")) + "_numbered.pdf")
            PdfOps.pageNumbers(d.file, p, s, out)
            OutFile(listOf(out), "Page numbers added", "Rotated pages are skipped")
        }
    }) {
        Text("Position", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            listOf(PdfOps.NumberPos.BOTTOM_CENTER to "Bottom", PdfOps.NumberPos.BOTTOM_RIGHT to "Bottom right", PdfOps.NumberPos.TOP_RIGHT to "Top right"),
            pos,
        ) { pos = it }
        OutlinedTextField(start.toString(), { start = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 1 }, label = { Text("Start at") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------------------------------- pdf -> images

@Composable
private fun ToImagesSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    var png by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1.5f) }
    ToolSheet("PDF to images", onClose, "Export", onAction = {
        val asPng = png; val sc = scale
        onClose()
        vm.launchOp("Exporting pages…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val dir = File(ctx.outDir(), "img_${System.currentTimeMillis()}").apply { mkdirs() }
            val base = safeName(d.name.removeSuffix(".pdf"))
            val files = ArrayList<File>()
            PageRenderer(vm.displayFile(d.file), vm.budget).use { r ->
                r.checkFonts()
                for (i in 0 until r.pageCount) {
                    val bmp = r.renderForExport(i, sc)
                    val f = File(dir, "${base}_${i + 1}.${if (asPng) "png" else "jpg"}")
                    f.outputStream().use { bmp.compress(if (asPng) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle() // one page in memory at a time
                    files += f
                }
            }
            OutFile(files, "${files.size} images exported", mime = if (asPng) "image/png" else "image/jpeg")
        }
    }) {
        Text("Format", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(listOf(false to "JPG (small)", true to "PNG (sharp)"), png) { png = it }
        Text("Quality", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(listOf(1f to "Low", 1.5f to "Normal", 2.5f to "High"), scale) { scale = it }
    }
}

// ---------------------------------------------------------------------------------------- properties

@Composable
private fun InfoSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    val info = remember(d.file) { runCatching { PdfOps.info(d.file) }.getOrNull() }
    var title by remember { mutableStateOf(info?.title ?: "") }
    var author by remember { mutableStateOf(info?.author ?: "") }
    var subject by remember { mutableStateOf(info?.subject ?: "") }
    ToolSheet("Properties", onClose, "Save properties", onAction = {
        val t = title; val a = author; val s = subject
        onClose()
        vm.launchOp("Saving…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val out = outFile(ctx, safeName(d.name))
            PdfOps.setMetadata(d.file, t, a, s, out)
            OutFile(listOf(out), "Properties updated")
        }
    }) {
        if (info != null) Text(
            "${info.pages} pages  ·  ${formatSize(info.sizeBytes)}  ·  PDF ${info.version}" + if (info.encrypted) "  ·  was encrypted" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}


// ---------------------------------------------------------------------------------------- header & footer

@Composable
private fun HeaderFooterSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    var header by remember { mutableStateOf("") }
    var footer by remember { mutableStateOf("Page {page} of {pages}") }
    var align by remember { mutableStateOf(HeaderFooter.Align.CENTER) }
    var size by remember { mutableFloatStateOf(10f) }
    ToolSheet("Header & footer", onClose, "Add", actionEnabled = header.isNotBlank() || footer.isNotBlank(), onAction = {
        val h = header; val f = footer; val a = align; val sz = size
        onClose()
        vm.launchOp("Adding header & footer…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val out = outFile(ctx, safeName(d.name.removeSuffix(".pdf")) + "_header.pdf")
            HeaderFooter.apply(d.file, h, f, a, sz, d.name.removeSuffix(".pdf"), out)
            OutFile(listOf(out), "Header & footer added")
        }
    }) {
        OutlinedTextField(header, { header = it }, label = { Text("Header (top of each page)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(footer, { footer = it }, label = { Text("Footer (bottom of each page)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Use {page}, {pages}, {date} and {file}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Position", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(listOf(HeaderFooter.Align.LEFT to "Left", HeaderFooter.Align.CENTER to "Centre", HeaderFooter.Align.RIGHT to "Right"), align) { align = it }
        Text("Size", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(listOf(8f to "Small", 10f to "Normal", 13f to "Large"), size) { size = it }
    }
}

// ---------------------------------------------------------------------------------------- hidden information

@Composable
private fun HiddenInfoSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    var counts by remember { mutableStateOf<Map<Sanitizer.Hidden, Int>?>(null) }
    var chosen by remember { mutableStateOf(emptySet<Sanitizer.Hidden>()) }
    LaunchedEffect(d.file) {
        val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Sanitizer.scan(d.file) }.getOrDefault(emptyMap()) }
        counts = c
        // on by default: what people rarely mean to share; off: what changes how the document works
        val careful = setOf(Sanitizer.Hidden.HIDDEN_TEXT, Sanitizer.Hidden.FORMS, Sanitizer.Hidden.BOOKMARKS, Sanitizer.Hidden.COMMENTS)
        chosen = c.filter { it.value > 0 && it.key !in careful }.keys
    }
    ToolSheet("Remove hidden information", onClose, "Remove", actionEnabled = chosen.isNotEmpty(), onAction = {
        val which = chosen
        onClose()
        vm.launchOp("Removing hidden information…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val out = outFile(ctx, safeName(d.name.removeSuffix(".pdf")) + "_clean.pdf")
            val done = Sanitizer.remove(d.file, which, out)
            OutFile(listOf(out), "Hidden information removed", done.filter { it.value > 0 }.entries.joinToString(" · ") { "${it.key.label}: ${it.value}" })
        }
    }) {
        val c = counts
        if (c == null) Text("Checking the document…")
        else Sanitizer.Hidden.entries.forEach { h ->
            val n = c[h] ?: 0
            Row(
                Modifier.fillMaxWidth().clickable(enabled = n > 0) { chosen = if (h in chosen) chosen - h else chosen + h },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(h in chosen, { chosen = if (it) chosen + h else chosen - h }, enabled = n > 0)
                Column(Modifier.weight(1f)) {
                    Text(if (n > 0) "${h.label} ($n)" else "${h.label}: none", style = MaterialTheme.typography.bodyLarge,
                        color = if (n > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                    Text(h.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("A cleaned copy is made; your file is not changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------- attachments

@Composable
private fun AttachmentsSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    var items by remember { mutableStateOf<List<Attachments.Item>?>(null) }
    LaunchedEffect(d.file) {
        items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Attachments.list(d.file) }.getOrDefault(emptyList()) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        onClose()
        vm.launchOp("Attaching…", onDone = { r: OutFile -> vm.showResult(r) }) {
            val name = ctx.displayName(uri)
            val data = ctx.copyToCache(uri, File(ctx.workDir(), "att_${System.nanoTime()}"))
            val out = outFile(ctx, safeName(d.name.removeSuffix(".pdf")) + "_attached.pdf")
            Attachments.add(d.file, data, name, ctx.contentResolver.getType(uri), out)
            data.delete()
            OutFile(listOf(out), "File attached", name)
        }
    }
    ToolSheet("Attachments", onClose, "Attach a file", onAction = { pick.launch(arrayOf("*/*")) }) {
        val l = items
        when {
            l == null -> Text("Reading…")
            l.isEmpty() -> Text("This PDF has no attached files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> l.forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(if (a.size > 0) formatSize(a.size) else null, a.description).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({
                        onClose()
                        vm.launchOp("Saving attachment…", onDone = { r: OutFile -> vm.showResult(r) }) {
                            val out = outFile(ctx, a.name)
                            if (!Attachments.save(d.file, a.name, out)) throw IllegalStateException("that attachment could not be read")
                            OutFile(listOf(out), "Attachment ready", a.name,
                                mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
                        }
                    }) { Text("Save") }
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------------------- compare

@Composable
private fun CompareSheet(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = vm.doc ?: return
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        onClose()
        vm.launchOp("Comparing…", onDone = { r: OutFile -> if (r.files.isEmpty()) vm.message = "No differences: the text of both files is the same" else vm.showResult(r) }) {
            val other = ctx.copyToCache(uri, File(ctx.workDir(), "cmp_${System.nanoTime()}.pdf"))
            val otherName = ctx.displayName(uri)
            try {
                val res = com.mobilepdf.editor.pdf.Compare.compare(d.file, other)
                if (res.changedPages.isEmpty()) return@launchOp OutFile(emptyList(), "")
                val out = outFile(ctx, safeName(d.name.removeSuffix(".pdf")) + "_comparison.pdf")
                com.mobilepdf.editor.pdf.Compare.report(res, d.name, otherName, out)
                OutFile(listOf(out), "Comparison ready", "${res.removed} line(s) removed, ${res.added} added, on ${res.changedPages.size} page(s)")
            } finally { other.delete() }
        }
    }
    ToolSheet("Compare", onClose, "Choose the other PDF", onAction = { pick.launch(arrayOf("application/pdf")) }) {
        Text("Compares the text of \"${d.name}\" with another version, page by page.")
        Text(
            "You get a report listing what was removed (red) and added (green) on each page.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
