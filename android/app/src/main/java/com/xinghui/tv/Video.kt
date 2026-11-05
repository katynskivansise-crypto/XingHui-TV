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
    val source: String = "",    // 所属站点 key（聚合搜索/自动换站用，引擎 searchAll 填充）
) {
    companion object {
        fun fromJson(o: JSONObject): Vod = Vod(
            vodId = o.optString("vod_id"),
            vodName = o.optString("vod_name"),
            // 封面字段兜底：jar 源（AppDrama 等）返回的字段名并不统一
            // —— 首页 converUrl、推荐 coverImage、部分站点 pic/cover，
            // 只认 vod_pic 会让海报区整片空白（标题与角标却有值）。
            vodPic = o.optString("vod_pic")
                .ifBlank { o.optString("pic") }
                .ifBlank { o.optString("coverImage") }
                .ifBlank { o.optString("converUrl") }
                .ifBlank { o.optString("cover") }
                .ifBlank { o.optString("vod_pic_url") },
            vodRemarks = o.optString("vod_remarks"),
            vodYear = o.optString("vod_year"),
            vodArea = o.optString("vod_area"),
            vodContent = o.optString("vod_content"),
            vodPlayFrom = o.optString("vod_play_from"),
            vodPlayUrl = o.optString("vod_play_url"),
            vodFlag = o.optString("vod_flag"),
            vodEpUrl = o.optString("vod_ep_url"),
            vodLinesJson = o.optString("vod_lines"),
            source = o.optString("source"),
        )

        /** 从引擎返回的 {list:[...]} 解析影片列表 */
        fun parseList(json: String): List<Vod> {
            return try {
                val arr = JSONObject(json).optJSONArray("list") ?: return emptyList()
                (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
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
                ?.mapIndexed { j, ep ->
                    // 对齐原版 Flag.setUrls：
                    //   "名称$地址" → 名称；名称为空 → 用集序号
                    //   没有 $ 的单集  → 名称用集序号，整段当地址（原版是 Episode.create(number, urls[i])）
                    val idx = ep.indexOf('$')
                    if (idx >= 0) {
                        val nm = ep.substring(0, idx).trim()
                        (nm.ifBlank { "第${j + 1}集" }) to ep.substring(idx + 1)
                    } else {
                        "第${j + 1}集" to ep
                    }
                } ?: emptyList()
            // 线路名为空时给个可读名字
            PlayLine(fromName.ifBlank { "线路${i + 1}" }, eps)
        }
    }
}

/** 一个播放线路：线路名 + 集数列表 */
data class PlayLine(val name: String, val episodes: List<Pair<String, String>>)
