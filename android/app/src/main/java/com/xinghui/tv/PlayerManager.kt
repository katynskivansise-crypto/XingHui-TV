package com.xinghui.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 播放内核统一入口。
 * 根据设置决定使用内置 ExoPlayer 还是调用外部 mpv-android（is.xyz.mpv）。
 *
 * 说明：真正把 libmpv 内嵌进 APK 需要在 CI 里交叉编译 ffmpeg+libmpv，
 * 体积与稳定性风险都很高。本实现采用 TVBox 生态通用的做法 ——
 * 安装 mpv-android（is.xyz.mpv）后，通过 Intent 把解析出的播放地址交给它播放，
 * 未安装或启动失败时自动回退到内置 ExoPlayer。
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

    /** 是否应使用 mpv（设置在 mpv 内核 且 已安装 mpv-android） */
    fun shouldUseMpv(context: Context): Boolean =
        Settings(context).playerKernel == Settings.KERNEL_MPV && isMpvInstalled(context)

    /**
     * 启动 mpv-android 播放。
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
