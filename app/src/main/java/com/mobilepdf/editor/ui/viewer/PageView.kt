package com.mobilepdf.editor.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.annot.MarkKind
import com.mobilepdf.editor.annot.ShapeKind
import com.mobilepdf.editor.annot.bounds
import com.mobilepdf.editor.annot.hit
import com.mobilepdf.editor.annot.moved
import com.mobilepdf.editor.pdf.FieldType
import com.mobilepdf.editor.pdf.FormField
import com.mobilepdf.editor.pdf.FormWidget
import com.mobilepdf.editor.pdf.Forms
import com.mobilepdf.editor.pdf.PageRenderer
import com.mobilepdf.editor.pdf.PageSize
import com.mobilepdf.editor.pdf.PageText
import com.mobilepdf.editor.pdf.TextIndexer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val NightFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
)

/** One page: bitmap + annotation overlay + the gesture handling of the active tool. */
@Composable
fun PageView(
    index: Int, size: PageSize, layoutWidth: Dp, renderWidthPx: Int,
    renderer: PageRenderer, indexer: TextIndexer, state: ViewerState,
    fields: List<Pair<FormField, FormWidget>>,
    onTextRequest: (page: Int, at: Offset) -> Unit,
    onEditText: (page: Int, a: Annot.Text) -> Unit,
    onViewTap: (page: Int, at: Offset) -> Unit,
    onReplaceText: (page: Int, at: Offset) -> Unit,
    onMessage: (String) -> Unit,
) {
    var bmp by remember(index, renderer) { mutableStateOf<Bitmap?>(renderer.cached(index, renderWidthPx)) }
    LaunchedEffect(index, renderWidthPx, renderer, renderer.version) {
        if (renderer.cached(index, renderWidthPx) == null) delay(renderer.budget.renderDebounceMs) // skip pages flung past
        bmp = runCatching { renderer.render(index, renderWidthPx) }.getOrNull() ?: bmp
    }

    val aspect = size.aspect
    val tm = rememberTextMeasurer()
    val annots = state.annots[index].orEmpty()
    val selId = state.selected?.takeIf { it.first == index }?.second
    val pageHits = state.hits.filter { it.page == index }
    val current = state.hits.getOrNull(state.currentHit)

    // transient (not yet committed) drawing state
    var livePts by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var liveA by remember { mutableStateOf<Offset?>(null) }
    var liveB by remember { mutableStateOf<Offset?>(null) }
    var liveRects by remember { mutableStateOf<List<Rect>>(emptyList()) }
    // keyed on the indexer: after an edit the document is a new file, and its text must be read again
    var pageText by remember(indexer) { mutableStateOf<PageText?>(null) }
    // gesture handlers live as long as the tool stays the same; they must always call the current callbacks
    // (which read the current file), never the ones from when the tool was picked
    val textRequestNow by androidx.compose.runtime.rememberUpdatedState(onTextRequest)
    val editTextNow by androidx.compose.runtime.rememberUpdatedState(onEditText)
    val viewTapNow by androidx.compose.runtime.rememberUpdatedState(onViewTap)
    val replaceTextNow by androidx.compose.runtime.rememberUpdatedState(onReplaceText)
    val indexerNow by androidx.compose.runtime.rememberUpdatedState(indexer)
    // Edit-text mode: outline every editable run, coloured by its font
    var runsText by remember(index, indexer) { mutableStateOf<PageText?>(null) }
    LaunchedEffect(index, indexer, state.tool) {
        if (state.tool == Tool.EDIT_TEXT && runsText == null) {
            val pt = runCatching { indexerNow.page(index) }.getOrNull()
            pt?.runs?.forEach { state.colorFor(if (it.invisible) OCR_FONT else it.font) }
            runsText = pt
        }
    }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var downAt by remember { mutableStateOf<Offset?>(null) }

    Box(Modifier.width(layoutWidth).aspectRatio(size.w / size.h).background(Color.White)) {
        bmp?.let {
            Image(
                it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                colorFilter = if (state.nightMode) NightFilter else null,
            )
        }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    // drag callbacks report the position only after the touch slop is crossed (~8 dp later);
                    // remember the real touch-down so strokes and selections start under the finger
                    awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
                }
                .pointerInput(state.tool, index, indexer) {
                    gestures(state, index, aspect, { p, a -> textRequestNow(p, a) }, { p, a -> editTextNow(p, a) }, { p, a -> viewTapNow(p, a) }, { p, a -> replaceTextNow(p, a) })
                }
                .pointerInput(state.tool, index, indexer) {
                    // drawing tools: one pointer-input block per tool family
                    // the page's size is read at every touch: it changes with pinch-zoom while the tool stays
                    val input = this
                    fun n(o: Offset) = Offset((o.x / input.size.width).coerceIn(0f, 1f), (o.y / input.size.height).coerceIn(0f, 1f))
                    fun start(o: Offset) = n(downAt ?: o)
                    when (state.tool) {
                        Tool.PEN, Tool.MARKER -> detectDragGestures(
                            onDragStart = { livePts = listOf(start(it), n(it)) },
                            onDrag = { c, _ ->
                                val p = n(c.position)
                                val last = livePts.lastOrNull()
                                if (last == null || abs(last.x - p.x) + abs(last.y - p.y) > 0.0015f) livePts = livePts + p
                            },
                            onDragEnd = {
                                if (livePts.size > 1) {
                                    val marker = state.tool == Tool.MARKER
                                    state.add(index, Annot.Ink(
                                        state.newId(), state.color,
                                        if (marker) max(state.strokeWidth * 4f, 0.022f) else state.strokeWidth, marker, livePts,
                                    ))
                                }
                                livePts = emptyList()
                            },
                            onDragCancel = { livePts = emptyList() },
                        )
                        Tool.RECT, Tool.OVAL, Tool.LINE, Tool.ARROW, Tool.CLOUD, Tool.AREA -> detectDragGestures(
                            onDragStart = { liveA = start(it); liveB = n(it) },
                            onDrag = { c, _ -> liveB = n(c.position) },
                            onDragEnd = {
                                val a = liveA; val b = liveB
                                if (a != null && b != null && (abs(a.x - b.x) + abs(a.y - b.y)) > 0.01f) {
                                    val kind = when (state.tool) {
                                        Tool.RECT -> ShapeKind.RECT; Tool.OVAL -> ShapeKind.OVAL
                                        Tool.LINE -> ShapeKind.LINE; Tool.CLOUD -> ShapeKind.CLOUD; Tool.AREA -> ShapeKind.AREA
                                        else -> ShapeKind.ARROW
                                    }
                                    state.add(index, Annot.Shape(state.newId(), state.color, state.strokeWidth, kind, a, b))
                                }
                                liveA = null; liveB = null
                            },
                            onDragCancel = { liveA = null; liveB = null },
                        )
                        Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKE -> detectDragGestures(
                            onDragStart = {
                                liveA = start(it); liveB = n(it)
                                scope.launch { pageText = runCatching { indexerNow.page(index) }.getOrNull() }
                            },
                            onDrag = { c, _ ->
                                liveB = n(c.position)
                                pageText?.let { pt -> liveRects = pt.select(liveA ?: return@let, liveB ?: return@let) }
                            },
                            onDragEnd = {
                                val pt = pageText
                                if (liveRects.isNotEmpty()) {
                                    val kind = when (state.tool) {
                                        Tool.HIGHLIGHT -> MarkKind.HIGHLIGHT; Tool.UNDERLINE -> MarkKind.UNDERLINE; else -> MarkKind.STRIKE
                                    }
                                    state.add(index, Annot.Mark(state.newId(), state.color, kind, liveRects))
                                } else if (pt != null && pt.isEmpty) {
                                    onMessage("This page has no selectable text (scanned?). Use the Marker instead.")
                                }
                                liveRects = emptyList(); liveA = null; liveB = null
                            },
                            onDragCancel = { liveRects = emptyList(); liveA = null; liveB = null },
                        )
                        Tool.ERASER -> detectDragGestures(
                            onDragStart = { state.beginChange(); eraseAt(state, index, start(it), aspect); eraseAt(state, index, n(it), aspect) },
                            onDrag = { c, _ -> eraseAt(state, index, n(c.position), aspect) },
                        )
                        Tool.SELECT -> detectDragGestures(
                            onDragStart = { o ->
                                val p = start(o)
                                val sel = state.selectedAnnot()
                                if (sel == null || sel.first != index || !sel.second.hit(p, 0.02f, aspect)) {
                                    // pick what is under the finger, so a drag can grab immediately
                                    state.annots[index].orEmpty().lastOrNull { it.hit(p, 0.02f, aspect) }
                                        ?.let { state.selected = index to it.id }
                                }
                                state.beginChange()
                            },
                            onDrag = { c, delta ->
                                val sel = state.selectedAnnot()
                                if (sel != null && sel.first == index) {
                                    c.consume()
                                    state.replaceLive(index, sel.second.moved(Offset(delta.x / input.size.width, delta.y / input.size.height)))
                                }
                            },
                        )
                        Tool.REDACT -> detectDragGestures(
                            onDragStart = {
                                liveA = start(it); liveB = n(it)
                                if (!state.redactArea) scope.launch { pageText = runCatching { indexerNow.page(index) }.getOrNull() }
                            },
                            onDrag = { c, _ ->
                                liveB = n(c.position)
                                if (!state.redactArea) pageText?.let { pt -> liveRects = pt.select(liveA ?: return@let, liveB ?: return@let) }
                            },
                            onDragEnd = {
                                val a = liveA; val b = liveB
                                if (state.redactArea) {
                                    if (a != null && b != null && abs(a.x - b.x) > 0.005f && abs(a.y - b.y) > 0.003f)
                                        state.add(index, Annot.Redact(state.newId(), 0, listOf(Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)))))
                                } else if (liveRects.isNotEmpty()) {
                                    val words = pageText?.let { pt -> if (a != null && b != null) pt.textBetween(a, b) else "" } ?: ""
                                    state.add(index, Annot.Redact(state.newId(), 0, liveRects.map(::padForRedaction), words))
                                } else if (pageText?.isEmpty == true) {
                                    onMessage("No text here. Choose Area to mark pictures or scanned pages.")
                                }
                                liveRects = emptyList(); liveA = null; liveB = null
                            },
                            onDragCancel = { liveRects = emptyList(); liveA = null; liveB = null },
                        )
                        // Read mode: long-press a word to select it, drag to extend, drag the handles to adjust
                        Tool.VIEW -> awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val w = input.size.width.toFloat(); val h = input.size.height.toFloat()
                            if (pageText == null) scope.launch { pageText = runCatching { indexerNow.page(index) }.getOrNull() }
                            val sel = state.textSel?.takeIf { it.page == index }
                            val grab = 30.dp.toPx()
                            val rad = 7.dp.toPx()
                            val handle = sel?.let { s0 ->
                                val (hs, he) = selectionHandles(s0)
                                when {
                                    (Offset(hs.x * w, hs.y * h + rad) - down.position).getDistance() < grab -> 0
                                    (Offset(he.x * w, he.y * h + rad) - down.position).getDistance() < grab -> 1
                                    else -> null
                                }
                            }
                            val pt0 = pageText
                            if (handle != null && sel != null && pt0 != null) {
                                down.consume()
                                // the finger sits on the handle, below the text: aim a line higher
                                val lift = (sel.rects.firstOrNull()?.height ?: 0.01f) * 0.9f
                                drag(down.id) { c ->
                                    val p = Offset(c.position.x / w, c.position.y / h - lift)
                                    val a = if (handle == 0) p else sel.a
                                    val b = if (handle == 1) p else sel.b
                                    state.textSel = TextSel(index, a, b, pt0.select(a, b), pt0.textBetween(a, b))
                                    c.consume()
                                }
                                return@awaitEachGesture
                            }
                            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(); true }
                            if (released != null) return@awaitEachGesture // a tap, a scroll or a pinch
                            val pt = pageText ?: return@awaitEachGesture
                            val word = pt.wordAt(Offset(down.position.x / w, down.position.y / h)) ?: return@awaitEachGesture
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            state.textSel = TextSel(index, word.first, word.second, pt.select(word.first, word.second), pt.textBetween(word.first, word.second))
                            drag(down.id) { c ->
                                val b = Offset(c.position.x / w, c.position.y / h)
                                state.textSel = TextSel(index, word.first, b, pt.select(word.first, b), pt.textBetween(word.first, b))
                                c.consume()
                            }
                        }
                        else -> Unit
                    }
                }
        ) {
            val w = this.size.width; val h = this.size.height
            for (r in pageHits.flatMap { hit -> hit.rects.map { hit to it } }) {
                val isCur = r.first === current
                drawRect(
                    if (isCur) Color(0x88FF6D00) else Color(0x66FFEB3B),
                    Offset(r.second.left * w, r.second.top * h), Size(r.second.width * w, r.second.height * h),
                )
            }
            if (state.tool == Tool.EDIT_TEXT) runsText?.runs?.forEach { r ->
                val c = Color(state.fontColors[if (r.invisible) OCR_FONT else r.font] ?: 0xFF9E9E9E.toInt())
                val pad = r.rect.height * h * 0.15f
                val tl = Offset(r.rect.left * w - pad, r.rect.top * h - pad)
                val sz = Size(r.rect.width * w + 2 * pad, r.rect.height * h + 2 * pad)
                drawRect(c.copy(alpha = 0.16f), tl, sz)
                drawRect(c, tl, sz, style = Stroke(2.5f, pathEffect = if (r.invisible) PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) else null))
            }
            if (state.showFields) for ((f, wd) in fields) drawField(state, f, wd, tm)
            for (a in annots) drawAnnot(a, tm)
            // live previews
            when (state.tool) {
                Tool.PEN, Tool.MARKER -> if (livePts.size > 1) {
                    val marker = state.tool == Tool.MARKER
                    drawAnnot(Annot.Ink(0, state.color, if (marker) max(state.strokeWidth * 4f, 0.022f) else state.strokeWidth, marker, livePts), tm)
                }
                Tool.RECT, Tool.OVAL, Tool.LINE, Tool.ARROW, Tool.CLOUD, Tool.AREA -> {
                    val a = liveA; val b = liveB
                    if (a != null && b != null) {
                        val kind = when (state.tool) {
                            Tool.RECT -> ShapeKind.RECT; Tool.OVAL -> ShapeKind.OVAL; Tool.LINE -> ShapeKind.LINE
                            Tool.CLOUD -> ShapeKind.CLOUD; Tool.AREA -> ShapeKind.AREA; else -> ShapeKind.ARROW
                        }
                        drawAnnot(Annot.Shape(0, state.color, state.strokeWidth, kind, a, b), tm)
                    }
                }
                Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKE -> if (liveRects.isNotEmpty()) {
                    val kind = when (state.tool) { Tool.HIGHLIGHT -> MarkKind.HIGHLIGHT; Tool.UNDERLINE -> MarkKind.UNDERLINE; else -> MarkKind.STRIKE }
                    drawAnnot(Annot.Mark(0, state.color, kind, liveRects), tm)
                }
                Tool.REDACT -> {
                    val a = liveA; val b = liveB
                    if (state.redactArea && a != null && b != null)
                        drawAnnot(Annot.Redact(0, 0, listOf(Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)))), tm)
                    else if (liveRects.isNotEmpty()) drawAnnot(Annot.Redact(0, 0, liveRects.map(::padForRedaction)), tm)
                }
                else -> Unit
            }
            // selected text (Read mode)
            state.textSel?.takeIf { it.page == index }?.let { s0 ->
                val blue = Color(0xFF3D7BFD)
                for (r in s0.rects) drawRect(blue.copy(alpha = 0.3f), Offset(r.left * w, r.top * h), Size(r.width * w, r.height * h))
                val (hs, he) = selectionHandles(s0)
                val rad = 7.dp.toPx()
                val lineH = (s0.rects.firstOrNull()?.height ?: 0f) * h
                for (p in listOf(hs, he)) {
                    drawLine(blue, Offset(p.x * w, p.y * h - lineH), Offset(p.x * w, p.y * h), 2.5f)
                    drawCircle(blue, rad, Offset(p.x * w, p.y * h + rad))
                }
            }
            // selection frame
            if (selId != null) annots.firstOrNull { it.id == selId }?.let { a ->
                val b = a.bounds(aspect)
                drawRect(
                    Color(0xFF1E88E5), Offset(b.left * w - 6f, b.top * h - 6f), Size(b.width * w + 12f, b.height * h + 12f),
                    style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
        }
    }
}

