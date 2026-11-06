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

    /**
     * spider.jar 内 `Init.saveConfig()` 的默认配置原文（反汇编逐个键值取出，437 字符 19 键）。
     *
     * 为什么不直接调 `Init.init` 让它自己写：见 loadJar 里的说明——那是个防二次打包校验器，
     * 命中后会起后台线程延迟结束进程，我们的签名 DN恰好命中黑名单。所以只复刻写盘动作。
     */
    private const val DEFAULT_SPIDER_CONFIG =
        """{"version":"2.0","update":"关闭","danmuColor":"默认","danmuSearch":"开启","aliQuality":"阿里原画","quarkQuality":"夸克无限","ucQuality":"UC无限","baiduQuality":"百度无限","123Quality":"123无限","proxyMode":"Go多线程","pansouUrl":"https://so.252035.xyz","panView":"文件夹视图","panOrder":"百度,夸克,UC,迅雷,光鸭,天翼,123,阿里,移动","homePage":"猜你喜欢,热门电影,热播剧集,热门动漫,热播综艺,电影筛选,电视筛选,电影榜单,电视剧榜单","aliThread":"64","quarkThread":"16","ucThread":"自动","baiduThread":"16","xunleiThread":"10"}"""

    /** 最近一次 jar 加载失败的具体原因，透传到首页提示，免去没有 logcat 时盲猜 */
    @Volatile
    private var lastJarError: String = ""

    /**
     * 最近一次 base 地址兜底的完整过程（探活了哪些域名、各自返回什么码、最终写没写进去）。
     * 必须透传到界面：这类"分类页空且一个请求都没发"的故障，只看屏幕上的
     * 「invoke=无异常 | ext=…」是判不出 a 到底是不是 null 的，而没有 logcat 时等于瞎猜。
     */
    @Volatile
    private var lastBaseInfo: String = ""

    /**
     * 当前站的候选 base 域名（来自 ext.site），用于"主域名失败就轮换下一项"。
     * JianPian 这类站的 ext.site 本身就是镜像列表，第一项常年是已注销或限地区的旧站；
     * 主域名返回空/非 JSON 时切到下一项重试一次，往往就能出数据，失败再还原。
     */
    @Volatile
    private var baseCandidates: List<String> = emptyList()

    /**
     * 当前站点的图片域名（带结尾斜杠），供 AsyncImageLoader 把相对海报路径补成完整地址。
     *
     * 为什么需要这一层：csp_AppDrama 这类站的 ext 里 "host" 是空串，真实域名写在
     * ext.site 指向的远程配置里。以苹果·APP 为例，ext.site 是
     *   https://xpgandroid03-1372779881.cos.ap-beijing.myqcloud.com/app_xpg_android.txt
     * 内容是 {"domain":"http://122.228.193.211:18008","enabled":true}（正好 65 字节）。
     * spider 没读到 domain 时 vod_pic 就只剩 load/vod/xxx 这种相对路径，
     * 加载器直接请求会拿到 CDN 的兜底文件 —— 也就是那份 65 字节配置本身，
     * 真机诊断行因此显示「解码失败 len=65」，跟解码器毫无关系。
     *
     * 这里只读站点配置、不碰 spider 的解析逻辑，属于宿主该补的那一层。
     */
    @Volatile var imageBase: String = ""
    @Volatile private var imageBaseSite: String = ""

    /**
     * 最近一次能取到的前台 Activity 类名（空串表示 spider 的 Init.getActivity 取不到）。
     * 配置·中心的卡片全部是 spider 自绘弹窗，没有 Activity 就挂不上 Window，
     * 一弹就是后台线程 NPE —— 这个值直接决定"点了会不会闪退"。
     */
    @Volatile
    var lastActivityName: String = ""
        private set

    /**
     * 最近一次反射调用（homeContent 等）抛出的真实异常。
     * 反射抛的是 InvocationTargetException，真正的 NoSuchMethodError 在 cause 里；
     * 且 Error 不属于 Exception，原来的 catch(Exception) 根本抓不住。
     */
    @Volatile
    private var lastInvokeError: String = ""

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
        // 原版 App.onCreate 里有 Init.set(base)。spider.jar 内的 Path.cache()/Prefers 都靠它拿 Context，
        // 不设置的话 AppDrama 一碰文件或配置就 NoClassDefFoundError/NPE。
        runCatching { com.github.catvod.Init.set(context) }
            .onFailure { Log.e(TAG, "[csp.prepare] Init.set 失败", it) }
        // ★必须先于一切 spider 调用：Config/网盘配置页的 categoryContent 全靠这份 config.json，
        // 缺失则整段 catch 成空，分类页永远 0 条。幂等，命中缓存 jar 也照样保证它在。
        ensureSpiderConfig(context)
        val key = md5Of(jar) // 原版 parseJar(Util.md5(jar), jar)
        loaders[key]?.let { return it }
        synchronized(lock) {
            loaders[key]?.let { return it }
            val (url, expectMd5) = splitJar(jar)
            if (url.isBlank()) return null
            // 原版 Path.jar(url)：本地文件按 jar 地址区分，避免多 jar 互相覆盖
            val file = File(context.filesDir, "spider_" + md5Of(url) + ".jar")
            val fileOk = file.exists() && file.length() > 1024
            // 严格校验：订阅提供了 md5 就必须匹配（对齐原版 JarLoader.parseJar 的 Util.equals）。
            // 实际教训：曾把判定放宽成"文件 >1KB 就复用"，结果一个早期下载失败留下的残缺
            // jar 被永久缓存，DexClassLoader 每次都抛
            //   ClassNotFoundException: com.github.catvod.spider.AppDrama
            // 而同一个 URL 在电脑上完整下载 4.97MB 的 classes.dex（dex 版本 035、4014 个类、
            // AppDrama 确实定义）是完全可加载的 —— 所以问题从来不在 dex，而在这个文件。
            val md5Ok = expectMd5.isEmpty() || (fileOk && md5OfFile(file) == expectMd5)
            val cacheOk = fileOk && md5Ok
            if (!cacheOk && file.exists()) {
                Log.e(
                    TAG,
                    "[csp.prepare] 旧缓存不可用，删除重下：len=${file.length()} " +
                        "期望md5=$expectMd5 实际=${runCatching { md5OfFile(file) }.getOrDefault("?")}"
                )
                file.delete()
            }
            if (!cacheOk) {
                var downloaded = false
                for (attempt in 0..1) {
                    val ok = runCatching {
                        JsonFetcher.download(url, file, if (attempt == 0) 30000 else 45000)
                    }.getOrElse { e ->
                        lastJarError = "下载异常：${e.message}"
                        Log.e(TAG, "[csp.prepare] 第${attempt + 1}次下载失败 url=$url", e)
                        false
                    }
                    if (ok && file.exists() && file.length() > 1024) {
                        downloaded = true
                        break
                    }
                    runCatching { file.delete() }
                }
                if (!downloaded) {
                    lastJarError = lastJarError.ifBlank { "jar 下载失败（网络不可达或超时）" }
                    Log.e(TAG, "[csp.prepare] jar 不可用 url=$url")
                    return null
                }
                Log.e(TAG, "[csp.prepare] jar 下载完成 len=${file.length()}")
                // 下载返回 true 也可能被连接中断截断（HTTP 200 + 提前 EOF），必须再校验一次
                if (expectMd5.isNotEmpty()) {
                    val real = md5OfFile(file)
                    if (real != expectMd5) {
                        lastJarError = "jar 下载不完整（md5 不符）"
                        Log.e(TAG, "[csp.prepare] 下载后 md5 校验失败 期望=$expectMd5 实际=$real，删除重试")
                        file.delete()
                        return null
                    }
                }
            } else {
                Log.e(TAG, "[csp.prepare] 缓存命中且校验通过 len=${file.length()}")
            }
            val dl = DexClassLoader(
                file.absolutePath,
                context.filesDir.absolutePath,
                null,
                context.classLoader
            )
            // ★关键补丁：spider.Init.init 是防二次打包校验器（包名/App名/签名DN黑名单），
            // 内部还会调 startFloatBall（需悬浮窗权限）并起 4 个后台线程。任一步抛异常，
            // 整个 init 就被 catch 截断，context 没能存进静态单例 → Init.context() 返回 null →
            // AppDrama.d 构造设备指纹时 getContentResolver() NPE → 整个 init 中断 →
            // 第二个请求（/api/v5/find/app/zone）永远发不出去 → homeContent 返回空。
            // 反汇编确认 context 写在实例字段 c（Init.init 里两处 iput-object Init.c）。
            // 在 init 前后各强制写一次：先写保证 init 内部崩了也已就绪，后写防止被 init 覆盖。
            // 原版传的是 App.get()（Application），不是 Activity。
            // jar 内 Init.init 有 startFloatBall 分支：context 是 Activity 时会尝试弹悬浮球，
            // 没有 SYSTEM_ALERT_WINDOW 权限就抛 BadTokenException，被外层 catch 截断，context 写不进去。
            // 传 Application 可避开该分支（AppDrama.d 只用到 getContentResolver，Application 足够）。
            val actx = context.applicationContext ?: context
            val initClz = runCatching { dl.loadClass("com.github.catvod.spider.Init") }.getOrNull()
            fun forceInitContext(tag: String) {
                val clz = initClz ?: return
                // ★最小化写入：只写反汇编确认过的实例字段 c，且只写 Context 本体。
                // 这里曾出现过两件必须避免的危险操作（会直接 native 崩，且无 FATAL 无 signal）：
                //   1) 往字段里塞 WeakReference —— R8 把 Init 的 9 个实例字段声明类型全抹成 Init 自身，
                //      运行时没有 check-cast，读出来当 Context 调方法就会段错误；
                //   2) 遍历所有非静态字段逐个覆盖 —— 会把 host / publicKey 等字段一并写脏，
                //      后续 Init.init 读到类型不符的对象同样会崩。
                // 结论：只写 c；写不进就放弃，宁可 csp 站空白，也不能让进程消失。
                runCatching {
                    val inst = clz.getMethod("get").invoke(null)
                    if (inst == null) {
                        lastJarError = "Init 单例为 null（<clinit> 未完成）"
                        Log.e(TAG, "[csp.prepare] $tag Init.get() 为 null，放弃写入")
                        return@runCatching
                    }
                    val f = runCatching { clz.getDeclaredField("c") }.getOrNull()
                    if (f == null) {
                        lastJarError = "Init 无字段 c"
                        Log.e(TAG, "[csp.prepare] $tag 未找到字段 c")
                        return@runCatching
                    }
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                        Log.e(TAG, "[csp.prepare] $tag 字段 c 是静态字段，跳过")
                        return@runCatching
                    }
                    f.isAccessible = true
                    f.set(inst, actx)
                    val back = runCatching { clz.getMethod("context").invoke(null) as? Context }.getOrNull()
                    Log.e(TAG, "[csp.prepare] $tag 写 Init.c 完成 回读=${back != null}")
                    if (back == null) lastJarError = "Init.context 写 c 后仍为 null"

                    // 配置·中心每张卡片点下去，最终都要一个真 Activity 去挂 Window
                    // （merge/m/G 是一整套自绘弹窗：showAuthDialog / addInputWithHint / createTitleView）。
                    // spider 不信任 Init.c，它自己反射 ActivityThread.mActivities 取前台 Activity；
                    // 这里顺带探一次，取到 null 就说明后面所有弹窗都会在后台线程 NPE —— 与其猜，不如写进界面。
                    val act = runCatching {
                        val m = clz.getMethod("getActivity")
                        if (java.lang.reflect.Modifier.isStatic(m.modifiers)) m.invoke(null) else m.invoke(inst)
                    }.getOrNull()
                    Log.e(TAG, "[csp.prepare] $tag Init.getActivity=${act?.javaClass?.name ?: "null"}")
                    if (act == null) {
                        lastJarError = lastJarError.ifBlank { "Init.getActivity 取不到前台 Activity" }
                    } else {
                        lastActivityName = act.javaClass.name
                    }
                }.onFailure { e ->
                    Log.e(TAG, "[csp.prepare] $tag 写 Init.c 失败", e)
                }
            }
            forceInitContext("预设")
            // ★不再默认执行 spider.Init.init。
            // 反汇编确认：com.github.catvod.spider.Init.init 是 spider.jar 的防二次打包校验器——
            // 校验包名（白名单仅 com.fongmi.android.tv / com.hisen.android.tv / com.layaboxhmhz.gameh）、
            // App 名（白名单仅 影视/影視/OK影视/OK影視/OK影视Pro/OK影視Pro/影视+）、
            // 签名 DN 黑名单（含 CN=Android,OU=Android,O=Android，恰好命中我们的 debug 签名）。
            // 校验本身只打日志，但它会起 4 个后台线程做"网络任务"，命中后延迟执行并直接结束进程——
            // 现象就是 logcat 无 FATAL、无 signal，几秒后 has died，日志在某个站点后突然截断。
            // AppDrama 真正需要的只是 Init.context()，已由 forceInitContext 手动写入实例字段 c，
            // 所以正常路径只写字段、不跑 init；仅当回读仍为 null 时才退回原版调用兜底。
            val ctxReady = runCatching {
                initClz?.getMethod("context")?.invoke(null) as? Context
            }.getOrNull() != null
            if (!ctxReady) {
                Log.e(TAG, "[csp.prepare] Init.context 手写未生效，兜底调用 Init.init（可能触发防二次打包校验）")
                runCatching {
                    initClz?.getMethod("init", Context::class.java)?.invoke(initClz, actx)
                }.onFailure { e ->
                    val real =
                        if (e is java.lang.reflect.InvocationTargetException) (e.targetException ?: e) else e
                    Log.e(TAG, "[csp.prepare] Init.init 兜底调用失败", real)
                }
                forceInitContext("后补")
            } else {
                Log.e(TAG, "[csp.prepare] Init.context 已就绪，跳过 Init.init（避开防二次打包校验）")
            }
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
     * 复刻 spider.jar 内 `Init.saveConfig()` 的产物：`<filesDir>/Pizazz/config.json`。
     *
     * 反汇编确认的调用链（这是「配置·中心 影片 0 条」的真正根因）：
     *   Config.categoryContent(tid) 里每个分支的 Quality/Thread 卡片，以及 tid="other"
     *   分支的 proxyMode / pansouUrl / panView 三张卡片，都是
     *       k.d("/config.json") → k.a(file) → JsonParser.parse(...).getAsJsonObject()
     *           .get("pansouUrl").getAsString()
     *   而 k.d 的路径基址是 `new File(Init.context().getFilesDir(), "Pizazz")`。
     *   文件不存在 → k.a 读空串 → parse("") 抛 → **整个 categoryContent 被 catch 成 ""** →
     *   分类页恒定「影片 0 条」。原版能出 8 格，唯一差别就是原版 Init.init 里跑过 saveConfig。
     *
     * 注意不能“文件存在就不管”：旧版本可能只写了部分键，缺键照样 NPE。所以按**补键**处理，
     * 已有值（用户自己改过的）一律保留，只补缺失项。
     */
    private fun ensureSpiderConfig(context: Context) {
        runCatching {
            val dir = File(context.filesDir, "Pizazz")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "[csp.config] 创建目录失败 ${dir.absolutePath}")
                return@runCatching
            }
            val f = File(dir, "config.json")
            val tpl = JSONObject(DEFAULT_SPIDER_CONFIG)
            if (f.exists() && f.length() > 2) {
                val cur = runCatching { JSONObject(f.readText()) }.getOrNull()
                if (cur == null) {
                    // 文件损坏（半截 JSON 之类）：备份后整份重写，避免永久卡在空分类
                    val bak = File(dir, "config.json.bak")
                    runCatching { f.copyTo(bak, overwrite = true) }
                    f.writeText(DEFAULT_SPIDER_CONFIG, Charsets.UTF_8)
                    Log.e(TAG, "[csp.config] 原配置无法解析，已备份到 ${bak.name} 并重写默认")
                    return@runCatching
                }
                var added = 0
                tpl.keys().forEach { key ->
                    if (!cur.has(key)) {
                        cur.put(key, tpl.get(key))
                        added++
                    }
                }
                if (added > 0) {
                    f.writeText(cur.toString(), Charsets.UTF_8)
                    Log.e(TAG, "[csp.config] 已有配置补键 $added 个 → ${f.absolutePath}")
                } else {
                    Log.e(TAG, "[csp.config] 配置完整（${cur.length()} 键），保留不动")
                }
            } else {
                f.writeText(DEFAULT_SPIDER_CONFIG, Charsets.UTF_8)
                Log.e(TAG, "[csp.config] 已写入默认配置 ${f.absolutePath} len=${f.length()} 键=${tpl.length()}")
            }
        }.onFailure { e ->
            Log.e(TAG, "[csp.config] 写入默认配置失败", e)
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
        val dl = loadJar(context, jar) ?: run {
            if (lastJarError.isBlank()) lastJarError = "jar 未加载（${jar.take(60)}）"
            return null
        }
        val jarKey = md5Of(jar)
        recentJarKey = jarKey
        val spKey = jarKey + site.key // 原版 spKey = md5(jar) + key
        spiders[spKey]?.let { cached ->
            // ★缓存命中也要再兜一次。原因：首次 init 时若 ext.site 第一项是已注销域名
            // （荐片就是 www.whhyn.com，NXDOMAIN），spider 内部的探活循环会被外层 catch
            // 整体中断，第二项 https://www.zxfmj.com 一次都没被试过 → 实例字段 a 留 null。
            // 而实例一旦进 spiders 缓存，就会带着 null 的 a 活到进程结束，
            // 分类页因此"永久 0 条且不发请求"。ensureBaseUrl 是幂等的（a 非空立即 return），
            // 放在这里每次只多一次字段读取，却能把这种"缓存了残缺实例"救回来。
            ensureBaseUrl(site, cached)
            return cached
        }
        return try {
            val clsName = "com.github.catvod.spider." + site.api.removePrefix("csp_")
            val clz = dl.loadClass(clsName)
            val inst = clz.newInstance()
            // 原版 getSpider：newInstance → spider.init(Context, String ext)
            runCatching {
                clz.getMethod("init", Context::class.java, String::class.java)
                    .invoke(inst, context.applicationContext ?: context, site.ext)
            }.onFailure { e ->
                val real: Throwable =
                    if (e is java.lang.reflect.InvocationTargetException) (e.targetException ?: e.cause ?: e) else e
                // init 失败时 spider 内部状态不完整，后续所有接口都会返回空，
                // 这里必须留下痕迹，否则会被误判成"站点不提供首页接口"
                lastJarError = "init 失败：${real.javaClass.simpleName}: ${real.message}"
                lastInvokeError = "init → ${real.javaClass.simpleName}: ${real.message}"
                Log.e(TAG, "[csp.spider] init 失败 ${site.key} ext=${site.ext.take(100)}", real)
            }
            spiders[spKey] = inst
            // init 之后 spider 内部会留下若干 String 字段（如 JianPian 的 a/b），
            // 它们承载 base 地址与分类父级 id。这些字段一旦为 null，
            // categoryContent 里 String.replace(占位符, null) 会直接抛 NPE 并被 spider 自己吞掉，
            // 表现就是"分类页永远空、而且根本没发出请求"。这里把真实值打出来。
            dumpStringFields(site, inst)
            ensureBaseUrl(site, inst)
            resolveImageBase(site, inst)
            inst
        } catch (e: Throwable) {
            // 对齐原版 catch Throwable：NoClassDefFoundError / ExceptionInInitializerError 也要接住
            lastJarError = "类加载失败：${clsNameOf(site)} → ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "[csp.spider] 实例化失败 ${site.key}", e)
            null
        }
    }

    /** csp_AppDrama → com.github.catvod.spider.AppDrama，仅用于日志/提示 */
    private fun clsNameOf(site: Site): String =
        "com.github.catvod.spider." + site.api.removePrefix("csp_")

    /**
     * 打印 spider 实例上所有 String 字段的真实值（含父类）。
     * 用途：init 不抛异常不代表初始化成功 —— 它可能把站点地址/父级 id 存进字段，
     * 而某个分支失败时会留下 null，后续接口再拿这个 null 去拼 URL 就静默返回空。
     * 这段只读字段、不写任何东西，避免把 host/publicKey 之类一起写脏。
     */
    private fun dumpStringFields(site: Site, inst: Any) {
        runCatching {
            val sb = StringBuilder()
            var c: Class<*>? = inst.javaClass
            var depth = 0
            while (c != null && depth < 4) {
                for (f in c.declaredFields) {
                    if (f.type != String::class.java) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(inst) as String?
                        sb.append(f.name).append('=').append(v ?: "null").append("  ")
                    } catch (t: Throwable) {
                        sb.append(f.name).append("=读取失败  ")
                    }
                }
                c = c.superclass
                depth++
            }
            Log.e(TAG, "[csp.fields] ${site.key} ${site.api} → ${sb.toString().trim()}")
        }.onFailure { Log.e(TAG, "[csp.fields] 读取失败 ${site.key}", it) }
    }

    /**
     * 补齐 spider 的 base 地址字段（JianPian 的 String a）。
     *
     * 实测语义（新 jar 字节码逐条核对过）：JianPian.init 遍历 ext.site 每一项，
     * 对每项发一次 `Request.Builder().url(u).head()` 的 HEAD 请求，拿到 200 就写 a 并 return。
     * try 的覆盖范围是 43..125，落在循环体内，捕获类型是 Exception —— 所以某一项 DNS 失败
     * 只会让这一轮 continue，不会中断整个循环。（早期注释里"整个循环被 catch 掉"是错的。）
     *
     * 但还有两种情况会让 a 留空：
     *   1) ext.site 里所有域名都不可达 / 全部非 200；
     *   2) 我们的 okhttp 兼容层把 HEAD 落成 code==0（Bridge 抛 IOException，被 init 的 catch 吞掉）。
     * a 一旦为空，categoryContent 的第一条实质指令 `StringBuilder.append(this.a)` 会拼出
     * "null/api/..."，而 merge/k/b.l 的第一条判断是 `if (!url.startsWith("http")) return "";`，
     * 于是**一个请求都不发**、直接返回空串 —— 屏幕上的诊断行因此看不到任何痕迹。
     */
    private fun ensureBaseUrl(site: Site, inst: Any) {
        val sb = StringBuilder()
        runCatching {
            val f = inst.javaClass.getDeclaredField("a")
            f.isAccessible = true
            val cur = f.get(inst) as String?
            if (!cur.isNullOrBlank()) {
                lastBaseInfo = "a=$cur（init 已设置）"
                Log.e(TAG, "[csp.base] ${site.key} a=$cur（init 已设置，无需补齐）")
                return
            }
            val arr = runCatching { JSONObject(site.ext).optJSONArray("site") }.getOrNull()
            if (arr == null) {
                lastBaseInfo = "a=null（ext 无 site 数组：${site.ext.take(40)}）"
                Log.e(TAG, "[csp.base] ${site.key} ext 无 site 数组，跳过")
                return
            }
            sb.append("a=null，逐个探活: ")
            var first: String? = null
            var ok: String? = null
            for (i in 0 until arr.length()) {
                val u = arr.optString(i).trim()
                if (u.isEmpty()) continue
                if (first == null) first = u
                var code = -1
                var em = ""
                runCatching { probeHead(u) }
                    .onSuccess { code = it }
                    .onFailure { code = -1; em = it.message ?: it.javaClass.simpleName }
                sb.append("$u→").append(code)
                if (em.isNotBlank()) sb.append("[").append(em.take(60)).append("]")
                sb.append("; ")
                Log.e(TAG, "[csp.base] ${site.key} 探活 $u → $code ${em.take(80)}")
                if (code in 200..399) {
                    ok = u
                    break
                }
            }
            val candidates = (0 until arr.length())
                .map { arr.optString(it).trim() }
                .filter { it.isNotEmpty() }
            baseCandidates = candidates
            val chosen = ok ?: first
            if (chosen != null) {
                f.set(inst, chosen)
                lastBaseInfo = sb.toString() +
                    if (ok != null) "⇒已写 a=$chosen" else "⇒全部不可达，强制写首项 a=$chosen（保证请求能发出）"
                Log.e(TAG, "[csp.base] ${site.key} 写入 a=$chosen${if (ok == null) "（探活全失败）" else ""}")
            } else {
                lastBaseInfo = sb.toString() + "⇒ext.site 全为空，a 仍 null"
                Log.e(TAG, "[csp.base] ${site.key} ext.site 全为空")
            }
        }.onFailure { e ->
            // 大多数 spider 根本没有 a 字段（只有 JianPian 这类"多域名择一"的站才有），
            // 这种情况不该在界面上显示成"补齐异常"，否则每个站的空分类都会挂一条噪音。
            lastBaseInfo = if (e is NoSuchFieldException) "" else "补齐异常: ${e.message}"
            Log.e(TAG, "[csp.base] 补齐失败 ${site.key}", e)
        }
    }

    /** 读 spider 的 base 字段（JianPian.a）；没有该字段返回 null */
    private fun readBase(inst: Any): String? = runCatching {
        val f = inst.javaClass.getDeclaredField("a").apply { isAccessible = true }
        f.get(inst) as? String
    }.getOrNull()

    /** 写回 spider 的 base 字段；没有该字段静默忽略 */
    private fun setBase(inst: Any, value: String?) {
        runCatching {
            val f = inst.javaClass.getDeclaredField("a").apply { isAccessible = true }
            f.set(inst, value)
        }
    }

    /**
     * 切到候选列表里的下一项，返回新地址；没有下一个候选返回 null。
     * 只用于"主域名已经发出请求但没拿到可用数据"的场景 —— 与 a 为空时补值不同，
     * 这里 a 本身是有效的，只是该镜像站不提供该接口。
     */
    private fun setBaseToNext(inst: Any, current: String?): String? {
        val cands = baseCandidates
        if (cands.size < 2) return null
        val idx = cands.indexOf(current ?: "")
        if (idx < 0 || idx + 1 >= cands.size) return null
        val next = cands[idx + 1]
        if (next == current) return null
        setBase(inst, next)
        return next
    }

    /** HEAD 探活，返回状态码；连不上返回 -1。超时压到 5s，避免启动被死域名拖住。 */
    /**
     * 探活一个 base 地址，返回 HTTP 状态码；任何异常一律 -1（调用方据此跳过）。
     *
     * ★这里刻意用 GET 而不是 HEAD，原因是实测出来的：
     *   `JianPian.init` 自己探活用的是 HEAD。它在循环里对 ext.site 逐项 `execute().code()`，
     *   而订阅里的 site 数组第一项是 https://www.whhyn.com —— **该域名已注销（NXDOMAIN）**，
     *   `execute()` 抛 UnknownHostException，被 spider 自己的外层 catch 吞掉，**整个循环就此中断**，
     *   第二项 https://www.zxfmj.com 一次都没被试过，于是 `a` 永远是 null。
     *   `categoryContent` 第一条实质指令就是 `StringBuilder.append(this.a)` → NPE → 被自己 catch
     *   → 返回 "" → 分类页恒「影片 0 条」，而且**一个请求都不会发出去**。
     *
     *   而 zxfmj 本身是活的：GET /api/crumb/list?fcate_pid=1&... → 200 / 4855 B
     *   {"code":1,"msg":"获取筛选页数据成功","data":[{"id":2023,"title":"地..."}]}
     *   HEAD 根路径也是 200。所以只要绕开 whhyn 那一项、把 a 补成 zxfmj，分类就能出内容。
     *
     *   这里不用 HEAD 的第二个原因：我们的 okhttp3 兼容层走 Rust 引擎，HEAD 的状态码
     *   在部分服务上会丢失，Bridge 里 code==0 直接抛 IOException，探活全部变成 -1，
     *   兜底等于没做。GET 没有这个毛病。
     */
    /**
     * 解析当前站点的图片域名，写进 imageBase。
     * 优先级：ext.host（已是完整 http）→ ext.site 远程配置里的 domain → spider 实例的 String 字段兜底。
     * 幂等：同一站点且已有值就直接返回，只在切换站点时重做一次。
     */
    private fun resolveImageBase(site: Site, inst: Any) {
        if (imageBase.isNotBlank() && imageBaseSite == site.key) return
        runCatching {
            val extObj = runCatching { JSONObject(site.ext) }.getOrNull() ?: return@runCatching
            // 1) ext 里直接给了完整域名
            val host = extObj.optString("host").trim()
            if (host.startsWith("http")) {
                imageBase = host.trimEnd('/') + "/"
                imageBaseSite = site.key
                Log.e(TAG, "[csp.image] ${site.key} ext.host → $imageBase")
                return@runCatching
            }
            // 2) 拉 ext.site 指向的远程配置，读 domain（苹果·APP 走这条）
            val cfgUrl = extObj.optString("site").trim()
            if (cfgUrl.startsWith("http")) {
                val body = fetchGet(cfgUrl)
                if (!body.isNullOrBlank()) {
                    val domain = runCatching { JSONObject(body).optString("domain") }
                        .getOrNull().orEmpty().trim()
                    if (domain.startsWith("http")) {
                        imageBase = domain.trimEnd('/') + "/"
                        imageBaseSite = site.key
                        Log.e(TAG, "[csp.image] ${site.key} site.domain → $imageBase")
                        return@runCatching
                    }
                }
            }
            // 3) spider 实例上的 String 字段兜底（JianPian 的 b = img.cqkgy.com）
            val b = runCatching {
                inst.javaClass.getDeclaredField("b").apply { isAccessible = true }.get(inst) as? String
            }.getOrNull().orEmpty().trim()
            if (b.contains(".") && !b.contains("/")) {
                imageBase = "https://" + b + "/"
                imageBaseSite = site.key
                Log.e(TAG, "[csp.image] ${site.key} 字段 b → $imageBase")
            }
        }.onFailure { Log.e(TAG, "[csp.image] 解析失败 ${site.key}", it) }
    }

    /** 取一小段文本（站点配置只有几十字节，读全文即可） */
    private fun fetchGet(url: String): String? {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(8000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val req = okhttp3.Request.Builder().url(url).method("GET", null).build()
        val resp = client.newCall(req).execute()
        return try {
            if (resp.code() in 200..299) resp.body()?.string() else null
        } finally {
            runCatching { resp.close() }
        }
    }

    private fun probeHead(url: String): Int {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val req = okhttp3.Request.Builder().url(url).method("GET", null).build()
        val resp = client.newCall(req).execute()
        return try {
            resp.code()
        } finally {
            runCatching { resp.close() }
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
        } catch (e: Throwable) {
            // 反射抛出的是 InvocationTargetException，真实异常（NoSuchMethodError 等）在 cause 里；
            // 且 NoSuchMethodError 是 Error，原来的 catch(Exception) 抓不住，会一路逃逸掉
            val real: Throwable =
                if (e is java.lang.reflect.InvocationTargetException) (e.targetException ?: e.cause ?: e) else e
            lastInvokeError = "$name → ${real.javaClass.simpleName}: ${real.message}"
            Log.e(TAG, "[csp.invoke] $name 失败", real)
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
                .put(
                    "msg",
                    "csp 未就绪（${lastJarError.ifBlank { "jar=${jarOf(site).take(60)}，原因未记录" }}）"
                ).toString()
        val r1 = str(invoke(s, "homeContent", true))
        val hasList = runCatching {
            JSONObject(r1).optJSONArray("list")?.length() ?: 0
        }.getOrDefault(0) > 0
        if (hasList) return r1
        // list 空 → 尝试 homeVideoContent（Gz360 的更新推荐）
        val r2 = str(invoke(s, "homeVideoContent"))
        if (r2.isBlank()) {
            return if (r1.isBlank())
                JSONObject().put("code", -1)
                    .put("msg", (if (lastJarError.isNotBlank()) "init=" + lastJarError.take(50) + " | " else "")
                        + "invoke=" + lastInvokeError.ifBlank { "无异常" }
                        + okhttp3.internal.Bridge.diag()
                        + " | r1=" + (if (r1.isBlank()) "空" else r1.take(50))
                        + " | r2=" + (if (r2.isBlank()) "空" else r2.take(30)))
                    .toString()
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
    /**
     * 分类列表。extend 来自首页 Result.filters 的选中值（原版 FilterDialog 的结果），
     * 形如 {"year":"2024","area":"大陆"}，原样交给 spider.categoryContent 的第 4 个参数。
     *
     * 第 3 个参数 filter 必须传 true（对齐原版 VodFragment:184 / TypeFragment:157）。
     * 空返回时带上失败原因，避免壳侧只能显示"接口返回空"——那样分不清是
     * spider 抛异常、init 失败（ext 没配好）还是站点该分类确实没内容。
     */
    fun category(
        context: Context,
        site: Site,
        tid: String,
        pg: String,
        extend: Map<String, String> = emptyMap()
    ): String {
        val s = spider(context, site)
            ?: return JSONObject().put("code", -1)
                .put(
                    "msg",
                    "csp 未就绪（${lastJarError.ifBlank { "jar=${jarOf(site).take(60)}，原因未记录" }}）"
                ).toString()
        lastInvokeError = ""
        var r = str(invoke(s, "categoryContent", tid, pg, true, HashMap(extend)))
        // spider 返回非空但列表为空时，问题在它内部的解析/解密，不在我们的参数；
        // 这条日志把 spider 的真实原文留下来，真机上直接看就知道它给了什么。
        Log.e(TAG, "[csp.category] tid=$tid pg=$pg extend=$extend len=${r.length} 原文=${r.take(300)}")
        // 主域名没拿到数据就轮换镜像：JianPian 这类站的 ext.site 是镜像列表，
        // 第一项常常是已注销或只提供 HTML 的旧站，切到下一项往往能出数据。
        // 轮换后仍为空则还原原值，避免把一个本来还能用的域名替换掉。
        if (r.isBlank()) {
            val aNow = readBase(s)
            val alt = setBaseToNext(s, aNow)
            if (alt != null) {
                lastInvokeError = ""
                val r2 = str(invoke(s, "categoryContent", tid, pg, true, HashMap(extend)))
                Log.e(TAG, "[csp.category] ${site.key} 主域名空，轮换 base=$alt len=${r2.length}")
                if (r2.isNotBlank()) {
                    r = r2
                } else {
                    setBase(s, aNow)
                }
            }
        }
        if (r.isBlank()) {
            // 关键诊断：spider 的 HTTP 入口 merge/k/b.l 只有 32 字码，第一条实质判断是
            //     if (!url.startsWith("http")) return "";
            // 也就是说 URL 只要不以 http 开头，它一个请求都不发，直接吐空串。
            // 而 categoryContent 的 URL 是 StringBuilder.append(this.a) + "/api/..."，
            // a 为 null 时会拼成 "null/api/..."，a 带了路径时会拼成 "https://host/xxx/api/..."，
            // 两种都会让分类页恒 0 条，且在网络层看不到任何请求。
            // 这里把 a 的真实值和拼出来的 URL 一起上屏，截图即可判断是哪种。
            val aVal = runCatching {
                s.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(s) as? String
            }.getOrNull()
            return JSONObject().put("code", -1)
                .put(
                    "msg",
                    "categoryContent 返回空（tid=$tid pg=$pg）"
                        + (if (lastInvokeError.isNotBlank()) " | invoke=$lastInvokeError" else " | invoke=无异常")
                        + " | a=" + (aVal?.let { "\"$it\"" } ?: "null(无 a 字段)")
                        + " | expectUrl=" + ((aVal ?: "null") + "/api/…")
                        + (if (lastJarError.isNotBlank()) " | init=$lastJarError" else "")
                        + (if (lastBaseInfo.isNotBlank()) " | base=[$lastBaseInfo]" else "")
                        + " | act=" + lastActivityName.ifBlank { "null" }
                        + " | ext=" + site.ext.take(80)
                        + okhttp3.internal.Bridge.diag()
                ).toString()
        }
        return r
    }

    /**
     * 执行站点 action（对齐原版 Spider.action / SiteViewModel.action）。
     * 配置类站点（csp_Config 等）的首页返回的不是影片，而是一堆 {"action":"..."} 功能入口，
     * 原版 VodFragment.onItemClick 最先判断 isAction → 调这个方法 → 把结果当 Result 展示。
     */
    fun action(context: Context, site: Site, action: String): String {
        val s = spider(context, site)
            ?: return JSONObject().put("code", -1)
                .put("msg", "csp 未就绪（${lastJarError.ifBlank { "jar=${jarOf(site).take(60)}" }}）").toString()
        lastInvokeError = ""
        val r = str(invoke(s, "action", action))
        if (r.isBlank()) {
            return JSONObject().put("code", -1)
                .put("msg", "action 返回空（action=$action）"
                    + (if (lastInvokeError.isNotBlank()) " | invoke=$lastInvokeError" else " | invoke=无异常")
                    + " | 该站点可能未实现 action 接口")
                .toString()
        }
        return r
    }

    /** 详情页（ids 为 vod_id） */
    fun detail(context: Context, site: Site, ids: String): String {
        val s = spider(context, site)
            ?: return JSONObject().put("code", -1)
                .put("msg", "csp 未就绪（${lastJarError.ifBlank { "jar=${jarOf(site).take(60)}" }}）").toString()
        lastInvokeError = ""
        val idList: List<String> = if (ids.isBlank()) emptyList() else listOf(ids)
        val r = str(invoke(s, "detailContent", idList))
        if (r.isBlank()) {
            return JSONObject().put("code", -1)
                .put("msg", "detailContent 返回空（ids=$ids）"
                    + (if (lastInvokeError.isNotBlank()) " | invoke=$lastInvokeError" else " | invoke=无异常")
                    + okhttp3.internal.Bridge.diag())
                .toString()
        }
        return r
    }

    /** 搜索 */
    /**
     * 搜索。签名对齐原版 Spider.searchContent(String key, boolean quick, String pg)：
     * quick=true 时站点可以只回粗略结果（原版用于聚合搜索的预筛），我们统一用 false 走完整结果；
     * pg 从 1 开始，翻页时递增 —— 之前只传 key/false 两参数，等于把每个源都锁死在第 1 页。
     */
    fun search(context: Context, site: Site, key: String, pg: String = "1"): String {
        val s = spider(context, site)
            ?: return JSONObject().put("code", -1)
                .put("msg", "csp 未就绪（${lastJarError.ifBlank { "jar=${jarOf(site).take(60)}" }}）").toString()
        lastInvokeError = ""
        // 3 参数调用；若该站的 searchContent 只声明了两参数（少数老 jar），
        // 反射会抛 NoSuchMethodException，这里退回两参数版本，避免整站搜索直接失效。
        val r = str(
            runCatching { invoke(s, "searchContent", key, false, pg) }
                .getOrElse {
                    lastInvokeError = ""
                    invoke(s, "searchContent", key, false)
                }
        )
        if (r.isBlank()) {
            return JSONObject().put("code", -1)
                .put("msg", "searchContent 返回空（key=$key pg=$pg）"
                    + (if (lastInvokeError.isNotBlank()) " | invoke=$lastInvokeError" else " | invoke=无异常")
                    + okhttp3.internal.Bridge.diag())
                .toString()
        }
        return r
    }

    /** 播放信息：返回含 url / header 的 JSON（原版 Result 结构） */
    fun player(context: Context, site: Site, flag: String, id: String): String {
        val s = spider(context, site)
            ?: return JSONObject().put("code", -1)
                .put("msg", "csp 未就绪（${lastJarError.ifBlank { "jar=${jarOf(site).take(60)}" }}）").toString()
        lastInvokeError = ""
        val r = str(invoke(s, "playerContent", flag, id, listOf<String>()))
        if (r.isBlank()) {
            return JSONObject().put("code", -1)
                .put("msg", "playerContent 返回空（flag=$flag id=$id）"
                    + (if (lastInvokeError.isNotBlank()) " | invoke=$lastInvokeError" else " | invoke=无异常")
                    + " | ext=" + site.ext.take(60)
                    + okhttp3.internal.Bridge.diag())
                .toString()
        }
        return r
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

    /** 聚合搜索：当前订阅里所有可搜索的 csp_ 站点逐个搜并合并（keepAll 不去重） */
    fun searchAll(context: Context, store: SubscriptionStore, key: String, pg: String = "1"): String {
        // 对齐原版 Site.isSearchable()：订阅里 29/157 站标了 searchable=0
        // （配置中心、各网盘、体育、新片预告等）。这类站没有搜索接口，
        // 参与聚合搜索只会让每个关键词多等一轮网络请求、再返回空列表。
        val cspSites = store.sites().filter { it.isCsp() && it.isSearchable() }
        if (cspSites.isEmpty()) return "{\"list\":[]}"
        val merged = JSONObject()
        val list = JSONArray()
        for (site in cspSites) {
            val r = search(context, site, key, pg)
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
