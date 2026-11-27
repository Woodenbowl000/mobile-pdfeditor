package com.mobilepdf.editor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentItem(val uri: String, val name: String, val time: Long)

/** Tiny recent-files list in SharedPreferences (no database, no extra dependency). */
class Recents(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun load(): List<RecentItem> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { RecentItem(it.getString("u"), it.getString("n"), it.getLong("t")) } }
    }.getOrDefault(emptyList())

    fun add(item: RecentItem): List<RecentItem> {
        val list = (listOf(item) + load().filter { it.uri != item.uri }).take(20)
        save(list); return list
    }

    fun remove(uri: String): List<RecentItem> {
        val list = load().filter { it.uri != uri }; save(list); return list
    }

    private fun save(list: List<RecentItem>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name).put("t", it.time)) }
        prefs.edit().putString("items", a.toString()).apply()
    }
}

/** Remembers the user's drawn signature (list of strokes, unit-square coordinates). */
class SignatureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("signature", Context.MODE_PRIVATE)

    fun load(): List<List<Pair<Float, Float>>>? = runCatching {
        val s = prefs.getString("strokes", null) ?: return null
        val a = JSONArray(s)
        List(a.length()) { i ->
            val st = a.getJSONArray(i)
            List(st.length()) { j -> st.getJSONArray(j).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } }
        }
    }.getOrNull()

    fun save(strokes: List<List<Pair<Float, Float>>>) {
        val a = JSONArray()
        strokes.forEach { s -> a.put(JSONArray().also { sa -> s.forEach { sa.put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) } }) }
        prefs.edit().putString("strokes", a.toString()).apply()
    }
}
