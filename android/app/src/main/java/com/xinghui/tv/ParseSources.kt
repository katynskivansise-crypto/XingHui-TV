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

    /**
     * 载入解析源。**解析已下沉到引擎**（`cmd=subscription` → parses）：
     * name/url 空值跳过、type0/1 必须 http、ext.header 提取、extJson 序列化，
     * 全在 Rust 侧完成。壳只做映射。
     */
    fun load(subscriptionText: String) {
        if (subscriptionText.isBlank()) return
        val json = runCatching {
            Engine.execute(
                "subscription", "",
                JSONObject().put("text", subscriptionText).put("jar", "").put("url", "").toString()
            )
        }.getOrElse {
            Log.e(TAG, "[parse] 引擎解析失败 ${it.message}")
            return
        }
        loadFromJson(json)
    }

    /** 已有引擎结果时直接喂（省一次重复解析）。 */
    fun loadFromJson(engineJson: String) {
        if (engineJson.isBlank()) return
        val parsed = runCatching {
            val root = JSONObject(engineJson)
            val arr = root.optJSONArray("parses") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                val url = o.optString("url").trim()
                if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
                val headers = HashMap<String, String>()
                o.optJSONObject("headers")?.let { h ->
                    val keys = h.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = h.optString(k, "")
                        if (v.isNotBlank()) headers[k] = v
                    }
                }
                Item(
                    name = name,
                    type = o.optInt("type", 0),
                    url = url,
                    click = o.optString("click", ""),
                    headers = headers,
                    extJson = o.optString("extJson", ""),
                )
            }
        }.getOrElse {
            Log.e(TAG, "[parse] 映射失败 ${it.message}")
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
