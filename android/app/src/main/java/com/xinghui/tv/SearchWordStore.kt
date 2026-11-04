package com.xinghui.tv

import android.content.Context
import android.content.SharedPreferences

/**
 * 搜索历史存储（SharedPreferences 去重、上限 20 条）。
 */
class SearchWordStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun addWord(word: String) {
        if (word.isBlank()) return
        val list = getWords().filterNot { it == word }.toMutableList()
        list.add(0, word)
        sp.edit().putStringSet(KEY, list.take(MAX).toSet()).apply()
    }

    fun getWords(): List<String> =
        sp.getStringSet(KEY, emptySet())?.toList() ?: emptyList()

    fun clearAll() {
        sp.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS = "xinghui_search"
        private const val KEY = "words"
        private const val MAX = 20
    }
}
