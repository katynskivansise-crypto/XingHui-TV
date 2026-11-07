package com.xinghui.tv

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对齐原版 `com.fongmi.android.tv.player.ParseJob`（224 行）。
 *
 * 原版五个分支全部照搬，唯一不做的是 type=0 之外还在依赖本地 server 的那条
 * `startWeb(List<Parse>)`（`Server.get().getAddress("/parse?jxs=a;b;c&url=...")`）——
 * 本工程没有那个聚合路由，type=4 的「web 侧」直接复用我们自己的 [SniffWebView]。
 *
 * 成功判据严格照 `ParseJob.checkResult`：**`url.length() > 40`** 才算拿到真地址。
 * 这是原版防止把错误页/空串当成功的关键闸门，必须照抄。
 */
object ParseJob {

    private const val TAG = "XingHuiTV"
    private const val MIN_URL_LEN = 40

    data class Hit(
        val url: String,
        val headers: Map<String, String>,
        val from: String,
    )

    /**
     * type=1 Json：`GET parse.url + webUrl`，取顶层 `url`，没有则取 `data.url`。
     * header 优先用响应 JSON 里的 `user-agent` / `referer` / `ua`（原版 getHeader），
     * 全空则退回 parse 自己的 ext.header。
     */
    fun jsonParse(item: ParseSources.Item, webUrl: String): Hit? {
        return try {
            val body = httpGet(item.url + webUrl, item.headers) ?: return null
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
            var url = obj.optString("url", "")
            if (url.isEmpty()) url = obj.optJSONObject("data")?.optString("url", "").orEmpty()
            if (url.length <= MIN_URL_LEN) {
                Log.e(TAG, "[parse] type1 ${item.name} 返回的 url 太短(${url.length})：${url.take(60)}")
                return null
            }
            Log.e(TAG, "[parse] type1 ${item.name} 成功 → ${url.take(80)}")
            Hit(url, headerFrom(obj) ?: item.headers, item.name)
        } catch (e: Throwable) {
            Log.e(TAG, "[parse] type1 ${item.name} 失败 ${e.message}")
            null
        }
    }

    /**
     * type=2 Json扩展：反射 jar 内 `com.github.catvod.parser.Json<key>`，
     * `parse(LinkedHashMap<String,String> jxs, String url)`。
     * `jxs` 是所有 type=1 的 `name → extUrl()`（原版 Parse.extUrl：把 ext 经 base64 塞进 URL）。
     */
    fun jsonExt(item: ParseSources.Item, webUrl: String): Hit? {
        return try {
            val cls = CspLoader.parserClass("com.github.catvod.parser.Json" + item.url)
                ?: return null
            val jxs = LinkedHashMap<String, String>()
            for (p in ParseSources.byType(1)) jxs[p.name] = extUrlOf(p)
            val method = cls.getMethod("parse", LinkedHashMap::class.java, String::class.java)
            val result = method.invoke(null, jxs, webUrl) as? JSONObject ?: return null
            hitFromResult(result, item, "type2/${item.name}")
        } catch (e: Throwable) {
            Log.e(TAG, "[parse] type2 ${item.name} 失败 ${e.message}")
            null
        }
    }

    /**
     * type=3 Json聚合：反射 jar 内 `com.github.catvod.parser.Mix<key>`，
     * `parse(LinkedHashMap<String,HashMap> jxs, String name, String flag, String url)`。
     * 订阅里唯一一条 `Json聚合` url=`Demo` → jar 中确有 `MixDemo`（已实测类名）。
     */
    fun jsonMix(item: ParseSources.Item, flag: String, webUrl: String): Hit? {
        return try {
            val cls = CspLoader.parserClass("com.github.catvod.parser.Mix" + item.url)
                ?: return null
            val jxs = LinkedHashMap<String, HashMap<String, String>>()
            for (p in ParseSources.all()) {
                val map = HashMap<String, String>()
                map["type"] = p.type.toString()
                map["ext"] = p.extJson
                map["url"] = p.url
                jxs[p.name] = map
            }
            val method = cls.getMethod(
                "parse",
                LinkedHashMap::class.java, String::class.java, String::class.java, String::class.java
            )
            // 原版调用顺序：method.invoke(null, jxs, name, flag, url)
            val result = method.invoke(null, jxs, item.name, flag, webUrl) as? JSONObject ?: return null
            hitFromResult(result, item, "type3/${item.name}")
        } catch (e: Throwable) {
            Log.e(TAG, "[parse] type3 ${item.name} 失败 ${e.message}")
            null
        }
    }

    /**
     * type=4 超级解析：并发跑全部 type=1 + 一个 web 嗅探，谁先成功算谁。
     * web 侧不在这里做（要起 WebView，必须由 Activity 调度），所以本函数只跑 Json 侧，
     * 返回第一个成功的 Json 结果；web 侧由 PlayActivity 同时启动，两边谁先到谁赢。
     */
    fun godJsonSide(webUrl: String, timeoutMs: Long = 15000): Hit? {
        val jsonItems = ParseSources.byType(1)
        if (jsonItems.isEmpty()) return null
        val latch = CountDownLatch(jsonItems.size)
        val done = AtomicBoolean(false)
        val winner = arrayOfNulls<Hit>(1)
        for (item in jsonItems) {
            Thread {
                try {
                    if (!done.get()) jsonParse(item, webUrl)?.let {
                        if (done.compareAndSet(false, true)) winner[0] = it
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return winner[0]
    }

    /** 按类型分发（供自动轮换用） */
    fun run(item: ParseSources.Item, webUrl: String, flag: String): Hit? = when (item.type) {
        1 -> jsonParse(item, webUrl)
        2 -> jsonExt(item, webUrl)
        3 -> jsonMix(item, flag, webUrl)
        4 -> godJsonSide(webUrl)
        else -> null   // type0 要起 WebView，由 Activity 处理
    }

    // ---------------------------------------------------------------- 工具

    private fun hitFromResult(obj: JSONObject, item: ParseSources.Item, from: String): Hit? {
        val url = obj.optString("url", "")
        if (url.length <= MIN_URL_LEN) {
            Log.e(TAG, "[parse] $from 返回 url 太短(${url.length})：${url.take(60)}")
            return null
        }
        // 原版 checkResult(Result)：result.getParse()==1 → 还要再嗅探一次；我们简化成直接返回
        Log.e(TAG, "[parse] $from 成功 → ${url.take(80)}")
        return Hit(url, headerFrom(obj) ?: item.headers, from)
    }

    /** 原版 getHeader：只认 user-agent / referer / ua 三个键，值非 null 才收 */
    private fun headerFrom(obj: JSONObject): Map<String, String>? {
        val out = HashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val lower = k.lowercase()
            if (lower != "user-agent" && lower != "referer" && lower != "ua") continue
            val v = obj.optString(k, "")
            if (v.isBlank()) continue
            out[if (lower == "ua") "User-Agent" else k] = v
        }
        return out.ifEmpty { null }
    }

    /** 原版 Parse.extUrl：把 ext 经 URL-safe base64 塞进 URL 的 query 里 */
    private fun extUrlOf(p: ParseSources.Item): String {
        if (p.extJson.isBlank()) return p.url
        val index = p.url.indexOf("?")
        if (index == -1) return p.url
        val b64 = android.util.Base64.encodeToString(
            p.extJson.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
        return p.url.substring(0, index + 1) + "cat_ext=" + b64 + "&" + p.url.substring(index + 1)
    }

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.1.2)")
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "[parse] GET $url → $code")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            Log.e(TAG, "[parse] GET $url 异常 ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
