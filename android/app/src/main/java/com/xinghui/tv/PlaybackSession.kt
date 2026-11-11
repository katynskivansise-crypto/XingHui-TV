package com.xinghui.tv

import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 播放会话 —— 全页唯一的「播放状态所有者」。
 *
 * 为什么要有它：以前播放页里散着四个记账变量（pendingPauseIntent / pendingJumpMs /
 * resumeOverride / contentReady），菜单键、返回键、切内核、重建播放器四条路径都会去改它们，
 * 于是同一种 bug 会以不同面貌反复出现：
 *   · 起播前按 OK/←→ → 只记意图不下发 → 「按键无效，按一次菜单之后才好」
 *   · pendingJumpMs 被当成「从头播」→ 「加载中 + 回片头重播」
 *   · 返回先收条再退 → 第一下看起来没反应 → 「要按两次」
 *
 * 现在的规则只有三条：
 *   1. Activity 只转发动作（play/pause/seek/kernel），自己不判「现在能不能做」
 *   2. 会话内部只读内核的真实状态，不存任何「打算怎么做」
 *   3. 换内核、重建播放器必须先读当前位置，再重建，再把位置补回去
 *
 * 内核实例由 Activity 构造（它要管媒体源、请求头、解码器），但**交给会话持有**，
 * 之后所有控制动作只经过会话，Activity 不再直接碰 player / mpv。
 */
