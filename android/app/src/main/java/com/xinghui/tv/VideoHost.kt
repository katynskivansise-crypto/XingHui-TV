package com.xinghui.tv

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView

/**
 * 画面层宿主 —— 全页唯一的一份，EXO 与 mpv 都往这一块 SurfaceView 输出。
 *
 * 结构刻意不用 media3 的 PlayerView：
 *
 *   PlayerView 内部带着一套自己的状态机，我们控制不了、也不该控制：
 *     · setVisibility(v) 会连带把内部 SurfaceView 置成同一可见性
 *       （PlayerView.java:616-620）→ 老 ROM 上就是 surface 销毁重建；
 *     · player 为 null 或 tracks 为空时 closeShutter()，盖一块不透明黑
 *       （PlayerView.java:1399/1406/1417/1475）→ player=null 那一瞬必然黑；
 *     · 没有渲染目标时解码器转去 placeholderSurface，那块面上什么都不画
 *       （MediaCodecVideoRenderer.java:158/802-808）；
 *     · 它自己还会维护 artwork / buffering / error 三个内部视图，与我们的浮层重复。
 *
 * 这里改用三个官方公开类，行为等价、契约干净：
 *     AspectRatioFrameLayout  —— resize_mode 与 PlayerView 内部用的是同一个类同一个枚举
 *     SurfaceView            —— 生死只由本类的 attach/detach 决定，外界一律不碰
 *     SubtitleView           —— 字幕样式/渲染走官方，不再依赖 PlayerView.subtitleView
 *
 * 约定：**永远不要改 frame 或 surfaceView 的 visibility**。要遮挡请用页面上层浮层。
 * 这块 surface 一旦被置 GONE/INVISIBLE，老 ROM 会销毁并重建它，重建期间就是黑。
 */
