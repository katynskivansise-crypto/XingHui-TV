package com.xinghui.tv

import java.util.regex.Pattern

/**
 * 视频地址嗅探判定（完整对齐原版 utils/Sniffer.java）。
 *
 * 判定顺序与原版 isVideoFormat 逐行一致：
 *   ① 取该 URL 命中规则（SnifferRules）的 exclude —— contains 命中即 false，正则命中即 false
 *   ② 取该规则的 regex —— contains 命中即 true，正则命中即 true
 *   ③ 明显是解析页/跳转页的（url=http / v=http / .html）→ false
 *   ④ 否则用通用 SNIFFER 正则
 *
 * 之前只保留第 ③④ 步，等于把订阅里 6 条嗅探白名单、11 条广告排除规则全丢了，
 * 火山/抖音/农民/七新这几个 host 的地址会被判成非视频，嗅探永远截不到。
 */
object Sniffer {

    val AI_PUSH: Pattern = Pattern.compile(
        "(http|https|rtmp|rtsp|smb|ftp|thunder|magnet|ed2k|mitv|tvbox-xg|jianpian|video):[^\\s]+",
        Pattern.MULTILINE
    )

    val SNIFFER: Pattern = Pattern.compile(
        "http((?!http).){12,}?\\.(m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)\\?.*" +
            "|http((?!http).){12,}\\.(m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)" +
            "|http((?!http).)*?video/tos*" +
            "|http((?!http).)*?obj/tos*"
    )

    val CLICKER: Pattern = Pattern.compile("\\[a=cr:(\\{.*?\\})\\/](.*?)\\[\\/a]")

    /** 从一段页面文本里提取第一个媒体地址（原版 getUrl，用于 JS 注入结果） */
    fun getUrl(text: String?): String {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return ""
        if (t.startsWith("{") && t.endsWith("}")) return t
        if (t.contains("$")) return t
        val m = AI_PUSH.matcher(t)
        return if (m.find()) m.group(0) else t
    }

    /** 判定一个 URL 是否为可播媒体（对齐原版 isVideoFormat 全流程） */
    fun isVideoFormat(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false

        // ① exclude：该 host 规则明确排除的（广告/统计域名在这里就被挡掉）
        for (e in SnifferRules.excludes(url)) if (url.contains(e)) return false
        for (e in SnifferRules.excludes(url)) {
            runCatching { if (Pattern.compile(e).matcher(url).find()) return false }
        }

        // ② regex 白名单：命中即认定视频（火山/抖音/农民/七新这类不走通用后缀的地址靠这一步）
        for (r in SnifferRules.regexes(url)) if (url.contains(r)) return true
        for (r in SnifferRules.regexes(url)) {
            runCatching { if (Pattern.compile(r).matcher(url).find()) return true }
        }

        // ③ 解析页/跳转页不是视频：真实地址在 query 里
        if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false

        // ④ 通用正则
        return SNIFFER.matcher(url).find()
    }
}
