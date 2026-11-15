package com.xinghui.tv

import android.app.Activity
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.xinghui.tv.mpv.MpvCore
import java.util.Locale

/**
 * 内嵌 mpv 播放器封装。
 *
 * 目的：把 mpv 从「Intent 拉起外部 mpv-android」改成「本进程内 libmpv + 与 EXO 共用的 SurfaceView」，
 * 这样播放页的控制条（暂停/继续、进度、倍速、缩放、上下集）可以直接控它，和 EXO 一致。
 *
 * 用的就是 mpv-android 官方 arm64 构建（libmpv / libplayer / ffmpeg 全套 so），
 * JNI 符号绑死 is.xyz.mpv.MPVLib，所以 MPVLib.kt 的 package 不能改。
 */
class MpvPlayer(
    private val activity: Activity,
    /**
     * 画面层宿主：与 EXO 共用的那一块。
     *
     * 两种形态都支持，由布局里的 app:surface_type 决定：
     *   - SurfaceView（surface_type=surface_view）：走 SurfaceHolder，画面是 window 上挖的一个洞，
     *     合成层单独一块，窗口重绘就可能牵动它；
     *   - TextureView（surface_type=texture_view）：走 SurfaceTexture，画面就是普通视图内容，
     *     跟控制条在同一个图层里合成，窗口怎么重绘都不会出现"画面层空白一帧"。
     * 后者是纯官方能力（media3 自带该属性、mpv 官方 JNI 也接受 SurfaceTexture 转出来的 Surface），
     * 不需要任何分叉库。切换只改布局里的一个属性，代码两条路径都保留。
     */
    private val host: android.view.View
) : SimpleBasePlayer(android.os.Looper.getMainLooper()), MpvCore.EventObserver {

    private val core = MpvCore(activity)

    companion object {
        /**
         * TrackGroup 的类型由 Format 的 sampleMimeType 推断，所以每类给一个能正确推断出
         * VIDEO / AUDIO / TEXT 的通用 mime；精确编解码仍放在 Format.codecs。
         */
        private const val MIME_VIDEO_GENERIC = "video/mp4"
        private const val MIME_AUDIO_GENERIC = "audio/mp4"
        private const val MIME_TEXT_GENERIC = "application/x-subrip"
        private const val TAG = "XingHuiTV"
        private const val UID = "xinghui-mpv"
        @Volatile
        var liveCount: Int = 0
            private set

        /**
         * 当前活着的那个实例。
         *
         * MPVLib 的句柄是**静态单例**：同进程里第二个 MpvPlayer 一旦 create，
         * 静态句柄就指到新实例上，旧实例还在渲染、还在出声，但任何 command/property
         * 都打到新实例（它没 loadfile，是空转状态）—— 表现就是
         * 「按暂停没反应、左右键不跳、回主页还在播」。
         * 真机 /debug 里抓到过同一秒两次「内核已释放」，就是两个实例并存。
         * 所以这里做单实例闸门：新实例创建前，先把旧的释放掉。
         */
        @Volatile
        private var current: MpvPlayer? = null
    }

    var onLoaded: (() -> Unit)? = null
    var onEndFile: (() -> Unit)? = null
    var onTimePos: ((Long) -> Unit)? = null
    var onDuration: ((Long) -> Unit)? = null
    var onPauseChanged: ((Boolean) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    private var inited = false
    private var surfaceReady = false
    private var pendingUrl = ""
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingSubs: List<String> = emptyList()
    private var playPending = false
    private var created = false
    private var initError: String? = null
    private val logFile: java.io.File by lazy { java.io.File(activity.filesDir, "mpv.log") }
    private var pendingStartMs = 0L
    private var pendingStartPaused = false
    private var pendingSeekMs = 0L
    private var paused = true
    private var eofReached = false
    private var currentItem: MediaItem? = null
    // ---- media3 状态快照（唯一事实来源）----
    //
    // getState() 是 media3 定义的"状态查询契约"，会被上层按需高频读取。
    // 它必须只读内存：不打 JNI、不做字符串解析、不跑正则。
    // 事实的唯一来源是 mpv 的属性回调 —— 回调把值推进下面这几个字段，
    // getState() 只负责把它们翻译成 media3 的 State。
    // 这是职责划分问题，与设备性能无关。
    private var snapPosMs = 0L
    private var snapDurMs = C.TIME_UNSET
    private var snapSpeed = 1f
    private var snapVideoW = 0
    private var snapVideoH = 0
    private var playlistData: List<MediaItemData>? = null
    private var playlistForItem: MediaItem? = null
    private var lastStatePushAt = 0L
    /**
     * 应用是否在后台（由 PlayActivity 的 onPause/onResume 置位）。
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

    private val holderCallback: SurfaceHolder.Callback by lazy { object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = onSurfaceCreated(holder)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
            onSurfaceChanged(holder, format, width, height)
        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceDestroyed(holder)
    } }

    private val textureWatch: Runnable by lazy { object : Runnable {
        override fun run() {
            val tv = host as? android.view.TextureView ?: return
            if (!inited) return
            val st = tv.surfaceTexture
            if (tv.isAvailable && st != null) {
                if (!surfaceReady) {
                    val s = ownedSurface?.takeIf { it.isValid } ?: Surface(st).also { ownedSurface = it }
                    MpvDebug.log("[mpv] textureReady ${tv.width}x${tv.height}（TextureView 形态）")
                    attachSurface(s, tv.width, tv.height, first = true)
                }
            } else if (surfaceReady) {
                MpvDebug.log("[mpv] textureLost（TextureView 被回收）")
                surfaceReady = false
                runCatching {
                    core.setPropertyString("vo", "null")
                    core.setOptionString("force-window", "no")
                    core.detachSurface()
                }
                runCatching { ownedSurface?.release() }
                ownedSurface = null
            }
            tv.postDelayed(this, 120)
        }
    } }

    init {
        // 这里**不要**调 setZOrderMediaOverlay(true)。
        // 原版全工程零命中；而它的语义是把 surface 放到「窗口之下的独立媒体层」，
        // 弹窗（AlertDialog / BottomSheet 都是独立 window）出现后这层的 z-order 在
        // 部分老 ROM（T1 的 Amlogic 7.1）上不会恢复 —— 表现为关掉菜单后 mpv 永久黑屏但声音正常。
        // 不设它时 surface 走默认的「窗口内 punch-through」，控制条是窗口里的 View，
        // 布局里 mpvView 声明在前、controlBar 在后，控制条照样盖在上面。
        // 单实例闸门：先放掉残留的旧内核，再接管静态句柄
        current?.let { old ->
            if (old !== this) {
                MpvDebug.log("[mpv] 发现残留实例，先释放（旧 live=${liveCount}）")
                runCatching { old.release() }
            }
        }
        current = this
        liveCount++
        when (val v = host) {
            is SurfaceView -> {
                MpvDebug.log("[mpv] 构造完成，holder 主权交由 VideoHost（本实例不再自注册回调）")
            }
            is android.view.TextureView -> watchTexture()
            else -> MpvDebug.log("[mpv] 画面层宿主类型不支持：${v.javaClass.simpleName}")
        }
    }

    /**
     * 把这块 Surface 的主权交给 VideoHost，由它统一转发 holder 事件。
     *
     * 必须在 MpvPlayer 构造之后、play() 之前调用。重复调用是安全的：
     * VideoHost.takeNative 会覆盖上一次的回调，不会叠加。
     */
    fun bindToHost(host: VideoHost) {
        host.takeNative(
            attach = { h -> runCatching { onSurfaceCreated(h) } },
            release = { runCatching { onSurfaceDestroyedForHost() } },
        )
        MpvDebug.log("[mpv] holder 主权已交给 VideoHost，owner=NATIVE")
    }

    private fun onSurfaceDestroyedForHost() {
        MpvDebug.log("[mpv] VideoHost 转发 surfaceDestroyed inited=$inited")
        surfaceReady = false
        if (inited) runCatching { core.detachSurface() }
    }

    // ---------------------------------------------------------------- 生命周期

    private fun initWithOptions() {
        if (inited) return
        if (android.os.Build.VERSION.SDK_INT < 23) {
            Log.e(TAG, "[mpv] 系统 API ${android.os.Build.VERSION.SDK_INT} < 23，内嵌 mpv 不可用")
            return
        }
        runCatching {
            if (created) {
                core.addObserver(this)
                observeAll()
                inited = true
                Log.i(TAG, "[mpv] 内嵌内核复用")
                return
            }
            core.create()
            core.setOptionString("config", "no")
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
            core.setOptionString("log-file", logFile.absolutePath)
            core.setOptionString("msg-level", "all=w")
            core.setOptionString("idle", "yes")
            core.setOptionString("vo", "gpu")
            core.setOptionString("gpu-context", "android")
            core.setOptionString("opengl-es", "yes")
            // ao 不显式指定时可能一个音频输出都找不到，结果是没声音或整条播放失败
            // 色彩链路：
            //   8bit 屏（T1 / API<26）：dither 到 8bit，HDR 片源由 mpv 做 tonemap，
            //     否则直接截断会过曝、暗部糊成一片。
            //   10bit 屏（API>=26 + GLES3）：目标直通 1010102，
            //     target-colorspace-hint 让 mpv 把色彩空间/亮度信息交给系统合成器。
            // 两者是运行时选的，不是编译期二选一 —— 同一份代码在不同机器上行为都正确。
            core.setOptionString("dither-depth", "auto")
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                core.setOptionString("target-colorspace-hint", "yes")
                core.setOptionString("hdr-compute-peak", "yes")
            } else {
                core.setOptionString("target-colorspace-hint", "no")
            }
            core.setOptionString("ao", "audiotrack,opensles")
            // 官方 MPVView.kt 无条件设。不设时安卓会把这条音频流当成普通/通知类，
            // 表现为遥控器音量键调的是铃声而不是播放音量、HDMI/蓝牙音频路由也可能不对。
            core.setOptionString("audio-set-media-role", "yes")
            // 官方 MPVView.kt 无条件设：蓝光盘里同一部片的多个 edition 合并成单轨，
            // 否则遇到这类源会多出一条轨道、选轨界面里看着莫名其妙。点播源基本遇不到，设了无害。
            core.setOptionString("flatten-editions", "yes")
            // 只让这些编码走硬解，其余走软解，避免 mediacodec 对不认识的编码直接报错
            core.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            // 硬解开关是 mpv 自己的偏好（官方 key hardware_decoding），和 EXO 的软/硬解各存各的
            val mpvSet = Settings(activity)
            core.setOptionString("hwdec", if (mpvSet.mpvHwdec) "mediacodec" else "no")
            // 官方 MPVView.kt —— API 23+ 用 disp.mode.refreshRate 显式覆盖 display-fps。
            // 不设的话 mpv 自己去"估"面板刷新率，估错会出现两件事：
            //   1) 渲染节拍与面板对不上（24fps 内容在 60Hz 面板上轻微抖、偶发吞帧）
            //   2) display-fps 未知时，render API 那条路径每帧都要按 target-time 保守等待
            // T1 是 API 25，正好在这条官方逻辑的生效区间内。
            runCatching {
                val wm = activity.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as android.view.WindowManager
                @Suppress("DEPRECATION")
                val disp = wm.defaultDisplay
                @Suppress("DEPRECATION")
                val hz = disp.mode.refreshRate
                if (hz > 1f) {
                    core.setOptionString("display-fps-override", hz.toString())
                    MpvDebug.log("[mpv] display-fps-override=$hz display=${disp.displayId}")
                }
            }
            core.setOptionString("keep-open", "no")
            core.setOptionString("pause", "yes")
            core.setOptionString("osc", "no")
            core.setOptionString("input-default-bindings", "no")
            core.setOptionString("input-vo-keyboard", "no")
            // ★★ 真正的根因：mpv 默认 ytdl=auto，遇到没有可识别扩展名的 http 直链
            //   会先走 ytdl_hook 去调 youtube-dl。安卓上没有 youtube-dl，
            //   于是 [ytdl_hook] youtube-dl failed → [cplayer] finished playback, loading failed，
            //   文件根本没加载成功，VO 自然一个都建不起来（这就是 vo=空 cfg=false）。
            //   关掉它，让 mpv 直接把 URL 交给 lavf demuxer。
            core.setOptionString("ytdl", "no")
            core.setOptionString("cache", "yes")
            val cacheMb = if (mpvSet.mpvCacheMb in 32..64) mpvSet.mpvCacheMb else 64
            // 用纯字节数而不是 "64MiB" 后缀：官方 MPVView.kt 就是 ${cacheMegs * 1024 * 1024}，
            // 后缀写法依赖 mpv 的单位解析，一旦不被识别会静默忽略整条选项、缓存其实没生效。
            val cacheBytes = cacheMb * 1024 * 1024
            core.setOptionString("demuxer-max-bytes", "$cacheBytes")
            // 官方 MPVView.kt 是 max-bytes 与 max-back-bytes 一起设的。
            // 只设前向缓存时，往回拖/倒退播放会重新走网络，表现为卡一下。
            core.setOptionString("demuxer-max-back-bytes", "$cacheBytes")
            // 官方 MPVView.kt：播放位置由宿主自己管（我们记在 HistoryStore），
            // 不要让 mpv 自己也写一份，否则退出时会互相覆盖。
            core.setOptionString("save-position-on-quit", "no")
            // 官方 MPVView.kt 用 tls-verify=yes + filesDir/cacert.pem。
            // 我们没有这个 ca bundle，留 yes 会让 https 片源在证书链不全时直接失败，
            // 电视盒子场景（片源多变、证书不规范）取 no 更实用；只连片源，不涉及登录凭据。
            core.setOptionString("tls-verify", "no")
            // 直通（官方 MpvPlayer.java `setOption("audio-spdif", config.audioSpdif())`）：
            //   AC3/EAC3/DTS 这类码流原样交给 HDMI，盒子不解码，功放才拿得到多声道。
            //   不设的话这些音轨会被 mpv 解成 PCM 立体声，听感直接降级。
            core.setOptionString("audio-spdif", "ac3,eac3,dts,dts-hd,truehd")
            // 字幕自动挂载（官方默认 fuzzy）：同名字幕文件/内嵌字幕自动识别，
            //   关掉的话 MKV 里的内嵌字幕要手动选，电视盒子上基本等于没有。
            core.setOptionString("sub-auto", "fuzzy")
            // 官方 MPVView.kt 是无条件设 profile=fast（关掉 gradfun/deband 这类高成本滤镜）。
            // 这里做成设置页开关：T1 这类低端芯片默认开，想拿画质细节时可以关掉。
            if (mpvSet.mpvFastProfile) {
                core.setOptionString("profile", "fast")
            }
            // 缩放算法（官方 MPVView.kt 的 video_scale / video_downscale / video_tscale）。
            // 官方只在偏好非空时下发，空就交给 mpv 默认；这里保持同样的语义。
            // 注意：1080p 片源在 1080p 输出上不触发缩放，这三个值对那种场景完全没作用；
            // 真正生效的是 4K→1080p（dscale）和 720p→1080p（scale）。
            mpvSet.mpvScale.takeIf { it.isNotBlank() }?.let {
                core.setOptionString("scale", it)
            }
            mpvSet.mpvDscale.takeIf { it.isNotBlank() }?.let {
                core.setOptionString("dscale", it)
            }
            mpvSet.mpvTscale.takeIf { it.isNotBlank() }?.let {
                core.setOptionString("tscale", it)
            }
            // 去色带（官方 video_debanding）：gradfun 走 vf，gpu 走 deband 滤镜
            when (mpvSet.mpvDeband) {
                1 -> core.setOptionString("vf", "gradfun=radius=12")
                2 -> core.setOptionString("deband", "yes")
            }
            // 插帧（官方 video_interpolation）：必须配 display-resample 才有意义
            if (mpvSet.mpvInterpolation) {
                core.setOptionString("video-sync", "display-resample")
                core.setOptionString("interpolation", "yes")
            }
            // 快速解码（官方 video_fastdecode）
            if (mpvSet.mpvFastDecode) {
                core.setOptionString("vd-lavc-fast", "yes")
                core.setOptionString("vd-lavc-skiploopfilter", "nonkey")
            }
            // 中文环境：音轨/字幕优先中文，避免默认选中英文字幕
            core.setOptionString("alang", "zh,chi,zho,eng")
            core.setOptionString("slang", "zh,chi,zho,eng")
            // 死链不要无限等待（EXO 侧靠超时判定，mpv 这边也要有）
            core.setOptionString("network-timeout", "15")
            val ua = Settings(activity).ua
            if (ua.isNotBlank()) core.setOptionString("user-agent", ua)
            core.init()
            // 照官方 BaseMPVView.initialize：init 之后立刻关掉 force-window。
            // 官方原注释「would crash before the surface is attached」——
            // surface 还没到时若 force-window 开着，mpv 会试图建一个没有 surface 的窗口。
            // 之后 surfaceCreated 会把它重新打开。
            runCatching { core.setOptionString("force-window", "no") }
            created = true
            core.addObserver(this)
            observeAll()
            inited = true
            Log.i(TAG, "[mpv] 内嵌内核已初始化")
        }.onFailure {
            val msg = it.message ?: it.javaClass.simpleName
            initError = msg
            Log.e(TAG, "[mpv] 初始化失败（so 未打进包或 ABI 不匹配）：$msg", it)
        }
    }

    // ---------------------------------------------------------------- SurfaceHolder.Callback
    //
    // ★路线 A：画面层只有一套。
    // 这里绑的不再是独立的 TextureView，而是 **PlayerView 自己那块 SurfaceView**（EXO 用的同一块）。
    // 对齐 WebHomeTV 5.6：`PlayerView surface_type="none"` + 内核往同一块 surface 输出，
    // 切内核不再换视图、不再换 window layer，收控制条也不会让父窗口重算挖洞。
    //
    // 与 TextureView 版的差别：
    //   1. 没有 setDefaultBufferSize（那是 SurfaceTexture 才有的），尺寸走 android-surface-size 属性；
    //   2. holder.surface 由 SurfaceView 自己持有，**不能**由我们 release，所以不再 track ownsSurface；
    //   3. surface 的生命周期跟着 SurfaceView，Activity 不可见时系统照样会 destroyed，逻辑与原来一致。

    private fun onSurfaceCreated(holder: SurfaceHolder) {
        val w = holder.surfaceFrame.width()
        val h = holder.surfaceFrame.height()
        MpvDebug.log("[mpv] surfaceCreated(SurfaceView) inited=$inited playPending=$playPending ${w}x$h")
        if (!inited) initWithOptions()
        if (!inited) return
        attachSurface(holder.surface, w, h, first = true)
    }

    private fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MpvDebug.log("[mpv] surfaceChanged ${width}x$height fmt=$format")
        if (!inited) return
        core.updateSurfaceSize(width, height)
    }

    private fun onSurfaceDestroyed(holder: SurfaceHolder) {
        MpvDebug.log("[mpv] surfaceDestroyed(SurfaceView) inited=$inited")
        surfaceReady = false
        if (inited) {
            // 官方 BaseMPVView.surfaceDestroyed 原文（BaseMPVView.kt）：
            //   MPVLib.setPropertyString("vo", "null")
            //   MPVLib.setPropertyString("force-window", "no")
            //   MPVLib.detachSurface()
            // 顺序不能反：先把视频输出摘掉，再解绑 Surface，否则 mpv 会在已失效的窗口上再推一帧。
            runCatching { core.setPropertyString("vo", "null") }
            runCatching { core.setPropertyString("force-window", "no") }
            core.detachSurface()
        }
    }

    /**
     * TextureView 形态：没有 SurfaceHolder 回调可用。
     *
     * media3 的 PlayerView 自己挂着 SurfaceTextureListener（它要负责把 texture 交给 ExoPlayer），
     * 我们**不能**再注册一个，否则会把它的监听顶掉、EXO 直接没画面。
     * 所以这里用轮询：可用就绑，掉了就按 surfaceDestroyed 的语义收干净，再等它回来。
     */
    private var ownedSurface: Surface? = null
    private fun watchTexture() {
        val tv = host as? android.view.TextureView ?: return
        tv.removeCallbacks(textureWatch)
        tv.post(textureWatch)
    }

    private fun attachSurface(surface: Surface, width: Int, height: Int, first: Boolean) {
        runCatching {
            // 官方 BaseMPVView.surfaceCreated 原文（BaseMPVView.kt）：
            //   恢复视频输出 → setPropertyString("vo", voInUse)
            // 然后才是 attachSurface + android-surface-size（尺寸由 MpvCore 内部一并设好）。
            core.setPropertyString("vo", "gpu")
            core.setPropertyString("force-window", "yes")
            core.attachSurface(surface, width, height)
        }
        runCatching {
            MpvDebug.log("[mpv] attachSurface ${width}x${height} first=$first 像素格式保持系统默认（本版不再 setFormat）")
        }
        surfaceReady = true
        if (playPending && pendingUrl.isNotBlank()) {
            playPending = false
            doPlay(pendingUrl)
        } else if (pendingResume && pendingUrl.isNotBlank()) {
            pendingResume = false
            paused = false
            runCatching { core.setPropertyBoolean("pause", false) }
        } else if (first && pendingUrl.isNotBlank() && !backgrounded) {
            // 换了一块新 surface，但 mpv 因为 vo 曾被置 null 已经掉到 idle（文件没在播），
            // 只重开 vo 是黑屏，按最后一次地址重新 loadfile，位置沿用记下来的进度。
            val wasIdle = runCatching {
                core.getPropertyBoolean("idle-active")
            }.getOrNull() ?: false
            if (wasIdle) {
                pendingSeekMs = pendingStartMs
                doPlay(pendingUrl)
            }
        }
    }

    fun ensureSurface() {
        if (!inited) return
        when (host) {
            is android.view.TextureView -> watchTexture()
            is SurfaceView -> {
                val h = (host as SurfaceView).holder
                if (h.surface?.isValid == true && !surfaceReady) onSurfaceCreated(h)
            }
        }
    }

    internal fun releaseInternal() {
        abandonAudioFocus()
        if (!inited) return
        // 先关闸再动 native：destroy 期间 surfaceDestroyed 和延迟的 idle 探测都可能进来
        inited = false
        surfaceReady = false
        playPending = false
        // 释放后不该再挂着媒体项：否则复用一个已 release 的实例时，
        // getState 会拿着"上一集的 item"去报状态，上层就是「进不去播放页」的观感。
        trackRefreshHandler.removeCallbacks(trackRefreshRunnable)
        trackRefreshPending = false
        fileLoadedAtMs = 0L
        currentTracks = Tracks.EMPTY
        currentItem = null
        snapPosMs = 0L
        snapDurMs = C.TIME_UNSET
        snapSpeed = 1f
        snapVideoW = 0
        snapVideoH = 0
        playlistData = null
        playlistForItem = null
        pendingResume = false
        backgrounded = false
        // 清掉已播地址：否则释放后若 surface 再次创建，会把旧地址重新 loadfile
        pendingUrl = ""
        // 照官方 BaseMPVView.destroy：先摘掉 surface 回调再 destroy，
        // 否则释放后视图的可见性变化还会回调进来，把已经 destroy 的 libmpv 再 init 一遍
        // 已不再自注册 holderCallback（主权在 VideoHost），这里无需移除；
        // 保留一行解释，避免以后又有人加回去。
        runCatching { (host as? android.view.TextureView)?.removeCallbacks(textureWatch) }
        runCatching { ownedSurface?.release() }
        ownedSurface = null
        runCatching { core.removeObserver(this) }
        runCatching { core.command(arrayOf("stop")) }
        runCatching { core.destroy() }
        created = false
        if (current === this) current = null
        liveCount--
        MpvDebug.log("[mpv] 内核已释放 存活实例=${liveCount}")
    }

    // ---------------------------------------------------------------- 音频焦点

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

    /**
     * @param startPaused 起播即暂停。
     *   用户在文件还没加载时按了 OK，PlayActivity 会先 mpv.pause() 记下意图；
     *   但 doPlay() 里 loadfile 之后无条件 `pause=false`，会把这条意图吃掉 ——
     *   真机上表现就是「刚进播放页按暂停没反应，播了一会儿再按才正常」。
     */
    fun play(
        url: String,
        headers: Map<String, String>,
        startMs: Long = 0,
        subs: List<String> = emptyList(),
        startPaused: Boolean = false,
    ) {
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
        pendingStartPaused = startPaused
        // ★ playlist 不能空：getState 里 STATE_READY/BUFFERING + 空 playlist 会触发
        //   media3 "Empty playlist only allowed in STATE_IDLE or STATE_ENDED" 直接崩。
        //   老路径 play(url) 不经过 handleSetMediaItems，这里补一项；
        //   若 handleSetMediaItems 已经设过同一个 uri 就别覆盖（它可能带 metadata）。
        if (currentItem?.localConfiguration?.uri?.toString() != url) {
            currentItem = MediaItem.fromUri(url)
        }
        // 起播前用户按过"继续"时，resume() 因为 surface 还没回来会记一笔 pendingResume。
        // 这一笔如果留着，下一次 texture 重建就会把 pause 强行置 false ——
        // 用户明明按了暂停，起播后却自己跑起来。新的 play() 一律清掉这笔旧账。
        pendingResume = false
        playPending = true
        if (surfaceReady) {
            playPending = false
            doPlay(url)
        }
    }

    private fun observeAll() {
        core.observeProperty("time-pos", MpvCore.MpvFormat.MPV_FORMAT_DOUBLE)
        core.observeProperty("duration", MpvCore.MpvFormat.MPV_FORMAT_DOUBLE)
        core.observeProperty("pause", MpvCore.MpvFormat.MPV_FORMAT_FLAG)
        // 用 eof-reached 判"真的播完"：END_FILE 在换文件/stop 时也会来，拿它判会误跳下一集
        core.observeProperty("eof-reached", MpvCore.MpvFormat.MPV_FORMAT_FLAG)
        // 视频帧尺寸：直接用 mpv 的 width / height 属性（整数），
        // 比解析 video-params 字符串再跑正则正规，也是 mpv 官方对外语义。
        core.observeProperty("width", MpvCore.MpvFormat.MPV_FORMAT_INT64)
        core.observeProperty("height", MpvCore.MpvFormat.MPV_FORMAT_INT64)
        // track-list 是 node 类型（MPV_FORMAT_NODE = 6）：只用来触发"变了"，具体内容读子属性。
        // 换集 / 加字幕 / 切轨都会推这个属性，是轨道快照的唯一刷新信号。
        core.observeProperty("track-list", 6)
    }

    private fun doPlay(url: String) {
        if (!inited) return
        runCatching {
            val headerLine = pendingHeaders.entries
                .filter { it.key.isNotBlank() }
                .joinToString(",") { "${it.key}: ${it.value}" }
            core.setPropertyString("http-header-fields", headerLine)
            // 起播前的定位必须等 FILE_LOADED：文件还没加载时 seek absolute 会直接失败
            pendingSeekMs = pendingStartMs
            core.command(arrayOf("loadfile", url, "replace"))
            // 尊重起播前按下的暂停意图：不能无条件解暂停
            val startPaused = pendingStartPaused
            paused = startPaused
            core.setPropertyBoolean("pause", startPaused)
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

    /**
     * 真实暂停态：缓存标记一旦被「写下去却没生效」带偏，就再也纠不回来 ——
     * 按 OK 第一次把 paused 置 true（mpv 其实没停），之后每次 toggle 都会走 resume()，
     * 表现就是用户说的「mpv 根本停不下来」。所以这里读一次 mpv 自己的 pause 属性为准并回写缓存。
     */
    fun isPausedActual(): Boolean {
        if (!inited) return true
        val real = runCatching { core.getPropertyBoolean("pause") }.getOrNull()
        if (real != null) paused = real
        return paused
    }

    /**
     * 写 pause：属性写和命令写两条路都下发，再读回核对。
     * 只用 setPropertyBoolean 时，个别 libmpv 版本对这条写入是静默丢弃（不抛异常、也不报错，
     * 播放照旧），读回核对才能把这种情况暴露出来。
     */
    private fun applyPause(value: Boolean) {
        if (!inited) return
        paused = value
        runCatching { core.setPropertyBoolean("pause", value) }
        runCatching {
            core.command(arrayOf("set", "pause", if (value) "yes" else "no"))
        }
        val actual = runCatching { core.getPropertyBoolean("pause") }.getOrNull()
        MpvDebug.log("[mpv] set pause=$value 实际=$actual")
        if (actual != null && actual != value && value) {
            // 个别实例会被自己延迟的一大串属性事件覆盖回 false，再补一条命令兜住
            runCatching { core.command(arrayOf("set", "pause", "yes")) }
        }
    }

    fun resume() {
        if (!inited) return
        if (eofReached) {
            eofReached = false
            runCatching { core.command(arrayOf("seek", "0", "absolute+exact")) }
        }
        paused = false
        if (!surfaceReady) {
            pendingResume = true
            return
        }
        pendingResume = false
        applyPause(false)
    }

    /**
     * 调试用：mpv 当前的视频输出后端。
     * 空串/null = vo 没起来（画面黑但声音照走就是这种）；"gpu" = 渲染后端在。
     */
    fun voNow(): String {
        if (!inited) return "未初始化"
        return runCatching {
            core.getPropertyString("current-vo") ?: "空"
        }.getOrElse { "读取失败" }
    }

    /**
     * 渲染自检 + 复位。
     *
     * 黑屏有声时 current-vo 是空串：surface 还在、mpv 也在播（声音照走），
     * 但渲染后端没挂到这块 surface 上，于是没有一帧输出。
     * 这里不依赖系统重建 surface，直接 detach 再 attach 一遍、把 vo 从 null 拉回 gpu。
     *
     * @return true 表示确实做过复位
     */
    fun surfaceReadyNow(): Boolean = surfaceReady

    fun setBackground(value: Boolean) {
        backgrounded = value
        if (!value) return
        // 进后台时把记账清掉，避免把「上一次切后台的 resume」带到下一次 surface 重建
        pendingResume = false
    }

    fun positionMs(): Long {
        if (!inited) return 0
        return runCatching {
            val v = core.getPropertyDouble("time-pos") ?: 0.0
            (v * 1000).toLong()
        }.getOrDefault(0)
    }

    fun durationMs(): Long {
        if (!inited) return 0
        return runCatching {
            val v = core.getPropertyDouble("duration") ?: 0.0
            (v * 1000).toLong()
        }.getOrDefault(0)
    }

    fun seekToMs(ms: Long) {
        if (!inited) return
        val target = ms.coerceAtLeast(0)
        val sec = String.format(Locale.US, "%.3f", target / 1000.0)
        runCatching { core.command(arrayOf("seek", sec, "absolute+exact")) }
        MpvDebug.log("[mpv] seek -> ${sec}s")
        // 1.2 秒后核对位置：这条命令在个别内核/异常流上会被静默丢掉（不报错也不动），
        // 表现就是用户说的「快进快退没反应」。真没动就补一条普通 absolute。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!inited) return@postDelayed
            val nowSec = runCatching {
                core.getPropertyDouble("time-pos")
            }.getOrNull() ?: return@postDelayed
            val nowMs = (nowSec * 1000).toLong()
            if (target > 3000 && kotlin.math.abs(nowMs - target) > 3000) {
                MpvDebug.log("[mpv] seek 未生效（现在 ${nowSec}s，目标 ${target}ms），改用 absolute 重试")
                runCatching { core.command(arrayOf("seek", sec, "absolute")) }
            }
        }, 1200)
    }

    fun setSpeed(speed: Float) {
        desiredSpeed = speed
        // 外推参数变了，必须重建 state，否则进度条按旧速度跑
        snapSpeed = speed
        ui { pushStateNow() }
        if (!inited) return
        runCatching { core.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun applyScale(idx: Int) {
        desiredScale = idx
        if (!inited) return
        runCatching {
            when (idx) {
                // 原始：保持片源宽高比
                0 -> {
                    core.setPropertyString("video-aspect-override", "no")
                    core.setPropertyBoolean("keepaspect", true)
                    core.setPropertyDouble("panscan", 0.0)
                }
                1 -> {
                    core.setPropertyString("video-aspect-override", "16:9")
                    core.setPropertyBoolean("keepaspect", true)
                    core.setPropertyDouble("panscan", 0.0)
                }
                2 -> {
                    core.setPropertyString("video-aspect-override", "4:3")
                    core.setPropertyBoolean("keepaspect", true)
                    core.setPropertyDouble("panscan", 0.0)
                }
                // 填充：拉伸铺满，不保持比例
                3 -> {
                    core.setPropertyString("video-aspect-override", "no")
                    core.setPropertyBoolean("keepaspect", false)
                    core.setPropertyDouble("panscan", 0.0)
                }
                // 裁剪：保持比例并拉满，超出部分裁掉
                else -> {
                    core.setPropertyString("video-aspect-override", "no")
                    core.setPropertyBoolean("keepaspect", true)
                    core.setPropertyDouble("panscan", 1.0)
                }
            }
        }
    }

    fun setHardwareDecode(hard: Boolean) {
        if (!inited) return
        runCatching {
            core.setPropertyString("hwdec", if (hard) "mediacodec" else "no")
        }
        // hwdec 是加载期选项：只对之后加载的文件生效，当前文件不重新加载就切不过去。
        // 跟 EXO 那边"重建播放器再回到原位置"的做法对齐。
        val cur = pendingUrl
        if (cur.isBlank()) return
        val at = positionMs()
        pendingStartMs = at
        pendingSeekMs = at
        runCatching { core.command(arrayOf("loadfile", cur, "replace")) }
        Log.i(TAG, "[mpv] 切换解码：hard=$hard，重载当前位置 ${at}ms")
    }

    fun toggleSubVisible() {
        if (!inited) return
        runCatching {
            val vis = core.getPropertyBoolean("sub-visibility") ?: true
            core.setPropertyBoolean("sub-visibility", !vis)
        }
    }

    // ---------------------------------------------------------------- 事件（mpv 线程 → 主线程）

    private fun ui(block: () -> Unit) {
        activity.runOnUiThread { runCatching { block() } }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvCore.MpvEvent.MPV_EVENT_FILE_LOADED -> ui {
                eofReached = false
                // 起播前的 speed / scale 可能不生效，文件加载完成后补下发
                runCatching { core.setPropertyDouble("speed", desiredSpeed.toDouble()) }
                applyScale(desiredScale)
                // 外挂字幕必须等文件加载后再 sub-add，提前下发会被丢掉
                for ((i, sub) in pendingSubs.withIndex()) {
                    runCatching {
                        core.command(arrayOf("sub-add", sub, "select", "字幕${i + 1}"))
                    }
                }
                fileLoadedAtMs = android.os.SystemClock.elapsedRealtime()
                scheduleTrackRefresh()
                val startMs = pendingSeekMs
                pendingSeekMs = 0
                if (startMs > 1000) {
                    val sec = String.format(Locale.US, "%.3f", startMs / 1000.0)
                    runCatching { core.command(arrayOf("seek", sec, "absolute+exact")) }
                }
                onLoaded?.invoke()
                pushStateNow()
            }
            // END_FILE 在 loadfile replace / stop 时也会触发，播完判定交给 eof-reached；
            // 但"加载失败/解码失败"也只有这个事件能感知，所以延迟 800ms 看是否真的掉回 idle
            MpvCore.MpvEvent.MPV_EVENT_END_FILE -> {
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
                        core.getPropertyBoolean("idle-active")
                    }.getOrNull() ?: false
                    // 换集时新文件会立刻接管，idle-active 回到 false；真的失败才停在这里
                    if (idle && !eofReached) {
                        // 把 mpv 自己的状态带出来：只报「播放中断」等于把真因盖住，
                        // 修完还是中断时根本没法判断是「视频输出没建起来」还是「解码/网络失败」。
                        //   current-vo       有值（通常是 gpu）说明渲染后端起来了
                        //   vo-configured    false 说明 VO 压根没配成功
                        val voName = runCatching {
                            core.getPropertyString("current-vo")
                        }.getOrNull()
                        val voReady = runCatching {
                            core.getPropertyBoolean("vo-configured")
                        }.getOrNull()
                        val ver = runCatching {
                            core.getPropertyString("mpv-version")
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
            "time-pos" -> {
                snapPosMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                ui {
                    onTimePos?.invoke(snapPosMs)
                    // 位置本身由 media3 外推，这里只做低频校准
                    pushStateCalibrate()
                }
            }
            "duration" -> {
                snapDurMs = (value * 1000.0).toLong().takeIf { it > 0L } ?: C.TIME_UNSET
                // 时长变了，下次 getState 重建带 durationUs 的媒体项
                playlistData = null
                ui { onDuration?.invoke((value * 1000.0).toLong()) }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                paused = value
                ui {
                    onPauseChanged?.invoke(value)
                    pushStateNow()
                }
            }
            "eof-reached" -> {
                eofReached = value
                ui {
                    // 只有真的播到末尾才回调，换集时的 false 不触发
                    if (value) onEndFile?.invoke()
                    pushStateNow()
                }
            }
        }
    }

    override fun eventProperty(property: String) {
        when (property) {
            "track-list" -> scheduleTrackRefresh()
        }
    }
    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "width" -> {
                snapVideoW = value.toInt()
                ui { pushStateNow() }
            }
            "height" -> {
                snapVideoH = value.toInt()
                ui { pushStateNow() }
            }
        }
    }
    override fun eventProperty(property: String, value: String) = Unit

    // ============================================================ Player 接口
    //
    // mpv 成为 media3 的标准 Player 实现，PlayerView.setPlayer(mpvPlayer) 可直接用，
    // 上层不再需要 VideoHost.Owner 那套手写交接。方向对齐原项目
    // androidx.media3.mpvplayer.MpvPlayer（extends SimpleBasePlayer）。
    // 关键前提：media3 1.4.1 起官方 SimpleBasePlayer 提供 handleSetVideoOutput（1.3.1 没有）。

    /**
     * 显式声明真正实现了的命令。
     *
     * ★不能用 addAllCommands()：SimpleBasePlayer 对"声明了但没实现"的命令直接抛
     * IllegalStateException —— 基类默认 handleSetTrackSelectionParameters 就是
     * "Missing implementation to handle COMMAND_SET_TRACK_SELECTION_PARAMETERS"。
     * 声明的集合必须与实现一一对应，这是契约，不是能力清单。
     * （对齐原项目 androidx/media3/mpvplayer/MpvPlayer.java 的写法）
     */
    private val commands: Player.Commands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            // release() 会检查这条：没声明时 release() 直接抛，上层的 runCatching 会把它吞成
            // "释放失败但不报错"，内核就泄漏了 —— 这是白名单改造最容易踩的坑。
            Player.COMMAND_RELEASE,
            // 不声明 COMMAND_STOP：没有 handleStop 实现。上层也没有调 stop()，
            // 声明了反而给"声明做不到的能力"留口子。
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            // media3 1.11.1 只有这一个画面命令，clearVideoSurface 也归它管
            Player.COMMAND_SET_VIDEO_SURFACE,
        )
        .build()

    private fun posMsNow(): Long = if (!inited) 0L else snapPosMs
    private fun durMsNow(): Long = if (!inited) C.TIME_UNSET else snapDurMs

    // ============================================================ 轨道（标准 media3 Tracks）
    //
    // 对齐原项目 androidx/media3/mpvplayer/MpvPlayer 的设计：
    //   上报：track-list → Format，Format.id = "{TRACK_TYPE}:{mpvId}"（如 "1:1" = 音频 1）
    //   TrackGroup.id = "mpv:{Format.id}"，selected 由 mpv 当前 vid/aid/sid 决定
    //   回写：从 Format.id 抠出 mpvId 直接写属性
    //
    // ★原生轨道号就藏在 Format.id 里，日常选轨是精确匹配，不需要任何语言/编解码猜；
    //   只有"持久化偏好恢复"才需要打分兜底。
    //
    // ★1.11.1 的 State.Builder 没有 setTracks，轨道挂在 MediaItemData 上，
    //   所以 refreshTracks 之后要让 playlist 缓存失效（playlistForItem = null），
    //   下一次 getState 重建时带上新 tracks。
    private var currentTracks: Tracks = Tracks.EMPTY
    private var fileLoadedAtMs = 0L
    private var trackRefreshPending = false
    private val trackRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val trackRefreshRunnable = Runnable {
        trackRefreshPending = false
        refreshTracks()
        pushStateNow()
    }

    /**
     * 合并刷新：起播 8 秒内 track-list 会连续变化，用 2000ms 静默；
     * 之后用 120ms 防抖。两个阈值取自原项目 MpvTrackRefreshPolicy。
     */
    private fun scheduleTrackRefresh() {
        if (!inited) return
        if (trackRefreshPending) return
        trackRefreshPending = true
        trackRefreshHandler.removeCallbacks(trackRefreshRunnable)
        val now = android.os.SystemClock.elapsedRealtime()
        val startup = fileLoadedAtMs <= 0L || now - fileLoadedAtMs < 8000L
        trackRefreshHandler.postDelayed(trackRefreshRunnable, if (startup) 2000L else 120L)
    }

    private fun refreshTracks() {
        if (!inited) {
            currentTracks = Tracks.EMPTY
            playlistForItem = null
            return
        }
        val count = runCatching { core.getPropertyInt("track-list/count") }.getOrNull() ?: 0
        if (count <= 0) {
            currentTracks = Tracks.EMPTY
            playlistForItem = null
            return
        }
        val selVid = runCatching { core.getPropertyString("vid") }.getOrNull().orEmpty()
        val selAid = runCatching { core.getPropertyString("aid") }.getOrNull().orEmpty()
        val selSid = runCatching { core.getPropertyString("sid") }.getOrNull().orEmpty()

        val types = ArrayList<Int>(count)
        val formats = ArrayList<Format>(count)
        val selected = ArrayList<Boolean>(count)

        for (i in 0 until count) {
            val kind = runCatching { core.getPropertyString("track-list/$i/type") }
                .getOrNull()?.trim().orEmpty()
            val id = runCatching { core.getPropertyInt("track-list/$i/id") }.getOrNull() ?: continue
            val type = when (kind) {
                "video" -> C.TRACK_TYPE_VIDEO
                "audio" -> C.TRACK_TYPE_AUDIO
                "sub" -> C.TRACK_TYPE_TEXT
                else -> C.TRACK_TYPE_UNKNOWN
            }
            if (type == C.TRACK_TYPE_UNKNOWN) continue
            val codec = runCatching { core.getPropertyString("track-list/$i/codec") }.getOrNull().orEmpty()
            val lang = runCatching { core.getPropertyString("track-list/$i/lang") }.getOrNull().orEmpty()
            val title = runCatching { core.getPropertyString("track-list/$i/title") }.getOrNull().orEmpty()
            val cur = when (type) {
                C.TRACK_TYPE_VIDEO -> selVid
                C.TRACK_TYPE_AUDIO -> selAid
                else -> selSid
            }
            val isSel = normalizeTrackId(cur) == id.toString()
            val fmt = Format.Builder()
                .setId("$type:$id")
                .setSampleMimeType(genericMimeOf(type))
                .setCodecs(codec.takeIf { it.isNotBlank() })
                .setLanguage(lang.takeIf { it.isNotBlank() && it != "und" })
                .setLabel(
                    title.takeIf { it.isNotBlank() }
                        ?: lang.takeIf { it.isNotBlank() && it != "und" }
                )
                .build()
            types.add(type)
            formats.add(fmt)
            selected.add(isSel)
        }

        // mpv 在 auto 状态可能返回 "auto"/空，此时按原项目做法把该类型第一条标为选中；
        // 字幕不做这个 fallback —— 会把"没开字幕"显示成"已选中某条字幕"。
        if (types.indices.none { types[it] == C.TRACK_TYPE_VIDEO && selected[it] }) {
            types.indexOfFirst { it == C.TRACK_TYPE_VIDEO }.takeIf { it >= 0 }
                ?.let { selected[it] = true }
        }
        if (types.indices.none { types[it] == C.TRACK_TYPE_AUDIO && selected[it] }) {
            types.indexOfFirst { it == C.TRACK_TYPE_AUDIO }.takeIf { it >= 0 }
                ?.let { selected[it] = true }
        }

        val groups = ArrayList<Tracks.Group>(types.size)
        for (k in types.indices) {
            val fmt = formats[k]
            val group = TrackGroup("mpv:${fmt.id}", fmt)
            groups.add(
                Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected[k]))
            )
        }
        currentTracks = if (groups.isEmpty()) Tracks.EMPTY else Tracks(groups)
        playlistForItem = null
    }

    private fun setMpvTrack(type: Int, mpvId: String) {
        if (!inited || mpvId.isBlank()) return
        val prop = mpvTrackProperty(type) ?: return
        val cur = runCatching { core.getPropertyString(prop) }.getOrNull().orEmpty()
        if (normalizeTrackId(cur) == normalizeTrackId(mpvId)) return
        runCatching { core.setPropertyString(prop, mpvId) }
    }

    private fun mpvTrackProperty(type: Int): String? = when (type) {
        C.TRACK_TYPE_VIDEO -> "vid"
        C.TRACK_TYPE_AUDIO -> "aid"
        C.TRACK_TYPE_TEXT -> "sid"
        else -> null
    }

    private fun genericMimeOf(type: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> MIME_VIDEO_GENERIC
        C.TRACK_TYPE_AUDIO -> MIME_AUDIO_GENERIC
        else -> MIME_TEXT_GENERIC
    }

    private fun normalizeTrackId(value: String): String {
        var s = value.trim()
        while (s.length > 1 && s.startsWith("0")) s = s.substring(1)
        return s
    }

    private fun pushStateNow() {
        lastStatePushAt = android.os.SystemClock.elapsedRealtime()
        runCatching { invalidateState() }
    }

    /**
     * 位置校准：播放中位置由 media3 自己按时钟外推（PositionSupplier.getExtrapolating），
     * 我们不需要按帧推送；这里只用 1Hz 把外推起点校正回 mpv 的真实 time-pos。
     * 频率再高只是白白 invalidate，再低则长片会积累可见漂移。
     */
    private fun pushStateCalibrate() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatePushAt < 1000L) return
        pushStateNow()
    }

    override fun getState(): State {
        val item = currentItem
        // ★ media3 契约：playlist 为空时 playbackState 只能是 IDLE 或 ENDED，
        //   否则 State.Builder.build() 抛 IllegalArgumentException
        //   "Empty playlist only allowed in STATE_IDLE or STATE_ENDED"。
        //   而 mpv 的 inited / playPending / eofReached 与"有没有媒体项"彼此独立：
        //   新建实例走 play(url) 时媒体项还没登记，就会拼出 BUFFERING + 空列表。
        //   所以"有没有媒体项"必须先判。
        val pb = when {
            item == null || !inited -> Player.STATE_IDLE
            eofReached -> Player.STATE_ENDED
            playPending -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(pb)
            .setIsLoading(pb == Player.STATE_BUFFERING)

        if (item == null) {
            // 空 playlist 分支不设 contentPosition / videoSize：没有媒体项时它们没有语义。
            return builder
                .setPlaylist(emptyList())
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }

        // playlist 只在 item / 时长变化时重建，避免 getState 每次都新建对象
        var playlist = playlistData
        if (playlist == null || playlistForItem !== item) {
            val dataBuilder = MediaItemData.Builder(UID).setMediaItem(item)
            if (snapDurMs > 0L) dataBuilder.setDurationUs(snapDurMs * 1000L)
            // 轨道挂在 MediaItemData 上（State.Builder 没有 setTracks）
            dataBuilder.setTracks(currentTracks)
            playlist = listOf(dataBuilder.build())
            playlistData = playlist
            playlistForItem = item
        }

        builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(!paused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        // ★位置：真正在播时交给 media3 按时钟外推，暂停 / 缓冲 / 播完才给恒定值。
        //   这样进度条平滑前进而不需要宿主按帧推送 —— media3 给的官方形态，
        //   不是为某台设备做的妥协。
        builder.setContentPositionMs(
            if (pb == Player.STATE_READY && !paused)
                PositionSupplier.getExtrapolating(posMsNow(), snapSpeed)
            else
                PositionSupplier.getConstant(posMsNow())
        )

        // 尺寸只在 mpv 报了有效宽高时才设
        if (snapVideoW > 0 && snapVideoH > 0) {
            builder.setVideoSize(VideoSize(snapVideoW, snapVideoH, 0, 1f))
        }
        return builder.build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val item = mediaItems.getOrNull(startIndex) ?: return Futures.immediateVoidFuture()
        currentItem = item
        val uri = item.localConfiguration?.uri?.toString()
        if (uri != null) play(uri, emptyMap(), startPositionMs, emptyList(), startPaused = true)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!inited) initWithOptions()
        if (pendingUrl.isNotBlank() && surfaceReady && playPending) {
            playPending = false
            doPlay(pendingUrl)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // 不能调基类 pause()（那会再进 handleSetPlayWhenReady，绕成环）
        if (playWhenReady) resume() else if (inited) applyPause(true)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // 外推起点必须立刻对齐到 seek 目标，否则进度条会从旧位置继续走
        snapPosMs = positionMs.coerceAtLeast(0L)
        pushStateNow()
        seekToMs(positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        releaseInternal()
        return Futures.immediateVoidFuture()
    }

    /**
     * 视频输出：PlayerView.setPlayer 时把它的 SurfaceView/SurfaceHolder/Surface 交进来。
     * media3 1.4.1 的 SimpleBasePlayer 提供这个扩展点（1.3.1 没有）。
     */
    /**
     * 轨道选择：把 media3 标准参数翻译成 mpv 的 vid / aid / sid。
     *
     * 三层语义全部落在 mpv 原生能力上：
     *   preferredAudioLanguages → alang（mpv 官方就是按语言优先级自动选轨）
     *   disabledTrackTypes      → aid=no / sid=no
     *   overrides               → Format.id 抠出原生轨道号，直接写属性
     */
    override fun handleSetTrackSelectionParameters(
        parameters: TrackSelectionParameters,
    ): ListenableFuture<*> {
        if (inited) {
            val langs = parameters.preferredAudioLanguages.filter { it.isNotBlank() }
            if (langs.isNotEmpty()) {
                runCatching { core.setPropertyString("alang", langs.joinToString(",")) }
            }
            // 有 override 的类型视为"已启用"：media3 语义里 override 与 disabled 互斥。
            // 两者同时出现时（用户先关字幕、再点一条字幕而没人清 disabled），
            // 如果照字面写 no，就把用户刚选的轨道又关掉了。
            val overrideTypes = HashSet<Int>()
            for (entry in parameters.overrides.entries) {
                if (entry.key.id?.startsWith("mpv:") == true) overrideTypes.add(entry.key.type)
            }
            for (type in intArrayOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
                val prop = mpvTrackProperty(type) ?: continue
                val disabled = parameters.disabledTrackTypes.any { it == type } && type !in overrideTypes
                val cur = runCatching { core.getPropertyString(prop) }.getOrNull().orEmpty()
                if (disabled) {
                    if (cur != "no") runCatching { core.setPropertyString(prop, "no") }
                } else if (cur == "no") {
                    runCatching { core.setPropertyString(prop, "auto") }
                }
            }
            for (entry in parameters.overrides.entries) {
                val group = entry.key
                // 只认本内核上报的 group（id 前缀 "mpv:"）。EXO 的 group 不在这里翻译：
                // 跨内核语义搬运是上层切换内核时的一次性职责，硬套会拿 EXO 的轨道号
                // 去写 mpv 的 vid/aid/sid，属于错误匹配。
                if (group.id?.startsWith("mpv:") != true) continue
                val idx = entry.value.trackIndices.firstOrNull() ?: continue
                if (idx < 0 || idx >= group.length) continue
                val formatId = group.getFormat(idx).id ?: continue
                // Format.id = "{type}:{mpvId}"，冒号后面就是 mpv 自己的轨道号
                val mpvId = formatId.substringAfter(':')
                setMpvTrack(group.type, mpvId)
            }
            // 用户刚点完轨道：立即刷新 + 推状态，不等防抖窗口
            // （起播窗口内有 2s 静默，菜单会显得"点了没反应"）
            trackRefreshPending = false
            trackRefreshHandler.removeCallbacks(trackRefreshRunnable)
            refreshTracks()
            pushStateNow()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is SurfaceView -> {
                val h = videoOutput.holder
                if (h.surface?.isValid == true) {
                    attachSurface(h.surface, h.surfaceFrame.width(), h.surfaceFrame.height(), first = true)
                }
            }
            is SurfaceHolder -> {
                if (videoOutput.surface?.isValid == true) {
                    attachSurface(videoOutput.surface, videoOutput.surfaceFrame.width(), videoOutput.surfaceFrame.height(), first = true)
                }
            }
            is android.view.TextureView -> {
                // PlayerView 的 surface_type=texture_view 时交进来的是 TextureView。
                // 官方 Player 契约里这是合法形态之一，必须接；否则一改布局属性就没画面。
                val st = videoOutput.surfaceTexture
                if (st != null) {
                    val s = ownedSurface?.takeIf { it.isValid } ?: Surface(st).also { ownedSurface = it }
                    attachSurface(s, videoOutput.width, videoOutput.height, first = true)
                }
            }
            is Surface -> attachSurface(videoOutput, 0, 0, first = true)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        // 官方 BaseMPVView.surfaceDestroyed 的三步，顺序不能反：
        //   vo=null → force-window=no → detachSurface
        // 先摘面再关输出，mpv 会对着一个已失效的窗口再做一次 swap。
        runCatching { core.setPropertyString("vo", "null") }
        runCatching { core.setPropertyString("force-window", "no") }
        runCatching { core.detachSurface() }
        return Futures.immediateVoidFuture()
    }
}
