package com.xinghui.tv

import org.json.JSONArray
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
    /**
     * 解析订阅文本。
     *
     * **解析已下沉到 Rust 引擎**（`cmd=subscription`，见 engine/src/subscription.rs）：
     * 三种订阅形态识别、相对地址补全（`./` `../`）、ext/header 归一化、
     * siteType 推断、门口值（searchable/changeable/style）都在引擎侧完成，
     * 壳这里只做「引擎 JSON → Site 模型」的映射，签名保持不变，调用点零改动。
     *
     * 规则/解析源两个副作用仍在壳侧：它们是壳内的单例，引擎下沉后也没必要搬。
     */
    fun parse(text: String, defaultJar: String = "", subscribeUrl: String = ""): List<Site> {
        val sites = mutableListOf<Site>()
        if (text.trim().isEmpty()) return sites
        val json = runCatching {
            Engine.execute(
                "subscription", "",
                JSONObject().put("text", text).put("jar", defaultJar).put("url", subscribeUrl).toString()
            )
        }.getOrElse { return sites }
        if (json.isBlank()) return sites
        // 同一次引擎结果里就带着 rules/ads/parses，直接喂给两个单例，不再各自重解析
        SnifferRules.loadFromJson(json)
        ParseSources.loadFromJson(json)
        val arr = runCatching { JSONObject(json).optJSONArray("sites") }.getOrNull() ?: return sites
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sites.add(Site.fromEngine(o))
        }
        return sites
    }

    /** 直播主页源（引擎 `subscription` 的 lives，已做相对地址补全）。 */
    /** 订阅里的一条直播主页源（对齐原版 bean/Live 用到的那几个字段） */
    data class Live(
        val name: String,
        val url: String,
        val ua: String,
        /** 原版 Live.epg：XMLTV 节目单地址，选中这条源时自动带上 */
        val epg: String,
    )

    fun lives(text: String, subscribeUrl: String = ""): List<Live> {
        if (text.trim().isEmpty()) return emptyList()
        val json = runCatching {
            Engine.execute(
                "subscription", "",
                JSONObject().put("text", text).put("jar", "").put("url", subscribeUrl).toString()
            )
        }.getOrElse { return emptyList() }
        if (json.isBlank()) return emptyList()
        val arr = runCatching { JSONObject(json).optJSONArray("lives") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            val url = o.optString("url").trim()
            val ua = o.optString("ua").trim()
            val epg = o.optString("epg").trim()
            if (name.isEmpty() || url.isEmpty()) null else Live(name, url, ua, epg)
        }
    }

    fun parseJar(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val obj = JSONObject(trimmed)
            // 顶层优先，其次 video 内（原版递归 initSite 时取的就是所在层级 object 的 spider）
            obj.optString("spider", "").ifBlank { obj.optJSONObject("video")?.optString("spider", "").orEmpty() }
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
        // 订阅里写成 "./jar/spider.jar;md5;356ad46e…"：路径要按订阅目录补成绝对地址，
        // 而 ;md5; 必须**保留**——它是"本地缓存的 jar 是否还需要重下"的唯一依据。
        // 之前这里只取 substringBefore(";") 把 md5 丢了，导致 expectMd5 恒为空、
        // cacheOk 恒 false，每次启动都重下 1.9MB（对齐原版 JarLoader.parseJar 的语义）
        val md5Part = jarRaw.substringAfter(";md5;", "").trim()
        val url = when {
            first.startsWith("http://") || first.startsWith("https://") -> first
            first.startsWith("./") && subscribeUrl.contains("://") ->
                subscribeUrl.substringBeforeLast('/', missingDelimiterValue = subscribeUrl) + "/" + first.removePrefix("./")
            // 相对路径但拿不到订阅 base：无法拼成可下载地址，返回空让上层走兜底，
            // 避免拼出 "./jar/spider.jar" 这种必然下载失败的地址（csp 站全空的主要成因之一）
            else -> ""
        }
        if (url.isBlank()) return ""
        return if (md5Part.isBlank()) url else "$url;md5;$md5Part"
    }
}
