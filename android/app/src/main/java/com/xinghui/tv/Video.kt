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
    // 形态覆盖，对齐原版 bean/Vod 的 land/circle/ratio：
    //   land==1   → rect，ratio 缺省 1.33
    //   circle==1 → oval，ratio 缺省 1.0
    //   都为 0    → Style.get 返回 null，回落到站点 style
    // 原版 VodFragment 取 result.getStyle(getStyle())，即"首条影片的形态决定整页"。
    val land: Int = 0,
    val circle: Int = 0,
    val ratio: Float = 0f,
) {
    companion object {
        fun fromJson(o: JSONObject): Vod = Vod(
            vodId = o.optString("vod_id"),
            vodName = o.optString("vod_name"),
            // 封面字段兜底：jar 源（AppDrama 等）返回的字段名并不统一。
            // 实测现象：同一站点首页有图、分类页整片空白 —— 标题与角标都有值、只有图取不到，
            // 说明两套接口组装的字段名不同。dex 里同时存在 vod_pic / vodPic / vod_pic_thumb /
            // cover / image / poster，全部纳入兜底，主图优先、缩略图其次。
            vodPic = o.optString("vod_pic")
                .ifBlank { o.optString("pic") }
                .ifBlank { o.optString("vodPic") }
                .ifBlank { o.optString("vod_pic_url") }
                .ifBlank { o.optString("coverImage") }
                .ifBlank { o.optString("converUrl") }
                .ifBlank { o.optString("vod_pic_thumb") }
                .ifBlank { o.optString("cover") }
                .ifBlank { o.optString("poster") }
                .ifBlank { o.optString("image") }
                .ifBlank { o.optString("img") }
                .ifBlank { o.optString("thumbnail") },
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
            land = o.optInt("land", 0),
            circle = o.optInt("circle", 0),
            ratio = o.optDouble("ratio", 0.0).toFloat(),
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
        val froms = vodPlayFrom.split("$$$")
        val urls = vodPlayUrl.split("$$$")
        val out = ArrayList<PlayLine>()
        for (i in froms.indices) {
            // 对齐原版 Vod.setVodFlags：
            //   if (playFlags[i].isEmpty() || i >= playUrls.length) continue;
            // 关键：不能先 filter 掉空段再按索引取 url —— filter 会让线路名与地址错位，
            // 表现为"线路 C 里面装的是线路 B 的集"。原版是按原始索引对应、空名直接跳过。
            val name = froms[i].trim()
            if (name.isEmpty() || i >= urls.size) continue
            val eps = urls[i].split("#").filter { it.isNotBlank() }.mapIndexed { j, ep ->
                // 对齐原版 Flag.createEpisode：
                //   split.length > 1 → 名称取 $ 前（为空则用 %02d 序号）
                //   没有 $           → 名称用 %02d 序号，整段当地址
                // 序号格式是 %02d（01、02…），原版不是"第N集"。
                val idx = ep.indexOf('$')
                val no = "%02d".format(j + 1)
                if (idx >= 0) {
                    ep.substring(0, idx).trim().ifBlank { no } to ep.substring(idx + 1)
                } else {
                    no to ep
                }
            }
            out.add(PlayLine(name, eps))
        }
        // 兜底：个别站只给 vod_play_url 不给 vod_play_from，按原版会一条线路都建不出来，
        // 这里给一条"线路1"，避免详情页完全没有可播集。
        if (out.isEmpty() && urls.size == 1 && urls[0].isNotBlank()) {
            val eps = urls[0].split("#").filter { it.isNotBlank() }.mapIndexed { j, ep ->
                val idx = ep.indexOf('$')
                val no = "%02d".format(j + 1)
                if (idx >= 0) ep.substring(0, idx).trim().ifBlank { no } to ep.substring(idx + 1)
                else no to ep
            }
            out.add(PlayLine("线路1", eps))
        }
        return out
    }
}

/** 一个播放线路：线路名 + 集数列表 */
data class PlayLine(val name: String, val episodes: List<Pair<String, String>>)