private fun eraseAt(state: ViewerState, page: Int, p: Offset, aspect: Float) {
    state.annots[page].orEmpty().filter { it.hit(p, 0.012f, aspect) }.forEach { state.removeLive(page, it.id) }
}

/** Signature photos are small (<= 900 px); decode each once. */
object SignatureImages {
    private val cache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap>()
    fun get(path: String): androidx.compose.ui.graphics.ImageBitmap? {
        cache[path]?.let { return it }
        val img = android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() ?: return null
        cache[path] = img
        return img
    }
    fun forget(path: String) { cache.remove(path) } // the saved signature photo was replaced
}

/** Tap / double-tap behaviour for tools that are not drag-based. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.gestures(
    state: ViewerState, index: Int, aspect: Float,
    onTextRequest: (Int, Offset) -> Unit, onEditText: (Int, Annot.Text) -> Unit, onViewTap: (Int, Offset) -> Unit,
    onReplaceText: (Int, Offset) -> Unit,
) {
    detectTapGestures(
        onTap = { o ->
            // current size, not the size when the tool was picked (pinch-zoom resizes the page)
            val w = size.width.toFloat(); val h = size.height.toFloat()
            val p = Offset(o.x / w, o.y / h)
            // how far off a fingertip may land, as a fraction of the page
            state.tapSlop = Offset(22.dp.toPx() / w, 22.dp.toPx() / h)
            when (state.tool) {
                Tool.VIEW -> if (state.textSel != null) state.textSel = null else onViewTap(index, p)
                Tool.TICK -> {
                    // a mark about a text line tall, centred where you tap
                    val half = 0.016f
                    state.add(index, Annot.Shape(state.newId(), state.color, 0.004f, state.tickKind, Offset(p.x - half, p.y - half / aspect), Offset(p.x + half, p.y + half / aspect)))
                }
                Tool.NOTE -> state.onNoteTap(index, p, state.annots[index].orEmpty().lastOrNull { it is Annot.Note && it.hit(p, 0.01f, aspect) } as? Annot.Note)
                Tool.TEXT -> {
                    val hitText = state.annots[index].orEmpty().lastOrNull { it is Annot.Text && it.hit(p, 0.01f, aspect) }
                    if (hitText is Annot.Text) onEditText(index, hitText) else onTextRequest(index, p)
                }
                Tool.EDIT_TEXT -> onReplaceText(index, p)
                Tool.SELECT -> {
                    state.selected = state.annots[index].orEmpty().lastOrNull { it.hit(p, 0.02f, aspect) }?.let { index to it.id }
                }
                else -> Unit
            }
        },
        onDoubleTap = { if (state.tool == Tool.VIEW) state.zoom = if (state.zoom > 1.2f) 1f else 2f },
    )
}

// ------------------------------------------------------------------------------------ drawing

fun DrawScope.drawAnnot(a: Annot, tm: TextMeasurer) {
    val w = size.width; val h = size.height
    fun px(o: Offset) = Offset(o.x * w, o.y * h)
    val col = Color(a.color)
    when (a) {
        is Annot.Ink -> {
            val path = Path().apply {
                moveTo(px(a.pts[0]).x, px(a.pts[0]).y)
                for (i in 1 until a.pts.size) lineTo(px(a.pts[i]).x, px(a.pts[i]).y)
            }
            drawPath(
                path, if (a.marker) col.copy(alpha = 0.38f) else col,
                style = Stroke(a.width * w, cap = if (a.marker) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        is Annot.Shape -> {
            val pa = px(a.a); val pb = px(a.b); val sw = a.width * w
            val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val tl = Offset(min(pa.x, pb.x), min(pa.y, pb.y)); val sz = Size(abs(pa.x - pb.x), abs(pa.y - pb.y))
            when (a.kind) {
                ShapeKind.RECT -> drawRect(col, tl, sz, style = st)
                ShapeKind.OVAL -> drawOval(col, tl, sz, style = st)
                ShapeKind.LINE -> drawLine(col, pa, pb, sw, StrokeCap.Round)
                ShapeKind.CLOUD -> {
                    val pts = com.mobilepdf.editor.annot.cloudOutline(a.a, a.b, h / w, a.width)
                    if (pts.isNotEmpty()) drawPath(Path().apply {
                        moveTo(pts[0].x * w, pts[0].y * h); for (p in pts.drop(1)) lineTo(p.x * w, p.y * h); close()
                    }, col, style = st)
                }
                ShapeKind.AREA -> drawRect(col.copy(alpha = 0.38f), tl, sz)
                ShapeKind.TICK, ShapeKind.CROSS -> for (stroke in com.mobilepdf.editor.annot.markStrokes(a.kind, a.a, a.b)) {
                    drawPath(Path().apply { moveTo(stroke[0].x * w, stroke[0].y * h); stroke.drop(1).forEach { lineTo(it.x * w, it.y * h) } }, col, style = st)
                }
                ShapeKind.DOT -> drawOval(col, tl + Offset(sz.width * 0.2f, sz.height * 0.2f), Size(sz.width * 0.6f, sz.height * 0.6f))
                ShapeKind.ARROW -> {
                    drawLine(col, pa, pb, sw, StrokeCap.Round)
                    val ang = atan2(pb.y - pa.y, pb.x - pa.x); val len = w * (0.035f + a.width * 2)
                    for (d in listOf(0.45f, -0.45f)) drawLine(
                        col, pb, Offset(pb.x - len * cos(ang + d), pb.y - len * sin(ang + d)), sw, StrokeCap.Round,
                    )
                }
            }
        }
        is Annot.Mark -> for (r in a.rects) {
            val tl = Offset(r.left * w, r.top * h); val sz = Size(r.width * w, r.height * h)
            when (a.kind) {
                MarkKind.HIGHLIGHT -> drawRect(col.copy(alpha = 0.40f), tl, sz)
                MarkKind.UNDERLINE -> drawLine(col, Offset(tl.x, tl.y + sz.height), Offset(tl.x + sz.width, tl.y + sz.height), max(2f, sz.height * 0.07f))
                MarkKind.STRIKE -> drawLine(col, Offset(tl.x, tl.y + sz.height / 2), Offset(tl.x + sz.width, tl.y + sz.height / 2), max(2f, sz.height * 0.07f))
            }
        }
        is Annot.Text -> {
            val fs = a.size * w
            drawText(
                tm, a.text, px(a.pos),
                style = TextStyle(color = col, fontSize = fs.toSp(), lineHeight = (fs * 1.2f).toSp()).withFont(a.font),
            )
        }
        is Annot.Replace -> {
            val c = com.mobilepdf.editor.annot.coverRect(a)
            drawRect(Color(a.cover), Offset(c.left * w, c.top * h), Size(c.width * w, c.height * h))
            val fs = a.size * w
            // Compose positions text by its top; the PDF by its baseline (about 0.93 em below the top)
            drawText(
                tm, a.text, Offset(a.rect.left * w, a.rect.bottom * h - fs * 0.93f),
                style = TextStyle(color = col, fontSize = fs.toSp()), softWrap = false,
            )
        }
        is Annot.Redact -> for (r in a.rects) {
            // a mark, not yet applied: dark, see-through, with a red edge (applied = solid boxes)
            val tl = Offset(r.left * w, r.top * h); val sz = Size(r.width * w, r.height * h)
            drawRect(Color(0x99000000.toInt()), tl, sz)
            drawRect(Color(0xFFE53935.toInt()), tl, sz, style = Stroke(3f))
        }
        is Annot.Seal -> {
            val r = a.rect
            val tl = Offset(r.left * w, r.top * h); val sz = Size(r.width * w, r.height * h)
            val lw = (sz.height * 0.06f).coerceIn(2f, 10f)
            drawRect(col.copy(alpha = 0.9f), tl + Offset(lw / 2, lw / 2), Size(sz.width - lw, sz.height - lw), style = Stroke(lw))
            val inset = lw * 2.6f
            drawRect(col.copy(alpha = 0.9f), tl + Offset(inset, inset), Size(sz.width - 2 * inset, sz.height - 2 * inset), style = Stroke(lw * 0.45f))
            val style = TextStyle(color = col, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif)
            // as large as fits, like the saved stamp
            val probe = tm.measure(a.text, style.copy(fontSize = 100f.toSp()))
            val px = minOf(sz.height * 0.5f / 0.72f, (sz.width - 4 * inset) / probe.size.width.coerceAtLeast(1) * 100f)
            val lay = tm.measure(a.text, style.copy(fontSize = px.toSp()), softWrap = false)
            drawText(lay, topLeft = Offset(tl.x + (sz.width - lay.size.width) / 2, tl.y + (sz.height - lay.size.height) / 2))
        }
        is Annot.Note -> {
            val s = com.mobilepdf.editor.annot.NOTE_SIZE * w
            val tl = px(a.pos)
            drawRoundRect(col, tl, Size(s, s), androidx.compose.ui.geometry.CornerRadius(s * 0.15f))
            drawRoundRect(Color(0x66000000), tl, Size(s, s), androidx.compose.ui.geometry.CornerRadius(s * 0.15f), style = Stroke(2f))
            for (i in 0..2) {
                val y = tl.y + s * (0.3f + 0.2f * i)
                drawLine(Color(0xCC000000.toInt()), Offset(tl.x + s * 0.2f, y), Offset(tl.x + s * (if (i == 2) 0.6f else 0.8f), y), max(1.5f, s * 0.06f))
            }
        }
        is Annot.Stamp -> if (a.image != null) {
            SignatureImages.get(a.image)?.let { img ->
                drawImage(
                    img, dstOffset = androidx.compose.ui.unit.IntOffset((a.rect.left * w).toInt(), (a.rect.top * h).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((a.rect.width * w).toInt().coerceAtLeast(1), (a.rect.height * h).toInt().coerceAtLeast(1)),
                )
            }
        } else {
            val sw = max(2f, a.rect.width * w * 0.012f)
            for (s in a.strokes) {
                if (s.isEmpty()) continue
                if (s.size == 1) { // a dot (i, full stop)
                    drawCircle(col, sw / 2 * 1.6f, Offset((a.rect.left + s[0].x * a.rect.width) * w, (a.rect.top + s[0].y * a.rect.height) * h))
                    continue
                }
                val path = Path().apply {
                    fun m(o: Offset) = Offset((a.rect.left + o.x * a.rect.width) * w, (a.rect.top + o.y * a.rect.height) * h)
                    moveTo(m(s[0]).x, m(s[0]).y)
                    for (i in 1 until s.size) lineTo(m(s[i]).x, m(s[i]).y)
                }
                drawPath(path, col, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * Form fields: a light tint shows where they are. A value the user changed (not saved yet) is drawn
 * over a white patch that hides the old appearance, so what you see is what will be saved.
 */
