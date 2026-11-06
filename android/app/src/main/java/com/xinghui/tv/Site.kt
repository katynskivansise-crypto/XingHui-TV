package com.xinghui.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * 订阅站点（订阅 JSON 里解析出的一个站点）。
 * - key   : 站点唯一标识（传给 Rust 引擎 init 的 key）
 * - name  : 站点显示名
 * - api   : 站点接口地址（drpy 源一般是 js 脚本；JSON 源是 http 接口）
 * - ext   : drpy 规则脚本地址 / csp 站配置 JSON；对象型 ext 已序列化成文本
 * - siteJson : 整个站点 JSON 原文（JSON 规则源用；drpy 源为空）
 * - jar   : csp_ 源使用的 spider.jar 下载地址（对齐原项目每个站点自带 jar）
 * - styleType / styleRatio : 卡片形态，对齐原版 bean/Style
 * - headerJson : 站点级请求头（UA/Referer/Cookie），对齐原版 Site.header
 */
data class Site(
    val key: String,
    val name: String,
    val api: String = "",
    val ext: String = "",
    val siteJson: String = "",
    val jar: String = "",
    /** rect（海报）/ list（横条）/ oval（圆形） */
    val styleType: String = "",
    /** 卡片宽高比（宽:高的倒数关系见 Style.ratio），<=0 视为未指定 */
    val styleRatio: Float = 0f,
    /** 站点级请求头 JSON 文本（对象型 header 已序列化） */
    val headerJson: String = "",
    /**
     * 站点类型，对齐原版 Site.type：
     * 0=XML 采集（ac=videolist）、1=JSON 采集（ac=detail，筛选取值走 f 参数）、
     * 3=csp（spider.jar）、4=特殊 js。默认 0。
     */
    val siteType: Int = 0,
    /** 是否可被搜索，对齐原版 Site.searchable：0=不可搜索，1=可搜索，2=用户关闭 */
    val searchable: Int = 1,
    /** 是否允许切换站点，对齐原版 Site.changeable */
    val changeable: Int = 1,
    /** 分类白名单 JSON 数组文本，对齐原版 Site.categories（为空表示不限制） */
    val categoriesJson: String = "",
) {
    /** csp_ 源（CatVod 内置 Java 爬虫类，走 spider.jar 壳侧执行，对齐原项目 JarLoader） */
    fun isCsp(): Boolean = api.startsWith("csp_")

    /** JS 规则源（对齐原项目 BaseLoader：api 含 .js 即 js 源） */
    fun isDrpy(): Boolean = !isCsp() && api.contains(".js")

    /** 该 csp 站可用的 jar（站点自带优先，全局兜底） */
    fun jarOr(fallback: String): String = jar.ifBlank { fallback }

    /** 是否列表形态（原版 ViewType.LIST：整行一条，不是网格） */
    fun isListStyle(): Boolean = styleType.equals("list", true)

    /**
     * 有效宽高比，对齐原版 Style.getRatio()：
     * 未指定时 rect 用 0.75（竖版海报）、oval 用 1.0，上限 4。
     */
    fun ratioOr(default: Float = 0.75f): Float {
        val r = if (styleRatio > 0f) styleRatio else default
        return r.coerceIn(0.1f, 4f)
    }

    /** 站点级请求头 Map（对齐原版 Site.getHeaders()） */
    fun headerMap(): Map<String, String> {
        if (headerJson.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(headerJson)
            val map = HashMap<String, String>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = o.optString(k)
                if (v.isNotBlank()) map[k] = v
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 是否可被搜索（对齐原版 Site.isSearchable：仅 searchable == 1 参与） */
    fun isSearchable(): Boolean = searchable == 1

    /** 分类白名单（对齐原版 Site.getCategories；为空表示不限制） */
    fun categories(): List<String> {
        if (categoriesJson.isBlank()) return emptyList()
        return try {
            val a = JSONArray(categoriesJson)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 按白名单过滤分类 tab，对齐原版 HomeActivity:185：
     *   for (String cate : site.getCategories())
     *       for (Class item : result.getTypes())
     *           if (Trans.s2t(cate).equals(item.getTypeName())) items.add(item);
     * 白名单为空时原样返回；否则只保留 name 命中的分类（保持原顺序与原版一致）。
     */
    fun filterCategories(tabs: List<Pair<String, String>>): List<Pair<String, String>> {
        val allow = categories()
        if (allow.isEmpty()) return tabs
        return tabs.filter { p -> allow.any { it.equals(p.second, true) } }
    }

    fun toJson(): String = JSONObject().apply {
        put("key", key)
        put("name", name)
        put("api", api)
        put("ext", ext)
        put("site_json", siteJson)
        put("jar", jar)
        // 持久化新字段：SubscriptionStore 用 toJson/fromJson 往返，
        // 不写进来重启后卡片形态与站点 header 会丢。
        if (styleType.isNotBlank()) put("style_type", styleType)
        if (styleRatio > 0f) put("style_ratio", styleRatio.toDouble())
        if (headerJson.isNotBlank()) put("header", headerJson)
        // 站点行为字段同样必须持久化：只存在内存里的话，杀掉 App 重进
        // 分类白名单和"是否可搜索"就会回默认值（表现为重启后搜索多出一堆站）。
        put("type", siteType)
        put("searchable", searchable)
        put("changeable", changeable)
        if (categoriesJson.isNotBlank()) put("categories", categoriesJson)
    }.toString()

    companion object {
        /**
         * ext 归一化：对齐原版 gson 的 ExtAdapter。
         * 字符串原样返回；对象/数组序列化成 JSON 文本；其余空串。
         */
        fun normalizeExt(o: JSONObject): String = when (val e = o.opt("ext")) {
            is String -> e
            is JSONObject -> e.toString()
            is JSONArray -> e.toString()
            else -> ""
        }

        /** 头部/header 归一化：同 ExtAdapter 语义（字符串原样、对象/数组序列化） */
        fun normalizeHeader(o: JSONObject): String = when (val h = o.opt("header")) {
            is String -> h
            is JSONObject -> h.toString()
            is JSONArray -> h.toString()
            else -> ""
        }

        fun fromJson(s: String): Site? = try {
            val o = JSONObject(s)
            val rawSite = o.optString("site_json", "")
            // 老数据兼容：早期存档没有 style_type / header，从 site_json 原文复算一次
            val inner = runCatching { JSONObject(rawSite) }.getOrNull()
            val hasStyle = o.has("style_type")
            val (stType, stRatio) = if (hasStyle) {
                o.optString("style_type") to o.optDouble("style_ratio", 0.0).toFloat()
            } else {
                inner?.let { styleOf(it) } ?: ("" to 0f)
            }
            val header = if (o.has("header")) normalizeHeader(o)
            else inner?.let { normalizeHeader(it) } ?: ""
            Site(
                key = o.optString("key"),
                name = o.optString("name", o.optString("key")),
                api = o.optString("api", ""),
                ext = normalizeExt(o),
                siteJson = rawSite,
                jar = o.optString("jar", ""),
                styleType = stType,
                styleRatio = stRatio,
                headerJson = header,
                siteType = if (o.has("type")) o.optInt("type", 0) else inner?.optInt("type", 0) ?: 0,
                searchable = if (o.has("searchable")) o.optInt("searchable", 1)
                else inner?.optInt("searchable", 1) ?: 1,
                changeable = if (o.has("changeable")) o.optInt("changeable", 1)
                else inner?.optInt("changeable", 1) ?: 1,
                categoriesJson = if (o.has("categories")) o.optString("categories", "")
                else inner?.let { categoriesOf(it) } ?: "",
            )
        } catch (_: Exception) {
            null
        }

        /** 解析订阅条目里的 style 对象（可能不存在） */
        fun styleOf(o: JSONObject): Pair<String, Float> {
            val st = o.optJSONObject("style") ?: return "" to 0f
            val type = st.optString("type", "").ifBlank { "rect" }
            val ratio = st.optDouble("ratio", 0.0).toFloat()
            return type to ratio
        }

        /** 分类白名单序列化（对齐 Site.categories：字符串数组） */
        fun categoriesOf(o: JSONObject): String {
            val a = o.optJSONArray("categories") ?: return ""
            return a.toString()
        }
    }
}
