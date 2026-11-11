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

    /** 记录播放历史（去重，最新在前）。flag/epUrl 用于续播。 */
    fun addHistory(
        vodId: String,
        name: String,
        pic: String = "",
        remarks: String = "",
        flag: String = "",
        epUrl: String = "",
        position: Long = 0,
    ) {
        // 原版 History.createHistory 从不会因为 id 为空就跳过（Room 的 key 由 cid + 片名组合）。
        // 这里原来是一句 `if (vodId.isBlank()) return` —— 详情接口不返回 vod_id 的站点，
        // 看多少遍都不会留下一条记录，而且没有任何报错。这就是「好多看过的都没有」的静默原因。
        val key = vodId.ifBlank { name }
        if (key.isBlank()) return
        // 起播时 recordHistory() 会走到这里：它是"记录这部片看过"，不是"位置归零"。
        // 不沿用旧值的话，每次起播都会把续播位置和片头片尾一起冲掉。
        val old = history().firstOrNull { it.optString("vod_id") == key }
        val keepPos = if (position > 0) position else (old?.optLong("position", 0) ?: 0)
        val list = history().filterNot { it.optString("vod_id") == key }.toMutableList()
        list.add(0, JSONObject().apply {
            put("vod_id", key)
            put("vod_name", name)
            put("vod_pic", pic)
            put("vod_remarks", remarks)
            put("vod_flag", flag)
            put("vod_ep_url", epUrl)
            put("position", keepPos)
            put("time", System.currentTimeMillis())
            if (old != null) {
                if (old.has("opening")) put("opening", old.optLong("opening"))
                if (old.has("ending")) put("ending", old.optLong("ending"))
                if (old.has("scale")) put("scale", old.optInt("scale"))
            }
        })
        // 原版 History 是 Room 表、不设条数上限，这里原来只留 50 条，看得多的会被顶掉
        save(KEY_HISTORY, list.take(200))
    }

    /**
     * 保存播放位置（毫秒）。按 vodId + 集地址定位：
     * 同一部剧换集后位置就作废，不会出现第 3 集跳到第 1 集进度的情况。
     */
    fun setPosition(vodId: String, epUrl: String, positionMs: Long) {
        if (vodId.isBlank() || positionMs <= 0) return
        val list = history().toMutableList()
        val idx = list.indexOfFirst { it.optString("vod_id") == vodId }
        com.xinghui.tv.MpvDebug.log("[hist] save vod=$vodId pos=$positionMs row=${idx >= 0} ep=${epUrl.takeLast(18)}")
        if (idx < 0) return
        list[idx] = JSONObject(list[idx].toString()).apply {
            put("position", positionMs)
            put("position_ep", epUrl)
        }
        save(KEY_HISTORY, list)
    }


    /**
     * 起播点自检：把「历史行是否存在 / 存的集地址 / 当前集地址 / 存的位置」压成一行，
     * 直接拼进 [play] 起播 那条日志 —— 续播点为 0 时不用再猜是没落盘还是签名不一致。
     */
    fun probe(vodId: String, epUrl: String): String {
        val obj = history().firstOrNull { it.optString("vod_id") == vodId }
        if (obj == null) return "row=false total=${history().size}"
        val stored = obj.optString("position_ep", obj.optString("vod_ep_url"))
        return "row=true pos=${obj.optLong("position", 0)} eq=${stored == epUrl} " +
            "stored=${stored.takeLast(24)} now=${epUrl.takeLast(24)}"
    }

    /** 读取续播位置；集地址不一致（换集了）则返回 0，从头播 */
    fun position(vodId: String, epUrl: String): Long {
        val obj = history().firstOrNull { it.optString("vod_id") == vodId } ?: return 0
        if (obj.optString("position_ep", obj.optString("vod_ep_url")) != epUrl) return 0
        return obj.optLong("position", 0)
    }

    /**
     * 一次读出「片头 / 片尾 / 本集进度」，返回 LongArray(opening, ending, position)。
     *
     * 原来 openingOf + endingOf + position 各自调一次 history()，而 history() 每次都要
     * 把整份历史 JSONArray 重新解析一遍；播放页起播路径上就是三次全量解析。
     * position 只在 epUrl 与上次那集一致时才有值（与原版 updateHistory 的语义一致）。
     */
    fun marks(vodId: String, epUrl: String = ""): LongArray {
        val zero = longArrayOf(0, 0, 0)
        if (vodId.isBlank()) return zero
        val obj = history().firstOrNull { it.optString("vod_id") == vodId } ?: return zero
        // 与 position() 完全一致：不做空串特判，否则「集地址为空、历史里也是空」这类站点
        // 会从「能续播」变成「从 0 播」，属于把优化做成行为变更
        val storedEp = obj.optString("position_ep", obj.optString("vod_ep_url"))
        val eq = storedEp == epUrl
        com.xinghui.tv.MpvDebug.log(
            "[hist] load vod=$vodId pos=${obj.optLong("position", 0)} eq=$eq " +
                "stored=${storedEp.takeLast(18)} now=${epUrl.takeLast(18)}"
        )
        val pos = if (eq) obj.optLong("position", 0) else 0L
        return longArrayOf(obj.optLong("opening", 0), obj.optLong("ending", 0), pos)
    }

    fun history(): List<JSONObject> = read(KEY_HISTORY)

    /** 上次看到的那一集的地址（用于详情页自动定位到该集起播），没有记录返回空串 */
    /**
     * 最后一次**打开过**的那一集地址（`vod_ep_url`）。
     *
     * 和 epUrlOf 的区别：epUrlOf 读的是 `position_ep`，只有真的落过一次进度才会更新；
     * 而 `vod_ep_url` 是 addHistory 每次起播就写，反映"用户最后点开的是哪一集"。
     * 从播放页返回、集数格标当前集，都要用这个，否则换集后立刻退出会拿到旧集号。
     */
    fun lastEpUrl(vodId: String): String {
        if (vodId.isBlank()) return ""
        val obj = history().firstOrNull { it.optString("vod_id") == vodId } ?: return ""
        return obj.optString("vod_ep_url", obj.optString("position_ep"))
    }

    fun epUrlOf(vodId: String): String {
        if (vodId.isBlank()) return ""
        val obj = history().firstOrNull { it.optString("vod_id") == vodId } ?: return ""
        return obj.optString("position_ep", obj.optString("vod_ep_url"))
    }

    /**
     * 本片的画面缩放档位（对齐原版 History.getScale）。
     * 返回 -1 表示这片从没记录过，调用方应回落到全局 Setting.getScale()。
     */
    fun scaleOf(vodId: String): Int {
        if (vodId.isBlank()) return -1
        val obj = history().firstOrNull { it.optString("vod_id") == vodId } ?: return -1
        return obj.optInt("scale", -1)
    }

    /**
     * 写入本片的缩放档位（对齐原版 VideoActivity.onScale → mHistory.setScale）。
     * 返回 false 表示该片还没有历史记录，调用方应改写全局设置。
     */
    fun setScaleOf(vodId: String, scale: Int): Boolean {
        if (vodId.isBlank()) return false
        val list = history().toMutableList()
        val idx = list.indexOfFirst { it.optString("vod_id") == vodId }
        if (idx < 0) return false
        list[idx] = JSONObject(list[idx].toString()).apply { put("scale", scale) }
        save(KEY_HISTORY, list)
        return true
    }

    /** 本片的片头毫秒数（对齐原版 History.getOpening），0 表示没设 */
    fun openingOf(vodId: String): Long {
        if (vodId.isBlank()) return 0
        val obj = history().firstOrNull { it.optString("vod_id") == vodId } ?: return 0
        return obj.optLong("opening", 0)
    }

    /** 写本片片头（原版 VideoActivity.setOpening → mHistory.setOpening）；false 表示该片还没有历史记录 */
    fun setOpeningOf(vodId: String, ms: Long): Boolean = putLong(vodId, "opening", ms)

    /** 本片的片尾毫秒数（从结尾往回算），对齐原版 History.getEnding */
    fun endingOf(vodId: String): Long {
        if (vodId.isBlank()) return 0
        val obj = history().firstOrNull { it.optString("vod_id") == vodId } ?: return 0
        return obj.optLong("ending", 0)
    }

    /** 写本片片尾（原版 VideoActivity.setEnding → mHistory.setEnding） */
    fun setEndingOf(vodId: String, ms: Long): Boolean = putLong(vodId, "ending", ms)

    private fun putLong(vodId: String, key: String, value: Long): Boolean {
        if (vodId.isBlank()) return false
        val list = history().toMutableList()
        val idx = list.indexOfFirst { it.optString("vod_id") == vodId }
        if (idx < 0) return false
        list[idx] = JSONObject(list[idx].toString()).apply { put(key, value) }
        save(KEY_HISTORY, list)
        return true
    }

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
