package com.mobilepdf.editor.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Rotation-aware conversion between PDF user space and the displayed, normalized page (0..1, top-left). */
object PageGeom {
    fun rotation(page: PDPage) = ((page.rotation % 360) + 360) % 360

    fun toPdf(page: PDPage, p: Offset): Pair<Float, Float> {
        val box = page.cropBox
        val w = box.width; val h = box.height; val u = p.x; val v = p.y
        return when (rotation(page)) {
            90 -> box.lowerLeftX + v * w to box.lowerLeftY + u * h
            180 -> box.lowerLeftX + (1 - u) * w to box.lowerLeftY + v * h
            270 -> box.lowerLeftX + (1 - v) * w to box.lowerLeftY + (1 - u) * h
            else -> box.lowerLeftX + u * w to box.lowerLeftY + (1 - v) * h
        }
    }

    fun toDisplayed(page: PDPage, r: PDRectangle): Rect {
        val box = page.cropBox
        val rot = rotation(page)
        fun uv(x: Float, y: Float): Pair<Float, Float> {
            val fx = (x - box.lowerLeftX) / box.width
            val fy = (y - box.lowerLeftY) / box.height
            return when (rot) {
                90 -> fy to fx
                180 -> (1 - fx) to fy
                270 -> (1 - fy) to (1 - fx)
                else -> fx to (1 - fy)
            }
        }
        val (u1, v1) = uv(r.lowerLeftX, r.lowerLeftY)
        val (u2, v2) = uv(r.upperRightX, r.upperRightY)
        return Rect(min(u1, u2), min(v1, v2), max(u1, u2), max(v1, v2))
    }
}

// ------------------------------------------------------------------------------------------ forms

enum class FieldType { TEXT, CHECK, RADIO, CHOICE, SIGNATURE }

/** One clickable box of a field. Radio buttons have one widget per option, each with its own [onValue]. */
data class FormWidget(val page: Int, val rect: Rect, val onValue: String?)

data class FormField(
    val name: String, val label: String, val type: FieldType, val value: String,
    val options: List<String>, val readOnly: Boolean, val multiline: Boolean, val widgets: List<FormWidget>,
    /** Built-in calculation (a total…), its place in the form's calculation order, and the number format. */
    val calc: Calc? = null, val calcOrder: Int = Int.MAX_VALUE, val format: NumFormat? = null,
)

object Forms {
    const val CHECKED = "true"
    const val UNCHECKED = "false"

    fun read(d: PDDocument): List<FormField> {
        val form = d.documentCatalog.acroForm ?: return emptyList()
        // widget dictionaries without /P: find their page by scanning page annotations once
        val pageOf = HashMap<Any, Int>()
        d.pages.forEachIndexed { i, p -> runCatching { p.annotations }.getOrNull()?.forEach { pageOf[it.cosObject] = i } }

        val out = ArrayList<FormField>()
        // calculation order (/CO): the fields whose values are computed, first to last
        val co = (form.cosObject.getDictionaryObject(COSName.getPDFName("CO")) as? com.tom_roush.pdfbox.cos.COSArray)
            ?.let { a -> (0 until a.size()).mapNotNull { a.getObject(it) } } ?: emptyList()
        fun js(f: PDTerminalField, key: String): String? = runCatching {
            val aa = f.cosObject.getDictionaryObject(COSName.AA) as? com.tom_roush.pdfbox.cos.COSDictionary ?: return@runCatching null
            val act = aa.getDictionaryObject(COSName.getPDFName(key)) as? com.tom_roush.pdfbox.cos.COSDictionary ?: return@runCatching null
            when (val j = act.getDictionaryObject(COSName.JS)) {
                is com.tom_roush.pdfbox.cos.COSString -> j.string
                is com.tom_roush.pdfbox.cos.COSStream -> j.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
                else -> null
            }
        }.getOrNull()
        for (f in form.fieldTree) {
            if (f !is PDTerminalField || f is PDPushButton) continue
            val type = when (f) {
                is PDTextField -> FieldType.TEXT
                is PDCheckBox -> FieldType.CHECK
                is PDRadioButton -> FieldType.RADIO
                is PDChoice -> FieldType.CHOICE
                is PDSignatureField -> FieldType.SIGNATURE
                else -> continue
            }
            val widgets = f.widgets.mapNotNull { w ->
                val pageIndex = w.page?.let { d.pages.indexOf(it) }?.takeIf { it >= 0 } ?: pageOf[w.cosObject] ?: return@mapNotNull null
                val rect = w.rectangle ?: return@mapNotNull null
                FormWidget(pageIndex, PageGeom.toDisplayed(d.getPage(pageIndex), rect), onValueOf(w))
            }
            if (widgets.isEmpty()) continue
            val value = when (f) {
                is PDCheckBox -> if (f.isChecked) CHECKED else UNCHECKED
                is PDChoice -> f.value.firstOrNull() ?: ""
                else -> runCatching { f.valueAsString }.getOrDefault("")
            }
            val options = (f as? PDChoice)?.let { c -> runCatching { c.optionsDisplayValues }.getOrNull()?.ifEmpty { null } ?: c.options } ?: emptyList()
            out += FormField(
                name = f.fullyQualifiedName,
                label = f.alternateFieldName?.takeIf { it.isNotBlank() } ?: humanize(f.partialName ?: f.fullyQualifiedName),
                type = type, value = value, options = options, readOnly = f.isReadOnly,
                multiline = (f as? PDTextField)?.isMultiline == true, widgets = widgets,
                calc = if (f is PDTextField || f is PDChoice) FormCalc.parseCalc(js(f, "C")) else null,
                calcOrder = co.indexOf(f.cosObject).let { if (it < 0) Int.MAX_VALUE else it },
                format = FormCalc.parseFormat(js(f, "F")),
            )
        }
        return out
    }

