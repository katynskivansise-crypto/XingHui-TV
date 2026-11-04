package com.xinghui.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * 影片数据模型（与 TVBox 生态 JSON 字段一一对应）。
 */
data class Vod(
    val vodId: String,
    val vodName: String,
    val vodPic: String = "",
    val vodRemarks: String = "",
    val vodYear: String = "",
    val vodArea: String = "",
    val vodContent: String = "",
    val vodPlayFrom: String = "",
    val vodPlayUrl: String = "",
    val vodFlag: String = "",   // 上次播放线路（历史续播用）
    val vodEpUrl: String = "",  // 上次播放集地址（历史续播用）
    val vodLinesJson: String = "", // 引擎解析的结构化线路（下沉多线路解析，缺失时壳回退）
) {
    companion object {
        fun fromJson(o: JSONObject): Vod = Vod(
            vodId = o.optString("vod_id"),
            vodName = o.optString("vod_name"),
            vodPic = o.optString("vod_pic"),
            vodRemarks = o.optString("vod_remarks"),
            vodYear = o.optString("vod_year"),
            vodArea = o.optString("vod_area"),
            vodContent = o.optString("vod_content"),
            vodPlayFrom = o.optString("vod_play_from"),
            vodPlayUrl = o.optString("vod_play_url"),
            vodFlag = o.optString("vod_flag"),
            vodEpUrl = o.optString("vod_ep_url"),
            vodLinesJson = o.optString("vod_lines"),
        )

        /** 从引擎返回的 {list:[...]} 解析影片列表 */
        fun parseList(json: String): List<Vod> = try {
            val arr = JSONObject(json).optJSONArray("list") ?: return emptyList()
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 播放线路（引擎 vod_lines 优先，缺失时本地解析） */
    fun lines(): List<PlayLine> {
        if (vodLinesJson.isNotBlank()) {
            val parsed = runCatching {
                val arr = JSONArray(vodLinesJson)
                (0 until arr.length()).mapNotNull { i ->
                    val line = arr.optJSONObject(i) ?: return@mapNotNull null
                    val epsArr = line.optJSONArray("episodes") ?: return@mapNotNull null
                    val eps = (0 until epsArr.length()).mapNotNull { j ->
                        val e = epsArr.optJSONObject(j) ?: return@mapNotNull null
                        e.optString("name") to e.optString("url")
                    }
                    PlayLine(line.optString("name"), eps)
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return parseLines()
    }

    /** 播放线路：[{name, episodes:[(名称,地址)]}]；TVBox 格式用 $$$ 分隔线路、# 分隔集、$ 分隔名称/地址 */
    fun parseLines(): List<PlayLine> {
        if (vodPlayUrl.isBlank()) return emptyList()
        val froms = vodPlayFrom.split("$$$").filter { it.isNotBlank() }
        val urls = vodPlayUrl.split("$$$").filter { it.isNotBlank() }
        return froms.mapIndexedNotNull { i, fromName ->
            val eps = urls.getOrNull(i)
                ?.split("#")
                ?.filter { it.isNotBlank() }
                ?.map { ep ->
                    val idx = ep.indexOf('$')
                    if (idx >= 0) ep.substring(0, idx) to ep.substring(idx + 1) else ep to ep
                } ?: emptyList()
            PlayLine(fromName, eps)
        }
    }
}

/** 一个播放线路：线路名 + 集数列表 */
data class PlayLine(val name: String, val episodes: List<Pair<String, String>>)
