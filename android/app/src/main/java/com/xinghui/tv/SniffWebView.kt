package com.xinghui.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream

/**
 * 嗅探器：打开解析页，从 WebView 的网络请求里截获真实播放地址。
 *
 * 对齐原版 ParseJob case 0 → CustomWebView + WebDialog 的完整机制：
 *   1. **可见可交互**（原版 WebDialog 是 80% 屏宽的弹窗）。这是关键差异：
 *      绝大多数解析页要先点一下页面里的「播放」按钮才会真正拉 m3u8，
 *      之前做成 1×1 隐形 WebView，没有任何人能点，所以基本截不到。
 *   2. 逐请求判定 Sniffer.isVideoFormat（已带订阅 rules 的白名单/排除）。
 *   3. 广告域名（订阅 ads）直接返回空响应阻断，省流量也避免误判（原版 CustomWebView.isAd）。
 *   4. onPageFinished 注入订阅 rules 的 script / 站点 click（原版 evaluate(getScript(url))），
 *      对应「夜市點擊 vjs-big-play-button」「毛驢點擊 swal 确认按钮」这类自动点击。
 *   5. 遇到需要人工过验证的页面时不抢超时，延长到 60 秒并提示用户操作。
 *
 * 与原版保留的差异：
 *   - 用系统 WebView 而非腾讯 X5(TBS)：TV 盒子内核下载失败率高，系统 WebView 一定有。
 *   - 不做 detect 递归（依赖原版 server 的 /parse 聚合路由，本工程没有）。
 */
