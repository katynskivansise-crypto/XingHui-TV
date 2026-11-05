package com.xinghui.tv

import org.json.JSONObject

/**
 * 订阅文本解析器。支持三种常见格式：
 * 1. 多站点聚合：sites 数组
 * 2. 单站点配置：单个 JSON 对象
 * 3. 逐行 txt：每行一个 JSON 站点配置
 */
object SubscriptionParser {

    /**
     * @param defaultJar 订阅顶层 spider 解析出的 jar 完整 URL（csp_ 站点自动带上，对齐原项目）
     */
    fun parse(text: String, defaultJar: String = ""): List<Site> {
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
                    siteFrom(s, i, defaultJar)?.let { sites.add(it) }
                }
                return sites
            }
            if (obj.has("key") || obj.has("name")) {
                siteFrom(obj, 0, defaultJar)?.let { sites.add(it) }
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
                    siteFrom(JSONObject(l), sites.size, defaultJar)?.let { sites.add(it) }
                } catch (_: Exception) {
                    // 跳过坏行
                }
            }
        }
        return sites
    }

    private fun siteFrom(o: JSONObject, index: Int, defaultJar: String = ""): Site? {
        val key = o.optString("key").ifBlank { "site$index" }
        val name = o.optString("name").ifBlank { key }
        val api = o.optString("api", "")
        val ext = o.optString("ext", "")
        // 对齐原项目：csp_ 站点自带 jar（订阅内若有 site.jar 优先，否则用订阅顶层 spider）
        val ownJar = o.optString("jar", "")
        val jar = ownJar.ifBlank { if (api.startsWith("csp_")) defaultJar else "" }
        return Site(key = key, name = name, api = api, ext = ext, siteJson = o.toString(), jar = jar)
    }

    /**
     * 解析订阅 JSON 顶层的 spider 字段（csp_ 源需要）。
     * 返回形如 ./jar/spider.jar;md5;c6c7 的原文；无则返回空串。
     */
    fun parseJar(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            JSONObject(trimmed).optString("spider", "")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 把订阅顶层 spider 字段转成可下载的完整 URL。
     * - 相对路径 ./jar/spider.jar 基于订阅 URL 所在目录拼接
     * - http(s) 开头直接使用
     * - 其它形式原样返回（由调用方处理）
     */
    fun jarUrl(jarRaw: String, subscribeUrl: String): String {
        if (jarRaw.isBlank()) return ""
        val first = jarRaw.substringBefore(";").trim()
        if (first.startsWith("http://") || first.startsWith("https://")) return first
        if (first.startsWith("./") && subscribeUrl.contains("://")) {
            val base = subscribeUrl.substringBeforeLast('/', missingDelimiterValue = subscribeUrl) + "/"
            return base + first.removePrefix("./")
        }
        return first
    }
}
