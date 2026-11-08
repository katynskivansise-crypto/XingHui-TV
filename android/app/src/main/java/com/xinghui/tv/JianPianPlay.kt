package com.xinghui.tv

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.charset.Charset
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 荐片（JianPian）extractor，对齐原版 `player/extractor/JianPian.java`（72 行）。
 *
 * 原版行为：
 *   match()  scheme ∈ { tvbox-xg, jianpian, ftp }
 *   fetch()  stop() → start(url) → 返回 `http://127.0.0.1:<port>/<GBK 编码的文件名>`
 *   start()  路径归一化（去掉 jianpian://pathtype=url&path= / tvbox-xg:// / xg://→ftp://）
 *            然后 P2Pdoxstart + P2Pdoxadd
 *   stop()   P2Pdoxpause
 *   exit()   清空 jpa 目录
 *
 * 与原版的差异（有意）：
 *   1. `Path.jpa()` 已在（见 ensureEngine）：荐片临时目录跟随「缓存目录」设置，和原版同源
 *   2. 不实现 exit()（同进程反复 start/end httpd 是已知易崩路径，和 ThunderPlay 一样刻意不暴露释放）
 *   3. `ftp://` 仍然 match，但只有当它确实被站点返回时才会走到这里；正常 http 直链不受影响
 */
object JianPianPlay {

    private const val TAG = "XingHuiTV"

    private var p2p: com.p2p.P2PClass? = null
    private var curPath: String? = null
    /** path → 是否已暂停（原版 pathPaused） */
    private val paused = HashMap<String, Boolean>()

    fun match(url: String): Boolean {
        val scheme = schemeOf(url)
        return scheme == "tvbox-xg" || scheme == "jianpian" || scheme == "ftp"
    }

    private fun schemeOf(url: String): String = url.substringBefore("://", "").lowercase()

    /**
     * @return 可直接交给播放器的 `http://127.0.0.1:<port>/<文件名>`，失败返回 null
     */
    fun fetch(context: Context, url: String): String? {
        return try {
            val engine = ensureEngine(context) ?: return null
            stop()
            val path = normalize(url)
            if (path.isEmpty()) {
                Log.e(TAG, "[jpa] 路径归一化后为空：${url.take(80)}")
                return null
            }
            val lastPath = curPath
            curPath = path
            val isDiff = lastPath != null && lastPath != path
            // 原版是 GBK 编码（站点侧文件名是中文）
            val bytes = path.toByteArray(Charset.forName("GBK"))
            if (isDiff && lastPath != null) {
                runCatching { engine.del(lastPath.toByteArray(Charset.forName("GBK"))) }
            }
            engine.start(bytes)
            if (lastPath == null || isDiff) engine.add(bytes)
            if (isDiff && lastPath != null) paused.remove(lastPath)
            paused[path] = false

            val name = Uri.parse(path).lastPathSegment.orEmpty()
            val local = "http://127.0.0.1:${engine.port}/" + URLEncoder.encode(name, "GBK")
            Log.e(TAG, "[jpa] 起播 $path → $local")
            local
        } catch (e: Throwable) {
            Log.e(TAG, "[jpa] 失败 ${e.message}", e)
            null
        }
    }

    fun stop() {
        val engine = p2p ?: return
        val path = curPath ?: return
        if (paused[path] == true) return
        runCatching {
            engine.pause(path.toByteArray(Charset.forName("GBK")))
            paused[path] = true
        }.onFailure { Log.e(TAG, "[jpa] pause 失败 ${it.message}") }
    }

    private fun normalize(url: String): String {
        var path = URLDecoder.decode(url, "UTF-8").split("|")[0]
        path = path.replace("jianpian://pathtype=url&path=", "")
        path = path.replace("tvbox-xg://", "").replace("tvbox-xg:", "")
        path = path.replace("xg://", "ftp://").replace("xgplay://", "ftp://")
        return path
    }

    @Synchronized
    private fun ensureEngine(context: Context): com.p2p.P2PClass? {
        p2p?.let { return it }
        // 原版 Path.jpa() = thunderCache()/jpa —— 荐片的 P2P 临时目录跟着「缓存目录」这一项走，
        // 和迅雷的 Path.thunder() 同一个父目录。之前硬编码 filesDir/jpa，用户改缓存目录后
        // 迅雷换了地方、荐片却还留在私有目录里，两套口径就分家了。
        runCatching { com.github.catvod.Init.set(context) }
        val dir = runCatching { com.github.catvod.utils.Path.jpa() }.getOrElse {
            File(context.filesDir, "jpa").apply { if (!exists()) mkdirs() }
        }
        return runCatching { com.p2p.P2PClass(dir.absolutePath) }
            .onFailure { Log.e(TAG, "[jpa] loadLibrary 失败 ${it.message}", it) }
            .getOrNull()
            ?.also {
                p2p = it
                Log.e(TAG, "[jpa] 引擎就绪 port=${it.port}")
            }
    }
}