class PlaybackSession(
    /** 由 Activity 提供：重建 EXO 实例（配置都在 Activity 那边） */
    private val exoFactory: () -> ExoPlayer?,
    /** 由 Activity 提供：拿 mpv 实例（可能还没建） */
    private val mpvProvider: () -> MpvPlayer?,
    /** 当前是否 mpv 内核 */
    private val isMpvKernel: () -> Boolean,
    /** 把画面层绑到 EXO（VideoHost.setPlayer 的等价能力） */
    private val bindSurfaceToExo: (ExoPlayer?) -> Unit,
    /** 事件出口：起播、缓冲、播完、出错、暂停变化 */
    private val events: Events,
) {
    interface Events {
        fun onState(state: State)
        fun onError(message: String)
        fun onEnded()
    }

    enum class State { IDLE, BUFFERING, READY, PLAYING, PAUSED, ENDED, ERROR }

    private var exo: ExoPlayer? = null

    val player: ExoPlayer? get() = exo
    val mpv: MpvPlayer? get() = mpvProvider()
    val mv: MpvPlayer? get() = mpv  // 便于调用点读起来短一点

    fun adoptExo(p: ExoPlayer?) {
        exo = p
        bindSurfaceToExo(p)
        p?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> events.onState(State.BUFFERING)
                    Player.STATE_READY -> events.onState(if (p.playWhenReady) State.PLAYING else State.READY)
                    Player.STATE_ENDED -> events.onEnded()
                    else -> events.onState(State.IDLE)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                events.onState(if (isPlaying) State.PLAYING else State.PAUSED)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                events.onError(error.errorCodeName)
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                // 字幕由 Activity 里的 SubtitleView 显示，这里只转发
                cueSink?.invoke(cueGroup)
            }
        })
    }

    /** 由 Activity 注入：把 cue 灌给它的 SubtitleView */
    var cueSink: ((androidx.media3.common.text.CueGroup) -> Unit)? = null

    /** 只读：当前内核是否已经有内容可播（不判断"就绪"程度，够用就下发） */
    fun hasContent(): Boolean =
        if (isMpvKernel()) (mv != null)
        else (exo?.mediaItemCount ?: 0) > 0

    /* ───────────── 控制三个动作：永远直接下发，不做任何前置判断 ───────────── */

    fun toggle() {
        if (isMpvKernel()) {
            val m = mv ?: return
            if (m.isPausedActual()) m.resume() else m.pause()
        } else {
            val p = exo ?: return
            if (p.playWhenReady) p.pause() else p.play()
        }
    }

    fun play() {
        if (isMpvKernel()) mv?.resume() else exo?.play()
    }

    fun pause() {
        if (isMpvKernel()) mv?.pause() else exo?.pause()
    }

    fun isPlaying(): Boolean =
        if (isMpvKernel()) (mv?.isPlaying() ?: false)
        else (exo?.isPlaying ?: false)

    fun isPaused(): Boolean = !isPlaying()

    /**
     * 相对当前位置跳转。
     * 位置一律取内核真实值；内核还没有内容时返回 false，由调用方决定是否提示
     * （Activity 只在"连媒体项都没有"这种真异常时提示，不再记任何补跳）。
     */
    fun seekBy(deltaMs: Long): Boolean {
        val cur = positionMs()
        if (!hasContent() && cur <= 0L) return false
        val dur = durationMs()
        var target = (cur + deltaMs).coerceAtLeast(0L)
        if (dur > 0L) target = target.coerceAtMost(dur)
        seekTo(target)
        return true
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        if (isMpvKernel()) mv?.seekToMs(target) else exo?.seekTo(target)
    }

    fun positionMs(): Long =
        if (isMpvKernel()) (mv?.positionMs() ?: 0L)
        else (exo?.currentPosition ?: 0L)

    fun durationMs(): Long =
        if (isMpvKernel()) (mv?.durationMs() ?: 0L)
        else (exo?.duration ?: 0L).let { if (it <= 0L) 0L else it }

    fun setSpeed(x: Float) {
        if (isMpvKernel()) mv?.setSpeed(x) else exo?.setPlaybackSpeed(x)
    }

    fun setScale(idx: Int) {
        if (isMpvKernel()) mv?.applyScale(idx)
    }

    /**
     * 释放。先摘画面层再释放播放器：release 期间 media3 还会去摸那块 surface，
     * 而这块 surface 马上要交给另一个内核。
     */
    fun releaseExo() {
        val p = exo
        exo = null
        bindSurfaceToExo(null)
        runCatching { p?.release() }
    }


    /* ───────────── 起播：把「建实例 + 装载 + 定位 + 是否自动播」收成一次调用 ───────────── */

    private var exoBuilder: ((Map<String, String>) -> ExoPlayer?)? = null

    fun setExoBuilder(f: (Map<String, String>) -> ExoPlayer?) {
        exoBuilder = f
    }

    /**
     * 保证有一个可用的 EXO 实例。请求头变了（播放接口返回新的 UA/Referer）才重建，
     * 其余情况复用——重建播放器是"从片头重播"和"黑一下"的直接来源。
     */
    fun ensureExo(headers: Map<String, String>, forceRebuild: Boolean): ExoPlayer? {
        val cur = exo
        if (cur != null && !forceRebuild) return cur
        releaseExo()
        val p = runCatching { exoBuilder?.invoke(headers) }.getOrNull() ?: return null
        adoptExo(p)
        return p
    }

    /** EXO 起播：setMediaItem → seekTo → prepare → playWhenReady，定位直接排队生效 */
    fun playExo(item: androidx.media3.common.MediaItem, startMs: Long, autoPlay: Boolean): Boolean {
        val p = exo ?: return false
        return runCatching {
            p.setMediaItem(item)
            if (startMs > 0L) p.seekTo(startMs)
            p.prepare()
            p.playWhenReady = autoPlay
            true
        }.getOrDefault(false)
    }

    /** mpv 起播（实例由 Activity 建，选项在那一侧） */
    fun playMpv(
        url: String,
        headers: Map<String, String>,
        startMs: Long,
        autoPlay: Boolean,
        subs: List<String> = emptyList(),
    ) {
        val m = mv ?: return
        m.setSpeed(1f)
        m.play(url, headers, startMs, subs, startPaused = !autoPlay)
    }

    /** 内核切换前的位置快照：切内核必须从这里续上，绝不从头 */
    fun snapshotPosition(): Long = positionMs()
}
