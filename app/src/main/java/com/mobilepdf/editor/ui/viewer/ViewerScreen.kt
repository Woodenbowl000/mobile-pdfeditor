@file:OptIn(ExperimentalMaterial3Api::class)

package com.mobilepdf.editor.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import com.mobilepdf.editor.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilepdf.editor.AppViewModel
import com.mobilepdf.editor.Screen
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.annot.scaled
import com.mobilepdf.editor.pdf.AnnotBaker
import com.mobilepdf.editor.pdf.PageRenderer
import com.mobilepdf.editor.pdf.PageSize
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.pdf.TextIndexer
import com.mobilepdf.editor.util.newWorkFile
import com.mobilepdf.editor.util.outDir
import com.mobilepdf.editor.util.printPdf
import com.mobilepdf.editor.util.safeName
import com.mobilepdf.editor.util.pages
import com.mobilepdf.editor.util.shareFiles
import com.mobilepdf.editor.ui.SignaturePad
import com.mobilepdf.editor.ui.TextPrompt
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Title
import com.mobilepdf.editor.data.Draft
import com.mobilepdf.editor.pdf.Bookmark
import com.mobilepdf.editor.pdf.FieldType
import com.mobilepdf.editor.pdf.FormField
import com.mobilepdf.editor.pdf.FormWidget
import com.mobilepdf.editor.pdf.Forms
import com.mobilepdf.editor.pdf.PageLink
import com.mobilepdf.editor.pdf.TextEditor
import com.mobilepdf.editor.pdf.TextFonts
import com.mobilepdf.editor.annot.bounds
import com.mobilepdf.editor.util.displayName
import com.mobilepdf.editor.util.copyToCache
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop54
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.automirrored.filled.Comment
import com.mobilepdf.editor.annot.hit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.UploadFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.foundation.border

private class TextDlg(val page: Int, val at: Offset, val existing: Annot.Text?)

/** Editing one line of the page's own text: where it is, what it said, and an earlier edit of it if any. */
private class ReplaceDlg(
    val page: Int, val rect: Rect, val original: String, val existing: Annot.Replace?, val size: Float = 0f,
    val font: String = "", val scanned: Boolean = false,
    /** The paragraph the line belongs to (its lines, top to bottom) and its text, if it has more than one line. */
    val para: List<Rect>? = null, val paraText: String = "",
)

