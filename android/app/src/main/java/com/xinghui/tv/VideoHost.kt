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
            frame.setAspectRatio(ratio)
            frame.requestLayout()
            surface.requestLayout()
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
        // 换内核时新引擎常已带尺寸，绑定后立即同步一次
        runCatching { p?.videoSize?.let { videoSizeListener.onVideoSizeChanged(it) } }
        bound = p
    }

    init {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                surface.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            }
        }
    }
}
