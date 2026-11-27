package com.mobilepdf.editor.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Remove Hidden Information (PdfCraft `pdfcraft-redact::sanitize`, after Acrobat's panel): count what each
 * category holds, then remove the chosen ones. Saving rewrites the whole file, so nothing removed stays in
 * an older revision.
 */
object Sanitizer {
    enum class Hidden(val label: String, val detail: String) {
        METADATA("Metadata", "Title, author, dates and the XMP packet"),
        ATTACHMENTS("Attached files", "Embedded files and file attachment comments"),
        COMMENTS("Comments & markup", "Notes, highlights, drawings and their pop-ups"),
        FORMS("Form fields", "Flattened: their current values stay as page content"),
        HIDDEN_TEXT("Hidden text", "Invisible text, such as an OCR layer (scans stop being searchable)"),
        LINKS_SCRIPTS("Links, actions & JavaScript", "Web/page links, open actions, scripts"),
        BOOKMARKS("Bookmarks", "The outline"),
        PRIVATE("Private application data", "Other apps' data (PieceInfo) and prepress data"),
    }

    fun scan(file: File): Map<Hidden, Int> = PdfOps.load(file).use { d -> Hidden.entries.associateWith { count(d, it) } }

    fun remove(src: File, which: Set<Hidden>, out: File): Map<Hidden, Int> = PdfOps.load(src).use { d ->
        val done = which.associateWith { h -> count(d, h).also { if (it > 0) removeOne(d, h) } }
        d.save(out)
        done
    }

    private fun isComment(a: Any) = a !is PDAnnotationLink && a !is PDAnnotationWidget

    private fun count(d: PDDocument, h: Hidden): Int = runCatching {
        val cat = d.documentCatalog
        when (h) {
            Hidden.METADATA -> (d.documentInformation?.cosObject?.keySet()?.size ?: 0) + (if (cat.metadata != null) 1 else 0)
            Hidden.ATTACHMENTS -> embedded(d).size + d.pages.sumOf { p -> p.annotations.count { it is PDAnnotationFileAttachment } }
            Hidden.COMMENTS -> d.pages.sumOf { p -> p.annotations.count { isComment(it) && it !is PDAnnotationPopup } }
            Hidden.FORMS -> cat.acroForm?.fieldTree?.count() ?: 0
            Hidden.HIDDEN_TEXT -> d.pages.sumOf { p -> hiddenText(d, p, remove = false) }
            Hidden.LINKS_SCRIPTS -> d.pages.sumOf { p -> p.annotations.count { it is PDAnnotationLink } } +
                (if (cat.cosObject.containsKey(COSName.OPEN_ACTION)) 1 else 0) + (if (cat.cosObject.containsKey(COSName.AA)) 1 else 0) +
                (if (namesDict(d)?.containsKey(COSName.JAVA_SCRIPT) == true) 1 else 0) +
                d.pages.count { it.cosObject.containsKey(COSName.AA) }
            Hidden.BOOKMARKS -> {
                var n = 0
                fun walk(i: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode?) {
                    var c = i?.firstChild
                    while (c != null) { n++; walk(c); c = c.nextSibling }
                }
                walk(cat.documentOutline); n
            }
            Hidden.PRIVATE -> (if (cat.cosObject.containsKey(PIECE)) 1 else 0) + d.pages.sumOf { p ->
                (if (p.cosObject.containsKey(PIECE)) 1 else 0) + xobjects(p.resources).count { it.containsKey(PIECE) || it.containsKey(OPI) || it.containsKey(ALT) }
            }
        }
    }.getOrDefault(0)

    private val PIECE = COSName.getPDFName("PieceInfo")
    private val OPI = COSName.getPDFName("OPI")
    private val ALT = COSName.getPDFName("Alternates")
    private val EMBEDDED = COSName.getPDFName("EmbeddedFiles")
    private val AF = COSName.getPDFName("AF")

    private fun namesDict(d: PDDocument) = d.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary

    private fun embedded(d: PDDocument): Map<String, PDComplexFileSpecification> = runCatching {
        val tree = d.documentCatalog.names?.embeddedFiles ?: return emptyMap()
        val out = LinkedHashMap<String, PDComplexFileSpecification>()
        fun walk(n: com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode<PDComplexFileSpecification>) {
            n.names?.let { out.putAll(it) }
            n.kids?.forEach { walk(it) }
        }
        walk(tree); out
    }.getOrDefault(emptyMap())

    private fun xobjects(res: PDResources?): List<COSDictionary> {
        res ?: return emptyList()
        return res.xObjectNames.mapNotNull { n -> (res.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.getDictionaryObject(n) as? COSDictionary }
    }

    private fun removeOne(d: PDDocument, h: Hidden) {
        val cat = d.documentCatalog
        when (h) {
            Hidden.METADATA -> {
                d.documentInformation = PDDocumentInformation()
                cat.cosObject.removeItem(COSName.METADATA)
                d.pages.forEach { it.cosObject.removeItem(COSName.METADATA) }
            }
            Hidden.ATTACHMENTS -> {
                namesDict(d)?.removeItem(EMBEDDED)
                cat.cosObject.removeItem(AF)
                d.pages.forEach { p -> p.annotations = p.annotations.filter { it !is PDAnnotationFileAttachment } }
            }
            Hidden.COMMENTS -> d.pages.forEach { p -> p.annotations = p.annotations.filter { !isComment(it) } }
            Hidden.FORMS -> cat.acroForm?.let { f -> runCatching { f.refreshAppearances() }; f.flatten() }
            Hidden.HIDDEN_TEXT -> d.pages.forEach { p -> hiddenText(d, p, remove = true) }
            Hidden.LINKS_SCRIPTS -> {
                cat.cosObject.removeItem(COSName.OPEN_ACTION); cat.cosObject.removeItem(COSName.AA)
                namesDict(d)?.removeItem(COSName.JAVA_SCRIPT)
                d.pages.forEach { p ->
                    p.cosObject.removeItem(COSName.AA)
                    p.annotations = p.annotations.filter { it !is PDAnnotationLink }
                    p.annotations.forEach { a -> a.cosObject.removeItem(COSName.A); a.cosObject.removeItem(COSName.AA) }
                }
                // what bookmarks run: keep the destination, drop the action
                var c = cat.documentOutline?.firstChild
                val stack = ArrayDeque<com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem>()
                while (c != null || stack.isNotEmpty()) {
                    if (c == null) { c = stack.removeLast().nextSibling; continue }
                    c.cosObject.removeItem(COSName.A)
                    val child = c.firstChild
                    if (child != null) { stack.addLast(c); c = child } else c = c.nextSibling
                }
            }
            Hidden.BOOKMARKS -> cat.cosObject.removeItem(COSName.OUTLINES)
            Hidden.PRIVATE -> {
                cat.cosObject.removeItem(PIECE)
                d.pages.forEach { p ->
                    p.cosObject.removeItem(PIECE)
                    xobjects(p.resources).forEach { it.removeItem(PIECE); it.removeItem(OPI); it.removeItem(ALT) }
                }
            }
        }
    }

    private val SHOW = setOf("Tj", "TJ", "'", "\"")

    /** Text drawn in render mode 3 (invisible) or 7 (clip only) in the page's own content: count or remove. */
    private fun hiddenText(d: PDDocument, page: com.tom_roush.pdfbox.pdmodel.PDPage, remove: Boolean): Int {
        val parser = PDFStreamParser(page); parser.parse()
        val tokens = parser.tokens
        var tr = 0
        val stack = ArrayList<Int>()
        var n = 0
        val operands = ArrayList<Any>()
        val out = ArrayList<Any>(tokens.size)
        for (t in tokens) {
            if (t !is Operator) { operands += t; continue }
            when (t.name) {
                "q" -> stack += tr
                "Q" -> if (stack.isNotEmpty()) tr = stack.removeAt(stack.lastIndex)
                "Tr" -> tr = (operands.firstOrNull() as? COSNumber)?.intValue() ?: tr
            }
            if (t.name in SHOW && (tr == 3 || tr == 7)) {
                n++
                if (remove) {
                    // keep the line movement of ' and ", drop the text
                    when (t.name) {
                        "'" -> out.add(Operator.getOperator("T*"))
                        "\"" -> { out.add(operands[0]); out.add(Operator.getOperator("Tw")); out.add(operands[1]); out.add(Operator.getOperator("Tc")); out.add(Operator.getOperator("T*")) }
                    }
                    operands.clear(); continue
                }
            }
            out.addAll(operands); out.add(t); operands.clear()
        }
        out.addAll(operands)
        if (remove && n > 0) {
            val s = PDStream(d)
            s.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
            page.setContents(s)
        }
        return n
    }
}

/** Saves every picture in the PDF as a file (PdfCraft: Export all images). Each image once, however often it is used. */
object ImageExtractor {
    fun extract(src: File, dir: File, base: String): List<File> = PdfOps.load(src).use { d ->
        val seen = HashSet<COSBase>()
        val files = ArrayList<File>()
        fun visit(res: PDResources?, pageNo: Int, depth: Int) {
            if (res == null || depth > 6) return
            for (name in res.xObjectNames) {
                val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
                if (!seen.add(x.cosObject)) continue
                when (x) {
                    is PDImageXObject -> runCatching {
                        if (x.width < 8 || x.height < 8) return@runCatching // tiny bits of artwork
                        val bmp = x.image ?: return@runCatching
                        val jpg = x.suffix == "jpg" && !bmp.hasAlpha()
                        val f = File(dir, "${base}_p${pageNo}_${files.size + 1}.${if (jpg) "jpg" else "png"}")
                        f.outputStream().use { bmp.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it) }
                        bmp.recycle()
                        files += f
                    }
                    is PDFormXObject -> visit(x.resources, pageNo, depth + 1)
                }
            }
        }
        d.pages.forEachIndexed { i, p -> visit(p.resources, i + 1, 0) }
        files
    }
}

/** Files attached to the PDF (PdfCraft's Attachments panel): list, save one, attach one. */
object Attachments {
    data class Item(val name: String, val size: Long, val description: String?)

    private fun tree(d: PDDocument): Map<String, PDComplexFileSpecification> {
        val t = d.documentCatalog.names?.embeddedFiles ?: return emptyMap()
        val out = LinkedHashMap<String, PDComplexFileSpecification>()
        fun walk(n: com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode<PDComplexFileSpecification>) { n.names?.let { out.putAll(it) }; n.kids?.forEach { walk(it) } }
        walk(t); return out
    }

    private fun fileOf(s: PDComplexFileSpecification): PDEmbeddedFile? =
        s.embeddedFileUnicode ?: s.embeddedFile ?: s.embeddedFileDos ?: s.embeddedFileMac ?: s.embeddedFileUnix

    fun list(file: File): List<Item> = PdfOps.load(file).use { d ->
        val named = tree(d).map { (k, s) -> Item(s.filename ?: k, fileOf(s)?.size?.toLong()?.takeIf { it > 0 } ?: (fileOf(s)?.length?.toLong() ?: 0L), s.fileDescription) }
        // files attached as comments on a page
        val onPages = d.pages.flatMap { p -> p.annotations.filterIsInstance<PDAnnotationFileAttachment>() }.mapNotNull { a ->
            (a.file as? PDComplexFileSpecification)?.let { s -> Item(s.filename ?: "attachment", fileOf(s)?.length?.toLong() ?: 0L, a.contents) }
        }
        named + onPages
    }

    fun save(file: File, name: String, out: File): Boolean = PdfOps.load(file).use { d ->
        val specs = tree(d).values + d.pages.flatMap { p -> p.annotations.filterIsInstance<PDAnnotationFileAttachment>().mapNotNull { it.file as? PDComplexFileSpecification } }
        val s = specs.firstOrNull { it.filename == name } ?: return false
        val ef = fileOf(s) ?: return false
        ef.createInputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        true
    }

    fun add(src: File, data: File, name: String, mime: String?, out: File) {
        PdfOps.load(src).use { d ->
            val ef = PDEmbeddedFile(d, data.inputStream())
            ef.size = data.length().toInt(); ef.subtype = mime
            ef.creationDate = java.util.Calendar.getInstance()
            val spec = PDComplexFileSpecification().apply { file = name; fileUnicode = name; embeddedFile = ef; embeddedFileUnicode = ef }
            val cat = d.documentCatalog
            val names = cat.names ?: PDDocumentNameDictionary(cat).also { cat.names = it }
            val existing = tree(d).toMutableMap()
            var key = name; var i = 2
            while (key in existing) key = "$name ($i)".also { i++ }
            existing[key] = spec
            names.embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { setNames(existing) }
            d.save(out)
        }
    }
}

/** Header & footer (PdfCraft: Add Header & Footer): text on every page with {page}, {pages}, {date}, {file}. */
object HeaderFooter {
    enum class Align { LEFT, CENTER, RIGHT }

    fun apply(src: File, header: String, footer: String, align: Align, size: Float, fileName: String, out: File) {
        PdfOps.load(src).use { d ->
            val total = d.numberOfPages
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
            val font = PDType1Font.HELVETICA
            d.pages.forEachIndexed { i, page ->
                fun fill(t: String) = t.replace("{page}", "${i + 1}").replace("{pages}", "$total").replace("{date}", date).replace("{file}", fileName)
                    .map { c -> if (c.code in 32..255) c else '?' }.joinToString("")
                val box = page.cropBox
                val rot = PageGeom.rotation(page)
                val w = if (rot == 90 || rot == 270) box.height else box.width
                val h = if (rot == 90 || rot == 270) box.width else box.height
                val margin = 28f
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setNonStrokingColor(0.25f, 0.25f, 0.25f)
                    for ((text, top) in listOf(header to true, footer to false)) {
                        val t = fill(text)
                        if (t.isBlank()) continue
                        val tw = font.getStringWidth(t) / 1000f * size
                        val x = when (align) { Align.LEFT -> margin; Align.CENTER -> (w - tw) / 2; Align.RIGHT -> w - margin - tw }
                        val y = if (top) h - margin + 4f - size else margin - 4f
                        // displayed (rotated) coordinates -> page space
                        val m = when (rot) {
                            90 -> com.tom_roush.pdfbox.util.Matrix(0f, 1f, -1f, 0f, box.upperRightX - y, box.lowerLeftY + x)
                            180 -> com.tom_roush.pdfbox.util.Matrix(-1f, 0f, 0f, -1f, box.upperRightX - x, box.upperRightY - y)
                            270 -> com.tom_roush.pdfbox.util.Matrix(0f, -1f, 1f, 0f, box.lowerLeftX + y, box.upperRightY - x)
                            else -> com.tom_roush.pdfbox.util.Matrix(1f, 0f, 0f, 1f, box.lowerLeftX + x, box.lowerLeftY + y)
                        }
                        cs.beginText(); cs.setFont(font, size); cs.setTextMatrix(m); cs.showText(t); cs.endText()
                    }
                }
            }
            d.save(out)
        }
    }
}

@Suppress("unused") private val keepImports = listOf(COSArray::class, COSStream::class, COSString::class)
