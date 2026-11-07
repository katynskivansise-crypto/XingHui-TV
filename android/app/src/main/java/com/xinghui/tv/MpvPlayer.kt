package com.xinghui.tv

import android.app.Activity
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Locale

/**
 * 内嵌 mpv 播放器封装。
 *
 * 目的：把 mpv 从「Intent 拉起外部 mpv-android」改成「本进程内 libmpv + SurfaceView」，
 * 这样播放页的控制条（暂停/继续、进度、倍速、缩放、上下集）可以直接控它，和 EXO 一致。
 *
 * 用的就是 mpv-android 官方 arm64 构建（libmpv / libplayer / ffmpeg 全套 so），
 * JNI 符号绑死 is.xyz.mpv.MPVLib，所以 MPVLib.kt 的 package 不能改。
 */
class MpvPlayer(
    private val activity: Activity,
    private val surfaceView: SurfaceView
) : SurfaceHolder.Callback, `is`.xyz.mpv.MPVLib.EventObserver {

    companion object {
        private const val TAG = "XingHuiTV"
    }

    var onLoaded: (() -> Unit)? = null
    var onEndFile: (() -> Unit)? = null
    var onTimePos: ((Long) -> Unit)? = null
    var onDuration: ((Long) -> Unit)? = null
    var onPauseChanged: ((Boolean) -> Unit)? = null
    /** 非正常结束（加载失败 / 解码失败 / 网络断）：上层据此走自动换源 */
    var onFailed: ((String) -> Unit)? = null

    private var inited = false
    private var surfaceReady = false
    private var pendingUrl = ""
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingSubs: List<String> = emptyList()
    /** play() 已下发但还没真正执行过 loadfile；surfaceCreated 与 play() 都会尝试补播，用它去重 */
    private var playPending = false
    /** mpv_create 是否已成功：init() 失败重试时不能重复 create，否则会多实例泄漏 */
    private var created = false
    private var pendingStartMs = 0L
    private var pendingSeekMs = 0L
    private var paused = true
    private var eofReached = false
    private var desiredSpeed = 1.0f
    private var desiredScale = 0
    private var audioManager: android.media.AudioManager? = null
    private var focusListener: android.media.AudioManager.OnAudioFocusChangeListener? = null

    init {
        // 关键：mpv 用独立 surface，不设 MediaOverlay 会把控制条盖住
        runCatching { surfaceView.setZOrderMediaOverlay(true) }
        surfaceView.holder.addCallback(this)
        // addCallback 时 surface 若已经有效，系统不会再补发 surfaceCreated，这里手动走一遍
        val h = surfaceView.holder
        if (h.surface != null && h.surface.isValid) surfaceCreated(h)
    }

    // ---------------------------------------------------------------- 生命周期

    /** 必须在 UI 线程、且在 Surface 可用之后调用 */
    private fun initWithOptions() {
        if (inited) return
        if (android.os.Build.VERSION.SDK_INT < 23) {
            Log.e(TAG, "[mpv] 系统 API ${android.os.Build.VERSION.SDK_INT} < 23，内嵌 mpv 不可用")
            return
        }
        runCatching {
            if (created) {
                `is`.xyz.mpv.MPVLib.addObserver(this)
                observeAll()
                inited = true
                Log.i(TAG, "[mpv] 内嵌内核复用")
                return
            }
            `is`.xyz.mpv.MPVLib.create(activity.applicationContext)
            `is`.xyz.mpv.MPVLib.setOptionString("config", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("idle", "yes")
            `is`.xyz.mpv.MPVLib.setOptionString("vo", "gpu")
            `is`.xyz.mpv.MPVLib.setOptionString("hwdec", "mediacodec")
            `is`.xyz.mpv.MPVLib.setOptionString("keep-open", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("pause", "yes")
            `is`.xyz.mpv.MPVLib.setOptionString("osc", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("input-default-bindings", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("input-vo-keyboard", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("cache", "yes")
            `is`.xyz.mpv.MPVLib.setOptionString("demuxer-max-bytes", "64MiB")
            // 中文环境：音轨/字幕优先中文，避免默认选中英文字幕
            `is`.xyz.mpv.MPVLib.setOptionString("alang", "zh,chi,zho,eng")
            `is`.xyz.mpv.MPVLib.setOptionString("slang", "zh,chi,zho,eng")
            // 死链不要无限等待（EXO 侧靠超时判定，mpv 这边也要有）
            `is`.xyz.mpv.MPVLib.setOptionString("network-timeout", "15")
            val ua = Settings(activity).ua
            if (ua.isNotBlank()) `is`.xyz.mpv.MPVLib.setOptionString("user-agent", ua)
            `is`.xyz.mpv.MPVLib.init()
            created = true
            `is`.xyz.mpv.MPVLib.addObserver(this)
            observeAll()
            inited = true
            Log.i(TAG, "[mpv] 内嵌内核已初始化")
        }.onFailure {
            Log.e(TAG, "[mpv] 初始化失败（so 未打进包或 ABI 不匹配）", it)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!inited) initWithOptions()
        // 初始化失败时绝不能继续调 native，否则 libmpv 内部空指针会直接 SEGV
        if (!inited) return
        runCatching { `is`.xyz.mpv.MPVLib.attachSurface(holder.surface) }
        surfaceReady = true
        // surface 晚于 play 到达时补一次起播（playPending 保证不会和 play() 里的那次重复 loadfile）
        if (playPending && pendingUrl.isNotBlank()) {
            playPending = false
            doPlay(pendingUrl)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // release() 之后 SurfaceView 还会回调一次，此时 handle 已销毁，再调 native 会崩
        if (!inited) return
        runCatching { `is`.xyz.mpv.MPVLib.detachSurface() }
    }

    fun release() {
        abandonAudioFocus()
        if (!inited) return
        // 先关闸再动 native：destroy 期间 surfaceDestroyed 和延迟的 idle 探测都可能进来
        inited = false
        surfaceReady = false
        playPending = false
        runCatching { `is`.xyz.mpv.MPVLib.removeObserver(this) }
        runCatching { `is`.xyz.mpv.MPVLib.command(arrayOf("stop")) }
        runCatching { `is`.xyz.mpv.MPVLib.destroy() }
        created = false
        Log.i(TAG, "[mpv] 内核已释放")
    }

    // ---------------------------------------------------------------- 音频焦点

    /** mpv 自己不申请 AudioFocus，不补会和后台播放器混音 */
    private fun requestAudioFocus() {
        val mgr = activity.getSystemService(android.content.Context.AUDIO_SERVICE)
                as? android.media.AudioManager ?: return
        audioManager = mgr
        if (focusListener == null) {
            focusListener = object : android.media.AudioManager.OnAudioFocusChangeListener {
                override fun onAudioFocusChange(focusChange: Int) {
                    when (focusChange) {
                        android.media.AudioManager.AUDIOFOCUS_LOSS,
                        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
                    }
                }
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            mgr.requestAudioFocus(
                focusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(focusListener)
        }
    }

    // ---------------------------------------------------------------- 播放

    fun play(url: String, headers: Map<String, String>, startMs: Long = 0, subs: List<String> = emptyList()) {
        requestAudioFocus()
        // addCallback 时 surface 已经有效的话，系统不会再补发 surfaceCreated，
        // 那样 inited/surfaceReady 会一直停在 false，起播就永远等下去
        if (!inited) initWithOptions()
        pendingUrl = url
        pendingHeaders = headers
        pendingStartMs = startMs
        pendingSubs = subs
        playPending = true
        if (surfaceReady) {
            playPending = false
            doPlay(url)
        }
    }

    private fun observeAll() {
        `is`.xyz.mpv.MPVLib.observeProperty("time-pos", `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        `is`.xyz.mpv.MPVLib.observeProperty("duration", `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        `is`.xyz.mpv.MPVLib.observeProperty("pause", `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        // 用 eof-reached 判"真的播完"：END_FILE 在换文件/stop 时也会来，拿它判会误跳下一集
        `is`.xyz.mpv.MPVLib.observeProperty("eof-reached", `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    private fun doPlay(url: String) {
        if (!inited) return
        runCatching {
            val headerLine = pendingHeaders.entries
                .filter { it.key.isNotBlank() }
                .joinToString(",") { "${it.key}: ${it.value}" }
            `is`.xyz.mpv.MPVLib.setPropertyString("http-header-fields", headerLine)
            // 起播前的定位必须等 FILE_LOADED：文件还没加载时 seek absolute 会直接失败
            pendingSeekMs = pendingStartMs
            `is`.xyz.mpv.MPVLib.command(arrayOf("loadfile", url, "replace"))
            paused = false
            `is`.xyz.mpv.MPVLib.setPropertyBoolean("pause", false)
            Log.i(TAG, "[mpv] 起播 $url")
        }.onFailure { Log.e(TAG, "[mpv] 起播失败", it) }
    }

    // ---------------------------------------------------------------- 控制（与 EXO 同一套语义）

    /** 用缓存的 pause/eof，不在这里同步调 native（mpv_get_property 在 seek 期间会阻塞 UI） */
    fun isPlaying(): Boolean = inited && !paused && !eofReached


    fun pause() {
        if (!inited) return
        paused = true
        runCatching { `is`.xyz.mpv.MPVLib.setPropertyBoolean("pause", true) }
    }

    fun resume() {
        if (!inited) return
        if (eofReached) {
            eofReached = false
            runCatching { `is`.xyz.mpv.MPVLib.command(arrayOf("seek", "0", "absolute+exact")) }
        }
        paused = false
        runCatching { `is`.xyz.mpv.MPVLib.setPropertyBoolean("pause", false) }
    }

    fun positionMs(): Long {
        if (!inited) return 0
        return runCatching {
            val v = `is`.xyz.mpv.MPVLib.getPropertyDouble("time-pos") ?: 0.0
            (v * 1000).toLong()
        }.getOrDefault(0)
    }

    fun durationMs(): Long {
        if (!inited) return 0
        return runCatching {
            val v = `is`.xyz.mpv.MPVLib.getPropertyDouble("duration") ?: 0.0
            (v * 1000).toLong()
        }.getOrDefault(0)
    }

    fun seekToMs(ms: Long) {
        if (!inited) return
        val sec = String.format(Locale.US, "%.3f", (ms.coerceAtLeast(0)) / 1000.0)
        runCatching { `is`.xyz.mpv.MPVLib.command(arrayOf("seek", sec, "absolute+exact")) }
    }

    fun setSpeed(speed: Float) {
        desiredSpeed = speed
        if (!inited) return
        runCatching { `is`.xyz.mpv.MPVLib.setPropertyDouble("speed", speed.toDouble()) }
    }

    /** 缩放 5 档，对应原版 select_scale：原始 / 16:9 / 4:3 / 填充 / 裁剪 */
    fun applyScale(idx: Int) {
        desiredScale = idx
        if (!inited) return
        runCatching {
            when (idx) {
                // 原始：保持片源宽高比
                0 -> {
                    `is`.xyz.mpv.MPVLib.setPropertyString("video-aspect-override", "no")
                    `is`.xyz.mpv.MPVLib.setPropertyBoolean("keepaspect", true)
                    `is`.xyz.mpv.MPVLib.setPropertyDouble("panscan", 0.0)
                }
                1 -> {
                    `is`.xyz.mpv.MPVLib.setPropertyString("video-aspect-override", "16:9")
                    `is`.xyz.mpv.MPVLib.setPropertyBoolean("keepaspect", true)
                    `is`.xyz.mpv.MPVLib.setPropertyDouble("panscan", 0.0)
                }
                2 -> {
                    `is`.xyz.mpv.MPVLib.setPropertyString("video-aspect-override", "4:3")
                    `is`.xyz.mpv.MPVLib.setPropertyBoolean("keepaspect", true)
                    `is`.xyz.mpv.MPVLib.setPropertyDouble("panscan", 0.0)
                }
                // 填充：拉伸铺满，不保持比例
                3 -> {
                    `is`.xyz.mpv.MPVLib.setPropertyString("video-aspect-override", "no")
                    `is`.xyz.mpv.MPVLib.setPropertyBoolean("keepaspect", false)
                    `is`.xyz.mpv.MPVLib.setPropertyDouble("panscan", 0.0)
                }
                // 裁剪：保持比例并拉满，超出部分裁掉
                else -> {
                    `is`.xyz.mpv.MPVLib.setPropertyString("video-aspect-override", "no")
                    `is`.xyz.mpv.MPVLib.setPropertyBoolean("keepaspect", true)
                    `is`.xyz.mpv.MPVLib.setPropertyDouble("panscan", 1.0)
                }
            }
        }
    }

    data class MpvTrackInfo(val id: Int, val label: String, val selected: Boolean)

    /**
     * 枚举轨道，kind = "video" / "audio" / "sub"。
     * mpv 的 track-list 本身是 node，但 track-list/N/{type,id,lang,title,codec,...}
     * 这些子路径能直接读成基础类型，所以不必引入 node API。
     */
    fun tracks(kind: String): List<MpvTrackInfo> {
        if (!inited) return emptyList()
        return runCatching {
            val out = ArrayList<MpvTrackInfo>()
            val n = `is`.xyz.mpv.MPVLib.getPropertyInt("track-list/count") ?: 0
            for (i in 0 until n) {
                if (`is`.xyz.mpv.MPVLib.getPropertyString("track-list/$i/type") != kind) continue
                val id = `is`.xyz.mpv.MPVLib.getPropertyInt("track-list/$i/id") ?: continue
                val selected = `is`.xyz.mpv.MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false
                val parts = ArrayList<String>()
                `is`.xyz.mpv.MPVLib.getPropertyString("track-list/$i/title")
                    ?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                `is`.xyz.mpv.MPVLib.getPropertyString("track-list/$i/lang")
                    ?.takeIf { it.isNotBlank() && it != "und" }?.let { parts.add(it) }
                if (kind == "video") {
                    val h = `is`.xyz.mpv.MPVLib.getPropertyInt("track-list/$i/demux-h")
                    if (h != null && h > 0) parts.add("${h}p")
                }
                if (kind == "audio") {
                    val ch = `is`.xyz.mpv.MPVLib.getPropertyInt("track-list/$i/demux-channel-count")
                    if (ch != null && ch > 0) parts.add("${ch}ch")
                }
                `is`.xyz.mpv.MPVLib.getPropertyString("track-list/$i/codec")
                    ?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                if (`is`.xyz.mpv.MPVLib.getPropertyBoolean("track-list/$i/external") == true) parts.add("外挂")
                out.add(MpvTrackInfo(id, if (parts.isEmpty()) "轨道 $id" else parts.joinToString(" · "), selected))
            }
            out
        }.getOrElse { emptyList() }
    }

    /** 选中某一路：aid / vid / sid 传轨道 id */
    fun selectTrack(kind: String, id: Int) {
        if (!inited) return
        runCatching { `is`.xyz.mpv.MPVLib.setPropertyString(propOf(kind), id.toString()) }
    }

    /** 关掉某一类轨道（aid=no / sid=no）。视轨不给这个入口，关了就是黑屏 */
    fun disableTracks(kind: String) {
        if (!inited) return
        runCatching { `is`.xyz.mpv.MPVLib.setPropertyString(propOf(kind), "no") }
    }

    private fun propOf(kind: String) = when (kind) {
        "audio" -> "aid"
        "sub" -> "sid"
        else -> "vid"
    }

    /** 循环切换该路轨（mpv 的 cycle audio/sub/video），返回切换后序号用于提示 */
    fun cycleTrack(which: String): String {
        if (!inited) return "0"
        return runCatching {
            `is`.xyz.mpv.MPVLib.command(arrayOf("cycle", when (which) {
                "audio" -> "audio"
                "sub" -> "sub"
                else -> "video"
            }))
            when (which) {
                "audio" -> `is`.xyz.mpv.MPVLib.getPropertyInt("aid") ?: 0
                "sub" -> `is`.xyz.mpv.MPVLib.getPropertyInt("sid") ?: 0
                else -> `is`.xyz.mpv.MPVLib.getPropertyInt("vid") ?: 0
            }.toString()
        }.getOrDefault("0")
    }

    /** 硬解切换：对齐 EXO 的软解 / 硬解两档 */
    fun setHardwareDecode(hard: Boolean) {
        if (!inited) return
        runCatching {
            `is`.xyz.mpv.MPVLib.setPropertyString("hwdec", if (hard) "mediacodec" else "no")
        }
        // hwdec 是加载期选项：只对之后加载的文件生效，当前文件不重新加载就切不过去。
        // 跟 EXO 那边"重建播放器再回到原位置"的做法对齐。
        val cur = pendingUrl
        if (cur.isBlank()) return
        val at = positionMs()
        pendingStartMs = at
        pendingSeekMs = at
        runCatching { `is`.xyz.mpv.MPVLib.command(arrayOf("loadfile", cur, "replace")) }
        Log.i(TAG, "[mpv] 切换解码：hard=$hard，重载当前位置 ${at}ms")
    }

    fun toggleSubVisible() {
        if (!inited) return
        runCatching {
            val vis = `is`.xyz.mpv.MPVLib.getPropertyBoolean("sub-visibility") ?: true
            `is`.xyz.mpv.MPVLib.setPropertyBoolean("sub-visibility", !vis)
        }
    }

    // ---------------------------------------------------------------- 事件（mpv 线程 → 主线程）

    private fun ui(block: () -> Unit) {
        activity.runOnUiThread { runCatching { block() } }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            `is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> ui {
                eofReached = false
                // 起播前的 speed / scale 可能不生效，文件加载完成后补下发
                runCatching { `is`.xyz.mpv.MPVLib.setPropertyDouble("speed", desiredSpeed.toDouble()) }
                applyScale(desiredScale)
                // 外挂字幕必须等文件加载后再 sub-add，提前下发会被丢掉
                for ((i, sub) in pendingSubs.withIndex()) {
                    runCatching {
                        `is`.xyz.mpv.MPVLib.command(arrayOf("sub-add", sub, "select", "字幕${i + 1}"))
                    }
                }
                val startMs = pendingSeekMs
                pendingSeekMs = 0
                if (startMs > 1000) {
                    val sec = String.format(Locale.US, "%.3f", startMs / 1000.0)
                    runCatching { `is`.xyz.mpv.MPVLib.command(arrayOf("seek", sec, "absolute+exact")) }
                }
                onLoaded?.invoke()
            }
            // END_FILE 在 loadfile replace / stop 时也会触发，播完判定交给 eof-reached；
            // 但"加载失败/解码失败"也只有这个事件能感知，所以延迟 800ms 看是否真的掉回 idle
            `is`.xyz.mpv.MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!inited || pendingUrl.isBlank()) return@postDelayed
                    val idle = runCatching {
                        `is`.xyz.mpv.MPVLib.getPropertyBoolean("idle-active")
                    }.getOrNull() ?: false
                    // 换集时新文件会立刻接管，idle-active 回到 false；真的失败才停在这里
                    if (idle && !eofReached) {
                        Log.e(TAG, "[mpv] 播放异常结束（idle，未到 EOF）")
                        onFailed?.invoke("mpv 播放中断")
                    }
                }, 800)
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> ui { onTimePos?.invoke((value * 1000).toLong()) }
            "duration" -> ui { onDuration?.invoke((value * 1000).toLong()) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                paused = value
                ui { onPauseChanged?.invoke(value) }
            }
            "eof-reached" -> {
                eofReached = value
                // 只有真的播到末尾才回调，换集时的 false 不触发
                if (value) ui { onEndFile?.invoke() }
            }
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit
    override fun eventProperty(property: String, value: String) = Unit
}
