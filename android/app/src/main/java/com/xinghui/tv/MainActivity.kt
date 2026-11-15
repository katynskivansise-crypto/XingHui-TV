package com.xinghui.tv

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * 详情页：影片信息 + 内嵌播放器小窗 + 集数网格（对齐原版 VideoActivity 的合一形态）。
 * 点播放器小窗 → 全屏；点集数 → 换集；点「当前正在播的那集」→ 全屏。
 */
/**
 * 唯一宿主 Activity。
 *
 * ★ 为什么详情页和播放页必须同一个窗口：
 *   1. 新窗口要重新拿 hasWindowFocus（真机实测 199~434ms）。这段窗口期里按键没有接收者，
 *      所以"从详情页进播放页、不按菜单直接按 ←/→"没有任何记录 —— 事件根本没到。
 *   2. SurfaceView 在**新窗口**里第一次出现会触发一次 IWindowSession.relayout，那一帧被跳过，
 *      这就是"第一次按菜单黑约 1 秒"。
 *   3. 两个 Activity 各一个引擎实例、各一份进度，这就是"按过菜单后返回跳回旧位置"。
 *
 * 本类持有：详情层（detailLayer）+ 播放层（playLayer）+ 唯一一块 SurfaceView（videoFrame）。
 * 三种形态只改 videoFrame 的 LayoutParams，Surface 永不重建 —— 也就是 OwnTV 说的
 * "promotes the running preview straight to full screen with no reload"。
 */
class MainActivity : Activity() {

    private lateinit var hostRoot: android.widget.FrameLayout
    private lateinit var detailLayer: android.widget.FrameLayout
    private lateinit var playContainer: android.widget.FrameLayout
    private lateinit var detailRoot: View
    private lateinit var videoFrame: androidx.media3.ui.AspectRatioFrameLayout
    private lateinit var videoSurface: android.view.SurfaceView
    private lateinit var subtitleView: androidx.media3.ui.SubtitleView

    private var playLayer: PlayLayer? = null
    private var surfaceConsumer: androidx.media3.common.Player? = null

/**
     */
    /**
     * 画面层宿主的解析：**运行时查 id，不在代码里写死画面层的资源 id**。
     *
     * 为什么不用编译期常量：R 里的 id 由 AGP 从 layout 生成。画面层换形态时，
     * 新 id 在旧布局里不存在、旧 id 在新布局里不存在 —— 任何一边没同步，
     * 都会变成 Unresolved reference 把整包编译打断。
     * 画面层是运行时装配的对象，不该成为编译期的硬依赖。
     *
     * 顺序：独立常驻 SurfaceView（新布局） → PlayerView 内部那块（旧布局） → 明确报错。
     */
    private fun resolveSurfaceView(): android.view.SurfaceView {
        val idNew = resources.getIdentifier("videoSurface", "id", packageName)
        if (idNew != 0) {
            findViewById<android.view.View>(idNew)?.let { if (it is android.view.SurfaceView) return it }
        }
        val idPv = resources.getIdentifier("playerView", "id", packageName)
        if (idPv != 0) {
            val pv = findViewById<android.view.View>(idPv) as? androidx.media3.ui.PlayerView
            (pv?.videoSurfaceView as? android.view.SurfaceView)?.let { return it }
        }
        throw IllegalStateException(
            "画面层宿主缺失：布局里既没有独立的 videoSurface，也没有带 surface 的 PlayerView"
        )
    }

    internal val videoHost: VideoHost by lazy {
        VideoHost(videoFrame, resolveSurfaceView(), subtitleView, null)
    }
    private var surfaceValid = false
    private var playMode = false

    private fun <T : View> findDetail(id: Int): T = detailRoot.findViewById(id)
    private fun <T : View> findViewHost(id: Int): T = this.findViewById<View>(id) as T

    private lateinit var store: HistoryStore
    private var vod: Vod? = null
    private var currentLine: PlayLine? = null

/**
     */
    internal var player: ExoPlayer? = null
    /**
     * 小窗形态的原始 LayoutParams —— 原版的 mFrameParams。
     * ★保存的是 XML 里那个对象本身，退出全屏时原样还回去。
     *   原版：VideoActivity.exitFullscreen() → mBinding.video.setLayoutParams(mFrameParams);
     *   我之前是手算 400dp×225dp 再补 margin，等于重新拼一个"差不多"的参数，
     *   漏掉的属性就会表现为位置或尺寸不对。
     */
    private var frameParams: android.view.ViewGroup.LayoutParams? = null

    private lateinit var inlineView: android.view.View
    private var inlineFullscreen = false
    private var inlinePausedByLeave = false
    private var inlineResumeOnReturn = false
    private var inlineLeaveFullscreen = false
    private var lastMenuTriggerMs = 0L
    private var inlineUrl: String = ""
    private var savedBoxParams: android.view.ViewGroup.LayoutParams? = null
    private var savedBoxForeground: Drawable? = null
    private var playingIdx = -1
    private var episodes: List<Pair<String, String>> = emptyList()
    private var epOrder = false
    private var activeSection = 0
    private val GROUP_SIZE = 40
    private var boxH = 0

    private val pairs = mutableListOf<Pair<String, String>>() // (key, 显示名)
    private lateinit var adapter: ListAdapter

    // ================= 宿主：唯一 surface + 层切换 =================

/**
     */
    private fun attachPreviewTo(p: androidx.media3.common.Player) {
        surfaceConsumer = p
        runCatching { videoHost.setPlayer(p) }
    }

    private fun detachSurfaceFrom() {
        surfaceConsumer = null
        // 松手也走 PlayerView：keep_content_on_player_reset=true 会保留最后一帧，
        // 切到播放页时不会先闪一下黑。
        runCatching { videoHost.setPlayer(null) }
    }

    // ★previewAttached() 已删。它只判断 `surfaceConsumer != null`，不判断是不是"这一颗"，
    //   实例换过之后必然误判成"已绑"，新实例一个 surface 都拿不到（只有声音、画面停在旧帧）。
    //   现在已经换过三次实例的地方各踩了一次，所以连函数一起删掉，避免再被拿来当闸门。
    //   绑定统一走 attachPreviewTo(p)，VideoHost.setPlayer 内部 `old === p` 会短路，幂等。

    /**
     * 预览显隐。★ 画面本体是宿主那块 SurfaceView，挡在它前面的是海报 detailImage，
     * 所以"显示预览" = 把海报收起来；"隐藏预览" = 海报回来。
     * 原来 inlineView 是 PlayerView，它自己就是画面，所以只切它一个就够；现在必须两个一起切，
     * 否则要么一片黑（海报盖着），要么两幅画面打架。
     */
    private fun setPreviewVisible(visible: Boolean) {
        inlineView.visibility = if (visible) View.VISIBLE else View.GONE
        runCatching {
            findDetail<View>(R.id.detailImage)?.visibility = if (visible) View.GONE else View.VISIBLE
        }
    }

    /**
     * 小窗形态：画面摆回详情页 playerBox 的位置（同一块 surface，只改 LayoutParams）。
     * 与原版一致 —— VideoActivity 就是把 mBinding.video 的 LayoutParams 在
     * 400x225 与 MATCH_PARENT 之间来回换，画面本身一直没动过。
     */
    private fun videoFrameToMini() {
        // ★原版 VideoActivity.exitFullscreen() 就这一句：
        //     mBinding.video.setLayoutParams(mFrameParams);
        //   保存的是 XML 里那个对象，原样还回去 —— 宽高、margin、gravity 一个不落。
        frameParams?.let { videoFrame.layoutParams = it }
        // 原版小窗还带一层焦点框：setForeground(selector_video)；全屏时置 null。
        // 这就是详情页小窗外面那圈细边。
        runCatching { videoFrame.foreground = getDrawable(R.drawable.selector_video) }
        videoFrame.requestLayout()
    }

    private fun videoFrameToFull() {
        videoFrame.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        // 原版 enterFullscreen()：setForeground(null)
        videoFrame.foreground = null
        videoFrame.requestLayout()
    }

    /**
     * 进播放层。★ 只换层，不换窗口、不换 surface。
     * 这就是 OwnTV 的 "promotes the running preview straight to full screen with no reload"。
     */
    /**
     * 两层的显隐统一走这里 —— **只改 alpha 与焦点可达性，绝不改 visibility**。
     * alpha=0 的层仍在窗口里占着同一块区域，所以绘制区域从页面第一帧起就是固定的，
     * 进出播放页不再触发挖洞区域重算（黑帧就是那么来的）。
     * 焦点这一侧用 FOCUS_BLOCK_DESCENDANTS 挡住：alpha=0 但视图还在，
     * 不挡的话方向键会落进看不见的层里。
     */
    private fun showDetailLayer(show: Boolean) {
        detailLayer.alpha = if (show) 1f else 0f
        detailLayer.descendantFocusability = if (show)
            android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        else android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (!show) detailLayer.clearFocus()
    }

