package com.xinghui.tv

import android.content.Context

/**
 * 应用设置存储（SharedPreferences 持久化）。
 * 播放内核（内置 ExoPlayer / 外部 mpv）、网络超时、海报显示开关。
 */
class Settings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 播放内核：内置 ExoPlayer */
    val kernelExo: String = KERNEL_EXO

    /** 播放内核：外部 mpv-android（is.xyz.mpv） */
    val kernelMpv: String = KERNEL_MPV

    /** 当前选择的播放内核，默认内置 ExoPlayer */
    var playerKernel: String
        get() = prefs.getString(KEY_PLAYER_KERNEL, KERNEL_EXO) ?: KERNEL_EXO
        set(value) = prefs.edit().putString(KEY_PLAYER_KERNEL, value).apply()

    /** 网络下载超时（秒），默认 15 */
    var networkTimeoutSec: Int
        get() = prefs.getInt(KEY_TIMEOUT, 15)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT, value).apply()

    /** 是否显示影片海报，默认显示 */
    var showPoster: Boolean
        get() = prefs.getBoolean(KEY_POSTER, true)
        set(value) = prefs.edit().putBoolean(KEY_POSTER, value).apply()

    /** 首页是否显示历史记录横排（对齐原项目 Setting.isHomeHistory），默认开 */
    var isHomeHistory: Boolean
        get() = prefs.getBoolean(KEY_HOME_HISTORY, true)
        set(value) = prefs.edit().putBoolean(KEY_HOME_HISTORY, value).apply()

    /** 全局 User-Agent（对齐原项目设置 UA），默认空=引擎内置 */
    var ua: String
        get() = prefs.getString(KEY_UA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UA, value).apply()

    /** 代理地址 scheme://user:pass@host:port（对齐原项目代理设置），空=关闭 */
    var proxy: String
        get() = prefs.getString(KEY_PROXY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROXY, value).apply()

    /** 直播 EPG 源（XMLTV 地址），空=不显示节目单（对齐原项目 EPG 数据源） */
    var epgUrl: String
        get() = prefs.getString(KEY_EPG_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EPG_URL, value).apply()

    /** 直播源（diyp JSON / txt / m3u 地址），空=直播页仅显示收藏（对齐原项目 LiveConfig） */
    var liveUrl: String
        get() = prefs.getString(KEY_LIVE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LIVE_URL, value).apply()

    // ==== 对齐原版 SettingCustomActivity（个性设置）====
    /** 选集样式：0=单行，1=多行（详情页集数网格） */
    var episodeStyle: Int
        get() = prefs.getInt("episode_style", 1)
        set(value) = prefs.edit().putInt("episode_style", value).apply()

    /** 首页 MENU 键：0选项弹窗 1切换站源 2切换线路 3直播 4历史 5搜索 6推送 7收藏 8设置 */
    var homeMenuKey: Int
        get() = prefs.getInt("home_menu_key", 8)
        set(value) = prefs.edit().putInt("home_menu_key", value).apply()

    /** 全屏 MENU 键：0=控制栏，1=选集 */
    var fullscreenMenuKey: Int
        get() = prefs.getInt("fullscreen_menu_key", 0)
        set(value) = prefs.edit().putInt("fullscreen_menu_key", value).apply()

    /** 小窗返回键：0=返回，1=焦点移动 */
    var smallWindowBackKey: Int
        get() = prefs.getInt("small_window_back_key", 0)
        set(value) = prefs.edit().putInt("small_window_back_key", value).apply()

    // ==== 播放核心：EXO / mpv 两套参数彼此独立 ====
    // 两个内核支持的东西不一样，参数不能共用一个键：
    // EXO（media3）走 RenderersFactory / resizeMode / MediaSource 那一套；
    // mpv 走 hwdec / profile / deband / interpolation 那一套（见官方 MPVView.initOptions）。
    // 共用一个开关的后果是「在 EXO 选软解，切到 mpv 后变成 hwdec=no」，两边都别扭。

    /**
     * EXO 缓冲秒数（对应原版 `exo_buffer`，原版允许 1~15）。
     * 原版这个值存了但没人读；我们把它真正接到 DefaultLoadControl 上，
     * 由它决定「起播前要攒多少数据」和「卡顿重缓冲后要等多久再播」。
     */
    var exoBufferSec: Int
        get() = prefs.getInt("exo_buffer", 5).coerceIn(1, 15)
        set(value) = prefs.edit().putInt("exo_buffer", value.coerceIn(1, 15)).apply()

    // ==== mpv 缩放算法（官方 MPVView.kt:48-59 的 video_scale / video_downscale / video_tscale）====
    // 官方做法是「偏好里取字符串，空则不下发」，空 = 走 mpv 编译默认（bilinear）。
    // 我们照抄这个语义：默认空串，只有用户选过才下发。
    // 实测 libmpv.so 里硬编码 scale=bilinear / dscale=bilinear，所以默认档就是 bilinear。

    /** 放大算法（官方 video_scale → scale）。空 = 不下发，走 bilinear */
    var mpvScale: String
        get() = prefs.getString("mpv_scale", "") ?: ""
        set(value) = prefs.edit().putString("mpv_scale", value).apply()

    /** 缩小算法（官方 video_downscale → dscale）。空 = 不下发，走 bilinear */
    var mpvDscale: String
        get() = prefs.getString("mpv_dscale", "") ?: ""
        set(value) = prefs.edit().putString("mpv_dscale", value).apply()

    /** 时域缩放算法（官方 video_tscale → tscale）。空 = 不下发，走 bilinear */
    var mpvTscale: String
        get() = prefs.getString("mpv_tscale", "") ?: ""
        set(value) = prefs.edit().putString("mpv_tscale", value).apply()

    /**
     * 字幕样式（对应原版 select_caption：Default / System）。
     * 0=预设：白字 + 黑描边（原版 CaptionStyleCompat(WHITE, TRANSPARENT, TRANSPARENT, EDGE_TYPE_OUTLINE, BLACK, null)）
     * 1=系统：跟随系统「字幕样式」设置（CaptioningManager.getUserStyle()）
     * 原版真消费点：ExoUtil.applySubtitleStyle() → SubtitleView.setStyle()
     */
    var captionMode: Int
        get() = prefs.getInt("caption_mode", 0)
        set(value) = prefs.edit().putInt("caption_mode", value.coerceIn(0, 1)).apply()

    /**
     * 线路播放（对应原版 select_flag：Auto / Manual，原版 key 就叫 flag）。
     * 0=全自动：一集播完自动切下一集（原版 VideoActivity.seamless() 的 else 分支）
     * 1=手动：播完只提示，等用户自己选（原版 true 分支只做临时高亮、不写历史）
     */
    var flagMode: Int
        get() = prefs.getInt("flag", 0)
        set(value) = prefs.edit().putInt("flag", value.coerceIn(0, 1)).apply()

    /** EXO 解码方式：0=软解（过滤硬件 codec），1=硬解。EXO 专属，mpv 见 mpvHwdec */
    var decodeMode: Int
        get() = prefs.getInt("decode_mode", 1)
        set(value) = prefs.edit().putInt("decode_mode", value).apply()

    /** 隧道模式：0=关，1=开 */
    var tunnelMode: Int
        get() = prefs.getInt("tunnel_mode", 0)
        set(value) = prefs.edit().putInt("tunnel_mode", value).apply()

    /**
     * mpv 快速档（对应 mpv 的 profile=fast）。
     * 官方 mpv-android 无条件开着它 —— 会关掉 gradfun/deband 这类高成本滤镜。
     * 低端盒子（如 T1）建议保持开启；对画质有要求时可以关。
     */
    var mpvFastProfile: Boolean
        get() = prefs.getBoolean(KEY_MPV_FAST, true)
        set(value) = prefs.edit().putBoolean(KEY_MPV_FAST, value).apply()

    /** mpv 硬件解码（官方 key hardware_decoding → hwdec=mediacodec / no），默认开 */
    var mpvHwdec: Boolean
        get() = prefs.getBoolean("mpv_hwdec", true)
        set(value) = prefs.edit().putBoolean("mpv_hwdec", value).apply()

    /**
     * mpv 去色带（官方 key video_debanding）：0=关，1=gradfun（vf=gradfun=radius=12），2=gpu（deband=yes）。
     * 吃性能，默认关。
     */
    var mpvDeband: Int
        get() = prefs.getInt("mpv_deband", 0)
        set(value) = prefs.edit().putInt("mpv_deband", value).apply()

    /** mpv 插帧（官方 key video_interpolation），默认关；开启时必须配 video-sync=display-resample */
    var mpvInterpolation: Boolean
        get() = prefs.getBoolean("mpv_interpolation", false)
        set(value) = prefs.edit().putBoolean("mpv_interpolation", value).apply()

    /** mpv 快速解码（官方 key video_fastdecode：vd-lavc-fast + skiploopfilter），默认关 */
    var mpvFastDecode: Boolean
        get() = prefs.getBoolean("mpv_fastdecode", false)
        set(value) = prefs.edit().putBoolean("mpv_fastdecode", value).apply()

    /** mpv 缓存大小（MB），官方按 SDK 给 64 / 32，默认 64 */
    var mpvCacheMb: Int
        get() = prefs.getInt("mpv_cache_mb", 64)
        set(value) = prefs.edit().putInt("mpv_cache_mb", value).apply()

    /** UA 当前档位中文名，用于设置界面展示 */
    fun uaLabel(): String = when (ua) {
        "" -> "默认"
        UA_TV -> "Android TV"
        UA_PHONE -> "Android 手机"
        UA_IOS -> "iPhone"
        UA_PC -> "Windows Chrome"
        else -> "自定义"
    }

    /** 界面大小（对齐原版 Setting.getSize：小/中/大/特大），默认 2=大 */
    var uiSize: Int
        get() = prefs.getInt(KEY_UI_SIZE, 2)
        set(value) = prefs.edit().putInt(KEY_UI_SIZE, value).apply()

    /** 画面缩放（对齐原版 Setting.getScale：原始/16:9/4:3/填充/裁剪），默认 0=原始 */
    var playerScale: Int
        get() = prefs.getInt(KEY_PLAYER_SCALE, 0)
        set(value) = prefs.edit().putInt(KEY_PLAYER_SCALE, value).apply()

    fun uiSizeLabel(): String = UI_SIZE_LABELS.getOrElse(uiSize) { "大" }
    fun playerScaleLabel(): String = SCALE_LABELS.getOrElse(playerScale) { "原始" }

    /** 设置项的中文名称，用于设置界面展示 */
    fun kernelLabel(): String =
        if (playerKernel == KERNEL_MPV) "mpv（内置）" else "ExoPlayer（内置）"

    companion object {
        private const val KEY_MPV_FAST = "mpv_fast_profile"
        const val KERNEL_EXO = "exo"
        const val KERNEL_MPV = "mpv"

        // 预设 UA 档位（对齐原项目 select_ua）
        const val UA_TV = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36"
        const val UA_PHONE = "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val UA_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
        const val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val PREFS = "xinghui_prefs"
        private const val KEY_PLAYER_KERNEL = "player_kernel"
        private const val KEY_TIMEOUT = "network_timeout"
        private const val KEY_POSTER = "show_poster"
        private const val KEY_HOME_HISTORY = "home_history"
        private const val KEY_UA = "ua"
        private const val KEY_PROXY = "proxy"
        private const val KEY_EPG_URL = "epg_url"
        private const val KEY_LIVE_URL = "live_url"
        private const val KEY_UI_SIZE = "ui_size"
        private const val KEY_PLAYER_SCALE = "player_scale"

        /** 界面大小档位（对齐原版 select_size：小/中/大/特大） */
        val UI_SIZE_LABELS = arrayOf("小", "中", "大", "特大")

        /** 画面缩放档位（对齐原版 select_scale：原始/16:9/4:3/填充/裁剪） */
        val SCALE_LABELS = arrayOf("原始", "16:9", "4:3", "填充", "裁剪")
    }
}