@Composable
fun ViewerScreen(vm: AppViewModel, snack: SnackbarHostState) {
    val doc = vm.doc ?: run { vm.screen = Screen.HOME; return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { ViewerState() }
    val listState = rememberLazyListState()

    // what the renderer shows: annotation appearances drawn in (the OS renderer skips them)
    // the document shows at once; on older Androids a display copy (annotations drawn in) replaces it if needed
    val viewFile by produceState<File?>(doc.file, doc.file) { value = withContext(Dispatchers.IO) { vm.displayFile(doc.file) } }
    val renderer = remember(viewFile, vm.budget) { viewFile?.let { f -> runCatching { PageRenderer(f, vm.budget) }.getOrNull() } }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    // older Androids: switch to the compatible renderer if this document's fonts don't show
    // (after the page list is up, see below)
    val indexer = remember(doc.file) { TextIndexer(doc.file) }
    DisposableEffect(indexer) { onDispose { indexer.close() } }
    val sizes by produceState<List<PageSize>?>(null, renderer) {
        value = renderer?.sizes()
        // then, older Androids: switch to the compatible renderer if this document's fonts don't show
        if (renderer?.checkFonts() == true) snack.showSnackbar("This document's fonts need the compatible renderer on this phone: pages may appear a little slower")
    }

    var menu by remember { mutableStateOf(false) }
    var gotoDlg by remember { mutableStateOf(false) }
    var textDlg by remember { mutableStateOf<TextDlg?>(null) }
    var replaceDlg by remember { mutableStateOf<ReplaceDlg?>(null) }
    var signDlg by remember { mutableStateOf(false) }
    var exitDlg by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var formSheet by remember { mutableStateOf(false) }
    var bookmarksSheet by remember { mutableStateOf(false) }
    var redactFind by remember { mutableStateOf(false) }
    var redactConfirm by remember { mutableStateOf(false) }
    var afterRedactAsk by remember { mutableStateOf<(() -> Unit)?>(null) } // save / share waiting on unapplied marks
    var noteDlg by remember { mutableStateOf<Triple<Int, Offset, Annot.Note?>?>(null) }
    var commentsSheet by remember { mutableStateOf(false) }
    var pictureRequest by remember { mutableStateOf(false) }
    var stampDlg by remember { mutableStateOf(false) }
    // read aloud: made on first use, released when the viewer closes
    var readAloud by remember(doc.file) { mutableStateOf<ReadAloud?>(null) }
    DisposableEffect(readAloud) { val r = readAloud; onDispose { r?.shutdown() } }
    // ---- digital signatures
    var dsigSheet by remember { mutableStateOf(false) }
    var sigsSheet by remember { mutableStateOf(false) }
    var signer by remember { mutableStateOf<com.mobilepdf.editor.pdf.DigitalSign.Signer?>(null) }
    var pendingP12 by remember { mutableStateOf<Uri?>(null) }
    var breakSigAsk by remember { mutableStateOf<(() -> Unit)?>(null) }
    var breakSigOk by remember(doc.file) { mutableStateOf(false) }
    val sigChecks by produceState<List<com.mobilepdf.editor.pdf.DigitalSign.Check>?>(null, doc.file) {
        value = withContext(Dispatchers.IO) {
            if (!com.mobilepdf.editor.pdf.DigitalSign.mightBeSigned(doc.file)) emptyList()
            else runCatching { com.mobilepdf.editor.pdf.DigitalSign.verify(doc.file) }.getOrNull()
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var bookmarks by remember(doc.file) { mutableStateOf<List<Bookmark>?>(null) }
    var fieldEdit by remember { mutableStateOf<FormField?>(null) }
    var linkAsk by remember { mutableStateOf<String?>(null) }
    var signTarget by remember { mutableStateOf<Pair<Int, Rect>?>(null) }
    var restoreDraft by remember { mutableStateOf<Draft?>(null) }
    var draftChecked by remember { mutableStateOf(false) }

    val unsaved = state.hasAnnots || doc.dirty || state.formDirty
    state.onDocSwitch = { f -> vm.adoptWorkFile(f) }
    state.onNoteTap = { p, at, existing -> noteDlg = Triple(p, existing?.pos ?: at, existing) }
    val draftKey = vm.draftKey(doc)
    fun say(m: String) { scope.launch { snack.showSnackbar(com.mobilepdf.editor.ui.I18n.tr(m)) } }
    fun currentPage() = listState.firstVisibleItemIndex

    // -------- fonts for added text: uploaded .ttf files and the eyedropper
    val fontsDir = remember { File(ctx.filesDir, "fonts") }
    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) {
            fontsDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() }.map { it.path }
        }
        state.customFonts.clear(); state.customFonts += found
    }
    val pickFontFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.launchOp("Adding font…", onDone = { f: File ->
            if (f.path !in state.customFonts) state.customFonts += f.path
            com.mobilepdf.editor.ui.viewer.forgetTypeface(f.path)
            state.textFont = com.mobilepdf.editor.annot.FontChoice(file = f.path)
            say("Font added: ${f.nameWithoutExtension}. New text uses it.")
        }) {
            val name = ctx.displayName(uri).replace(Regex("[^A-Za-z0-9 ._()-]"), "_").ifBlank { "font.ttf" }
            fontsDir.mkdirs()
            val tmp = File(fontsDir, ".incoming")
            ctx.copyToCache(uri, tmp)
            try {
                TextFonts.validate(tmp)
            } catch (e: Exception) {
                tmp.delete()
                throw IllegalArgumentException("This font can't be used (only TrueType .ttf / TrueType-based .otf fonts work)")
            }
            File(fontsDir, if (name.contains('.')) name else "$name.ttf").also { dest -> dest.delete(); tmp.renameTo(dest) }
        }
    }
    /** Eyedropper: copy the font, size and colour of the page's text at [at] (or of added text there). */
    fun pickFontAt(page: Int, at: Offset) {
        state.annots[page].orEmpty().lastOrNull { it is Annot.Text && it.bounds(sizes?.getOrNull(page)?.aspect ?: 1.41f).contains(at) }
            ?.let { a ->
                a as Annot.Text
                state.textFont = a.font; state.textSize = a.size; state.color = a.color; state.pickingFont = false
                say("Copied ${a.font.label} · now tap where the new text goes")
                return
            }
        val rend = renderer
        scope.launch {
            val run = runCatching { indexer.page(page) }.getOrNull()?.runAt(at, state.tapSlop)
            if (run == null) { say("No text there. Tap on a word of the page to copy its style"); return@launch }
            state.pickingFont = false
            val ink = rend?.let { r -> runCatching { sampleScanColors(r, page, run.rect).second }.getOrNull() }
            state.textFont = if (run.invisible) com.mobilepdf.editor.annot.FontChoice(family = "serif") // a scan: no real font
                else TextFonts.guess(run.font).copy(pdfFont = run.font, fromPage = page)
            state.textSize = run.size.coerceIn(0.01f, 0.2f)
            if (ink != null) state.color = ink or 0xFF000000.toInt()
            val f = state.textFont
            val traits = listOfNotNull(if (f.bold) "bold" else null, if (f.italic) "italic" else null).joinToString(" ")
            say("Copied ${if (run.invisible) "scanned text" else run.font}${if (traits.isNotEmpty()) " ($traits)" else ""} · now tap where the new text goes")
        }
    }

    // -------- save / share plumbing
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.saveAs(uri)
    }

    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val d = vm.doc
        if (uri != null && d != null) vm.saveCopyTo(uri, d.file)
    }

    /**
     * Writes pending form values and annotations into the PDF (as a new working copy), then continues
     * with the finished file. Form first, so annotations are drawn on top of filled fields.
     */
    fun withBaked(onReady: () -> Unit) {
        if (state.redactCount > 0) { afterRedactAsk = onReady; return }
        // changes to a validly signed document break its signature: say so once before doing it
        if (!breakSigOk && (state.hasAnnots || state.formDirty) &&
            sigChecks?.any { it.status == com.mobilepdf.editor.pdf.DigitalSign.Status.VALID } == true) { breakSigAsk = onReady; return }
        val flatten = state.flattenOnSave && state.fields.isNotEmpty()
        if (!state.hasAnnots && !state.formDirty && !flatten) { onReady(); return }
        val pending = state.annots
        val values = state.formValues.toMap()
        vm.launchOp("Applying changes…", onDone = { f: File ->
            vm.replaceDoc(f); state.clearAll(); state.formValues.clear(); onReady()
        }) {
            vm.requireEditable()
            var cur = doc.file
            if (values.isNotEmpty() || flatten) {
                val o = ctx.newWorkFile(); Forms.fill(cur, values, flatten, o); cur = o
            }
            if (pending.values.any { it.isNotEmpty() }) {
                val o = ctx.newWorkFile(); AnnotBaker.bake(cur, pending, o); cur = o
            }
            cur
        }
    }

    fun handleField(f: FormField, w: FormWidget) {
        if (f.readOnly) { say("This field is read-only"); return }
        when (f.type) {
            FieldType.CHECK -> state.setValue(f, if (state.valueOf(f) == Forms.CHECKED) Forms.UNCHECKED else Forms.CHECKED)
            FieldType.RADIO -> w.onValue?.let { state.setValue(f, it) }
            FieldType.TEXT, FieldType.CHOICE -> fieldEdit = f
            FieldType.SIGNATURE -> { signTarget = w.page to w.rect; signDlg = true }
        }
    }

    /** Tap in reading mode: a form field, else a link, else show/hide the toolbars. */
    fun onViewTap(page: Int, p: Offset) {
        val aspect = sizes?.getOrNull(page)?.aspect ?: 1.41f
        (state.annots[page].orEmpty().lastOrNull { it is Annot.Note && it.hit(p, 0.01f, aspect) } as? Annot.Note)?.let {
            noteDlg = Triple(page, it.pos, it); return
        }
        if (state.showFields && doc.access.canModify) {
            val hit = state.fields.firstNotNullOfOrNull { f ->
                f.widgets.firstOrNull { it.page == page && it.rect.inflate(0.006f).contains(p) }?.let { f to it }
            }
            if (hit != null) { handleField(hit.first, hit.second); return }
        }
        scope.launch {
            when (val l = runCatching { indexer.links(page) }.getOrDefault(emptyList()).firstOrNull { it.rect.contains(p) }) {
                is PageLink.Uri -> linkAsk = l.uri
                is PageLink.Page -> listState.animateScrollToItem(l.page.coerceIn(0, sizes?.lastIndex ?: 0))
                null -> state.chrome = !state.chrome
            }
        }
    }

    // Load the PdfBox side (forms, links) shortly after the first pages are on screen.
    LaunchedEffect(doc.file) {
        delay(700)
        state.fields = indexer.fields()
        if (state.fields.isNotEmpty() && !state.formDirty && !state.formHintShown) {
            state.formHintShown = true // once per document, not after every save
            say("This PDF has a form. Tap a blue box, or use Fill form.")
        }
        else if (state.fields.isEmpty() && indexer.xfaOnly()) say("This form type (XFA) can be viewed but not filled in here.")
    }

    // Crash recovery: offer a draft left by a previous session, then keep the draft current.
    LaunchedEffect(Unit) {
        val d = withContext(Dispatchers.IO) { vm.drafts.load(draftKey) }
        if (d != null && vm.consumeResumed()) {
            // the system killed the app while it was in the background: the user never chose to
            // leave, so bring their unsaved work straight back without asking
            state.restore(d.annots)
            state.formValues.putAll(d.formValues)
            draftChecked = true
        } else {
            restoreDraft = d
            draftChecked = d == null
        }
    }
    LaunchedEffect(draftChecked, state.annots, state.formValues.toMap(), doc.file, doc.dirty) {
        if (!draftChecked) return@LaunchedEffect
        delay(800)
        val draft = if (state.hasAnnots || state.formDirty || doc.dirty) Draft(
            state.annots, state.formValues.toMap(), if (doc.dirty) doc.file.path else null, System.currentTimeMillis(),
        ) else null
        withContext(Dispatchers.IO) { if (draft != null) vm.drafts.save(draftKey, draft) else vm.drafts.delete(draftKey) }
    }

    fun save() = withBaked {
        val d = vm.doc ?: return@withBaked
        if (d.source == null || !vm.saveToSource()) saveAs.launch(d.name)
    }

    fun share() = withBaked {
        val d = vm.doc ?: return@withBaked
        vm.launchOp("Preparing…", onDone = { f: File -> ctx.shareFiles(listOf(f), "application/pdf") }) {
            File(ctx.outDir(), safeName(d.name)).also { d.file.copyTo(it, overwrite = true) }
        }
    }

    fun print() = withBaked {
        val d = vm.doc ?: return@withBaked
        vm.launchOp("Preparing…", onDone = { f: File -> ctx.printPdf(f, d.name) }) { vm.displayFile(d.file) }
    }

    fun runSearch() {
        searchJob?.cancel()
        state.hits.clear(); state.currentHit = -1
        val q = state.query.trim(); val n = renderer?.pageCount ?: 0
        if (q.isEmpty()) return
        state.searchTotal = n
        searchJob = scope.launch {
            state.searchBusy = true
            try {
                for (i in 0 until n) {
                    state.searchedPages = i
                    val pt = indexer.page(i)
                    pt.find(q, state.matchCase).forEach { state.hits += Hit(i, it) }
                    if (state.currentHit < 0 && state.hits.isNotEmpty()) { state.currentHit = 0; listState.animateScrollToItem(state.hits[0].page) }
                    if (state.hits.size >= 500) break
                }
                if (state.hits.isEmpty()) say("No matches for \"$q\"")
            } finally { state.searchBusy = false }
        }
    }

    fun stepHit(d: Int) {
        if (state.hits.isEmpty()) return
        state.currentHit = (state.currentHit + d + state.hits.size) % state.hits.size
        scope.launch { listState.animateScrollToItem(state.hits[state.currentHit].page) }
    }

    fun requestExit() { if (unsaved) exitDlg = true else vm.closeDoc(discard = true) }

    BackHandler {
        when {
            state.searching -> { state.searching = false; searchJob?.cancel(); state.hits.clear(); state.currentHit = -1 }
            state.selected != null -> state.selected = null
            state.tool != Tool.VIEW -> state.tool = Tool.VIEW
            else -> requestExit()
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(state.chrome) {
                if (state.searching) SearchBar(state, ::runSearch, ::stepHit) {
                    state.searching = false; searchJob?.cancel(); state.hits.clear(); state.currentHit = -1
                } else TopAppBar(
                    title = { Text(doc.name + if (unsaved) " •" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { IconButton(::requestExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                    actions = {
                        if (doc.access.canModify) {
                            IconButton({ state.undo() }, enabled = state.undoSize > 0) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                            IconButton({ state.redo() }, enabled = state.redoSize > 0) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                        }
                        IconButton({ state.searching = true }) { Icon(Icons.Filled.Search, "Find") }
                        if (doc.access.canModify) IconButton({ withBaked { vm.screen = Screen.ORGANIZE } }) { Icon(Icons.Filled.GridView, "Organize pages") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Save") }, { menu = false; save() })
                                DropdownMenuItem({ Text("Save a copy…") }, { menu = false; withBaked { saveCopy.launch(safeName(vm.doc?.name ?: "document.pdf")) } })
                                DropdownMenuItem({ Text("Share") }, { menu = false; share() })
                                DropdownMenuItem({ Text("Print") }, { menu = false; print() })
                                DropdownMenuItem({ Text(if (state.nightMode) "Day mode" else "Night mode") }, { menu = false; state.nightMode = !state.nightMode })
                                DropdownMenuItem({ Text(if (state.zoom > 1.01f) "Fit page" else "Fit text width") }, {
                                    menu = false
                                    if (state.zoom > 1.01f) state.zoom = 1f else state.fitWidthRequest++
                                })
                                DropdownMenuItem({ Text("Go to page…") }, { menu = false; gotoDlg = true })
                                DropdownMenuItem({ Text("Read aloud") }, {
                                    menu = false
                                    val n = sizes?.size ?: 0
                                    if (n > 0) {
                                        val r = readAloud ?: ReadAloud(ctx, scope, n, textOf = { p -> indexer.page(p).text },
                                            onPage = { p -> scope.launch { if (currentPage() != p) listState.animateScrollToItem(p) } }, onMessage = ::say).also { readAloud = it }
                                        r.start(currentPage())
                                    }
                                })
                                DropdownMenuItem({ Text("Comments & changes") }, { menu = false; commentsSheet = true })
                                if (doc.access.canModify) DropdownMenuItem({ Text("Digital signature…") }, { menu = false; dsigSheet = true })
                                sigChecks?.takeIf { it.isNotEmpty() }?.let { c -> DropdownMenuItem({ Text("Signatures (${c.size})") }, { menu = false; sigsSheet = true }) }
                                if (doc.access.canModify) DropdownMenuItem({ Text("Find & redact…") }, { menu = false; state.tool = Tool.REDACT; redactFind = true })
                                DropdownMenuItem({ Text("Bookmarks") }, {
                                    menu = false; bookmarksSheet = true
                                    if (bookmarks == null) scope.launch { bookmarks = indexer.outline() }
                                })
                                if (state.fields.isNotEmpty() && doc.access.canModify) DropdownMenuItem({ Text("Fill form") }, { menu = false; formSheet = true })
                                if (doc.access.canModify) DropdownMenuItem({ Text("Recognize text (OCR)") }, {
                                    menu = false
                                    withBaked {
                                        val d0 = vm.doc ?: return@withBaked
                                        vm.launchOp("Recognizing text…", onDone = { r: Pair<File, com.mobilepdf.editor.pdf.Ocr.Result> ->
                                            val res = r.second
                                            if (res.recognized == 0) say("All pages already have text: nothing to recognize")
                                            else {
                                                vm.replaceDoc(r.first)
                                                state.pushDocEdit(d0.file, r.first)
                                                say("Recognized ${res.words} words on ${pages(res.recognized)}. Now searchable and editable.")
                                            }
                                        }) {
                                            val out = ctx.newWorkFile()
                                            out to com.mobilepdf.editor.pdf.Ocr.recognize(d0.file, vm.displayFile(d0.file), out, vm.budget) { p, n ->
                                                vm.busy = "Recognizing text… page $p of $n"
                                            }
                                        }
                                    }
                                })
                                DropdownMenuItem({ Text("More tools…") }, { menu = false; withBaked { vm.screen = Screen.TOOLS } })
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(state.chrome || readAloud?.speaking == true) { Column {
                readAloud?.takeIf { it.speaking }?.let { ReadAloudBar(it) }
                state.textSel?.let { sel ->
                    val copyOk = doc.access.canCopy
                    SelectionActions(
                        sel, copyOk, doc.access.canModify,
                        onCopy = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(sel.text))
                            say("Copied ${sel.text.length} characters"); state.textSel = null
                        },
                        onShare = {
                            ctx.startActivity(android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, sel.text), null,
                            ))
                        },
                        onHighlight = {
                            state.add(sel.page, Annot.Mark(state.newId(), Palette[2], com.mobilepdf.editor.annot.MarkKind.HIGHLIGHT, sel.rects)); state.textSel = null
                        },
                        onRedact = {
                            state.add(sel.page, Annot.Redact(state.newId(), 0, sel.rects.map { r ->
                                val hh = r.height; Rect(r.left - hh * 0.06f, r.top - hh * 0.25f, r.right + hh * 0.06f, r.bottom + hh * 0.3f)
                            }, sel.text))
                            state.textSel = null
                            say("Marked for redaction. Open the Redact tool to apply.")
                        },
                        onAll = {
                            scope.launch {
                                val pt = runCatching { indexer.page(sel.page) }.getOrNull() ?: return@launch
                                val (a, b) = pt.ends() ?: return@launch
                                state.textSel = TextSel(sel.page, a, b, pt.select(a, b), pt.textBetween(a, b))
                            }
                        },
                        onClose = { state.textSel = null },
                    )
                    if (!copyOk) Text(
                        "The author of this PDF does not allow copying its text.",
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                    )
                }
                if (doc.access.canModify) ToolBar(state, { signDlg = true }, { pickFontFile.launch(FontTypes) }, { redactFind = true }, { redactConfirm = true }, { pictureRequest = true }, { stampDlg = true }) { p -> sizes?.getOrNull(p)?.aspect ?: 1.41f }
                else Surface(tonalElevation = 3.dp) {
                    Text(
                        "Read-only: the author of this PDF does not allow changes.",
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } }
        },
    ) { pad ->
        if (viewFile == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            return@Scaffold
        }
        if (renderer == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This PDF could not be displayed.")
                    TextButton({ vm.closeDoc() }) { Text("Close") }
                }
            }
            return@Scaffold
        }
        val sz = sizes
        val hScroll = rememberScrollState()
        // horizontal position to reach once the zoomed layout exists (its scroll range grows only after layout)
        var targetX by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(hScroll) {
            snapshotFlow { hScroll.maxValue to targetX }.collect { (max, t) ->
                if (t != null) {
                    val x = t.roundToInt().coerceIn(0, max)
                    hScroll.scrollTo(x)
                    if (x == t.roundToInt() || t <= 0f) targetX = null
                }
            }
        }
        // the pages keep left-to-right geometry even when the interface is right-to-left (Urdu)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(pad)
                .background(if (state.nightMode || androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF222222) else Color(0xFFE3E0E0))
                .pinchZoom(state, vm.budget.maxZoom, listState, hScroll, { targetX = it }) { p -> sizes?.getOrNull(p)?.aspect ?: 1.41f },
        ) {
            if (sz == null) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }
            val density = LocalDensity.current
            val maxW = maxWidth
            val pageW = maxW * state.zoom
            val containerPx = with(density) { maxW.toPx() }
            // Fit text width: zoom so the text (not the paper margins) fills the screen
            LaunchedEffect(state.fitWidthRequest) {
                if (state.fitWidthRequest == 0) return@LaunchedEffect
                val p = currentPage()
                val boxes = (p until minOf(p + 3, sz.size)).flatMap { i ->
                    runCatching { indexer.page(i).runs }.getOrDefault(emptyList()).filter { !it.invisible && it.text.length > 3 }
                }
                if (boxes.size < 3) { say("No text to fit on this page"); return@LaunchedEffect }
                // the middle 90% of line edges: a stray margin note (an arXiv stamp, a page number) doesn't count
                val lefts = boxes.map { it.rect.left }.sorted(); val rights = boxes.map { it.rect.right }.sorted()
                val l = lefts[(lefts.size * 0.05f).toInt()]; val r = rights[((rights.size - 1) * 0.95f).toInt()]
                val z = (0.96f / (r - l).coerceAtLeast(0.05f)).coerceIn(1f, vm.budget.maxZoom)
                if (z < 1.05f) { say("The text already fills the width"); return@LaunchedEffect }
                state.zoom = z
                targetX = ((l - 0.02f / z) * containerPx * z).coerceAtLeast(0f)
            }
            var committed by remember { mutableFloatStateOf(1f) }
            LaunchedEffect(state.zoom) { delay(220); committed = state.zoom }
            val renderPx = (containerPx * committed).roundToInt()
            // capable phones: once scrolling settles, render the neighbouring pages ahead of time
            LaunchedEffect(listState.firstVisibleItemIndex, renderPx, renderer) {
                val n = renderer.budget.prefetchPages
                if (n == 0) return@LaunchedEffect
                delay(300)
                val first = listState.firstVisibleItemIndex
                val last = first + listState.layoutInfo.visibleItemsInfo.size
                for (i in (last until last + n) + (first - n until first)) {
                    if (i in sz.indices) runCatching { renderer.render(i, renderPx) }
                }
            }
            val fieldsByPage = remember(state.fields) { state.fields.flatMap { f -> f.widgets.map { f to it } }.groupBy { it.second.page } }

            Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = state.zoom > 1.01f)) {
                LazyColumn(
                    Modifier.width(pageW),
                    state = listState,
                    userScrollEnabled = state.tool == Tool.VIEW,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(sz.size, key = { it }) { i ->
                        PageView(
                            index = i, size = sz[i], layoutWidth = pageW, renderWidthPx = renderPx,
                            renderer = renderer, indexer = indexer, state = state,
                            fields = fieldsByPage[i].orEmpty(),
                            onTextRequest = { p, at -> if (state.pickingFont) pickFontAt(p, at) else textDlg = TextDlg(p, at, null) },
                            onEditText = { p, a -> if (state.pickingFont) pickFontAt(p, a.pos) else textDlg = TextDlg(p, a.pos, a) },
                            onViewTap = ::onViewTap,
                            onReplaceText = { p, at ->
                                // tapped an earlier edit: change it again; else find the page's own line
                                val prev = state.annots[p].orEmpty().filterIsInstance<Annot.Replace>()
                                    .lastOrNull { com.mobilepdf.editor.annot.coverRect(it).contains(at) }
                                if (prev != null) replaceDlg = ReplaceDlg(p, prev.rect, prev.original, prev)
                                else scope.launch {
                                    val pt = runCatching { indexer.page(p) }.getOrNull()
                                    val run = pt?.runAt(at, state.tapSlop)
                                    when {
                                        run != null -> {
                                            val para = pt.paragraphAt(at, state.tapSlop)
                                            replaceDlg = ReplaceDlg(p, run.rect, run.text, null, run.size, run.font, run.invisible,
                                                para?.map { it.rect }, para?.let { pt.paragraphText(it) } ?: "")
                                        }
                                        pt == null || pt.isEmpty -> say("This page has no editable text. If it is a scan, use ⋮ → Recognize text (OCR) first.")
                                        else -> say("Tap on one of the outlined texts")
                                    }
                                }
                            },
                            onMessage = ::say,
                        )
                    }
                }
            }

            // page indicator + quick page buttons while a drawing tool disables scrolling
            val cur by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
            if (state.chrome) Surface(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clickable { gotoDlg = true },
                shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            ) {
                val zoomLabel = if (state.zoom > 1.01f) "  ·  ${(state.zoom * 100).roundToInt()}%" else ""
                Text("$cur / ${sz.size}$zoomLabel", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
            }
            if (state.tool != Tool.VIEW && state.chrome) Column(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
                FloatingStep(Icons.Filled.KeyboardArrowUp) { scope.launch { listState.animateScrollToItem((currentPage() - 1).coerceAtLeast(0)) } }
                FloatingStep(Icons.Filled.KeyboardArrowDown) { scope.launch { listState.animateScrollToItem((currentPage() + 1).coerceAtMost(sz.lastIndex)) } }
            }
        }
        } // left-to-right pages
    }

    // -------- dialogs
    if (gotoDlg) {
        val n = sizes?.size ?: 1
        TextPrompt("Go to page (1–$n)", label = "Page", confirm = "Go", onDone = { t ->
            t.trim().toIntOrNull()?.let { p -> scope.launch { listState.scrollToItem((p - 1).coerceIn(0, n - 1)) } }
            gotoDlg = false
        }, onCancel = { gotoDlg = false })
    }
    textDlg?.let { d ->
        TextPrompt(if (d.existing == null) "Add text" else "Edit text", initial = d.existing?.text ?: "", label = "Text", multiline = true,
            confirm = if (d.existing == null) "Add" else "Update",
            onDone = { t ->
                val e = d.existing
                if (e == null) {
                    val a = Annot.Text(state.newId(), state.color, d.at, t, state.textSize, state.textFont)
                    state.add(d.page, a)
                    // select it straight away so it can be moved and pinch-resized
                    state.selected = d.page to a.id
                    state.tool = Tool.SELECT
                    say("Drag to move it; pinch with two fingers to resize")
                } else state.replace(d.page, e.copy(text = t))
                textDlg = null
            }, onCancel = { textDlg = null })
    }
    replaceDlg?.let { d ->
        // typeface: keep the PDF's own font, or pick one (scans: OCR can't tell the typeface; serif is the
        // most common in printed documents)
        var style by remember(d) { mutableStateOf(if (d.scanned) "serif" else null as String?) }
        // a line inside a paragraph: edit the whole paragraph (re-wrapped), or just this line
        var whole by remember(d) { mutableStateOf(d.para != null) }
        val original = if (whole) d.paraText else d.original
        key(whole) { TextPrompt(
            if (whole) "Edit paragraph" else "Edit text", initial = d.existing?.text ?: original,
            label = (if (d.scanned) "Scanned text" else "Font: ${d.font}") + "  (empty = delete)",
            confirm = "Replace", multiline = true, allowEmpty = true,
            onDone = { t ->
                replaceDlg = null
                val newText = t.replace('\n', ' ').trim()
                if (newText != original) {
                    // applied to the document right away (PdfCraft-style): the page shows exactly what is saved,
                    // the old words are gone, and the step can be undone from the top bar
                    val before = doc.file
                    val rend = renderer
                    vm.launchOp("Editing text…", onDone = { r: Pair<File, TextEditor.Result> ->
                        vm.replaceDoc(r.first)
                        state.pushDocEdit(before, r.first)
                        val res = r.second
                        val grew = whole && d.para != null && res.linesWritten > d.para.size
                        when {
                            grew -> say("The paragraph now has ${res.linesWritten} lines (was ${d.para!!.size}): check it doesn't run into what follows")
                            res.wasScan -> say("Scanned words replaced in ${res.fallbackName}, on the paper's own colour")
                            res.missing.isNotEmpty() ->
                                say("${res.fontName} in this PDF has no \"${res.missing.toSet().joinToString("")}\"; those letters use ${res.fallbackName}")
                        }
                    }) {
                        vm.requireEditable()
                        // scanned text: sample the paper (around the words) and the ink (darkest pixels inside)
                        val colors = if (d.scanned && rend != null) sampleScanColors(rend, d.page, d.rect) else null
                        val out = ctx.newWorkFile()
                        val lines = if (whole && d.para != null) d.para else listOf(d.rect)
                        out to TextEditor.replaceBlock(before, d.page, lines, newText, out, colors?.first, colors?.second, style)
                    }
                }
            },
            onCancel = { replaceDlg = null },
            extra = {
                if (d.para != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    androidx.compose.material3.Switch(whole, { whole = it })
                    Text("  Whole paragraph (${d.para.size} lines)", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Font", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val options = (if (d.scanned) emptyList() else listOf(null to "Original")) +
                        listOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono") +
                        state.customFonts.map { "file:$it" to File(it).nameWithoutExtension } // uploaded fonts
                    options.forEach { (v, l) -> androidx.compose.material3.FilterChip(style == v, { style = v }, { Text(l) }) }
                }
            },
        ) }
    }
    /** Puts a signature (drawn strokes or a photo) into a signature field, or on the current page to be moved. */
    fun placeSignature(hw: Float, strokes: List<List<Offset>>, image: String?) {
            val target = signTarget
            signTarget = null
            val page = target?.first ?: currentPage().coerceIn(0, sizes?.lastIndex ?: 0)
            val aspect = sizes?.getOrNull(page)?.aspect ?: 1.4f
            val rect = if (target != null) {
                // fit inside the signature field, keeping the signature's proportions
                val box = target.second
                var wN = box.width
                var hN = wN * hw / aspect
                if (hN > box.height) { hN = box.height; wN = hN * aspect / hw }
                Rect(box.center.x - wN / 2, box.center.y - hN / 2, box.center.x + wN / 2, box.center.y + hN / 2)
            } else {
                val wN = 0.4f
                val hN = (wN * hw / aspect).coerceAtMost(0.4f)
                Rect(0.3f, 0.5f - hN / 2, 0.3f + wN, 0.5f + hN / 2)
            }
            val stamp = Annot.Stamp(state.newId(), 0xFF1A237E.toInt(), rect, strokes, image)
            state.add(page, stamp)
            if (target == null) {
                state.selected = page to stamp.id
                state.tool = Tool.SELECT
                say("Drag to move it; pinch with two fingers to resize")
            }
    }
    val signaturePhoto = java.io.File(ctx.filesDir, "signature.png")
    val pickP12 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pendingP12 = uri }
    // Insert a picture (PdfCraft: Add Image): placed in the middle of the page, selected, to move / pinch-resize
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.launchOp("Adding picture…", onDone = { r: Pair<File, Float> ->
            placeSignature(r.second, emptyList(), r.first.path)
            say("Drag to move it; pinch with two fingers to resize")
        }) {
            val bmp = PdfOps.decodeImage(ctx, uri, vm.budget.imageMaxDim.coerceAtMost(2000))
            val dir = File(ctx.filesDir, "images").apply { mkdirs() }
            val jpg = !bmp.hasAlpha()
            val f = File(dir, "pic_${System.currentTimeMillis()}.${if (jpg) "jpg" else "png"}")
            f.outputStream().use { bmp.compress(if (jpg) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            val hw = bmp.height.toFloat() / bmp.width.coerceAtLeast(1)
            bmp.recycle()
            f to hw
        }
    }
    LaunchedEffect(pictureRequest) { if (pictureRequest) { pictureRequest = false; pickPicture.launch("image/*") } }
    val pickSignature = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.launchOp("Reading signature…", onDone = { r: Pair<File, Float> ->
            SignatureImages.forget(r.first.path)
            placeSignature(r.second, emptyList(), r.first.path)
        }) { PdfOps.signatureFromPhoto(ctx, uri, signaturePhoto) }
    }
    if (signDlg) SignaturePad(
        initial = vm.signatures.load(),
        onCancel = { signDlg = false; signTarget = null },
        onDone = { strokes ->
            signDlg = false
            vm.signatures.save(strokes)
            val hw = strokes.flatten().maxOf { it.second }.coerceAtLeast(0.05f)
            placeSignature(hw, strokes.map { s -> s.map { Offset(it.first, it.second / hw) } }, null)
        },
        onPhoto = { signDlg = false; pickSignature.launch("image/*") },
        savedPhoto = signaturePhoto.exists(),
        onSavedPhoto = {
            signDlg = false
            android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                android.graphics.BitmapFactory.decodeFile(signaturePhoto.path, it)
                placeSignature(it.outHeight.toFloat() / it.outWidth.coerceAtLeast(1), emptyList(), signaturePhoto.path)
            }
        },
    )
    // -------- redaction
    fun applyRedactions(then: (() -> Unit)? = null) {
        val marks = state.annots.mapValues { (_, l) -> l.filterIsInstance<Annot.Redact>().flatMap { it.rects } }.filterValues { it.isNotEmpty() }
        if (marks.isEmpty()) return
        val before = doc.file
        val width = vm.budget.maxBitmapWidth.coerceIn(1400, 2400)
        vm.launchOp("Redacting…", onDone = { r: Pair<File, com.mobilepdf.editor.pdf.Redactor.Report> ->
            state.removeWhere { it is Annot.Redact }
            vm.replaceDoc(r.first)
            state.pushDocEdit(before, r.first)
            val rep = r.second
            val parts = listOfNotNull(
                if (rep.glyphs > 0) "${rep.glyphs} characters" else null,
                if (rep.images > 0) "${rep.images} image(s)" else null,
                if (rep.drawings > 0) "${rep.drawings} drawing(s)" else null,
                if (rep.annots > 0) "${rep.annots} comment/link/field(s)" else null,
                if (rep.metadata + rep.altTexts > 0) "${rep.metadata + rep.altTexts} metadata item(s)" else null,
            )
            var msg = if (parts.isEmpty()) "Redacted: the marked areas are now blacked out" else "Removed for good: " + parts.joinToString(", ")
            if (rep.flattened.isNotEmpty()) msg += ". Page(s) ${rep.flattened.sorted().joinToString { (it + 1).toString() }} became pictures (their text can't be selected; OCR can restore it)"
            if (rep.bookmarksMention > 0) msg += ". ${rep.bookmarksMention} bookmark(s) still mention the removed text"
            say(msg + ". Save to keep it.")
            then?.invoke()
        }) {
            vm.requireEditable()
            val out = ctx.newWorkFile()
            out to com.mobilepdf.editor.pdf.Redactor.apply(before, marks, out, rasterWidth = width)
        }
    }
    if (redactConfirm) AlertDialog(
        onDismissRequest = { redactConfirm = false },
        title = { Text("Apply ${state.redactCount} redaction(s)?") },
        text = {
            Text(
                "Everything under the marks (text, images, drawings, links and comments) is removed from the file, " +
                    "not just covered, and replaced by black boxes. You can undo until you close this file; once saved, it's permanent.",
            )
        },
        confirmButton = { TextButton({ redactConfirm = false; applyRedactions() }) { Text("Apply") } },
        dismissButton = { TextButton({ redactConfirm = false }) { Text("Cancel") } },
    )
    afterRedactAsk?.let { next ->
        AlertDialog(
            onDismissRequest = { afterRedactAsk = null },
            title = { Text("Redaction marks not applied") },
            text = { Text("${state.redactCount} mark(s) haven't been applied, so nothing under them has been removed yet.") },
            confirmButton = { TextButton({ afterRedactAsk = null; applyRedactions { withBaked(next) } }) { Text("Apply, then continue") } },
            dismissButton = {
                Row {
                    TextButton({ afterRedactAsk = null }) { Text("Cancel") }
                    TextButton({ afterRedactAsk = null; state.removeWhere { it is Annot.Redact }; withBaked(next) }) { Text("Drop marks") }
                }
            },
        )
    }
    if (redactFind) RedactFindSheet(onDismiss = { redactFind = false }) { terms, matchCase, patterns ->
        redactFind = false
        val n = sizes?.size ?: 0
        scope.launch {
            val found = ArrayList<Pair<Int, Annot>>()
            var pages = 0
            for (i in 0 until n) {
                val pt = runCatching { indexer.page(i) }.getOrNull() ?: continue
                val before = found.size
                for (t in terms) for (rects in pt.find(t, matchCase)) found += i to Annot.Redact(state.newId(), 0, rects.map { r ->
                    val hh = r.height; Rect(r.left - hh * 0.06f, r.top - hh * 0.25f, r.right + hh * 0.06f, r.bottom + hh * 0.3f)
                }, t)
                for (p in patterns) for ((txt, rects) in pt.findRegex(p.regex, p::accept)) found += i to Annot.Redact(state.newId(), 0, rects.map { r ->
                    val hh = r.height; Rect(r.left - hh * 0.06f, r.top - hh * 0.25f, r.right + hh * 0.06f, r.bottom + hh * 0.3f)
                }, txt)
                if (found.size > before) pages++
            }
            state.addMany(found)
            state.tool = Tool.REDACT
            say(if (found.isEmpty()) "No matches found" else "Marked ${found.size} match(es) on $pages page(s). Check them, then tap Apply.")
        }
    }
    noteDlg?.let { (page, at, existing) ->
        TextPrompt(
            if (existing == null) "New note" else "Note", initial = existing?.text ?: "", label = if (existing == null) "Note" else "Note (empty = delete)",
            multiline = true, allowEmpty = existing != null, confirm = if (existing == null) "Add" else "Save",
            onDone = { t ->
                noteDlg = null
                when {
                    existing == null -> if (t.isNotBlank()) state.add(page, Annot.Note(state.newId(), 0xFFFFD54F.toInt(), at, t.trim()))
                    t.isBlank() -> state.remove(page, existing.id)
                    else -> state.replace(page, existing.copy(text = t.trim()))
                }
            },
            onCancel = { noteDlg = null },
        )
    }
    // a signed document says so when it opens
    LaunchedEffect(sigChecks) {
        val c = sigChecks?.firstOrNull() ?: return@LaunchedEffect
        say("Signed by ${c.signer} · ${statusLabel(c.status)}" + if ((sigChecks?.size ?: 0) > 1) " (+${sigChecks!!.size - 1} more, see ⋮ → Signatures)" else "")
    }
    if (dsigSheet) {
        LaunchedEffect(Unit) { signer = withContext(Dispatchers.IO) { com.mobilepdf.editor.pdf.DigitalSign.current() } }
        DigitalSignSheet(
            signer, onDismiss = { dsigSheet = false },
            onCreate = { name ->
                vm.launchOp("Creating your signing key…", onDone = { s: com.mobilepdf.editor.pdf.DigitalSign.Signer -> signer = s }) {
                    com.mobilepdf.editor.pdf.DigitalSign.createOwn(name)
                }
            },
            onImport = { pickP12.launch(arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream", "*/*")) },
            onForget = { com.mobilepdf.editor.pdf.DigitalSign.forget(); signer = null },
            onSign = { reason, place ->
                dsigSheet = false
                val s = signer ?: return@DigitalSignSheet
                withBaked {
                    val before = vm.doc?.file ?: return@withBaked
                    vm.launchOp("Signing…", onDone = { f: File ->
                        vm.replaceDoc(f); state.pushDocEdit(before, f)
                        say("Signed by ${s.name}. Save to keep it; any change after this breaks the signature.")
                    }) {
                        vm.requireEditable()
                        ctx.newWorkFile().also { com.mobilepdf.editor.pdf.DigitalSign.sign(before, it, s, reason, place) }
                    }
                }
            },
        )
    }
    pendingP12?.let { uri ->
        CertPasswordDialog(onDismiss = { pendingP12 = null }) { pw ->
            pendingP12 = null
            vm.launchOp("Importing certificate…", onDone = { s: com.mobilepdf.editor.pdf.DigitalSign.Signer -> signer = s; say("Certificate of ${s.name} ready") }) {
                try {
                    ctx.contentResolver.openInputStream(uri)!!.use { com.mobilepdf.editor.pdf.DigitalSign.importP12(it, pw) }
                } catch (e: java.io.IOException) {
                    throw IllegalArgumentException("wrong password, or not a certificate file")
                } finally { pw.fill(' ') }
            }
        }
    }
    if (sigsSheet) sigChecks?.let { SignaturesSheet(it) { sigsSheet = false } }
    breakSigAsk?.let { next ->
        AlertDialog(
            onDismissRequest = { breakSigAsk = null },
            title = { Text("This will break the signature") },
            text = { Text("The document is digitally signed by ${sigChecks?.firstOrNull()?.signer}. Saving your changes into it makes that signature invalid.") },
            confirmButton = { TextButton({ breakSigAsk = null; breakSigOk = true; withBaked(next) }) { Text("Continue") } },
            dismissButton = { TextButton({ breakSigAsk = null }) { Text("Cancel") } },
        )
    }
    if (stampDlg) StampDialog(onDismiss = { stampDlg = false }) { text, color ->
        stampDlg = false
        val page = currentPage().coerceIn(0, sizes?.lastIndex ?: 0)
        val aspect = sizes?.getOrNull(page)?.aspect ?: 1.41f
        // about 3.4 : 1, a third of the page wide, in the middle of the page on screen
        val wN = 0.34f; val hN = wN / 3.4f / aspect
        val seal = Annot.Seal(state.newId(), color, Rect(0.5f - wN / 2, 0.42f - hN / 2, 0.5f + wN / 2, 0.42f + hN / 2), text)
        state.add(page, seal)
        state.selected = page to seal.id
        state.tool = Tool.SELECT
        say("Drag to move it; pinch with two fingers to resize")
    }
    if (commentsSheet) CommentsSheet(
        state,
        onGoTo = { p -> commentsSheet = false; scope.launch { listState.animateScrollToItem(p) } },
        onEdit = { p, a -> commentsSheet = false; if (a is Annot.Note) noteDlg = Triple(p, a.pos, a) },
        onDismiss = { commentsSheet = false },
    )
    if (formSheet) FormSheet(
        state, onDismiss = { formSheet = false },
        onGoTo = { p -> formSheet = false; scope.launch { listState.animateScrollToItem(p) } },
        onSign = { f -> formSheet = false; f.widgets.firstOrNull()?.let { signTarget = it.page to it.rect; signDlg = true } },
    )
    fieldEdit?.let { f -> FieldEditDialog(state, f) { fieldEdit = null } }
    if (bookmarksSheet) BookmarksSheet(
        bookmarks, { bookmarksSheet = false },
        onGoTo = { p ->
            bookmarksSheet = false
            scope.launch { listState.animateScrollToItem(p.coerceIn(0, sizes?.lastIndex ?: 0)) }
        },
        canEdit = doc.access.canModify, currentPage = currentPage(),
        // a new bookmark is named after the page's heading: its biggest text
        suggestTitle = { p ->
            runCatching { indexer.page(p) }.getOrNull()?.runs?.filter { !it.invisible && it.text.length in 3..80 }
                ?.maxByOrNull { it.size }?.text ?: "Page ${p + 1}"
        },
        onSave = { list ->
            bookmarksSheet = false
            val before = doc.file
            vm.launchOp("Saving bookmarks…", onDone = { f: File ->
                vm.replaceDoc(f); state.pushDocEdit(before, f)
                say(if (list.isEmpty()) "Bookmarks removed" else "Bookmarks saved")
            }) {
                vm.requireEditable()
                ctx.newWorkFile().also { com.mobilepdf.editor.pdf.Navigation.setOutline(before, list, it) }
            }
        },
    )
    linkAsk?.let { url ->
        AlertDialog(
            onDismissRequest = { linkAsk = null },
            title = { Text("Open link?") },
            text = { Text(url) },
            confirmButton = {
                TextButton({
                    linkAsk = null
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { say("No app can open this link") }
                }) { Text("Open") }
            },
            dismissButton = { TextButton({ linkAsk = null }) { Text("Cancel") } },
        )
    }
    restoreDraft?.let { d ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restore unsaved work?") },
            text = { Text("You have unsaved changes to this document from an earlier session.") },
            confirmButton = {
                TextButton({
                    d.workFile?.let(::File)?.takeIf { it.exists() }?.let { vm.adoptWorkFile(it) }
                    state.restore(d.annots)
                    state.formValues.putAll(d.formValues)
                    restoreDraft = null; draftChecked = true
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton({ vm.drafts.delete(draftKey); restoreDraft = null; draftChecked = true }) { Text("Discard") }
            },
        )
    }
    if (exitDlg) AlertDialog(
        onDismissRequest = { exitDlg = false },
        title = { Text("Unsaved changes") },
        text = { Text("Save your changes to “${doc.name}” before closing?") },
        confirmButton = { TextButton({ exitDlg = false; save() }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton({ exitDlg = false }) { Text("Cancel") }
                TextButton({ exitDlg = false; vm.closeDoc(discard = true) }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

@Composable
private fun FloatingStep(icon: ImageVector, onClick: () -> Unit) {
    Surface(
        Modifier.padding(vertical = 4.dp).size(44.dp).clickable(onClick = onClick), shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shadowElevation = 4.dp,
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, null) } }
}

/**
 * Two-finger pinch zoom and pan. Watches events *before* children (Initial pass) and only takes over
 * with 2+ fingers, so one-finger scrolling and drawing are untouched.
 *
 * Zooms around the point between the fingers: every page grows by the same factor, so a point at
 * distance d below the top of the first visible page moves to d × f; scrolling by d × (f − 1) keeps it
 * under the fingers. Horizontally likewise, applied once the wider layout exists.
 */
private fun Modifier.pinchZoom(
    state: ViewerState, maxZoom: Float, list: LazyListState, h: ScrollState, scrollXTo: (Float) -> Unit,
    aspectOf: (Int) -> Float,
): Modifier = pointerInput(maxZoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // With an item selected (text box, signature, shape…), pinching resizes that item, not the page.
        var resizing: Boolean? = null
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.count { it.pressed } >= 2 && resizing == null) {
                resizing = state.selectedAnnot() != null
                if (resizing == true) state.beginChange() // the whole pinch undoes as one step
            }
            if (ev.changes.count { it.pressed } >= 2 && resizing == true) {
                val z = ev.calculateZoom()
                val sel = state.selectedAnnot()
                if (sel != null && z != 1f) state.replaceLive(sel.first, sel.second.scaled(z, aspectOf(sel.first)))
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (ev.changes.count { it.pressed } >= 2) {
                val c = ev.calculateCentroid(useCurrent = true)
                val pan = ev.calculatePan()
                val old = state.zoom
                val new = (old * ev.calculateZoom()).coerceIn(1f, maxZoom)
                val f = new / old
                if (f != 1f) {
                    list.dispatchRawDelta((list.firstVisibleItemScrollOffset + c.y) * (f - 1f))
                    scrollXTo(if (new <= 1.01f) 0f else (h.value + c.x) * f - c.x)
                    state.zoom = new
                }
                // moving both fingers together pans the page
                if (pan.y != 0f) list.dispatchRawDelta(-pan.y)
                if (pan.x != 0f && new > 1.01f) h.dispatchRawDelta(-pan.x)
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (ev.changes.any { it.pressed })
    }
}

@Composable
private fun SearchBar(state: ViewerState, onSearch: () -> Unit, onStep: (Int) -> Unit, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } } // keyboard up straight away
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClose) { Icon(Icons.Filled.Close, "Close search") }
            OutlinedTextField(
                state.query, { state.query = it },
                Modifier.weight(1f).focusRequester(focus), singleLine = true, placeholder = { Text("Find in document") },
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search, keyboardType = KeyboardType.Text),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
            )
            Text(
                // a long document: matches so far and how far the search has got
                if (state.searchBusy) "${if (state.hits.isEmpty()) "" else "${state.currentHit + 1}/${state.hits.size}+ · "}p${state.searchedPages + 1}/${state.searchTotal}"
                else if (state.hits.isEmpty()) "" else "${state.currentHit + 1}/${state.hits.size}",
                Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium,
            )
            IconButton({ onStep(-1) }, enabled = state.hits.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
            IconButton({ onStep(1) }, enabled = state.hits.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
        }
    }
}

