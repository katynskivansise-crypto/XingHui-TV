package com.xinghui.tv

import android.content.Context
import org.json.JSONArray

/**
 * 订阅站点存储（SharedPreferences 持久化）。
 * - 站点列表：JSON 数组字符串
 * - 当前站点：只存 key
 */
class SubscriptionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部已订阅站点 */
    fun sites(): List<Site> {
        val raw = prefs.getString(KEY_SITES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Site.fromJson(arr.getString(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(sites: List<Site>) {
        val arr = JSONArray()
        sites.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SITES, arr.toString()).apply()
    }

    /** 批量新增（按 key 去重） */
    fun addAll(newSites: List<Site>) {
        if (newSites.isEmpty()) return
        val all = (sites() + newSites).distinctBy { it.key }
        save(all)
        // 首次添加时自动设为当前站点
        if (currentKey() == null && all.isNotEmpty()) {
            setCurrent(all.first().key)
        }
    }

    /** 删除站点；若删的是当前站点则清空当前 */
    fun remove(key: String) {
        val all = sites().filterNot { it.key == key }
        save(all)
        if (currentKey() == key) {
            prefs.edit().remove(KEY_CURRENT).apply()
        }
    }

    fun setCurrent(key: String) {
        prefs.edit().putString(KEY_CURRENT, key).apply()
    }

    fun currentKey(): String? = prefs.getString(KEY_CURRENT, null)

    /** 当前选中的站点；没有则返回 null */
    fun currentSite(): Site? {
        val k = currentKey() ?: return null
        return sites().firstOrNull { it.key == k }
    }

    companion object {
        private const val PREFS = "xinghui_subscriptions"
        private const val KEY_SITES = "sites"
        private const val KEY_CURRENT = "current_key"
    }
}