    private fun showPlayLayerView(show: Boolean) {
        playContainer.alpha = if (show) 1f else 0f
        playContainer.descendantFocusability = if (show)
            android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        else android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (!show) playContainer.clearFocus()
    }

    /**
     * 播放层切集后的回写入口。
 */
    internal fun onEpisodeChanged(newEpUrl: String, newUrl: String) {
        val idx = if (newEpUrl.isBlank()) -1 else episodes.indexOfFirst { it.second == newEpUrl }
        if (idx >= 0 && idx != playingIdx) {
            playingIdx = idx
            MpvDebug.log("[gate] 播放层切集回写 playingIdx=$idx ep=${newEpUrl.take(30)}")
        }
        if (newUrl.isNotBlank()) inlineUrl = newUrl
        if (playingIdx >= 0) runCatching { adapter.setActive("E:" + playingIdx) }
    }

    fun showPlayLayer(extras: Intent) {
        val layer = playLayer ?: PlayLayer(this) { exitPlayLayer() }.also { playLayer = it }
        // gate 日志：进入这条链路每一步的输入状态。有它才不用靠猜。
        // ★这里只用 PlayLayer 已有的公开 API（mpvInstance / takePlayer / enter / reconnect），
        //   不再引用后加的调试方法 —— 少一个跨文件依赖，就少一次"只覆盖了一个文件"导致的编译失败。
        MpvDebug.log("[gate] showPlayLayer ep=${extras.getStringExtra("ep_url").orEmpty().take(40)} " +
            "exoPlayer=${if (player != null) "ok" else "null"} mpv=${if (layer.mpvInstance() != null) "ok" else "-"}")
        playMode = true
        // ★★不再 GONE↔VISIBLE 对调。两者都常驻 VISIBLE，只切 alpha。
        //
        //   依据：这台机器上"整片有内容区域变透明"会让 ViewRootImpl 重算挖洞区域
        //   （setTransparentRegionHint），窗口层那一帧被合成器跳过 —— 用户看到的就是黑一帧。
        //   退出时最严重：播放层（整屏不透明）消失 + 详情层（大面积透明）出现，同一帧发生。
        //   本文件里 playProgress / playCenter / controlBar 三层早就统一成"常驻 + alpha"了，
        //   只有最外面这两层还留着 GONE，黑帧就一直是这里漏出来的。
        showDetailLayer(false)
        showPlayLayerView(true)
        // 画面层一直是 VISIBLE：小窗形态下它只有 400x225，剩下的区域露出窗口壁纸；
        // 只有它变成 MATCH_PARENT 时才占满全屏。所以壁纸从不被挡住。
        videoFrameToFull()
        // enter 单独兜住：起播失败不该把用户挡在详情页外面
        runCatching { layer.enter(extras, playContainer) }
            .onFailure {
                MpvDebug.log("[gate] ★enter 抛异常（层已切换，起播未完成）：$it")
                runCatching {
                    android.widget.Toast.makeText(this, "[enter] 异常：${it.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        layer.onHostResume()
        MpvDebug.log("[gate] 已切换 detailAlpha=${detailLayer.alpha} playAlpha=${playContainer.alpha} " +
            "frame=${videoFrame.layoutParams?.width}x${videoFrame.layoutParams?.height} playMode=$playMode")
    }

    /**
     * 把详情页的监听器挂到某个引擎上。★ 退出播放层时播放层会把引擎原样交回来，
     * 但监听器不会跟着回来 —— 不重新挂，小窗就没有 onVideoSizeChanged，画面比例和分辨率都会停在旧值。
     */
    /**
     * 详情页用到的播放器监听器。**必须复用同一个实例**。
 */
    private var detailListener: androidx.media3.common.Player.Listener? = null

    private fun bindDetailListener(p: androidx.media3.common.Player) {
        detailListener?.let { runCatching { p.removeListener(it) } }
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 原版没有错误去重（5.6.0 无 errorCount/playFail，5.0.8 无 800 常量）：
                // 一次起播只由 App.post(runnable, TIMEOUT_PLAY) 的起播窗口兜底，错误回调照常处理。
                Log.e("XingHuiTV", "[player] 播放失败 ${error.errorCodeName}，走自动换源")
                handlePlayFailure(playingIdx.coerceAtLeast(0), auto = false,
                    reason = "播放器错误 ${error.errorCodeName}", depth = 0)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val w = videoSize.width
                val h = videoSize.height
                if (w > 0 && h > 0) {
                    findDetail<TextView>(R.id.vodInfoSize)?.text = "$w x $h"
                }
                // ★不要调 videoFrame.setAspectRatio()。
                //   那是我上一轮加的错误优化：AspectRatioFrameLayout 在宽度约束为 EXACTLY 时
                //   会**强制按比例重算高度**（它为 PlayerView 设计的行为）。
                //   我们是拿它当固定尺寸的容器用：设了 2.398 之后，
                //   小窗 400dp×225dp 变 600×250（高度被压扁），
                //   全屏 1920×1080 变 1920×801（底下露出两百多像素壁纸）。
                //   比例要由 resize_mode 在子 View 上做，容器尺寸必须由 LayoutParams 说了算。
                MpvDebug.log("[render] vsize ${w}x$h frame=${videoFrame.width}x${videoFrame.height} resizeMode=${videoFrame.resizeMode}")
            }

            /**
             * ★唯一能证明「帧有没有真的进到这块 surface」的信号。
             *   st=READY + playing=true 只能说明解码器在跑，不能说明画面进了我们这块面。
             *   这个回调只在渲染器真的把第一帧送到 surface 时触发一次。
             */
            override fun onRenderedFirstFrame() {
                MpvDebug.log(
                    "[render] 首帧已进面 sv=${videoSurface.width}x${videoSurface.height} " +
                        "frame=${videoFrame.width}x${videoFrame.height} " +
                        "visible=${videoSurface.isShown} playMode=$playMode"
                )
            }
        }
        detailListener = listener
        p.addListener(listener)
    }

    fun exitPlayLayer() {
        if (!playMode) return
        playMode = false
        showPlayLayerView(false)
        showDetailLayer(true)
        runCatching { setPreviewVisible(true) }
        videoFrameToMini()
        runCatching {
            val rv = findDetail<RecyclerView>(R.id.detailList)
            val idx = playingIdx
            val count = rv?.adapter?.itemCount ?: 0
            if (rv != null && idx >= 0 && idx < count) rv.scrollToPosition(idx)
            detailLayer.post {
                runCatching {
                    val rv2 = findDetail<RecyclerView>(R.id.detailList)
                    rv2?.requestFocus()
                    val item = rv2?.layoutManager?.findViewByPosition(idx)
                    item?.requestFocus()
                    MpvDebug.log(
                        "[gate] exit 焦点恢复 idx=$idx " +
                            "rvFocus=${rv2?.isFocused} itemFound=${item != null} itemFocus=${item?.isFocused} " +
                            "rvShown=${rv2?.isShown} dListShown=${rv2?.visibility}"
                    )
                }
            }
        }
        // 详情页错误回调（只加 listener，不动画面）
        runCatching { playLayer?.currentPlayer()?.let { bindDetailListener(it) } }
        MpvDebug.log("[gate] exit 只缩容器：player/surface 全程未动 playMode=$playMode")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (playMode) playLayer?.onHostWindowFocusChanged(hasFocus)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (playMode) playLayer?.onHostConfigurationChanged(newConfig)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)
        // ★版本戳。前几轮改完都是"问题依旧"，但谁也说不清是代码没生效、还是改错了方向 ——
        //   从这一版起第一眼就能确认装的是哪一版：开机看到这条 Toast / 日志就说明进了包。
        runCatching {
            MpvDebug.log("[boot] build=tk20 handoff=playerView gate=on sdk=${android.os.Build.VERSION.SDK_INT}")
            android.widget.Toast.makeText(this, "tk20 · 画面层归一", android.widget.Toast.LENGTH_LONG).show()
        }

        hostRoot = findViewHost<android.widget.FrameLayout>(R.id.hostRoot)
        detailLayer = findViewHost(R.id.detailLayer)
        // ★不依赖 XML：布局文件的背景一律在这里强制成透明。
        //   壁纸现在是布局里那块 contextWall（全屏 ImageView），不再依赖窗口背景；
        //   这两层只要有一层不是 transparent，详情页就是纯黑 ——
        //   而历史上有过"只覆盖 kt 没覆盖 xml"的情况，所以不能把这件事交给 XML 决定。
        runCatching {
            hostRoot.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            detailLayer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        playContainer = findViewHost(R.id.playLayer)
        videoFrame = findViewHost(R.id.videoFrame)
        // ★画面由官方 PlayerView 承载，它内部那个 SurfaceView 才是真正的渲染目标。
        videoSurface = resolveSurfaceView()
        frameParams = videoFrame.layoutParams
        subtitleView = findViewHost(R.id.subtitleView)

        detailRoot = layoutInflater.inflate(R.layout.activity_detail_layer, detailLayer, false)
        detailLayer.addView(detailRoot)

        if (intent?.getBooleanExtra("play_mode", false) == true) {
            MpvDebug.log("[boot] play_mode 直进播放层")
            detailLayer.post { runCatching { showPlayLayer(intent!!) } }
        }

        // 唯一一块 surface 的生死只由宿主掌握：创建/销毁都记一笔，
        // 之后任何引擎要挂画面都走 surfaceConsumer，不许自己去拿 holder.surface。
        videoSurface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                surfaceValid = true
                MpvDebug.log("[sv2] created（全屏播放页专用） ${videoSurface.width}x${videoSurface.height}")
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) {
                MpvDebug.log("[sv2] changed $w x $ht")
                // ★这是整条链路里我们一直漏掉的一步。
                //   media3 的 PlayerView 内部有个 ComponentListener，surface 尺寸一变它就调
                //     player.setVideoSurfaceSize(width, height)
                //   （原版 PlaybackActivity 里对应的就是 syncVideoSurfaceSize）。
                //   我们用的是裸 SurfaceView + setVideoSurfaceView，PlayerView 那一层不在了，
                //   而 setVideoSurfaceView 本身**不会**把尺寸变化转给解码器。
                //   结果：容器从小窗 400x225 撑到全屏，surface 变了，解码器却还按旧尺寸写帧 ——
                //   画面停住、声音照常。
                //   原版 PlaybackActivity.syncVideoSurfaceSize 的默认路径就一句：
                //     surfaceView.getHolder().setSizeFromLayout();
                //   它让 surface 的 buffer 尺寸按布局重新算 —— View 尺寸变化只有经过这一步，
                //   才真正落到 buffer 上。我们 resize 了 videoFrame、view 变了，
                //   却从没通知 holder，buffer 一直是旧尺寸，解码器继续往旧 buffer 写帧。
                //   ★不是 setVideoSurfaceSize：那个 API 在 media3 里不存在（编译会直接报错）。
                runCatching { h.setSizeFromLayout() }
            }
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                surfaceValid = false
                MpvDebug.log("[sv2] destroyed（唯一一块）")
            }
        })

        store = HistoryStore(this)
        val vodId = intent.getStringExtra("vod_id") ?: ""

        val list = findDetail<RecyclerView>(R.id.detailList)
        // 集数聚焦用黄字（对齐原版 01[1080P] 的高亮），分类条等其它地方不受影响
        adapter = ListAdapter(pairs, null, Color.rgb(255, 204, 0)) { key -> onPick(key) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // 返回按钮按原版去掉（原版 VodActivity 靠系统返回键），布局里也不再声明
        findDetail<View>(R.id.btnFav).setOnClickListener { toggleFav() }
        findDetail<View>(R.id.btnDesc).setOnClickListener { showDescDialog() }
        findDetail<View>(R.id.btnSource).setOnClickListener { switchSource() }
        // 倒序：整段集数反向排（原版「倒序 →」那个按钮）
        findDetail<View>(R.id.btnOrder).setOnClickListener {
            epOrder = !epOrder
            if (episodes.size > GROUP_SIZE) activeSection = 0
            rebuildGroups()
            showGroup(activeSection * GROUP_SIZE)
            Toast.makeText(this, if (epOrder) "已倒序" else "已正序", Toast.LENGTH_SHORT).show()
        }

        // 对齐原版 VideoActivity：那里播放器和集数在同一页，点上方视频框即进全屏。
        // 我们是 DetailActivity + PlayActivity 两页，就在这里补上同样的直达：
        // 点海报→播第一集，点任意集数→直接全屏播那一集（省掉「先看详情、再点进去」这一步）。
        // 对齐原版 VideoActivity：视频框和集数在同一页，点框进全屏（onVideo 的语义）
        // 原版 BaseActivity.refreshWall：壁纸设在 window 层，布局透明透出来。
        // 注意必须走 WallpaperStore.apply：它才有「用户自设图 > 内置 wallpaper_1」的分支。
        // 之前这里写死 setBackgroundDrawableResource(wallpaper_1)，用户换了壁纸详情页仍是绿的。
        runCatching { WallpaperStore.apply(this) }
        // ★同时把同一张图画到布局里的 contextWall（原版做法）。
        //   只靠 window 背景在这一版上实测会间歇失效（同样是这套代码，之前绿过也黑过），
        //   实打实的 ImageView 不会因为窗口层级或 decor 重建而丢。
        runCatching {
            val wall = findViewHost<android.widget.ImageView>(R.id.contextWall)
            wall?.setImageDrawable(WallpaperStore.drawableFor(this))
            wall?.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        // ★布局自检：一次回答三件事 —— 工程里跑的是新布局还是旧布局、壁纸有没有加载、
        //   画面层当前是什么控件多大。下面这些值如果和仓库里的 XML 对不上，
        //   就说明装上去的不是这一版（res 没重新编译 / 只覆盖了 kt 没覆盖 xml）。
        runCatching {
            MpvDebug.log(
                "[boot] 布局自检 hostRootBg=${findViewHost<android.view.View>(R.id.hostRoot)?.background?.javaClass?.simpleName} " +
                    "detailLayerBg=${detailLayer?.background?.javaClass?.simpleName} " +
                    "frame=${videoFrame.width}x${videoFrame.height} " +
                    "frameLp=${videoFrame.layoutParams?.width}x${videoFrame.layoutParams?.height} " +
                    "surface=${videoSurface.javaClass.simpleName} " +
                    "selfWall=${WallpaperStore.has(this)} " +
                    "windowBg=${window.decorView.background?.javaClass?.simpleName}"
            )
        }
        inlineView = findDetail(R.id.inlinePlayer)
        // ★这一行是「详情页预览到底创建的是 SurfaceView 还是 TextureView」的唯一直接证据。
        //   PlayerView 在 surface_type=surface_view 下 getVideoSurfaceView() 返回内部的 SurfaceView；
        //   改成 texture_view 后它返回 null。所以 /debug 里这一行打「null」就说明 texture_view 生效了，
        //   打「SurfaceView」就说明 XML 没被覆盖（装的还是旧包 / 没重新编译）。
        //   这是「第二块 overlay plane 到底存不存在」的判据，不靠猜。
        runCatching {
            MpvDebug.log(
                "[det] 预览=常驻 SurfaceView（全 app 只有一块，不再有第二块 surface）"
            )
        }
        // 内嵌播放器之前是裸 build()：没有轨道选择参数，没有解码方式，也没有请求头。
        // 照原版 ExoUtil 补齐 TrackSelector（最高码率 / 字幕语言 / 隧道模式），
        // 解码方式跟播放页共用同一个值。
        player = buildInlinePlayer()
        // 播放器框按 16:9 收口：之前 height 写死 270dp、宽度随 weight 撑开，
        // 比例约 2.6:1，画面只能居中留大黑边（真机上看着就是「缩放不对」）。
        findDetail<View>(R.id.playerBox)?.let { boxView ->
            boxView.post {
                boxH = boxView.width * 9 / 16
                boxView.layoutParams = boxView.layoutParams.apply { height = boxH }
            }
        }
        // 原版同一个站点有多个播放源时，某个源播不动会自动切下一个
        player?.let { bindDetailListener(it) }
        // ★点小窗 = 进全屏播放层（原版 VideoActivity 点视频框即进全屏）。
        //   不再走 enterInlineFullscreen：那条路铺满的是 playerBox，而画面在宿主层的 videoFrame。
        findDetail<View>(R.id.playerBox).setOnClickListener {
            openPlayPageAt(playingIdx.coerceAtLeast(0))
        }

        Thread {
            val store = SubscriptionStore(this)
            // 无当前站点：直接退出，不回退任何内置内容
            val key = store.currentKey() ?: ""
            if (key.isBlank()) {
                runOnUiThread { Toast.makeText(this, "未配置站点，请先在站点管理中导入订阅源", Toast.LENGTH_LONG).show() }
                return@Thread
            }
            val site = store.sites().firstOrNull { it.key == key }
            val detail = if (site?.isCsp() == true) {
                CspLoader.detail(this, site, vodId)
            } else {
                val params = JSONObject().put("ids", vodId).toString()
                Engine.execute("detail", key, params)
            }
            val first = Vod.parseList(detail).firstOrNull()
            runOnUiThread {
                if (first == null) {
                    Toast.makeText(this, "未获取到详情数据", Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                vod = first
                // ★每换一部影片都必须重置这个闸门。
                //   autoStarted 是 Activity 级一次性闸门，只有进程内第一次进详情时是 false；
                //   不重置的话第二次进详情 autoPlayFromHistory() 第一行就 return，
                //   小窗永远不起播 —— 表现就是「详情页秒开、小窗没画面、进播放页才加载」。
                autoStarted = false
                autoPlayAfterExpand = false
                playingIdx = 0
                renderLines()
            }
        }.start()
    }

    /**
     * 字幕样式 —— 原版 ExoUtil.applySubtitleStyle() 的等价实现。
     *   预设：白字 + 黑描边 + 透明底
     *   系统：跟随系统「字幕样式」（CaptioningManager.getUserStyle()）
     * 同时按原版设 setApplyEmbeddedStyles(!system)：选预设用字幕自带样式，选系统用系统样式覆盖。
     */

    /**
     * 内嵌预览播放器的唯一构建点。
     * 抽出来是为了「进播放页释放、返回重建」这条路径不用把参数再抄一遍。
     */
    private fun buildInlinePlayer(): androidx.media3.exoplayer.ExoPlayer {
        val p = ExoPlayer.Builder(this)
            .apply {
                val rf = if (Settings(this@MainActivity).decodeMode == 0)
                    SoftRenderersFactory(this@MainActivity)
                else
                    androidx.media3.exoplayer.DefaultRenderersFactory(this@MainActivity)
                setRenderersFactory(rf.setEnableDecoderFallback(true))
                setLoadControl(androidx.media3.exoplayer.DefaultLoadControl())
                val ts = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this@MainActivity)
                ts.setParameters(
                    ts.buildUponParameters()
                        .setPreferredTextLanguage(java.util.Locale.getDefault().getISO3Language())
                        .setForceHighestSupportedBitrate(true)
                        .setTunnelingEnabled(Settings(this@MainActivity).tunnelMode == 1)
                )
                setTrackSelector(ts)
                // 内嵌播放器之前是裸 build()：没有超时、没有跨协议重定向。
                val timeoutMs = Settings(this@MainActivity).networkTimeoutSec.coerceIn(5, 120) * 1000
                val http = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(timeoutMs)
                    .setReadTimeoutMs(timeoutMs)
                    .setAllowCrossProtocolRedirects(true)
                // 同播放页：包一层 DefaultDataSource（原版 MediaSourceFactory:90），
                // 本地路径不必再依赖 UrlConvert 转写
                setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        androidx.media3.datasource.DefaultDataSource.Factory(this@MainActivity, http)
                    )
                )
            }
            .build().also {
                // 官方 Players.java：HDMI / 蓝牙音频断开时自动暂停
                it.setHandleAudioBecomingNoisy(true)
                // 内嵌预览同样请求音频焦点：进播放页时播放页抢焦点，这一路会自动 LOSS → 暂停，
                // 不再依赖手动 pauseInlinePlayback 兜底（原版 Players.java 同一句）。
                it.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                attachPreviewTo(it)
            }
        return p
    }

    internal fun applyCaptionStyle() {
        val sv = subtitleView
        val system = Settings(this).captionMode == 1
        val style = if (system) {
            val cm = getSystemService(CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager
            if (cm != null) androidx.media3.ui.CaptionStyleCompat.createFromCaptionStyle(cm.userStyle)
            else androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK, null)
        } else {
            androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK, null)
        }
        sv.setStyle(style)
        sv.setApplyEmbeddedStyles(!system)
        sv.setApplyEmbeddedFontSizes(false)
    }

    private fun renderLines() {
        val v = vod ?: return
        showLines = true
        currentLine = null

        findDetail<TextView>(R.id.detailName).text = v.vodName
        // 对齐原版信息行：「站源: xxx   年份: xxx   地区: xxx」，标签与值分段，
        // 之前用「 · 」拼成一行，读不出哪段是什么。
        val siteName = SubscriptionStore(this).currentSite()?.name.orEmpty()
        val info = buildString {
            if (siteName.isNotBlank()) append("站源：").append(siteName)
            if (v.vodYear.isNotBlank()) { if (isNotEmpty()) append("    "); append("年份：").append(v.vodYear) }
            if (v.vodArea.isNotBlank()) { if (isNotEmpty()) append("    "); append("地区：").append(v.vodArea) }
            if (v.vodRemarks.isNotBlank() && v.vodRemarks != v.vodYear) {
                if (isNotEmpty()) append("    "); append("更新：").append(v.vodRemarks)
            }
        }
        findDetail<TextView>(R.id.detailInfo).text = info.ifBlank { "暂无信息" }
        findDetail<TextView>(R.id.detailDesc).text = v.vodContent.ifBlank { "暂无简介" }
        if (Settings(this).showPoster) {
            AsyncImageLoader.load(findDetail(R.id.detailImage), v.vodPic)
        }
        applyFavState(store.isFavorite(v.vodId))

        pairs.clear()
        val lines = v.parseLines()
        if (lines.isEmpty()) {
            showLines = true
            pairs.add("none" to "无播放线路")
            buildFlags(emptyList())
            findDetail<RecyclerView>(R.id.detailList).layoutManager = LinearLayoutManager(this)
            adapter.notifyDataSetChanged()
            return
        }
        // 对齐原版：进详情页直接展开第一条线路的集数网格，焦点落在第一集，
        // 按确认即起播。原版没有「先选线路、再选集数」这一层，多条线路用「换源」切换。
        currentLine = lines.first()
        showLines = false
        buildFlags(lines)
        renderEpisodes(lines.first())
        findDetail<RecyclerView>(R.id.detailList)?.let { rv ->
            rv.post { rv.requestFocus() }
        }
        // 对齐原版：VideoActivity 打开即 onRefresh 起播当前集，不是等用户点。
        // 迅雷单条要先等展开结果，展开完再起播。
        val autoOnly = episodes.singleOrNull()
        if (autoOnly != null && ThunderPlay.match(autoOnly.second)) {
            autoPlayAfterExpand = true
        } else {
            autoPlayFromHistory()
        }
    }

    private fun autoPlayFromHistory() {
        if (autoStarted) return
        autoStarted = true
        val v0 = vod
        val hKey = HistoryStore.keyOf(v0?.vodId.orEmpty(), v0?.vodName.orEmpty(), episodes.getOrNull(playingIdx)?.second)
        val last = runCatching { HistoryStore(this).lastEpUrl(hKey) }.getOrDefault("")
        val idx = episodes.indexOfFirst { it.second.isNotBlank() && it.second == last }
            .takeIf { it >= 0 } ?: 0
        Log.e("XingHuiTV", "[detail] 自动起播 idx=$idx 共${episodes.size}集")
        playInline(idx, auto = true)
    }

    private fun renderEpisodes(line: PlayLine) {
        episodeSeq++          // 切线路/重渲染即作废上一轮异步展开
        episodes = line.episodes
        // 原版这一行是线路 tab（超级无敌4K / 高清4K / …），选中项标黄
        markActiveFlag(line.name)
        applyEpisodeGrid()
        rebuildGroups()
        showGroup(0)
        // 迅雷体系：磁力/ed2k/thunder/种子在详情阶段就展开成文件列表
        // （对齐原版 player/Source.parse → Thunder.Parser.call）。
        // 种子解析要等元信息，可能要几秒，所以放后台线程，回来后替换集数列表。
        val only = episodes.singleOrNull()
        if (only != null && ThunderPlay.match(only.second)) {
            ThunderPlay.ensureInit(this)
            expandThunder(only.second)
        }
    }

    private fun expandThunder(url: String) {
        Log.e("XingHuiTV", "[detail] 迅雷体系，开始展开 ${url.take(70)}")
        val seq = episodeSeq
        Thread {
            val list = runCatching { ThunderPlay.expand(url) }.getOrDefault(emptyList())
            if (list.isEmpty()) {
                Log.e("XingHuiTV", "[detail] 展开无结果，保持单条")
                return@Thread
            }
            runOnUiThread {
                // 用户可能已经切到别的线路/退出，序号不匹配就不覆盖
                if (seq != episodeSeq || isFinishing) return@runOnUiThread
                Log.e("XingHuiTV", "[detail] 展开完成，共 ${list.size} 个文件")
                episodes = list
                applyEpisodeGrid()
                rebuildGroups()
                showGroup(0)
                if (autoPlayAfterExpand) {
                    autoPlayAfterExpand = false
                    autoStarted = true
                    playInline(0, auto = true)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private var episodeSeq = 0

    private var autoStarted = false

    private var autoPlayAfterExpand = false

    /**
     * 集数网格：列数与行高都照原版 VideoActivity.setEpisodeAdapter 的算法
     * （VideoActivity.java 686-712 行实读）：
     *   numColumns 默认 10，按「集数名称最大长度」逐档降到 1/2/3/4/6/8；
     *   行高固定 44dp，列间隙 8dp（原版 colWidth = (屏宽-48dp-(cols-1)*8dp)/cols）。
     * 之前是硬编码 GridLayoutManager(this, 4)，且空格子靠 padding 12dp 撑，所以格距显得很大。
     */
    private fun applyEpisodeGrid() {
        val rv = findDetail<RecyclerView>(R.id.detailList)
        val len = episodes.maxOfOrNull { it.first.length } ?: 0
        val cols = when {
            len > 40 -> 1
            len > 30 -> 2
            len > 15 -> 3
            len > 10 -> 4
            len > 6 -> 6
            len > 4 -> 8
            else -> 10
        }
        val singleLine = runCatching { Settings(this).episodeStyle == 0 }.getOrDefault(false)
        rv.layoutManager = if (singleLine)
            androidx.recyclerview.widget.LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        else GridLayoutManager(this, cols)
        if (rv.itemDecorationCount == 0) {
            // 列间隙 8dp：左右各 4dp，相邻两格加起来正好 8dp（对齐原版 colWidth 的扣减方式）
            rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: android.graphics.Rect, view: View,
                    parent: RecyclerView, state: RecyclerView.State
                ) {
                    // 原版 VideoActivity.setEpisodeView(): setHorizontalSpacing(8dp) + setVerticalSpacing(8dp)
                    // 相邻两格各摊一半，正好 8dp
                    val half = dp(4)
                    outRect.left = half
                    outRect.right = half
                    outRect.top = half
                    outRect.bottom = half
                }
            })
        }
    }

    private fun showVodInfo(title: String) {
        val info = findDetail<View>(R.id.vodInfo) ?: return
        val name = findDetail<TextView>(R.id.vodInfoTitle) ?: return
        name.text = if (currentLine != null) "${currentLine!!.name} · $title" else title
        info.visibility = View.VISIBLE
        info.postDelayed({ info.visibility = View.GONE }, 3000)
    }

    private fun buildFlags(lines: List<PlayLine>) {
        val box = findDetail<android.widget.LinearLayout>(R.id.flagItems) ?: return
        box.removeAllViews()
        val row = findDetail<View>(R.id.flagRow)
        if (lines.isEmpty()) {
            row?.visibility = View.GONE
            return
        }
        row?.visibility = View.VISIBLE
        lines.forEach { line ->
            val tv = TextView(this).apply {
                text = line.name
                textSize = 16f
                // 原版 flag 行每项之间 8dp（CustomHorizontalGridView.setHorizontalSpacing(8)）
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = true
                isClickable = true
                background = getDrawable(R.drawable.selector_item)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener { switchToLine(line) }
            }
            box.addView(tv)
        }
        markActiveFlag(currentLine?.name.orEmpty())
    }

    private fun markActiveFlag(name: String) {
        val box = findDetail<android.widget.LinearLayout>(R.id.flagItems) ?: return
        for (i in 0 until box.childCount) {
            val tv = box.getChildAt(i) as? TextView ?: continue
            tv.setTextColor(if (tv.text.toString() == name) 0xFFFFCC00.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun switchToLine(line: PlayLine) {
        if (line.name == currentLine?.name) return
        currentLine = line
        val keep = playingIdx
        renderEpisodes(line)
        if (keep >= 0) playInline(keep.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)))
    }

    /**
     * 起播失败统一入口（对齐原版 VideoActivity.startFlow → checkFlag）：
     * 原版任何失败都走这里 —— 先 checkParse 轮解析源，再 checkFlag 逐条往后换线路，
     * 走到最后一条才 checkSearch 跨站。
     *
     * 我们之前只在 onPlayerError 里换源，而「解析返回空 url」「内核不支持该源」这两种
     * 失败发生在起播之前，根本触发不到 onPlayerError，所以自动换源永远不生效、只能手动点。
     */
    private fun handlePlayFailure(idx: Int, auto: Boolean, reason: String, depth: Int) {
        val v = vod ?: return
        val lines = v.parseLines()
        Log.e("XingHuiTV", "[detail] 起播失败 depth=$depth：$reason（当前 ${(currentLine?.name ?: "?")}，共 ${lines.size} 条线路）")
        val cur = lines.indexOfFirst { it.name == currentLine?.name }.let { if (it < 0) 0 else it }
        if (cur < lines.size - 1 && depth < lines.size) {
            val next = lines[cur + 1]
            // 对齐原版 Notify.show(getString(R.string.play_switch_flag, flag.getFlag()))
            Toast.makeText(this, "正在切换到「${next.name}」", Toast.LENGTH_SHORT).show()
            currentLine = next
            renderEpisodes(next)
            val i = idx.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
            playInline(i, auto = auto, depth = depth + 1)
            return
        }
        // 走完最后一条线路：原版继续 checkSearch 跨站搜索，本工程暂不做跨站
        Log.e("XingHuiTV", "[detail] 全部 ${lines.size} 条线路均失败")
        if (auto) {
            Toast.makeText(this, "所有线路都播放失败，可手动换源", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "内嵌全部线路失败，转播放页", Toast.LENGTH_SHORT).show()
            openPlayPage(idx)
        }
    }

    /**
     * 重建分段按钮：原版按 40 集一段（1-40 / 41-80 / 81-102）。
     * 注意：原版即使只有 6 集也照样显示「倒序 → 1-6」（整行存在，只是只有一个段按钮），
     * 只有单集时才没意义。之前写成 episodes.size <= GROUP_SIZE 就整行 GONE，
     * 导致 3 集的影片连「倒序」都没有。
     */
    private fun rebuildGroups() {
        val box = findDetail<android.widget.LinearLayout>(R.id.groupItems)
        box.removeAllViews()
        val row = findDetail<View>(R.id.groupRow)
        if (episodes.size <= 1) {
            row?.visibility = View.GONE
            return
        }
        row?.visibility = View.VISIBLE
        val sections = ((episodes.size + GROUP_SIZE - 1) / GROUP_SIZE)
        // 倒序时后缀小的段排在前面，和原版「倒序」的语义一致
        val list = if (epOrder) (sections - 1 downTo 0) else (0 until sections)
        for (s in list) {
            val start = s * GROUP_SIZE
            val end = minOf(start + GROUP_SIZE, episodes.size)
            val label = "${start + 1}-$end"
            val btn = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(if (s == activeSection) 0xFFFFCC00.toInt() else 0xFFFFFFFF.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isFocusable = true
                isClickable = true
                background = getDrawable(R.drawable.selector_tv_btn)
                setOnClickListener {
                    activeSection = s
                    rebuildGroups()
                    showGroup(s * GROUP_SIZE)
                    (it as TextView).requestFocus()
                }
            }
            box.addView(btn)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showGroup(start: Int) {
        pairs.clear()
        if (episodes.isEmpty()) {
            pairs.add("none" to "无集数")
            adapter.notifyDataSetChanged()
            return
        }
        val from = start.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
        val to = minOf(from + GROUP_SIZE, episodes.size)
        if (epOrder) {
            for (i in to - 1 downTo from) pairs.add("E:$i" to episodes[i].first)
        } else {
            for (i in from until to) pairs.add("E:$i" to episodes[i].first)
        }
        adapter.notifyDataSetChanged()
        adapter.setActive(if (playingIdx >= 0) "E:$playingIdx" else null)
    }

    private var showLines = true

    private fun onPick(key: String) {
        val v = vod ?: return
        when {
            showLines && key.startsWith("L:") -> {
                val idx = key.removePrefix("L:").toIntOrNull() ?: return
                val line = v.parseLines().getOrNull(idx) ?: return
                currentLine = line
                showLines = false
                renderEpisodes(line)
            }
            !showLines && key.startsWith("E:") -> {
                val idx = key.removePrefix("E:").toIntOrNull() ?: return
                MpvDebug.log("[pick] 点集数 E:$idx → openPlayPageAt（无条件进全屏）")
                // ★点集数 = 直接进全屏播放层（原版 VideoActivity 点集数即进全屏）。
                //   单 Activity 之后画面在宿主层的 videoFrame，详情页里的 playerBox 只是透明占位，
                //   既没有 inlinePlayer 也没有第二块 surface。原来那套「小窗里换集 /
                //   把 playerBox 铺满当全屏」已经没有任何画面可言 —— 铺满 playerBox 只会得到
                //   「左上一个小画面 + 其余全黑」，也就是这个 bug 的现场。
                openPlayPageAt(idx)
            }
        }
    }

    private fun switchSource() {
        val v = vod ?: return
        val lines = v.parseLines()
        if (lines.size <= 1) {
            Toast.makeText(this, "该影片只有 ${lines.size} 个线路，无处可换", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = lines.indexOfFirst { it.name == currentLine?.name }.let { if (it < 0) 0 else it }
        val next = lines[(cur + 1) % lines.size]
        currentLine = next
        renderEpisodes(next)
        Toast.makeText(this, "已换源：${next.name}", Toast.LENGTH_SHORT).show()
        // 正在播就接着播同一集号的另一线路，否则只切换集数列表
        if (playingIdx >= 0) playInline(playingIdx.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)))
    }

    private fun playInline(idx: Int, auto: Boolean = false, depth: Int = 0, quiet: Boolean = false) {
        val v = vod ?: return
        val line = currentLine ?: v.parseLines().firstOrNull() ?: return
        // 集数列表以「当前展示的 episodes」为准：磁力/种子被 expandThunder 展开后，
        // line.episodes 还是原始未展开的那份，用它会取错集或越界（点了不播）。
        val ep = episodes.getOrNull(idx)
            ?: line.episodes.getOrNull(idx)
            ?: run { Log.e("XingHuiTV", "[detail] 集索引越界 idx=$idx eps=${episodes.size}"); return }
        playingIdx = idx
        // 集数格里标出"当前这一集"（原版 setEpisodeActivated）
        adapter.setActive("E:$idx")
        setPreviewVisible(true)
        findDetail<View>(R.id.detailImage)?.visibility = View.GONE
        showVodInfo(ep.first)
        // 原版没有这条提示：VideoActivity.onItemClick 只做 shouldEnterFullscreen 判断与
        // selectEpisode，任何一步都不弹 Toast。删掉（历史注释本就记着"这句是自造的"）。
        // 续播：原版 VideoActivity 打开即 setPosition(Math.max(opening, history.position))。
        // 这里只在「和上次是同一集」时才带位置（HistoryStore.position 内部会核对集地址），
        // 换集就是从 0 开始，不会出现点第 3 集跳到第 1 集进度的情况。
        val resumeMs = runCatching {
            HistoryStore(this).marks(HistoryStore.keyOf(v.vodId, v.vodName, ep.second), ep.second)[2]
        }.getOrDefault(0L)
        // 对齐原版 5.6.0 VideoActivity.java:3655（`mHistory = mHistory == null ? createHistory(item) : mHistory`，定义在 :3705；startPlayer 调用在 :1234）—— 起播之前就把 History 建好，不看解析成败。
        // 放在解析之后写的话，解析慢、解析失败、看两眼就退出，三种情况都不留记录。
        runCatching {
            HistoryStore(this).addHistory(v.vodId, v.vodName, v.vodPic, ep.first, line.name, ep.second)
        }
        Thread {
            var url = ""
            var err = ""
            runCatching {
                val store = SubscriptionStore(this)
                val site = store.sites().firstOrNull { it.key == store.currentKey() }
                if (site == null) { err = "无当前站点"; return@runCatching }
                val play = if (site.isCsp()) {
                    CspLoader.player(this, site, line.name, ep.second.ifBlank { v.vodId })
                } else {
                    val params = JSONObject().put("flag", line.name)
                        .put("id", ep.second.ifBlank { v.vodId }).toString()
                    Engine.execute("player", store.currentKey() ?: "", params)
                }
                url = extractPlayUrl(play)
                if (url.isBlank()) err = "解析返回空 url"
            }.onFailure { err = it.message ?: it.javaClass.simpleName }
            runOnUiThread {
                if (url.isBlank()) {
                    // 解析失败也走自动换源（原版 startFlow 就是解析失败和播放失败同一个入口）
                    handlePlayFailure(idx, auto, "解析失败：$err", depth)
                    return@runOnUiThread
                }
                inlineUrl = url
                // ★这里必须拿到一颗**非空**播放器再下发。
                //   原来整段都是 player?.xxx —— player 为 null 时四次调用全部空转，
                //   却仍然返回 true，于是"成功了但什么都没加载"，没有任何日志，也不进换源。
                //   这是静默失败，不是容错。
                val p = player ?: runCatching { buildInlinePlayer() }.getOrNull()?.also {
                    player = it
                    applyCaptionStyle()
                    bindDetailListener(it)
                }
                if (p == null) {
                    handlePlayFailure(idx, auto, "内嵌播放器构建失败（无法下发媒体源）", depth)
                    return@runOnUiThread
                }
                // 画面绑定同样要在这里确认：小窗能出画面靠的是这块常驻 SurfaceView 上有播放器，
                // 只 prepare 不绑定 = 只有声音、画面留在海报上。
                // ★无条件重绑（setPlayer 内部 `old === p` 会短路，幂等）。
                //   原来写的是 `if (!previewAttached())`，而 previewAttached 只判断
                //   `surfaceConsumer != null`，不判断是不是**这一颗** —— 一旦玩家实例换过，
                //   这句就误判成"已绑"，新实例一个 surface 都没拿到：只有声音、画面留在旧帧上。
                attachPreviewTo(p)
                val ok = runCatching {
                    // mediaId 显式设成同一路 url：播放层靠它判断「小窗已经在播这一路」。
                    p.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(url).build())
                    p.prepare()
                    if (resumeMs > 1000) p.seekTo(resumeMs)
                    p.play()
                    true
                }.getOrElse { e ->
                    Log.e("XingHuiTV", "[detail] 内嵌播放源不被支持 $url", e)
                    false
                }
                if (!ok) {
                    handlePlayFailure(idx, auto, "内核不支持该地址（$url）", depth)
                }
            }
        }.start()
    }

    private fun extractPlayUrl(play: String): String {
        if (play.isBlank()) return ""
        runCatching {
            val obj = JSONObject(play)
            return when (val u = obj.opt("url")) {
                is String -> u
                is org.json.JSONArray -> {
                    // 每两个一组：名称, 地址（与原版 Result.parse 一致）
                    var i = 0
                    while (i + 1 < u.length()) {
                        val addr = u.optString(i + 1, "")
                        if (addr.startsWith("http")) return addr
                        i += 2
                    }
                    ""
                }
                is JSONObject -> {
                    val vals = u.optJSONArray("values") ?: return ""
                    for (i in 0 until vals.length()) {
                        val addr = vals.optJSONObject(i)?.optString("v", "").orEmpty()
                        if (addr.startsWith("http")) return addr
                    }
                    ""
                }
                else -> ""
            }
        }
        return ""
    }

    private fun openPlayPage(idx: Int) {
        val v = vod ?: return
        val line = currentLine ?: return
        val ep = line.episodes.getOrNull(idx) ?: return
        // 先把预览那一路停掉，再起播放页：两边同时出声就是「两个不同的声音」
        detachInlinePlayback()
        // ★再把预览从窗口里摘掉。
        //   官方 Surface types 文档："The lifecycle of a SurfaceView's surface is tied to **view visibility**"
        //   —— 只 release player 不动 View 的时候，这块 SurfaceView 仍然 VISIBLE，
        //   它的 surface 就一直挂在 SurfaceFlinger 的 overlay plane 上，跟播放页那块抢。
        //   texture_view 已经把"新装包"这一侧从根上去掉了；这一行是给"万一 XML 没覆盖到"留的兜底：
        //   GONE 才是可见性变化，才真正让 surface 销毁。返回时 onResume 会重建预览（playInline 里设回 VISIBLE）。
        // ★记住离开时的形态，返回时按它还原（见 onResume）。
        //   这里**不调 exitInlineFullscreen()**：它会让列表在跳转前就冒出来。
        inlineLeaveFullscreen = inlineFullscreen
        runCatching { setPreviewVisible(false) }
        // ★转场动画保留（曾经想去掉，已撤）。
        // 这是「去播放页」，不是「离开详情页」：回来应当自动续上
        inlineResumeOnReturn = true
        showPlayLayer(Intent(this, MainActivity::class.java).apply {
            putExtra("vod_id", v.vodId)
            putExtra("vod_name", v.vodName)
            putExtra("vod_pic", v.vodPic)
            putExtra("flag", line.name)
            putExtra("ep_url", ep.second)
        })
    }

    /**
     * 进全屏：播放器铺满整页，其余区域隐藏（对齐原版 enterFullscreen）。
     * ★ 这里必须用 RelativeLayout.LayoutParams：playerBox 的父容器是 activity_detail 的根 RelativeLayout，
     *   之前赋的是 LinearLayout.LayoutParams，运行时被父容器强转成 RelativeLayout 的参数 →
     *   ClassCastException: LinearLayout$LayoutParams cannot be cast to RelativeLayout$LayoutParams，
     *   表现就是「点方框或点集数，一全屏就闪退回首页」。
     */
    private fun enterInlineFullscreen() {
        if (inlineFullscreen) return
        inlineFullscreen = true
        setPreviewVisible(true)
        val box = findDetail<FrameLayout>(R.id.playerBox)
        savedBoxParams = box.layoutParams
        // 对齐原版 VideoActivity.enterFullscreen():853 —— mBinding.video.setForeground(null)。
        // 小窗挂的 selector_video 聚焦时画的是 shape_video_focused（1.5dp 纯白描边），
        // 全屏时不去掉，四周就留一整圈白线，也就是一直在说的「四周白条」。
        savedBoxForeground = box.foreground
        box.foreground = null
        box.layoutParams = android.widget.RelativeLayout.LayoutParams(
            android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
            android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
        )
        listOf(R.id.detailName, R.id.detailInfo, R.id.detailDesc,
            R.id.btnDesc, R.id.btnFav, R.id.btnSource, R.id.flagRow, R.id.groupRow, R.id.detailList).forEach {
            findDetail<View>(it)?.visibility = View.GONE
        }
        // ★画面在宿主层，铺满 playerBox 不等于铺满画面 —— 必须同时把 videoFrame 拉全屏，
        //   否则就是「playerBox 占满屏幕，但画面还是左上 400x225」（本 bug 的原始现场）。
        runCatching { videoFrameToFull() }
        box.requestFocus()
    }

    private fun exitInlineFullscreen() {
        if (!inlineFullscreen) return
        inlineFullscreen = false
        val box = findDetail<FrameLayout>(R.id.playerBox)
        // 还原进全屏时保存的那份参数（类型与父容器天然匹配，不做任何强转）
        savedBoxParams?.let { box.layoutParams = it }
        // 对齐原版 exitFullscreen():866 —— setForeground(selector_video) 还原小窗焦点边框
        box.foreground = savedBoxForeground ?: runCatching {
            getDrawable(R.drawable.selector_video)
        }.getOrNull()
        listOf(R.id.detailName, R.id.detailInfo, R.id.detailDesc,
            R.id.btnDesc, R.id.btnFav, R.id.btnSource, R.id.flagRow, R.id.groupRow, R.id.detailList).forEach {
            findDetail<View>(it)?.visibility = View.VISIBLE
        }
        // ★预览本身也必须恢复 VISIBLE —— 这一行之前漏了。
        //
        // 症状：详情页全屏预览里按菜单 → 转独立播放页 → 返回详情页后小窗**只有声音没有画面**。
        // 链条：openPlayPageFromCurrent() 里摘掉预览（inlineView = GONE）却没有退出全屏，
        //       于是 inlineFullscreen 仍为 true；返回时 onResume 的执行顺序是
        //         ① if (!inlineFullscreen) inlineView = VISIBLE   ← 被跳过
        //         ② if (inlineFullscreen) exitInlineFullscreen()   ← 只恢复了 9 个文字/列表视图，
        //            从来没恢复 inlineView 本身
        //       inlineView 一直是 GONE → PlayerView 内部那块 texture_view 的 surface 被销毁
        //       （media3 官方：SurfaceView 的 surface 生命周期绑 view visibility；
        //         PlayerView.setVisibility 会连带设内部 surface view，ExoPlayer#3160）
        //       → 解码器照常在跑、声音照常在出，画面没有承载层。
        runCatching { setPreviewVisible(true) }
        findDetail<View>(R.id.detailList).requestFocus()
    }

    /**
     * 菜单键（原版 VideoActivity.dispatchKeyEvent:1686-1687 只在全屏时分流）。
     * 详情页内嵌播放器没有原版那套控制条 UI，所以菜单键直接把当前集转独立播放页
     * PlayActivity —— 那才是原版 VideoActivity 的形态，控制条、选集、选轨全都在。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ★★ 播放层优先：onHostKeyEvent 就是原来 PlayActivity.dispatchKeyEvent 的全套语义。
        //   之前这里**从来没转发过**，进播放层后所有按键都落到 super，于是「除了返回键全无效」。
        if (playMode) {
            val handled = runCatching { playLayer?.onHostKeyEvent(event) ?: false }.getOrDefault(false)
            if (handled) return true
        }
        val menu = event.keyCode == KeyEvent.KEYCODE_MENU
        if (inlineFullscreen && menu) {
            val fire = event.action == KeyEvent.ACTION_UP ||
                (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
            if (fire) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastMenuTriggerMs > 600) {
                    lastMenuTriggerMs = now
                    openPlayPageFromCurrent()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ★补 onKeyUp：这台盒子的菜单键（kc=82）只发 ACTION_UP 不发 DOWN，
    //   fastForwarding 之类的收尾只在 onKeyUp 里，不接这条就等于长按永远收不回来。
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (playMode) {
            val handled = runCatching { playLayer?.onHostKeyUp(keyCode, event) ?: false }.getOrDefault(false)
            if (handled) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun openPlayPageFromCurrent() {
        val v = vod ?: return
        val line = currentLine ?: return
        val ep = episodes.getOrNull(playingIdx) ?: line.episodes.getOrNull(playingIdx) ?: return
        // ★记住离开时的形态（这里一定是全屏 —— 只有全屏时菜单键才会走到这个函数）。
        //   原来在这里调 exitInlineFullscreen()：它会立刻把 9 个列表/文字视图恢复可见、
        //   把小窗尺寸还原，而 startActivity 还没执行 —— 转场期间用户看到的就是
        //   「详情页布局 + 小窗黑洞」，也就是"按菜单后回详情页会先显示详情页，再重新加载画面"。
        //   改成只记住形态，返回时由 onResume 按形态还原。
        inlineLeaveFullscreen = true
        // 交接给播放页之前先把预览停下来（暂停 + 摘掉画面层，但保留实例）
        detachInlinePlayback()
        // 摘掉预览（全屏那条路这里是 1920x1080 的整屏 overlay，代价最大）
        runCatching { setPreviewVisible(false) }
        inlineResumeOnReturn = true
        showPlayLayer(Intent(this, MainActivity::class.java).apply {
            putExtra("vod_id", v.vodId)
            putExtra("vod_name", v.vodName)
            putExtra("vod_pic", v.vodPic)
            putExtra("flag", line.name)
            putExtra("ep_url", ep.second)
            // ★小窗正在播的就是这一集（全屏预览时菜单键转播放页）：把地址一起交过去，
            //   播放层据此**复用**这一路，不重新解析、不重新加载。
            putExtra("pre_url", inlineUrl)
        })
        // 同上：不做 overridePendingTransition(0, 0)，理由见 openPlayPage 里的说明。
    }

    private fun openPlayPageAt(idx: Int) {
        val v = vod ?: return
        val line = currentLine ?: return
        val ep = episodes.getOrNull(idx) ?: line.episodes.getOrNull(idx) ?: return
        // ★点的是不是"小窗此刻正在播的那一集"——决定播放层要不要复用现成的这一路。
        //   必须在 playingIdx 被改写之前取。
        val wasPlayingThis = idx == playingIdx
        val reusableUrl = if (wasPlayingThis) inlineUrl else ""
        playingIdx = idx
        runCatching { adapter.setActive("E:$idx") }
        inlineLeaveFullscreen = inlineFullscreen
        // 交接给播放层之前先把详情页预览停下来（暂停 + 摘掉画面层，但保留实例）
        detachInlinePlayback()
        runCatching { setPreviewVisible(false) }
        inlineResumeOnReturn = true
        showPlayLayer(Intent(this, MainActivity::class.java).apply {
            putExtra("vod_id", v.vodId)
            putExtra("vod_name", v.vodName)
            putExtra("vod_pic", v.vodPic)
            putExtra("flag", line.name)
            putExtra("ep_url", ep.second)
            // ★同集：把已经在播的地址交过去，播放层直接接管这一路（不重载）。
            //   不同集（小窗在播别的集）：留空，播放层照常解析新的一集。
            putExtra("pre_url", reusableUrl)
            // ★这是原版 VideoActivity.onItemClick 的语义：
            //     if (shouldEnterFullscreen(item)) return;   ← item.isSelected() 时**只放大容器**
            //     selectEpisode(item, true);                  ← 否则才换集
            //   原版从来不去比对"当前播的是不是这个地址"，它相信的是**宿主的意图**：
            //   这一集是不是我正在播的那一集。我们把这条意图显式传下去，
            //   播放层就不再靠 url 字符串比较来猜（那是脆弱的一环：代理化/重定向/头部差异都会让它失配）。
            if (wasPlayingThis) putExtra("attach_only", true)
        })
    }

    override fun onBackPressed() {
        if (playMode) { exitPlayLayer(); return }
        // 全屏时返回键先退全屏，不直接退出页面（对齐原版 mKeyDown 的返回语义）。
        // 这里接 select_small_window_back_key 两档（原版）：
        //   0 = 返回（退出全屏）  1 = 焦点移动（不退出全屏，焦点交给集数列表）
        if (inlineFullscreen) {
            val backMode = runCatching { Settings(this).smallWindowBackKey }.getOrDefault(0)
            if (backMode == 0) exitInlineFullscreen()
            else findDetail<android.view.View>(R.id.detailList)?.requestFocus()
            return
        }
        super.onBackPressed()
    }

    private fun saveInlinePosition() {
        val v = vod ?: return
        val ep = episodes.getOrNull(playingIdx) ?: return
        val pos = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        if (pos > 1000) runCatching {
            HistoryStore(this).setPosition(HistoryStore.keyOf(v.vodId, v.vodName, ep.second), ep.second, pos)
        }
        Log.i("XingHuiTV", "[detail] 保存进度 ${pos}ms ep=${ep.first}")
    }

    /**
     * 预览播放器只有一路出声：离开本页、或转去独立播放页之前必须先停。
     *
     * 真机上表现为「播放有时会有两个不同的声音」—— 原因就是这里：
     * 详情页内嵌预览是我们自己加的（原版 VodActivity 里没有任何播放器，只有列表和详情），
     * 跳去 PlayActivity 时本页只是走到 onPause，之前只保存进度、没有 pause，
     * 预览那一路音频继续在响，和新起的播放页叠在一起，所以「有时」（预览正在播时才会）。
     *
     * mpv 内核不申请音频焦点，EXO 内核离开焦点也不一定立刻停，
     * 所以不能指望系统帮忙，必须自己停。
     */
/**
     */
    /**
     * 进播放页前的收尾：暂停预览、把画面层摘掉，但**保留** ExoPlayer 实例。
     *
     * 为什么不再 release：
     *   release 之后返回只能 buildInlinePlayer() 重建 + prepare()，
     *   重建必然走 IDLE → BUFFERING → READY，小窗要转一次圈 —— 用户看到的就是
     *   "回来了但又要重新加载画面"。保留实例后它一直是 READY，返回时
     *   seekTo + play 即可，画面几乎立刻就在。
     * 代价只是一个**暂停态**的 ExoPlayer 在播放页期间活着（不是第二路在播，
     * 不会有两个声音、也不占解码带宽）；onDestroy 里已有 player?.release() 兜底。
     * 若播放页期间进程被回收导致实例没了，onResume 里的 `player ?: buildInlinePlayer()`
     * 会自动退回原来的重建路径。
     */
/**
     */
    private fun detachInlinePlayback() {
        Log.i("XingHuiTV", "[detail] 进播放页：播放器与画面都不动，只切容器")
    }

    private fun releaseInlinePlayback() {
        runCatching {
            detachSurfaceFrom()
            player?.release()
            player = null
            Log.i("XingHuiTV", "[detail] 离开页面，预览播放器已释放")
        }
    }

    private fun pauseInlinePlayback() {
        runCatching {
            val m = playLayer?.mpvInstance() ?: return@runCatching
            runCatching { m.setBackground(true) }
            runCatching { m.pause() }
            Log.i("XingHuiTV", "[detail] 离开页面，mpv 已转为后台并暂停")
        }
        runCatching {
            val p = player ?: return@runCatching
            if (p.isPlaying || p.playWhenReady) {
                inlinePausedByLeave = true
                p.pause()
                Log.i("XingHuiTV", "[detail] 离开页面，预览播放器已暂停")
            }
        }
    }

    override fun onPause() {
        runCatching { playLayer?.onHostPause() }

        // 退到后台、按返回、切播放页，都先落一次进度；否则只有正常看完才记得住
        saveInlinePosition()
        // 同时停声：只落进度不停播放，离开页面后音频会继续在后台响
        pauseInlinePlayback()
        super.onPause()
    }

    /**
     * 页面已不可见：再兜一次底。
     *
     * onPause 之后到 onStop 之间还可能有人把播放拉起来 —— 内核 watchdog 恢复、mpv 的
     * 后台模式（setBackground(true)）本身就不抑制音频、以及某些 ROM 上 onResume 提前触发。
     * 走到 onStop 时窗口已经不可见，这一刻必须保证没有声音。
     */
    override fun onStop() {
        runCatching { playLayer?.onHostPause() }
        pauseInlinePlayback()
        super.onStop()
    }

    override fun onResume() {
        if (playMode) playLayer?.onHostResume()

        super.onResume()
        // 分两种「回来」：
        //   1. 从我们自己的播放页返回 → 预览要继续播（否则小窗停在离开时那一帧，
        //      看着像卡住/退回第一屏）；这是本页自己发起的跳转，续播是预期行为
        //   2. 从别处回来（首页回来、系统弹窗回来）→ 保持原样，不自动出声
        if (inlineResumeOnReturn) {
            inlineResumeOnReturn = false
            if (inlineLeaveFullscreen) {
                // 离开时是全屏预览 → 回到全屏预览。
                // 跳转前刻意没动布局，9 个视图还是 GONE、playerBox 还是全屏参数，
                // 所以这里只要把画面层恢复可见并把焦点还给 playerBox，不需要再叠一次全屏。
                inlineLeaveFullscreen = false
                runCatching {
                    setPreviewVisible(true)
                    findDetail<FrameLayout>(R.id.playerBox)?.requestFocus()
                }
            } else {
                // 离开时是小窗 → 回到小窗。
                // 跳转前 was GONE（见 openPlayPage 的说明），回来先恢复可见，
                // 否则重建预览时画面在窗外、小窗是黑的
                runCatching { setPreviewVisible(true) }
                if (inlineFullscreen) runCatching { exitInlineFullscreen() }
            }
            // ★画面层要在这里就挂回去：下面「换过集」那条分支会直接 playInline() 后 return，
            //   走不到后面重建段的挂载。实例被保留时它是 READY，挂上就能出画面。
            runCatching {
                val p0 = player
                if (p0 != null) attachPreviewTo(p0)
            }
            // 在独立播放页里换过集：集号以历史里最后记下的那一集为准。
            // 不这样做，返回后小窗会退回离开前那一集 —— 就是「选了第 3 集，退回来又变第 1 集」。
            val v0 = vod
            if (v0 != null) {
                val key0 = HistoryStore.keyOf(v0.vodId, v0.vodName, episodes.getOrNull(playingIdx)?.second)
                val lastEp = runCatching { HistoryStore(this).lastEpUrl(key0) }.getOrDefault("")
                if (lastEp.isNotBlank()) {
                    val idx = episodes.indexOfFirst { it.second.isNotBlank() && it.second == lastEp }
                    if (idx >= 0 && idx != playingIdx) {
                        Log.i("XingHuiTV", "[detail] 播放页换集过：${playingIdx} → $idx，按该集重建预览")
                        playInline(idx, quiet = true)
                        // 这条分支直接 return，不经过下面的续播段，焦点要在这里补一次，
                        // 否则同样会落到"没有任何视图持有焦点"的样子。
                        findDetail<View>(R.id.detailList)?.requestFocus()
                        return
                    }
                }
            }
            runCatching {
                // 进播放页时预览已释放，这里就地重建（参数与首次完全一致）
                val p = player ?: buildInlinePlayer().also { player = it; applyCaptionStyle() }
                // ★跳转时摘掉了画面层（inlineView.player = null），回来要重新挂上。
                //   实例若被保留，此刻它是 READY，不需要 prepare，也就不会转圈。
                runCatching { attachPreviewTo(p) }
                if (p.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    if (inlineUrl.isNotBlank()) {
                        p.setMediaItem(MediaItem.Builder().setUri(inlineUrl).build())
                    }
                    p.prepare()
                }
                // 对齐原版 updateHistory:1278-1290：
                //   同一集才续播（换集本来就回 0），位置取 max(片头, 历史进度)。
                // 之前只 play() 不定位，小窗一旦进过 IDLE 就从 0 播 —— 真机上就是「先回到片头」。
                val v = vod
                val ep = episodes.getOrNull(playingIdx)?.second
                if (v != null && !ep.isNullOrBlank()) {
                    val key = HistoryStore.keyOf(v.vodId, v.vodName, ep)
                    val store = HistoryStore(this)
                    // 一次读出片头与本集进度（原来分别解析两遍历史 JSON）
                    val m = store.marks(key, ep)
                    val at = maxOf(m[0], m[2])
                    if (at > 0) runCatching { p.seekTo(at) }
                    Log.i("XingHuiTV", "[detail] 从播放页返回，预览续播 at=${at}ms")
                } else {
                    Log.i("XingHuiTV", "[detail] 从播放页返回，预览续播 at=0ms")
                }
                p.play()
                // 回到本页后焦点要落到集数列表上：SurfaceView 重建期间焦点常常是空的，
                // 真机表现就是「黑一下，得再按一次才好使」。这里主动收一次焦点。
                findDetail<View>(R.id.detailList)?.requestFocus()
            }
        }
        inlinePausedByLeave = false
    }

    override fun onDestroy() {
        runCatching { playLayer?.onHostDestroy() }

        saveInlinePosition()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun showDescDialog() {
        val v = vod ?: return
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val tv = TextView(this)
        tv.text = v.vodContent.ifBlank { "暂无简介" }
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        tv.setLineSpacing(4f, 1f)
        tv.setPadding(48, 40, 48, 40)
        tv.movementMethod = LinkMovementMethod.getInstance()
        dlg.setContentView(tv)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }

    private fun toggleFav() {
        val v = vod ?: return
        val fav = store.toggleFavorite(v.vodId, v.vodName, v.vodPic, v.vodRemarks)
        applyFavState(fav)
        Toast.makeText(this, if (fav) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }

    private fun applyFavState(fav: Boolean) {
        val btn = findDetail<TextView>(R.id.btnFav) ?: return
        btn.text = "收藏"
        btn.setCompoundDrawablesWithIntrinsicBounds(
            if (fav) R.drawable.ic_detail_keep_on else R.drawable.ic_detail_keep_off,
            0, 0, 0
        )
        btn.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
    }
}