// ------------------------------------------------------------------------------------ tool bar

private class ToolDef(val tool: Tool, val icon: ImageVector, val label: String)

private val tools = listOf(
    ToolDef(Tool.VIEW, Icons.Filled.TouchApp, "Read"),
    ToolDef(Tool.EDIT_TEXT, Icons.Filled.Title, "Edit text"),
    ToolDef(Tool.HIGHLIGHT, Icons.Filled.Highlight, "Highlight"),
    ToolDef(Tool.UNDERLINE, Icons.Filled.FormatUnderlined, "Underline"),
    ToolDef(Tool.STRIKE, Icons.Filled.StrikethroughS, "Strike"),
    ToolDef(Tool.PEN, Icons.Filled.Edit, "Pen"),
    ToolDef(Tool.MARKER, Icons.Filled.Brush, "Marker"),
    ToolDef(Tool.TEXT, Icons.Filled.TextFields, "Text"),
    ToolDef(Tool.TICK, Icons.Filled.Check, "Tick"),
    ToolDef(Tool.NOTE, Icons.AutoMirrored.Filled.Comment, "Note"),
    ToolDef(Tool.REDACT, Icons.Filled.VisibilityOff, "Redact"),
    ToolDef(Tool.RECT, Icons.Filled.CropSquare, "Box"),
    ToolDef(Tool.OVAL, Icons.Filled.RadioButtonUnchecked, "Oval"),
    ToolDef(Tool.LINE, Icons.Filled.HorizontalRule, "Line"),
    ToolDef(Tool.ARROW, Icons.AutoMirrored.Filled.CallMade, "Arrow"),
    ToolDef(Tool.CLOUD, Icons.Filled.Cloud, "Cloud"),
    ToolDef(Tool.AREA, Icons.Filled.Crop54, "Area"),
    ToolDef(Tool.ERASER, Icons.Filled.AutoFixNormal, "Eraser"),
    ToolDef(Tool.SELECT, Icons.Filled.OpenWith, "Move"),
)

