package com.xinghui.tv

/**
 * 本地 scheme → 本地 HTTP 服务的地址重写。
 *
 * 对齐原版 utils/UrlUtil.convert(String)：
 * ```
 * clan://   → fixUrl(clan→file) 再走 file 规则
 * local://  → http://127.0.0.1:9978/
 * assets:// → http://127.0.0.1:9978/
 * file://   → http://127.0.0.1:9978/file/
 * proxy://  → http://127.0.0.1:9978/proxy?
 * ```
 * spider 返回的防盗链地址、内置资源、海报本地路径都用这套前缀，
 * 不转换就会被播放器/图片加载器当成非法地址直接失败。
 */
object UrlConvert {

    private const val PORT = 9978
    private const val BASE = "http://127.0.0.1:$PORT"

    /** 与 ProxyServer 监听端口保持一致 */
    fun base(): String = BASE

    fun convert(url: String): String {
        if (url.isBlank()) return url
        val lower = url.lowercase()
        return when {
            lower.startsWith("proxy://") -> BASE + "/proxy?" + url.substring(8)
            lower.startsWith("local://") -> BASE + "/" + url.substring(8)
            lower.startsWith("assets://") -> BASE + "/" + url.substring(9)
            lower.startsWith("file://") -> BASE + "/file/" + url.substring(7)
            lower.startsWith("clan://") -> convert(fixUrl("file://" + url.substring(7)))
            else -> url
        }
    }

    /** 对齐原版 UrlUtil.fixUrl：clan 站的路径常带 /localhost/ 前缀，需先抹掉再当文件路径用 */
    private fun fixUrl(url: String): String {
        var u = url
        if (u.contains("/localhost/")) u = u.replace("/localhost/", "/")
        return u
    }
}
