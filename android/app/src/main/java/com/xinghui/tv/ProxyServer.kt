package com.xinghui.tv

import android.content.Context
import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder

/**
 * 本地服务（对齐原版 server/Nano.java 的路由分派 + server/process 下各 Process 实现）。
 *
 * 原版 Nano 在 serve() 里按路径前缀分派到 6 个 Process，再兜底当 assets 读：
 *   /action   Action      —— 遥控/投屏动作
 *   /cache    Cache       —— 缓存查询
 *   /file     Local       —— 本地文件（支持 Range，播放器 seek 靠它）
 *   /upload /newFolder /delFolder /delFile  Local —— 文件管理
 *   /media    Media       —— 媒体信息
 *   /parse    Parse       —— 解析
 *   /proxy    Proxy       —— 播放/图片代理（csp 站核心）
 *   /go       Go          —— 投屏
 *   /tvbus    LiveConfig  —— 直播推送
 *   /device   Device      —— 设备信息
 *   兜底      getAssets   —— APK 内 assets 静态资源
 *
 * 我们只需要三条：
 *   /proxy  —— csp/js 站的代理地址，必须反射 jar 里的 Proxy.proxy(Map)
 *   /file   —— file:// 与 clan:// 的落点，缺它播放器与图片加载器全是 404
 *   兜底    —— local:// 与 assets:// 的落点，读 APK 内资源
 *
 * /action 只做三条（原版 /action 还有 file/cast/sync/refresh/transmit 等设备间同步，
 * 本项目不做）：
 *   do=setting —— 手机网页改订阅地址 / UA
 *   do=search  —— 手机网页搜片，电视端开搜索页
 *   do=push    —— 手机网页推链接，电视端直接播放
 *
 * 注意：本类不是 NanoHTTPD 子类，所有静态方法/嵌套类型都必须显式带 NanoHTTPD 前缀，
 * 否则 Kotlin 编译报 Unresolved reference。
 */
object ProxyServer {

    private const val TAG = "XingHuiTV"
    private const val PORT = 9978

    /**
     * 原版 `Sniffer.AI_PUSH`：
     *   Pattern.compile("(http|https|rtmp|rtsp|smb|ftp|thunder|magnet|ed2k|mitv|tvbox-xg|jianpian|video):[^\\s]+")
     * 手机分享出来的通常是一段文案带链接，得先把链接抠出来再送播放器。
     */
    private val AI_PUSH = Regex(
        "(http|https|rtmp|rtsp|smb|ftp|thunder|magnet|ed2k|mitv|tvbox-xg|jianpian|video):\\S+",
        setOf(RegexOption.MULTILINE)
    )

    @Volatile
    private var http: NanoHTTPD? = null

    @Volatile
    private var appCtx: Context? = null

    /** 启动本地服务（幂等）。ctx 用于读 assets 与私有目录；缺失时 /file 仍可用，assets 兜底不可用。 */
    /** /debug 页尾部的「上次崩溃完整堆栈」：MainActivity 的兜底处理器会写到 filesDir/crash.log */
    private fun crashTail(): String {
        val dir = appCtx?.filesDir
            ?: return "== 上次崩溃 ==\n（无私有目录）"
        return runCatching {
            val f = File(dir, "crash.log")
            if (!f.exists()) "== 上次崩溃 ==\n（无）"
            else "== 上次崩溃 ==\n" + f.readText().takeLast(2400)
        }.getOrElse { "== 上次崩溃 ==\n（读取失败：${it.message}）" }
    }

