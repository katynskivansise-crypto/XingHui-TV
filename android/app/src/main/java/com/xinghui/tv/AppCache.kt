package com.xinghui.tv

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 设置页「缓存」这一行的统计与清理。
 *
 * **原版的做法**（`FileUtil.getCacheSize` / `clearCache`）：
 * ```
 * Path.clear(Path.cache())                          // 直接删掉整个 getCacheDir()
 * getFolderSize(Path.cache())                       // 递归算大小
 * ```
 * 原版能这么干，是因为它所有可再生数据都规规矩矩放在 `Path.cache()`（= `getCacheDir()`）下面：
 * `cache/js` `cache/py` `cache/jar` `cache/doh` `cache/exo` `cache/epg` `cache/jpa` `cache/thunder` `cache/restore`。
 *
 * **我们不能照抄**，因为我们的可再生数据大部分写在 `filesDir`：
 * `filesDir/imgcache`（海报）、`filesDir/dexcache/`（spider jar + dex 优化产物）、`filesDir/jpa`（荐片临时）。
 * 只扫 `cacheDir` 就永远是 0 B —— 这就是设置页显示「0 B」的直接原因。
 *
 * 同时 `filesDir` 里还有**绝对不能删**的东西：
 * `filesDir/Pizazz/config.json`（csp 站的 base 域名等配置，删了分类页会整片空掉）。
 *
 * 所以这里改成**白名单**：只统计/删除「删了能自动重建」的目录，其余一律不碰。
 */
object AppCache {

    private const val TAG = "XingHuiTV"

    /** 可再生的目录/文件（删了会自动重下或重建） */
    private fun regenerable(ctx: Context): List<File?> = listOf(
        File(ctx.filesDir, "imgcache"),   // 海报磁盘缓存，AsyncImageLoader 自管上限 100MB
        // 荐片 P2P 引擎临时目录：跟随「缓存目录」设置（原版 Path.jpa() = thunderCache()/jpa）
        runCatching { File(com.github.catvod.utils.Path.thunderCache(), "jpa") }.getOrNull(),
        // 迅雷下载/分片目录（Path.thunder()）。缓存目录改到外部时它就不在 cacheDir 里了，
        // 必须单独列出来，否则统计不到、清理也清不掉。
        runCatching { File(com.github.catvod.utils.Path.thunderCache(), "thunder") }.getOrNull(),
        File(ctx.filesDir, "jpa"),        // 历史残留：旧版硬编码在 filesDir，升级后旧数据还在
        File(ctx.filesDir, "dexcache"),   // spider jar 的 dex 优化产物（对齐原版 cache/jar）
        File(ctx.filesDir, "mpv.log"),    // mpv 日志（msg-level=all=w 也会缓慢增长）
        File(ctx.filesDir, "crash.log"),  // 上次崩溃堆栈
        ctx.cacheDir,                     // 含 spider jar 的 dex 优化产物、迅雷 Path.thunder()/jpa
        ctx.externalCacheDir,             // 系统外置缓存
    )

    /**
     * `filesDir` 根目录下的 spider_<md5>.*（历史残留，新版已迁进 dexcache）。
     * 不限定扩展名：旧版本把 dex 优化目录设成了 filesDir 根，那边会留下 `spider_<md5>.dex`，
     * 只匹配 `.jar` 会把这些残留永远留在盘上。
     */
    private fun spiderFiles(ctx: Context): List<File> =
        ctx.filesDir.listFiles { f -> f.isFile && f.name.startsWith("spider_") }?.toList() ?: emptyList()

    fun size(ctx: Context): Long =
        regenerable(ctx).sumOf { folderSize(it) } + spiderFiles(ctx).sumOf { it.length() }

    /**
     * 清理并返回释放的字节数。
     * 注意：不清 `filesDir/Pizazz`（配置）、不清数据库与 shared_prefs、不清订阅原文。
     */
    fun clear(ctx: Context): Long {
        val freed = size(ctx)
        regenerable(ctx).forEach { runCatching { it?.deleteRecursively() } }
        spiderFiles(ctx).forEach { runCatching { it.delete() } }
        Log.i(TAG, "[cache] 已清理 ${format(freed)}")
        return freed
    }

    private fun folderSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        return f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 逐字复刻原版 FileUtil.byteCountToDisplaySize（含「0 KB」与「bytes」这两个原文案） */
    fun format(size: Long): String {
        if (size <= 0) return "0 KB"
        val units = arrayOf("bytes", "KB", "MB", "GB", "TB")
        var digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups > units.size - 1) digitGroups = units.size - 1
        val df = java.text.DecimalFormat("#,##0.#")
        return df.format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
