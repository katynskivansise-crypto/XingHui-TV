package com.xinghui.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 播放内核统一入口。
 *
 * 本版本已把 libmpv 内嵌进 APK（mpv-android 官方 arm64 构建，见 MpvPlayer.kt），
 * 设置里选 mpv 时，播放走本进程内的 libmpv + SurfaceView，控制条可以直接控它，
 * 不再需要用 Intent 拉起外部 mpv-android。外部调用仅作为兜底的兼容路径保留。
 */
object PlayerManager {

    /** mpv-android 应用包名 */
    const val MPV_PACKAGE = "is.xyz.mpv"

    /** 是否已安装 mpv-android */
    fun isMpvInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(MPV_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** 是否应使用 mpv：只看设置，内嵌内核不依赖外部 app 是否安装 */
    fun shouldUseMpv(context: Context): Boolean =
        Settings(context).playerKernel == Settings.KERNEL_MPV

    /**
     * 兼容路径：把地址交给外部 mpv-android（内嵌内核不可用时兜底）。
     * 正常情况下 PlayActivity 走 MpvPlayer，不再调用这里。
     * @return true=成功交给 mpv；false=失败（未安装/无法启动），调用方应回退 ExoPlayer
     */
    fun launchMpv(context: Context, url: String, title: String?): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(MPV_PACKAGE)
                putExtra(Intent.EXTRA_TITLE, title ?: "星汇TV")
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "无法启动 mpv，请先安装 mpv-android", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