    fun startServer(ctx: Context? = null) {
        ctx?.applicationContext?.let { appCtx = it }
        if (http != null) return
        synchronized(this) {
            if (http != null) return
            val server = object : NanoHTTPD(PORT) {
                override fun serve(session: IHTTPSession): Response {
                    val path = session.uri.substringBefore('?').trim()
                    return when {
                        // 调试通道：手机浏览器打开 http://盒子IP:9978/debug
                        // 只给排查用，播放页 UI 一行不加、不弹任何提示
                        path == "/debug" -> {
                            // 诊断档位：/debug?x=1 或 /debug?x=2，一次装包即可做 A/B（界面零改动、不弹提示）
                            session.parameters["x"]?.firstOrNull()?.toIntOrNull()?.let { x ->
                                MpvDebug.experiment = x.coerceIn(0, 2)
                                MpvDebug.log("[exp] 档位切换为 ${MpvDebug.experiment}")
                            }
                            newFixedLengthResponse(
                                Response.Status.OK, "text/plain; charset=utf-8",
                                MpvDebug.dump() + "\n" + crashTail()
                            )
                        }
                        // 原版 /action（server/process/Action.java）：手机网页的「設定/推送/搜尋」都打这里
                        path == "/action" -> handleAction(session)
                        path == "/proxy" -> handleProxy(session)
                        path.startsWith("/file") -> handleFile(session, path)
                        // 原版 /upload /newFolder /delFolder /delFile 也归 Local，但我们不做文件管理
                        path.startsWith("/upload") || path.startsWith("/newFolder") ||
                            path.startsWith("/delFolder") || path.startsWith("/delFile") ->
                            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not supported")
                        // 原版兜底：任何未匹配路径都当 APK 内 assets 读
                        else -> handleAssets(path)
                    }
                }
            }
            runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
                .onSuccess {
                    http = server
                    Log.e(TAG, "[proxy.server] 已启动 127.0.0.1:$PORT（/proxy + /file + assets）")
                }
                .onFailure { Log.e(TAG, "[proxy.server] 启动失败", it) }
        }
    }

    fun stopServer() {
        runCatching { http?.stop() }
        http = null
    }

    // ───────────────────────── /action ─────────────────────────

