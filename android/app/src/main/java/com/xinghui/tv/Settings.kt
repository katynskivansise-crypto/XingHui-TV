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
