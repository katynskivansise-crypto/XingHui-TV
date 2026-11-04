package com.xinghui.tv

import android.content.Context
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * csp_ 源执行器（对齐原项目 api/loader/JarLoader.java）。
 *
 * CatVod 生态里 api="csp_Gz360" 表示 spider.jar 内的 Java 爬虫类
 * （com.github.catvod.spider.Gz360），不是 HTTP 接口。Rust 引擎无法执行
 * Java DEX，因此本类在壳侧用 DexClassLoader 加载订阅的 spider.jar，
 * 反射实例化目标类并调用 homeContent / categoryContent / detailContent /
 * searchContent / playerContent，与引擎返回同样的 TVBox Result JSON。
 *
 * 必须在非主线程调用（方法内部自带网络与阻塞反射）。
 */
object CspLoader {

    private const val JAR_FILE = "spider.jar"
    private var loader: DexClassLoader? = null
    private var prepared = false
    // 每站一个爬虫实例（原版 getSpider 缓存；Gz360.init 含鉴权网络调用，必须复用）
    private val spiders = HashMap<String, Any>()

    private val lock = Any()

    /** 是否可用（spider.jar 已就绪） */
    fun ready(): Boolean = loader != null

    /**
     * 下载并加载 spider.jar（幂等，仅首次生效）。
     * jarUrl 为空或下载失败时 loader 保持 null，csp_ 源后续调用返回空。
     */
    fun prepare(context: Context, jarUrl: String) {
        synchronized(lock) {
            if (prepared) return
            prepared = true
            if (jarUrl.isBlank()) return
            try {
                val file = File(context.filesDir, JAR_FILE)
                if (!file.exists() || file.length() < 1024) {
                    JsonFetcher.download(jarUrl, file)
                }
                if (!file.exists() || file.length() < 1024) return
                val dl = DexClassLoader(
                    file.absolutePath,
                    context.filesDir.absolutePath,
                    null,
                    context.classLoader
                )
                loader = dl
                // 原版 invokeInit：com.github.catvod.spider.Init.init(Context)
                runCatching {
                    val initClz = dl.loadClass("com.github.catvod.spider.Init")
                    initClz.getMethod("init", Context::class.java).invoke(initClz, context)
                }
            } catch (_: Exception) {
                loader = null
            }
        }
    }

    /** 获取并缓存 csp 爬虫实例；失败返回 null */
    private fun spider(context: Context, site: Site): Any? {
        val dl = loader ?: return null
        spiders[site.key]?.let { return it }
        return try {
            val clsName = "com.github.catvod.spider." + site.api.removePrefix("csp_")
            val clz = dl.loadClass(clsName)
            val inst = clz.newInstance()
            // 原版 getSpider：spider.init(Context, String ext)
            runCatching {
                clz.getMethod("init", Context::class.java, String::class.java)
                    .invoke(inst, context, site.ext)
            }
            spiders[site.key] = inst
            inst
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            null
        }
    }

    private fun str(r: Any?): String = r?.toString() ?: ""

    // ---------- 五个 Spider 接口方法（返回 TVBox Result JSON 字符串） ----------

    /** 首页：homeContent；list 为空时再取 homeVideoContent 合并（对齐原版 type=3 流程） */
    fun home(context: Context, site: Site): String {
        val s = spider(context, site) ?: return ""
        val r1 = str(invoke(s, "homeContent", true))
        val hasList = runCatching {
            JSONObject(r1).optJSONArray("list")?.length() ?: 0
        }.getOrDefault(0) > 0
        if (hasList) return r1
        // list 空 → 尝试 homeVideoContent（Gz360 的更新推荐）
        val r2 = str(invoke(s, "homeVideoContent"))
        if (r2.isBlank()) return r1
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
