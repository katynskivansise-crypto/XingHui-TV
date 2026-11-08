package com.xinghui.tv

import android.content.Context
import org.json.JSONArray

/**
 * 搜索历史存储。
 *
 * 原来存 StringSet —— set 本身不保序，取出来顺序是乱的，
 * 和原版 RecordAdapter「最近的排最前」正好相反。
 * 这里改成 JSON 数组，顺序即顺序，条数上限 8（原版 RecordAdapter 也是 8）。
 */
class SearchWordStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("xinghui_search", Context.MODE_PRIVATE)

    fun getWords(): List<String> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val w = arr.optString(i, "").trim()
                if (w.isNotEmpty()) out.add(w)
            }
            out
        }.getOrDefault(emptyList())
    }

    fun putWords(list: List<String>) {
        sp.edit().putString(KEY, JSONArray(list.take(MAX).toList()).toString()).apply()
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val list = getWords().filterNot { it == w }.toMutableList()
        list.add(0, w)
        putWords(list)
    }

    fun clearAll() {
        sp.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "words"
        const val MAX = 8
    }
}
