package com.xinghui.tv

import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView

/**
 * 画面层宿主：一块 SurfaceView，EXO 与 mpv 轮流接管。
 *
 * 与 5.6.0 PlaybackActivity 同构 —— 两边都是 media3 Player 的实现，
 * 换内核即 setPlayer(null) → setPlayer(next)，surface 由 setVideoSurfaceView 统一管理。
 */
class VideoHost(
    private val frame: AspectRatioFrameLayout,
    private val surface: SurfaceView,
    val subtitleView: SubtitleView,
) {

    val surfaceView: SurfaceView
        get() = surface

    /** 与 PlayerView 的 resize_mode 同义：0=fit 1=fixed_width 2=fixed_height 3=fill 4=zoom。 */
    var resizeMode: Int
        get() = frame.resizeMode
        set(value) {
            frame.resizeMode = value
            frame.requestLayout()
            surface.requestLayout()
        }

    val visibility: Int
        get() = frame.visibility

    fun post(action: () -> Unit) {
        surface.post { action() }
    }

    private var bound: Player? = null

    /**
     * 对应 PlaybackActivity.onSizeChanged:293 / onSurfaceAttached:296 —— 两处都只是
     * applyResizeMode(getScale())。换内核或视频尺寸变化后必须重应用一次，
     * 否则档位停在上一颗内核的状态上（mpv↔EXO 的 FIT/FILL 改写就会失配）。
     */
    var onResizeTargetChanged: (() -> Unit)? = null

    private val diagnostics = ArrayList<SurfaceHolder.Callback>()

    /**
     * AspectRatioFrameLayout 在 aspectRatio <= 0 时跳过整段调整逻辑，
     * 没有 PlayerView 就得自己维护这个值，否则 resize_mode 不生效。
     */
    private val videoSizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val h = videoSize.height
            val ratio = if (h == 0) 0f
            else videoSize.width * videoSize.pixelWidthHeightRatio / h
            // AspectRatioFrameLayout 在 aspectRatio <= 0 时整段 onMeasure 直接 return，
            // resize_mode 就完全不生效 —— 容器按 LayoutParams 填满、画面被拉伸。
            // PlayerView 内部也是这么设的（updateAspectRatio → contentFrame.setAspectRatio）。
            frame.setAspectRatio(ratio)
            frame.requestLayout()
            surface.requestLayout()
            MpvDebug.log("[vs] ${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio} ratio=${"%.3f".format(ratio)} mode=${frame.resizeMode}")
            onResizeTargetChanged?.invoke()
        }
    }

    fun addSurfaceCallback(cb: SurfaceHolder.Callback) {
        if (diagnostics.contains(cb)) return
        diagnostics.add(cb)
        surface.holder.addCallback(cb)
    }

    fun removeSurfaceCallback(cb: SurfaceHolder.Callback) {
        diagnostics.remove(cb)
        runCatching { surface.holder.removeCallback(cb) }
    }

    /** 松手：走到 Player.clearVideoSurfaceView，mpv 侧对应 handleClearVideoOutput。 */
    fun releaseOwner() {
        bind(null)
    }

    fun setPlayer(p: Player?) {
        bind(p)
    }

    private fun bind(p: Player?) {
        val old = bound
        if (old === p) return
        if (old != null) {
            runCatching { old.removeListener(videoSizeListener) }
            runCatching { old.setVideoSurfaceView(null) }
        }
        runCatching { p?.setVideoSurfaceView(surface) }
        runCatching { p?.addListener(videoSizeListener) }
        // 换内核时新引擎常已带尺寸，绑定后立即同步一次。
        // 但必须排除「还没有尺寸」的情况：刚 build 出来的播放器 videoSize 是 UNKNOWN(0,0)，
        // 照原样转发会让 aspectRatio 归零 —— AspectRatioFrameLayout.onMeasure 在
        // videoAspectRatio<=0 时整段 return，resize_mode 当场失效，画面按容器拉伸。
        // 原版 PlayerView 只在 onVideoSizeChanged 里设，从不主动清零，这里同口径。
        runCatching {
            val vs = p?.videoSize
            if (vs != null && vs.width > 0 && vs.height > 0) videoSizeListener.onVideoSizeChanged(vs)
        }
        bound = p
        onResizeTargetChanged?.invoke()
    }

    init {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                surface.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            }
        }
    }
}
