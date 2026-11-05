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

    private val pool = Executors.newFixedThreadPool(4)

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
        if (url.isBlank()) return
        // 海报地址可能是 file:// / proxy:// / assets://，统一重写成本地 HTTP 地址再下载；
        // 原版 UrlUtil.convert 也是在这个位置做的（图片走 OkHttp 而非 file loader）
        val realUrl = UrlConvert.convert(url).ifBlank { url }

        cache.get(url)?.let { bmp ->
            view.setImageBitmap(bmp)
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
                // 磁盘/内存缓存 key 用原始 url（同一张图不因 scheme 差异重复缓存），下载用重写后的地址
                val bytes = readDisk(ctx, url) ?: fetch(ctx, realUrl)?.also { writeDisk(ctx, url, it) }
                if (bytes == null || bytes.isEmpty()) return@execute
                val bmp = decodeSampled(bytes, reqW, reqH) ?: return@execute
                cache.put(url, bmp)
                view.post {
                    // 控件已被复用绑定到别的 url 时，丢弃这次结果
                    if (view.tag == url) view.setImageBitmap(bmp)
                }
            } catch (_: Throwable) {
                // 占位图已设置，静默失败（catch Throwable：解码 OOM 也不该让进程崩）
            }
        }
    }

    /** 走引擎海报代理拿原始字节（带设置里的 UA / 代理）；引擎失败时回退系统栈直连 */
    private fun fetch(context: Context, url: String): ByteArray? {
        val s = Settings(context)
        val params = JSONObject()
            .put("url", url)
            .put("ua", s.ua)
            .put("proxy", s.proxy)
            .toString()
        val b64 = Engine.execute("proxy_image", "", params)
        if (b64.isNotBlank()) {
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
            Log.e("XingHuiTV", "[image] 引擎返回内容解不出来 len=${b64.length} url=$url")
        } else {
            Log.e("XingHuiTV", "[image] 引擎取图返回空，回退直连 url=$url")
        }
        // 兜底：部分图片 CDN 对引擎侧 TLS 指纹/HTTP 版本不友好，用系统栈再试一次。
        // 原版走 okhttp 直连能出图，这里用 HttpURLConnection 等价于原版那条路径。
        return runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", s.ua.ifBlank { "okhttp/4.12.0" })
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            conn.disconnect()
            if (code !in 200..299) {
                Log.e("XingHuiTV", "[image] 直连 HTTP $code url=$url")
                null
            } else body
        }.onFailure { e ->
            Log.e("XingHuiTV", "[image] 直连也失败 url=$url", e)
        }.getOrNull()
    }

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
                    total -= f.length()
                    f.delete()
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
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // 海报无透明需求，省一半内存
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