    /** "fullName" / "full_name" / "FULL-NAME" -> "Full name" (forms rarely set a friendly /TU label). */
    fun humanize(raw: String): String {
        val spaced = raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace(Regex("[_\\-.]+"), " ").trim()
        return spaced.lowercase().replaceFirstChar { it.uppercase() }.ifEmpty { raw }
    }

    private fun onValueOf(w: PDAnnotationWidget): String? = runCatching {
        w.appearance?.normalAppearance?.subDictionary?.keys?.map { it.name }?.firstOrNull { it != "Off" }
    }.getOrNull()

    fun hasXfaOnly(d: PDDocument): Boolean {
        val form = d.documentCatalog.acroForm ?: return false
        return form.hasXFA() && form.fields.isEmpty()
    }

    /** Writes [values] (field name -> value) into the form; optionally flattens it into page content. */
    fun fill(src: File, values: Map<String, String>, flatten: Boolean, out: File) {
        PdfOps.load(src).use { d ->
            val form = d.documentCatalog.acroForm ?: throw IllegalStateException("This PDF has no form")
            for ((name, v) in values) {
                val f = form.getField(name) ?: continue
                try {
                    when (f) {
                        is PDCheckBox -> if (v == CHECKED) f.check() else f.unCheck()
                        is PDRadioButton -> if (v.isNotEmpty()) f.setValue(v)
                        is PDChoice -> f.setValue(v)
                        is PDTextField -> f.setValue(v)
                    }
                } catch (e: Exception) {
                    // the form's font cannot draw this value: let the viewer that opens it build the appearance
                    form.setNeedAppearances(true)
                    if (f is PDTextField) f.cosObject.setString(COSName.V, v)
                }
            }
            if (flatten) runCatching { form.flatten() }
            d.save(out)
        }
    }
}

// ------------------------------------------------------------------------------------------ outline / links

data class Bookmark(val title: String, val depth: Int, val page: Int)

sealed class PageLink {
    abstract val rect: Rect
    data class Uri(override val rect: Rect, val uri: String) : PageLink()
    data class Page(override val rect: Rect, val page: Int) : PageLink()
}

object Navigation {
    /**
     * Edit bookmarks (PdfCraft: Bookmarks panel): writes [items] (in order, nested by depth) as the
     * document's outline. Each bookmark opens its page fitted to the screen; an empty list removes the outline.
     */
    fun setOutline(src: java.io.File, items: List<Bookmark>, out: java.io.File) {
        PdfOps.load(src).use { d ->
            if (items.isEmpty()) {
                d.documentCatalog.documentOutline = null
            } else {
                val root = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
                val parents = ArrayList<PDOutlineNode>().apply { add(root) }
                for (b in items) {
                    val depth = b.depth.coerceIn(0, parents.size - 1) // never deeper than one below the previous
                    while (parents.size > depth + 1) parents.removeAt(parents.lastIndex)
                    val item = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem()
                    item.title = b.title.ifBlank { "Untitled" }
                    if (b.page in 0 until d.numberOfPages) {
                        item.destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
                            .apply { page = d.getPage(b.page) }
                    }
                    parents.last().addLast(item)
                    parents += item
                }
                root.openNode()
                d.documentCatalog.documentOutline = root
            }
            d.save(out)
        }
    }

    fun outline(d: PDDocument): List<Bookmark> {
        val root = d.documentCatalog.documentOutline ?: return emptyList()
        val out = ArrayList<Bookmark>()
        fun walk(node: PDOutlineNode, depth: Int) {
            for (item in node.children()) {
                if (out.size > 2000) return // pathological outlines
                val page = runCatching { item.findDestinationPage(d)?.let { d.pages.indexOf(it) } }.getOrNull() ?: -1
                out += Bookmark(item.title ?: "", depth, page)
                walk(item, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    fun links(d: PDDocument, index: Int): List<PageLink> {
        val page = d.getPage(index)
        return runCatching { page.annotations }.getOrDefault(emptyList()).filterIsInstance<PDAnnotationLink>().mapNotNull { l ->
            val rect = PageGeom.toDisplayed(page, l.rectangle ?: return@mapNotNull null)
            when (val a = l.action) {
                is PDActionURI -> a.uri?.let { PageLink.Uri(rect, it) }
                is PDActionGoTo -> resolve(d, a.destination)?.let { PageLink.Page(rect, it) }
                else -> resolve(d, l.destination)?.let { PageLink.Page(rect, it) }
            }
        }
    }

    private fun resolve(d: PDDocument, dest: PDDestination?): Int? = runCatching {
        val pd: PDPageDestination? = when (dest) {
            is PDPageDestination -> dest
            is PDNamedDestination -> d.documentCatalog.findNamedDestinationPage(dest)
            else -> null
        }
        pd?.let { p -> p.page?.let { d.pages.indexOf(it) }?.takeIf { it >= 0 } ?: p.pageNumber.takeIf { it >= 0 } }
    }.getOrNull()
}
