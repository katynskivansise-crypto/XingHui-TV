package com.xinghui.tv

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 简易网络下载（仅用于下载订阅 JSON / drpy 规则脚本 / spider.jar）。
 * 用系统 HttpURLConnection，不引第三方依赖；必须在非主线程调用。
 */
object JsonFetcher {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 下载文本内容；失败返回 null */
    fun get(url: String, timeoutMs: Int = 20000): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code in 200..299) {
                val input = conn.inputStream
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 下载字节到本地文件（spider.jar 用）；成功返回 true，失败 false。
     *
     * 与旧实现的区别（这是 csp 站长期"未就绪"的真正根因）：
     * 旧版直接 input.copyTo(output) 后返回 true —— 连接被提前掐断时 copyTo 读到 EOF 就
     * 正常结束，结果是**写下一个截断的 jar 却报告成功**，DexClassLoader 打开后一个类都
     * 读不到，报 ClassNotFoundException: com.github.catvod.spider.AppDrama。
     * 原版 JarLoader.download 走 OkHttp 的 body().bytes()，字节数不足会抛
     * IOException("unexpected end of stream")，绝不会落下半截文件。
     * 这里改为：按 Content-Length 逐块读并计数、长度不符即失败、先写 .part 再原子重命名。
     */
    fun download(url: String, dest: File, timeoutMs: Int = 30000): Boolean {
        val tmp = File(dest.absolutePath + ".part")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            // 必须禁用 gzip：否则 contentLength 是压缩后的长度，与实际落盘字节数对不上
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) {
                runCatching { tmp.delete() }
                return false
            }
            dest.parentFile?.mkdirs()
            // contentLengthLong 是 API 24+，minSdk 21 的老电视会 NoSuchMethodError，用 Int 版转 Long
            val expected = conn.contentLength.toLong()
            var written = 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                    }
                    output.flush()
                }
            }
            if (expected > 0 && written != expected) {
                Log.e(
                    JsonFetcher::class.java.simpleName,
                    "[download] 长度不符，丢弃：期望=$expected 实际=$written url=$url"
                )
                runCatching { tmp.delete() }
                return false
            }
            if (written < 1024) {
                runCatching { tmp.delete() }
                return false
            }
            // 原子替换：校验通过才让正式文件名生效，任何中途失败都不会污染缓存
            if (dest.exists()) runCatching { dest.delete() }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                runCatching { tmp.delete() }
            }
            true
        } catch (e: Exception) {
            Log.e(JsonFetcher::class.java.simpleName, "[download] 失败 url=$url", e)
            runCatching { tmp.delete() }
            false
        }
    }
}