private fun DrawScope.drawField(state: ViewerState, f: FormField, wd: FormWidget, tm: TextMeasurer) {
    val w = size.width; val h = size.height
    val tl = Offset(wd.rect.left * w, wd.rect.top * h)
    val sz = Size(wd.rect.width * w, wd.rect.height * h)
    drawRect(Color(0x2E2196F3), tl, sz)
    drawRect(Color(0x882196F3), tl, sz, style = Stroke(1.5f))
    val pending = state.formValues[f.name] ?: return
    val ink = Color(0xFF0D2A6B)
    when (f.type) {
        FieldType.TEXT, FieldType.CHOICE -> {
            drawRect(Color.White, tl, sz)
            drawRect(Color(0x882196F3), tl, sz, style = Stroke(1.5f))
            if (pending.isNotEmpty()) {
                val fs = (if (f.multiline) min(sz.height * 0.5f, w * 0.022f) else sz.height * 0.6f).coerceIn(6f, w * 0.04f)
                drawText(
                    tm, pending, Offset(tl.x + 4f, tl.y + if (f.multiline) 2f else (sz.height - fs * 1.2f) / 2),
                    style = TextStyle(color = ink, fontSize = fs.toSp()),
                    size = Size(max(1f, sz.width - 8f), max(1f, sz.height - 2f)),
                    maxLines = if (f.multiline) Int.MAX_VALUE else 1,
                )
            }
        }
        FieldType.CHECK -> {
            drawRect(Color.White, tl, sz)
            drawRect(Color(0x882196F3), tl, sz, style = Stroke(1.5f))
            if (pending == Forms.CHECKED) {
                val p = Path().apply {
                    moveTo(tl.x + sz.width * 0.2f, tl.y + sz.height * 0.55f)
                    lineTo(tl.x + sz.width * 0.42f, tl.y + sz.height * 0.78f)
                    lineTo(tl.x + sz.width * 0.82f, tl.y + sz.height * 0.22f)
                }
                drawPath(p, ink, style = Stroke(max(2f, sz.minDimension * 0.12f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        FieldType.RADIO -> {
            val c = Offset(tl.x + sz.width / 2, tl.y + sz.height / 2)
            drawCircle(Color.White, sz.minDimension / 2, c)
            drawCircle(Color(0x882196F3), sz.minDimension / 2, c, style = Stroke(1.5f))
            if (pending == wd.onValue) drawCircle(ink, sz.minDimension * 0.25f, c)
        }
        FieldType.SIGNATURE -> Unit
    }
}

/** Typefaces of uploaded fonts, loaded once. */
private val fileTypefaces = HashMap<String, androidx.compose.ui.text.font.FontFamily?>()

/**
 * The on-screen look of [f]: the uploaded font itself, or the system serif / sans / mono in the chosen
 * weight. (A font copied from the PDF is previewed by its family and weight; saving writes the real font.)
 */
fun TextStyle.withFont(f: com.mobilepdf.editor.annot.FontChoice): TextStyle {
    val file = f.file?.let { p ->
        fileTypefaces.getOrPut(p) {
            runCatching { androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.createFromFile(p)) }.getOrNull()
        }
    }
    val family = file ?: when (f.family) {
        "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
        "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
        else -> androidx.compose.ui.text.font.FontFamily.SansSerif
    }
    return copy(
        fontFamily = family,
        fontWeight = if (f.bold && file == null) androidx.compose.ui.text.font.FontWeight.Bold else null,
        fontStyle = if (f.italic && file == null) androidx.compose.ui.text.font.FontStyle.Italic else null,
    )
}

/** Drops a cached uploaded typeface (the file was replaced). */
fun forgetTypeface(path: String) { fileTypefaces.remove(path) }


/** Redaction marks made from text are taller than the letters' boxes: ascenders, accents and descenders. */
private fun padForRedaction(r: Rect): Rect {
    val hh = r.height
    return Rect(r.left - hh * 0.06f, r.top - hh * 0.25f, r.right + hh * 0.06f, r.bottom + hh * 0.3f)
}

/** Where the two selection handles sit: under the start of the first line and the end of the last. */
fun selectionHandles(s: TextSel): Pair<Offset, Offset> {
    val f = s.rects.firstOrNull() ?: return Offset.Zero to Offset.Zero
    val l = s.rects.last()
    return Offset(f.left, f.bottom) to Offset(l.right, l.bottom)
}
