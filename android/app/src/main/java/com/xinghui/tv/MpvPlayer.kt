package com.xinghui.tv

import android.app.Activity
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xinghui.tv.mpv.MpvCore
import com.xinghui.tv.mpv.XhMpv
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
) : MpvCore.EventObserver {

    /** 官方 libmpv + 我们自己的 render API 上屏（handle 与 EGL 都在这一层） */
    private val core = MpvCore(activity)

    companion object {
        private const val TAG = "XingHuiTV"
        /** 当前活着的 MpvPlayer 数量：>1 说明有实例没释放，MPVLib 的静态句柄会指错，指令全打到空实例上 */
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
    /** 非正常结束（加载失败 / 解码失败 / 网络断）：上层据此走自动换源 */
    var onFailed: ((String) -> Unit)? = null

    private var inited = false
    private var surfaceReady = false
    /** 当前 attach 到 mpv 的那块 surface，渲染自检复位时要重新用上它 */
    private var attachedSurface: android.view.Surface? = null
    private var lastSurfaceW = 0
    private var lastSurfaceH = 0
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
    private var pendingStartPaused = false
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
                attachedSurface = null
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
                v.holder.addCallback(holderCallback)
                // 已经被 SurfaceView 自己创建过：手动补一次
                if (v.holder.surface?.isValid == true) onSurfaceCreated(v.holder)
            }
            is android.view.TextureView -> watchTexture()
            else -> MpvDebug.log("[mpv] 画面层宿主类型不支持：${v.javaClass.simpleName}")
        }
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
            // ★ 输出是 libmpv：帧不再由 mpv 自己往 Surface 上推，而是由我们的
            //   mpv_render_context_render + eglSwapBuffers 上屏（xh_mpv.cpp）。
            //   vo 必须是 libmpv，且只有在 initialize 之前设才有效；
            //   xh_mpv.cpp 的 nativeCreate 已经设好，这里不要再覆盖成 gpu / mediacodec_embed，
            //   一旦覆盖，render 上下文就拿不到帧，画面直接空。
            core.setOptionString("vo", "libmpv")
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
            // 官方 MPVView.kt:97 无条件设。不设时安卓会把这条音频流当成普通/通知类，
            // 表现为遥控器音量键调的是铃声而不是播放音量、HDMI/蓝牙音频路由也可能不对。
            core.setOptionString("audio-set-media-role", "yes")
            // 官方 MPVView.kt:101 无条件设：蓝光盘里同一部片的多个 edition 合并成单轨，
            // 否则遇到这类源会多出一条轨道、选轨界面里看着莫名其妙。点播源基本遇不到，设了无害。
            core.setOptionString("flatten-editions", "yes")
            // 只让这些编码走硬解，其余走软解，避免 mediacodec 对不认识的编码直接报错
            core.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            // 硬解开关是 mpv 自己的偏好（官方 key hardware_decoding），和 EXO 的软/硬解各存各的
            val mpvSet = Settings(activity)
            core.setOptionString("hwdec", if (mpvSet.mpvHwdec) "mediacodec" else "no")
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
            // 用纯字节数而不是 "64MiB" 后缀：官方 MPVView.kt:104 就是 ${cacheMegs * 1024 * 1024}，
            // 后缀写法依赖 mpv 的单位解析，一旦不被识别会静默忽略整条选项、缓存其实没生效。
            val cacheBytes = cacheMb * 1024 * 1024
            core.setOptionString("demuxer-max-bytes", "$cacheBytes")
            // 官方 MPVView.kt:104-105 是 max-bytes 与 max-back-bytes 一起设的。
            // 只设前向缓存时，往回拖/倒退播放会重新走网络，表现为卡一下。
            core.setOptionString("demuxer-max-back-bytes", "$cacheBytes")
            // 官方 MPVView.kt:114：播放位置由宿主自己管（我们记在 HistoryStore），
            // 不要让 mpv 自己也写一份，否则退出时会互相覆盖。
            core.setOptionString("save-position-on-quit", "no")
            // 官方 MPVView.kt:98-99 用 tls-verify=yes + filesDir/cacert.pem。
            // 我们没有这个 ca bundle，留 yes 会让 https 片源在证书链不全时直接失败，
            // 电视盒子场景（片源多变、证书不规范）取 no 更实用；只连片源，不涉及登录凭据。
            core.setOptionString("tls-verify", "no")
            // 直通（官方 MpvPlayer.java:1778 `setOption("audio-spdif", config.audioSpdif())`）：
            //   AC3/EAC3/DTS 这类码流原样交给 HDMI，盒子不解码，功放才拿得到多声道。
            //   不设的话这些音轨会被 mpv 解成 PCM 立体声，听感直接降级。
            core.setOptionString("audio-spdif", "ac3,eac3,dts,dts-hd,truehd")
            // 字幕自动挂载（官方默认 fuzzy）：同名字幕文件/内嵌字幕自动识别，
            //   关掉的话 MKV 里的内嵌字幕要手动选，电视盒子上基本等于没有。
            core.setOptionString("sub-auto", "fuzzy")
            // 官方 MPVView.kt:23 是无条件设 profile=fast（关掉 gradfun/deband 这类高成本滤镜）。
            // 这里做成设置页开关：T1 这类低端芯片默认开，想拿画质细节时可以关掉。
            if (mpvSet.mpvFastProfile) {
                core.setOptionString("profile", "fast")
            }
            // 缩放算法（官方 MPVView.kt:48-59 的 video_scale / video_downscale / video_tscale）。
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
        lastSurfaceW = width
        lastSurfaceH = height
        MpvDebug.log("[mpv] surfaceChanged ${width}x$height fmt=$format")
        if (!inited) return
        runCatching {
            core.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    private fun onSurfaceDestroyed(holder: SurfaceHolder) {
        MpvDebug.log("[mpv] surfaceDestroyed(SurfaceView) inited=$inited")
        surfaceReady = false
        attachedSurface = null
        if (inited) {
            // render API 下"停止输出"= 解绑 EGLSurface，vo 保持 libmpv。
            // 以前这里写 vo=null + force-window=no，等于把 render 输出整个拆掉，
            // 回来时再设 vo=gpu（见 attachSurface 注释），render context 就再也拿不到帧。
            runCatching { core.detachSurface() }
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
        attachedSurface = surface
        lastSurfaceW = width
        lastSurfaceH = height
        runCatching {
            core.attachSurface(surface, host.width.coerceAtLeast(1), host.height.coerceAtLeast(1))
            // ★这三行是照 mpv-android 官方 BaseMPVView 抄的，但那是"mpv 自己往 surface 推帧"的形态。
            //   我们走的是 render API：vo 恒为 libmpv，帧由 mpv 交给我们的 EGL，我们自己 eglSwapBuffers。
            //   在 render context 已经建好之后再 setProperty("vo","gpu")，等于让 mpv 丢掉 render 输出、
            //   去建一个自己的窗口 vo —— 结果就是 render 回调不再出帧，画面停在黑，
            //   而音频还在走（用户看到的"黑屏有声"）。surfaceDestroyed 里那句 vo=null 同理。
            //   render API 下 re-attach 只需要把 EGLSurface 绑到新窗口，vo / force-window 一律不许动。
            if (width > 0 && height > 0) {
                core.setPropertyString("android-surface-size", "${width}x$height")
            }
        }
        // 10bit 输出：整条链的最后一环是 Surface 的缓冲格式。
        // 只有系统 >= API26 才有 RGBA_1010102，且必须在这一帧分配之前设好，
        // 所以放在 attach 之前。拿不到就保持默认 8888，不影响 8bit 路径。
        runCatching {
            val h = (host as? SurfaceView)?.holder
            if (h != null) {
                val fmt = if (android.os.Build.VERSION.SDK_INT >= 26 && XhMpv.available)
                    android.graphics.PixelFormat.RGBA_1010102 else android.graphics.PixelFormat.RGBA_8888
                h.setFormat(fmt)
            }
        }
        surfaceReady = true
        if (playPending && pendingUrl.isNotBlank()) {
            playPending = false
            doPlay(pendingUrl)
        } else if (pendingResume && pendingUrl.isNotBlank()) {
            // 回前台时 onResume 早于 surface 可用：resume() 当时只记账，这里才真正下发 pause=false
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

    /** 页面回到前台后重新确认一次画面层（TextureView 可能被系统回收过） */
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

    fun release() {
        redrawHandler.removeCallbacksAndMessages(null)
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
        // 否则释放后视图的可见性变化还会回调进来，把已经 destroy 的 libmpv 再 init 一遍
        runCatching { (host as? SurfaceView)?.holder?.removeCallback(holderCallback) }
        attachedSurface = null
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

    /** 用缓存的 pause/eof，不在这里同步调 native（mpv_get_property 在 seek 期间会阻塞 UI） */
    fun isPlaying(): Boolean = inited && !paused && !eofReached

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

    fun pause() {
        if (!inited) return
        applyPause(true)
    }

    fun resume() {
        if (!inited) return
        if (eofReached) {
            eofReached = false
            runCatching { core.command(arrayOf("seek", "0", "absolute+exact")) }
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
    fun recoverRendering(): Boolean {
        val surface = attachedSurface
        // 入口无条件打点：之前几个早退分支是静默 return，日志里就看不出「调用过但被挡回去了」
        MpvDebug.log(
            "[mpv] 渲染自检 进入 inited=$inited surfaceReady=$surfaceReady " +
                "surface=${surface != null} valid=${surface?.isValid} vo=${runCatching { voNow() }.getOrElse { "读取异常" }}"
        )
        if (!inited || !surfaceReady) return false
        if (surface == null) return false
        if (!surface.isValid) return false
        val vo = voNow()
        if (vo != "空" && vo != "未初始化" && vo != "读取失败" && vo.isNotBlank()) {
            MpvDebug.log("[mpv] 渲染自检 vo=$vo（正常，不处理）")
            return false
        }
        MpvDebug.log("[mpv] 渲染自检 vo=$vo → 复位")
        // render API 下 vo 恒为 libmpv，帧在我们自己手里：
        // 不需要 detach/attach 重建渲染后端，直接把上一帧再画一遍即可 ——
        // 这正是"画面层空白一帧"能盖住、而重绘能盖住的那一类。
        // ★这里绝对不能重新 attach。
        //   attach 的实现是「eglDestroySurface 旧表面 → 为新窗口重建」，
        //   而 render context 里还有待上屏的帧，重复 attach 会让 mpv 拿到一个已销毁的 EGLSurface
        //   → native 层 SIGSEGV，真机表现正是「按菜单键瞬间闪退」。
        //   render API 下恢复画面的唯一动作就是：把上一帧再画一遍。
        runCatching { core.redrawNow() }
        MpvDebug.log("[mpv] vo 已复位 gpu")
        return true
    }

    /** 调试用：surface 是否还挂着（控制条呼出后黑屏要先看这个） */
    fun surfaceReadyNow(): Boolean = surfaceReady

    /** 主动把上一帧重绘上屏：窗口合成被扰动时用它兜住"空白一帧" */
    fun redrawNow() {
        if (!inited || !surfaceReady) return
        runCatching { core.redrawNow() }
    }

    /**
     * 窗口扰动期连续重绘。
     *
     * 只重绘一帧不够：层的 buffer 可能在合成之后才被系统丢弃，
     * 那一次 redrawNow 就白做了，屏幕上仍然空着。这里在 1.2 秒内每 33ms 重贴一次，
     * 只要 buffer 被丢弃，下一个 tick 就补上 —— 用户看到的是"从没黑过"。
     * 必须是 mpv：render API 下我们才有这个「同一帧反复上屏」的能力。
     */
    private val redrawHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var redrawUntil = 0L
    private val redrawTick = object : Runnable {
        override fun run() {
            if (android.os.SystemClock.uptimeMillis() > redrawUntil) return
            runCatching { core.redrawNow() }
            redrawHandler.postDelayed(this, 33)
        }
    }

    /** @param durationMs 覆盖"扰动 + 系统合成重算"整段窗口期 */
    fun redrawFor(durationMs: Long) {
        if (!inited || !surfaceReady) return
        redrawUntil = android.os.SystemClock.uptimeMillis() + durationMs
        redrawHandler.removeCallbacks(redrawTick)
        redrawHandler.post(redrawTick)
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
        if (!inited) return
        runCatching { core.setPropertyDouble("speed", speed.toDouble()) }
    }

    /** 缩放 5 档，对应原版 select_scale：原始 / 16:9 / 4:3 / 填充 / 裁剪 */
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
            val n = core.getPropertyInt("track-list/count") ?: 0
            for (i in 0 until n) {
                if (core.getPropertyString("track-list/$i/type") != kind) continue
                val id = core.getPropertyInt("track-list/$i/id") ?: continue
                val selected = core.getPropertyBoolean("track-list/$i/selected") ?: false
                val parts = ArrayList<String>()
                core.getPropertyString("track-list/$i/title")
                    ?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                core.getPropertyString("track-list/$i/lang")
                    ?.takeIf { it.isNotBlank() && it != "und" }?.let { parts.add(it) }
                if (kind == "video") {
                    val h = core.getPropertyInt("track-list/$i/demux-h")
                    if (h != null && h > 0) parts.add("${h}p")
                }
                if (kind == "audio") {
                    val ch = core.getPropertyInt("track-list/$i/demux-channel-count")
                    if (ch != null && ch > 0) parts.add("${ch}ch")
                }
                core.getPropertyString("track-list/$i/codec")
                    ?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                if (core.getPropertyBoolean("track-list/$i/external") == true) parts.add("外挂")
                out.add(MpvTrackInfo(id, if (parts.isEmpty()) "轨道 $id" else parts.joinToString(" · "), selected))
            }
            out
        }.getOrElse { emptyList() }
    }

    /** 选中某一路：aid / vid / sid 传轨道 id */
    fun selectTrack(kind: String, id: Int) {
        if (!inited) return
        runCatching { core.setPropertyString(propOf(kind), id.toString()) }
    }

    /** 关掉某一类轨道（aid=no / sid=no）。视轨不给这个入口，关了就是黑屏 */
    fun disableTracks(kind: String) {
        if (!inited) return
        runCatching { core.setPropertyString(propOf(kind), "no") }
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
            core.command(arrayOf("cycle", when (which) {
                "audio" -> "audio"
                "sub" -> "sub"
                else -> "video"
            }))
            when (which) {
                "audio" -> core.getPropertyInt("aid") ?: 0
                "sub" -> core.getPropertyInt("sid") ?: 0
                else -> core.getPropertyInt("vid") ?: 0
            }.toString()
        }.getOrDefault("0")
    }

    /** 硬解切换：对齐 EXO 的软解 / 硬解两档 */
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
                val startMs = pendingSeekMs
                pendingSeekMs = 0
                if (startMs > 1000) {
                    val sec = String.format(Locale.US, "%.3f", startMs / 1000.0)
                    runCatching { core.command(arrayOf("seek", sec, "absolute+exact")) }
                }
                onLoaded?.invoke()
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
