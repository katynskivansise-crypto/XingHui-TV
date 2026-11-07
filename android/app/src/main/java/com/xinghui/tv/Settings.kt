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

    /** 返回键/菜单键配置是否生效，见 MainActivity.dispatchKeyEvent */
    var keyConfigEnabled: Boolean
        get() = prefs.getBoolean("key_config_enabled", true)
        set(value) = prefs.edit().putBoolean("key_config_enabled", value).apply()

    // ==== 对齐原版 SettingPlayerActivity（播放核心）====
    /** 播放核心：0=EXO，1=IJK（本壳仅内置 EXO，IJK 选项保留定义） */
    var playCore: Int
        get() = prefs.getInt("play_core", 0)
        set(value) = prefs.edit().putInt("play_core", value).apply()

    /** 解码方式：0=软解，1=硬解 */
    var decodeMode: Int
        get() = prefs.getInt("decode_mode", 1)
        set(value) = prefs.edit().putInt("decode_mode", value).apply()

    /** 渲染方式：0=Surface，1=Texture */
    var renderMode: Int
        get() = prefs.getInt("render_mode", 0)
        set(value) = prefs.edit().putInt("render_mode", value).apply()

    /** 缩放比例：0原始 1=16:9 2=4:3 3填充 4裁剪 */
    var videoScale: Int
        get() = prefs.getInt("video_scale", 0)
        set(value) = prefs.edit().putInt("video_scale", value).apply()

    /** 隧道模式：0=关，1=开 */
    var tunnelMode: Int
        get() = prefs.getInt("tunnel_mode", 0)
        set(value) = prefs.edit().putInt("tunnel_mode", value).apply()

    /** AAC 优先：0=关，1=开 */
    var aacFirst: Int
        get() = prefs.getInt("aac_first", 0)
        set(value) = prefs.edit().putInt("aac_first", value).apply()

    /** 后台播放：0=关，1=开 */
    var backgroundPlay: Int
        get() = prefs.getInt("background_play", 1)
        set(value) = prefs.edit().putInt("background_play", value).apply()

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
        if (playerKernel == KERNEL_MPV) "mpv（外部播放器）" else "ExoPlayer（内置）"

    companion object {
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
