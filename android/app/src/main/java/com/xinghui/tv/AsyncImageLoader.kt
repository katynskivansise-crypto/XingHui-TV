package com.xinghui.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 海报图片异步加载：走引擎海报代理（proxy_image），复用设置的 UA/代理，
 * 比壳侧 HttpURLConnection 直连更稳（统一 ureq 网络层，可带 UA/代理防部分防盗链）。
 *
 * 原版这块用 Glide 4.16（内存+磁盘双层缓存、按控件尺寸降采样、线程池、生命周期管理）。
 * 我们不引第三方图片库，自己补齐同等能力：
 *  1) 内存 LruCache —— 滚动回看不重新解码；
 *  2) 磁盘缓存 filesDir/imgcache/<md5(url)>.img —— 存"原始图片字节"而不是 Bitmap，
 *     每次命中后按当前控件尺寸重新采样，所以不受首次加载尺寸影响，重开 App 也不用重新下载；
 *  3) 固定线程池 —— 第一版每张图 new Thread，一屏 5 张就是 5 个线程；
 *  4) 按控件尺寸降采样 + RGB_565 —— 第一版全尺寸解码，500x750 海报也吃 1.5MB。
 * 另用 view.tag 记录本次请求的 URL，避免 RecyclerView 复用导致"旧图覆盖新图"。
 */
object AsyncImageLoader {

    private val placeHolder = ColorDrawable(0xFF1A1A1A.toInt())

    // ---------------- 诊断计数器 ----------------
    // TV 上抓 logcat 麻烦，海报空白时最要紧的是分清两类完全不同的失败：
    //   ① vodPic 本来就是空的    → 数据侧，spider 没给图，壳侧怎么修都没用
    //   ② vodPic 有值但一张没下来 → 加载侧，UA / 防盗链 / 代理 / 解码
    // 三个计数由 MainActivity 拼进「影片 N 条」那一行，截图就能判断，不用连 adb。
    @Volatile var okCount = 0          // 成功上屏的
    @Volatile var failCount = 0        // 下载 / 解码失败的
    @Volatile var emptyCount = 0       // url 本身就是空的
    @Volatile var lastFailReason = ""  // 首条失败原因（带 url 尾巴），点开分类就能看到

    fun resetDiag() {
        okCount = 0
        failCount = 0
        emptyCount = 0
        lastFailReason = ""
    }

    private fun noteFail(reason: String, url: String) {
        failCount++
        // 只留首条，避免被后续重复失败覆盖成噪音；URL 取尾巴 60 字符，够认出是哪张图
        if (lastFailReason.isEmpty()) lastFailReason = "$reason@${url.takeLast(60)}"
    }

    // 并发要够：一屏 20+ 张海报，4 线程时每张都得等前面排队，
    // 表现为"从左到右慢慢填、大部分长时间空白"。8 线程配合下面的短超时后基本秒出。
    private val pool = Executors.newFixedThreadPool(8)

    /** 内存缓存：取可用堆的 1/8，按 KB 计量 */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private const val DISK_DIR = "imgcache"
    private const val DISK_LIMIT_KB = 100L * 1024        // 上限 100MB
    private val trimToKb = DISK_LIMIT_KB * 8 / 10        // 触发清理后回落到 80%

    /** 每 16 次写入扫一次目录，避免每次写都遍历 */
    private var writeCount = 0

