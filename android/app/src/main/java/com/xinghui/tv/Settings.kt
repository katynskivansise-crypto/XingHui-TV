package com.xinghui.tv

import android.content.Context

/**
 * 应用设置存储（SharedPreferences 持久化）。
 * 提供：播放内核（内置 ExoPlayer / 外部 mpv）、播放内核切换时的清理标记。
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

    /** 设置项的中文名称，用于设置界面展示 */
    fun kernelLabel(): String =
        if (playerKernel == KERNEL_MPV) "mpv（外部播放器）" else "ExoPlayer（内置）"

    companion object {
        const val KERNEL_EXO = "exo"
        const val KERNEL_MPV = "mpv"

        private const val PREFS = "xinghui_prefs"
        private const val KEY_PLAYER_KERNEL = "player_kernel"
    }
}
//（注：内容由AI生成）
