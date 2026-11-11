package com.xinghui.tv

import android.view.SurfaceView
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
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
    val surfaceView: SurfaceView,
    val subtitleView: SubtitleView,
) {
    /** 与 PlayerView 的 resize_mode 完全同义（0=fit 1=fixed_width 2=fixed_height 3=fill 4=zoom） */
    var resizeMode: Int
        get() = frame.resizeMode
        set(value) {
            frame.resizeMode = value
        }

    /** 只读：页面里有些诊断要读它，但不允许任何人改 */
    val visibility: Int
        get() = frame.visibility

    fun post(action: () -> Unit) {
        surfaceView.post { action() }
    }

    private var bound: Player? = null

    /**
     * 与 EXO 的唯一接线点。
     *
     * 官方 Player 的约定：setVideoSurfaceView 内部会自己挂 SurfaceHolder 回调，
     * surface 还没创建好时先记下，等 created 再 attach —— 所以不需要我们提前判断。
     * 反方向必须先 clear，再换人：否则旧播放器还握着这块 surface。
     */
    fun setPlayer(p: Player?) {
        val old = bound
        if (old === p) return
        if (old != null) runCatching { old.clearVideoSurfaceView(surfaceView) }
        bound = p
        if (p != null) runCatching { p.setVideoSurfaceView(surfaceView) }
    }
}
