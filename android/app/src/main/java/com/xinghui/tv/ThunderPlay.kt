package com.xinghui.tv

import android.os.SystemClock
import android.util.Log
import com.github.catvod.utils.Path
import com.github.catvod.utils.Util
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.parameter.GetTaskId
import com.xunlei.downloadlib.parameter.TorrentFileInfo
import java.io.File

/**
 * 迅雷下载器适配层（对齐原版 player/extractor/Thunder.java）。
 *
 * 原版流程分两段，这里完整照搬：
 *   ① 磁力解析：XLTaskHelper.parse(magnet, dir) → 落到 .torrent 文件 → getTorrentInfo 列文件
 *                每个文件给出 `magnet://<本地文件>?name=&index=` 的中间态地址（不是 http）
 *   ② 起下载：    addTorrentTask(torrentFile, dir, index) → 轮询 getBtSubTaskInfo 直到 mTaskStatus != 0
 *                再用 getLocalUrl(真实文件) 拿回 `http://127.0.0.1:<port>/...` —— 由 libxl_thunder_sdk.so
 *                内置的 HTTP 服务提供，ExoPlayer 直接可播
 *
 * 所以我们不用自己写 BT 引擎，也不用管端口；只需要把这两段串起来。
 *
 * 依赖：libxl_thunder_sdk.so + libxl_stat.so（arm64-v8a，6.2MB），
 *      以及 catvod 的 Init.context() / Path.thunder() / Prefers（三项本工程都已有）。
 */
object ThunderPlay {

    private const val TAG = "XingHuiTV"

    /** 磁力元信息解析等待上限（原版 Parser 用 5000ms） */
    private const val META_TIMEOUT = 5000L

    /** 单个文件起下载后的等待上限，避免无做种时一直卡住 */
    private const val TASK_TIMEOUT = 12000L

    /** 判断是否属于迅雷体系（对齐原版 Thunder.Parser.match 的正则 + .torrent 判定） */
    fun match(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        val lower = u.lowercase()
        if (lower.startsWith("magnet:") || lower.startsWith("ed2k:") || lower.startsWith("thunder:")) return true
        // 原版 isTorrent：非 magnet 且分号前以 .torrent 结尾
        return !lower.startsWith("magnet") && u.substringBefore(';').lowercase().endsWith(".torrent")
    }

    /**
     * 解析出可播放地址（阻塞）。必须在子线程调用。
     * @return http://127.0.0.1:<port>/... 这样的直链
     */
    fun resolve(url: String): String {
        // 分支一：中间态 magnet:///abs/path.torrent?name=&index=
        // 这是 TorrentFileInfo.getPlayUrl() 生成的，原版走 addTorrentTask（直接起下载，不再解析元信息）
        if (url.startsWith("magnet://", true)) return addTorrentTask(url)

        val dir: File = Path.thunder(Util.md5(url))
        val taskId: GetTaskId = XLTaskHelper.get().parse(url, dir)

        // 迅雷链接 / ed2k：parse 已经把真实地址解出来了，直接返回（原版 fetch() 同分支）
        val realUrl = taskId.realUrl.orEmpty()
        if (!url.startsWith("magnet:", true) && !realUrl.startsWith("magnet:", true)) {
            Log.e(TAG, "[thunder] 直链解析成功 ${realUrl.take(80)}")
            return realUrl
        }

        // 磁力：等元信息解析完成（getTaskStatus()==2 表示就绪）
        var waited = 0L
        while (XLTaskHelper.get().getTaskInfo(taskId).taskStatus != 2 && waited < META_TIMEOUT) {
            SystemClock.sleep(200)
            waited += 200
        }
        if (waited >= META_TIMEOUT) Log.e(TAG, "[thunder] 元信息超时，继续尝试读取文件列表")

        val torrentFile: File = taskId.saveFile
        if (!torrentFile.exists() || torrentFile.length() < 1024) {
            throw IllegalStateException("磁力元信息未就绪（可能无做种或链接失效）")
        }

        val medias: List<TorrentFileInfo> = runCatching {
            XLTaskHelper.get().getTorrentInfo(torrentFile).medias.orEmpty()
        }.getOrElse { throw IllegalStateException("读取磁力文件列表失败：${it.message}") }

        if (medias.isEmpty()) throw IllegalStateException("磁力里没有可播文件")

        // 优先挑视频文件；原版是全列出来让用户选，我们这里取第一个视频，找不到再退回第一个
        val pick = medias.firstOrNull { isVideo(it.getExt()) } ?: medias.first()
        Log.e(TAG, "[thunder] 选中文件 ${pick.getFileName()} size=${pick.getSize()}")

        // 直接复用中间态那条路径，逻辑只写一份
        val mid = "magnet://" + pick.getFile().absolutePath + "?name=" + pick.getFileName() + "&index=" + pick.getFileIndex()
        return addTorrentTask(mid)
    }

    /** 对齐原版 Thunder.addTorrentTask：起下载 → 轮询子任务状态 → 取本地服务地址 */
    private fun addTorrentTask(midUrl: String): String {
        val uri = android.net.Uri.parse(midUrl)
        val path = uri.path.orEmpty()
        if (path.isBlank()) throw IllegalStateException("磁力中间态地址缺少文件路径：${midUrl.take(80)}")
        val torrent = File(path)
        if (!torrent.exists()) throw IllegalStateException("种子文件不存在：${torrent.name}")

        val name = uri.getQueryParameter("name") ?: torrent.name
        val index = uri.getQueryParameter("index")?.toIntOrNull() ?: 0
        val parent = torrent.parentFile ?: Path.thunder("tmp")

        val sub = XLTaskHelper.get().addTorrentTask(torrent, parent, index)
        var waited = 0L
        while (true) {
            val info = XLTaskHelper.get().getBtSubTaskInfo(sub, index).mTaskInfo
            // 原版语义：3 = 失败；0 = 还在准备；其余（1/2）= 已开始，可以给本地地址
            if (info.mTaskStatus == 3) throw IllegalStateException(info.getErrorMsg())
            if (info.mTaskStatus != 0) {
                val local = XLTaskHelper.get().getLocalUrl(File(parent, name))
                Log.e(TAG, "[thunder] 本地服务地址 ${local.take(80)}")
                return local
            }
            if (waited >= TASK_TIMEOUT) throw IllegalStateException("下载任务启动超时（可能无做种）")
            SystemClock.sleep(300)
            waited += 300
        }
    }

    /** 释放任务与 native 句柄（对齐原版 Thunder.stop / exit） */
    fun stop() {
        runCatching { XLTaskHelper.get().release() }
            .onFailure { Log.e(TAG, "[thunder] release 失败", it) }
    }

    private fun isVideo(ext: String): Boolean =
        ext in setOf("mp4", "mkv", "avi", "ts", "m3u8", "flv", "mov", "wmv", "rmvb", "mpg", "mpeg", "webm")
}
