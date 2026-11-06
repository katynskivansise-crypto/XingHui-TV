package com.xinghui.tv;

import java.util.regex.Pattern;

/**
 * 视频地址嗅探判定（对齐原版 utils/Sniffer.java 的 SNIFFER 正则）。
 *
 * 原版还支持订阅顶层 `rules` 的 host 级 exclude/regex 覆盖，我们当前订阅没有
 * 可用的 rules 配置，所以只保留通用正则（与原版 fallback 分支一致）：
 *   return SNIFFER.matcher(url).find();
 *
 * 四个分支：
 *   1) 带 query 的媒体直链      xxx.m3u8?xxx
 *   2) 不带 query 的媒体直链    xxx.m3u8
 *   3) 字节系 video/tos 路径
 *   4) 字节系 obj/tos 路径
 * 中间用 ((?!http).){12,} 排除"嵌套了另一个 http 的跳转链接"，避免把解析页地址误判成视频。
 */
public final class Sniffer {

    private Sniffer() {}

    public static final Pattern AI_PUSH = Pattern.compile(
            "(http|https|rtmp|rtsp|smb|ftp|thunder|magnet|ed2k|mitv|tvbox-xg|jianpian|video):[^\\s]+",
            Pattern.MULTILINE);

    public static final Pattern SNIFFER = Pattern.compile(
            "http((?!http).){12,}?\\.(m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)\\?.*" +
            "|http((?!http).){12,}\\.(m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)" +
            "|http((?!http).)*?video/tos*" +
            "|http((?!http).)*?obj/tos*");

    /** 从一段页面文本里提取第一个媒体地址（原版 getUrl，用于 JS 注入结果） */
    public static String getUrl(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("{") && t.endsWith("}")) return t;
        if (t.contains("$")) return t;
        java.util.regex.Matcher m = AI_PUSH.matcher(t);
        return m.find() ? m.group(0) : t;
    }

    /** 判定一个 URL 是否为可播媒体（对齐原版 isVideoFormat 的 fallback 分支） */
    public static boolean isVideoFormat(String url) {
        if (url == null || url.isEmpty()) return false;
        // 解析页地址本身不是视频：这类页面把真实地址放在 query 里
        if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false;
        return SNIFFER.matcher(url).find();
    }
}
