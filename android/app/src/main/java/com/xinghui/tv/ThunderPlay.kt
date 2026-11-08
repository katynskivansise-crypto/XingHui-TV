package com.xinghui.tv

import android.os.SystemClock
import android.util.Log
import com.github.catvod.utils.Path
import com.github.catvod.utils.Util
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.parameter.GetTaskId
import com.xunlei.downloadlib.parameter.TorrentFileInfo
import java.io.File
import java.util.concurrent.Semaphore

/**
 * 迅雷下载器适配层。
 *
 * 对齐两个基准：
 *   ① 本仓库同源的 okcaptain/TV `player/extractor/Thunder.java`（经典版流程）
 *   ② FongMi/TV master 的 `player/extractor/Thunder.java` + `utils/TorrentUrl.java`（新版重构）
 *
 * 新版相对经典版的关键改进，这里全部吸收：
 *   - **中间态地址可还原**：经典版是 `magnet:///绝对路径?name=&index=`，路径一变（清缓存/换设备）就失效；
 *     新版是 `magnet://file?id=<原始磁力>&name=<文件>&index=<序号>`，只把「原始链接」当种子身份，
 *     播放时再用 Path.thunder(md5(id)) 找回 .torrent。旧格式仍然兼容（legacySelectionOf）。
 *   - **磁力解析全局串行**：native 库解析磁力是重操作，并发解析会把进程拖死；新版用
 *     `Semaphore(1, true)` 公平锁，这里照做。
 *   - **torrent 落在磁盘当缓存**：同一磁力第二次进详情不再重新解析元信息。
 *   - **路径穿越校验**：`name` 里出现 `../` 时会写到 thunder 目录外，新版用双重 startsWith 拦住。
 *   - **等待循环**：100 × 100ms，同时检查「总任务失败(taskStatus==3)」与「子任务失败」。
 *
 * 依赖：libxl_thunder_sdk.so + libxl_stat.so（arm64-v8a），以及 catvod 的 Init.context() / Path.thunder()。
 */
object ThunderPlay {

    private const val TAG = "XingHuiTV"

    /** 磁力元信息解析等待上限（经典版 5000ms；新版按 100×100ms 循环，这里取 10s 折中） */
    private const val META_TIMEOUT = 10_000L

    /** 单个文件起下载后的等待上限 */
    private const val TASK_TIMEOUT = 12_000L

    /** 中间态前缀（新版 TorrentUrl.PREFIX） */
    private const val SELECTION_PREFIX = "magnet://file?"

    /** 宽松磁力判定：兼容 `magnet:?` / `magnet:/?` / `magnet://?` 这些脏写法（新版 isRawMagnet） */
    private val RAW_MAGNET = Regex("^magnet:/*\\?", RegexOption.IGNORE_CASE)

    /** 迅雷体系总判（经典版 Parser.PATTERN） */
    private val THUNDER_PATTERN = Regex("^(magnet|thunder|ed2k):.*", RegexOption.IGNORE_CASE)

    /** 磁力/种子解析的全局公平锁（新版 PARSE_LOCK） */
    private val PARSE_LOCK = Semaphore(1, true)

    @Volatile
    private var inited = false

    /** 当前迅雷任务：stop() 时要交给 XLTaskHelper.deleteTask 才能清掉分片 */
    private var curTask: GetTaskId? = null