class VideoHost(
    private val frame: AspectRatioFrameLayout,
    /** 官方 PlayerView。画面由它内部那个 SurfaceView 承载（surface_type=surface_view）。 */
    val playerView: PlayerView,
    val subtitleView: SubtitleView,
) {
    /**
     * PlayerView 内部那块 SurfaceView。
     * surface_type=surface_view 时它构造完就存在，所以这里可以直接强取；
     * 取不到说明布局里的 surface_type 被改坏了，应该立刻暴露而不是静默降级。
     */
    val surfaceView: SurfaceView = (playerView.videoSurfaceView as? SurfaceView)
        ?: throw IllegalStateException("PlayerView 内没有 SurfaceView，检查布局的 surface_type")
    /**
     * 与 PlayerView 的 resize_mode 完全同义（0=fit 1=fixed_width 2=fixed_height 3=fill 4=zoom）
     *
     * ★设置之后必须两层都 requestLayout。原版 PlaybackActivity.applyResizeMode 的原文：
     *     view.setResizeMode(effectiveResizeMode);
     *     view.requestLayout();
     *     View surface = view.getVideoSurfaceView();
     *     if (surface != null) surface.requestLayout();
     *   只改 resizeMode 而不 requestLayout，尺寸已经算好的那一帧不会重排，
     *   表现就是「选了缩放但画面没变」。
     */
    var resizeMode: Int
        get() = frame.resizeMode
        set(value) {
            frame.resizeMode = value
            frame.requestLayout()
            surfaceView.requestLayout()
        }

    /** 只读：页面里有些诊断要读它，但不允许任何人改 */
    val visibility: Int
        get() = frame.visibility

    fun post(action: () -> Unit) {
        surfaceView.post { action() }
    }

    private var bound: Player? = null

    /**
     * 这块 Surface 现在归谁。
     *
     * ★为什么必须有这个状态：EXO 和 mpv 都会往同一块 SurfaceView 上挂 SurfaceHolder.Callback
     *   （media3 的 setVideoSurfaceView 内部挂一套，MpvPlayer 自己又挂一套），两边都以为自己是主人。
     *   原项目是靠 PlayerView 上自建的 render 状态机（RENDER_SURFACE / RENDER_NONE / RENDER_TEXTURE）
     *   保证"同一时刻只有一方拿到渲染权限"，我们一直没有这一层，于是谁都能往上面画。
     *   Owner 就是补上这一层：任何一方要接管，必须先让当前主人松手。
     */
    enum class Owner { NONE, EXO, NATIVE }

    var owner: Owner = Owner.NONE
        private set

    /** native 侧交回控制权时用的回调（由 MpvPlayer 提供，它负责 detach 与 vo=null） */
    private var nativeRelease: (() -> Unit)? = null

    /** native 侧拿到 Surface 后要做的事（由 MpvPlayer 提供，它负责 attach 与 vo=gpu） */
    private var nativeAttach: ((SurfaceHolder) -> Unit)? = null

    /**
     * EXO 接管画面。
     * 顺序仍是官方的"先挂新的、再摘旧的"，但要多一步：**native 必须先松手**。
     * 否则 mpv 还握着这块 surface，EXO 的解码器接上去就是两家同时输出。
     */
    fun takeExo(p: Player?) {
        if (owner == Owner.NATIVE) {
            runCatching { nativeRelease?.invoke() }
            nativeRelease = null
            owner = Owner.NONE
        }
        setPlayer(p)
        if (p != null) owner = Owner.EXO
    }

    /** native（mpv）接管画面。EXO 必须先 clear 掉，理由同上。 */
    fun takeNative(attach: (SurfaceHolder) -> Unit, release: () -> Unit): Boolean {
        if (owner == Owner.EXO) {
            setPlayer(null)
            owner = Owner.NONE
        }
        nativeAttach = attach
        nativeRelease = release
        val h = surfaceView.holder
        owner = Owner.NATIVE
        // surface 已经创建过就立刻 attach；没创建过则等 VideoHost 转发的 surfaceCreated
        if (h.surface?.isValid == true) runCatching { attach(h) }
        return true
    }

    /** 让当前主人松手，交回 NONE。换内核前必须调。 */
    fun releaseOwner() {
        when (owner) {
            Owner.EXO -> setPlayer(null)
            Owner.NATIVE -> runCatching { nativeRelease?.invoke() }
            Owner.NONE -> Unit
        }
        nativeAttach = null
        nativeRelease = null
        owner = Owner.NONE
    }

    /**
     * 重建这块 Surface。
     *
     * ★依据（原项目 PlayerManager.switchPlayer 的注释，原文）：
     *   "A Surface used by a CPU renderer stays connected after decoder release.
     *    A different player needs a new Surface before its GPU/codec can connect."
     *   所以换内核时原项目是强制 resetVideoSurface 的（switchPlayer 里写死传 true），
     *   具体动作是把 SurfaceView 从容器里摘掉再挂回去，逼出一次 destroyed→created。
     *   不重建的话，新的 GPU/codec 会挂在一块还带着上一个内核残留的 surface 上。
     *
     * 代价是一次 surfaceDestroyed→surfaceCreated，画面会黑一帧 —— 换内核本来就是重操作，
     * 原项目也接受这个代价。**不要**把它用在呼条、改布局这类轻动作上。
     */
    fun recreateSurface() {
        // ★surfaceView 现在是 PlayerView 的子 View，不能再从父容器摘出来（会破坏 PlayerView 结构）。
        //   官方 PlayerView 没有公开的「重建 surface」接口，等价做法是 detach → attach：
        //   原项目用的是 setRender 切两次（RENDER_SURFACE ↔ RENDER_TEXTURE）来逼一次重建，
        //   我们只有一个渲染目标，就用 setPlayer(null) → 下一帧 setPlayer(p) 逼近同样效果。
        val p = bound
        setPlayer(null)
        MpvDebug.log("[surf] 重建 surface owner=$owner")
        surfaceView.post { if (bound == null && p != null) setPlayer(p) }
    }

    /** VideoHost 是 holder 事件的唯一转发者：谁占着就转给谁 */
    private val forwarder = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) {
            if (owner == Owner.NATIVE) runCatching { nativeAttach?.invoke(h) }
        }
        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (owner == Owner.NATIVE) runCatching { nativeAttach?.invoke(h) }
        }
        override fun surfaceDestroyed(h: SurfaceHolder) {
            if (owner == Owner.NATIVE) runCatching { nativeRelease?.invoke() }
        }
    }

    init {
        surfaceView.holder.addCallback(forwarder)
        // PlayerView 自带一个字幕视图（exo_subtitles）。字幕统一由外层 subtitleView 管，
        // 不关掉就是两份字幕叠在一起。
        // 按类型找而不是按 R.id 找：media3 的 R 在不同打包方式下不一定可见。
        runCatching { hideSubtitleViews(playerView) }
    }

    /**
     * 与 EXO 的唯一接线点。
     *
     * 官方 Player 的约定：setVideoSurfaceView 内部会自己挂 SurfaceHolder 回调，
     * surface 还没创建好时先记下，等 created 再 attach —— 所以不需要我们提前判断。
     * 反方向必须先 clear，再换人：否则旧播放器还握着这块 surface。
     */
    /** 递归把 PlayerView 内部的 SubtitleView 全部藏起来 */
    private fun hideSubtitleViews(v: View) {
        if (v is SubtitleView) {
            v.visibility = View.GONE
            return
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) hideSubtitleViews(v.getChildAt(i))
        }
    }

    fun setPlayer(p: Player?) {
        val old = bound
        if (old === p) return
        // ★顺序照 media3 官方 PlayerView.switchTargetView 的注释：
        //   "We attach the new view before detaching the old one because this ordering allows the
        //    player to swap directly from one surface to another, without transitioning through a
        //    state where no surface is attached. This is significantly more efficient and achieves
        //    a more seamless transition when using platform provided video decoders."
        //   我们以前是反过来的（先 clear 再 set）：中间那一刻没有任何 surface 挂着，
        //   MediaCodecVideoRenderer 会退回 placeholderSurface（什么都不画），
        //   在这家 amlogic 的硬解上就是"黑一下"。先挂新的、再摘旧的，就没有这个中间态。
        // ★改走官方 PlayerView.setPlayer：
        //   它内部就是 switchTargetView —— 先挂新的、再摘旧的，并且顺带把
        //   SurfaceHolder.Callback、尺寸同步、首帧清 shutter 全部接好。
        //   这些我们手搓了一遍，但漏了细节；既然主线就是这个类，就交回去。
        runCatching { playerView.setPlayer(p) }
        bound = p
    }
}
