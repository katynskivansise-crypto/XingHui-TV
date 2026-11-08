package com.xinghui.tv

import android.app.Application
import android.util.Log
import javax.net.ssl.HttpsURLConnection

/**
 * 全局网络初始化。
 *
 * 拆原版时发现的一处缺口：
 *
 * 原版 `App.java:111` 调 `OkHttp.get()`，首次 build client 时会 `new SSLCompat()`，
 * 而 `SSLCompat` 构造函数最后一句是
 * `HttpsURLConnection.setDefaultSSLSocketFactory(...)`，
 * 把「信任所有证书」的工厂设成了 JVM 全局默认。
 *
 * 我们仓库里虽然带着 `SSLCompat.java`，但全工程零 `new SSLCompat` ——
 * 也就是说那个构造函数从来没被执行过，所有 HTTPS 请求一直在严格校验证书。
 *
 * 后果：影视站里自签证书、证书过期、证书链不完整的那一批会直接连不上，
 * 表现为封面空白、EXO 点播起不来；而 mpv 反而能播（我们已经给它下发了 tls-verify=no）。
 * 这种「一个内核能播、另一个不能」的错位，最容易让人以为是内核参数问题。
 *
 * 再往下一层：原版 EXO 用的是 `OkHttpDataSource`，底层 `com.github.catvod.net.OkHttp`
 * 里是 `sslSocketFactory(SSLCompat)` + `hostnameVerifier((h, s) -> true)`，**两层都放开**。
 * 我们走 `DefaultHttpDataSource`（HttpURLConnection），所以除了证书链，
 * hostname 校验也要单独放开，才能对齐原版播放时那套网络能力。
 */
class XingHuiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installRelaxedTls()
    }

    /**
     * 幂等、且失败不致命：任何一步抛异常都只写日志，回落到系统默认的严格校验，
     * 绝不能因为放宽证书失败而让整个应用起不来。
     */
    private fun installRelaxedTls() {
        runCatching { com.github.catvod.net.SSLCompat() }
            .onFailure { Log.w(TAG, "SSLCompat 初始化失败，回落系统默认证书校验", it) }
        runCatching {
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        }.onFailure { Log.w(TAG, "hostnameVerifier 设置失败", it) }
    }

    companion object {
        private const val TAG = "XingHuiTV"
    }
}
