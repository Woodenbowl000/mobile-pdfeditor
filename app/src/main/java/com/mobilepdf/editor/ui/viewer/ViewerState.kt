package com.mobilepdf.editor.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.pdf.FormField

enum class Tool { VIEW, EDIT_TEXT, SELECT, HIGHLIGHT, UNDERLINE, STRIKE, PEN, MARKER, TEXT, NOTE, REDACT, RECT, OVAL, LINE, ARROW, CLOUD, AREA, TICK, ERASER }

/** Text selected in Read mode (long-press, then drag the handles): what Copy / Share / Highlight act on. */
data class TextSel(val page: Int, val a: androidx.compose.ui.geometry.Offset, val b: androidx.compose.ui.geometry.Offset, val rects: List<Rect>, val text: String)

class Hit(val page: Int, val rects: List<Rect>)

/** Distinct hues for outlining text by font in Edit-text mode. */
val FontPalette = listOf(0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00897B, 0xFFD81B60, 0xFF6D4C41, 0xFF3949AB).map { it.toInt() }
const val OCR_FONT = "Scanned text (OCR)"

val Palette = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF212121).map { it.toInt() }

/**
 * Everything about the open viewer that is not the PDF itself.
 * Annotations are an immutable map page -> list; every change swaps in a new map, so undo/redo is
 * just a stack of references (no copying of the data).
 */
class ViewerState {
    var tool by mutableStateOf(Tool.VIEW)
    var color by mutableIntStateOf(Palette[0])
    var strokeWidth by mutableFloatStateOf(0.006f)   // fraction of page width
    var textSize by mutableFloatStateOf(0.04f)       // fraction of page width
    /** Font of new text (Text tool); the eyedropper copies it from text on the page. */
    var textFont by mutableStateOf(com.mobilepdf.editor.annot.FontChoice())
    /** Eyedropper armed: the next tap on the page copies that text's font, size and colour. */
    var pickingFont by mutableStateOf(false)
    /** Fingertip tolerance of the last tap, as a fraction of page width / height. */
    var tapSlop = androidx.compose.ui.geometry.Offset(0.02f, 0.015f)
    /** Redact tool: false = drag over words, true = drag a box (pictures, scans, anything). */
    var redactArea by mutableStateOf(false)
    /** Tick tool: which mark a tap places (tick, cross or dot). */
    var tickKind by mutableStateOf(com.mobilepdf.editor.annot.ShapeKind.TICK)
    var textSel by mutableStateOf<TextSel?>(null)
    /** Note tool / tap on a note: the screen opens the note editor. */
    var onNoteTap: (Int, androidx.compose.ui.geometry.Offset, Annot.Note?) -> Unit = { _, _, _ -> }
    val redactCount: Int get() = annots.values.sumOf { l -> l.count { it is Annot.Redact } }
    /** Fonts the user uploaded (paths of .ttf files in app storage). */
    val customFonts = mutableStateListOf<String>()

    var zoom by mutableFloatStateOf(1f)
    var nightMode by mutableStateOf(false)
    /** Bumped by "Fit text width": the viewer zooms so the text column fills the screen. */
    var fitWidthRequest by mutableIntStateOf(0)
    var chrome by mutableStateOf(true)

    var annots by mutableStateOf<Map<Int, List<Annot>>>(emptyMap())
        private set
    var selected by mutableStateOf<Pair<Int, Long>?>(null)

    /**
     * One history for everything: annotation changes (snapshots of the map) and edits made directly to
     * the document, such as "Edit text" (the working copy before and after).
     */
    private sealed class Step {
        class Annots(val map: Map<Int, List<Annot>>) : Step()
        class Doc(val before: java.io.File, val after: java.io.File) : Step()
    }
    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    /** Switches the viewer to another working copy (undo / redo of a document edit). */
    var onDocSwitch: (java.io.File) -> Unit = {}
    var undoSize by mutableIntStateOf(0); private set
    var redoSize by mutableIntStateOf(0); private set
    private var nextId = 1L
    fun newId() = nextId++

    val hasAnnots get() = annots.values.any { it.isNotEmpty() }

    // ---- forms: fields come from the PDF, edits are kept here until saved
    var fields by mutableStateOf<List<FormField>>(emptyList())
    val formValues = mutableStateMapOf<String, String>()
    var flattenOnSave by mutableStateOf(false)
    var showFields by mutableStateOf(true)
    var formHintShown = false
    val formDirty get() = formValues.isNotEmpty()
    fun valueOf(f: FormField) = formValues[f.name] ?: f.value
    fun setValue(f: FormField, v: String, format: Boolean = true) {
        // a number typed into a formatted field is shown the way the form formats it ("1200" -> "$1,200.00"),
        // once the typing is done (format = false while the keyboard is still up)
        val shown = f.format?.takeIf { format && v.isNotBlank() && v.any(Char::isDigit) }
            ?.let { fmt -> com.mobilepdf.editor.pdf.FormCalc.format(com.mobilepdf.editor.pdf.FormCalc.number(v, fmt), fmt) } ?: v
        put(f, shown)
        recalculate()
    }

