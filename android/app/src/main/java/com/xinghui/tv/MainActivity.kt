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

    /** 播放层（懒加载：第一次进播放才 inflate） */
    private var playLayer: PlayLayer? = null
    /** 当前占据这块 surface 的 ExoPlayer（预览或播放页），用于 surfaceCreated 后补挂 */
    private var surfaceConsumer: androidx.media3.exoplayer.ExoPlayer? = null
    /** SurfaceView 的 surface 是否已有效 */
    private var surfaceValid = false
    /** 是否处于播放页形态（播放层在前台） */
    private var playMode = false

    /** 详情层的节点都在 detailRoot 里，统一从这里找 */
    private fun <T : View> findDetail(id: Int): T = detailRoot.findViewById(id)
    private fun <T : View> findViewHost(id: Int): T = this.findViewById<View>(id) as T


    private lateinit var store: HistoryStore
    private var vod: Vod? = null
    private var currentLine: PlayLine? = null

    /** 内嵌播放器（对齐原版 mBinding.video） */
    private var player: ExoPlayer? = null
    /**
     * 小窗形态的原始 LayoutParams —— 原版的 mFrameParams。
     * ★保存的是 XML 里那个对象本身，退出全屏时原样还回去。
     *   原版：VideoActivity.exitFullscreen() → mBinding.video.setLayoutParams(mFrameParams);
     *   我之前是手算 400dp×225dp 再补 margin，等于重新拼一个"差不多"的参数，
     *   漏掉的属性就会表现为位置或尺寸不对。
     */
    private var frameParams: android.view.ViewGroup.LayoutParams? = null

    /** 详情页小窗的占位框（透明）。画面本体一直是 host 层那块 videoFrame。 */
    private lateinit var inlineView: android.view.View
    private var inlineFullscreen = false
    /** 预览是否因为离开页面被我们主动暂停（回来时不自动续播） */
    private var inlinePausedByLeave = false
    /** 本次离开是「跳去独立播放页」，回来要把预览续上；按返回退出详情页则不用 */
    private var inlineResumeOnReturn = false
    // ★进独立播放页之前，预览当时是「小窗」还是「全屏」。
    //   跳转前刻意不调用 exitInlineFullscreen()：那会把列表立刻恢复可见、把小窗尺寸还原，
    //   而页面还没跳走 —— 转场那段时间用户看到的就是「详情页布局 + 小窗一个黑洞」，
    //   也就是"按菜单后回详情页会先显示详情页"。改成记住形态，返回时再按它还原。
    private var inlineLeaveFullscreen = false
    private var inlineUrl: String = ""
    /** 进全屏前的 playerBox 原始 LayoutParams（退出时原样还原） */
    private var savedBoxParams: android.view.ViewGroup.LayoutParams? = null
    /** 进全屏前小窗上的焦点边框（selector_video），退出全屏时原样还原 */
    private var savedBoxForeground: Drawable? = null
    /** 当前正在播放的集下标；-1 表示还没起播 */
    private var playingIdx = -1
    /** 当前线路的集数数组，供"点当前那集进全屏"判断用 */
    private var episodes: List<Pair<String, String>> = emptyList()
    /** 是否倒序（原版「倒序」按钮） */
    private var epOrder = false
    /** 当前所在分段（0 表示 1-40 那一段） */
    private var activeSection = 0
    /** 原版每段 40 集（1-40 / 41-80 / 81-102） */
    private val GROUP_SIZE = 40
    /** 播放器框的 16:9 高度（退出全屏时按这个恢复） */
    private var boxH = 0

    private val pairs = mutableListOf<Pair<String, String>>() // (key, 显示名)
    private lateinit var adapter: ListAdapter

    // ================= 宿主：唯一 surface + 层切换 =================

    /** 把这块唯一的 surface 交给某个引擎；surface 还没创建时先记下，created 后自动补挂 */
    /**
     * 把详情页的小窗画面交给某个引擎 —— 走 TextureView。
     * ★这里不再碰 videoSurface.holder.surface：那块 SurfaceView 只服务全屏播放页，
     *   详情页期间它被不透明的 detailLayer 盖住，尺寸一次都不改。
     *   历史教训：以前小窗也用那块 SurfaceView，靠改 LayoutParams 缩放，
     *   surface 的 buffer 尺寸跟着变，API 25 的 MediaCodec 不重建输出 → 有声音没画面。
     */
    private fun attachPreviewTo(p: androidx.media3.exoplayer.ExoPlayer) {
        surfaceConsumer = p
        // ★必须是 setVideoSurfaceView(View)，不能是 setVideoSurface(裸 Surface)。
        //   media3 只有在拿到一个 View 时才会挂 SurfaceHolder.Callback，并在 surfaceChanged
        //   里自己调用 setVideoSurfaceSize —— 解码器就是靠这一次通知重建输出。
        //   给裸 Surface 的话没有任何人监听尺寸变化，容器从小窗撑到全屏时画面就停住了。
        runCatching { p.setVideoSurfaceView(videoSurface) }
    }

    private fun detachSurfaceFrom() {
        surfaceConsumer?.let { runCatching { it.clearVideoSurfaceView(videoSurface) } }
        surfaceConsumer = null
    }

    private fun previewAttached(): Boolean = surfaceConsumer != null

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

    /** 全屏形态 */
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
    fun showPlayLayer(extras: Intent) {
        val layer = playLayer ?: PlayLayer(this) { exitPlayLayer() }.also { playLayer = it }
        layer.prepare(extras)
        layer.bind(playContainer)
        // 详情预览先把这块 surface 让出来，再交给播放层 —— 同一块，不新建
        detachSurfaceFrom()
        runCatching { player?.pause() }
        playMode = true
        detailLayer.visibility = View.GONE
        playContainer.visibility = View.VISIBLE
        // 画面层一直是 VISIBLE：小窗形态下它只有 400x225，剩下的区域露出窗口壁纸；
        // 只有它变成 MATCH_PARENT 时才占满全屏。所以壁纸从不被挡住。
        videoFrameToFull()
        layer.onHostResume()
    }

    /** 退出播放层：播放层的引擎原样交回详情预览，不 release、不重建 */
    /**
     * 把详情页的监听器挂到某个引擎上。★ 退出播放层时播放层会把引擎原样交回来，
     * 但监听器不会跟着回来 —— 不重新挂，小窗就没有 onVideoSizeChanged，画面比例和分辨率都会停在旧值。
     */
    /**
     * 详情页用到的播放器监听器。**必须复用同一个实例**。
     *
     * ★这是一个自己造出来的负面优化：以前这里每次都 addListener 一个新建的匿名对象，
     *   而从不 removeListener。来回进出播放页几次之后，同一个事件（错误、vsize、首帧）
     *   会回调 N 次 —— 表现为「播放失败连发好几次」「诊断 Toast 一次弹两三个」「日志成倍」，
     *   还会让 handlePlayFailure 的 800ms 去抖形同虚设。
     *   监听器不是一次性的，挂一次就够了。
     */
    private var detailListener: androidx.media3.common.Player.Listener? = null

    private fun bindDetailListener(p: androidx.media3.exoplayer.ExoPlayer) {
        detailListener?.let { runCatching { p.removeListener(it) } }
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 同一个错误可能连发，800ms 内只处理一次，避免疯狂切源
                val now = System.currentTimeMillis()
                if (now - lastErrorMs < 800) return
                lastErrorMs = now
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
        playLayer?.onHostPause()
        playContainer.visibility = View.GONE
        detailLayer.visibility = View.VISIBLE
        // 播放层的引擎原样交回：不 release、不 prepare、不 seek。
        val p = playLayer?.takePlayer()
        if (p != null) player = p
        val q = p ?: player
        if (q == null) return
        bindDetailListener(q)
        // ★海报要收起来。以前退出播放层没走这一步：如果进播放页前海报是可见的，
        //   回来后海报会一直盖在小窗上 —— 用户看到的是静止封面，而不是"没画面"。
        runCatching { setPreviewVisible(true) }
        // ① 容器先缩回小窗（原版 mFrameParams 那一步）
        videoFrameToMini()
        // ② 把画面交回，走 setVideoSurfaceView —— media3 自己接住尺寸变化
        attachPreviewTo(q)
        // ★引擎可能已经被重置成 IDLE（换源/换集/换内核都会）。IDLE 下调 play() 不会起播，
        //   表现就是"小窗有 surface 但一直是灰的"。
        if (q.playbackState == androidx.media3.common.Player.STATE_IDLE) {
            // 只 prepare 不设 mediaItem 等于空转，先补上片源
            runCatching {
                if (q.mediaItemCount == 0 && inlineUrl.isNotBlank()) {
                    q.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri(inlineUrl).build())
                }
            }
            runCatching { q.prepare() }
        }
        runCatching { q.play() }
        // 把判断依据直接打到屏幕上：装一版就能看清是哪一环断的，不用再去翻日志。
        runCatching {
            val st = when (q.playbackState) {
                1 -> "IDLE"; 2 -> "BUFFERING"; 3 -> "READY"; 4 -> "ENDED"; else -> "?" + q.playbackState
            }
            // ★必须延后：videoFrameToMini 只是 requestLayout，布局要到下一帧才跑完。
            //   在同一拍读 videoSurface.width 拿到的是上一轮的尺寸（1920x1080），
            //   那条 Toast 因此误导了两轮排查。
            videoSurface.postDelayed({
                runCatching {
                    android.widget.Toast.makeText(
                        this,
                        "[exit] st=$st playing=${q.isPlaying} " +
                            "vsize=${q.videoSize.width}x${q.videoSize.height} " +
                            "tracks=${q.currentTracks.groups.size} " +
                            "frame=${videoFrame.width}x${videoFrame.height} " +
                            "sv=${videoSurface.width}x${videoSurface.height} " +
                            "shown=${videoSurface.isShown} surfValid=$surfaceValid",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }, 600)
        }
        MpvDebug.log(
            "[exit] 交回小窗 frame=${videoFrame.width}x${videoFrame.height} " +
                "sv=${videoSurface.width}x${videoSurface.height} " +
                "vsize=${q.videoSize.width}x${q.videoSize.height} " +
                "state=${q.playbackState} playing=${q.isPlaying} surfaceValid=$surfaceValid"
        )
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
        videoSurface = (findViewHost(R.id.playerView) as androidx.media3.ui.PlayerView)
            .videoSurfaceView as android.view.SurfaceView
        frameParams = videoFrame.layoutParams
        subtitleView = findViewHost(R.id.subtitleView)

        detailRoot = layoutInflater.inflate(R.layout.activity_detail_layer, detailLayer, false)
        detailLayer.addView(detailRoot)

        // ★从历史 / 直播 / 网页推送进来时带 play_mode=true：直接落在播放层。
        //   单 Activity 之后必须补的入口 —— 以前是 PlayActivity 自己 onCreate 就起播。
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
                // ★只在播放页把 surface 交出去。详情页期间这块 SurfaceView 是 GONE 的，
                //   它一旦重新出现不应该抢走详情页小窗（小窗走 TextureView）。
                if (playMode) surfaceConsumer?.let { p -> runCatching { p.setVideoSurface(h.surface) } }
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
                renderLines()
            }
        }.start()
    }

    /** 显示影片信息 + 线路列表 */
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
                // 与播放页同源，对齐原版 ExoUtil.buildLoadControl()：new DefaultLoadControl()。
                // 之前按 exoBufferSec 把缓冲砍到 5s/10s 是负优化，已回滚（默认 50s）。
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
                // 官方 Players.java:158：HDMI / 蓝牙音频断开时自动暂停
                it.setHandleAudioBecomingNoisy(true)
                // 内嵌预览同样请求音频焦点：进播放页时播放页抢焦点，这一路会自动 LOSS → 暂停，
                // 不再依赖手动 pauseInlinePlayback 兜底（原版 Players.java:155 同一句）。
                it.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                attachPreviewTo(it)
            }
        return p
    }

    private fun applyCaptionStyle() {
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

    /** 详情报自动起播：优先上次看到的那一集，没有记录就第 1 集 */
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

    /** 显示当前线路的集数列表 */
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

    /** 异步展开磁力/种子；返回空列表（无做种等）时保留原样，点进去由播放页直接解析 */
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

    /** 每次渲染线路自增，用于丢弃过期的异步展开结果 */
    private var episodeSeq = 0

    /** 详情页自动起播只做一次（换线路/换源由各自逻辑负责） */
    private var autoStarted = false

    /** 迅雷单条：等磁力/种子展开完成后再起播 */
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
        // 集数区高度是 match_parent，横向单行会把每个格子撑满整屏高，所以这里只做网格。
        // 列数按名称长度自适应（原版 setEpisodeAdapter 的算法），不需要用户再选「选集样式」。
        rv.layoutManager = GridLayoutManager(this, cols)
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

    /** 播放器框内起播浮层（原版 view_widget_vod 的 info），3 秒后自动收起 */
    private fun showVodInfo(title: String) {
        val info = findDetail<View>(R.id.vodInfo) ?: return
        val name = findDetail<TextView>(R.id.vodInfoTitle) ?: return
        name.text = if (currentLine != null) "${currentLine!!.name} · $title" else title
        info.visibility = View.VISIBLE
        info.postDelayed({ info.visibility = View.GONE }, 3000)
    }

    /** 填充播放源 tab：原版 flag 行就是同一站点的全部播放源（超级无敌4K / 高清4K / …） */
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

    /** 选中的源标黄（原版 setFlagActivated 的观感） */
    private fun markActiveFlag(name: String) {
        val box = findDetail<android.widget.LinearLayout>(R.id.flagItems) ?: return
        for (i in 0 until box.childCount) {
            val tv = box.getChildAt(i) as? TextView ?: continue
            tv.setTextColor(if (tv.text.toString() == name) 0xFFFFCC00.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    /** 切到指定源，保持当前集号接着播 */
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
    private var lastErrorMs = 0L
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

    /** 只展示 [start, start+GROUP_SIZE) 这一段集数；倒序时在该段内反向排列 */
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
                // ★点集数 = 直接进全屏播放层（原版 VideoActivity 点集数即进全屏）。
                //   单 Activity 之后画面在宿主层的 videoFrame，详情页里的 playerBox 只是透明占位，
                //   既没有 inlinePlayer 也没有第二块 surface。原来那套「小窗里换集 /
                //   把 playerBox 铺满当全屏」已经没有任何画面可言 —— 铺满 playerBox 只会得到
                //   「左上一个小画面 + 其余全黑」，也就是这个 bug 的现场。
                openPlayPageAt(idx)
            }
        }
    }

    /** 换源：在当前影片的各条播放线路之间轮换（对齐原版 onChange → 重新取播放地址） */
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

    /** 当前线路的第 idx 集（对齐原版 setEpisodeActivated → onRefresh） */
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
        // 「准备播放 X」这句是自造的，原版没有；返回列表重建时还会再弹一次，更没必要
        if (!quiet) Toast.makeText(this, "准备播放 ${ep.first}", Toast.LENGTH_SHORT).show()
        // 续播：原版 VideoActivity 打开即 setPosition(Math.max(opening, history.position))。
        // 这里只在「和上次是同一集」时才带位置（HistoryStore.position 内部会核对集地址），
        // 换集就是从 0 开始，不会出现点第 3 集跳到第 1 集进度的情况。
        val resumeMs = runCatching {
            HistoryStore(this).marks(HistoryStore.keyOf(v.vodId, v.vodName, ep.second), ep.second)[2]
        }.getOrDefault(0L)
        // 对齐原版 VideoActivity:1269 —— 起播之前就把 History 建好，不看解析成败。
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
                // ExoPlayer 缺扩展模块时会抛 IllegalStateException（No suitable media source factory），
                // 以前没兜住，换到 m3u8 源就直接闪退回主页。这里失败就转独立播放页走完整链路。
                inlineUrl = url
                val ok = runCatching {
                    player?.setMediaItem(MediaItem.Builder().setUri(url).build())
                    player?.prepare()
                    if (resumeMs > 1000) player?.seekTo(resumeMs)
                    player?.play()
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

    /** 从 playerContent 的 JSON 里取第一个可播地址（url 可能是字符串/扁平数组/对象） */
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

    /** 转独立播放页（完整链路兜底） */
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
        // ★这里**故意不做** overridePendingTransition(0, 0)。
        //
        // 上一版做过，而且放错了位置（放在 startActivity 之前，按官方定义属于空操作）。
        // 现在连「放对位置」也不做了，原因有三：
        //   1) 转场动画要保留 —— 它不是黑屏的原因，它是**掩盖**黑屏的那层东西。
        //      播放页窗口出现时 SurfaceView 的 surface 还没有内容（黑），有淡入就是「淡入一段黑」，
        //      没有动画就是「整屏瞬间黑住」。原版一直是系统默认转场，从没关过。
        //   2) 腾讯云开发者社区实证：overridePendingTransition(0, 0) 本身就会导致跳转短暂黑屏，
        //      正确做法是换回真实 anim，或者干脆用系统默认。
        //   3) 关掉动画还会让新窗口的输入焦点提前到来，这时按键语义还没挂好，
        //      更容易出现「进去按 ← 没反应」—— 保留动画反而给了首帧和焦点一个缓冲。
        // 结论：系统默认转场 + DetailActivity 不做任何 overridePendingTransition。
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

    /** 退出全屏：恢复小窗与其它区域（原版退出全屏走返回键，这里同样） */
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
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                openPlayPageFromCurrent()
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

    /** 把当前正在播的这一集原样交给独立播放页 */
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
        })
        // 同上：不做 overridePendingTransition(0, 0)，理由见 openPlayPage 里的说明。
    }

    /** 点集数 / 点小窗：直接进全屏播放层，播第 idx 集（原版点集数即进全屏） */
    private fun openPlayPageAt(idx: Int) {
        val v = vod ?: return
        val line = currentLine ?: return
        val ep = episodes.getOrNull(idx) ?: line.episodes.getOrNull(idx) ?: return
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
        })
    }

    override fun onBackPressed() {
        if (playMode) { exitPlayLayer(); return }
        // 全屏时返回键先退全屏，不直接退出页面（对齐原版 mKeyDown 的返回语义）
        if (inlineFullscreen) {
            exitInlineFullscreen()
            return
        }
        super.onBackPressed()
    }

    /** 把内嵌播放器当前进度写回历史（原版 updateHistory 里 setPosition 那条） */
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
     * 进播放页前直接释放内嵌预览。
     * 只 pause 不 release 时，详情页这一路 ExoPlayer（含解码器、缓冲）会和播放页那一路同时活着，
     * 2GB 的盒子上等于两套解码器常驻；这也是「两个声音」的结构性来源之一。
     * 返回时用 buildInlinePlayer() + inlineUrl 就地重建，不再重新解析接口。
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
    private fun detachInlinePlayback() {
        runCatching {
            player?.let { if (it.isPlaying) it.pause() }
            detachSurfaceFrom()
            Log.i("XingHuiTV", "[detail] 离开页面，预览已暂停并摘掉画面（实例保留，返回可续上）")
        }
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
            val p = player ?: return@runCatching
            if (p.isPlaying || p.playWhenReady) {
                inlinePausedByLeave = true
                p.pause()
                Log.i("XingHuiTV", "[detail] 离开页面，预览播放器已暂停")
            }
        }
    }

    override fun onPause() {
        if (playMode) playLayer?.onHostPause()

        // 退到后台、按返回、切播放页，都先落一次进度；否则只有正常看完才记得住
        saveInlinePosition()
        // 同时停声：只落进度不停播放，离开页面后音频会继续在后台响
        pauseInlinePlayback()
        super.onPause()
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
                // ★从播放页回来先把「全屏预览」复位。
                //   进播放页的两条路（小窗全屏 enterInlineFullscreen、转独立播放页 openPlayPage）
                //   都会把 inlineFullscreen 置为 true 并**一直保留**，而它只在详情页自己的返回键里
                //   才会复位。于是从播放页按返回时，看到的是「铺满整页的预览」，还得再按一次返回
                //   才回到详情页 —— 这就是"要按两次"的结构性来源（原版没有内嵌预览，没有这个状态）。
                if (inlineFullscreen) runCatching { exitInlineFullscreen() }
            }
            // ★画面层要在这里就挂回去：下面「换过集」那条分支会直接 playInline() 后 return，
            //   走不到后面重建段的挂载。实例被保留时它是 READY，挂上就能出画面。
            runCatching {
                val p0 = player
                if (p0 != null && !previewAttached()) attachPreviewTo(p0)
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
                runCatching { if (!previewAttached()) attachPreviewTo(p) }
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
        if (playMode) runCatching { playLayer?.onHostDestroy() }

        saveInlinePosition()
        player?.release()
        player = null
        super.onDestroy()
    }

    /** 简介全文弹窗（支持点击链接），对应原项目 DescDialog */
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

    /** 收藏态：文字固定「收藏」，靠图标切换（原版 keep 就是 off/on 两个矢量，实心星表示已收藏） */
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