    /**
     * 迅雷 SDK 的初始化前提（原版靠 App.onCreate 里的 Init.set(base) 完成，我们没有 Application 类）。
     * XLDownloadManager 构造函数第一行 `this.context = Init.context();` 之后立刻 `context.getFilesDir()`，
     * 所以 Init.context() 为 null 会 NPE —— 表现为「点磁力/迅雷链接闪退」。
     */
    fun ensureInit(context: android.content.Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            runCatching {
                com.github.catvod.Init.set(context.applicationContext ?: context)
                Log.e(TAG, "[thunder] Init.context 就绪=${com.github.catvod.Init.context() != null}")
                inited = true
            }.onFailure { Log.e(TAG, "[thunder] Init.set 失败 ${it.message}") }
        }
    }

    // ==== 链接分类（新版 Thunder.Parser + TorrentUrl）====

    /** 是否属于迅雷体系（磁力 / 电驴 / thunder / 种子 / 新旧中间态） */
    fun match(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        return THUNDER_PATTERN.containsMatchIn(u) || isTorrent(u) || isSelection(u)
    }

    /** 宽松磁力：`magnet:?`、`magnet:/?`、`magnet://?` 都算 */
    fun isRawMagnet(url: String): Boolean = RAW_MAGNET.containsMatchIn(url.trim())

    /**
     * 种子文件判定（新版 isTorrent 的精髓）：
     *   - 先排除 magnet scheme（磁力里可能带 `.torrent` 字样，不能误判）
     *   - 取 `;` 之前那段（TVBox 的 `url;md5;xxx` 形式）
     *   - 看路径是否以 .torrent 结尾
     */
    fun isTorrent(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.substringBefore(':').lowercase() == "magnet") return false
        val path = u.substringBefore(';')
        val name = path.substringBefore('?').substringAfterLast('/').lowercase()
        return name.endsWith(".torrent")
    }

    /** 是否是新版中间态 `magnet://file?id=&name=&index=` */
    fun isSelection(url: String): Boolean =
        url.regionMatches(0, SELECTION_PREFIX, 0, SELECTION_PREFIX.length, ignoreCase = true)

    /** 是否「延迟解析」——这几类都别在播放前直接 fetch（新版 isDeferred） */
    fun isDeferred(url: String): Boolean {
        val u = url.trim()
        return isRawMagnet(u) || isSelection(u) || isTorrent(u) || u.startsWith("thunder:", true)
    }

    /** 生成中间态地址（新版 TorrentUrl.url()） */
    fun selectionUrl(id: String, name: String, index: Int): String =
        "magnet://file?id=" + android.net.Uri.encode(id) +
            "&name=" + android.net.Uri.encode(name) +
            "&index=$index"

    private class Selection(val torrent: File, val media: File, val index: Int)

    /**
     * 解析中间态地址，带路径穿越校验（新版 getSelection）。
     * 校验点：torrent 必须在 Path.thunder() 目录内；目标文件不能跑出种子所在目录。
     */
    private fun selectionOf(url: String): Selection? {
        if (!isSelection(url)) return null
        val uri = android.net.Uri.parse(url)
        val id = uri.getQueryParameter("id").orEmpty()
        val name = uri.getQueryParameter("name").orEmpty()
        val index = uri.getQueryParameter("index")?.toIntOrNull() ?: -1
        if (id.isEmpty() || name.isEmpty() || index < 0) throw IllegalStateException("无效的种子选集地址")
        val dir: File = Path.thunder(Util.md5(id))
        val torrent = locateTorrent(dir)
            ?: throw IllegalStateException("种子文件已丢失（${dir.name}），请重新解析磁力")
        val parent = torrent.parentFile ?: dir
        val root = Path.thunder().canonicalPath + File.separator
        if (!parent.canonicalPath.startsWith(root)) throw IllegalStateException("种子目录不在迅雷缓存内，已拒绝")
        val media = File(parent, name).canonicalFile
        if (!media.path.startsWith(parent.path + File.separator)) {
            throw IllegalStateException("种子内文件名非法（疑似路径穿越），已拒绝")
        }
        return Selection(torrent, media, index)
    }

    /** 旧版中间态 `magnet:///abs/path?name=&index=` 也兼容（新版 fromLegacy） */
    private fun legacySelectionOf(url: String): Selection? {
        if (!url.startsWith("magnet:///", true)) return null
        val uri = android.net.Uri.parse(url)
        val path = uri.path.orEmpty()
        if (path.isBlank()) return null
        val torrent = File(path)
        if (!torrent.exists()) return null
        val name = uri.getQueryParameter("name") ?: torrent.name
        val index = uri.getQueryParameter("index")?.toIntOrNull() ?: 0
        val parent = torrent.parentFile ?: Path.thunder("tmp")
        return Selection(torrent, File(parent, name), index)
    }

    /** 在种子目录里找 .torrent（新版固定叫 source.torrent，旧版沿用 XL 自己的命名） */
    private fun locateTorrent(dir: File): File? {
        if (!dir.isDirectory) return null
        val named = File(dir, "source.torrent")
        if (named.isFile && named.length() > 1024) return named
        return dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".torrent") && f.length() > 1024 }
            ?.maxByOrNull { it.length() }
    }

    // ==== 解析：链接 → 可播放地址 ====

    /**
     * 解析出可播放地址（阻塞，必须在子线程调用）。
     * @return `http://127.0.0.1:<port>/...` 直链
     */
    fun resolve(url: String): String {
        val u = url.trim()

        // ① 新版中间态：已选好的种子文件，直接起下载
        selectionOf(u)?.let { return startTorrentTask(it) }
        // ② 旧版中间态：兼容历史记录/收藏里存下的老格式
        legacySelectionOf(u)?.let { return startTorrentTask(it) }

        // ③ 原始磁力 / thunder / ed2k / 种子文件
        return resolveOrigin(u)
    }

    private fun resolveOrigin(url: String): String {
        // 磁力与 thunder 解析是重操作，全局串行（新版 PARSE_LOCK 只在 raw magnet / thunder 时加）
        val needLock = isRawMagnet(url) || url.startsWith("thunder:", true)
        if (needLock) PARSE_LOCK.acquire()
        try {
            val dir: File = Path.thunder(Util.md5(url))

            // 种子的 .torrent 已在磁盘 → 不再重新解析元信息，直接读文件列表（新版缓存语义）
            locateTorrent(dir)?.let { cached ->
                // 纯磁力：缓存已足够，不必再起一次磁力任务（重复 createBtMagnetTask 很浪费）
                if (!isRawMagnet(url)) {
                    val realUrl = runCatching { XLTaskHelper.get().parse(url, dir).realUrl.orEmpty() }
                        .getOrDefault("")
                    if (!isTorrent(realUrl) && !realUrl.startsWith("magnet", true) && realUrl.isNotBlank()) {
                        Log.e(TAG, "[thunder] 直链解析成功 ${realUrl.take(80)}")
                        return realUrl
                    }
                }
                val picked = pickMedia(cached) ?: throw IllegalStateException("种子内没有可播文件")
                Log.e(TAG, "[thunder] 命中种子缓存，直接选集 ${picked.getFileName()}")
                return startTorrentTask(
                    Selection(cached, File(cached.parentFile, picked.getFileName()), picked.getFileIndex())
                )
            }

            val taskId: GetTaskId = XLTaskHelper.get().parse(url, dir)
            curTask = taskId
            val realUrl = taskId.realUrl.orEmpty()

            // 迅雷链接 / ed2k：parse 已把真实地址解出来，直接返回
            if (!isTorrent(realUrl) && !realUrl.startsWith("magnet", true) && realUrl.isNotBlank()) {
                Log.e(TAG, "[thunder] 直链解析成功 ${realUrl.take(80)}")
                return realUrl
            }

            // 磁力：等元信息就绪（status == 2）
            var waited = 0L
            while (XLTaskHelper.get().getTaskInfo(taskId).taskStatus != 2 && waited < META_TIMEOUT) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("迅雷解析被取消")
                SystemClock.sleep(100)
                waited += 100
            }
            if (waited >= META_TIMEOUT) Log.e(TAG, "[thunder] 元信息超时，尝试读文件列表")

            val torrentFile: File = taskId.saveFile
            if (!torrentFile.exists() || torrentFile.length() < 1024) {
                throw IllegalStateException("磁力元信息未就绪（可能无做种或链接失效）")
            }
            val picked = pickMedia(torrentFile) ?: throw IllegalStateException("磁力里没有可播文件")
            Log.e(TAG, "[thunder] 选中文件 ${picked.getFileName()}")
            return startTorrentTask(
                Selection(torrentFile, File(torrentFile.parentFile, picked.getFileName()), picked.getFileIndex())
            )
        } finally {
            if (needLock) PARSE_LOCK.release()
        }
    }

    /**
     * 把一条磁力/种子展开成文件列表（对齐原版 Thunder.Parser.call，详情页阶段调用）。
     *
     * 与经典版不同：这里**不再自动替用户挑一个文件**，而是把全部文件都列出来，
     * 每个文件对应一条 `magnet://file?id=&name=&index=` 中间态地址（新版 fetchTorrent +
     * setResolvedEpisodes 的语义）。视频排前面，非视频（样本/字幕/图片）排后面但保留。
     *
     * @return 显示名 to 中间态地址；解析失败返回空列表
     */
    fun expand(url: String): List<Pair<String, String>> {
        val u = url.trim()
        if (u.isEmpty()) return emptyList()
        if (isSelection(u)) return emptyList()          // 已经展开过

        val needLock = isRawMagnet(u) || u.startsWith("thunder:", true)
        if (needLock) PARSE_LOCK.acquire()
        try {
            val dir: File = Path.thunder(Util.md5(u))

            // 缓存优先：.torrent 已在磁盘就不再请求元信息
            locateTorrent(dir)?.let { cached ->
                if (!isRawMagnet(u)) {
                    val realUrl = runCatching { XLTaskHelper.get().parse(u, dir).realUrl.orEmpty() }
                        .getOrDefault("")
                    if (!isTorrent(realUrl) && !realUrl.startsWith("magnet", true) && realUrl.isNotBlank()) {
                        return listOf(realUrl.substringAfterLast('/').ifBlank { "直链播放" } to realUrl)
                    }
                }
                return buildEpisodes(u, cached)
            }

            val taskId: GetTaskId = XLTaskHelper.get().parse(u, dir)
            curTask = taskId
            val realUrl = taskId.realUrl.orEmpty()

            // 迅雷/ed2k 直链：本身就是能播的地址，不需要展开
            if (!isTorrent(realUrl) && !realUrl.startsWith("magnet", true) && realUrl.isNotBlank()) {
                return listOf(realUrl.substringAfterLast('/').ifBlank { "直链播放" } to realUrl)
            }

            // 种子文件：先把 .torrent 抓下来
            if (isTorrent(u) || isTorrent(realUrl)) {
                val target = File(dir, "source.torrent")
                if (!target.exists() || target.length() < 1024) downloadTorrent(realUrl.ifBlank { u }, target)
                if (!target.exists() || target.length() < 1024) {
                    Log.e(TAG, "[thunder] 种子下载失败")
                    return emptyList()
                }
                return buildEpisodes(u, target)
            }

            // 磁力：等元信息
            var waited = 0L
            while (XLTaskHelper.get().getTaskInfo(taskId).taskStatus != 2 && waited < META_TIMEOUT) {
                if (Thread.currentThread().isInterrupted) return emptyList()
                SystemClock.sleep(100)
                waited += 100
            }
            val saveFile: File = taskId.saveFile
            if (!saveFile.exists() || saveFile.length() < 1024) {
                Log.e(TAG, "[thunder] 展开失败：元信息未就绪 ${saveFile.absolutePath}")
                return emptyList()
            }
            return buildEpisodes(u, saveFile)
        } catch (e: Throwable) {
            Log.e(TAG, "[thunder] 展开异常 ${e.message}")
            return emptyList()
        } finally {
            if (needLock) PARSE_LOCK.release()
        }
    }

    /** 读种子文件列表 → 显示名 + 中间态地址（视频优先排序） */
    private fun buildEpisodes(id: String, torrent: File): List<Pair<String, String>> {
        val medias: List<TorrentFileInfo> = runCatching {
            XLTaskHelper.get().getTorrentInfo(torrent).medias.orEmpty()
        }.getOrDefault(emptyList())
        if (medias.isEmpty()) {
            Log.e(TAG, "[thunder] 种子内无文件")
            return emptyList()
        }
        Log.e(TAG, "[thunder] 种子内文件 ${medias.size} 个")
        return medias
            .sortedByDescending { if (isVideo(it.getExt())) 1 else 0 }
            .map { it.getFileName() to selectionUrl(id, it.getFileName(), it.getFileIndex()) }
    }

    /** 选一个可播文件：优先视频，否则退回第一个 */
    private fun pickMedia(torrent: File): TorrentFileInfo? = runCatching {
        val medias = XLTaskHelper.get().getTorrentInfo(torrent).medias.orEmpty()
        medias.firstOrNull { isVideo(it.getExt()) } ?: medias.firstOrNull()
    }.getOrNull()

    // ==== 起下载 → 拿本地服务地址 ====

    /** 对齐原版 addTorrentTask：起下载 → 轮询子任务状态 → 取本地服务地址 */
    private fun startTorrentTask(sel: Selection): String {
        if (!sel.torrent.exists()) throw IllegalStateException("种子文件不存在：${sel.torrent.name}")
        val parent = sel.torrent.parentFile ?: Path.thunder("tmp")

        val sub = XLTaskHelper.get().addTorrentTask(sel.torrent, parent, sel.index)
        curTask = sub
        var waited = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("迅雷下载被取消")
            // 总任务失败（新版 waitTorrentTask 第一层检查）
            val info = runCatching { XLTaskHelper.get().getTaskInfo(sub) }.getOrNull()
            if (info != null && info.taskStatus == 3) throw IllegalStateException(info.errorMsg ?: "任务失败")
            // 子任务失败/已开始
            val subInfo = XLTaskHelper.get().getBtSubTaskInfo(sub, sel.index).mTaskInfo
            if (subInfo.mTaskStatus == 3) throw IllegalStateException(subInfo.getErrorMsg())
            if (subInfo.mTaskStatus != 0) {
                // 新版按「选区文件」构造本地地址，比 File(parent, name) 更稳
                val local = XLTaskHelper.get().getLocalUrl(sel.media)
                Log.e(TAG, "[thunder] 本地服务地址 ${local.take(80)}")
                return local
            }
            if (waited >= TASK_TIMEOUT) throw IllegalStateException("下载任务启动超时（可能无做种）")
            SystemClock.sleep(100)
            waited += 100
        }
    }

    /** .torrent 链接直接抓到本地（经典版 Download.create(url, saveFile).start()） */
    private fun downloadTorrent(url: String, target: File) {
        runCatching {
            target.parentFile?.mkdirs()
            val conn = java.net.URL(url).openConnection()
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.getInputStream().use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            Log.e(TAG, "[thunder] 种子下载完成 ${target.length()} B")
        }.onFailure { Log.e(TAG, "[thunder] 种子下载失败 ${it.message}") }
    }

    /**
     * 对齐原版 `Source.Extractor.stop()`（Thunder.java:59-64）：
     * `deleteTask` 会递归删掉 savePath 下的文件，**但保留 `.torrent`** —— 所以重播同一条磁力
     * 仍能命中 `locateTorrent` 的缓存，不用重新解析元信息；分片不会永久堆在 `thunder/` 里。
     *
     * 之前完全不调：任务一直跑、分片一直涨，退出后文件也没有任何回收口。
     */
    fun stop() {
        val t = curTask ?: return
        curTask = null
        runCatching { XLTaskHelper.get().deleteTask(t) }
            .onFailure { Log.e(TAG, "[thunder] deleteTask 失败 ${it.message}") }
    }

    /**
     * 这里刻意**不**提供 release()。
     * XLTaskHelper.release() 会把 manager 置 null 并调 native unInit，之后再 getManager() 会 new 一个
     * XLDownloadManager 重新 init —— 同一进程内 native 反复 init/unInit 是已知易崩路径。
     * 原版只在 App 退出时 exit()，我们没有必须释放的场景，索性不暴露。
     */

    fun isVideo(ext: String): Boolean =
        ext.lowercase() in setOf("mp4", "mkv", "avi", "ts", "m3u8", "flv", "mov", "wmv", "rmvb", "mpg", "mpeg", "webm")
}
