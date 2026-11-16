package com.xinghui.tv

import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView

/**
 * 画面层宿主：EXO 与 mpv 各用一块独立的 SurfaceView。
 *
 * 为什么两块，而不是共用一块：
 *   EXO 通过 Player.setVideoSurfaceView 让 media3 自己挂 SurfaceHolder.Callback，
 *   mpv 通过 MPVLib.attachSurface 自己挂一套。两家同时挂在一块面上时，任何一方
 *   松手都会让另一方收到 surfaceDestroyed —— 换内核时出现的"
 *   存活实例=0 / 双实例 / 换回 EXO 黑屏"都源于此。分成两块后，两块面各归各的引擎，
 *   谁也不会收到别人的回调。
 *
 * 为什么另一块不是 GONE（关键）：
 *   media3 官方「Surface 类型」页原文：
 *     「SurfaceView 的 Surface 生命周期与视图可见性相关联……在 Android 14 之前，
 *       可以通过在回收视图时将其平移到屏幕外来解决 Surface 被销毁的问题。」
 *     「从 Android 14 开始，PlayerView 使用
 *       SurfaceView.setSurfaceLifecycle(SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT) 来避免此行为。」
 *   所以：
 *     API >= 34：开 FOLLOWS_ATTACHMENT 后置 INVISIBLE，官方保证不销毁；
 *     API <  34：把整块面平移到容器宽度之外，保持 VISIBLE —— 这是官方给旧版本的唯一手段。
 *   绝不设 GONE/INVISIBLE：那会立刻触发 surfaceDestroyed，再变回来就是重建，中间必黑。
 *
 * 关于"电视只有一块硬件视频平面"：
 *   AOSP「实现硬件混合渲染器 HAL」页要求 HWC 至少支持 4 个叠加层；
 *   叠加层不够时 SurfaceFlinger 会退回 GLES / MIXED 合成，画面照样有。
 *   所以两块面同时存在不会出现"第二块拿不到画面"。
 *
 * 约定：绝不改 frame 或任一块 SurfaceView 的 visibility/GONE —— 停泊只走 [park]。
 */
