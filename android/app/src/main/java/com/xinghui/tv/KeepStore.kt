package com.xinghui.tv

import android.content.Context
import android.content.SharedPreferences

/**
 * 直播频道收藏（对齐原项目 Keep：按频道名存储，含"我的收藏"分组）。
 */
class KeepStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun channels(): Set<String> =
        sp.getStringSet(KEY, emptySet()) ?: emptySet()

    /** 切换收藏，返回操作后是否已收藏 */
    fun toggle(name: String): Boolean {
        if (name.isBlank()) return false
        val s = channels().toMutableSet()
        val existed = s.contains(name)
        if (existed) s.remove(name) else s.add(name)
        sp.edit().putStringSet(KEY, s).apply()
        return !existed
    }

    fun clear() = sp.edit().remove(KEY).apply()

    companion object {
        private const val PREFS = "xinghui_keep"
        private const val KEY = "channels"
    }
}
