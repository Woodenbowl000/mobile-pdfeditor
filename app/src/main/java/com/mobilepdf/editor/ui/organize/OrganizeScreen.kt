@file:OptIn(ExperimentalMaterial3Api::class)

package com.mobilepdf.editor.ui.organize

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.mobilepdf.editor.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobilepdf.editor.AppViewModel
import androidx.compose.material.icons.filled.PostAdd
import com.mobilepdf.editor.OutFile
import com.mobilepdf.editor.Screen
import com.mobilepdf.editor.pdf.PageRenderer
import com.mobilepdf.editor.pdf.PageSize
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.pdf.PdfOps.PageItem
import com.mobilepdf.editor.util.newWorkFile
import com.mobilepdf.editor.util.outDir
import com.mobilepdf.editor.util.safeName
import com.mobilepdf.editor.util.pages
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OrganizeScreen(vm: AppViewModel) {
    val doc = vm.doc ?: run { vm.screen = Screen.HOME; return }
    val ctx = LocalContext.current
    val viewFile by produceState<File?>(null, doc.file) { value = withContext(Dispatchers.IO) { vm.displayFile(doc.file) } }
    val vf = viewFile ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        return
    }
    val renderer = remember(vf, vm.budget) { PageRenderer(vf, vm.budget) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }

    var nextId by remember { mutableStateOf(1L) }
    val initial = remember(doc.file) { List(renderer.pageCount) { PageItem(it.toLong() + 1, it) }.also { nextId = renderer.pageCount + 1L } }
    var pages by remember(doc.file) { mutableStateOf(initial) }
    var selected by remember(doc.file) { mutableStateOf(setOf<Long>()) }
    val undo = remember(doc.file) { mutableStateListOf<List<PageItem>>() }
    val changed = pages != initial

    fun edit(new: List<PageItem>) { undo += pages; pages = new }
    fun id() = nextId++
    val sel = pages.indices.filter { pages[it].id in selected }
    var cropDlg by remember { mutableStateOf(false) }
    val sizes by produceState<List<PageSize>?>(null, renderer) { renderer.checkFonts(); value = renderer.sizes() }

    fun leave() { vm.screen = Screen.VIEWER }
    // Insert another PDF's pages after the last selected page (or at the end); page edits made so far are applied first
    val insertPdf = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val items = pages
        val at = (pages.indices.filter { pages[it].id in selected }.maxOrNull() ?: pages.lastIndex) + 1
        vm.launchOp("Inserting pages…", onDone = { r: Pair<File, Int> -> vm.replaceDoc(r.first); vm.message = "Inserted ${pages(r.second)}" }) {
            vm.requireEditable()
            val base = if (items != initial) ctx.newWorkFile().also { PdfOps.applyPageEdits(doc.file, items, it) } else doc.file
            val other = ctx.newWorkFile()
            ctx.contentResolver.openInputStream(uri)!!.use { i -> other.outputStream().use { i.copyTo(it) } }
            val out = ctx.newWorkFile()
            val n = try { PdfOps.insertPages(base, other, at, out) } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                throw IllegalArgumentException("that PDF is password-protected: open it and save an unlocked copy first")
            }
            other.delete()
            out to n
        }
    }
    BackHandler { if (selected.isNotEmpty()) selected = emptySet() else leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Organize · ${pages(pages.size)}" else "${selected.size} selected") },
                navigationIcon = { IconButton(::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } },
                actions = {
                    IconButton({ pages = undo.removeAt(undo.lastIndex); selected = emptySet() }, enabled = undo.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    IconButton({ insertPdf.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Filled.PostAdd, "Insert pages from another PDF")
                    }
                    IconButton({ selected = if (selected.size == pages.size) emptySet() else pages.map { it.id }.toSet() }) {
                        Icon(Icons.Filled.SelectAll, "Select all")
                    }
                    TextButton({
                        val items = pages
                        vm.launchOp("Applying changes…", onDone = { f: File -> vm.replaceDoc(f); vm.screen = Screen.VIEWER }) {
                            val out = ctx.newWorkFile()
                            vm.requireEditable()
                            PdfOps.applyPageEdits(doc.file, items, out)
                            out
                        }
                    }, enabled = changed && pages.isNotEmpty()) { Text("Done") }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) BottomAppBar {
                ActionRow(
                    listOf(
                        Act(Icons.AutoMirrored.Filled.RotateRight, "Rotate") {
                            // crop margins turn with the page: (l, t, r, b) -> (b, l, t, r)
                            edit(pages.map {
                                if (it.id in selected) it.copy(
                                    rotation = (it.rotation + 90) % 360,
                                    crop = it.crop?.let { c -> listOf(c[3], c[0], c[1], c[2]) },
                                ) else it
                            })
                        },
                        Act(Icons.Filled.Crop, "Crop") { cropDlg = true },
                        Act(Icons.AutoMirrored.Filled.ArrowBack, "Earlier") {
                            val l = pages.toMutableList()
                            for (i in 1 until l.size) if (l[i].id in selected && l[i - 1].id !in selected) { val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t }
                            edit(l)
                        },
                        Act(Icons.AutoMirrored.Filled.ArrowForward, "Later") {
                            val l = pages.toMutableList()
                            for (i in l.size - 2 downTo 0) if (l[i].id in selected && l[i + 1].id !in selected) { val t = l[i]; l[i] = l[i + 1]; l[i + 1] = t }
                            edit(l)
                        },
                        Act(Icons.Filled.ContentCopy, "Duplicate") {
                            edit(pages.flatMap { if (it.id in selected) listOf(it, it.copy(id = id(), copy = true)) else listOf(it) })
                        },
                        Act(Icons.AutoMirrored.Filled.NoteAdd, "Blank") {
                            val at = (sel.maxOrNull() ?: pages.lastIndex) + 1
                            edit(pages.toMutableList().apply { add(at, PageItem(id(), -1)) })
                        },
                        Act(Icons.Filled.ContentCut, "Extract") {
                            val chosen = pages.filter { it.id in selected }
                            vm.launchOp("Extracting…", onDone = { r: OutFile -> vm.showResult(r) }) {
                                val out = File(ctx.outDir(), safeName(doc.name.removeSuffix(".pdf")) + "_pages.pdf")
                                PdfOps.applyPageEdits(doc.file, chosen, out)
                                OutFile(listOf(out), "Pages extracted", pages(chosen.size))
                            }
                        },
                        Act(Icons.Filled.Delete, "Delete") {
                            if (selected.size < pages.size) { edit(pages.filter { it.id !in selected }); selected = emptySet() }
                            else vm.message = "A PDF needs at least one page."
                        },
                    ),
                )
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            GridCells.Adaptive(104.dp),
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { index, p ->
                val isSel = p.id in selected
                Column(
                    Modifier.clickable { selected = if (isSel) selected - p.id else selected + p.id },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .border(if (isSel) 3.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (p.src < 0) Text("Blank", color = Color.Gray)
                        else Thumb(renderer, p, sizes?.getOrNull(p.src))
                        if (isSel) Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
                    }
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (cropDlg) {
        val first = pages.firstOrNull { it.id in selected && it.src >= 0 }
        CropDialog(
            renderer, first, first?.let { sizes?.getOrNull(it.src) },
            onDismiss = { cropDlg = false },
        ) { m ->
            cropDlg = false
            edit(pages.map { if (it.id in selected && it.src >= 0) it.copy(crop = m) else it })
        }
    }
}

/** Displayed aspect (w/h) of an item, including the rotation added in this screen. */
private fun displayedRatio(p: PageItem, s: PageSize?): Float {
    val r = s?.let { it.w / it.h } ?: 0.72f
    return if (p.rotation % 180 == 0) r else 1f / r
}

@Composable
private fun CropDialog(
    renderer: PageRenderer, page: PageItem?, size: PageSize?,
    onDismiss: () -> Unit, onApply: (List<Float>?) -> Unit,
) {
    val start = page?.crop ?: listOf(0f, 0f, 0f, 0f)
    var l by remember { mutableFloatStateOf(start[0]) }
    var t by remember { mutableFloatStateOf(start[1]) }
    var r by remember { mutableFloatStateOf(start[2]) }
    var b by remember { mutableFloatStateOf(start[3]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop pages") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (page != null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Thumb(renderer, page.copy(crop = listOf(l, t, r, b)), size)
                }
                CropSlider("Top", t) { t = it }
                CropSlider("Bottom", b) { b = it }
                CropSlider("Left", l) { l = it }
                CropSlider("Right", r) { r = it }
                Text(
                    "Applies to all selected pages. Nothing is deleted: cropping only hides the edges.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton({ onApply(if (l + t + r + b == 0f) null else listOf(l, t, r, b)) }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton({ onApply(null) }) { Text("Remove crop") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CropSlider(label: String, v: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${(v * 100).toInt()}%", Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium)
        Slider(v, onChange, Modifier.weight(1f), valueRange = 0f..0.4f)
    }
}

private class Act(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val onClick: () -> Unit)

@Composable
private fun ActionRow(actions: List<Act>) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { a ->
            Column(
                Modifier.clickable(onClick = a.onClick).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(a.icon, a.label)
                Text(a.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Low-resolution thumbnail, loaded lazily (only cells on screen), shown in its final orientation,
 * with any pending crop shaded.
 */
@Composable
private fun Thumb(renderer: PageRenderer, p: PageItem, size: PageSize?) {
    val index = p.src
    var bmp by remember(index) { mutableStateOf<Bitmap?>(renderer.cached(index, 240)) }
    LaunchedEffect(index) { if (bmp == null) bmp = runCatching { renderer.render(index, 240) }.getOrNull() }
    val ratio = displayedRatio(p, size)
    BoxWithConstraints(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 0.72f)) {
        val turned = p.rotation % 180 != 0
        bmp?.let {
            Image(
                it.asImageBitmap(), null,
                (if (turned) Modifier.requiredSize(maxHeight, maxWidth) else Modifier.fillMaxSize()).rotate(p.rotation.toFloat()),
                contentScale = ContentScale.FillBounds,
            )
        }
        p.crop?.let { (l, t, r, b) ->
            Canvas(Modifier.fillMaxSize()) {
                val shade = Color(0x99000000)
                val w = this.size.width; val h = this.size.height
                drawRect(shade, Offset.Zero, Size(w, t * h))
                drawRect(shade, Offset(0f, h * (1 - b)), Size(w, b * h))
                drawRect(shade, Offset(0f, t * h), Size(l * w, h * (1 - t - b)))
                drawRect(shade, Offset(w * (1 - r), t * h), Size(r * w, h * (1 - t - b)))
            }
        }
    }
}
