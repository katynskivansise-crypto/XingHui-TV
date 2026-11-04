package com.xinghui.tv

import org.json.JSONObject

/**
 * 订阅站点（订阅 JSON 里解析出的一个站点）。
 * - key   : 站点唯一标识（传给 Rust 引擎 init 的 key）
 * - name  : 站点显示名
 * - api   : 站点接口地址（drpy 源一般是 c.mjsp.cc 之类，JSON 源是 http 接口）
 * - ext   : drpy 规则脚本 URL（http 开头）；JSON 规则源为空
 * - siteJson : 整个站点 JSON 原文（JSON 规则源用；drpy 源为空）
 */
data class Site(
    val key: String,
    val name: String,
    val api: String = "",
    val ext: String = "",
    val siteJson: String = "",
) {
    /** csp_ 源（CatVod 内置 Java 爬虫类，走 spider.jar 壳侧执行，对齐原项目 JarLoader） */
    fun isCsp(): Boolean = api.startsWith("csp_")

    fun toJson(): String = JSONObject().apply {
        put("key", key)
        put("name", name)
        put("api", api)
        put("ext", ext)
        put("site_json", siteJson)
    }.toString()

    companion object {
        fun fromJson(s: String): Site? = try {
            val o = JSONObject(s)
            Site(
                key = o.optString("key"),
                name = o.optString("name", o.optString("key")),
                api = o.optString("api", ""),
                ext = o.optString("ext", ""),
                siteJson = o.optString("site_json", ""),
            )
        } catch (_: Exception) {
            null
        }
    }
}
