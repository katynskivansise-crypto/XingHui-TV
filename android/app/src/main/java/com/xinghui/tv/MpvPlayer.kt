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
    /** 初始化期抛出的原因：那时 onFailed 还没接上，先存着，等 play() 时报出来 */
    private var initError: String? = null
    private val logFile: java.io.File by lazy { java.io.File(activity.filesDir, "mpv.log") }
    private var pendingStartMs = 0L
    private var pendingSeekMs = 0L
    private var paused = true
    private var eofReached = false
    /**
     * 应用是否在后台（由 PlayActivity 的 onPause/onResume 置位）。
     *
     * 为什么需要它：切后台时 Activity 的 SurfaceView 会被系统销毁，
     * surfaceDestroyed 把 vo 置 null、detachSurface 之后，mpv 会短暂掉进 idle-active=true。
     * END_FILE 的异常判定若只看 idle-active，就会把「切后台」误判成「播放中断」，
     * 进而触发上层静默自动换线路 —— 用户看到的就是「切回来卡住/换了台」。
     */
    private var backgrounded = false
    /**
     * resume() 在 surface 还没回来时先记账。
     *
     * onResume 常常早于 surfaceCreated：那时 vo 还是 null，直接下发 pause=false
     * 会让 mpv 在「无视频输出」状态下开始播放，部分机型因此触发 END_FILE。
     * 所以 surface 不在时只记账，等 surfaceCreated 里补发。
     */
    private var pendingResume = false
    private var desiredSpeed = 1.0f
    private var desiredScale = 0
    private var audioManager: android.media.AudioManager? = null
    private var focusListener: android.media.AudioManager.OnAudioFocusChangeListener? = null

    init {
        // 这里**不要**调 setZOrderMediaOverlay(true)。
        // 原版全工程零命中；而它的语义是把 surface 放到「窗口之下的独立媒体层」，
        // 弹窗（AlertDialog / BottomSheet 都是独立 window）出现后这层的 z-order 在
        // 部分老 ROM（T1 的 Amlogic 7.1）上不会恢复 —— 表现为关掉菜单后 mpv 永久黑屏但声音正常。
        // 不设它时 surface 走默认的「窗口内 punch-through」，控制条是窗口里的 View，
        // 布局里 mpvView 声明在前、controlBar 在后，控制条照样盖在上面。
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
            // 让 mpv 把自己的日志写到私有目录。VO 建不起来时平台侧到底报了什么，
            // 只有它自己知道；只报「中断」等于永远查不到真因。
            //
            // 级别用 w（warning + error）而不是 v（verbose）：ytdl_hook 失败、VO 建不起来、
            // 解码器报错这些都在 w 里，全量 v 只会把每次播放写成几十 MB，
            // 在只剩 32MB 预算的盒子上纯属白占盘。
            // 另外 log-file 每次 init 是覆盖写，不会跨次累积；下面的上限是防单次播放写太大。
            runCatching {
                if (logFile.exists() && logFile.length() > 2L * 1024 * 1024) logFile.delete()
            }
            `is`.xyz.mpv.MPVLib.setOptionString("log-file", logFile.absolutePath)
            `is`.xyz.mpv.MPVLib.setOptionString("msg-level", "all=w")
            `is`.xyz.mpv.MPVLib.setOptionString("idle", "yes")
            `is`.xyz.mpv.MPVLib.setOptionString("vo", "gpu")
            // 官方 MPVView.kt:42 无条件把显示器实测刷新率喂给 mpv（display-fps-override）。
            // 不设的话 mpv 自己探，探错就会按 60Hz 去排 50Hz 的片源，表现为轻微抖动 / 隔几秒一顿。
            runCatching {
                val fps = activity.windowManager.defaultDisplay.refreshRate
                if (fps > 0f) {
                    `is`.xyz.mpv.MPVLib.setOptionString("display-fps-override", fps.toString())
                }
            }
            // ★ 官方 mpv-android MPVView.kt:92-96 的必备项，之前一直漏掉。
            //   缺 gpu-context=android 时 mpv 会去用默认的 gpu 上下文（桌面平台的），
            //   在 Android 上创建 GL 上下文直接失败 → VO 初始化失败 → loadfile 之后立刻 END_FILE，
            //   idle-active 回到 true，我们这边看到的提示就是「mpv 播放中断」。
            //   这是 mpv 在安卓上起播即失败最常见的原因，和片源、网络、解码器都无关。
            `is`.xyz.mpv.MPVLib.setOptionString("gpu-context", "android")
            `is`.xyz.mpv.MPVLib.setOptionString("opengl-es", "yes")
            // ao 不显式指定时可能一个音频输出都找不到，结果是没声音或整条播放失败
            `is`.xyz.mpv.MPVLib.setOptionString("ao", "audiotrack,opensles")
            // 官方 MPVView.kt:97 无条件设。不设时安卓会把这条音频流当成普通/通知类，
            // 表现为遥控器音量键调的是铃声而不是播放音量、HDMI/蓝牙音频路由也可能不对。
            `is`.xyz.mpv.MPVLib.setOptionString("audio-set-media-role", "yes")
            // 官方 MPVView.kt:101 无条件设：蓝光盘里同一部片的多个 edition 合并成单轨，
            // 否则遇到这类源会多出一条轨道、选轨界面里看着莫名其妙。点播源基本遇不到，设了无害。
            `is`.xyz.mpv.MPVLib.setOptionString("flatten-editions", "yes")
            // 只让这些编码走硬解，其余走软解，避免 mediacodec 对不认识的编码直接报错
            `is`.xyz.mpv.MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            // 硬解开关是 mpv 自己的偏好（官方 key hardware_decoding），和 EXO 的软/硬解各存各的
            val mpvSet = Settings(activity)
            `is`.xyz.mpv.MPVLib.setOptionString("hwdec", if (mpvSet.mpvHwdec) "mediacodec" else "no")
            `is`.xyz.mpv.MPVLib.setOptionString("keep-open", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("pause", "yes")
            `is`.xyz.mpv.MPVLib.setOptionString("osc", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("input-default-bindings", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("input-vo-keyboard", "no")
            // ★★ 真正的根因：mpv 默认 ytdl=auto，遇到没有可识别扩展名的 http 直链
            //   会先走 ytdl_hook 去调 youtube-dl。安卓上没有 youtube-dl，
            //   于是 [ytdl_hook] youtube-dl failed → [cplayer] finished playback, loading failed，
            //   文件根本没加载成功，VO 自然一个都建不起来（这就是 vo=空 cfg=false）。
            //   关掉它，让 mpv 直接把 URL 交给 lavf demuxer。
            `is`.xyz.mpv.MPVLib.setOptionString("ytdl", "no")
            `is`.xyz.mpv.MPVLib.setOptionString("cache", "yes")
            val cacheMb = if (mpvSet.mpvCacheMb in 32..64) mpvSet.mpvCacheMb else 64
            // 用纯字节数而不是 "64MiB" 后缀：官方 MPVView.kt:104 就是 ${cacheMegs * 1024 * 1024}，
            // 后缀写法依赖 mpv 的单位解析，一旦不被识别会静默忽略整条选项、缓存其实没生效。
            val cacheBytes = cacheMb * 1024 * 1024
            `is`.xyz.mpv.MPVLib.setOptionString("demuxer-max-bytes", "$cacheBytes")
            // 官方 MPVView.kt:104-105 是 max-bytes 与 max-back-bytes 一起设的。
            // 只设前向缓存时，往回拖/倒退播放会重新走网络，表现为卡一下。
            `is`.xyz.mpv.MPVLib.setOptionString("demuxer-max-back-bytes", "$cacheBytes")
            // 官方 MPVView.kt:114：播放位置由宿主自己管（我们记在 HistoryStore），
            // 不要让 mpv 自己也写一份，否则退出时会互相覆盖。
            `is`.xyz.mpv.MPVLib.setOptionString("save-position-on-quit", "no")
            // 官方 MPVView.kt:98-99 用 tls-verify=yes + filesDir/cacert.pem。
            // 我们没有这个 ca bundle，留 yes 会让 https 片源在证书链不全时直接失败，
            // 电视盒子场景（片源多变、证书不规范）取 no 更实用；只连片源，不涉及登录凭据。
            `is`.xyz.mpv.MPVLib.setOptionString("tls-verify", "no")
            // 官方 MPVView.kt:23 是无条件设 profile=fast（关掉 gradfun/deband 这类高成本滤镜）。
            // 这里做成设置页开关：T1 这类低端芯片默认开，想拿画质细节时可以关掉。
            if (mpvSet.mpvFastProfile) {
                `is`.xyz.mpv.MPVLib.setOptionString("profile", "fast")
            }
            // 缩放算法（官方 MPVView.kt:48-59 的 video_scale / video_downscale / video_tscale）。
            // 官方只在偏好非空时下发，空就交给 mpv 默认；这里保持同样的语义。
            // 注意：1080p 片源在 1080p 输出上不触发缩放，这三个值对那种场景完全没作用；
            // 真正生效的是 4K→1080p（dscale）和 720p→1080p（scale）。
            mpvSet.mpvScale.takeIf { it.isNotBlank() }?.let {
                `is`.xyz.mpv.MPVLib.setOptionString("scale", it)
            }
            mpvSet.mpvDscale.takeIf { it.isNotBlank() }?.let {
                `is`.xyz.mpv.MPVLib.setOptionString("dscale", it)
            }
            mpvSet.mpvTscale.takeIf { it.isNotBlank() }?.let {
                `is`.xyz.mpv.MPVLib.setOptionString("tscale", it)
            }
            // 去色带（官方 video_debanding）：gradfun 走 vf，gpu 走 deband 滤镜
            when (mpvSet.mpvDeband) {
                1 -> `is`.xyz.mpv.MPVLib.setOptionString("vf", "gradfun=radius=12")
                2 -> `is`.xyz.mpv.MPVLib.setOptionString("deband", "yes")
            }
            // 插帧（官方 video_interpolation）：必须配 display-resample 才有意义
            if (mpvSet.mpvInterpolation) {
                `is`.xyz.mpv.MPVLib.setOptionString("video-sync", "display-resample")
                `is`.xyz.mpv.MPVLib.setOptionString("interpolation", "yes")
            }
            // 快速解码（官方 video_fastdecode）
            if (mpvSet.mpvFastDecode) {
                `is`.xyz.mpv.MPVLib.setOptionString("vd-lavc-fast", "yes")
                `is`.xyz.mpv.MPVLib.setOptionString("vd-lavc-skiploopfilter", "nonkey")
            }
            // 中文环境：音轨/字幕优先中文，避免默认选中英文字幕
            `is`.xyz.mpv.MPVLib.setOptionString("alang", "zh,chi,zho,eng")
            `is`.xyz.mpv.MPVLib.setOptionString("slang", "zh,chi,zho,eng")
            // 死链不要无限等待（EXO 侧靠超时判定，mpv 这边也要有）
            `is`.xyz.mpv.MPVLib.setOptionString("network-timeout", "15")
            val ua = Settings(activity).ua
            if (ua.isNotBlank()) `is`.xyz.mpv.MPVLib.setOptionString("user-agent", ua)
            `is`.xyz.mpv.MPVLib.init()
            // 照官方 BaseMPVView.initialize：init 之后立刻关掉 force-window。
            // 官方原注释「would crash before the surface is attached」——
            // surface 还没到时若 force-window 开着，mpv 会试图建一个没有 surface 的窗口。
            // 之后 surfaceCreated 会把它重新打开。
            runCatching { `is`.xyz.mpv.MPVLib.setOptionString("force-window", "no") }
            created = true
            `is`.xyz.mpv.MPVLib.addObserver(this)
            observeAll()
            inited = true
            Log.i(TAG, "[mpv] 内嵌内核已初始化")
        }.onFailure {
            val msg = it.message ?: it.javaClass.simpleName
            initError = msg
            Log.e(TAG, "[mpv] 初始化失败（so 未打进包或 ABI 不匹配）：$msg", it)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!inited) initWithOptions()
        // 初始化失败时绝不能继续调 native，否则 libmpv 内部空指针会直接 SEGV
        if (!inited) return
        runCatching {
            `is`.xyz.mpv.MPVLib.attachSurface(holder.surface)
            // 照官方 BaseMPVView.surfaceCreated 的三步：attach → force-window=yes → 重开 vo。
            // surfaceDestroyed 里把 vo 置成 null 了，这里必须重开，否则 vo 一直指向
            // 那个已经销毁的 surface —— 画面永久黑、声音照走。
            `is`.xyz.mpv.MPVLib.setOptionString("force-window", "yes")
            `is`.xyz.mpv.MPVLib.setPropertyString("vo", "gpu")
        }
        // attach 完立刻把真实尺寸报给 mpv：只等 surfaceChanged 的话，
        // 有些机型在首次是全屏尺寸、尺寸没变化时不会回调，mpv 就拿着 0 尺寸建渲染目标
        runCatching {
            val w = holder.surfaceFrame.width()
            val h = holder.surfaceFrame.height()
            if (w > 0 && h > 0) {
                `is`.xyz.mpv.MPVLib.setPropertyString("android-surface-size", "${w}x${h}")
            }
        }
        surfaceReady = true
        // surface 晚于 play 到达时补一次起播（playPending 保证不会和 play() 里的那次重复 loadfile）
        if (playPending && pendingUrl.isNotBlank()) {
            playPending = false
            doPlay(pendingUrl)
        } else if (pendingResume && pendingUrl.isNotBlank()) {
            // 回前台时 onResume 早于 surfaceCreated：resume() 当时只记了账，
            // 这里 surface 已经 attach 好、vo 也重开了，才真正下发 pause=false。
            pendingResume = false
            paused = false
            runCatching { `is`.xyz.mpv.MPVLib.setPropertyBoolean("pause", false) }
        } else if (pendingUrl.isNotBlank() && !backgrounded) {
            // surface 换了一块新的，但 mpv 因为 vo 曾被置 null 已经掉到 idle（文件没在播），
            // 只重开 vo 是黑屏。这种情况按最后一次地址重新 loadfile，位置沿用记下来的进度。
            val wasIdle = runCatching {
                `is`.xyz.mpv.MPVLib.getPropertyBoolean("idle-active")
            }.getOrNull() ?: false
            if (wasIdle) {
                pendingSeekMs = pendingStartMs
                doPlay(pendingUrl)
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!inited) return
        // mpv 要知道 surface 的实际尺寸才能建对渲染目标；不报的话在某些设备上会渲染成 0 尺寸
        runCatching {
            `is`.xyz.mpv.MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // release() 之后 SurfaceView 还会回调一次，此时 handle 已销毁，再调 native 会崩
        if (!inited) return
        // 照官方 BaseMPVView.surfaceDestroyed：先关视频输出、关 force-window，再 detach。
        // 只 detach 不关 vo 的话，mpv 的 vo 子系统仍持有旧 surface 句柄，
        // 下次 surfaceCreated 再 attach 也不会真正重建 —— 这就是「关掉菜单后永久黑屏」的机制。
        runCatching {
            `is`.xyz.mpv.MPVLib.setPropertyString("vo", "null")
            `is`.xyz.mpv.MPVLib.setOptionString("force-window", "no")
            `is`.xyz.mpv.MPVLib.detachSurface()
        }
    }

    fun release() {
        abandonAudioFocus()
        if (!inited) return
        // 先关闸再动 native：destroy 期间 surfaceDestroyed 和延迟的 idle 探测都可能进来
        inited = false
        surfaceReady = false
        playPending = false
        pendingResume = false
        backgrounded = false
        // 清掉已播地址：否则释放后若 surface 再次创建，会把旧地址重新 loadfile
        pendingUrl = ""
        // 照官方 BaseMPVView.destroy：先摘掉 surface 回调再 destroy，
        // 否则释放后 SurfaceView 的可视性变化还会回调进来，把已经 destroy 的 libmpv 再 init 一遍
        runCatching { surfaceView.holder.removeCallback(this) }
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
        if (!inited) {
            onFailed?.invoke("mpv 初始化失败｜${initError ?: "无错误码"}")
            return
        }
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

    /**
     * 从 mpv 自己的日志里捞最后几条关键行。
     * VO/EGL/解码失败的信息只在这里有，拿到它才能判断到底是渲染后端没起来还是别的原因。
     */
    private fun tailOfMpvLog(): String = runCatching {
        if (!logFile.exists()) return "无日志"
        val all = logFile.readText()
        val lines = all.split("\n").filter { it.isNotBlank() }
        val key = lines.filter {
            val l = it.lowercase()
            l.contains("error") || l.contains("fail") || l.contains("could not") ||
                l.contains("vo/") || l.contains("gpu") || l.contains("egl") ||
                l.contains("surface") || l.contains("context")
        }.takeLast(2)
        val picked = if (key.isNotEmpty()) key else lines.takeLast(2)
        picked.joinToString(" / ") { it.trim().take(90) }.ifBlank { "日志为空" }
    }.getOrElse { "读日志失败" }

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
        // surface 还没回来：此时 vo 是 null（surfaceDestroyed 置的），
        // 直接解暂停会让 mpv 在无视频输出下开播，部分机型借此触发 END_FILE → 误判中断。
        // 只记账，等 surfaceCreated 里 attach 完再补发。
        if (!surfaceReady) {
            pendingResume = true
            return
        }
        pendingResume = false
        runCatching { `is`.xyz.mpv.MPVLib.setPropertyBoolean("pause", false) }
    }

    /** 由宿主 Activity 的 onPause/onResume 置位：后台期间不把 idle 判定成播放中断 */
    fun setBackground(value: Boolean) {
        backgrounded = value
        if (!value) return
        // 进后台时把记账清掉，避免把「上一次切后台的 resume」带到下一次 surface 重建
        pendingResume = false
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
                    // 后台期间 / surface 已销毁时一律不判异常结束：
                    // 切后台 -> surfaceDestroyed 把 vo 置 null 并 detachSurface，
                    // mpv 会短暂 idle-active=true；这时判「中断」会触发上层静默自动换线路，
                    // 用户切回来看到的就是「卡住 / 换了台」而不是继续播。
                    if (backgrounded || !surfaceReady) {
                        Log.i(TAG, "[mpv] 后台或无 surface，跳过 END_FILE 异常判定")
                        return@postDelayed
                    }
                    val idle = runCatching {
                        `is`.xyz.mpv.MPVLib.getPropertyBoolean("idle-active")
                    }.getOrNull() ?: false
                    // 换集时新文件会立刻接管，idle-active 回到 false；真的失败才停在这里
                    if (idle && !eofReached) {
                        // 把 mpv 自己的状态带出来：只报「播放中断」等于把真因盖住，
                        // 修完还是中断时根本没法判断是「视频输出没建起来」还是「解码/网络失败」。
                        //   current-vo       有值（通常是 gpu）说明渲染后端起来了
                        //   vo-configured    false 说明 VO 压根没配成功
                        val voName = runCatching {
                            `is`.xyz.mpv.MPVLib.getPropertyString("current-vo")
                        }.getOrNull()
                        val voReady = runCatching {
                            `is`.xyz.mpv.MPVLib.getPropertyBoolean("vo-configured")
                        }.getOrNull()
                        val ver = runCatching {
                            `is`.xyz.mpv.MPVLib.getPropertyString("mpv-version")
                        }.getOrNull()
                        Log.e(TAG, "[mpv] 异常结束 vo=${voName ?: "空"} cfg=$voReady ver=$ver url=$pendingUrl")
                        onFailed?.invoke(
                            "mpv 中断｜vo=${voName?.ifBlank { "空" } ?: "空"} cfg=${voReady ?: false}" +
                                "｜${tailOfMpvLog()}"
                        )
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