@Composable
private fun ToolBar(
    state: ViewerState, onSign: () -> Unit, onUploadFont: () -> Unit, onRedactFind: () -> Unit, onRedactApply: () -> Unit,
    onPicture: () -> Unit, onStamp: () -> Unit, aspectOf: (Int) -> Float,
) {
    Surface(tonalElevation = 3.dp) {
        // navigationBarsPadding: on Android 15+ the app draws under the system navigation bar
        Column(Modifier.navigationBarsPadding().padding(bottom = 4.dp)) {
            val sel = state.selectedAnnot()
            when {
                sel != null -> SelectionBar(state, sel.first, sel.second, aspectOf(sel.first))
                state.tool == Tool.REDACT -> RedactBar(state, onRedactFind, onRedactApply)
                state.tool == Tool.NOTE -> Text(
                    "Tap where the note goes · tap a note to read or change it",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.tool == Tool.EDIT_TEXT -> Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        "Tap outlined text to change it · colour = font",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // legend: every font seen so far, in its outline colour
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                        items(state.fontColors.size) { i ->
                            val (font, color) = state.fontColors.entries.elementAt(i).toPair()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(Color(color), RoundedCornerShape(3.dp)))
                                Text(" $font", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
                state.tool in setOf(Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKE, Tool.PEN, Tool.MARKER, Tool.TEXT, Tool.RECT, Tool.OVAL, Tool.LINE, Tool.ARROW, Tool.CLOUD, Tool.AREA, Tool.TICK) -> Column {
                    if (state.tool == Tool.TEXT) FontBar(state, onUploadFont)
                    OptionsBar(state)
                }
            }
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // most-used first, so they are visible without scrolling: Read, Edit text, Sign
                items(2) { i ->
                    val t = tools[i]
                    ToolButton(t.icon, t.label, state.tool == t.tool) { selectTool(state, t.tool) }
                }
                item { ToolButton(Icons.Filled.Draw, "Sign", false, onClick = onSign) }
                item { ToolButton(Icons.Filled.AddPhotoAlternate, "Picture", false, onClick = onPicture) }
                item { ToolButton(Icons.Filled.Approval, "Stamp", false, onClick = onStamp) }
                items(tools.size - 2) { i ->
                    val t = tools[i + 2]
                    ToolButton(t.icon, t.label, state.tool == t.tool) { selectTool(state, t.tool) }
                }
                // Undo / Redo live in the top bar, always one tap away
            }
        }
    }
}

private fun selectTool(state: ViewerState, t: Tool) {
    state.tool = t; state.selected = null; state.textSel = null
    // sensible default colours: yellow for highlighters, red otherwise
    val yellow = Palette[2]
    if ((t == Tool.HIGHLIGHT || t == Tool.MARKER || t == Tool.AREA) && state.color == Palette[0]) state.color = yellow
    if (t == Tool.TICK && (state.color == yellow || state.color == Palette[0])) state.color = Palette[5] // forms: dark ink
    if ((t == Tool.PEN || t == Tool.TEXT || t == Tool.RECT || t == Tool.OVAL || t == Tool.LINE || t == Tool.ARROW || t == Tool.CLOUD) && state.color == yellow) state.color = Palette[0]
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (!enabled) MaterialTheme.colorScheme.outline else if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.width(64.dp).background(bg, RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = fg)
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/** Mime types offered when uploading a font (file managers label .ttf files inconsistently). */
private val FontTypes = arrayOf(
    "font/*", "application/x-font-ttf", "application/x-font-truetype", "application/font-sfnt",
    "application/vnd.ms-opentype", "application/x-font-otf", "application/octet-stream",
)

/**
 * Text tool, top row: eyedropper (copy font, weight, size and colour from text on the page), bold,
 * italic, typeface (standard, copied, or uploaded), and "upload a font".
 */
@Composable
private fun FontBar(state: ViewerState, onUploadFont: () -> Unit) {
    val f = state.textFont
    val custom = f.file != null
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.FilledTonalIconToggleButton(state.pickingFont, { state.pickingFont = it }) {
            Icon(Icons.Filled.Colorize, "Copy style from text on the page")
        }
        androidx.compose.material3.IconToggleButton(f.bold, { b -> state.textFont = f.copy(bold = b, pdfFont = null) }, enabled = !custom) {
            Icon(Icons.Filled.FormatBold, "Bold")
        }
        androidx.compose.material3.IconToggleButton(f.italic, { i -> state.textFont = f.copy(italic = i, pdfFont = null) }, enabled = !custom) {
            Icon(Icons.Filled.FormatItalic, "Italic")
        }
        if (f.pdfFont != null) androidx.compose.material3.FilterChip(true, {}, { Text(f.pdfFont, maxLines = 1) })
        listOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono").forEach { (v, l) ->
            androidx.compose.material3.FilterChip(
                f.pdfFont == null && !custom && f.family == v,
                { state.textFont = f.copy(family = v, pdfFont = null, file = null, fromPage = -1) },
                { Text(l) },
            )
        }
        state.customFonts.forEach { path ->
            androidx.compose.material3.FilterChip(
                f.file == path,
                { state.textFont = com.mobilepdf.editor.annot.FontChoice(file = path) },
                { Text(File(path).nameWithoutExtension, maxLines = 1) },
            )
        }
        TextButton(onUploadFont) { Icon(Icons.Filled.UploadFile, null); Text(" Font") }
    }
    if (state.pickingFont) Text(
        "Tap any text on the page to copy its font, size and colour",
        Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun OptionsBar(state: ViewerState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // forms take dark ink: the tick tool offers black, blue and red, leaving room for its marks
        (if (state.tool == Tool.TICK) listOf(Palette[5], Palette[4], Palette[0]) else Palette).forEach { c ->
            Box(
                Modifier.padding(end = 8.dp).size(if (state.color == c) 32.dp else 26.dp).background(Color(c), CircleShape)
                    .clickable { state.color = c },
            )
        }
        // a colour copied with the eyedropper
        if (state.color !in Palette) Box(
            Modifier.padding(end = 8.dp).size(32.dp).background(Color(state.color), CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
        )
        when (state.tool) {
            Tool.TEXT -> listOf("S" to 0.028f, "M" to 0.04f, "L" to 0.06f).forEach { (l, v) ->
                TextButton({ state.textSize = v }) { Text(l, color = if (state.textSize == v) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKE -> Text("Drag over the words", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Tool.TICK -> listOf(com.mobilepdf.editor.annot.ShapeKind.TICK to "✓", com.mobilepdf.editor.annot.ShapeKind.CROSS to "✗", com.mobilepdf.editor.annot.ShapeKind.DOT to "●").forEach { (k, l) ->
                TextButton({ state.tickKind = k }) { Text(l, style = MaterialTheme.typography.titleLarge, color = if (state.tickKind == k) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Tool.AREA -> Text("Drag a box: works on scans and pictures too", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Slider(state.strokeWidth, { state.strokeWidth = it }, Modifier.weight(1f), valueRange = 0.002f..0.02f)
        }
    }
}

@Composable
private fun SelectionBar(state: ViewerState, page: Int, a: Annot, aspect: Float) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ state.remove(page, a.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
        IconButton({ state.replace(page, a.scaled(0.88f, aspect)) }) { Icon(Icons.Filled.Remove, "Smaller") }
        IconButton({ state.replace(page, a.scaled(1.14f, aspect)) }) { Icon(Icons.Filled.Add, "Larger") }
        Text("Drag to move · pinch to resize", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ state.selected = null }) { Text("Done") }
    }
}

/**
 * For editing OCR text over a scan: the paper colour (median of a ring just outside the words) and the
 * ink colour (median of the darkest tenth of the pixels inside them), from a small render of the page.
 */
private suspend fun sampleScanColors(r: PageRenderer, page: Int, box: Rect): Pair<Int, Int> {
    val bmp = r.render(page, 900)
    val w = bmp.width; val h = bmp.height
    fun px(x: Float, y: Float) = bmp.getPixel((x * w).toInt().coerceIn(0, w - 1), (y * h).toInt().coerceIn(0, h - 1))
    fun lum(c: Int) = (android.graphics.Color.red(c) * 299 + android.graphics.Color.green(c) * 587 + android.graphics.Color.blue(c) * 114) / 1000
    val ring = ArrayList<Int>()
    val m = box.height * 0.6f
    for (i in 0..40) {
        val t = i / 40f
        ring += px(box.left - m + t * (box.width + 2 * m), box.top - m)
        ring += px(box.left - m + t * (box.width + 2 * m), box.bottom + m)
    }
    val inside = ArrayList<Int>()
    for (i in 0..30) for (j in 0..6) inside += px(box.left + i / 30f * box.width, box.top + j / 6f * box.height)
    val paper = ring.sortedBy(::lum)[ring.size / 2]
    val pl = lum(paper)
    val ink = inside.sortedByDescending { abs(lum(it) - pl) }.take((inside.size / 10).coerceAtLeast(1))
    return paper to ink[ink.size / 2]
}
