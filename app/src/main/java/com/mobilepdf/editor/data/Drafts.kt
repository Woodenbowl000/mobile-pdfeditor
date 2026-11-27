package com.mobilepdf.editor.data

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.annot.MarkKind
import com.mobilepdf.editor.annot.ShapeKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Crash recovery. While a document has unsaved work, a small JSON draft is kept in app storage:
 * the pending annotations, pending form values, and (if the PDF itself was already changed, e.g. pages
 * reorganized) the path of the edited working copy. If the app is killed — common on 1 GB phones —
 * the next open of the same document offers to restore it.
 */
data class Draft(
    val annots: Map<Int, List<Annot>>,
    val formValues: Map<String, String>,
    val workFile: String?,
    val time: Long,
)

class DraftStore(private val ctx: Context) {
    private val dir get() = File(ctx.filesDir, "drafts").apply { mkdirs() }

    private fun keyFile(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.json")
    }

    fun load(key: String): Draft? = runCatching {
        val f = keyFile(key)
        if (!f.exists()) return null
        decode(JSONObject(f.readText()))
    }.getOrNull()

    fun save(key: String, d: Draft) {
        val f = keyFile(key)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(encode(d).toString())
        tmp.renameTo(f) // atomic replace: a draft is never half-written
    }

    fun delete(key: String) { keyFile(key).delete() }

    /** Working copies referenced by drafts must survive the cache clean-up. */
    fun referencedFiles(): Set<File> = dir.listFiles().orEmpty().mapNotNull { f ->
        runCatching { JSONObject(f.readText()).optString("work").takeIf { it.isNotEmpty() }?.let(::File) }.getOrNull()
    }.toSet()

    /** Drafts older than a week are dropped. */
    fun prune() {
        val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        dir.listFiles().orEmpty().filter { it.lastModified() < limit }.forEach { it.delete() }
    }

    companion object {
        fun encode(d: Draft): JSONObject {
            val pages = JSONObject()
            d.annots.forEach { (p, list) -> if (list.isNotEmpty()) pages.put(p.toString(), JSONArray().apply { list.forEach { put(encodeAnnot(it)) } }) }
            val form = JSONObject().apply { d.formValues.forEach { (k, v) -> put(k, v) } }
            return JSONObject().put("v", 1).put("pages", pages).put("form", form).put("work", d.workFile ?: "").put("t", d.time)
        }

        fun decode(o: JSONObject): Draft {
            val pages = o.getJSONObject("pages")
            val annots = pages.keys().asSequence().associate { k ->
                val a = pages.getJSONArray(k)
                k.toInt() to List(a.length()) { decodeAnnot(a.getJSONObject(it)) }
            }
            val form = o.optJSONObject("form")
            val values = form?.keys()?.asSequence()?.associate { it to form.getString(it) } ?: emptyMap()
            return Draft(annots, values, o.optString("work").takeIf { it.isNotEmpty() }, o.optLong("t"))
        }

        private fun pts(l: List<Offset>) = JSONArray().apply { l.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }
        private fun pts(a: JSONArray) = List(a.length() / 2) { Offset(a.getDouble(2 * it).toFloat(), a.getDouble(2 * it + 1).toFloat()) }
        private fun rect(r: Rect) = JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble())
        private fun rect(a: JSONArray) = Rect(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
        private fun JSONObject.f(k: String) = getDouble(k).toFloat()

        fun encodeAnnot(a: Annot): JSONObject {
            val o = JSONObject().put("id", a.id).put("c", a.color)
            return when (a) {
                is Annot.Ink -> o.put("k", "ink").put("w", a.width.toDouble()).put("m", a.marker).put("p", pts(a.pts))
                is Annot.Shape -> o.put("k", "shape").put("w", a.width.toDouble()).put("s", a.kind.name).put("p", pts(listOf(a.a, a.b)))
                is Annot.Mark -> o.put("k", "mark").put("s", a.kind.name).put("r", JSONArray().apply { a.rects.forEach { put(rect(it)) } })
                is Annot.Text -> o.put("k", "text").put("p", pts(listOf(a.pos))).put("t", a.text).put("z", a.size.toDouble())
                    .put("f", a.font.let { f ->
                        JSONObject().put("fam", f.family).put("b", f.bold).put("i", f.italic).put("pg", f.fromPage)
                            .put("pf", f.pdfFont ?: "").put("ff", f.file ?: "")
                    })
                is Annot.Replace -> o.put("k", "replace").put("r", rect(a.rect)).put("t", a.text).put("z", a.size.toDouble())
                    .put("o", a.original).put("cv", a.cover)
                is Annot.Redact -> o.put("k", "redact").put("r", JSONArray().apply { a.rects.forEach { put(rect(it)) } }).put("t", a.label)
                is Annot.Seal -> o.put("k", "seal").put("r", rect(a.rect)).put("t", a.text)
                is Annot.Note -> o.put("k", "note").put("p", pts(listOf(a.pos))).put("t", a.text)
                is Annot.Stamp -> o.put("k", "stamp").put("r", rect(a.rect)).put("st", JSONArray().apply { a.strokes.forEach { put(pts(it)) } })
                    .put("img", a.image ?: "")
            }
        }

        fun decodeAnnot(o: JSONObject): Annot {
            val id = o.getLong("id"); val c = o.getInt("c")
            return when (o.getString("k")) {
                "ink" -> Annot.Ink(id, c, o.f("w"), o.getBoolean("m"), pts(o.getJSONArray("p")))
                "shape" -> pts(o.getJSONArray("p")).let { Annot.Shape(id, c, o.f("w"), ShapeKind.valueOf(o.getString("s")), it[0], it[1]) }
                "mark" -> o.getJSONArray("r").let { a -> Annot.Mark(id, c, MarkKind.valueOf(o.getString("s")), List(a.length()) { rect(a.getJSONArray(it)) }) }
                "text" -> Annot.Text(id, c, pts(o.getJSONArray("p"))[0], o.getString("t"), o.f("z"), o.optJSONObject("f")?.let { f ->
                    com.mobilepdf.editor.annot.FontChoice(
                        f.optString("fam", "sans"), f.optBoolean("b"), f.optBoolean("i"),
                        f.optString("pf").ifEmpty { null }, f.optInt("pg", -1), f.optString("ff").ifEmpty { null },
                    )
                } ?: com.mobilepdf.editor.annot.FontChoice())
                "replace" -> Annot.Replace(id, c, rect(o.getJSONArray("r")), o.getString("t"), o.f("z"), o.optString("o"), o.optInt("cv", -1))
                "redact" -> o.getJSONArray("r").let { a -> Annot.Redact(id, c, List(a.length()) { rect(a.getJSONArray(it)) }, o.optString("t")) }
                "seal" -> Annot.Seal(id, c, rect(o.getJSONArray("r")), o.getString("t"))
                "note" -> Annot.Note(id, c, pts(o.getJSONArray("p"))[0], o.getString("t"))
                "stamp" -> o.getJSONArray("st").let { s ->
                    Annot.Stamp(id, c, rect(o.getJSONArray("r")), List(s.length()) { pts(s.getJSONArray(it)) }, o.optString("img").takeIf { it.isNotEmpty() })
                }
                else -> throw IllegalArgumentException("unknown annotation")
            }
        }
    }
}