class VideoHost(
    private val frame: AspectRatioFrameLayout,
    /** EXO 专用面 */
    private val exoSurface: SurfaceView,
    /** mpv 专用面 */
    private val mpvSurface: SurfaceView,
    val subtitleView: SubtitleView,
) {

    /** 当前在屏内、正在输出的那一块。对外兼容旧调用点（诊断、日志用）。 */
    val surfaceView: SurfaceView
        get() = if (nativeActive) mpvSurface else exoSurface

    /** 当前活跃引擎是不是 mpv。 */
    var nativeActive: Boolean = false
        private set

    /**
     * 与 PlayerView 的 resize_mode 同义（0=fit 1=fixed_width 2=fixed_height 3=fill 4=zoom）。
     *
     * 设完之后两层都要 requestLayout —— 依据 5.6.0 PlaybackActivity.applyResizeMode 原文：
     *     view.setResizeMode(effectiveResizeMode);
     *     view.requestLayout();
     *     View surface = view.getVideoSurfaceView();
     *     if (surface != null) surface.requestLayout();
     * 只改 resizeMode 不 requestLayout，已经算好的那一帧不会重排，表现就是"选了缩放画面没变"。
     */
    var resizeMode: Int
        get() = frame.resizeMode
        set(value) {
            frame.resizeMode = value
            frame.requestLayout()
            surfaceView.requestLayout()
        }

    val visibility: Int
        get() = frame.visibility

    fun post(action: () -> Unit) {
        surfaceView.post { action() }
    }

    private var bound: Player? = null

    private var nativeRelease: (() -> Unit)? = null

    private var nativeAttach: ((SurfaceHolder) -> Unit)? = null

    /** 诊断回调：跟着"当前活跃那块"走，切换时自动迁移。 */
    private val diagnostics = ArrayList<SurfaceHolder.Callback>()

    /**
     * 视频宽高比同步 —— 不做这一步，resize_mode 等于没设。
     *
     * media3 AspectRatioFrameLayout.onMeasure 原文：
     *     if (aspectRatio <= 0f) { return; }     // 比例为 0，整段调整逻辑直接跳过
     * 原版 PlayerView 内部有个 ComponentListener 一直维护这个值，公式是：
     *     aspectRatio = height == 0 ? 0f
     *                 : width * pixelWidthHeightRatio / height
     * 我们不用 PlayerView（布局里已经没有它），这一层必须自己补，否则五档缩放全都没反应。
     *
     * pixelWidthHeightRatio 处理变形像素（宽银幕），不能省。
     * unappliedRotationDegrees 不用管：ExoPlayer VideoListener 文档写明
     *   "This value will always be zero on API levels 21 and above, since the renderer
     *    will apply all necessary rotations internally."
     */
    private val videoSizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val h = videoSize.height
            val ratio = if (h == 0) 0f
            else videoSize.width * videoSize.pixelWidthHeightRatio / h
            frame.setAspectRatio(ratio)
            frame.requestLayout()
            surfaceView.requestLayout()
        }
    }

    /**
     * 把它停泊到屏外，Surface 保持存活。
     *
     * API >= 34 用 INVISIBLE：此时 PlayerView 同款的 FOLLOWS_ATTACHMENT 已经开在面上了，
     * 系统不会因不可见而销毁 Surface（官方原文见类注释）。
     * API <  34 只能用平移：官方给的旧版本规避手段。
     */
    private fun park(target: SurfaceView) {
        if (Build.VERSION.SDK_INT >= 34) {
            target.visibility = View.INVISIBLE
        } else {
            // 容器还没量出宽度（init 阶段）时平移量是 0，此时那块面里还没有任何引擎输出，
            // 重叠无害；真正的停泊发生在第一次 selectXxx（那时布局已完成）。
            target.translationX = frame.width.toFloat()
            target.translationY = 0f
            target.visibility = View.VISIBLE
        }
    }

    /** 把它放回屏内。 */
    private fun restore(target: SurfaceView) {
        target.translationX = 0f
        target.translationY = 0f
        target.visibility = View.VISIBLE
    }

    private fun surfaceOf(native: Boolean): SurfaceView = if (native) mpvSurface else exoSurface

    /**
     * mpv 那块面的 holder 事件转发。
     *
     * MpvPlayer 已把主权交给本类（它自己不再注册 callback），所以 mpvSurface 的
     * created / changed / destroyed 必须由这里转给它，否则 mpv 永远 attach 不上。
     * 只挂在 mpvSurface 上，不碰 exoSurface（EXO 的 holder 由 media3 自己管）。
     */
    private val nativeForwarder = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) {
            runCatching { nativeAttach?.invoke(h) }
        }

        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            runCatching { nativeAttach?.invoke(h) }
        }

        override fun surfaceDestroyed(h: SurfaceHolder) {
            runCatching { nativeRelease?.invoke() }
        }
    }

    /**
     * 换到 EXO：EXO 那块回到屏内，mpv 那块停泊。
     * 先 restore 再 park，避免中间出现"两块都在屏内"的一帧（叠加输出）。
     */
    private fun selectExo() {
        restore(exoSurface)
        park(mpvSurface)
        nativeActive = false
        rebindDiagnostics()
    }

    /**
     * 换到 mpv：反向。
     */
    private fun selectNative() {
        restore(mpvSurface)
        park(exoSurface)
        nativeActive = true
        rebindDiagnostics()
    }

    /**
     * EXO 接管画面。
     *
     * 官方约定：Player.setVideoSurfaceView 内部自己挂 SurfaceHolder.Callback，
     * surface 还没好时先记下、等 created 再 attach；反方向必须先 clear 再换人，
     * 否则旧播放器还握着那块面。
     *
     * 这里只动 exoSurface，绝不碰 mpvSurface。
     */
    fun takeExo(p: Player?) {
        if (bound != null && bound !== p) bindExo(null)
        selectExo()
        bindExo(p)
    }

    /**
     * mpv 接管画面。
     *
     * attach/release 由 MpvPlayer 传入，只作用于 mpvSurface。
     * 那块面已经创建过就立刻 attach，没创建过就等它自己的 surfaceCreated。
     */
    fun takeNative(attach: (SurfaceHolder) -> Unit, release: () -> Unit): Boolean {
        if (bound != null) bindExo(null)
        nativeAttach = attach
        nativeRelease = release
        selectNative()
        val h = mpvSurface.holder
        if (h.surface?.isValid == true) runCatching { attach(h) }
        return true
    }

    /**
     * 两边都松手（退出播放页 / 释放播放器时）。
     */
    fun releaseOwner() {
        bindExo(null)
        runCatching { nativeRelease?.invoke() }
        nativeAttach = null
        nativeRelease = null
    }

    /** 诊断回调：加到当前活跃那块面上。 */
    fun addSurfaceCallback(cb: SurfaceHolder.Callback) {
        if (diagnostics.contains(cb)) return
        diagnostics.add(cb)
        surfaceView.holder.addCallback(cb)
    }

    /** 诊断回调：从它挂着的所有面上摘掉。 */
    fun removeSurfaceCallback(cb: SurfaceHolder.Callback) {
        diagnostics.remove(cb)
        runCatching { exoSurface.holder.removeCallback(cb) }
        runCatching { mpvSurface.holder.removeCallback(cb) }
    }

    /** 切换引擎时，把诊断回调从旧面迁到新面。 */
    private fun rebindDiagnostics() {
        if (diagnostics.isEmpty()) return
        for (cb in diagnostics) {
            runCatching { exoSurface.holder.removeCallback(cb) }
            runCatching { mpvSurface.holder.removeCallback(cb) }
            surfaceView.holder.addCallback(cb)
        }
    }

    init {
        // API >= 34 才有的官方开关：Surface 跟随 attach，不随可见性销毁。
        if (Build.VERSION.SDK_INT >= 34) {
            for (sv in arrayOf(exoSurface, mpvSurface)) {
                runCatching {
                    sv.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
                }
            }
        }
        // mpv 那块面的 holder 事件由本类转给 MpvPlayer。
        mpvSurface.holder.addCallback(nativeForwarder)
        // 初始 EXO 在屏内，mpv 停泊。
        restore(exoSurface)
        park(mpvSurface)
    }

    /**
     * 与 EXO 的唯一接线点（对外入口名沿用 setPlayer）。
     *
     * 官方 Player 的约定：setVideoSurfaceView 内部会自己挂 SurfaceHolder 回调，
     * surface 还没创建好时先记下、等 created 再 attach，所以不需要我们提前判断。
     * 反方向必须先 clear 再换人，否则旧播放器还握着那块面。
     */
    fun setPlayer(p: Player?) {
        bindExo(p)
    }

    private fun bindExo(p: Player?) {
        val old = bound
        if (old === p) return
        runCatching { old?.removeListener(videoSizeListener) }
        runCatching { if (old != null && old !== p) old.setVideoSurfaceView(null) }
        runCatching { p?.setVideoSurfaceView(exoSurface) }
        runCatching { p?.addListener(videoSizeListener) }
        // 挂上去时引擎可能已经有视频尺寸（换内核常这样），立刻同步一次
        runCatching { p?.videoSize?.let { videoSizeListener.onVideoSizeChanged(it) } }
        bound = p
    }
}
