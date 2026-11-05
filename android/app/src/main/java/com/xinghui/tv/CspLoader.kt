package com.xinghui.tv

import android.content.Context
import android.util.Base64
import android.util.Log
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * csp_ 源执行器（对齐原项目 api/loader/JarLoader.java）。
 *
 * 与原版一致的关键语义：
 * 1) 多 jar：loaders / proxyMethods 以 **md5(完整 jar 字符串)** 为 key，不同站点可用不同 jar；
 * 2) md5 校验：jar 形如 "url;md5;xxxx"，本地缓存文件 md5 与声明一致才复用，否则重新下载；
 * 3) 爬虫实例：key = md5(jar) + site.key（原版 spKey = jaKey + key），Gz360 等 init 含鉴权网络调用必须复用；
 * 4) recent jar：最近访问站点的 jar，供 /proxy 播放代理取对应 Proxy.proxy(Map)。
 *
 * 必须在非主线程调用（方法内部自带网络与阻塞反射）。
 */
object CspLoader {

    private const val TAG = "XingHuiTV"

    private val loaders = HashMap<String, DexClassLoader>()
    private val proxyMethods = HashMap<String, java.lang.reflect.Method>()
    private val spiders = HashMap<String, Any>()
    private val lock = Any()

    /** 全局默认 jar（订阅顶层 spider 字段）；站点未自带 jar 时用它 */
    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var defaultJar: String = ""

    /** 最近一次访问的站点所用 jar 的 md5，/proxy 分发按它取 Proxy 方法 */
    @Volatile
    private var recentJarKey: String = ""

    /** 是否可用（至少一个 jar 已就绪） */
    fun ready(): Boolean = synchronized(lock) { loaders.isNotEmpty() }

    // ---------- 基础工具 ----------