    fun load(view: ImageView, url: String) {
        view.setImageDrawable(placeHolder)
        view.tag = url
        if (url.isBlank()) {
            // 站点压根没给封面。这类"空白"改加载器是白费劲，要回头查 vod_pic 映射
            emptyCount++
            return
        }
        // TVBox 站的封面地址会把 header 内嵌在 URL 尾巴上（对齐原版 ImgUtil.getUrl 的四类后缀）：
        //   https://x.jpg@Referer=https://y/@User-Agent=Mozilla...
        //   不拆出来的话，@Referer=... 会被当成路径的一部分 → 服务器 404 → 海报永远空白。
        //   这是"部分图能显示、大部分空白"的真正原因（能显示的那些恰好没带后缀）。
        // 站点返回相对海报路径时（苹果·APP 就是 load/vod/20240617-1/xxx），
        // 用站点图片域名补成完整地址。不补的话请求会打到 CDN 根，
        // 拿到的是它的兜底文件——实测正好 65 字节，就是站点那份 domain 配置本身，
        // 于是在诊断行里表现为「解码失败 len=65」。
        val absUrl = if (!url.contains("://") && !url.startsWith("data:")) {
            val base = CspLoader.imageBase
            if (base.isNotBlank()) base + url.trimStart('/') else url
        } else url
        val (cleanUrl, extraHeaders) = splitHeaders(absUrl)
        // data: 内嵌 base64 图，无需下载（原版同样在 getUrl 里直接返回）
        if (cleanUrl.startsWith("data:")) {
            val comma = cleanUrl.indexOf(',')
            if (comma > 0) {
                val raw = runCatching { Base64.decode(cleanUrl.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
                val bmp = raw?.let { decodeSampled(it, 0, 0) }
                if (bmp != null) {
                    cache.put(url, bmp)
                    view.setImageBitmap(bmp)
                    okCount++
                }
            }
            return
        }
        // 海报地址可能是 file:// / proxy:// / assets://，统一重写成本地 HTTP 地址再下载；
        // 原版 UrlUtil.convert 也是在这个位置做的（图片走 OkHttp 而非 file loader）
        val realUrl = UrlConvert.convert(cleanUrl).ifBlank { cleanUrl }

        cache.get(url)?.let { bmp ->
            view.setImageBitmap(bmp)
            okCount++
            return
        }

        // 目标解码尺寸：控件已测量就按实际大小，否则按卡片 200dp×250dp 兜底
        val density = view.resources.displayMetrics.density
        val reqW = if (view.width > 0) view.width else (200 * density).toInt()
        val reqH = if (view.height > 0) view.height else (250 * density).toInt()

        val ctx = view.context.applicationContext
        pool.execute {
            try {
                // 1) 先查磁盘
                // 磁盘/内存缓存 key 用原始 url（同一张图不因 scheme 差异重复缓存）
                // 取值顺序：磁盘 → 本地文件/assets 直读 → 网络。
                // 本地资源必须直读：UrlConvert 会把 file://、local://、assets:// 改写成本地 HTTP，
                // 而 ProxyServer 只实现了 /proxy，/file/ 等路径一律 404（原版 Server 才有那套路由）。
                val bytes = readDisk(ctx, url)
                    ?: localBytes(ctx, cleanUrl)?.also { writeDisk(ctx, url, it) }
                    ?: fetch(ctx, realUrl, extraHeaders)?.also { writeDisk(ctx, url, it) }
                if (bytes == null || bytes.isEmpty()) {
                    noteFail("下载为空", url)
                    return@execute
                }
                val bmp = decodeSampled(bytes, reqW, reqH)
                if (bmp == null) {
                    // 磁盘里存的可能是错误页/半截数据（例如 CDN 返回的 HTML），
                    // 不清掉的话这张海报会永久空白——删掉让下次重新下载
                    runCatching { diskFile(ctx, url).delete() }
                    noteFail("解码失败 len=${bytes.size}", url)
                    Log.e("XingHuiTV", "[image] 解码失败，已清除缓存 len=${bytes.size} url=$url")
                    return@execute
                }
                cache.put(url, bmp)
                view.post {
                    // 控件已被复用绑定到别的 url 时，丢弃这次结果
                    if (view.tag == url) {
                        view.setImageBitmap(bmp)
                        okCount++
                    }
                }
            } catch (e: Throwable) {
                // 占位图已设置，静默失败（catch Throwable：解码 OOM 也不该让进程崩）
                noteFail("异常 ${e.javaClass.simpleName}", url)
            }
        }
    }

    /**
     * 本地资源直读。file:// / local:// 走文件系统，assets:// 走 APK assets。
     * 必须直读而不是交给 UrlConvert 改写成 http://127.0.0.1:9978/...：
     * 本地服务只实现了 /proxy，其余路径返回 404（原版是 Server/process 那一套才有 /file/）。
     */
    private fun localBytes(ctx: Context, url: String): ByteArray? = runCatching {
        when {
            url.startsWith("file://") ->
                File(url.removePrefix("file://")).takeIf { it.isFile }?.readBytes()
            url.startsWith("local://") ->
                File(url.removePrefix("local://")).takeIf { it.isFile }?.readBytes()
            url.startsWith("assets://") ->
                ctx.assets.open(url.removePrefix("assets://")).use { it.readBytes() }
            else -> null
        }
    }.onFailure { Log.e("XingHuiTV", "[image] 本地资源读取失败 $url", it) }.getOrNull()

    /**
     * 拆出 TVBox 图片地址尾部的内嵌 header（逐条对齐原版 ImgUtil.getUrl）：
     *   @Headers={"Referer":"https://x/"}  @Cookie=xxx  @Referer=https://x/  @User-Agent=Mozilla...
     * 返回 (干净 URL, header 表)。原版用 `url.split("@")[0]` 截断，这里用正则截掉对应片段，
     * 效果一致但不会把 URL 本身里的 `@`（如用户名密码）误切。
     */
    private fun splitHeaders(raw: String): Pair<String, Map<String, String>> {
        var url = raw.trim()
        val headers = mutableMapOf<String, String>()

        // @Headers={json} —— 值里可能含 "@"（URL），所以吃到下一个" @" 或行尾为止
        Regex("@Headers=(\\{.*?\\})(@|$)").find(url)?.let { m ->
            runCatching {
                val o = JSONObject(m.groupValues[1])
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    headers[k] = o.optString(k)
                }
            }
            url = url.removeRange(m.range).trim()
        }
        // 三个单值后缀，值同样可能是 URL（含 @）
        for (key in arrayOf("Cookie", "Referer", "User-Agent")) {
            Regex("@$key=(.*?)(?:@(?:Cookie|Referer|User-Agent|Headers)=|$)").find(url)?.let { m ->
                headers[key] = m.groupValues[1].trim()
                url = url.removeRange(m.range).trim()
            }
        }
        return url.trimEnd('@') to headers
    }

    /**
     * 取图字节。顺序经过调整：
     *  - 没配代理时先走系统栈直连（等价原版 okhttp 那条路径，实测能出图），
     *    失败再交给引擎——避免引擎侧的长超时把线程池全部占死。
     *  - 代理已从壳里摘掉（路由器统一接管），不再有「先走引擎」的分支。
     * 注意：内嵌 header（@Referer= 等）只有直连能带，proxy_image 不支持 header 参数，
     * 注意：内嵌 header（@Referer= 等）只有直连能带，proxy_image 不支持 header 参数。
     */
    private fun fetch(context: Context, url: String, extraHeaders: Map<String, String>): ByteArray? {
        val s = Settings(context)

        // 代理已从壳里摘掉（路由器侧统一接管流量），所以远端取图只剩「直连」与「引擎」两条路：
        // 一律先直连，直连拿不到再交给引擎兜底（引擎侧有自己的超时与重试）。
        directFetch(s, url, extraHeaders)?.let { return it }

        // 带 Referer 的兜底：引擎 proxy_image 支持 referer 参数（proxy.rs:43），
        // 但之前壳这边只传 url/ua，等于「防盗链的图交到引擎手里也拿不到」。
        // 原版没有这个问题是因为它只有一条路（Glide + OkHttp，header 一次带齐）；
        // 我们拆成「直连 + 引擎兜底」两条路，就得把这个头补到兜底那一路才算对称。
        val params = JSONObject()
            .put("url", url)
            .put("ua", s.ua)
            .apply {
                extraHeaders["Referer"]?.takeIf { it.isNotBlank() }?.let { put("referer", it) }
                // Cookie 与直连那条路保持对称：登录墙的封面直连失败后，兜底也得带会话，
                // 否则引擎一样被 302 到登录页（原版 Glide 共享 OkHttp client 天然带 Cookie）。
                val ck = extraHeaders["Cookie"]?.takeIf { it.isNotBlank() }
                    ?: okhttp3.internal.Bridge.cookieFor(url)
                if (ck.isNotBlank()) put("cookie", ck)
            }
            .toString()
        val b64 = Engine.execute("proxy_image", "", params)
        if (b64.isNotBlank()) {
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
            Log.e("XingHuiTV", "[image] 引擎返回内容解不出来 len=${b64.length} url=$url")
        } else {
            Log.e("XingHuiTV", "[image] 引擎取图返回空 url=$url")
        }

        return null
    }

    /** 系统栈直连（等价原版 Glide→okhttp 直连出图的那条路径）；超时取短，失败快放弃 */
    private fun directFetch(s: Settings, url: String, extraHeaders: Map<String, String> = emptyMap()): ByteArray? = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", extraHeaders["User-Agent"] ?: s.ua.ifBlank { "okhttp/4.12.0" })
        conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
        // 原版从 URL 拆出来的防盗链 header 原样带上（Referer / Cookie 是出图的关键）
        extraHeaders["Referer"]?.let { conn.setRequestProperty("Referer", it) }
        // 会话 Cookie：原版 Glide 通过 OkHttpUrlLoader 共享 OkHttp.client()，spider 会话期间的
        // Cookie 会跟着图片请求一起走；我们直连必须显式补上，否则被判未登录、302 到登录页。
        val ck = extraHeaders["Cookie"]?.takeIf { it.isNotBlank() }
            ?: okhttp3.internal.Bridge.cookieFor(url)
        if (ck.isNotBlank()) conn.setRequestProperty("Cookie", ck)
        extraHeaders.filterKeys { it != "Referer" && it != "Cookie" && it != "User-Agent" }
            .forEach { (k, v) -> runCatching { conn.setRequestProperty(k, v) } }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
        val location = conn.headerFields?.get("Location")?.firstOrNull() ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            // 302 到 login.html 不是"解码失败"，是这个封面本身要求登录态。
            // 把这层语义显式打出来，避免把站点侧的鉴权要求误判成我们的解码 bug。
            val needLogin = location.contains("login", true) || code == 302 || code == 401 || code == 403
            Log.e(
                "XingHuiTV",
                "[image] 直连 HTTP $code${if (needLogin) "（需登录/被拒）" else ""}" +
                    " loc=${location.take(80)} url=$url"
            )
            null
        } else body
    }.onFailure { e ->
        Log.e("XingHuiTV", "[image] 直连失败 ${e.javaClass.simpleName}: ${e.message} url=$url")
    }.getOrNull()

    // ---------------- 磁盘缓存 ----------------

    private fun diskFile(context: Context, url: String): File =
        File(File(context.filesDir, DISK_DIR), md5(url) + ".img")

    private fun md5(text: String): String {
        val d = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun readDisk(context: Context, url: String): ByteArray? = runCatching {
        val f = diskFile(context, url)
        if (f.exists() && f.length() > 0) f.readBytes() else null
    }.getOrNull()

    private fun writeDisk(context: Context, url: String, bytes: ByteArray) {
        runCatching {
            val dir = File(context.filesDir, DISK_DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = diskFile(context, url)
            f.writeBytes(bytes)
            if (++writeCount % 16 == 0) trimDisk(dir)
        }
    }

    /** 超过 100MB 时按最后修改时间从旧到新删，回落到 80% */
    private fun trimDisk(dir: File) {
        runCatching {
            val files = dir.listFiles() ?: return@runCatching
            var total = files.sumOf { it.length() }
            if (total <= DISK_LIMIT_KB * 1024) return@runCatching
            val target = trimToKb * 1024
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total > target) {
                    // 先删成功再扣：文件被占用时 delete 返回 false，
                    // 原先先扣后删会让统计偏小、提前停止收缩，磁盘停在 100MB 以上。
                    if (runCatching { f.delete() }.getOrDefault(false) || !f.exists()) total -= f.length()
                }
            }
        }
    }

    /** 先只读尺寸算采样率，再按目标尺寸解码，避免大图整幅进内存 */
    private fun decodeSampled(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        // reqW/reqH <= 0 表示"按原尺寸解"；不设这个保护的话下面的循环条件恒为真，
        // 采样率会一路翻倍直到解出 1 像素（data: 内嵌图那条路径会踩到）。
        if (reqW > 0 && reqH > 0) {
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // 海报无透明需求，省一半内存
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