    private fun put(f: FormField, v: String) { if (v == f.value) formValues.remove(f.name) else formValues[f.name] = v }

    /** Totals and other calculated fields follow the values they depend on. */
    fun recalculate() {
        if (fields.none { it.calc != null }) return
        val byName = fields.associateBy { it.name }
        for ((name, v) in com.mobilepdf.editor.pdf.FormCalc.recompute(fields, ::valueOf)) byName[name]?.let { put(it, v) }
    }

    // ---- Edit text: one colour per font, the same on every page
    val fontColors = mutableStateMapOf<String, Int>()
    fun colorFor(font: String): Int = fontColors.getOrPut(font) { FontPalette[fontColors.size % FontPalette.size] }

    // ---- search
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var searchBusy by mutableStateOf(false)
    /** Pages searched so far (shown while a long document is being searched). */
    var searchedPages by mutableIntStateOf(0)
    var searchTotal by mutableIntStateOf(0)
    val hits = mutableStateListOf<Hit>()
    var currentHit by mutableIntStateOf(-1)

    // ---- annotation edits
    /** Call once before a series of live edits (e.g. a drag) so they undo as one step. */
    fun beginChange() {
        push(Step.Annots(annots))
    }

    private fun push(s: Step) {
        undoStack.addLast(s); if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear(); sync()
    }

    /** A change made to the PDF itself (e.g. Edit text): undo goes back to [before]. */
    fun pushDocEdit(before: java.io.File, after: java.io.File) = push(Step.Doc(before, after))

    private fun sync() { undoSize = undoStack.size; redoSize = redoStack.size }

    fun add(page: Int, a: Annot) {
        beginChange(); annots = annots + (page to (annots[page].orEmpty() + a))
    }

    /** Replace without a new undo step (use after [beginChange]). */
    fun replaceLive(page: Int, a: Annot) {
        annots = annots + (page to annots[page].orEmpty().map { if (it.id == a.id) a else it })
    }

    fun replace(page: Int, a: Annot) { beginChange(); replaceLive(page, a) }

    fun removeLive(page: Int, id: Long) {
        annots = annots + (page to annots[page].orEmpty().filter { it.id != id })
        if (selected?.second == id) selected = null
    }

    fun remove(page: Int, id: Long) { beginChange(); removeLive(page, id) }

    /** Several additions as one undo step (find & redact). */
    fun addMany(items: List<Pair<Int, Annot>>) {
        if (items.isEmpty()) return
        beginChange()
        var m = annots
        for ((p, a) in items) m = m + (p to (m[p].orEmpty() + a))
        annots = m
    }

    /** Removes every annotation matching [pred], as one undo step. */
    fun removeWhere(pred: (Annot) -> Boolean) {
        if (annots.values.none { l -> l.any(pred) }) return
        beginChange()
        annots = annots.mapValues { (_, l) -> l.filterNot(pred) }
        selected = null
    }

    fun undo() {
        when (val s = undoStack.removeLastOrNull() ?: return) {
            is Step.Annots -> { redoStack.addLast(Step.Annots(annots)); annots = s.map }
            is Step.Doc -> { redoStack.addLast(s); onDocSwitch(s.before) }
        }
        selected = null; sync()
    }

    fun redo() {
        when (val s = redoStack.removeLastOrNull() ?: return) {
            is Step.Annots -> { undoStack.addLast(Step.Annots(annots)); annots = s.map }
            is Step.Doc -> { undoStack.addLast(s); onDocSwitch(s.after) }
        }
        selected = null; sync()
    }

    /** Crash recovery: bring back annotations from a draft. */
    fun restore(a: Map<Int, List<Annot>>) {
        annots = a; undoStack.clear(); redoStack.clear(); sync()
        nextId = (a.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1
    }

    /** After the annotations have been written into the PDF. */
    fun clearAll() {
        annots = emptyMap(); undoStack.clear(); redoStack.clear(); selected = null; sync()
    }

    fun selectedAnnot(): Pair<Int, Annot>? {
        val (p, id) = selected ?: return null
        return annots[p]?.firstOrNull { it.id == id }?.let { p to it }
    }
}