    private fun md5Of(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun md5OfFile(file: File): String = try {
        val bytes = MessageDigest.getInstance("MD5").digest(file.readBytes())
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        sb.toString()
    } catch (e: Exception) {
        Log.e(TAG, "[csp.prepare] 计算文件 md5 失败 ${file.name}", e)
        ""
    }

    /** 拆 "url;md5;hash" → (url, hash)；无 md5 段时 hash 为空 */
    private fun splitJar(jar: String): Pair<String, String> {
        val idx = jar.indexOf(";md5;")
        return if (idx >= 0) {
            jar.substring(0, idx).trim() to jar.substring(idx + 5).trim()
        } else {
            jar.trim() to ""
        }
    }

    /** 站点使用的 jar：站点自带优先，全局兜底（对齐原版 site.jar 站点级字段） */
    private fun jarOf(site: Site): String = site.jarOr(defaultJar)

    // ---------- jar 加载（对齐原版 parseJar / load / invokeInit / putProxy） ----------

    private fun loadJar(context: Context, jar: String): DexClassLoader? {
        if (jar.isBlank()) return null
        val key = md5Of(jar) // 原版 parseJar(Util.md5(jar), jar)
        loaders[key]?.let { return it }
        synchronized(lock) {
            loaders[key]?.let { return it }
            val (url, expectMd5) = splitJar(jar)
            if (url.isBlank()) return null
            // 原版 Path.jar(url)：本地文件按 jar 地址区分，避免多 jar 互相覆盖
            val file = File(context.filesDir, "spider_" + md5Of(url) + ".jar")
            val cacheOk = expectMd5.isNotEmpty() && file.exists() && file.length() > 1024 &&
                md5OfFile(file) == expectMd5
            if (!cacheOk) {
                if (file.exists()) Log.e(TAG, "[csp.prepare] 缓存 md5 不符或缺失，重新下载 url=$url")
                runCatching { JsonFetcher.download(url, file) }.onFailure { e ->
                    Log.e(TAG, "[csp.prepare] 下载失败 url=$url", e)
                }
            }
            if (!file.exists() || file.length() < 1024) {
                Log.e(TAG, "[csp.prepare] jar 不可用 url=$url exists=${file.exists()} len=${file.length()}")
                return null
            }
            if (expectMd5.isNotEmpty()) {
                val real = md5OfFile(file)
                if (real != expectMd5) {
                    Log.e(TAG, "[csp.prepare] md5 校验不一致 期望=$expectMd5 实际=$real（仍尝试加载）")
                }
            }
            val dl = DexClassLoader(
                file.absolutePath,
                context.filesDir.absolutePath,
                null,
                context.classLoader
            )
            // 原版 invokeInit：com.github.catvod.spider.Init.init(Context)
            runCatching {
                val initClz = dl.loadClass("com.github.catvod.spider.Init")
                initClz.getMethod("init", Context::class.java).invoke(initClz, context)
            }.onFailure { e -> Log.e(TAG, "[csp.prepare] Init.init 失败", e) }
            // 原版 putProxy：Proxy.proxy(Map) 按 jar 存 Method
            runCatching {
                val proxyClz = dl.loadClass("com.github.catvod.spider.Proxy")
                proxyMethods[key] = proxyClz.getMethod("proxy", Map::class.java)
                Log.e(TAG, "[csp.prepare] Proxy.proxy 已注册 key=${key.take(8)}")
            }.onFailure { e ->
                Log.e(TAG, "[csp.prepare] 未找到 Proxy.proxy（该 jar 播放代理不可用）", e)
            }
            loaders[key] = dl
            Log.e(TAG, "[csp.prepare] jar 已加载 ${file.name} len=${file.length()} key=${key.take(8)}")
            return dl
        }
    }

    /**
     * 准备全局默认 jar（幂等，按 md5 缓存）。
     * 站点自带 jar 时由 spider() 按需懒加载，无需逐个调本方法。
     */
    fun prepare(context: Context, jarUrl: String) {
        appCtx = context.applicationContext
        if (jarUrl.isBlank()) return
        defaultJar = jarUrl
        loadJar(context, jarUrl)
    }

    // ---------- 代理 ----------

    /**
     * 本地代理分发（对齐原版 JarLoader.proxyInvoke）：
     * params（query + headers）→ Object[]{status, contentType, InputStream, headers}
     * 或单个 NanoHTTPD.Response（原版 Proxy.doResponse 直接返回该对象）。
     */
    fun proxyLocal(params: Map<String, String>): Array<Any?>? = when (params["do"]) {
        // 对齐原 BaseLoader.proxyLocal：do=js → jsLoader，do=py → pyLoader，其余 → jarLoader
        "js" -> jsProxyLocal(params)
        "py" -> {
            Log.e(TAG, "[csp.proxy] do=py 依赖第三方 Pyramid AAR，本项目未接入，跳过")
            null
        }
        else -> jarProxyLocal(params)
    }

    /** do=js：调用 drpy 规则的 proxy(params)（对齐原 JsLoader.proxyInvoke → Spider.proxy1）。 */
    private fun jsProxyLocal(params: Map<String, String>): Array<Any?>? {
        val ctx = appCtx
        if (ctx == null) {
            Log.e(TAG, "[csp.proxy] do=js 未持有 Context，跳过")
            return null
        }
        // 对齐原版 JsLoader.proxyInvoke：params 带 siteKey 时按该站取，否则用"最近站点"（currentKey）
        val key = params["siteKey"]?.takeIf { it.isNotBlank() }
            ?: SubscriptionStore(ctx).currentKey()
            ?: ""
        if (key.isBlank()) {
            Log.e(TAG, "[csp.proxy] do=js 无站点 key，跳过")
            return null
        }
        val out = try {
            Engine.execute("proxy", key, JSONObject(params as Map<*, *>).toString())
        } catch (t: Throwable) {
            // 对齐原版 JsLoader.proxyInvoke 的 catch (Throwable)：so 未加载等 Error 也不能崩
            Log.e(TAG, "[csp.proxy] do=js 引擎调用失败", t)
            return null
        }
        if (out.isBlank()) {
            Log.e(TAG, "[csp.proxy] do=js key=$key 规则未返回结果")
            return null
        }
        return try {
            val o = JSONObject(out)
            val code = o.optInt("code", 200)
            val type = o.optString("type", "text/plain")
            val bytes = Base64.decode(o.optString("b64", ""), Base64.DEFAULT)
            val headers = HashMap<String, String>()
            o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
            Log.e(TAG, "[csp.proxy] do=js key=$key code=$code type=$type len=${bytes.size}")
            // 显式 <Any?>：Array 为不变型，避免 arrayOf 元素类型推断歧义
            arrayOf<Any?>(code, type, ByteArrayInputStream(bytes), headers)
        } catch (t: Throwable) {
            Log.e(TAG, "[csp.proxy] do=js 解析失败", t)
            null
        }
    }

    private fun jarProxyLocal(params: Map<String, String>): Array<Any?>? {
        val method = proxyMethods[recentJarKey] ?: proxyMethods.values.firstOrNull()
        if (method == null) {
            Log.e(TAG, "[csp.proxy] 未注册 Proxy.proxy recent=${recentJarKey.take(8)}")
            return null
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            method.invoke(null, params) as? Array<Any?>
        } catch (t: Throwable) {
            // 对齐原版 JarLoader.proxyInvoke 的 catch (Throwable)
            Log.e(TAG, "[csp.proxy] Proxy.proxy 调用失败", t)
            null
        }
    }

    // ---------- 爬虫实例（对齐原版 getSpider） ----------

    private fun spider(context: Context, site: Site): Any? {
        val jar = jarOf(site)
        val dl = loadJar(context, jar) ?: return null
        val jarKey = md5Of(jar)
        recentJarKey = jarKey
        val spKey = jarKey + site.key // 原版 spKey = md5(jar) + key
        spiders[spKey]?.let { return it }
        return try {
            val clsName = "com.github.catvod.spider." + site.api.removePrefix("csp_")
            val clz = dl.loadClass(clsName)
            val inst = clz.newInstance()
            // 原版 getSpider：newInstance → spider.init(Context, String ext)
            runCatching {
                clz.getMethod("init", Context::class.java, String::class.java)
                    .invoke(inst, context, site.ext)
            }.onFailure { e ->
                Log.e(TAG, "[csp.spider] init 失败 ${site.key} ext=${site.ext.take(100)}", e)
            }
            spiders[spKey] = inst
            inst
        } catch (e: Throwable) {
            // 对齐原版 catch Throwable：NoClassDefFoundError / ExceptionInInitializerError 也要接住
            Log.e(TAG, "[csp.spider] 实例化失败 ${site.key}", e)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invoke(obj: Any, name: String, vararg args: Any?): Any? {
        return try {
            val types = args.map {
                when (it) {
                    is Boolean -> java.lang.Boolean.TYPE
                    is HashMap<*, *> -> java.util.HashMap::class.java
                    is List<*> -> java.util.List::class.java
                    else -> it?.javaClass ?: String::class.java
                }
            }.toTypedArray()
            obj.javaClass.getMethod(name, *types).invoke(obj, *args)
        } catch (e: Exception) {
            Log.e(TAG, "[csp.invoke] $name 失败", e)
            null
        }
    }

    private fun str(r: Any?): String = r?.toString() ?: ""

    // ---------- 五个 Spider 接口方法（返回 TVBox Result JSON 字符串） ----------

    /** 首页：homeContent；list 为空时再取 homeVideoContent 合并（对齐原版 type=3 流程） */
    fun home(context: Context, site: Site): String {
        // 失败不返回空串：带上原因，壳侧可直接显示给用户（便于定位 jar/init/接口问题）
        val s = spider(context, site)
            ?: return JSONObject().put("code", -1)
                .put("msg", "csp 未就绪（jar=${jarOf(site).take(60)}；为空或非 URL 说明 jar 没加载成功）").toString()
        val r1 = str(invoke(s, "homeContent", true))
        val hasList = runCatching {
            JSONObject(r1).optJSONArray("list")?.length() ?: 0
        }.getOrDefault(0) > 0
        if (hasList) return r1
        // list 空 → 尝试 homeVideoContent（Gz360 的更新推荐）
        val r2 = str(invoke(s, "homeVideoContent"))
        if (r2.isBlank()) {
            return if (r1.isBlank())
                JSONObject().put("code", -1).put("msg", "homeContent/homeVideoContent 均返回空（该站点可能不提供首页接口）").toString()
            else r1
        }
        return try {
            val o1 = JSONObject(r1)
            val o2 = JSONObject(r2)
            val list2 = o2.optJSONArray("list")
            if (list2 != null) o1.put("list", list2)
            o1.toString()
        } catch (_: Exception) {
            r1
        }
    }

    /** 分类页 */
    fun category(context: Context, site: Site, tid: String, pg: String): String {
        val s = spider(context, site) ?: return ""
        val extend = HashMap<String, String>()
        return str(invoke(s, "categoryContent", tid, pg, false, extend))
    }

    /** 详情页（ids 为 vod_id） */
    fun detail(context: Context, site: Site, ids: String): String {
        val s = spider(context, site) ?: return ""
        val idList: List<String> = if (ids.isBlank()) emptyList() else listOf(ids)
        return str(invoke(s, "detailContent", idList))
    }

    /** 搜索 */
    fun search(context: Context, site: Site, key: String): String {
        val s = spider(context, site) ?: return ""
        return str(invoke(s, "searchContent", key, false))
    }

    /** 播放信息：返回含 url / header 的 JSON（原版 Result 结构） */
    fun player(context: Context, site: Site, flag: String, id: String): String {
        val s = spider(context, site) ?: return ""
        return str(invoke(s, "playerContent", flag, id, listOf<String>()))
    }

    /** 从 playerContent 结果里取 header 映射（User-Agent / Referer 等），空则返回空 map */
    fun playerHeaders(playJson: String): Map<String, String> {
        if (playJson.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(playJson)
            val h = obj.optJSONObject("header") ?: return emptyMap()
            val map = HashMap<String, String>()
            val it = h.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = h.optString(k)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 聚合搜索：当前订阅里所有 csp_ 站点逐个搜并合并（keepAll 不去重） */
    fun searchAll(context: Context, store: SubscriptionStore, key: String): String {
        val cspSites = store.sites().filter { it.isCsp() }
        if (cspSites.isEmpty()) return "{\"list\":[]}"
        val merged = JSONObject()
        val list = JSONArray()
        for (site in cspSites) {
            val r = search(context, site, key)
            try {
                val arr = JSONObject(r).optJSONArray("list") ?: continue
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    item.put("source", site.key)
                    list.put(item)
                }
            } catch (_: Exception) {
                // 单站失败不影响其它站
            }
        }
        merged.put("list", list)
        return merged.toString()
    }
}
