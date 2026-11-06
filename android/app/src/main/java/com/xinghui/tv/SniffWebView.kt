package com.xinghui.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 嗅探器：用系统 WebView 打开解析页，从网络请求里截获真实播放地址。
 *
 * 对齐原版 ParseJob case 0 → CustomWebView 的抓流逻辑：
 *   shouldInterceptRequest 里逐个请求做 Sniffer.isVideoFormat 判定，命中即成功。
 *
 * 与原版的差异（都是有意的）：
 *   1. 原版用腾讯 X5(TBS) WebView，闭源且首次运行要下载内核；TV 盒子内核经常下载失败。
 *      这里统一走系统 WebView —— 斐讯 T1 / TCL 这类机器都有，无需额外依赖。
 *   2. 原版 `detect && url.contains("player/?url=")` 会再起一层 WebView 递归嗅探。
 *      这条依赖原版 server 的 /parse 聚合路由，我们没有，所以只做单层。
 *   3. 原版 onPageFinished 会注入订阅 rules 里的 JS 脚本。当前订阅没有可用的 rules，
 *      且注入脚本容易和站点 CSP 打架，这里先用纯请求拦截（覆盖绝大多数解析页）。
 */
class SniffWebView(
    private val host: Activity,
    private val onFound: (url: String, headers: Map<String, String>) -> Unit,
    private val onFail: (reason: String) -> Unit,
) {

    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private var finished = false
    private var timeoutRunnable: Runnable? = null

    companion object {
        private const val TAG = "XingHuiTV"
        private const val TIMEOUT_MS = 15000L
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.104 Safari/537.36"
    }

    /** 打开解析页；url 通常形如 https://jx.xxx/?url=<真实地址> */
    fun start(url: String, headers: Map<String, String> = emptyMap()) {
        if (url.isBlank()) { onFail("嗅探地址为空"); return }
        Log.e(TAG, "[sniff] 打开 ${url.take(120)}")
        host.runOnUiThread {
            val w = build(headers)
            web = w
            // 1x1 透明贴在页面右上角：系统 WebView 需要在视图树里才会真正跑网络请求
            val lp = ViewGroup.LayoutParams(1, 1)
            w.layoutParams = lp
            w.setBackgroundColor(Color.TRANSPARENT)
            (host.window.decorView as? ViewGroup)?.addView(w)
            w.loadUrl(url, headers.takeIf { it.isNotEmpty() } ?: emptyMap<String, String>())

            timeoutRunnable = Runnable {
                if (!finished) {
                    finished = true
                    Log.e(TAG, "[sniff] 超时 ${TIMEOUT_MS}ms 未截获媒体地址")
                    cleanup()
                    onFail("嗅探超时（${TIMEOUT_MS / 1000}秒内没截到 m3u8/mp4，可能是解析页要求交互或已失效）")
                }
            }.also { main.postDelayed(it, TIMEOUT_MS) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(headers: Map<String, String>): WebView {
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
        w.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val u = request?.url?.toString().orEmpty()
                if (u.isNotEmpty() && !finished && Sniffer.isVideoFormat(u)) {
                    finished = true
                    val hdrs = request?.requestHeaders.orEmpty()
                    Log.e(TAG, "[sniff] 截获 ${u.take(140)}")
                    main.post {
                        cleanup()
                        onFound(u, hdrs)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                // API < 21 不会走到这里，但保留兼容实现以防厂商定制 WebView
                if (!url.isNullOrEmpty() && !finished && Sniffer.isVideoFormat(url)) {
                    finished = true
                    Log.e(TAG, "[sniff] 截获(legacy) ${url.take(140)}")
                    main.post { cleanup(); onFound(url, emptyMap()) }
                }
                return super.shouldInterceptRequest(view, url)
            }

            @Suppress("DEPRECATION")
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()   // 解析页常见自签/过期证书，直接放行（对齐原版）
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.e(TAG, "[sniff] 页面加载完成 ${url?.take(100)}")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && !finished) {
                    finished = true
                    val desc = error?.description?.toString() ?: "未知错误"
                    Log.e(TAG, "[sniff] 主页面加载失败 $desc")
                    main.post { cleanup(); onFail("解析页打不开：$desc") }
                }
            }
        }
        return w
    }

    /** 主动取消（用户退出播放页等） */
    fun cancel() {
        if (finished) return
        finished = true
        main.post { cleanup() }
    }

    private fun cleanup() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
        runCatching {
            web?.let { w ->
                w.stopLoading()
                w.loadUrl("about:blank")
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
        }.onFailure { Log.e(TAG, "[sniff] 清理失败", it) }
        web = null
    }
}