    /**
     * 对齐原版 server/process/Action.java 的 doResponse：
     *   do=setting  {text, name}  —— 写配置。name 为空时原版写的是点播订阅地址
     *   do=push     {url}         —— 推送到电视播放
     *   do=search   {word}        —— 电视端搜索
     */
    private fun handleAction(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val p = HashMap<String, String>()
        runCatching {
            // parms 是 Map<String,String>（POST 表单已解析），parameters 是 Map<String,List<String>>
            session.parms?.forEach { (k, v) -> p[k] = v }
            session.parameters?.forEach { (k, v) -> v.firstOrNull()?.let { if (p[k] == null) p[k] = it } }
        }
        val act = p["do"].orEmpty().trim()
        val text = p["text"].orEmpty().trim()
        val name = p["name"].orEmpty().trim()
        // 原版 Action.onPush 读的是 url 参数（手机网页 js 里也是 push_url → do=push&url=），
        // 之前这里只读了 text，所以推送分支拿不到地址。text 作为兜底。
        val pushUrl = p["url"].orEmpty().trim().ifBlank { text }
        Log.i(TAG, "[proxy.action] do=$act name=$name text=${text.take(80)} url=${pushUrl.take(80)}")
        val ctx = appCtx
        when (act) {
            "setting" -> if (ctx != null && text.isNotBlank()) {
                if (name.isBlank()) {
                    // 原版 name 为空 = 点播订阅。LiveConfig.sync 语义：直播没单独配就跟着点播走
                    SubscriptionStore(ctx).setSubscribeUrl(text)
                    val settings = Settings(ctx)
                    if (settings.liveUrl.isBlank()) settings.liveUrl = text
                } else if (name.equals("ua", true)) {
                    Settings(ctx).ua = text
                }
            }
            // do=search：手机网页搜片 → 电视直接打开搜索页并开搜。
            "search" -> if (ctx != null && text.isNotBlank()) {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(ctx, SearchActivity::class.java)
                            .putExtra("keyword", text)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Log.e(TAG, "[proxy.action] 启动搜索失败", it) }
            }
            // do=push：对齐原版 Action.onPush → ServerEvent.push → VideoActivity.push：
            //   1) Sniffer.getUrl 从分享文案里抠真实地址；
            //   2) 直接进播放页（原版 start(..., clear=true)，对应 CLEAR_TOP|NEW_TASK）。
            // magnet / thunder / ed2k 由 PlayActivity.playUrl 内部转 ThunderPlay，这里不区分。
            "push" -> if (ctx != null && pushUrl.isNotBlank()) {
                val real = extractPushUrl(pushUrl)
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(ctx, PlayActivity::class.java)
                            .putExtra("direct_url", real)
                            // 原版 start(activity, url) → start(activity, "push_agent", url, url, clear)
                            // id 与 name 都是 url 本身；id 一起带上，历史记录才能被再次点开回放
                            .putExtra("vod_id", real)
                            .putExtra("vod_name", real)
                            // 标记「这是手机推送」：网页地址在播放页里转嗅探 WebView，直链照常直接播
                            .putExtra("push_mode", true)
                            .addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                    )
                    Log.i(TAG, "[proxy.action] push → ${real.take(120)}")
                }.onFailure { Log.e(TAG, "[proxy.action] 推送启动播放页失败", it) }
            }
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK")
    }

    /**
     * 原版 Sniffer.getUrl：
     *   含 `$`（规则/配置串）→ 原样返回；
     *   否则按 AI_PUSH 抠第一个链接；
     *   抠不到就原样返回（让播放页自己失败，而不是这里吞掉）。
     */
    private fun extractPushUrl(text: String): String {
        val t = text.trim()
        if (t.contains("$")) return t
        return AI_PUSH.find(t)?.value ?: t
    }

    // ───────────────────────── /proxy ─────────────────────────

    private fun handleProxy(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val params = HashMap<String, String>()
        runCatching {
            session.parameters.forEach { (k, v) -> v.firstOrNull()?.let { params[k] = it } }
            session.headers?.forEach { (k, v) -> params[k] = v }
        }
        val rs = CspLoader.proxyLocal(params)
        if (rs == null || rs.isEmpty()) {
            Log.e(TAG, "[proxy.server] proxyLocal 返回空 params=$params")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "proxy null"
            )
        }
        // 对齐原版 Proxy.doResponse：rs[0] 若是 Response 对象则直接透出
        val first = rs[0]
        if (first is NanoHTTPD.Response) return first
        return try {
            val status = (rs[0] as? Number)?.toInt() ?: 200
            val contentType = rs.getOrNull(1) as? String ?: "application/octet-stream"
            val stream = rs.getOrNull(2) as? InputStream
            val headers = rs.getOrNull(3) as? Map<*, *>
            val resp: NanoHTTPD.Response = if (stream != null) {
                NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.lookup(status), contentType, stream)
            } else {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.lookup(status), contentType, "")
            }
            headers?.forEach { (k, v) -> if (k is String && v is String) resp.addHeader(k, v) }
            resp
        } catch (e: Exception) {
            Log.e(TAG, "[proxy.server] 响应构造失败", e)
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, e.toString()
            )
        }
    }

    // ───────────────────────── /file ─────────────────────────

    /**
     * UrlConvert 把 file:// 改写成 http://127.0.0.1:9978/file/<path>，
     * 这里把 <path> 还原成磁盘文件并按 HTTP Range 语义返回。
     */
    private fun handleFile(session: NanoHTTPD.IHTTPSession, path: String): NanoHTTPD.Response {
        val file = resolveFile(path)
        if (file == null) {
            Log.e(TAG, "[proxy.server] /file 找不到 $path")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "file not found"
            )
        }
        return runCatching { streamFile(session, file, path) }
            .getOrElse {
                Log.e(TAG, "[proxy.server] /file 读取失败 $path", it)
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, it.toString()
                )
            }
    }

    private fun resolveFile(path: String): File? {
        val raw = path.removePrefix("/file").trimStart('/')
        if (raw.isEmpty()) return null
        val rel = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        // 1) 已经是绝对路径（file:// 常见写法 file:///storage/emulated/0/xxx）
        File("/$rel").takeIf { it.isFile }?.let { return it }
        // 2) 外部存储根（对齐原版 Path.root = Environment.getExternalStorageDirectory）
        runCatching { File(Environment.getExternalStorageDirectory(), rel) }
            .getOrNull()?.takeIf { it.isFile }?.let { return it }
        // 3) 应用私有目录：Android 10+ 分区存储下外部根常常读不到
        appCtx?.let { ctx ->
            File(ctx.filesDir, rel).takeIf { it.isFile }?.let { return it }
            ctx.getExternalFilesDir(null)?.let { ext -> File(ext, rel).takeIf { it.isFile }?.let { return it } }
            File(ctx.cacheDir, rel).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /**
     * 文件流式响应。播放器 seek 会发 Range: bytes=start-end，
     * 必须回 206 + Content-Range，否则拖动进度条会失败或整段重下。
     */
    private fun streamFile(
        session: NanoHTTPD.IHTTPSession,
        file: File,
        path: String
    ): NanoHTTPD.Response {
        val len = file.length()
        val mime = NanoHTTPD.getMimeTypeForFile(path)
        val etag = Integer.toHexString((file.absolutePath + file.lastModified() + "" + len).hashCode())
        val hIfNone = session.headers?.get("if-none-match")
        if (hIfNone != null && (hIfNone == "*" || hIfNone == etag)) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_MODIFIED, mime, ""
            ).also {
                it.addHeader("Accept-Ranges", "bytes")
                it.addHeader("ETag", etag)
            }
        }
        var start = 0L
        var end = len - 1
        var hasRange = false
        val range = session.headers?.get("range")
        if (range != null && range.startsWith("bytes=")) {
            hasRange = true
            val spec = range.substring("bytes=".length)
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                spec.substring(0, dash).trim().toLongOrNull()?.let { start = it }
                spec.substring(dash + 1).trim().toLongOrNull()?.let { end = it }
            }
        }
        if (start < 0) start = 0
        if (start >= len) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, NanoHTTPD.MIME_PLAINTEXT, ""
            ).also {
                it.addHeader("Content-Range", "bytes */$len")
                it.addHeader("Accept-Ranges", "bytes")
            }
        }
        if (end < 0 || end >= len) end = len - 1
        if (end < start) end = len - 1
        val contentLen = end - start + 1
        val fis = FileInputStream(file)
        if (start > 0) runCatching { fis.skip(start) }
        return if (hasRange) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.PARTIAL_CONTENT, mime, fis, contentLen
            ).also {
                it.addHeader("Content-Length", "$contentLen")
                it.addHeader("Content-Range", "bytes $start-$end/$len")
                it.addHeader("Accept-Ranges", "bytes")
                it.addHeader("ETag", etag)
            }
        } else {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, mime, fis, len
            ).also {
                it.addHeader("Content-Length", "$len")
                it.addHeader("Accept-Ranges", "bytes")
                it.addHeader("ETag", etag)
            }
        }
    }

    // ───────────────────── assets 兜底 ─────────────────────

    /**
     * 对齐原版 Nano.getAssets：未匹配的路径一律当 APK 内 assets 读。
     * UrlConvert 把 local:// 与 assets:// 都改写成 http://127.0.0.1:9978/<path>，落点就是这里。
     */
    private fun handleAssets(path: String): NanoHTTPD.Response {
        val ctx = appCtx
        if (ctx == null) {
            Log.e(TAG, "[proxy.server] assets 兜底缺 Context，path=$path")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "no context"
            )
        }
        val p = path.trimStart('/').ifEmpty { "index.html" }
        return runCatching {
            val ins = ctx.assets.open(p)
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, NanoHTTPD.getMimeTypeForFile(p), ins, ins.available().toLong()
            )
        }.getOrElse {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found"
            )
        }
    }
}
