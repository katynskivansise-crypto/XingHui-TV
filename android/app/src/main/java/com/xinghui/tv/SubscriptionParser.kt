package com.xinghui.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * 订阅文本解析器。支持三种常见格式：
 * 1. 多站点聚合：{ "sites": [ {key,name,api,ext}, ... ] }
 * 2. 单站点配置：{ key, name, api, ext }
 * 3. 逐行 txt：每行一个 JSON 站点配置
 */
object SubscriptionParser {

    fun parse(text: String): List<Site> {
        val sites = mutableListOf<Site>()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return sites

        // 1) / 2) 整体 JSON
        try {
            val obj = JSONObject(trimmed)
            val arr = obj.optJSONArray("sites")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    siteFrom(s, i)?.let { sites.add(it) }
                }
                return sites
            }
            if (obj.has("key") || obj.has("name")) {
                siteFrom(obj, 0)?.let { sites.add(it) }
                return sites
            }
        } catch (_: Exception) {
            // 不是整体 JSON，尝试逐行
        }

        // 3) 逐行 txt
        trimmed.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.startsWith("{")) {
                try {
                    siteFrom(JSONObject(l), sites.size)?.let { sites.add(it) }
                } catch (_: Exception) {
                    // 跳过坏行
                }
            }
        }
        return sites
    }

    private fun siteFrom(o: JSONObject, index: Int): Site? {
        val key = o.optString("key").ifBlank { "site$index" }
        val name = o.optString("name").ifBlank { key }
        val api = o.optString("api", "")
        val ext = o.optString("ext", "")
        return Site(key = key, name = name, api = api, ext = ext, siteJson = o.toString())
    }
}
