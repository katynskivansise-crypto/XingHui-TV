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
    fun parse(text: String, defaultJar: String = "", subscribeUrl: String = ""): List<Site> {
        val sites = mutableListOf<Site>()
        // 对齐原项目 Decoder.fix(url, data)：订阅 JSON 里的 "./xxx"、"../xxx" 一律按订阅
        // 所在目录补成绝对地址（原版是在 Json.parse 之前对整段文本做替换）。
        // 这一步覆盖的不只是 spider/jar，还包括站点 ext、lives、parses 里的相对地址。
        val trimmed = absolutize(text.trim(), subscribeUrl)
        if (trimmed.isEmpty()) return sites

        // 1) / 2) 整体 JSON
        try {
            val obj = JSONObject(trimmed)
            // 对齐原版 VodConfig.initSite：订阅允许 {"video":{"sites":[...]}} 的嵌套写法
            // （原版 if (object.has("video")) { initSite(object.getAsJsonObject("video")); return; }）
            val holder = obj.optJSONObject("video")?.takeIf { it.has("sites") } ?: obj
            val arr = holder.optJSONArray("sites")
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
        // ext 对齐原版 gson 的 ExtAdapter：字符串原样，对象/数组序列化成 JSON 文本。
        // 之前直接 optString("ext")，遇到对象型 ext（苹果站等加密站）会返回空串，
        // spider.init(ctx, "") 拿不到 appName/publicKey/dataKey 等配置，
        // 表现为首页还能出内容、分类页却缺字段（海报空白）。
        // 订阅实测：157 站中 42 站 ext 是对象。
        val ext = when (val e = o.opt("ext")) {
            is String -> e
            is JSONObject -> e.toString()
            is JSONArray -> e.toString()
            else -> ""
        }
        // 对齐原项目：csp_ 站点自带 jar（订阅内若有 site.jar 优先，否则用订阅顶层 spider）
        val ownJar = o.optString("jar", "")
        val jar = ownJar.ifBlank { if (api.startsWith("csp_")) defaultJar else "" }
        // 卡片形态（订阅里 51/157 站带 style，如体育站 type=list、影视站 ratio=1.33）
        val (stType, stRatio) = Site.styleOf(o)
        return Site(
            key = key,
            name = name,
            api = api,
            ext = ext,
            siteJson = o.toString(),
            jar = jar,
            styleType = stType,
            styleRatio = stRatio,
            headerJson = Site.normalizeHeader(o),
        )
    }

    /**
     * 解析订阅 JSON 顶层的 spider 字段（csp_ 源需要）。
     * 返回形如 ./jar/spider.jar;md5;c6c7 的原文；无则返回空串。
     */
    /**
     * 把 JSON 字符串值里以 "./" / "../" 开头的地址补成绝对地址。
     * 只匹配 "key": "./xxx" 这种"值以相对路径开头"的位置，比原版的全局 replace 更保守，
     * 不会误伤普通文本里的 "./"。先处理 "../" 再处理 "./"，否则 "../" 会被拆坏。
     */
    private fun absolutize(text: String, subscribeUrl: String): String {
        if (text.isEmpty() || !subscribeUrl.contains("://")) return text
        val dir = subscribeUrl.substringBeforeLast('/', missingDelimiterValue = subscribeUrl) + "/"
        val parent = dir.trimEnd('/').substringBeforeLast('/', dir) + "/"
        return text
            .replace(Regex("(:\\s*\")\\.\\./")) { m -> m.groupValues[1] + parent }
            .replace(Regex("(:\\s*\")\\./")) { m -> m.groupValues[1] + dir }
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
