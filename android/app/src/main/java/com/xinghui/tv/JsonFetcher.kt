package com.xinghui.tv

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

    /** 下载字节到本地文件（spider.jar 用）；成功返回 true，失败 false */
    fun download(url: String, dest: File, timeoutMs: Int = 30000): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            conn.instanceFollowRedirects = true
            if (conn.responseCode in 200..299) {
                dest.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
