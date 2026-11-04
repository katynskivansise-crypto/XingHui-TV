package com.xinghui.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地资料库：历史记录 + 收藏（SharedPreferences 持久化，JSON 数组）。
 * 历史自动记录，收藏由详情页操作。
 */
class HistoryStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记录播放历史（去重，上限 50 条，最新在前）。flag/epUrl 用于续播。 */
    fun addHistory(
        vodId: String,
        name: String,
        pic: String = "",
        remarks: String = "",
        flag: String = "",
        epUrl: String = "",
    ) {
        if (vodId.isBlank()) return
        val list = history().filterNot { it.optString("vod_id") == vodId }.toMutableList()
        list.add(0, JSONObject().apply {
            put("vod_id", vodId)
            put("vod_name", name)
            put("vod_pic", pic)
            put("vod_remarks", remarks)
            put("vod_flag", flag)
            put("vod_ep_url", epUrl)
            put("time", System.currentTimeMillis())
        })
        save(KEY_HISTORY, list.take(50))
    }

    fun history(): List<JSONObject> = read(KEY_HISTORY)

    /** 删除单条历史 */
    fun removeHistory(vodId: String) {
        if (vodId.isBlank()) return
        save(KEY_HISTORY, history().filterNot { it.optString("vod_id") == vodId })
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    /** 收藏 / 取消收藏，返回是否已收藏 */
    fun toggleFavorite(vodId: String, name: String, pic: String = "", remarks: String = ""): Boolean {
        if (vodId.isBlank()) return false
        val list = favorites().toMutableList()
        val existed = list.any { it.optString("vod_id") == vodId }
        if (existed) {
            list.removeAll { it.optString("vod_id") == vodId }
        } else {
            list.add(0, JSONObject().apply {
                put("vod_id", vodId)
                put("vod_name", name)
                put("vod_pic", pic)
                put("vod_remarks", remarks)
            })
        }
        save(KEY_FAVORITE, list)
        return !existed
    }

    fun isFavorite(vodId: String): Boolean =
        favorites().any { it.optString("vod_id") == vodId }

    fun favorites(): List<JSONObject> = read(KEY_FAVORITE)

    fun clearFavorites() = prefs.edit().remove(KEY_FAVORITE).apply()

    private fun read(key: String): List<JSONObject> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(key: String, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "xinghui_library"
        private const val KEY_HISTORY = "history"
        private const val KEY_FAVORITE = "favorites"
    }
}