class SniffWebView(
    private val host: Activity,
    private val onFound: (url: String, headers: Map<String, String>) -> Unit,
    private val onFail: (reason: String) -> Unit,
) {

    private var web: WebView? = null
    private var overlay: FrameLayout? = null
    private var tip: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private var finished = false
    private var timeoutRunnable: Runnable? = null
    private var nudgeRunnable: Runnable? = null
    private var extended = false
    /** 起始请求头（嵌套跳转时要带上，否则内层解析页拿不到 UA/Referer） */
    private var startHeaders: Map<String, String> = emptyMap()
    /** 是否已经做过一次「解析页套解析页」跳转（原版 detect 递归，这里限一次防死循环） */
    private var nested = false
    /** 站点级点击脚本（订阅 site.click），单独存一份供 runScripts 使用 */
    private var clickScript: String = ""

    companion object {
        private const val TAG = "XingHuiTV"
        private const val TIMEOUT_MS = 15_000L
        private const val EXTENDED_MS = 60_000L
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.104 Safari/537.36"
    }

    /** 打开解析页；url 通常形如 https://jx.xxx/?url=<真实地址>；clickScript 为站点级点击脚本 */
    fun start(url: String, headers: Map<String, String> = emptyMap(), clickScript: String = "") {
        if (url.isBlank()) {
            onFail("嗅探地址为空")
            return
        }
        Log.e(TAG, "[sniff] 打开 ${url.take(120)}")
        startHeaders = headers
        this.clickScript = clickScript
        host.runOnUiThread {
            buildOverlay()
            val w = build(headers, clickScript)
            web = w
            overlay?.addView(
                w,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            w.requestFocus()
            w.loadUrl(url, headers.takeIf { it.isNotEmpty() } ?: emptyMap<String, String>())
            scheduleTimeout(TIMEOUT_MS)
        }
    }

    /** 覆盖层：全屏半透明遮罩 + 顶部提示条 + WebView（原版 WebDialog 的可交互版） */
    private fun buildOverlay() {
        val act = host
        val root = FrameLayout(act).apply {
            setBackgroundColor(0xCC000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val t = TextView(act).apply {
            text = "正在嗅探播放地址 —— 如果页面里有「播放」按钮，请用遥控器点一下"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(act).apply {
            text = "关闭"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            isFocusable = true
            isClickable = true
            setOnClickListener { cancel("用户关闭嗅探") }
        }
        bar.addView(t)
        bar.addView(close)
        tip = t

        column.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        (act.window.decorView as? ViewGroup)?.addView(root)
        overlay = root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(headers: Map<String, String>, clickScript: String): WebView {
        val w = WebView(host)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            blockNetworkImage = true          // 图片不加载，省流量也省时间（对齐原版）
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = headers.entries
                .firstOrNull { it.key.equals("User-Agent", true) || it.key.equals("ua", true) }
                ?.value ?: DEFAULT_UA
        }
        // 解析页普遍依赖第三方 cookie（Cloudflare 验证通过后就靠 cookie 续命），
        // 原版 CustomWebView.start 里显式 setAcceptThirdPartyCookies(this, true)
        runCatching {
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(w, true)
            }
        }.onFailure { Log.e(TAG, "[sniff] Cookie 设置失败 ${it.message}") }
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val u = request?.url?.toString().orEmpty()
                val h = request?.url?.host.orEmpty()

                // 广告域名直接阻断（对齐原版 CustomWebView.isAd → 返回空响应）
                if (h.isNotEmpty() && SnifferRules.isAd(h)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                // 解析页套解析页（原版 CustomWebView: detect && url.contains("player/?url=") → onParseAdd）
                // 典型场景：站点给的是 xxx/?url=https://jx.yyy/?url=<真实地址>，
                // 外层页面自己不起播，真正的播放器在内层。原版的做法是再开一个 WebView 打开内层地址。
                // 这里简化为「就地跳转过去」，并且只做一次，避免 A→B→A 死循环。
                if (!nested && request?.isForMainFrame == true && u.contains("player/?url=") && !finished) {
                    nested = true
                    Log.e(TAG, "[sniff] 命中嵌套解析层，跳转 ${u.take(120)}")
                    main.post { web?.loadUrl(u, startHeaders) }
                    return super.shouldInterceptRequest(view, request)
                }
                if (u.isNotEmpty() && !finished && Sniffer.isVideoFormat(u)) {
                    hit(u, request?.requestHeaders.orEmpty())
                }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (!url.isNullOrEmpty() && !finished && Sniffer.isVideoFormat(url)) {
                    hit(url, emptyMap())
                }
                return super.shouldInterceptRequest(view, url)
            }

            @Suppress("DEPRECATION")
            override fun onReceivedSslError(
                view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?
            ) {
                handler?.proceed()   // 解析页常见自签/过期证书，直接放行（对齐原版）
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val u = url.orEmpty()
                // 原版 CustomWebView.onPageFinished 第一句：if (url.equals(BLANK)) return;
                // 我们 cleanup 时会 loadUrl("about:blank")，不拦的话会往空白页注入脚本
                if (u.isEmpty() || u.startsWith("about:")) return
                Log.e(TAG, "[sniff] 页面加载完成 ${u.take(100)}")
                runScripts(view, u)
                // 页面加载完还没截到，提示用户手动操作一次
                if (!finished) {
                    nudgeRunnable?.let { main.removeCallbacks(it) }
                    nudgeRunnable = Runnable {
                        if (!finished) {
                            tip?.text = "页面已加载但还没截到视频 —— 请用遥控器点一下页面里的播放按钮"
                        }
                    }.also { main.postDelayed(it, 2500) }
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && !finished) {
                    // 原版 CustomWebView 没有 onReceivedError，页面打不开就交给 15s 超时处理。
                    // 我们之前直接 fail 太激进：解析页首屏偶发 5xx / 证书链问题很常见，
                    // 用户手动刷新或点一下常常还能救回来，所以这里只提示，判死仍交给超时。
                    val desc = error?.description ?: "未知错误"
                    Log.e(TAG, "[sniff] 主框架加载出错：$desc（不中断，等待超时或手动重试）")
                    tip?.text = "页面加载出错（$desc）—— 可以点「关闭」重来，或等自动重试"
                }
            }
        }
        return w
    }

    /**
     * 脚本注入顺序对齐原版 CustomWebView：
     *   getScript(url) = Sniffer.getScript(uri) 的规则脚本；click 不为空且不在列表里 → **add(0, click)**，
     *   即站点级 click 永远排第一。
     * 执行方式也照原版 evaluate()：逐个 evaluateJavascript，前一个回调回来再执行下一个（串行）。
     * 之前是并行 evaluate 所有脚本，后一个脚本可能在前一个还没点出播放按钮时就跑，效果不稳定。
     */
    private fun runScripts(view: WebView?, url: String) {
        val list = ArrayList<String>()
        if (clickScript.isNotBlank()) list.add(clickScript)
        val rules = SnifferRules.scripts(url)
        for (r in rules) if (r.isNotBlank() && r !in list) list.add(r)
        if (list.isEmpty()) return
        evalSequential(view, list, 0)
    }

    private fun evalSequential(view: WebView?, list: List<String>, index: Int) {
        if (view == null || finished || index >= list.size) return
        val s = list[index]
        if (s.isBlank()) {
            evalSequential(view, list, index + 1)
            return
        }
        Log.e(TAG, "[sniff] 注入脚本 ${index + 1}/${list.size}：${s.take(60)}")
        view.evaluateJavascript(s) { evalSequential(view, list, index + 1) }
    }

    private fun hit(url: String, headers: Map<String, String>) {
        if (finished) return
        finished = true
        Log.e(TAG, "[sniff] 截获 ${url.take(140)}")
        main.post {
            cleanup()
            onFound(url, headers)
        }
    }

    private fun scheduleTimeout(ms: Long) {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = Runnable {
            if (finished) return@Runnable
            // 需要人工过验证/交互的页面，给一次延长机会
            if (!extended) {
                extended = true
                tip?.text = "页面需要验证或交互，已延长到 60 秒 —— 请用遥控器完成页面操作"
                Log.e(TAG, "[sniff] 首次超时，延长至 ${EXTENDED_MS}ms")
                scheduleTimeout(EXTENDED_MS)
                return@Runnable
            }
            fail("嗅探超时（60 秒内没截到 m3u8/mp4，可能是解析页要求交互或已失效）")
        }.also { main.postDelayed(it, ms) }
    }

    /** 主动取消（用户退出播放页等） */
    fun cancel(reason: String = "嗅探已取消") {
        if (finished) return
        finished = true
        main.post {
            cleanup()
            onFail(reason)
        }
    }

    private fun fail(reason: String) {
        if (finished) return
        finished = true
        Log.e(TAG, "[sniff] 失败：$reason")
        main.post {
            cleanup()
            onFail(reason)
        }
    }

    private fun cleanup() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
        nudgeRunnable?.let { main.removeCallbacks(it) }
        nudgeRunnable = null
        runCatching {
            web?.let { w ->
                w.stopLoading()
                w.loadUrl("about:blank")
                w.destroy()
            }
        }.onFailure { Log.e(TAG, "[sniff] WebView 清理失败", it) }
        web = null
        runCatching { overlay?.let { o -> (o.parent as? ViewGroup)?.removeView(o) } }
        overlay = null
        tip = null
    }

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()
}
