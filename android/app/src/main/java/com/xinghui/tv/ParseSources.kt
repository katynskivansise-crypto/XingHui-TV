package com.xinghui.tv

import android.util.Log
import org.json.JSONObject

/**
 * 订阅顶层的 `parses`（解析源）。
 *
 * 原版 `ParseJob.doInBackground` 按 `parse.getType()` 分流：
 *   type=0  嗅探      —— WebView 打开 `parse.url + 真实地址`，从网络请求里截 m3u8
 *   type=1  Json      —— GET `parse.url + 真实地址`，从返回 JSON 取 `url` / `data.url`
 *   type=2  Json扩展  —— 反射 jar 内 `com.github.catvod.parser.Json<key>`
 *   type=3  Json聚合  —— 反射 jar 内 `com.github.catvod.parser.Mix<key>`
 *   type=4  超级解析  —— 并发跑全部 type=1 + 一个 web 嗅探，谁先成功算谁
 *
 * 实测当前订阅（qist/tvbox jsm.json，29 条）：
 *   - type=0 ×24（WebView 嗅探，可用）
 *   - type=1 ×4（pandown.pro / mfjx.iwsyy.xyz 拒连、api.tyun77.cn 403、zy.qiaoji8.com 404）
 *   - type=3 ×1（`Json聚合` url=`Demo` → jar 里确有 `MixDemo`）
 *   - type=2 ×0、type=4 ×0
 */
object ParseSources {

    private const val TAG = "XingHuiTV"

    data class Item(
        val name: String,
        val type: Int,
        val url: String,          // type0/1 是接口前缀；type2/3 是 jar 内类名后缀（key）
        val click: String = "",
        val headers: Map<String, String> = emptyMap(),
        val extJson: String = "", // 原样的 ext 对象字符串，jsonExtMix 要用
    )

    @Volatile
    private var items: List<Item> = emptyList()

    /** 用户当前选中的（null = 还没选，取列表第一个） */
    @Volatile
    var current: Item? = null

    fun load(subscriptionText: String) {
        val parsed = runCatching { doLoad(subscriptionText) }.getOrElse {
            Log.e(TAG, "[parse] 解析源载入失败 ${it.message}")
            emptyList()
        }
        items = parsed
        Log.e(TAG, "[parse] 解析源 ${parsed.size} 个：" +
            parsed.groupingBy { it.type }.eachCount().entries.joinToString(" ") { "type${it.key}×${it.value}" })
    }

    fun all(): List<Item> = items

    /** UI 那一行只列 type=0：其余类型要么依赖 jar、要么站点已失联，点了没反应不如不显示 */
    fun web(): List<Item> = items.filter { it.type == 0 }

    /** 超级解析 / 自动轮换用：全部可用类型 */
    fun byType(type: Int): List<Item> = items.filter { it.type == type }

    fun isEmpty(): Boolean = items.isEmpty()

    /** 按名字找（`playUrl = "parse:名字"` 那条路径用） */
    fun byName(name: String): Item? = items.firstOrNull { it.name == name }

    private fun doLoad(text: String): List<Item> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val holder = root.optJSONObject("video")?.takeIf { it.has("parses") } ?: root
        val arr = holder.optJSONArray("parses") ?: return emptyList()

        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optInt("type", 0)
            val name = o.optString("name", "").trim()
            val url = o.optString("url", "").trim()
            if (name.isEmpty() || url.isEmpty()) continue
            // type0/1 必须是 http 前缀；type2/3 的 url 是 jar 里的类名后缀，不能要求 http
            if ((type == 0 || type == 1) && !url.startsWith("http")) {
                Log.e(TAG, "[parse] 跳过非 http 解析源 $name → ${url.take(40)}")
                continue
            }
            val extObj = o.opt("ext")
            out.add(
                Item(
                    name = name,
                    type = type,
                    url = url,
                    click = o.optString("click", ""),
                    headers = headerOf(extObj),
                    extJson = when (extObj) {
                        is JSONObject -> extObj.toString()
                        is String -> extObj
                        else -> ""
                    },
                )
            )
        }
        return out
    }

    /** ext 既可能是 {"header":{"User-Agent":"..."}} 也可能是字符串（原版 Parse.getHeaders 同义） */
    private fun headerOf(ext: Any?): Map<String, String> {
        val headerObj = (ext as? JSONObject)?.optJSONObject("header") ?: return emptyMap()
        val out = HashMap<String, String>()
        val keys = headerObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = headerObj.optString(k, "")
            if (v.isNotBlank()) out[k] = v
        }
        return out
    }
}
