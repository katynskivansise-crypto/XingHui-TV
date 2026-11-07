package com.xinghui.tv

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * 订阅规则表（对齐原版 bean/Rule + VodConfig.getRules() / getAds()）。
 *
 * 原版 isVideoFormat 不是「一条通用正则走到底」，而是：
 *   1) 先按 URL 的 host（以及 query 里 url= 的 host）匹配订阅 rules 里的某条 Rule；
 *   2) 用该 Rule 的 exclude 排除；
 *   3) 用该 Rule 的 regex 白名单直接判定为视频；
 *   4) 只有没匹配到任何 Rule 时才退回 Sniffer.SNIFFER 通用正则。
 *
 * 实测当前订阅（qist/tvbox jsm.json）顶层确实带 19 条 rules 与 ~60 条 ads：
 *   - 6 条「嗅探」类给出白名单 regex（火山 item_id= / 抖音 is_play_url= / 农民 video/tos/cn / 七新 m3u8?pt=m3u8）
 *   - 11 条广告类给排除 regex
 *   - 2 条「点击」类给注入脚本（夜市 yeslivetv 的 vjs-big-play-button / 毛驴 maolvys 的 swal 确认按钮）
 * 之前 Sniffer 完全没用这张表，所以这几类地址都被判成「不是视频」，嗅探必然空手而归。
 */
object SnifferRules {

    private const val TAG = "XingHuiTV"

    class Rule {
        var name: String = ""
        var hosts: List<String> = emptyList()
        var regex: List<String> = emptyList()
        var script: List<String> = emptyList()
        var exclude: List<String> = emptyList()
    }

    @Volatile
    private var rules: List<Rule> = emptyList()

    @Volatile
    private var ads: List<String> = emptyList()

    fun clear() {
        rules = emptyList()
        ads = emptyList()
    }

    /** 从订阅 JSON 原文读取顶层 rules / ads（也兼容 video.rules 的嵌套写法） */
    @Synchronized
    fun load(subscriptionText: String?) {
        if (subscriptionText.isNullOrBlank()) return
        try {
            val root = JSONObject(subscriptionText)
            val holder = root.optJSONObject("video")?.takeIf { it.has("sites") } ?: root

            val parsed = ArrayList<Rule>()
            holder.optJSONArray("rules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val r = Rule()
                    r.name = o.optString("name", "")
                    r.hosts = listOf(o.optJSONArray("hosts"))
                    r.regex = listOf(o.optJSONArray("regex"))
                    r.script = listOf(o.optJSONArray("script"))
                    r.exclude = listOf(o.optJSONArray("exclude"))
                    if (r.hosts.isNotEmpty()) parsed.add(r)
                }
            }
            rules = parsed

            val adList = ArrayList<String>()
            holder.optJSONArray("ads")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotEmpty()) adList.add(s)
                }
            }
            ads = adList

            Log.e(TAG, "[rules] 载入 rules=${rules.size} ads=${ads.size}")
        } catch (e: Throwable) {
            Log.e(TAG, "[rules] 解析失败 ${e.message}")
        }
    }

    private fun listOf(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** 广告域名（原版 CustomWebView.isAd：contains 或正则命中都算） */
    fun isAd(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        for (ad in ads) {
            if (host.contains(ad)) return true
            runCatching { if (Pattern.compile(ad).matcher(host).find()) return true }
        }
        return false
    }

    /** 命中 URL 对应的 Rule（没命中返回 null） */
    fun ruleOf(url: String?): Rule? {
        if (url.isNullOrEmpty()) return null
        val host = hostOf(url)
        val queryHost = hostOf(queryParam(url, "url"))
        val join = "$host,$queryHost"
        for (r in rules) {
            for (h in r.hosts) {
                if (containOrMatch(join, h)) return r
            }
        }
        return null
    }

    fun excludes(url: String?): List<String> = ruleOf(url)?.exclude ?: emptyList()

    fun regexes(url: String?): List<String> = ruleOf(url)?.regex ?: emptyList()

    /** 该 URL 适用规则里的注入脚本（原版 onPageFinished → evaluate） */
    fun scripts(url: String?): List<String> = ruleOf(url)?.script ?: emptyList()

    fun hostOf(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return runCatching { URI(url).host.orEmpty() }.getOrDefault("")
    }

    private fun queryParam(url: String, key: String): String {
        return runCatching {
            val q = URI(url).rawQuery ?: return ""
            for (p in q.split("&")) {
                val i = p.indexOf('=')
                if (i < 0) continue
                if (p.substring(0, i) == key) {
                    return URLDecoder.decode(p.substring(i + 1), "UTF-8")
                }
            }
            ""
        }.getOrDefault("")
    }

    fun containOrMatch(text: String?, pattern: String?): Boolean {
        if (text == null || pattern.isNullOrEmpty()) return false
        if (text.contains(pattern)) return true
        return runCatching { Pattern.compile(pattern).matcher(text).find() }.getOrDefault(false)
    }
}
