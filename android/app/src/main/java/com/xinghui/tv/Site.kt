package com.xinghui.tv

import org.json.JSONObject

/**
 * 订阅站点（订阅 JSON 里解析出的一个站点）。
 * - key   : 站点唯一标识（传给 Rust 引擎 init 的 key）
 * - name  : 站点显示名
 * - api   : 站点接口地址（drpy 源一般是 js 脚本；JSON 源是 http 接口）
 * - ext   : drpy 规则脚本地址；JSON 规则源为空
 * - siteJson : 整个站点 JSON 原文（JSON 规则源用；drpy 源为空）
 * - jar   : csp_ 源使用的 spider.jar 下载地址（对齐原项目每个站点自带 jar）
 */
data class Site(
    val key: String,
    val name: String,
    val api: String = "",
    val ext: String = "",
    val siteJson: String = "",
    val jar: String = "",
) {
    /** csp_ 源（CatVod 内置 Java 爬虫类，走 spider.jar 壳侧执行，对齐原项目 JarLoader） */
    fun isCsp(): Boolean = api.startsWith("csp_")

    /** JS 规则源（对齐原项目 BaseLoader：api 含 .js 即 js 源） */
    fun isDrpy(): Boolean = !isCsp() && api.contains(".js")

    /** 该 csp 站可用的 jar（站点自带优先，全局兜底） */
    fun jarOr(fallback: String): String = jar.ifBlank { fallback }

    fun toJson(): String = JSONObject().apply {
        put("key", key)
        put("name", name)
        put("api", api)
        put("ext", ext)
        put("site_json", siteJson)
        put("jar", jar)
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
                jar = o.optString("jar", ""),
            )
        } catch (_: Exception) {
            null
        }
    }
}
