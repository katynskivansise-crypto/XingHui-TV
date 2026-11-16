package com.xinghui.tv

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.xinghui.tv.dialog.EpisodeDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放页：
 * - 解析播放地址 → 按设置内核播放（内置 ExoPlayer / 外部 mpv）
 * - 遥控器【菜单键】弹出选集/换源面板（EpisodeDialog），切换线路/集数即时重新解析播放
 * - 播放成功自动写入历史（含线路+集地址，供历史页续播）
 * 入参（Intent extra）：
 *   vod_id / vod_name / vod_pic / flag(线路名) / ep_url(集地址) / direct_url(直播直链，可空)
 */
/**
 * 播放层。宿主 MainActivity 里的一个 View 层，不是 Activity。
 *
 * 不拆成独立 Activity 的理由只有一个：窗口只能创建一次，SurfaceView 只能有一块。
 * 新窗口要重新拿 hasWindowFocus（实测 199~434ms），窗口期按键无接收者，
 * 且 SurfaceView 首次出现会触发一次 relayout 丢一帧。
 *
 * 生命周期由宿主显式转发：bind / onHostResume / onHostPause / onHostWindowFocusChanged /
 * onHostConfigurationChanged / onHostKeyEvent / onHostKeyUp / onHostDestroy
 */
class PlayLayer(private val host: MainActivity, private val onExit: () -> Unit) {

    private var args: android.content.Intent? = null
    fun prepare(a: android.content.Intent) { args = a }

/**
     */
    fun mpvInstance(): MpvPlayer? = if (isMpvMode()) mpv else null

    fun currentPlayer(): androidx.media3.common.Player? =
        if (isMpvMode()) (mpv ?: player) else (player ?: mpv)

    fun isAttached(): Boolean = attached

/**
     */
    /**
     * 解析 Intent 字段。
     * 从 bind() 里抽出来的：bind() 只 inflate 一次（attached 后立刻 return），
     *   而每次从详情页进来都可能带着不同的集 / 不同的线路，字段必须每次重解析。
     */
    private fun applyArgs(a: android.content.Intent) {
        vodId = a.getStringExtra("vod_id") ?: ""
        vodName = a.getStringExtra("vod_name") ?: ""
        vodPic = a.getStringExtra("vod_pic") ?: ""
        flag = a.getStringExtra("flag") ?: ""
        epUrl = a.getStringExtra("ep_url") ?: ""
        invalidateHistoryKey()
        directUrl = a.getStringExtra("direct_url") ?: ""
        pushMode = a.getBooleanExtra("push_mode", false)
        // 直播频道与手机推送写入历史时，vod_id 存的就是播放地址本身（原版同样如此）。
        // 从历史点回来时不会有 direct_url，这里补认一次，否则会去站点详情接口查一个 URL，必然失败。
        if (directUrl.isBlank() && (looksLikeMedia(vodId) || ThunderPlay.match(vodId))) directUrl = vodId
        siteKey = SubscriptionStore(host).currentKey() ?: ""
        runCatching {
            // 两张表顺序完全一致（0.25/0.5/0.75/1.0/1.25/1.5/2.0），设置里的档位下标就是这里的下标
            val at = Settings(host).playSpeed.coerceIn(0, speedSteps.lastIndex)
            speedIdx = at
            savedSpeedIdx = at
        }
    }

/**
     */
    /**
     * @param reuseUrl 非空表示**这一路已经在播**（详情页小窗正在播的就是它）。
     *        此时只接管界面状态，不重新拉站点详情、不重新 setMediaSource ——
     *        重新加载必然走 IDLE→BUFFERING→READY，用户看到的就是"每次点开播放界面都重新加载一次"。
     *        这是原版 enterFullscreen() 的语义：播放器与媒体项都不动，只换容器。
     */
    private fun startPlayback(reuseUrl: String = "") {
        if (reuseUrl.isBlank()) showPlayProgress("进页面解析")
        // 输入环境探针（只打一次，几乎零开销）。
        //   Android 官方文档明确：开启无障碍/触摸探索后，D-pad 事件不会传给开发者定义的方向键处理器，
        //   而是被无障碍服务拿走用于移动无障碍焦点 —— 这是"应用收不到方向键、菜单键却照常到达"的一个真实成因。
        //   touchMode 同理：触摸模式下没有视图持有焦点，第一下按键只会把焦点捡回来。
        //   这两项只读一次，就足以越过所有猜测。
        runCatching {
            val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            MpvDebug.log(
                "[env] touchMode=${runCatching { window.decorView.isInTouchMode }.getOrNull()}" +
                    " a11y=${am?.isEnabled} touchExploration=${am?.isTouchExplorationEnabled}" +
                    " rootFocusable=${rootView?.isFocusable} rootFocusableInTouch=${rootView?.isFocusableInTouchMode}" +
                    " decorHasFocus=${runCatching { window.decorView.hasFocus() }.getOrNull()}"
            )
        }
        MpvDebug.log("[play] 进页面 开始解析/起播 t=${System.currentTimeMillis() % 100000}")
        updateDecodeText()
        updatePlayerText()
        val marks0 = HistoryStore(host).marks(historyKey, epUrl)
        openingMs = marks0[0]
        endingMs = marks0[1]
        startPosMs = marks0[2]
        updateOpeningText()
        updateEndingText()
        // 倍速：点击循环档位，对齐原项目 addSpeed()
        val speedBtn = findViewById<TextView>(R.id.btnSpeed)
        speedBtn.setOnClickListener { toggleSpeed() }
        // 长按倍速快进（对齐原项目 onSpeedUp：长按 3x，松开恢复）
        speedBtn.setOnLongClickListener {
            fastForwarding = true
            savedSpeedIdx = speedIdx
            val press = runCatching { Settings(host).pressSpeed }.getOrDefault(3.0f)
            speedBtn.text = if (press >= 3f) "快进 3x（松开恢复）"
            else String.format(java.util.Locale.US, "%.2f", speedSteps[speedIdx])
            if (press >= 3f) {
                if (isMpvMode()) mpv?.setSpeed(press) else player?.setPlaybackSpeed(press)
            }
            true
        }
        // 缩放档位循环（对齐原项目 onScale/setScale）
        findViewById<TextView>(R.id.btnScale).setOnClickListener { toggleScale() }
        // 上一集/下一集（对齐原项目 control.prev/next → checkPrev/checkNext）
        findViewById<View>(R.id.btnPrev).setOnClickListener { switchEpisode(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { switchEpisode(1) }
        // 清晰度切换（对齐原项目 QualityAdapter/setQualityActivated）
        findViewById<View>(R.id.btnQuality).setOnClickListener { toggleQuality() }

        if (historyKey.isNotBlank()) recordHistory()

        // 复用分支：小窗那一路上来就是 READY，这里只补齐界面与状态，绝不重设媒体项。
        if (reuseUrl.isNotBlank()) {
            activeUrl = reuseUrl
            lastPlayedUrl = reuseUrl
            hidePlayProgress()
            updateDecodeText()
            updatePlayerText()
            setKeepScreenOn(true)
            attachPlayerListeners()
            MpvDebug.log("[play] 复用已在播的一路，不重新加载 url=${reuseUrl.take(48)} pos=${currentPosMs()}ms")
            return
        }
        // 直播直链：直接播放；影片：先拉详情拿线路（供选集面板），再解析播放
        if (directUrl.isNotBlank()) {
            // 手机推送（push_mode）有两种来源：
            //   1) 直链 m3u8/mp4/磁力/迅雷 —— 直接进播放器；
            //   2) 网页分享地址 —— 交给嗅探 WebView 抓真实流地址，等价原版
            //      VideoActivity.push → 播放失败后转解析/嗅探的路径。
            // 直播直链（push_mode=false）保持原样，不受影响。
            // 只有 http/https 的「页面地址」才进嗅探；rtmp/rtsp/smb/ftp 这类协议直接交播放器，
            // 否则 WebView 会去加载一个它根本不支持的 scheme。认不出的 scheme 同样不进 WebView。
            val scheme = directUrl.substringBefore(':', "").lowercase()
            val pageLike = scheme == "http" || scheme == "https"
            if (pushMode && pageLike && !ThunderPlay.match(directUrl) && !looksLikeMedia(directUrl)) {
                startSniff(directUrl, emptyMap())
            } else {
                playUrl(directUrl)
            }
            // 历史由上面的守卫统一写一次：push / 直播 / 历史回放三条来源都带了 vod_id，
            // 这里再写一次会是同一 key 的重复入库（addHistory 虽幂等，但没必要双写）。
        } else {
            Thread { fetchAndPlay(vodId, flag, epUrl) }.start()
        }
    }

    /**
     * 进入播放层。每次进入都走这一条：
     *   1) 记下 Intent
     *   2) 重解析参数（换集 / 换线路都会变）
     *   3) bind 只在第一次真正干活（inflate + 绑 UI）
     *   4) 每次重新起播
     */
    fun enter(a: android.content.Intent, parent: android.view.ViewGroup) {
        // 重新进场第一件事：解除退场闸门。
        //   leaving 会在返回键那一刻置 true（见 onHostKeyEvent 的 BACK 分支），它的用途有两个：
        //   · 挡掉 savePosition 的重复落盘；
        //   · 挡掉**退出之后仍在后台跑的解析/自动换源链**。
        //   如果进场不复位，第二次进入时这条闸门仍然关着 —— 表现就是
        //   「进得去、但解析结果被丢弃、什么都不播，而且退出后再也不落盘」。
        leaving = false
        val wasAttached = attached
        // 必须在 applyArgs 之前比：applyArgs 会把 epUrl 覆盖成新值
        val sameEpisode = a.getStringExtra("ep_url").orEmpty() == epUrl &&
            a.getStringExtra("flag").orEmpty() == flag
        args = a
        applyArgs(a)
        bind(parent)
        val src = player ?: host.player
        val playerBroken = src?.playerError != null
        if (wasAttached && sameEpisode && activeUrl.isNotBlank() && !playerBroken) {
            MpvDebug.log("[play] 同集再入：补齐界面，不重载 url=${activeUrl.take(48)}")
            startPlayback(reuseUrl = activeUrl)
            return
        }
        // 小窗已经在播这一路 → 进播放页只接管界面，不重新解析、不重新加载。
        //   判据用宿主播放器**当前媒体项**而不是本层的 activeUrl：首次进播放页时 activeUrl 还是空的，
        //   只看它永远判不出"已经在播"，于是每次都老老实实重新解析一遍 ——
        //   这就是"原版进详情页就开始加载、点进播放界面却要再转一次圈"的差别。
        val preUrl = a.getStringExtra("pre_url").orEmpty()
        // 取 localConfiguration.uri 作主判据：mediaId 只有在小窗起播时显式设过才有值，
        //   而 uri 是 setMediaItem 就存在的。两者取先到的非空值，避免任何一侧漏设导致永远判不中。
        val runningUrl = runCatching {
            val item = src?.currentMediaItem ?: return@runCatching ""
            item.localConfiguration?.uri?.toString().orEmpty().ifBlank { item.mediaId }
        }.getOrDefault("")
        val hasContent = (src?.mediaItemCount ?: 0) > 0
        // 内核为 mpv 时不能复用：详情页小窗那一路上宿主 EXO 起的，播放页要交 mpv，
        //   直接把 EXO 的媒体项当成"已在播"接过去就是黑屏。这种跨内核只能老实加载一次。
        //   （要让 mpv 用户也做到"零加载"，得让详情页小窗直接跑 mpv —— 那是下一步，不在本包。）
        // 判据不命中时也要留一行证据 —— 否则"为什么还要加载一次"只能靠猜。
        //   这一行把五个条件的实际值全打出来，一眼就能看出卡在哪个上。
        MpvDebug.log(
            "[play] 复用判据 preUrl=${preUrl.take(48)} running=${runningUrl.take(48)} " +
                "hit=${preUrl.isNotBlank() && runningUrl == preUrl} ready=$hasContent src=${if (src == null) "null" else (if (player === src) "layer" else "host")} " +
                "mpv=${isMpvMode()} broken=$playerBroken"
        )
        // 原版语义优先：宿主说"这就是我正在播的那一集"（attach_only）且播放器确实有内容，
        //   就直接接管 —— 不再做 url 字符串比较。原版 VideoActivity.shouldEnterFullscreen
        //   用的是 item.isSelected()，从头到尾没有比对过地址。
        val attachOnly = a.getBooleanExtra("attach_only", false)
        // 原版 shouldEnterFullscreen(Episode item) 的判据只有 item.isSelected()：
        //   点的是「此刻这一集」就进全屏，从不判断"是否已经有画面"。
        //   这里同样不能拿 hasContent 当门槛 —— 小窗那一路可能还在解析中，
        //   把同一颗实例接管过来、等它自己到位，才是"进全屏不重新加载、不回片头"。
        if (attachOnly && src != null && !playerBroken && !isMpvMode()) {
            if (player !== src) {
                player = src
                runCatching { session.adoptExo(src) }
            }
            if (hasContent) {
                MpvDebug.log("[play] attach_only：宿主正在播这一集，只接管界面 url=${preUrl.take(48)}")
                startPlayback(reuseUrl = if (preUrl.isNotBlank()) preUrl else runningUrl)
            } else {
                MpvDebug.log("[play] attach_only：宿主仍在解析这一集，接管实例并等待其到位")
                awaitHostEpisode(src, preUrl)
            }
            return
        }
        if (preUrl.isNotBlank() && runningUrl == preUrl && !playerBroken && hasContent && !isMpvMode()) {
            // 复用前必须把宿主那一颗接进本层：player 字段与 session.exo 都要指向它，
            //   否则 startPlayback 之后 onHostResume 的 play() 会落到空引用上（表现就是"进去了不播"）。
            if (src != null && player !== src) {
                player = src
                runCatching { session.adoptExo(src) }
            }
            MpvDebug.log("[play] 小窗已在播，接管界面不重载 url=${preUrl.take(48)} pos=${currentPosMs()}ms")
            startPlayback(reuseUrl = preUrl)
            return
        }
        startPlayback()
    }

    /**
     * 接管「宿主还在解析」的那一集。
     *
     * 原版进全屏从不发起第二次解析：它把同一次加载的结果从小窗直接放大到全屏。
     * 所以这里只等待 —— 宿主那颗实例一旦出现媒体项（onMediaItemTransition，
     * 这是 media3 里"媒体项到位"的唯一官方信号），立刻按"已在播"接管界面；
     * 宿主报错、或等不到结果，才回落到本层自己的解析。
     */
    private fun awaitHostEpisode(src: androidx.media3.common.Player, fallbackUrl: String) {
        showPlayProgress("继续加载")
        var settled = false
        var listener: androidx.media3.common.Player.Listener? = null
        var timeout: Runnable? = null

        fun settle(url: String, byTimeout: Boolean) {
            if (settled) return
            settled = true
            listener?.let { runCatching { src.removeListener(it) } }
            timeout?.let { t -> runCatching { rootView?.removeCallbacks(t) } }
            MpvDebug.log("[play] 接管小窗结果 url=${url.take(48)} timeout=$byTimeout")
            if (url.isNotBlank()) startPlayback(reuseUrl = url) else startPlayback()
        }

        val l = object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val u = mediaItem?.localConfiguration?.uri?.toString().orEmpty()
                    .ifBlank { mediaItem?.mediaId.orEmpty() }
                if (u.isNotBlank()) settle(u, false)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                MpvDebug.log("[play] 小窗那一路失败，改由本层解析 ${error.errorCodeName}")
                settle("", false)
            }
        }
        listener = l
        runCatching { src.addListener(l) }
        // 宿主解析可能根本没回来（站点慢 / 失败但未报错）—— 兜底仍由本层解析，
        // 只是这条路只在等不到时才走，正常情况不会触发第二次加载。
        timeout = Runnable { if (!settled) settle(fallbackUrl, true) }
        // 超时对齐原版 Constant.TIMEOUT_PLAY（15 秒），不是自创的 8 秒
        timeout?.let { t -> runCatching { rootView?.postDelayed(t, 15_000) } }
    }

    /**
     * 再次进入播放层：把引擎和画面一起接回来。
     *
     * 所有权模型：引擎归宿主，画面层由宿主临时借给详情小窗。
     *   退出时 takePlayer() 把 EXO 交回宿主、releaseSurface() 让画面层松手；
     *   再进入必须由宿主把这两样都还回来。
     *   缺这一步时 PlayLayer.player 恒为 null，onHostResume 里的 player?.play() 是空操作 ——
     *   真机表现就是「从播放页回详情页后，再点集数进不去播放页」。
     */
    private val window get() = host.window
    private fun runOnUiThread(action: Runnable) = host.runOnUiThread(action)
    private val isFinishing get() = host.isFinishing
    private val isDestroyed get() = host.isDestroyed
    private val packageName get() = host.packageName
    private val intent get() = args ?: host.intent
    private val resources get() = host.resources
    private fun getSystemService(name: String): Any? = host.getSystemService(name)
    private fun <T> getSystemService(cls: Class<T>): T? = host.getSystemService(cls)

    private lateinit var root: android.view.View
    private fun <T : android.view.View> findViewById(id: Int): T = root.findViewById(id)

    val attached: Boolean get() = this::root.isInitialized

    private var player: ExoPlayer? = null

    private var vodId = ""

    /**
     * 历史记录主键。原版 History 以「vod_id 为空则用片名」建 key，
     * 但 setPosition / marks / setOpeningOf 都会先判 vodId 为空直接 return。
     * 详情接口不返回 vod_id 的站点就会写不进进度，读出来恒为 0。
     * 统一走这个 key，写入与读取才是同一条记录。
     */
    /**
     * 历史键 —— **进页面时算一次，之后冻结**。
     *
     * 为什么必须冻结：`vodId` 在本页里会被两处改写
     *   · 手机推送嗅探成功后 `vodId = playable`（第 656 行附近）
     *   · 手动换源后 `vodId = target.vodId`
     * 而详情页/首页读历史用的是"进页面时的那个 key"。只要中途改了，
     * 后面写进去的位置就落到另一行上 —— 退出再进、返回详情页都读不到，表现就是回片头。
     * 冻结后：写和读永远是同一个字符串。
     */
    // 不能用 by lazy 冻结：yield 出来的键只反映"第一次访问那一刻"的 vodId/vodName/epUrl。
    //   而运行中这三个值会被合法改写 ——
    //     · 换集 epUrl = ep
    //     · 手机推送 vodId = playable
    //     · 自动换源 vodId = target.vodId
    //   冻结之后，换源/推送的新内容仍然往旧剧那一行写历史，下次进入还按旧剧的位置续播；
    //   vodId 为空（只靠 epUrl 推键）时换集更糟：不同集算成同一个键，直接串位置。
    //   这里保留"算一次就缓存"的性能意图，改为在输入真的变化时失效。
    private var historyKeyCache: String? = null
    private val historyKey: String
        get() = historyKeyCache ?: HistoryStore.keyOf(vodId, vodName, epUrl)
            .also { historyKeyCache = it }

    private fun invalidateHistoryKey() {
        historyKeyCache = null
    }
    private var vodName = ""
    private var vodPic = ""
    private var flag = ""
    private var epUrl = ""
    private var directUrl = ""
    private var pushMode = false          // 手机推送进来（网页地址要走嗅探）
    private val MEDIA_SUFFIXES = listOf(".m3u8", ".mp4", ".flv", ".mkv", ".ts", ".mp3", ".m4a", ".aac", ".mpd")
    private var siteKey = ""

    private var vodLines: List<PlayLine> = emptyList()

    private val speedSteps = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = 3 // 默认 1.0x（本表下标 3）
    private var fastForwarding = false
    private var savedSpeedIdx = speedIdx
/**
     */
    private val videoHost: VideoHost get() = host.videoHost
    private lateinit var session: PlaybackSession
    // 对齐原版 PlayerSetting.getScale():150 —— "scale" 未设置时默认 0，即「原始」= FIT。
    // 原版 @id/exo 是 PlayerView，FIT 下按视频比例 letterbox，两侧留黑是正常形态。
    private var scaleIdx = 0
    // 对齐原版 select_scale：原始 / 16:9 / 4:3 / 填充 / 裁剪 → ExoPlayer resizeMode
    private val scaleModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val scaleNames = arrayOf("原始", "16:9", "4:3", "填充", "裁剪")

    // 清晰度（对齐原版 Url.values）：多清晰度切换；单清晰度时隐藏按钮
    private val qualities = mutableListOf<Pair<String, String>>() // (name, v)
    private var qualityIdx = 0

    // 自动换站（对齐原版 checkSearch→nextSite）：线路走完失败后自动搜同片名其他站点；限次防死循环
    private var siteSwitchCount = 0
    private val MAX_SITE_SWITCH = 2

    fun bind(parent: android.view.ViewGroup) {
        if (attached) return
        tCreate = SystemClock.uptimeMillis()
        root = host.layoutInflater.inflate(R.layout.activity_play_layer, parent, false)
        parent.addView(root)
        MpvDebug.log("[perf] bind(inflate)=${SystemClock.uptimeMillis() - tCreate}ms")
        // 屏显（原版 `play_display`）：**逐元素**按掩码显隐，不是一整个开关。
        //    bit0 片名 / bit1 时钟 / bit2 网速 / bit3 总时长 / bit4 底部 1dp 进度条
        runCatching {
            val mask = Settings(host).displayMask
            // 同样只用 alpha：这几项是常驻元素，GONE 会改绘制区域（黑帧）
            fun vis(v: android.view.View?, bit: Int) {
                v?.alpha = if ((mask and (1 shl bit)) != 0) 1f else 0f
            }
            vis(root?.findViewById(R.id.playTitle), 0)
            vis(root?.findViewById(R.id.clock), 1)
            vis(root?.findViewById(R.id.netspeed), 2)
            vis(root?.findViewById(R.id.playDuration), 3)
            vis(root?.findViewById(R.id.bottomProgress), 4)
        }
        // 根容器当画面容器用（原版 @id/video 的等价物）：可聚焦，收控制条时焦点回这里
        rootView = root
        // 对齐原版 5.6.0 VideoActivity.java:1141「进页面就把焦点放到画面容器」：
        // 有焦点时这条 ROM 上按键分发最稳，也是控制条收起时的落点。
        // 进页面这一拍的实际焦点状态写进 /debug：进页面到按菜单键之前，
        // 如果这里是 cur=-1 且 winFocus=false，就说明"按键无效"是窗口/焦点这一层没接上，
        // 而不是按键分支写错（分支层面 ←→/OK 根本不看焦点）。
        rootView?.post {
            // 守卫：只在"没人持焦"时落点。
            //   这个 post 会等到首帧才执行；如果用户已经先按了菜单、焦点进了控制条，
            //   这里无条件 requestFocus 就会把它抢回根容器 —— 真机日志里表现为
            //   「呼出完成 nowFocus=DefaultTimeBar」→ 一秒后「was=-1(FrameLayout)」，
            //   然后靠 showControl 里的补救再落回条上。这一抢一补正是首键体感不稳定的来源。
            val hadFocus = host.currentFocus
            val rootOk = if (hadFocus == null) rootView?.requestFocus() == true else true
            val dv = window?.decorView
            if (dv != null && !dv.hasWindowFocus()) runCatching { dv.requestFocus() }
            MpvDebug.log(
                "[ui] 进页面焦点 rootFocus=$rootOk 原有焦点=${hadFocus?.id} cur=${host.currentFocus?.id}" +
                    " winFocus=${runCatching { dv?.hasWindowFocus() }.getOrElse { null }}" +
                    " rootFocusable=${rootView?.isFocusable} barVis=${controlBar?.visibility}"
            )
        }
        // 调试快照：手机浏览器打开 http://盒子IP:9978/debug 就能看到这份状态（界面零改动）
        MpvDebug.snapshot = { mpvDebugLine() }
        // 播放页不铺壁纸。原版是「小窗 + 详情」结构，壁纸在画面四周可见；
        // 我们是全屏画面，壁纸只会把窗口背景变成不透明 drawable，而 SurfaceView 靠窗口挖洞
        // 露画面 —— 窗口不透明等于把洞堵死。其它页面继续铺壁纸。
        // 全屏靠主题 windowFullscreen + systemUiVisibility（见 applyImmersive），不额外下发窗口 flag。
        runCatching { window.setBackgroundDrawable(null) }
            .onFailure { MpvDebug.log("[boot] 窗口背景置空失败：$it") }

        applyArgs(intent)

        session = PlaybackSession(
            exoFactory = { null },                        // 实例由 Activity 自己建，建好交给会话
            mpvProvider = { mpv },
            isMpvKernel = { isMpvMode() },
            bindSurfaceToExo = { p -> videoHost?.setPlayer(p) },
            events = object : PlaybackSession.Events {
                override fun onState(state: PlaybackSession.State) = Unit
                override fun onError(message: String) = Unit
                override fun onEnded() = Unit
            }
        )
        session.cueSink = { group -> videoHost?.subtitleView?.setCues(group.cues) }
        session.setExoBuilder { h -> rebuildPlayerInternal(h) }
        MpvDebug.log(
            "[boot] build=tk21 layer=aspect+surface+subtitle sdk=${android.os.Build.VERSION.SDK_INT}" +
                " host=${if (videoHost != null) "ok" else "null"}"
        )

        enableKeepContent()
        /**
         * keep_content_on_player_reset 保持 media3 默认 false：原版 styles.xml 里
         * Player.Vod 为 false、Player.Live 为 true。点播换集必须清掉旧片画面，
         * 否则上一集最后一帧会挂在新集起播前；期间由 progress 转圈占位。
         */
        // 画面缩放（对齐原版 VideoActivity.getScale：本片记录优先，-1 才回落全局 Setting.getScale）
        scaleIdx = HistoryStore(host).scaleOf(historyKey)
            .takeIf { it >= 0 } ?: Settings(host).playerScale
        scaleIdx = scaleIdx.coerceIn(0, scaleModes.size - 1)
        videoHost?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale)?.text = scaleNames[scaleIdx]
        // mpv 内核下不必先建 EXO 实例：T1 只有 2GB 内存，两套解码器并存很吃紧。
        // 切回 EXO 时 rebuildPlayer() 会补建，所以这里可以省。
        if (!isMpvMode()) {
            val tExo0 = SystemClock.uptimeMillis()
            // 只能走 rebuildPlayerInternal：它开头第一句就「宿主演的那一颗存在就复用」，
            //   而且是唯一带全套配置的构建路径（RenderersFactory / TrackSelector / LoadControl / MediaSourceFactory）。
            //   新那颗 setPlayer 时把宿主那颗的画面层摘走（setPlayer(null)），但宿主那颗没人暂停 ——
            //   声音继续放、新那颗还没媒体项，真机表现正是「只听见声音、画面冻在一帧」；
            //   退出再进时宿主那颗还在、复用命中，才恢复正常。
            player = rebuildPlayerInternal(activeHeaders)
            applyCaptionStyle()
            attachPlayerListeners()
            MpvDebug.log("[perf] ExoPlayer构建=${SystemClock.uptimeMillis() - tExo0}ms")
        } else {
            // mpv 内核：先把原生实例建好，按键（暂停/快进）从第一秒起就有效。
            // 画面层这里先摆出来，真正的 play(url) 在地址解析完之后由 startMpv 触发。
            // 画面层是一块 SurfaceView，EXO 与 mpv 轮流接管（见 VideoHost 类注释）。
            // 原生内核初始化要几十到几百毫秒（mpv_create + 四十多条选项 + observeProperty + attachSurface）。
            // 放在 onCreate 里同步执行会把"首帧"和"窗口拿到输入焦点"一起往后推 ——
            // 从详情页进来那一两秒的按键会被系统直接丢掉，表现就是"进去按 OK 没反应"。
            // 原版 onCreate 里只有 ExoPlayer 的构造（轻得多），不存在这个空档。
            // 挪到首帧之后：窗口先挂上、先拿到输入焦点，内核随后建好（地址解析本来也要时间）。
            val sv = currentSurface()
            if (sv == null) {
                MpvDebug.log("[mpv] 拿不到共用画面层，起播时会退回 EXO")
            } else {
                sv.post {
                    if (isFinishing || isDestroyed) return@post
                    ensureMpv(sv)
                    MpvDebug.log("[mpv] 首帧后建实例（绑定 mpv 专用 SurfaceView，按键已可用）")
                }
            }
        }
        findViewById<TextView>(R.id.playTitle).apply {
            text = vodName.ifBlank { "播放中" }
            // 原版 title 用 ellipsize=marquee；TextView 的 marquee 只有 isSelected=true 才会滚
            // （或持有焦点时滚）。TV 上这里不抢焦点，所以用 isSelected 打开。
            isSelected = true
        }
        // 顶部标题条也用 alpha 表显隐：它的 GONE↔VISIBLE 会带来一次 layout pass
        findViewById<View>(R.id.playTitle)?.let { it.visibility = View.VISIBLE; it.alpha = 0f }

        applyImmersive()
        // 原版播放页没有返回按钮（VideoActivity 靠系统返回键），这里同样不摆
        // 控制条按钮全部绑定（结构照原版 view_control_vod.xml）
        val tBar0 = SystemClock.uptimeMillis()
        bindControlBar()
        // 时间条监听只能在 bind（一次性）里挂：DefaultTimeBar.addListener 没有对应的 remove，
        //   挂几次就回调几次。它刚从 startPlayback 挪回来 —— 那版每进一次播放页就多挂一个，
        //   onScrubStop 会成倍执行（也是"进出几次后按键手感变差"的来源之一）。
        bindTimeBarScrub()
        MpvDebug.log("[perf] bindControlBar=${SystemClock.uptimeMillis() - tBar0}ms 距onCreate=${SystemClock.uptimeMillis() - tCreate}ms")
        videoHost?.post { setupSharedSurface() }
        uiHandler.postDelayed(snapTick, 1000)
        surfCallback?.let { runCatching { videoHost?.removeSurfaceCallback(it) } }
        val cb = object :
            android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                surfGen++
                // 色深能力恢复：API25 的 SurfaceView 默认是 RGB_565（16bit），
                //   之前我们是在播放中途 setFormat(RGBA_8888)，等于向 SurfaceFlinger
                //   申请换一整套 buffer（队列重建），那才是"动画面层拓扑"。
                //   正确做法是**在 surface 刚创建、还没有任何 buffer 的时候**设一次：
                //   之后分配的 buffer 天生就是 8888，播放中再也不用碰。
                //   8bit 链路靠它拿满 24bit；10bit 由 mpv 的离屏格式（RGBA10A2）负责。
                // 不在这里 setFormat。
                //   SurfaceHolder.setFormat 在 surface 已经创建的情况下会让系统重分配 buffer，
                //   老 ROM 上等于把这块 surface 重建一次（EXO 解码器跟着重启、mpv 要重挂 EGL）。
                //   稳定优先：先保持官方默认 PixelFormat，色深另想办法（EXO 输出侧协商，不动 surface）。
                lastSurfCreateAt = android.os.SystemClock.uptimeMillis()
                val posNow = runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
                val playingNow = runCatching { player?.isPlaying }.getOrNull()
                MpvDebug.log("[sv2] created ${h.surfaceFrame.width()}x${h.surfaceFrame.height()} gen=$surfGen " +
                    "kernel=${if (isMpvMode()) "mpv" else "exo"} pos=${posNow}ms playing=$playingNow mpvPos=${mpvPos}ms")
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {
                MpvDebug.log("[sv2] changed ${w}x$hh fmt=$f kernel=${if (isMpvMode()) "mpv" else "exo"}")
            }
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                MpvDebug.log("[sv2] destroyed kernel=${if (isMpvMode()) "mpv" else "exo"}")
            }
        }
        surfCallback = cb
        videoHost?.addSurfaceCallback(cb)
    }

    private fun fetchAndPlay(vid: String, fl: String, ep: String) {
        var playFlag = fl
        var playEp = ep
        runCatching {
            val store = SubscriptionStore(host)
            val site = store.sites().firstOrNull { it.key == siteKey }
            val detail = if (site?.isCsp() == true) {
                CspLoader.detail(host, site, vid)
            } else {
                Engine.execute("detail", siteKey, JSONObject().put("ids", vid).toString())
            }
            val v = Vod.parseList(detail).firstOrNull()
            v?.let { vodLines = it.lines() }
            // 从历史点进来时，线路名/集地址可能有缺（老记录、站点改过线路名）。
            // 原版 VideoActivity 拿不到也会自己回落到第一集，这里做同样的事，
            // 否则 resolvePlay 会把影片 id 当成集地址去请求，直接解析失败。
            val line = vodLines.firstOrNull { it.name == playFlag } ?: vodLines.firstOrNull()
            if (line != null) {
                if (playFlag.isBlank()) playFlag = line.name
                if (playEp.isBlank()) playEp = line.episodes.firstOrNull()?.second.orEmpty()
            }
        }
        resolvePlay(playFlag, playEp)
    }

    private fun resolvePlay(fl: String, ep: String) {
        // 退场即作废：这条方法一律从后台线程进来（fetchAndPlay / tryAutoSwitch），
        //   用户按返回之后它还会跑完 —— 跑完就会调 playUrl 往**详情页绑定的播放器**里塞新地址、
        //   改 activeUrl，接着 playUrl 失败又触发 onPlayFail → 又换线路 → 又解析。
        //   详情页那边看到的就是"选集怎么点都没反应，还老弹'正在尝试解析源'"。
        if (leaving) {
            MpvDebug.log("[play] 退场后丢弃解析结果 fl=$fl ep=${ep.take(24)}")
            return
        }
        // 解析要发网络请求（引擎 player / csp 源），这段到 ExoPlayer 第一次状态回调之间
        // 画面层还没有内容（黑底）。原版同样的空档是 progressLayout.showProgress()，
        // 这里先盖上转圈，等 STATE_BUFFERING / READY 回调再按原版时机切换。
        showPlayProgress("resolvePlay")
        var url = ""
        var err = ""
        var sniffTarget = ""
        var sniffClick = ""          // 解析源自带的 click 脚本（优先于站点 click）
        var sitePlayUrl = ""         // 站点级 playUrl；为空 = 本站要靠外部解析源
        var siteJx = 0               // 接口返回 jx=1：明说必须走解析
        var parseOverride: ParseSources.Item? = null   // json: / parse: 前缀指定的解析源
        var headers: Map<String, String> = emptyMap()
        qualities.clear()
        qualityIdx = 0
        runCatching {
            val store = SubscriptionStore(host)
            val site = store.sites().firstOrNull { it.key == siteKey }
            val play = if (site?.isCsp() == true) {
                CspLoader.player(host, site, fl, if (ep.isNotBlank()) ep else vodId)
            } else {
                val params = JSONObject()
                    .put("flag", fl)
                    .put("id", if (ep.isNotBlank()) ep else vodId)
                    .toString()
                Engine.execute("player", siteKey, params)
            }
            val obj = JSONObject(play)
            // 对齐原版 Result.parse：0=直链直接播；1=需二次解析，playUrl 是解析前缀
            // （TVBox 生态里 playUrl 形如 "https://jx.xxx/?url="，拼上 url 才是可播地址）
            val parseFlag = obj.optInt("parse", 0)
            // 对齐原版 gson/UrlAdapter：playerContent 的 url 有 3 种形态
            //   1) 字符串      "http://x.m3u8"
            //   2) 扁平数组    ["1080P","http://a","720P","http://b"]   （每两个一组，n/v 交替）
            //   3) 对象        {"values":[{"n":"1080P","v":"http://a"}],"position":0}
            // 之前只认第 1 种，第 2/3 种会被 optString 变成 JSON 文本直接送进播放器，必然播不出来。
            val urlField = obj.opt("url")
            val urlPairs = mutableListOf<Pair<String, String>>()
            when (urlField) {
                is String -> url = urlField
                is JSONArray -> {
                    var i = 0
                    while (i + 1 < urlField.length()) {
                        val n = urlField.optString(i, "").ifBlank { "清晰度${urlPairs.size + 1}" }
                        urlPairs.add(n to urlField.optString(i + 1, ""))
                        i += 2
                    }
                    url = urlPairs.firstOrNull()?.second.orEmpty()
                }
                is JSONObject -> {
                    val vals = urlField.optJSONArray("values")
                    val pos = urlField.optInt("position", 0)
                    if (vals != null) for (i in 0 until vals.length()) {
                        val o = vals.optJSONObject(i) ?: continue
                        urlPairs.add(o.optString("n").ifBlank { "清晰度${i + 1}" } to o.optString("v"))
                    }
                    url = urlPairs.getOrNull(pos)?.second ?: urlPairs.firstOrNull()?.second.orEmpty()
                }
                else -> url = ""
            }
            // 原版 ParseJob.setParse 的协议前缀识别（点播链路最后一环）：
            //   playUrl 以 "json:" 开头  → Parse.get(1, playUrl.substring(5))，type=1 纯 HTTP 解析
            //   playUrl 以 "parse:" 开头 → 按名字查已配置的解析源
            //   parse=1 且两者都没有 → 嗅探（type=0，需 WebView）
            val playUrlRaw = obj.optString("playUrl", "")
            sitePlayUrl = playUrlRaw
            // 原版 Result.jx：接口明确要求走外部解析（5.6.0 Result.java:332/336 —— needParse = getParse()==1 || getJx()==1）
            try { siteJx = obj.optInt("jx", 0) } catch (_: Exception) { siteJx = 0 }
            when {
                // json: 前缀 → 当 type=1 直接跑（原版 Parse.get(1, url.substring(5))）
                playUrlRaw.startsWith("json:") -> {
                    val jsonUrl = playUrlRaw.substring(5)
                    if (!jsonUrl.startsWith("http")) {
                        err = "json: 前缀后面的地址不合法（${jsonUrl.take(40)}）"
                    } else {
                        parseOverride = ParseSources.Item("json:", 1, jsonUrl)
                        Log.e("XingHuiTV", "[play] json: 前缀 → type1 ${jsonUrl.take(70)}")
                    }
                }
                // parse: 前缀 → 按名字找解析源（原版 VodConfig.get().getParse(name)）
                playUrlRaw.startsWith("parse:") -> {
                    val name = playUrlRaw.substring(6).trim()
                    val item = ParseSources.byName(name)
                    if (item == null) {
                        err = "订阅里没有名为「$name」的解析源"
                    } else {
                        parseOverride = item
                        Log.e("XingHuiTV", "[play] parse: 前缀 → 解析源 $name (type=${item.type})")
                    }
                }
                // playUrl 是普通 http 解析页 → type=0 嗅探：起 WebView 抓真实 m3u8（对齐原版 startWeb）
                parseFlag == 1 && !looksLikeMedia(url) && playUrlRaw.startsWith("http") -> {
                    sniffTarget = playUrlRaw + url
                    Log.e("XingHuiTV", "[play] parse=1 且 playUrl 是网页 → 走嗅探 ${sniffTarget.take(100)}")
                }
                parseFlag == 1 && !looksLikeMedia(url) && playUrlRaw.isNotBlank() -> {
                    Log.e("XingHuiTV", "[play] parse=1 → playUrl 前缀拼接 ${playUrlRaw.take(60)}")
                    url = playUrlRaw + url
                    for (i in urlPairs.indices) urlPairs[i] = urlPairs[i].first to (playUrlRaw + urlPairs[i].second)
                }
                parseFlag == 1 && !looksLikeMedia(url) -> {
                    Log.e("XingHuiTV", "[play] parse=1 但无 playUrl，按直链尝试 ${url.take(80)}")
                }
            }
            if (urlPairs.size > 1) qualities.addAll(urlPairs)
            // Result.header 透传：原版对所有源都取（不区分 csp / js / json），
            // 之前只在 csp 站取，drpy 与 json 站播放接口返回的 UA/Referer 被丢掉了，
            // 表现为「能进详情但播放器一直转圈 / 403」。
            // 合并顺序对齐原版：站点级 header 打底，播放接口返回的 header 覆盖同名项。
            val siteHdr = site?.headerMap().orEmpty()
            val playHdr = CspLoader.playerHeaders(play)
            headers = if (siteHdr.isEmpty()) playHdr else siteHdr + playHdr
            // Result.subs 字幕：原版 Result.getSubs() → Sub.getConfig() → ExoPlayer SubtitleConfiguration
            activeSubs = parseSubs(play)
            // 多清晰度（引擎已把 TVBox `#` 分隔拆成 urls）
            val urls = obj.optJSONArray("urls")
            if (urls != null && urls.length() > 1) {
                for (i in 0 until urls.length()) {
                    val u = urls.optJSONObject(i) ?: continue
                    qualities.add(u.optString("name", "清晰度${i + 1}") to u.optString("v", ""))
                }
            } else if (url.isNotBlank() && qualities.isEmpty()) {
                qualities.add("自动" to url)
            }
            // 迅雷体系：magnet:?xt= / ed2k: / thunder: / magnet:///path?name=&index=（磁力中间态）
            // 对齐原版 Source.getExtractor → Thunder：交给 libxl_thunder_sdk 起下载，
            // 内部会返回 http://127.0.0.1:<port>/... 的本地直链，ExoPlayer 直接可播。
            // 这一步会阻塞（等元信息/等任务启动，上限十几秒），当前在后台线程，安全。
            if (ThunderPlay.match(url)) {
                // 对齐原版 Source.fetch：切到新片前先停掉上一个迅雷任务，
                // deleteTask 会清分片但留 .torrent，重播仍能命中缓存。
                runCatching { ThunderPlay.stop() }
                Log.e("XingHuiTV", "[play] 迅雷体系 → ${url.take(70)}")
                ThunderPlay.ensureInit(host)
                url = ThunderPlay.resolve(url)
                if (qualities.isEmpty()) qualities.add("自动" to url)
            }
            flag = fl
            epUrl = ep
            invalidateHistoryKey()
        }.onFailure { err = it.message ?: "解析失败" }
        // 嗅探分支：url 不是媒体直链、playUrl 是解析页 → 交给 WebView 抓真实地址
        if (sniffTarget.isNotBlank()) {
            runOnUiThread { startSniff(sniffTarget, headers) }
            return
        }
        // json: / parse: 前缀指定的解析源优先，先跑它
        val override = parseOverride
        if (override != null && ep.isNotBlank()) {
            parseList = listOf(override) + ParseSources.all().filter { it.name != override.name }
            parseEpRaw = ep
            parseHdrs = headers
            parseIdx = 0
            runParseItem(override)
            return
        }
        if (url.isBlank() || (err.isNotBlank() && !looksLikeMedia(url))) {
            // 站点没配 playUrl、但订阅里有 type=0 解析源 —— 原版这时会把解析源列表显示出来让用户选
            // （VideoActivity:568：setUseParse(VodConfig.hasParse() && result.getPlayUrl().isEmpty() ...)）。
            // 只在**确实没拿到地址**时才展示，不能因为"站点没配 playUrl"就打断本来能播的直链。
            val epRaw = ep.ifBlank { url }
            // 显示条件照原版：有解析配置 且（站点没给 playUrl 就认为要外部解析 或 接口明说 jx=1）
            if (!ParseSources.isEmpty() && (sitePlayUrl.isBlank() || siteJx == 1) && epRaw.isNotBlank()) {
                bindingParseBar(epRaw, headers)
                return
            }
            // err 非空且 url 不是媒体直链：把"需要解析接口"这类原因直接抛给用户，
            // 而不是硬塞一个网页地址给 ExoPlayer 变成无限转圈
            onPlayFail(if (err.isBlank()) "未解析到播放地址：$fl" else "解析失败[$fl]：$err")
            return
        }
        runOnUiThread {
            // spider 可能返回 proxy://xxx 这类本地 scheme，进播放器前统一重写成 http://127.0.0.1:9978/...
            val jpa = toPlayableUrl(url)
            if (jpa == null) {
                Log.e("XingHuiTV", "[jpa] 起播失败 ${url.take(60)}")
                showPlayError("荐片 P2P 起播失败")   // 原版 onError → showError(msg)，无 Toast
            } else {
                playUrl(jpa, headers)
            }
            updateQualityBtn()
            recordHistory()
        }
    }

    /**
     * 对齐原版 Source.getExtractor 的顺序判断里荐片那一条（`JianPian.match`）：
     * scheme ∈ { tvbox-xg, jianpian, ftp } → 交给 P2P 引擎，转成本地 `http://127.0.0.1:<port>/...`。
     * 其余走原本的 UrlConvert。
     */
    private fun toPlayableUrl(raw: String): String? {
        if (JianPianPlay.match(raw)) {
            val local = JianPianPlay.fetch(host, raw)
            Log.e("XingHuiTV", "[jpa] ${raw.take(50)} → ${local.orEmpty().take(80)}")
            return local
        }
        return UrlConvert.convert(raw)
    }

    private var sniffer: SniffWebView? = null

    // 解析源轮换状态（对齐原版 checkParse：一个失败就试下一个）
    private var parseList: List<ParseSources.Item> = emptyList()
    private var parseIdx = -1
    private var parseEpRaw = ""
    private var parseHdrs: Map<String, String> = emptyMap()

    /**
     * 解析源条（对齐原版 view_control_vod.xml 里 `id=parse` 那一行 + adapter_parse.xml 的 TextView 样式）。
     *
     * 显示条件照 VideoActivity:568：
     *   setUseParse(VodConfig.hasParse() && (result.getPlayUrl().isEmpty() && ...) || result.getJx() == 1)
     */
    private fun bindingParseBar(epRaw: String, hdrs: Map<String, String>) {
        val list = ParseSources.all()
        if (list.isEmpty()) return
        parseList = list
        parseEpRaw = epRaw
        parseHdrs = hdrs
        parseIdx = -1
        runOnUiThread {
            val bar = findViewById<android.widget.HorizontalScrollView>(R.id.parseBar)
            val row = findViewById<android.widget.LinearLayout>(R.id.parseRow)
            row.removeAllViews()
            for (item in list) {
                val tv = android.widget.TextView(host).apply {
                    text = item.name
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    isFocusable = true
                    isClickable = true
                    setBackgroundResource(R.drawable.selector_parse_text)
                    setOnClickListener {
                        ParseSources.current = item
                        parseIdx = list.indexOf(item)
                        runParseItem(item)
                    }
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * resources.displayMetrics.density + 0.5f).toInt() }
                row.addView(tv, lp)
            }
            bar.visibility = android.view.View.VISIBLE
            showPlayError("本站需要解析，请用遥控器选择一个解析源")
        }
    }

    private fun runParseItem(item: ParseSources.Item) {
        Log.e("XingHuiTV", "[play] 解析源 ${item.name} type=${item.type}")
        val mergedHeaders = parseHdrs + item.headers
        when (item.type) {
            0 -> startSniff(item.url + parseEpRaw, mergedHeaders, item.click)
            else -> Thread {
                val hit = ParseJob.run(item, parseEpRaw, flag)
                runOnUiThread {
                    if (leaving) return@runOnUiThread
                    if (hit != null) {
                        playUrl(toPlayableUrl(hit.url) ?: hit.url, mergedHeaders + hit.headers)
                        updateQualityBtn()
                        recordHistory()
                    } else {
                        tryNextParse()
                    }
                }
            }.start()
        }
    }

    private fun tryNextParse() {
        val next = parseIdx + 1
        if (parseIdx < 0 || next >= parseList.size) {
            Log.e("XingHuiTV", "[play] 解析源全部失败（共 ${parseList.size} 个）")
            parseIdx = -1
            onPlayFail("解析源全部失败[$flag]")
            return
        }
        parseIdx = next
        runParseItem(parseList[next])
    }

    /**
     * type=0 嗅探（对齐原版 ParseJob case 0 → CustomWebView）。
     * 打开 解析页地址 = playUrl + 真实地址，从 WebView 的网络请求里截获 m3u8/mp4。
     */
    private fun startSniff(target: String, hdrs: Map<String, String>, parseClick: String = "") {
        sniffer?.cancel()
        sniffer = SniffWebView(
            host,
            onFound = { u, h ->
                runOnUiThread {
                    if (leaving) return@runOnUiThread
                    val merged = if (hdrs.isEmpty()) h else hdrs + h
                    val playable = toPlayableUrl(u) ?: u
                    // 推送进来的网页地址本身不可播：抓到真流后把 vod_id 换成流地址，
                    // 历史里这条记录才能被再次点开回放（否则点回去又是那个网页地址）。
                    if (pushMode) {
                        vodId = playable
                        invalidateHistoryKey()
                    }
                    playUrl(playable, merged)
                    updateQualityBtn()
                    recordHistory()
                }
            },
            onFail = { why ->
                runOnUiThread {
                    // 解析源流程里失败 = 换下一个（对齐原版 checkParse）；普通站点失败才报错
                    if (parseIdx >= 0) tryNextParse() else onPlayFail("嗅探失败[$flag]：$why")
                }
            },
        )
        // 站点级 click 脚本（订阅 site.click，原版 ParseJob.getClick → CustomWebView.start）：有些解析页
        // 的播放按钮不会自动起播，需要注入一次点击。空串时只走 rules 里的 script。
        val curSite = SubscriptionStore(host).sites().firstOrNull { it.key == siteKey }
        // 解析源自带 click 优先（原版 ParseJob.getClick：站点 click 优先，其次是 result/parse 的 click；
        // 但走"外部解析源"这条路时，站点 click 对解析站的页面毫无意义，所以这里反过来）
        val clickScript = parseClick.ifBlank {
            runCatching {
                curSite?.siteJson?.takeIf { it.isNotBlank() }
                    ?.let { org.json.JSONObject(it).optString("click", "") }
                    .orEmpty()
            }.getOrDefault("")
        }
        sniffer?.start(target, hdrs, clickScript)
    }

    private fun looksLikeMedia(u: String): Boolean {
        if (Sniffer.isVideoFormat(u)) return true
        val lower = u.lowercase().substringBefore('?').substringBefore('#')
        return MEDIA_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * 起播/缓冲指示，对齐原版 VideoActivity：
     *   showProgress() -> mBinding.widget.progress.setVisibility(VISIBLE)
     *   hideProgress() -> 同 GONE + 停掉网速刷新
     *
     * 原版这两次调用挂在 onPlayerEvent 的 STATE_BUFFERING / STATE_READY 上，
     * 另外起播前（checkCast 里非投屏时）也先盖一层 progressLayout。
     * 我们照同一套时机：解析开始、首次缓冲、seek 后都显示，就绪/播完/失败时收起。
     */

    /**
     * 播放失败提示：对齐原版 VideoActivity.showError / hideError（5.6.0 :3450 / :3456）。
     *   原版 onError(msg) 顺序：停播放器 → showError(msg) → startFlow()（换源重试），即先亮居中错误块再换源，全程无 Toast。
     *   显隐沿用本页约定（常驻 VISIBLE + alpha），不切 GONE。
     */
    private fun showPlayError(text: String) {
        runOnUiThread {
            findViewById<android.widget.TextView>(R.id.playErrorText)?.text = text
            findViewById<View>(R.id.playError)?.alpha = 1f
        }
    }

    private fun hidePlayError() {
        runOnUiThread {
            findViewById<View>(R.id.playError)?.alpha = 0f
            findViewById<android.widget.TextView>(R.id.playErrorText)?.text = ""
        }
    }

    private fun showPlayProgress(reason: String = "其它") {
        hidePlayError()   // 对齐原版 showProgress() 里的 hideError()：新一次起播先清掉上一次的错误
        // 转圈是谁点亮的 —— 这是区分「EXO 真的重新缓冲」与「我们自己误亮」的唯一依据
        MpvDebug.log(
            "[prog] 转圈 显示 来源=$reason state=${runCatching { player?.playbackState }.getOrNull()}" +
                " playing=${runCatching { player?.isPlaying }.getOrNull()} pos=${runCatching { player?.currentPosition }.getOrNull()}" +
                " surfGen=$surfGen"
        )
        runOnUiThread {
            // 常驻 VISIBLE + alpha 显隐。转圈层原本是 GONE→VISIBLE，是第二个会改绘制区域的
            // 覆盖层。三层（控制条 / 转圈 / 暂停图标）统一后，绘制区域从第一帧起固定，
            // 之后任何显隐都只是重绘。
            val v = findViewById<View>(R.id.playProgress)
            v?.alpha = 1f
        }
    }

    private fun hidePlayProgress() {
        // 同上：只降 alpha，不动 visibility（不动窗口绘制区域）
        runOnUiThread { findViewById<View>(R.id.playProgress)?.alpha = 0f }
    }

    /**
     * 是否还在"起播前"（画面没出来）。
     *
     * 判据只有一条：内核里有没有内容（contentReady()）。
     * 原版对应 VideoActivity.onKeyCenter:6280 的 player().isEmpty() 分支：
     * 空 → onRefresh()（重新起播）；非空 → 暂停/播放。
     */
    private fun preStart(): Boolean = !contentReady()

    private fun onPlayFail(msg: String) {
        // 退场后失败重试必须停：这条链每次都会起新线程解析，退出播放页后它还在跑，
        //   换线路 → 换站点 → 改 vodId/siteKey → 弹 Toast，全都会砸在详情页上。
        if (leaving) {
            MpvDebug.log("[play] 退场后忽略失败重试：${msg.take(48)}")
            return
        }
        hidePlayProgress()
        if (tryAutoSwitch()) return
        if (siteSwitchCount < MAX_SITE_SWITCH && autoSwitchSite()) return
        showPlayError(msg)   // 原版 onError → showError(msg)
    }

    private fun tryAutoSwitch(): Boolean {
        if (leaving) return false
        val next = nextLineIndex()
        if (next == null) return false
        val line = vodLines[next]
        Thread { resolvePlay(line.name, line.episodes.firstOrNull()?.second ?: vodId) }.start()
        return true
    }

    private fun nextLineIndex(): Int? {
        if (vodLines.isEmpty()) return null
        val cur = vodLines.indexOfFirst { it.name == flag }
        val start = if (cur < 0) 0 else cur + 1
        return if (start < vodLines.size) start else null
    }

    private fun autoSwitchSite(): Boolean {
        if (leaving) return false
        if (vodName.isBlank()) return false
        siteSwitchCount++
        val target = runCatching {
            val params = JSONObject().put("key", vodName).put("keepAll", true).toString()
            val json = Engine.execute("searchAll", "", params)
            Vod.parseList(json).firstOrNull {
                it.source.isNotBlank() && it.source != siteKey && it.vodName == vodName
            }
        }.getOrNull()
        if (target == null) {
            siteSwitchCount--
            return false
        }
        vodId = target.vodId
        invalidateHistoryKey()
        siteKey = target.source
        Thread { fetchAndPlay(target.vodId, "", "") }.start()
        return true
    }

    private fun toggleQuality() {
        if (qualities.size < 2) return
        if (isMpvMode()) {
            return
        }
        // 切档只换播放地址，但 headers 是在播放器构建时绑到数据源上的：
        //   DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        // 直接 setMediaItem(fromUri(v)) 会绕过 headers 比较与字幕挂载，
        // 换到不同 CDN 时新地址仍沿用旧数据源（或干脆没有 UA/Referer），
        // 部分源表现为一直转圈或 403。这里补齐对齐，并保持当前进度不跳回续播点。
        val pos = player?.currentPosition ?: 0L
        val wasPlaying = player?.playWhenReady ?: true
        qualityIdx = (qualityIdx + 1) % qualities.size
        val v = qualities[qualityIdx].second
        updateQualityBtn()
        val want = withUa(activeHeaders)
        if (want != activeHeaders) rebuildPlayer(want)
        val b = MediaItem.Builder().setUri(v).setMediaId(v)
        if (activeSubs.isNotEmpty()) b.setSubtitleConfigurations(activeSubs)
        if (applyMediaSource(b)) {
            player?.apply {
                if (pos > 0) seekTo(pos)
                playWhenReady = wasPlaying
            }
        }
    }

    /**
     * 清晰度按钮：**恒隐藏**。
     *
     * 原版控制条里没有这枚按钮 —— 它的清晰度网格 `@id/quality` 在详情页的面板里（activity_video.xml），
     * 与「线路 flag 行」并列。这里保留 id 与切换逻辑，只为将来把入口挪到详情页时不用重建按钮；
     * 控制条上不再出现它，观感与原版一致。
     */
    private fun updateQualityBtn() {
        val btn = findViewById<TextView>(R.id.btnQuality)
        btn.visibility = View.GONE
        if (qualities.size > 1) btn.text = "清晰度 ${qualities[qualityIdx].first}"
    }

    private var activeHeaders: Map<String, String> = emptyMap()

    private var activeSubs: List<MediaItem.SubtitleConfiguration> = emptyList()

    private var pendingSeek: Long = 0

/**
     */

    private var lastSeekCommitAt = 0L

    /**
     * 本次起播将要落到的那一点（续播点）。起播前按快进时用它当基准。
     *
     * 用例：片子的续播点在 1700s，用户刚进页面就按 →。
     * 若基准取 currentPosition（未起播恒为 0），目标就成了 10s，
     * 同时 playUrl 里"用户意图优先"会把续播点清零 —— 一按快进就回片头。
     */
    private var startPosMs: Long = 0

    private var lastSavedPos: Long = 0

    /**
     * 同集重新起播的位置纪律。
     *   现场现象：播到 3 分钟退出 → 再进播到 1 分钟（合计 4 分钟）→ 第一次按菜单跳回 3 分钟。
     *   这个 3 分钟恰好是**上一次会话退出时落盘的续播点**。只要本次会话里再发生一次 playUrl
     *   （换内核、重建播放器、任何重建路径），旧的 at 计算会回头读磁盘上的 marks，画面被拽回旧点。
     *   正确语义：同一地址在本会话内再次起播，起点用**当前真实位置**；只有地址变了（换集/换源）才读历史。
     */
    private var lastPlayedUrl = ""

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "xh-history-save").apply { isDaemon = true }
        }
    private val saveRunnable = object : Runnable {
        override fun run() {
            savePosition(reason = "周期")
            saveHandler.postDelayed(this, 15_000)
        }
    }

    private var playerListener: Player.Listener? = null
    private var playerListenerHost: ExoPlayer? = null

    /**
     * 挂播放器监听（错误、缓冲、就绪续播、暂停落盘）。
     *
     * 幂等：先摘掉上一份再挂新的。
     *   而调用点有两个（首帧建实例、rebuildPlayerInternal），所以「切内核 / 换源重建」
     *   每做一次就多一份监听：错误会上报多次（连弹好几个失败提示、自动换源被触发多次），
     *   暂停落盘也会重复写。现在无论调用多少次都只保留一份。
     */
    private fun attachPlayerListeners() {
        playerListener?.let { l -> playerListenerHost?.let { h -> runCatching { h.removeListener(l) } } }
        playerListener = null
        playerListenerHost = null
        val p = player ?: return
        // 字幕 cue 由 PlaybackSession 统一转发给 SubtitleView（见 session.cueSink）
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (directUrl.isNotBlank()) return // 直播直链不自动换线路
                onPlayFail("播放出错：${error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(state: Int) {
                // 对齐原版 VideoActivity.onPlayerEvent：
                //   STATE_BUFFERING -> showProgress()
                //   STATE_READY     -> hideProgress()
                // 所以画面层没内容时看起来就是纯黑。
                when (state) {
                    Player.STATE_BUFFERING -> showPlayProgress("BUFFERING回调")
                    Player.STATE_READY, Player.STATE_ENDED -> hidePlayProgress()
                    else -> Unit
                }
                if (state == Player.STATE_ENDED) {
                    handleEnded()
                    return
                }
                if (state != Player.STATE_READY) return

                val p = player ?: return
                uiHandler.post { if (barShown()) ensureBarFocus() }
                uiHandler.post {
                    if (barShown()) {
                        uiHandler.removeCallbacks(autoHide)
                        uiHandler.postDelayed(autoHide, IMM_HIDE_MS)
                    }
                }
                if (pendingSeek <= 0) return
                val target = pendingSeek
                pendingSeek = 0
                if (p.duration > 0 && target < p.duration - 5_000) p.seekTo(target)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (seekingByKey) return
                if (!isPlaying && lastSeekCommitAt > 0L &&
                    android.os.SystemClock.uptimeMillis() - lastSeekCommitAt < 1_500L
                ) {
                    MpvDebug.log("[play] seek 引起的瞬时暂停，不落盘")
                    return
                }
                if (isPlaying) {
                    lastSeekCommitAt.takeIf { it > 0L }?.let { t ->
                        val gap = android.os.SystemClock.uptimeMillis() - t
                        if (gap < 5_000) {
                            MpvDebug.log("[play] seek 后恢复播放 停顿=${gap}ms")
                            lastSeekCommitAt = 0L
                        }
                    }
                    scheduleSave()
                } else savePosition(reason = "暂停")
            }
        }
        playerListener = l
        playerListenerHost = p
        p.addListener(l)
    }

    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 15_000)
    }

    /**
     * 退出/切后台时落盘进度。
 */
    private fun savePosition(force: Boolean = false, reason: String = "自动") {
        val pos = if (isMpvMode()) mpvPos else runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        if (pos <= 0) return
        if (!force && pos == lastSavedPos) return
        val dur = if (isMpvMode()) mpvDur else runCatching { player?.duration ?: 0L }.getOrDefault(0L)
        if (dur > 0 && pos >= dur - 10_000) return
        if (Settings(host).isIncognito) return
        if (historyKey.isBlank()) {
            MpvDebug.log("[hist] 保存[$reason]跳过：historyKey 为空 pos=$pos")
            return
        }
        val key = historyKey
        val name = vodName
        val pic = vodPic
        val fl = flag
        val ep = epUrl
        if (force) {
            // 必须先把排队的后台写排空再同步写，否则顺序会反过来：
            //   后台那条带着更早的 pos（真机 22:41:06 bg pos=416924），退场同步写 417876，
            //   若后台任务晚一点才跑，就会把退场写好的准确位置**覆盖回旧值**。
            //   表现是"下次进来从小一点的位置续播"，而且很难复现（取决于线程调度）。
            runCatching { saveExecutor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            HistoryStore(host).addHistory(key, name, pic, "", fl, ep)
            HistoryStore(host).setPosition(key, ep, pos)
        } else {
            // executor 在 onDestroy 已 shutdown 后 execute 会抛 RejectedExecutionException，
            // 这里包住：宁可这一次后台写丢掉（退场那条是同步写，不会丢），也不要在 onPause 崩。
            runCatching {
                saveExecutor.execute {
                    runCatching {
                        HistoryStore(host).addHistory(key, name, pic, "", fl, ep)
                        HistoryStore(host).setPosition(key, ep, pos)
                    }
                }
            }
        }
        lastSavedPos = pos
        MpvDebug.log(
            "[hist] 保存[$reason] pos=$pos key=${historyKey.take(24)} " +
                (if (force) HistoryStore(host).probe(historyKey, epUrl) else "period=true") +
                (if (force) " sync=true" else " bg=true")
        )
    }

    /**
     * 对齐原版 Players.java：headers 没带 UA 时补上用户设置的 UA。
     * 注释见调用点——media3 默认 UA 是 "ExoPlayerLib/1.3.1"，多数采集站/CDN 见它直接 403。
     * 抽成函数是为了让切档路径（toggleQuality）复用同一份判定，而不是各自写一遍。
     */
    private fun withUa(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", true) }) headers
        else Settings(host).ua.takeIf { it.isNotBlank() }?.let { headers + ("User-Agent" to it) } ?: headers

    private fun playUrl(url: String, headers: Map<String, String> = emptyMap()) {
        if (url.isBlank()) {
            showPlayError("未解析到播放地址")
            return
        }
        // 对齐原版 Players.java：
        //   for (Map.Entry<String, String> header : headers.entrySet())
        //       if (HttpHeaders.USER_AGENT.equalsIgnoreCase(header.getKey())) return headers;
        //   headers.put(HttpHeaders.USER_AGENT, Setting.getUa());
        // 播放接口通常只返回 Referer，没有 UA。media3 默认 UA 是 "ExoPlayerLib/1.3.1"，
        // 多数采集站/CDN 见到这个标识会直接 403，表现为"能进详情、播放器一直转圈"。
        // 原版走 OkHttpDataSource，继承的是用户设置的 UA，所以不会踩这个坑。
        val effHeaders = withUa(headers)
        activeUrl = url
        // 回写宿主：播放页里切集（上一集/下一集、播完自动下一集）必须让详情页知道现在播的是哪一集。
        //   不回写的后果是一整条错位链：
        //     · exitPlayLayer 里 scrollToPosition(playingIdx) 与焦点恢复都把用户带回「进入时那一集」
        //     · 详情页高亮永远是旧集
        //     · 再点真正的当前集时 wasPlayingThis 判 false → 又老老实实重新加载一遍
        //   这就是「返回详情页后选集对不上/再进要重载」的残余根因。
        runCatching { host.onEpisodeChanged(epUrl, url) }
        logVideoCodecOnce()
        // 片头/片尾按片记忆（对齐原版 History.getOpening/getEnding）
        val marks = HistoryStore(host).marks(historyKey, epUrl)
        openingMs = marks[0]
        endingMs = marks[1]
        endingDone = false
        updateOpeningText()
        updateEndingText()
        val sameEpisodeAgain = url == lastPlayedUrl
        val atSrc = when {
            resumeOverride >= 0 -> "强制"
            sameEpisodeAgain -> "续接当前"
            else -> "历史"
        }
        val at = when {
            resumeOverride >= 0 -> resumeOverride
            // 同集再次起播：用当前真实位置，绝不回头读磁盘上的旧续播点
            sameEpisodeAgain -> currentPosMs()
            else -> maxOf(openingMs, marks[2])
        }
        lastPlayedUrl = url
        val atFrom = runCatching {
            Throwable().stackTrace.getOrNull(2)?.let { "${it.fileName}:${it.lineNumber}" }
        }.getOrNull()
        val atArg = when {
            resumeOverride >= 0 -> "resumeOverride=$resumeOverride"
            else -> "hist=${marks[2]}"
        }
        resumeOverride = -1
        pendingSeek = at
        startPosMs = at
        lastSavedPos = 0
        MpvDebug.log(
            "[play] 起播 来源=$atSrc($atArg) 调用=$atFrom url=${url.take(48)} 续播点=${at}ms " +
                "(hist=${marks[2]} opening=${openingMs} ending=${endingMs}) " +
                HistoryStore(host).probe(historyKey, epUrl)
        )
        // mpv 内核：本进程内 libmpv（内嵌），播放页控制条直接控它
        if (isMpvMode()) {
            startMpv(url, effHeaders, at)
            return
        }
        // 只有"还没有播放器实例"才建。headers 差异不再触发重建 ——
        //   它现在只作用于本次起播的 MediaSource（见 buildSourceFactory 的注释）。
        //   重建播放器 = 摘画面 + 重建输出面，是"进页面黑一下 / 卡一下 / 残留上一帧"的来源。
        if (player == null) {
            rebuildPlayer(effHeaders)
        }
        activeHeaders = effHeaders
        val itemBuilder = MediaItem.Builder().setUri(url).setMediaId(url)
        // 字幕轨：对齐原版 ExoUtil.getMediaItem → setSubtitleConfigurations(getSubtitleConfigs(subs))
        if (activeSubs.isNotEmpty()) itemBuilder.setSubtitleConfigurations(activeSubs)
        // 原版起播即播（onRefresh → getPlayer），没有"起播前按暂停"这条通道
        val wantPlay = true
        val item = itemBuilder.build()
        // 优先走 MediaSource 路径：headers 跟着这一条媒体项走，播放器实例不动。
        //   失败（例如工厂构造异常）才退回 session 的 setMediaItem 路径。
        val src = runCatching { buildSourceFactory(effHeaders).createMediaSource(item) }.getOrNull()
        val ok = if (src != null) session.playExoSource(src, at, wantPlay)
                 else session.playExo(item, at, wantPlay)
        if (ok) {
            setKeepScreenOn(wantPlay)
            if (!wantPlay) showPlayCenter()
        } else {
            showPlayError("该片源当前内核不支持")
        }
    }

    /**
     * 解析播放接口返回的 subs（对齐原版 Result.subs / Sub.getConfig）：
     * 缺 format 时按扩展名推断 MIME（原版 ExoUtil.getMimeType 同一套规则）。
     */
    private fun parseSubs(playJson: String): List<MediaItem.SubtitleConfiguration> {
        if (playJson.isBlank()) return emptyList()
        val out = ArrayList<MediaItem.SubtitleConfiguration>()
        runCatching {
            val arr = JSONObject(playJson).optJSONArray("subs") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val raw = o.optString("url")
                if (raw.isBlank()) continue
                // 与原版一致：subs 里的 url 也过一遍 UrlConvert（可能带 @Referer= 或 proxy:// 前缀）
                val src = UrlConvert.convert(raw)
                val clean = src.substringBefore("@")
                val name = o.optString("name").ifBlank { clean.substringAfterLast('/') }
                val lang = o.optString("lang")
                val mime = o.optString("format").ifBlank { mimeOfSub(clean) }
                out.add(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(clean))
                        .setLabel(name)
                        .setMimeType(mime)
                        .apply { if (lang.isNotBlank()) setLanguage(lang) }
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        }.onFailure { Log.e("XingHuiTV", "[subs] 解析字幕失败", it) }
        if (out.isNotEmpty()) Log.e("XingHuiTV", "[subs] 装配 ${out.size} 条字幕轨")
        return out
    }

    private fun mimeOfSub(url: String): String = when {
        url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
        url.endsWith(".ass", true) || url.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
        url.endsWith(".ttml", true) || url.endsWith(".xml", true) || url.endsWith(".dfxp", true) -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /**
     * 安全地设置播放源。
     * ExoPlayer 的 DefaultMediaSourceFactory 对 .m3u8/.mpd/.ism 需要对应的扩展模块，
     * 缺模块时会抛 IllegalStateException("No suitable media source factory found for content type: N")，
     * 未捕获就直接闪退。这里统一吞掉并提示，避免整页崩掉。
     */
    private fun applyMediaSource(b: MediaItem.Builder): Boolean {
        val p = player ?: return false
        return runCatching {
            p.setMediaItem(b.build())
            p.prepare()
            true
        }.getOrElse { e ->
            Log.e("XingHuiTV", "[play] 播放源不被支持 ${b.build().localConfiguration?.uri}", e)
            runOnUiThread {
                runCatching { showPlayError("该片源当前内核不支持（${e.javaClass.simpleName}）") }
            }
            false
        }
    }

    private fun rebuildPlayer(headers: Map<String, String>) {
        activeHeaders = headers
        session.ensureExo(headers, forceRebuild = true)
        player = session.player
    }

    private fun rebuildPlayerInternal(headers: Map<String, String>): ExoPlayer? {
        activeHeaders = headers
        val existing = host.player
        if (existing != null) {
            MpvDebug.log("[player] 复用宿主那一颗（headers 已下沉到 MediaSource），不新建")
            // 复用同样要接管：绑定画面层、挂监听、套字幕样式。
            //   原版全程只有一颗 player（mBinding.video 里那一个），小窗→全屏只改容器尺寸。
            player = existing
            session.adoptExo(existing)
            applyCaptionStyle()
            attachPlayerListeners()
            return existing
        }
        val builder = ExoPlayer.Builder(host)
        builder.setRenderersFactory(buildRenderersFactory())
        builder.setTrackSelector(buildTrackSelector())
        builder.setLoadControl(buildLoadControl())
        // 工厂统一由 buildSourceFactory(headers) 提供（见下），这里不再重复构造。
        // 解析器参数对齐原版 ExoUtil 的 getExtractorsFactory（原版 MediaSourceFactory.java 同一行）：
        //   FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS —— TS 里 HDMV 封装的 DTS 音轨默认会被丢弃，
        //     这类片源表现为「有画面没声音」，加上才认；
        //   时间戳搜索范围 ×3 —— TS 流 PTS 混乱/首包不是 PAT 时，搜索窗口太窄会起播失败或首帧错乱，
        //     原版把 DEFAULT_TIMESTAMP_SEARCH_BYTES 放宽到三倍。
        // 不设这两项时都是 media3 默认值，跟原版不一致。
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
                    .FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
            )
            .setTsExtractorTimestampSearchBytes(
                androidx.media3.extractor.ts.TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3
            )
        builder.setMediaSourceFactory(buildSourceFactory(headers, extractors))
        val built = builder.build().also {
            // 官方 Players.java setHandleAudioBecomingNoisy(true)：
            // HDMI / 蓝牙音频突然断开时自动暂停，而不是继续无声播放。
            it.setHandleAudioBecomingNoisy(true)
            // 同 onCreate：音频焦点必须请求（原版 Players.java 同一句）
            it.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            it.setPlaybackSpeed(speedSteps[speedIdx])
        }
        player = built
        // 回写宿主：详情页预览与播放页共用这一颗（原版 PlayerManager 单例语义）。
        //   不回写的话，宿主 next 次还会自己 buildInlinePlayer() 再建一颗。
        host.player = built
        session.adoptExo(built)
        // 玩家表面探针必须在绑定之后挂：media3 会在这时把 surface 交给播放器
        applyCaptionStyle()
        attachPlayerListeners()
        return built
    }

    /**
     * 数据源/媒体源工厂 —— **请求头属于"这一条媒体项"，不属于"播放器实例"**。
     *
     *   现在 headers 只影响本函数返回的工厂，player 一次都不用重建。
     */
    private fun buildSourceFactory(
        headers: Map<String, String> = emptyMap(),
        extractors: androidx.media3.extractor.ExtractorsFactory = defaultExtractors(),
    ): DefaultMediaSourceFactory {
        val timeoutMs = Settings(host).networkTimeoutSec.coerceIn(5, 120) * 1000
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(timeoutMs)
            .setReadTimeoutMs(timeoutMs)
            // 允许 302 跳到另一个协议。默认关着时 http→https 这种重定向会直接抛
            // HttpDataSourceException，表现为某些源「起播就报错」而别的源正常。
            .setAllowCrossProtocolRedirects(true)
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)
        // 对齐原版 MediaSourceFactory:90：原版数据源是 DefaultDataSource.Factory(http)，
        // 除 http 外还认 file:// / content:// / asset:// / data://。
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(host, httpFactory)
        return DefaultMediaSourceFactory(dataSource, extractors)
    }

    private fun defaultExtractors(): androidx.media3.extractor.ExtractorsFactory =
        androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
                    .FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
            )
            .setTsExtractorTimestampSearchBytes(
                androidx.media3.extractor.ts.TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3
            )

    /**
     * 渲染器工厂。
     *   1. 解码方式：0=软解（换成过滤硬件 codec 的选择器），1=硬解（默认）—— 对齐原版 Setting.getDecode
     *   2. setEnableDecoderFallback(true) —— 原版 NextRenderersFactory 构造里就开着这一条。
     *      硬解的 MediaCodec 初始化失败时（T1 上某些编码会），它会自动退到下一个候选解码器，
     *      而不是直接抛 ERROR_CODE_DECODER_INIT_FAILED 把整条播放打断。
     *      只开这个开关不引入任何 so，纯兜底。
     */
    private fun buildRenderersFactory(): androidx.media3.exoplayer.RenderersFactory {
        val factory = if (Settings(host).decodeMode == 0)
            SoftRenderersFactory(host)
        else
            androidx.media3.exoplayer.DefaultRenderersFactory(host)
        return factory.setEnableDecoderFallback(true)
    }

    /**
     * 缓冲策略。设置页「缓冲时间」（原版 exo_buffer，1~15 秒）真正落地的地方。
     * 原版这个值存了没人读；不设 LoadControl 时 ExoPlayer 用的是
     * min=13MB / 起播=2.5s / 重缓冲=5s / 上限=50s 的默认档。
     * 这里按秒数换算：起播前攒 N 秒，卡顿重缓冲后等 2N 秒，上限仍保持 50 秒。
     */
    /**
     * 签名是 setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)。
     * media3 有硬校验：minBufferMs 必须 >= bufferForPlaybackMs，也必须 >= bufferForPlaybackAfterRebufferMs，
     * 否则构造时直接抛 IllegalArgumentException，Activity 起不来。
     * 之前写成 (ms, ms, ms*2, 50000)：起播门槛和重缓冲门槛都比 min 大，必崩。
     */
    private fun buildLoadControl(): androidx.media3.exoplayer.LoadControl {
        return androidx.media3.exoplayer.DefaultLoadControl()
    }

    /**
     * 照原版 ExoUtil.buildTrackSelector() 的三个参数：
     *   1. setPreferredTextLanguage —— 字幕按系统语言优先
     *   2. setForceHighestSupportedBitrate(true) —— 自适应流里强制拿最高码率。
     *      不设这项时 ExoPlayer 会按带宽估计算着挑档，电视上看到的就是「糊」。
     *      原版是开着的，这是画质和原版不一致的一个隐藏原因。
     *   3. setTunnelingEnabled —— 隧道模式（设置页那一项真正落地的地方）
     */
    private fun buildTrackSelector(): androidx.media3.exoplayer.trackselection.TrackSelector {
        val ts = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(host)
        ts.setParameters(
            ts.buildUponParameters()
                .setPreferredTextLanguage(java.util.Locale.getDefault().getISO3Language())
                .setForceHighestSupportedBitrate(true)
                .setTunnelingEnabled(Settings(host).tunnelMode == 1)
        )
        return ts
    }

    private fun recordHistory() {
        // 无痕模式：不写历史（对齐原版 Setting.isIncognito 的语义）
        if (Settings(host).isIncognito) return
        HistoryStore(host).addHistory(historyKey, vodName, vodPic, "", flag, epUrl)
    }

    /**
     * 原版 control.action.speed 是 CustomUpDownView：↑ → onSpeedAdd()、↓ → onSpeedSub()，
     * 每次 ±0.25f（5.6.0 VideoActivity:3240/3246）。点击仍走 toggleSpeed()（原版长按 toggleSpeed）。
     * 我们的档位表是 0.5~2.0 六档、步长恰好 0.25，所以在档位间移动即可，行为与原版一致。
     */
    private fun adjustSpeed(dir: Int) {
        val idx = (speedIdx + dir).coerceIn(0, speedSteps.size - 1)
        if (idx == speedIdx) return
        speedIdx = idx
        val sp = speedSteps[idx]
        if (isMpvMode()) runCatching { mpv?.setSpeed(sp) }
        else runCatching { player?.setPlaybackSpeed(sp) }
        findViewById<TextView>(R.id.btnSpeed).text = String.format(java.util.Locale.US, "%.2f", sp)
        MpvDebug.log("[bar] 速度 ↑↓ → ${sp}")
    }

    private fun toggleSpeed() {
        speedIdx = (speedIdx + 1) % speedSteps.size
        applySpeed()
    }

    private fun toggleScale() {
        scaleIdx = (scaleIdx + 1) % scaleModes.size
        if (isMpvMode()) mpv?.applyScale(scaleIdx) else videoHost?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale).text = scaleNames[scaleIdx]
        // 对齐原版 VideoActivity.onScale：档位写进本片 History，不动全局 Setting
        if (!HistoryStore(host).setScaleOf(historyKey, scaleIdx)) Settings(host).playerScale = scaleIdx
    }

    // ===================== 内嵌 mpv 内核（libmpv + TextureView） =====================

    private var mpv: MpvPlayer? = null
    private var mpvPos = 0L
    /**
     * 全片时长唯一入口（毫秒，未知返回 0）。
     * mpv 侧读 mpvDur，取不到时回落内核 duration 属性；EXO 侧读 player.duration，
     * TIME_UNSET 与负数一律折算成 0。散在各处取值会让漏掉 isMpvMode() 的地方拿 0 去算。
     */
    private fun durNow(): Long = session.durationMs()

    private var mpvDur = 0L
    private var mpvLoaded = false
    private var mpvFallbackExo = false

    /**
     * 当前这块 ANativeWindow（画面层）的生产者是谁 —— "exo" / "mpv" / "none"。
     *
     * 为什么必须显式记账：EXO 走 MediaCodec 直接往 Surface 推帧，mpv 走自己的 EGL 往同一个
     * Surface 推帧，两者共用**同一个 BufferQueue**，而 BufferQueue 同一时刻只允许一个生产者。
     * 两个都还连在上面时不是"哪个先画"的问题，是后连的那个 connect 失败 → 黑面 / 解码器报错。
     * 所以每次内核切换都必须是一次有先有后的让渡，并且能在 /debug 里看到谁握着。
     */
    private var surfaceOwner = "none"

    private var leaving = false

    private fun handOver(to: String, how: String) {
        val from = surfaceOwner
        surfaceOwner = to
        MpvDebug.log("[surf] 画面层让渡 $from → $to（$how）")
    }

    /**
     * 字幕样式 —— 原版 ExoUtil.applySubtitleStyle() 的等价实现。
     *   预设（默认）：白字 + 黑描边 + 透明底
     *   系统：跟随系统「字幕样式」设置（CaptioningManager.getUserStyle()）
     * 原版同时设了 setApplyEmbeddedStyles(!isCaption())：选「预设」时用 ASS 里自带样式，
     * 选「系统」时用系统样式覆盖内嵌样式。
     */
    /**
     * 委托给宿主那一份实现。
     *   （MainActivity:380 取到的 subtitleView 就是 VideoHost 构造时传进去的那一个），
     *   代码双写意味着以后改一处漏一处，字幕样式就会在小窗和全屏两个形态下变得不一致。
     */
    private fun applyCaptionStyle() = host.applyCaptionStyle()

    /**
     * 画面层唯一的宿主：EXO 与 mpv 共用。
     *
     * 可能是 SurfaceView（surface_type=surface_view，window 上挖洞）或
     * TextureView（surface_type=texture_view，就是普通视图内容）。
     * 后者不挖洞，控制条显隐/窗口重绘都不会牵动画面层那一帧，这是纯官方能力。
     */
    private fun currentSurface(): android.view.View? =
        videoHost?.surfaceView

    /**
     * 本页保留此函数只为不打断调用点。
     *
     * keepContentOnPlayerReset 是 PlayerView 的接口；布局里已经没有 PlayerView
     * （画面层是 AspectRatioFrameLayout + 一块 SurfaceView），这个开关没有作用对象，
     * 所以函数体为空。起播前的遮挡由 playProgress 承担。
     */
    private fun enableKeepContent() {
        runCatching { Unit }
    }

    private fun isMpvMode(): Boolean =
        !mpvFallbackExo && PlayerManager.shouldUseMpv(host) &&
            android.os.Build.VERSION.SDK_INT >= 23   // 官方 libmpv 构建要求 API 23+

    /**
     * 起播前按键意图（两个内核共用）。原版按键先记在界面层再落到内核
     * （onSeekTo → mPlayers.seekTo + showProgress + onPlay），即"意图先成立、内核就绪再执行"。
     * 直接打 player 时，起播前那几秒既无内核也无媒体项，按键会全部打空。
     *
     * resumeOverride：换内核 / 重建播放器后强制回到此位置，出处 5.6.0 PlayerManager
     * pendingSwitchPositionMs。暂停/快进意图原版无对应机制，由 service() != null 挡掉。
     */


    private fun contentReady(): Boolean = session.hasContent()

    /**
     * 建（或复用）mpv 原生实例。
     *
     * 之前实例只在 startMpv 里建 —— 也就是「地址解析完、文件开始加载」时才存在。
     * 于是刚进播放页那几秒，`mpv` 还是 null，togglePlayPause / seekByKey 全部
     * `mpv ?: return` 直接返回：按暂停没反应、按快进没反应，要等起播了才「突然好用」。
     * 现在进页面就先建好实例（还没文件，pause 属性照样能设、能保持），
     * startMpv 只负责 play(url)，按键从第一秒起就有效。
     */
    private fun ensureMpv(surface: android.view.View): MpvPlayer {
        mpv?.let { old ->
            // 复用旧实例：重新把画面交出去（releaseOwner 时已经松过手）
            videoHost?.let { vh -> runCatching { vh.setPlayer(old) } }
            return old
        }
        val p = MpvPlayer(host, surface).also { p ->
            p.onLoaded = {
                mpvLoaded = true
                uiHandler.post {
                    if (barShown()) {
                        uiHandler.removeCallbacks(autoHide)
                        uiHandler.postDelayed(autoHide, IMM_HIDE_MS)
                    }
                }
                Log.i("XingHuiTV", "[mpv] 已加载 ${activeUrl.take(60)}")
                hidePlayProgress()
                recordHistory()
                // 起播这一刻焦点最容易掉回根布局：控制条若还开着，立刻把落点拉回条内，
                // 否则用户下一按方向键就是"空打一发"。
                uiHandler.post { if (barShown()) ensureBarFocus() }
            }
            p.onEndFile = { handleEnded() }
            p.onPauseChanged = { paused -> setKeepScreenOn(!paused) }
            p.onFailed = { why ->
                Log.e("XingHuiTV", "[mpv] 播放失败：$why")
                onPlayFail("$why（$flag）")
            }
            p.onTimePos = { mpvPos = it }
            p.onDuration = { mpvDur = it }
        }
        mpv = p
        videoHost?.let { vh -> runCatching { vh.setPlayer(p) } }
        return p
    }

    private var codecLogged = false
    /**
     * 把"这台机器实际用的视频解码器名"打出来。
     *
     * media3 的 MediaCodecVideoRenderer 里有一张按 codec 名匹配的黑名单
     * `codecNeedsSetOutputSurfaceWorkaround(name)`：命中就走"重建解码器"而不是
     * 无缝 setOutputSurface。Surface 一旦重建，命中与不命中的差别就是
     * "毫无察觉" 与 "从零重新起解码、黑一秒"。
     * 我们无法读到它内部的那个布尔，但可以把 codec 名打出来，按官方那张表就能判定。
     */
    private fun logVideoCodecOnce() {
        if (codecLogged) return
        codecLogged = true
        runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val dec = list.codecInfos.firstOrNull { ci ->
                !ci.isEncoder && ci.supportedTypes.any { it.equals("video/avc", true) }
            }
            MpvDebug.log(
                "[codec] avc=${dec?.name} hw=${android.os.Build.HARDWARE} " +
                    "model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}"
            )
        }.onFailure { MpvDebug.log("[codec] 读取失败 $it") }
    }

    private fun startMpv(url: String, headers: Map<String, String>, startMs: Long) {
        // EXO 若还在跑，会和 mpv 同时出声，所以切到 mpv 前必须把它收掉。
        // 用 release 而不是 stop：stop 只把状态推到 IDLE，MediaCodec / Surface 还挂着。
        // 顺序：先把 EXO 与它那块面解绑，再释放播放器；反过来 release 期间 media3
        // 还会去摸一块马上要交给 mpv 的 surface。
        session.releaseExo()
        player = null
        handOver("mpv", "releaseExo 已解绑并释放，下一步 setPlayer 给 mpv")
        // 换内核照 5.6.0 attachSurface 的顺序：先 setPlayer(null) 松手，
        // 再由 ensureMpv 里 setPlayer(mpv) 接管同一块面。
        // 原版 PlayerManager 那句"不同 player 需要新 Surface"针对的是 PlayerView 的
        // render 模式切换（setRender 会重建内部 surface，因此会黑一下），
        // 我们这里没有 setRender —— 面不变，只是换个内核往上输出。
        runCatching {
            videoHost?.releaseOwner()
            MpvDebug.log("[kernel] 换到 mpv：已 setPlayer(null) 松手")
        }
        val surface = currentSurface()
        // 画面层拿不到就本页退回 EXO（不能静默不播）
        if (surface == null) {
            MpvDebug.log("[mpv] 拿不到画面层，本页退回 EXO")
            mpvFallbackExo = true
            if (player == null) rebuildPlayer(headers)
            resumeOverride = startMs
            playUrl(url, headers)
            return
        }
        mpvLoaded = false
        // mpv 没有 ExoPlayer 的 STATE_BUFFERING/READY 回调，等价时机是 onLoaded（FILE_LOADED）。
        // 这里先盖上转圈，onLoaded 里再收起，行为与原版 progress 一致。
        showPlayProgress()
        val m = ensureMpv(surface)
        m.setSpeed(speedSteps[speedIdx])
        m.applyScale(scaleIdx)
        mpvPos = startMs
        mpvDur = 0
        setKeepScreenOn(true)
        val startPaused = false
        m.play(
            url, headers, startMs, activeSubs.map { it.uri.toString() },
            // 起播前按过暂停就带下去，别让内核自己解暂停
            startPaused = startPaused,
        )
        Log.i("XingHuiTV", "[mpv] 起播（内嵌） headers=${headers.size} subs=${activeSubs.size} start=${startMs}ms")
        // 15 秒没等到文件加载：说明 so 没打进包 / 该片源 mpv 解不了 → 本页临时退回 EXO
        uiHandler.removeCallbacks(mpvWatchdog)
        uiHandler.postDelayed(mpvWatchdog, 15_000)
    }

    private val mpvWatchdog = Runnable {
        if (mpvLoaded || activeUrl.isBlank()) return@Runnable
        Log.e("XingHuiTV", "[mpv] 起播超时，回退 EXO：${activeUrl.take(60)}")
        mpvFallbackExo = true
        runCatching { mpv?.release() }
        mpv = null
        updatePlayerText()
        resumeOverride = mpvPos
        playUrl(activeUrl, activeHeaders)
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun handleEnded() {
        if (loopOn) {
            if (isMpvMode()) {
                // resume() 内部已处理 eof-reached → seek 0 + play，这里不再重复 seek
                mpv?.resume()
            } else {
                player?.seekTo(0)
                player?.play()
            }
        } else if (directUrl.isBlank()) {
            setKeepScreenOn(false)
            // 线路播放（原版 select_flag）：全自动=切下一集；手动=只提示，等用户自己选。
            // 原版 VideoActivity.seamless() 的 true 分支就是只做临时高亮、不写历史、不自动切。
            // 手动模式只做高亮、不自动切集（原版 seamless() 的 true 分支没有提示，这里同样不提示）
            if (Settings(host).flagMode == 0) switchEpisode(1)
        }
    }

    // ===================== 底部控制条（对齐原版 view_control_vod + CustomKeyDownVod） =====================

    private var controlBar: View? = null
    /**
     * 控制条"显示中"的唯一判定。
     * 控制条从进页面起就一直 VISIBLE，显隐只靠 alpha —— 见 hideControl() 里的长注释。
     */
    private fun isInsideBar(v: android.view.View?): Boolean {
        val bar = controlBar ?: return false
        var p: android.view.View? = v
        while (p != null) {
            if (p === bar) return true
            p = p.parent as? android.view.View
        }
        return false
    }

    /** 居中提示是否可见 —— 对应原版 onBackInvoked 里的 `isVisible(mBinding.widget.center)`。 */
    private fun centerShown(): Boolean = runCatching {
        findViewById<View>(R.id.playCenter)?.let { it.alpha > 0.01f } ?: false
    }.getOrDefault(false)

    private fun barShown(): Boolean {
        val b = controlBar ?: return false
        // 控制条常驻 VISIBLE，是否"显示中"只能按 alpha 判。判据在 showControl / hideControl /
        // 按键三处共用，必须唯一：若这里误用 visibility，常驻 VISIBLE 会让判据恒为 true，
        // showControl 命中幂等分支永不出现、hideControl 永远早退。
        return b.visibility == View.VISIBLE && b.alpha > 0.5f
    }
    private var rootView: View? = null
    // 进页面耗时打点：/debug 里直接看「onCreate→onResume→窗口焦点」各多少毫秒，
    // 用毫秒数据判断"第一下按键被吞"到底是 onCreate 重活、还是首帧后 WMS 焦点移交慢。
    private var tCreate = 0L
    private var tResume = 0L
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var surfCallback: android.view.SurfaceHolder.Callback? = null
    private val IMM_HIDE_MS = 5_000L

    private var vodSpeedBefore = 1f

    private val vodKey = VodKeyMap(object : VodKeyMap.Listener {
        override fun onSeeking(accum: Long) {
            // 原版 onSeeking: seekTo(player().getPosition() + holdTime) —— 基准永远是"当前位置"。
            // 本层的 showSeekCenter 读 seekBaseMs，而它只在旧按键路径里写；
            // 新路径接管后可能停在 0 或旧值 → 第一次按 ← 直接跳开头。
            seekBaseMs = currentPosMs()
            showSeekCenter(accum)
        }
        override fun onSeekEnd(accum: Long) = applySeekEnd(accum)
        override fun onSpeedUp() = vodSpeedUp()
        override fun onSpeedEnd() = vodSpeedEnd()
        override fun onKeyUp() = vodKeyUp()
        override fun onKeyDown() = showControl(0)
        override fun onKeyCenter() = vodKeyCenter()
        override fun onSingleTap() = hideControl()
        override fun onDoubleTap() = vodKeyCenter()
    })

    /**
     * 原版 VideoActivity.dispatchKeyEvent:5964 那一行，逐字搬：
     *   if (isFullscreen() && isGone(mBinding.control.getRoot()) && mKeyDown.hasEvent(event) && service() != null)
     *       return mKeyDown.onKeyDown(event);
     * 四重合取：全屏 + 控制条不可见 + 五类键 + 引擎就绪；控制条一显示就交回焦点系统。
     */
    private fun vodKeyRoute(event: KeyEvent): Boolean {
        if (!vodKey.hasEvent(event)) return false
        // 控制条在本层是「常驻 VISIBLE + alpha 切换」（showControl/hideControl：GONE↔VISIBLE
        //   整层切换会黑一帧，所以只切 alpha）。所以"是否显示"只能看 alpha。
        //   用 visibility 判会永远成立 → 这五个键一个都到不了这里，新逻辑等于没接上。
        val bar = findViewById<View>(R.id.controlBar)
        if (bar != null && bar.alpha > 0.01f) return false
        if (player == null && mpv == null) return false
        return vodKey.onKeyEvent(event)
    }

    private fun applySeekEnd(accum: Long) {
        val dur = durNow()
        val raw = seekBaseMs + accum
        val target = if (dur > 0L) raw.coerceIn(0L, dur) else maxOf(0L, raw)
        runCatching { session.seekTo(target) }
        hidePlayCenter()
        seekBaseMs = target
        vodKey.reset()
    }

    private fun vodSpeedUp() {
        runCatching {
            if (!session.isPlaying()) return
        }
        vodSpeedBefore = runCatching { player?.playbackParameters?.speed ?: 1f }.getOrDefault(1f)
        val want = runCatching { Settings(host).pressSpeed }.getOrDefault(3f)
        runCatching { session.setSpeed(want) }
        findViewById<View>(R.id.playCenter)?.alpha = 1f
        findViewById<TextView>(R.id.playCenterPos)?.text = "加速"
        findViewById<ImageView>(R.id.playCenterIcon)?.setImageResource(R.drawable.ic_widget_forward)
    }

    private fun vodSpeedEnd() {
        runCatching { session.setSpeed(vodSpeedBefore) }
        hidePlayCenter()
    }

    private fun vodKeyUp() {
        val pos = currentPosMs()
        val dur = durNow()
        showControl(
            when {
                dur > 0L && pos < 60_000L -> R.id.btnOpening
                dur > 0L && pos > dur - 60_000L -> R.id.btnEnding
                else -> 0
            }
        )
    }

    private fun vodKeyCenter() {
        togglePlayPause()
        hideControl()
    }

    private val autoHide = Runnable { hideControl() }

    // 片头/片尾（对齐原版 History.getOpening/getEnding，按片记忆）
    private var openingMs = 0L
    private var endingMs = 0L
    private var endingDone = false
    private var loopOn = false
    private var resumeOverride = -1L
    // 最后一次起播的地址与请求头，供「硬解切换」重建播放器后重放
    private var activeUrl = ""

    private fun bindControlBar() {
        controlBar = findViewById(R.id.controlBar)
        runCatching {
            (controlBar as? android.view.ViewGroup)?.descendantFocusability =
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        findViewById<View>(R.id.playTitle)?.alpha = 0f
        // 常驻 VISIBLE + alpha 显隐。
        // GONE 形态下整条控制条不参与 dispatchDraw，第一次置 VISIBLE 那一帧要一次性建完
        // 整棵 display list，同时窗口「有绘制内容区域」从只有画面层的洞变成洞 + 整条，
        // ViewRootImpl 必须重算挖洞区域并 setTransparentRegionHint，该窗口层那一帧被跳过 ——
        // 就是「第一次按菜单黑约 1 秒」。
        // 常驻 VISIBLE + alpha=0 让这两件事在页面创建、画面还没出来时一次性付掉，
        // 之后呼条/收条只剩改 alpha（纯重绘，不动布局、不动挖洞区域）。
        controlBar?.visibility = View.VISIBLE
        controlBar?.alpha = 0f
        // 三层覆盖层统一初始化：全部 VISIBLE + alpha=0。
        // 它们都只在"显隐"上变化，不参与布局，alpha=0 时纯透明但仍占着绘制区域 —— 这正是要的：
        // 窗口的"有绘制内容区域"从第一帧起就是固定的，之后呼条 / 转圈 / 暂停图标出现都只是重绘。
        runCatching {
            findViewById<View>(R.id.playProgress)?.let { it.visibility = View.VISIBLE; it.alpha = 0f }
            findViewById<View>(R.id.playCenter)?.let { it.visibility = View.VISIBLE; it.alpha = 0f }
        }
        MpvDebug.log("[bar] 控制条初值 vis=${if (controlBar?.visibility == View.VISIBLE) "VISIBLE" else "GONE"} " +
            "alpha=${controlBar?.alpha} 常驻VISIBLE/alpha 显隐")
        // nextFocus 链必须在页面创建时就建好。
        //   actionLayout 里 17 个按钮的 nextFocusLeftId/nextFocusRightId 全是 0 ——
        //   焦点停在根 FrameLayout 上按方向键，系统做 focusSearch 找不到任何目标，
        //   这一下按键就被当"无效输入"丢掉（不是我们没收到，是焦点导航没有落点）。
        //   按过一次菜单后链建好了，之后一直正常，退出重进又从零开始 ——
        //   与现场「第一次按了菜单后面一直都可以用，除非退出重新进入」逐字吻合。
        runCatching { setControlNextFocus() }
        // 显隐语义与 5.6 的差别（这是本次架构级改动，不要再照抄 5.6 的 GONE）：
        //   5.6 用 VISIBLE/GONE，因为它没有把 SurfaceView 放进同层布局里被反复重排；
        //   我们用一块自有 SurfaceView 做画面层，GONE↔VISIBLE 会触发 measure/layout，
        //   连带把 playView 与它的 SurfaceView 重新摆放 → 挖洞区域重算 → 那一帧整窗被跳过 = 黑。
        //   现在统一用「常驻 VISIBLE + alpha」：任何一次显隐都只改绘制，不碰布局。
        // INVISIBLE↔VISIBLE 只改绘制、不触发布局（View.setFlags 里只有 GONE 参与可见性位变化
        // 才 requestLayout），所以这条链路彻底不碰画面层的 frame。
        // 根布局黑底已经做过洋红对照实验：闪的仍是黑、洋红一次都没露出来，
        // 说明盖住画面的不是窗口这层底，只能是画面层自己在那一帧没有内容。
        findViewById<View>(R.id.btnEpisodes).setOnClickListener { openEpisodes() }
        findViewById<View>(R.id.btnReset).setOnClickListener { resetCurrentEpisode() }
        findViewById<View>(R.id.btnPlayer).setOnClickListener { openPlayerDialog() }
        findViewById<View>(R.id.btnDecode).setOnClickListener { toggleDecode() }
        // 控制条控件清单：真机上按 OK 落在哪个 id 上，×debug 里对不上号就没法判断是谁没接住。
        runCatching {
            uiHandler.postDelayed({
                runCatching {
                    val row = findViewById<android.view.ViewGroup>(R.id.actionLayout)
                    if (row != null) {
                        val sb = StringBuilder("[ui] 控制条控件")
                        for (i in 0 until row.childCount) {
                            val c = row.getChildAt(i)
                            sb.append(" {id=${c.id} t=").append((c as? TextView)?.text?.toString() ?: "-")
                                .append(" click=").append(c.hasOnClickListeners()).append("}")
                        }
                        MpvDebug.log(sb.toString())
                    }
                    val top = findViewById<android.view.ViewGroup>(R.id.controlBar)
                    if (top != null) {
                        val sb = StringBuilder("[ui] 控制条行")
                        for (i in 0 until top.childCount) {
                            val c = top.getChildAt(i)
                            sb.append(" {id=${c.id} cls=").append(c.javaClass.simpleName)
                                .append(" click=").append(c.hasOnClickListeners()).append("}")
                        }
                        MpvDebug.log(sb.toString())
                    }
                }
            }, 200)
        }
        // 三个轨道按钮：tag 语义对齐原版 view_control_vod.xml（text=3 / audio=1 / video=2）
        findViewById<View>(R.id.btnTrackText).setOnClickListener {
            openTrack(androidx.media3.common.C.TRACK_TYPE_TEXT)
        }
        findViewById<View>(R.id.btnTrackAudio).setOnClickListener {
            openTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO)
        }
        findViewById<View>(R.id.btnTrackVideo).setOnClickListener {
            openTrack(androidx.media3.common.C.TRACK_TYPE_VIDEO)
        }
        // 片头/片尾：点击取当前位置，长按归零（原版 onOpening/onEnding + onOpeningReset/onEndingReset）
        findViewById<View>(R.id.btnOpening).setOnClickListener { markOpening() }
        findViewById<View>(R.id.btnOpening).setOnLongClickListener { clearOpening(); true }
        findViewById<View>(R.id.btnEnding).setOnClickListener { markEnding() }
        findViewById<View>(R.id.btnEnding).setOnLongClickListener { clearEnding(); true }
        // 弹幕未接入：原版 showControl 是按「站点是否支持弹幕」显隐这个按钮，无源时本来就隐藏。
        // 我们没有弹幕实现，布局里已置 gone，这里不再留「点了只弹一句未接入」的死入口。
        // 单集循环（对齐原版 onLoop：按钮 setActivated 翻转，播到结尾时重播而不是下一集）
        findViewById<View>(R.id.btnLoop).setOnClickListener { toggleLoop() }
        startProgressTicker()
    }

    private fun resetCurrentEpisode() {
        if (epUrl.isBlank()) {
            showPlayError("无当前集地址，无法刷新")
            return
        }
        Thread { resolvePlay(flag, epUrl) }.start()
    }

    private fun openTrack(type: Int) {
        hideControl()
        // 两颗内核现在都实现标准 Player 契约：轨道列表来自 player.currentTracks，
        // 选择走 player.trackSelectionParameters。上层不再需要判断"现在是哪个内核"。
        val p: androidx.media3.common.Player? = if (isMpvMode()) mpv else player
        if (p == null) {
            return
        }
        TrackDialog(host, p, type).show()
    }

    /**
     * 播放内核切换。
     * 本包只有两颗内核：内置 EXO 与内嵌 mpv，都真正可用，不存在「列出来但点不动」的项。
     */
    private fun openPlayerDialog() {
        val items = arrayOf("EXO（内置）", "mpv（内置）")
        val cur = if (isMpvMode()) 1 else 0
        android.app.AlertDialog.Builder(host)
            .setTitle("播放内核")
            .setSingleChoiceItems(items, cur) { d, which ->
                d.dismiss()
                when (which) {
                    1 -> if (!isMpvMode()) switchKernel(Settings.KERNEL_MPV)
                    0 -> if (isMpvMode()) switchKernel(Settings.KERNEL_EXO)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun switchKernel(kernel: String) {
        // 必须先取位置再改设置：isMpvMode() 读的就是设置值，改完再取会拿到另一颗内核的旧值
        val wasMpv = isMpvMode()
        val at = if (wasMpv) mpvPos else (player?.currentPosition ?: 0L)
        Settings(host).playerKernel = kernel
        mpvFallbackExo = false
        uiHandler.removeCallbacks(mpvWatchdog)
        updatePlayerText()
        val url = activeUrl
        if (url.isBlank()) {
            return
        }
        if (isMpvMode()) {
            startMpv(url, activeHeaders, at)
        } else {
            runCatching {
                videoHost?.releaseOwner()
                MpvDebug.log("[kernel] 换到 EXO：releaseOwner 已执行（输出面沿用常驻 SurfaceView）")
            }
            mpv?.release()
            mpv = null
            handOver("exo", "mpv 已 release（同步 destroy），下一步 EXO 接管画面层")
            // mpv 走了，EXO 需要重建播放器后接管
            if (player == null) rebuildPlayer(activeHeaders)
            // mpv 期间 scaleIdx 只作用在 mpv 上，切回 EXO 要把档位同步回 EXO 侧的 resizeMode
            videoHost?.resizeMode = scaleModes[scaleIdx]
            resumeOverride = at
            playUrl(url, activeHeaders)
        }
    }

    private fun toggleDecode() {
        if (isMpvMode()) {
            // mpv 走 hwdec=mediacodec / no，开关单独存在 mpvHwdec（不再和 EXO 的 decodeMode 共用）
            val st = Settings(host)
            st.mpvHwdec = !st.mpvHwdec
            mpv?.setHardwareDecode(st.mpvHwdec)
            updateDecodeText()
            return
        }
        val st = Settings(host)
        st.decodeMode = if (st.decodeMode == 1) 0 else 1
        updateDecodeText()
        if (activeUrl.isBlank()) {
            return
        }
        rebuildPlayer(activeHeaders)
        playUrl(activeUrl, activeHeaders)
    }

    private fun updateDecodeText() {
        // 对齐原版 select_decode = [软解, 硬解]；两内核各有各的开关，按当前内核取值显示
        val hard = if (isMpvMode()) Settings(host).mpvHwdec else Settings(host).decodeMode == 1
        findViewById<TextView>(R.id.btnDecode)?.text = if (hard) "硬解" else "软解"
    }

    private fun toggleLoop() {
        loopOn = !loopOn
        findViewById<View>(R.id.btnLoop)?.isActivated = loopOn
    }

    private fun updatePlayerText() {
        // 对齐原版 control.player：显示当前内核名
        findViewById<TextView>(R.id.btnPlayer)?.text = if (isMpvMode()) "mpv" else "EXO"
    }

    private fun markOpening() {
        val cur: Long
        val dur: Long
        if (isMpvMode()) {
            cur = mpvPos
            dur = mpvDur
        } else {
            val p = player ?: return
            cur = p.currentPosition
            dur = runCatching { p.duration }.getOrDefault(0L).takeIf { it > 0 } ?: 0L
        }
        if (cur <= 0 || dur <= 0 || cur > dur / 2) {
            showPlayError("请播到片头附近再按（需在前半段）")
            return
        }
        setOpening(cur)
    }

    private fun markEnding() {
        val cur: Long
        val dur: Long
        if (isMpvMode()) {
            cur = mpvPos
            dur = mpvDur
        } else {
            val p = player ?: return
            cur = p.currentPosition
            dur = runCatching { p.duration }.getOrDefault(0L).takeIf { it > 0 } ?: 0L
        }
        if (cur <= 0 || dur <= 0 || cur < dur / 2) {
            showPlayError("请播到片尾附近再按（需在后半段）")
            return
        }
        setEnding(dur - cur)
    }

    private fun setOpening(ms: Long) {
        openingMs = ms.coerceAtLeast(0)
        HistoryStore(host).setOpeningOf(historyKey, openingMs)
        updateOpeningText()
    }

    private fun setEnding(ms: Long) {
        endingMs = ms.coerceAtLeast(0)
        HistoryStore(host).setEndingOf(historyKey, endingMs)
        updateEndingText()
        endingDone = false
    }

    private fun clearOpening() {
        setOpening(0)
    }

    private fun clearEnding() {
        setEnding(0)
    }

    private fun updateOpeningText() {
        findViewById<TextView>(R.id.btnOpening)?.text = if (openingMs == 0L) "片头" else fmtClock(openingMs)
    }

    private fun updateEndingText() {
        findViewById<TextView>(R.id.btnEnding)?.text = if (endingMs == 0L) "片尾" else fmtClock(endingMs)
    }

    private fun fmtClock(ms: Long): String {
        val t = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d", t / 60, t % 60)
    }

    private fun isMenuKeyCode(code: Int): Boolean = code == KeyEvent.KEYCODE_MENU

    /**
     * 菜单键动作（原版 5.0.8 VideoActivity.java:5630-5641 两分支：c.P()==0 → o0(true)/X0(i0()) 切条，==1 → t0():6195 弹选集）：
     * Setting.getFullscreenMenuKey() == 0 → onToggle() 切控制栏显隐
     * == 1 → onEpisodes() 弹选集并收起控制栏
     */

    private var lastBackMs = 0L

    private fun fireMenuKeyOnce() {
        // 原版没有去重也没有窗口闸门：KeyUtil.isMenuKey 只认 ACTION_UP，到了就执行（onToggle:3426）
        handleMenuKey()
    }

    private fun windowReady(): Boolean = runCatching {
        val dv = window?.decorView
        dv != null && dv.width > 0 && dv.height > 0 && dv.hasWindowFocus()
    }.getOrDefault(false)

    private fun mpvDebugLine(): String {
        val m = mpv
        // 方向键到达计数（永不被裁剪）。若这里全是 0，说明事件根本没到 Activity。
        val dpadCnt = "左=${keyCnt[0]}/${keyCnt[5]}" +
            " 右=${keyCnt[1]}/${keyCnt[6]}" +
            " OK=${keyCnt[2]}/${keyCnt[7]}" +
            " 菜单=${keyCnt[3]}/${keyCnt[8]}" +
            " 上下=${keyCnt[4]}/${keyCnt[9]}（DOWN/UP）" +
            (if (lastDpadInfo.isNotBlank()) " | 末次方向键: $lastDpadInfo" else "")
        val surfaceVis = runCatching {
            val sv = currentSurface()
            "${sv?.width}x${sv?.height} vis=${sv?.visibility} valid=${(sv as? android.view.SurfaceView)?.holder?.surface?.isValid ?: (sv != null)}"
        }.getOrNull()
        return "mpv=${m != null} 存活实例=${MpvPlayer.liveCount} 设置内核=${Settings(host).playerKernel} " +
            "isMpvMode=${isMpvMode()} surfaceReady=${m?.surfaceReadyNow()} paused=${m?.isPausedActual()} " +
            "【方向键】$dpadCnt " +
            "mpvSink=$surfaceVis playView=${videoHost?.visibility} exo=${player != null} " +
            "controlBar=${controlBar?.visibility} vo=${m?.voNow()} pos=${snapPosMs}ms dur=${snapDurMs}ms " +
            "playing=${snapPlaying} " +
            "decor=${runCatching { "${window.decorView.width}x${window.decorView.height}" }.getOrElse { "?" }} " +
            "winFlags=${runCatching { Integer.toHexString(window.attributes.flags) }.getOrElse { "?" }} " +
            "winFmt=${runCatching { Integer.toHexString(window.attributes.format) }.getOrElse { "?" }} " +
            "insets=${runCatching { val i = window.decorView.rootWindowInsets; "${i?.systemWindowInsetLeft}x${i?.systemWindowInsetTop}x${i?.systemWindowInsetRight}x${i?.systemWindowInsetBottom}" }.getOrElse { "?" }} " +
            "exoSurf=${runCatching { val sv = videoHost?.surfaceView as? android.view.View; "${sv?.width}x${sv?.height} vis=${sv?.visibility}" }.getOrElse { "?" }} " +
            "url=${activeUrl.take(60)}"
    }

    // ---------- EXO 三秒黑屏探针（只写 /debug，不改任何 UI 与播放行为） ----------
/**
     */

    private fun handleMenuKey() {
        val toEpisodes = Settings(host).fullscreenMenuKey == 1
        // 菜单这一帧只做「控制条显隐」这一件事，所有诊断全部推到下一帧。
        //
        //   推理依据是现成的：?x=2（菜单键完全不呼出控制条）真机**仍然黑** ——
        //   说明黑跟控制条、跟 showControl 里的任何布局/焦点动作都无关。
        //   那个档位下，菜单这一帧里还在跑的东西只剩下这两段诊断：
        //     · mpvDebugLine()：Settings（SharedPreferences）、currentSurface()、
        //       window.attributes、rootWindowInsets、mpv 的 JNI 属性读——
        //       全部在主线程同步执行，恰好压在窗口要重新合成的那一帧上；
        //     · [disp]：windowManager.defaultDisplay + window attributes。
        //   把它们挪到 post（下一帧）之后，菜单帧里就什么都不剩了。
        //   这就是最便宜、可判定的一次 A/B：若 x=2 从此不黑，黑源就是诊断自己。
        if (toEpisodes) {
            hideControl()
            openEpisodes()
        } else {
            toggleControl()
        }
        // 这两段诊断必须离开"呼条后那 1 秒"。
        //
        //   它们读的是 currentSurface()、window.attributes、decorView.rootWindowInsets、
        //   以及 mpv 的 JNI 属性 —— 全部是主线程同步 IO/IPC。
        //   其中 decorView.rootWindowInsets 在 API 25 上**第一次读取**会走一遍
        //   dispatchApplyWindowInsets 全树，等于在呼条那一帧里又塞了一次窗口遍历；
        //   而 ?x=2（菜单键完全不呼出控制条）真机**仍然黑**，说明黑源本来就不在控制条上，
        //   这一帧里剩下的正是这两段。之前只挪到 post 下一帧，仍然压在黑的那一秒里。
        //   推到 1.5 秒之后：黑的那一段已经过去，日志一条不少。
    }

    /**
     * 双入口兜底：极少数 ROM 的按键不经过 Activity.dispatchKeyEvent 直接落到 onKeyDown，
     * 这里再认一次菜单键。dispatchKeyEvent 已经 return true 时不会走到这里，所以不会双触发。
     */
    /**
     * 呼出控制条（对齐原版 VideoActivity.showControl:1148-1157）。
     *
     * focusId / withTitle 也照原版分开：
     *   ↑(onKeyUp:1755) → 焦点给片头/片尾，并 showInfo（顶部片名信息）
     *   ↓(onKeyDown:1763) / 菜单(onToggle:1086) → 焦点给"下集"，不带 info
     */
    private var lastBarFocusId: Int = 0

    /* 判黑取证：surface 代数 + UI 线程迟到检测。
       「黑的那一下连洋红方块都不在」已经排除了"某个视图画黑"这条分支，
       只剩两种可能，必须靠这两组数分开：
         surfGen 变大  → 视频 surface 真的被销毁重建（合成器/显示管线跟着重配 → 整屏黑）
         [stall] 迟到  → UI 线程被卡住（这一帧压根没提交，屏幕自然是上一层）
       两者都不出现却还黑 → 显示管线自己切了，代码里改视图没用。 */
    /* 帧投递探针（区别于 [stall]）：
       [stall] 只看 UI 线程有没有迟到；它看不出一件事 —— 线程照常跑、帧照常提交，
       但屏幕就是不更新（整窗不参与合成）。有了这个每帧回调，黑屏期间的帧数变化可以定死：
         n 一直在涨 + 屏幕黑  → 我们的帧在出、是合成/窗口层把它丢了（代码改视图无用）
         n 停住             → 线程或 vsync 断了（配合 [stall] 定性）
       只在每满 1 秒打一行，播放中零额外开销。 */
    private var frameLastLog = 0L
    private var surfGen = 0
    private var lastSurfCreateAt = 0L

    // /debug 快照是在 NanoHttpd 线程里跑的函数式调用，直接读 EXO 会抛
    //   IllegalStateException(Player is accessed on the wrong thread)。
    //   位置/时长/播放态改由主线程每 500ms 落一份缓存，快照只读缓存。
    @Volatile private var snapPosMs = 0L
    @Volatile private var snapDurMs = 0L
    @Volatile private var snapPlaying = false
    private var snapAt = 0L

/**
     */
    private val snapTick = object : Runnable {
        override fun run() {
            runCatching {
                snapPosMs = session.positionMs()
                snapDurMs = session.durationMs()
                snapPlaying = session.isPlaying()
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

    private fun showControl(focusId: Int = 0, withTitle: Boolean = false) {
        // wasShown 必须在这里取：常驻 VISIBLE 之后 barShown() 只看 alpha，
        //   下面马上就把 alpha 抬到 1，若在那之后再判就恒为"已显示"，每次呼条都被幂等分支吞掉。
        val wasShown = barShown()
        // 呼出 = 放开焦点进入 + alpha 抬到 1（否则条内控件不接收焦点）
        // 常驻 VISIBLE 下这一步不要再写 visibility：对已经 VISIBLE 的视图置 VISIBLE 是 no-op，
        // 但留在代码里会让人以为"显隐仍然靠 visibility"，下次重构又改回 GONE。
        if (controlBar?.visibility != View.VISIBLE) controlBar?.visibility = View.VISIBLE
        controlBar?.alpha = 1f
        findViewById<View>(R.id.playTitle)?.alpha = 1f
        runCatching {
            (controlBar as? android.view.ViewGroup)?.descendantFocusability =
                android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        // 幂等 + 只补差集：真机日志里一次菜单键 / 一次起播能连进来 3 次 showControl
        // （菜单键一次、起播一次带标题、焦点兜底一次），打出 draws=4~5 layouts=3 —— 
        // 一次呼条造了三轮全树 traversal。条已经显示时，剩下要补的只有两件事：
        //   a) withTitle=true 时把标题透明度抬起来（只重绘，不重排）
        //   b) 指定了 focusId 时把焦点挪过去（焦点变化不触发布局）
        // 其余一律跳过，绝不重复 measure/layout。
        if (wasShown) {
            if (withTitle) findViewById<View>(R.id.playTitle)?.let { it.alpha = 1f }
            if (focusId != 0) runCatching { findViewById<View>(focusId)?.requestFocus() }
            uiHandler.removeCallbacks(autoHide)
            uiHandler.postDelayed(autoHide, IMM_HIDE_MS)
            return
        }
        // 呼出前先让画面层把上一帧重绘一次：
        //   接下来这一帧要同时合成「视频层 + 控制条」，如果视频层这次没提交新帧，
        //   合成器就会拿到空队列 → 整屏黑一下。先把同一帧重新贴上去，黑的那一下就不存在。
        // 菜单路径上一个 native 调用都不做。
        //   历史：这里曾是 redrawFor(1200)（36 次 render+swap），后改成单次 redrawNow()，
        //   真机仍闪退 —— 说明 mpv 内核按菜单键的崩溃点就在"窗口变化期间调 render API"本身。
        //   控制条并不覆盖视频矩形（它在留边/画面之上，由窗口层合成），根本不需要重贴视频帧；
        //   要恢复画面是在隐/显切换之后（onWindowFocusChanged 的 recoverRendering）再处理。
        findViewById<View>(R.id.playTitle)?.alpha = if (withTitle) 1f else 0f
        // 焦点落点：原版 getFocus2() —— 显式指定就用指定的；否则沿用上次；
        // 上次停在片头/片尾时按原版规则换成"下一集"。
        val target = when {
            focusId != 0 -> focusId
            lastBarFocusId != 0 &&
                lastBarFocusId != R.id.btnOpening && lastBarFocusId != R.id.btnEnding -> lastBarFocusId
            else -> R.id.timeBar
        }
        // 呼条前先记下真实位置：呼条这一拍若把播放点顶回旧值（现场"播了 4 分钟、
        //   第一次按菜单又回到 3 分钟"就是这种），下面的守卫会把它拉回来。
        val posBeforeBar = if (!wasShown) currentPosMs() else 0L
        // 呼条前把进度条内部位置对齐真实位置。
        //   DefaultTimeBar 的 scrub 起点取它自己的 position（只在 setPosition 时更新）；
        //   进页面不久就呼条时那份值可能还是 0，焦点一进条、随后一次 ←，就会提交 0
        //   —— 现场「第一次按菜单视频回到开头 + 黑完出现加载中」正是它（seekTo(0) 触发重缓冲）。
        runCatching {
            findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.timeBar)
                ?.setPosition(currentPosMs())
        }
        var focusOk = false
        focusOk = runCatching { findViewById<View>(target)?.requestFocus() == true }.getOrDefault(false)
        MpvDebug.log(
            "[bar] 呼出完成 alpha=${controlBar?.alpha} target=$target focusOk=$focusOk" +
                " nowFocus=${host.currentFocus?.id}(${host.currentFocus?.javaClass?.simpleName})"
        )
        // 次帧焦点确认。
        //   androidx/media issue #1828 现场记录过同一件事：在 dispatchKeyEvent 里调 requestFocus()
        //   **不会当帧落在视图上**，用户要等到"松开按键"才看到焦点真正过去。
        //   我们呼条也是在按键回调里 requestFocus 的，所以 focusOk=true 只代表"受理了"，
        //   不代表这一帧焦点已经在条里；下一按若走焦点系统（OK / 条内导航），就会打空。
        //   这里在下一帧补一次确认：只有在焦点仍为空或仍在根布局时才重试，避免把用户
        //   已经移进条内的焦点又拽回去。
        if (!wasShown) {
            val want = target
            uiHandler.post {
                val cur = host.currentFocus
                if (cur == null || cur === rootView) {
                    val ok2 = runCatching { findViewById<View>(want)?.requestFocus() == true }.getOrDefault(false)
                    MpvDebug.log("[bar] 次帧焦点确认 want=$want ok=$ok2 now=${host.currentFocus?.id}(${host.currentFocus?.javaClass?.simpleName})")
                }
            }
        }
        // 位置守卫：呼条必然带着一次窗口重排，这一拍里任何"按历史续播点重新定位"
        //   都会把画面顶回旧位置。播了 1 分钟却退回起点、或退回上次的续播点，
        //   用时间差就能认出来（正常播放只会前进，不会一次倒退 30 秒以上）。
        if (posBeforeBar > 30_000L) {
            uiHandler.postDelayed({
                val now = currentPosMs()
                if (now > 0L && now < posBeforeBar - 30_000L) {
                    MpvDebug.log("[bar] 呼条后位置被顶回 pos=$now 原=$posBeforeBar → 复位")
                    session.seekTo(posBeforeBar)
                }
            }, 600)
        }
        // nextFocus 链只影响**后续**方向键，不影响刚才这次 requestFocus，
        // 所以推到下一帧再算，别压在呼条那一帧上（17 个按钮 × 2 个属性）。
        uiHandler.post { runCatching { setControlNextFocus() } }
        uiHandler.removeCallbacks(autoHide)
        uiHandler.postDelayed(autoHide, IMM_HIDE_MS)
        MpvDebug.log("[ui] showControl isMpv=${isMpvMode()} mpv=${mpv != null} focus=$focusId")
    }

    /**
     * 窗口层合成探针（PixelCopy）。
     *
     * 注意：INVISIBLE/GONE 时窗口层那片区域本来就是 0，所以探针只在 alpha>0 时才有判读意义。
     */
    private fun hideControl() {
        uiHandler.removeCallbacks(autoHide)
        // 幂等：真机日志里同一毫秒能连进来 4 次（autoHide + 起播收条 + 按键兜底各调一次），
        // 每次 GONE 都给 root 记一次 requestLayout，= 多出 3 轮全树 traversal，
        // 每一轮父窗口重画黑底、SurfaceView 的洞晚一帧合成 = 白闪一格。
        // 5.6 的 hideControl 任意时刻只被调用一次，所以它从来不会连闪。
        // 常驻 VISIBLE 下"收起" = 清焦点 + 降 alpha + 关掉焦点进入。
        // 不用 GONE/INVISIBLE：那两种都会让窗口层的绘制内容区域变化（GONE 还额外触发 measure/layout）。
        if (!barShown()) return
        val fromId = host.currentFocus?.id
        val fromIn = isInsideBar(host.currentFocus)
        if (fromIn) {
            rootView?.requestFocus()
            // from 必须先在 requestFocus 之前取。之前这条日志取的是动作之后的值，
            // 真机上永远显示"从=-1"，看着像"焦点自己从条上漂走了"，实际是插值取晚了。
            MpvDebug.log(
                "[key] 收条前清焦点 从=$fromId inBar=$fromIn" +
                    " lastBarFocus=$lastBarFocusId barVis=${controlBar?.visibility}" +
                    " barAlpha=${controlBar?.alpha} → root ok=${host.currentFocus === rootView}"
            )
        }
        // 原版 hideControl 只有 alpha 动作；GONE 会让窗口层绘制内容区域变化（多一轮 measure/layout）
        controlBar?.alpha = 0f
        findViewById<View>(R.id.playTitle)?.alpha = 0f
        runCatching {
            (controlBar as? android.view.ViewGroup)?.descendantFocusability =
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        // 同上：收条这一步也不碰 render API（菜单键的 DOWN/UP 常常成对触发呼/收，两次都在窗口变化期）
        MpvDebug.log("[ui] hideControl isMpv=${isMpvMode()} mpv=${mpv != null}")
    }

    /**
     * 对齐原版 setControlNextFocus(:1128-1145)：把不可见 / 禁用的按钮从左右焦点链里摘掉。
     * 控制条里片头、片尾、字幕、音轨、视轨这些按钮是按能力显隐的，
     * 不重算 nextFocus 时方向键会跳进 GONE 的按钮，看着像"跳不动"。
     */
    /**
     * 把焦点交回画面层（原版 getFocus1:1670 的落点）。
     *
     * 真机日志里 hideControl 之后 host.currentFocus 是 -1（无任何视图持有焦点），
     * 说明 rootView.requestFocus() 一直没成功 —— 首键就成了"把焦点捡回来"，表现为要按两次。
     * 这里显式置可聚焦再请求，仍失败就退到 decorView，并把结果写进 /debug。
     */
    private fun chainOf(v: View?): String {
        val sb = StringBuilder()
        var p: View? = v
        var n = 0
        while (p != null && n < 6) {
            if (n > 0) sb.append(" < ")
            sb.append(p.id).append(':').append(p.javaClass.simpleName)
            p = p.parent as? View
            n++
        }
        return sb.toString()
    }

    private fun isInside(parent: View, child: View): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    /**
     * 控制条可见时，焦点必须落在条内的控件上。
     *
     * 真机日志（22:30）: 控制条刚呼出时 `[probe] menu t0 focus=2131361874`（下集），
     * 起播后同一批按键就变成 `focus=-1(FrameLayout)` —— 焦点被抢回了根布局，
     * 于是 ←/→ 交给 super 时没有任何控件接住，表现就是"按了没反应、要再按一次"。
     * 这里在每次按键分发之前兜一次：不在条内就把落点拉回去。
     */
    private fun ensureBarFocus(): Boolean {
        val bar = controlBar ?: return false
        if (!barShown()) return false
        val before = host.currentFocus?.id
        val cur = host.currentFocus
        // 所以按方向键时 lastBarFocus 一直是 0，收条/重落点都拿不到"上次停在哪个按钮"。
        if (cur != null && cur.id != 0 && isInsideBar(cur)) {
            lastBarFocusId = cur.id
            return true
        }
        // 判据二：焦点树根在条之下时 findFocus() 才会返回非空 —— 这是"焦点是否属于本条"的权威答案。
        // 现场出现过 was=2131362217(DefaultTimeBar) 却仍判"不在条上"（沿 parent 比较那条路
        // 在我们这套布局上不可靠），结果是每次按键都对同一个控件再 requestFocus 一次，
        // 白多一轮焦点变化。这里补上这条，并在两条都失败时把 parent 链打出来。
        if (cur != null && runCatching { bar.findFocus() === cur }.getOrDefault(false)) {
            if (cur.id != 0) lastBarFocusId = cur.id
            return true
        }
        MpvDebug.log(
            "[ui] 条内判据都失败 was=${cur?.id}(${cur?.javaClass?.simpleName})" +
                " bar=${bar.id}(${bar.javaClass.simpleName}) chain=${chainOf(cur)}"
        )
        var target = runCatching { bar.findFocus() }.getOrNull()
        if (target == null || target.visibility != View.VISIBLE) {
            target = findViewById<View>(lastBarFocusId.takeIf { it != 0 } ?: R.id.timeBar)
        }
        if (target == null || target.visibility != View.VISIBLE) target = findViewById(R.id.timeBar)
        val ok = target?.requestFocus() == true
        MpvDebug.log(
            "[ui] 控制条可见但焦点不在条上 → 重新落点 was=$before" +
                " lastBarFocus=$lastBarFocusId → id=${target?.id}" +
                "(${target?.javaClass?.simpleName}) ok=$ok"
        )
        return ok
    }

    /**
     * 画面层像素格式保持系统默认，不做任何改动。
     *
     * SurfaceHolder.setFormat 会让 SurfaceFlinger 重分配一整套 buffer，等于在播放中
     * 重建画面层拓扑；原版与官方 PlayerView 全程不碰它。这里只留一条诊断日志。
     */
    private fun setupSharedSurface() {
        val sv = videoHost?.surfaceView ?: return
        runCatching {
            MpvDebug.log("[sv] 画面层沿用官方默认 PixelFormat（不 setFormat）")
        }
    }

    private fun focusRoot(reason: String) {
        var v: View? = rootView
        if (v == null) {
            v = runCatching {
                (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)
            }.getOrNull()
            rootView = v
        }
        if (v == null) {
            MpvDebug.log("[ui] focusRoot 无视图 reason=$reason")
            return
        }
        if (!v.isFocusable) v.isFocusable = true
        if (!v.isFocusableInTouchMode) v.isFocusableInTouchMode = true
        val ok = v.requestFocus()
        if (!ok) runCatching { window?.decorView?.requestFocus() }
        MpvDebug.log("[ui] focusRoot reason=$reason ok=$ok id=${v.id} focus=${host.currentFocus?.id}")
    }

    private fun setControlNextFocus() {
        runCatching {
            val row = findViewById<android.view.ViewGroup>(R.id.actionLayout) ?: return@runCatching
            var prev: View? = null
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i)
                if (v.visibility != View.VISIBLE || !v.isEnabled || !v.isFocusable) continue
                prev?.let {
                    it.nextFocusRightId = v.id
                    v.nextFocusLeftId = it.id
                }
                prev = v
            }
        }
    }

    private fun toggleControl() {
        if (barShown()) hideControl() else showControl()
    }

    private fun enableBarFocus(on: Boolean) {
        runCatching {
            (controlBar as? android.view.ViewGroup)?.descendantFocusability =
                if (on) android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                else android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    /**
     * 暂停时居中提示（对齐原版 VideoActivity.onPaused → showInfoAndCenter）。
     *
     * 原版动作：把 exo_position / exo_duration 填好，再把 widget.center 设 VISIBLE。
     * center 里固定是 ic_widget_play（播放三角），语义为「按 OK 继续播放」，不是暂停符号。
     * 只在全屏下显示，我们播放页本身就是全屏。
     */
    private fun showPlayCenter() {
        val durMs = if (isMpvMode()) mpvDur
        else runCatching { player?.duration ?: 0L }.getOrDefault(0L)
        findViewById<TextView>(R.id.playCenterPos)?.text = fmtTime(currentPosMs())
        findViewById<TextView>(R.id.playCenterDur)?.text =
            if (durMs > 0L) fmtTime(durMs) else "--:--"
        // 第三层覆盖层同样常驻 VISIBLE + alpha（见 showPlayProgress 里的依据）
        findViewById<View>(R.id.playCenter)?.alpha = 1f
    }

    private fun hidePlayCenter() {
        // 对齐原版 hideCenter()：图标复位回 ic_widget_play，再整块收起
        findViewById<ImageView>(R.id.playCenterIcon)?.setImageResource(R.drawable.ic_widget_play)
        findViewById<View>(R.id.playCenter)?.alpha = 0f
    }

    private fun togglePlayPause() {
        val wasPlaying = session.isPlaying()
        session.toggle()
        val nowPaused = !session.isPlaying()
        applyPauseUi(nowPaused)
        MpvDebug.log(
            "[key] toggle kernel=${if (isMpvMode()) "mpv" else "exo"} wasPlaying=$wasPlaying 现在暂停=$nowPaused"
        )
        // 内核的 isPlaying 是异步刷新：刚下发 pause 的那一帧可能还是 true。
        // 250ms 后回读一次真实状态，日志里这条才是"到底停没停"的判据。
        uiHandler.postDelayed({
            MpvDebug.log("[key] toggle+250ms kernel=${if (isMpvMode()) "mpv" else "exo"} isPlaying=${session.isPlaying()}")
        }, 250)
    }

    private fun applyPauseUi(pausing: Boolean) {
        // 原版控制条没有播放/暂停按钮：暂停态只体现在居中 playCenter（图标 + 时间），
        //   按钮文字不需要同步。OK 键仍走 togglePlayPause()，行为不变。
        if (pausing) {
            setKeepScreenOn(false)
            showPlayCenter()
        } else {
            setKeepScreenOn(true)
            hidePlayCenter()
        }
    }

    // ===== 左右键快进/快退（原版 CustomKeyDownVod：按住累加，松开 250ms 后一次跳） =====
    private val SEEK_STEP_MS = 10_000L
    private var seekBaseMs = 0L          // 按下方向键那一刻的位置
    private var seekAccumMs = 0L         // 按住期间累计的偏移
    private var seekingByKey = false

/**
     */
    private fun currentPosMs(): Long = session.positionMs()

    /**
     * 快进/快退的居中提示：对齐原版 VideoActivity.onSeeking。
     *
     * 原版动作：exoPosition 填「目标位置」、图标换成 forward / rewind、center 设 VISIBLE、
     * 同时 hideProgress()。目标位置用 seekBaseMs + accum 算；方向按 accum 正负判断
     * （原版用的是 time > 0，对跳到 0 附近会判错方向，这里更准）。
     */
    private fun showSeekCenter(accum: Long) {
        val durMs = durNow()
        // 时长未知时不能显示「绝对位置」：那时 seekBaseMs=0，按 ← 的 -10000 会被夹成 0，
        //   屏幕上和起点一模一样的 00:00 —— 用户看到的就是"按了没反应"。
        //   这时显示位移本身（-00:10 / +00:10），让人一眼知道键收到了。
        val target = if (durMs > 0L) (seekBaseMs + accum).coerceIn(0L, durMs) else 0L
        findViewById<ImageView>(R.id.playCenterIcon)?.setImageResource(
            if (accum > 0L) R.drawable.ic_widget_forward else R.drawable.ic_widget_rewind
        )
        findViewById<TextView>(R.id.playCenterPos)?.text =
            if (durMs > 0L) fmtTime(target)
            else (if (accum < 0L) "-" else "+") + fmtTime(if (accum < 0L) -accum else accum)
        findViewById<TextView>(R.id.playCenterDur)?.text = if (durMs > 0L) fmtTime(durMs) else "--:--"
        findViewById<View>(R.id.playCenter)?.alpha = 1f  // 覆盖率同其它覆盖层：只改 alpha，不动 visibility
        MpvDebug.log(
            "[ui] 居中提示 显示 " + (if (durMs > 0L) "target=${fmtTime(target)}" else "位移=${if (accum<0) "-" else "+"}${fmtTime(if(accum<0) -accum else accum)}") +
                " dur=${if (durMs > 0L) fmtTime(durMs) else "--:--"}" +
                " panelAlpha=${findViewById<View>(R.id.playCenter)?.alpha} accum=${accum}ms"
        )
    }

    /**
     * 进度条的 scrub 回调。左右键已收回 Activity 处理，这里只剩触摸拖动，但仍必须挂 ——
     * DefaultTimeBar 无监听者时只消费输入不向外报告。
     *
     * DefaultTimeBar 1.3.1 的三处行为：
     *   1) duration <= 0 时 scrubIncrementally 直接 return false，左右键连 scrub 都不启动；
     *   2) 按键 scrub 延迟 1s 才触发 onScrubStop，而 seek 会把 READY 打回 BUFFERING；
     *   3) 起点取自身 position 字段，只由 setPosition 喂，ticker 停摆时会从旧值往回跳。
     */
    private fun bindTimeBarScrub() {
        val tb = findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.timeBar) ?: return
        tb.setKeyTimeIncrement(10_000L)
        tb.setKeyCountIncrement(1)
        tb.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                seekingByKey = true
                uiHandler.removeCallbacks(hideSeekCenterRun)
                showJumpCenter(position)
            }

            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                showJumpCenter(position)
            }

            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, cancelled: Boolean) {
                seekingByKey = false
                if (!cancelled) {
                    // DefaultTimeBar 的 position 由 setPosition() 喂，内核位置另有来源；
                    // 两者差得多就说明条的位置是陈旧的（ticker 没跑 / 刚呼条）。留痕以便分流。
                    val livePos = currentPosMs()
                    if (livePos > 0L && kotlin.math.abs(position - livePos) > 30_000L) {
                        MpvDebug.log("[key] 进度条位置陈旧 条=${position}ms 内核=${livePos}ms 差=${position - livePos}ms")
                    }
                    MpvDebug.log("[key] 进度条跳转 target=${position}ms dur=${durNow()}ms")
                    session.seekTo(position)
                    seekBaseMs = position
                    seekAccumMs = 0L
                }
                uiHandler.removeCallbacks(hideSeekCenterRun)
                uiHandler.postDelayed(hideSeekCenterRun, 2_200)
            }
        })
    }

    private fun showJumpCenter(target: Long) {
        val durMs = durNow()
        findViewById<ImageView>(R.id.playCenterIcon)?.setImageResource(R.drawable.ic_widget_forward)
        findViewById<TextView>(R.id.playCenterPos)?.text = fmtTime(target)
        findViewById<TextView>(R.id.playCenterDur)?.text = if (durMs > 0L) fmtTime(durMs) else "--:--"
        findViewById<View>(R.id.playCenter)?.alpha = 1f  // 覆盖率同其它覆盖层：只改 alpha，不动 visibility
    }

    private fun mediaSeekStep(dir: Long) {
        val dur = durNow()
        var target = (currentPosMs() + dir * SEEK_STEP_MS).coerceAtLeast(0L)
        if (dur > 0L) target = target.coerceAtMost(dur)
        MpvDebug.log("[key] 媒体键 seek dir=$dir → target=${target}ms dur=${dur}ms")
        if (!contentReady()) {
            // 原版 VideoActivity.dispatchKeyEvent:5974 用 service() != null 挡掉未就绪的按键：
            // 不记账、不补发、不提示。
            MpvDebug.log("[key] 内核未就绪，媒体键 seek 不处理 target=$target")
            return
        }
        seekBaseMs = currentPosMs()
        seekAccumMs = dir * SEEK_STEP_MS
        showSeekCenter(seekAccumMs)
        lastSeekCommitAt = android.os.SystemClock.uptimeMillis()
        session.seekTo(target)
        uiHandler.postDelayed({ MpvDebug.log("[key] 媒体键跳+250ms pos=${currentPosMs()}ms") }, 250)
        uiHandler.removeCallbacks(hideSeekCenterRun)
        uiHandler.postDelayed(hideSeekCenterRun, 2_200)
    }

    private fun seekTargetNow(): Long {
        val dur = durNow()
        var t = (seekBaseMs + seekAccumMs).coerceAtLeast(0L)
        if (dur > 0L) t = t.coerceAtMost(dur)
        return t
    }

    private val hideSeekCenterRun = Runnable { hidePlayCenter() }

    /**
     * 方向键 / 媒体快进键共用的累加逻辑。
     *
     * 参数只有一套：首按 +10s；长按每 300ms 再 +10s；最后一次按下后 400ms 自动提交。
     */
    /**
     * 隐藏态左右键，完全对齐原版 CustomKeyDownVod.check()：
     *   LEFT/RIGHT + ACTION_DOWN → onSeeking(subTime()/addTime())，只累积 holdTime 并更新居中提示；
     *   LEFT/RIGHT + ACTION_UP   → App.post(onSeekEnd(holdTime), 250)，按「当前位置 + holdTime」只跳一次。
     * 必须一次下发。逐档 DOWN 直接 seekTo 会让 EXO 反复重进 BUFFERING、互相打断。
     */
    private fun handleSeekKey(dir: Long, event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!seekingByKey) {
                    seekingByKey = true
                    seekBaseMs = currentPosMs()
                    seekAccumMs = 0L
                }
                // 对齐原版 CustomKeyDownVod.java:83 / :87 —— 每来一个 ACTION_DOWN 就 ±INTERVAL_SEEK（10s），
                // 属于为了压住 T1 高频 repeat 加的补偿，已按原版要求去掉。
                seekAccumMs += dir * SEEK_STEP_MS
                MpvDebug.log(
                    "[key] 快进 dir=$dir accum=${seekAccumMs}ms repeat=${event.repeatCount} dur=${durNow()}ms bar=${barShown()}"
                )
                // 夹在 [0, dur] 里：原版会开到越界再靠 seekTo 夹，我们提前夹，提示上的时间才不会出现负数。
                // 未起播（dur=0）时不能夹：那时 seekBaseMs 恒为 0，夹下界会把 -10000 直接变成 0，
                //   位移就丢了 —— 现场"进页面按 ← 完全没反应"正是这里把它抹平了。
                val dur = durNow()
                if (dur > 0L) {
                    if (seekAccumMs < -seekBaseMs) seekAccumMs = -seekBaseMs
                    if (seekBaseMs + seekAccumMs > dur) seekAccumMs = dur - seekBaseMs
                }
                showSeekCenter(seekAccumMs)
            }
            KeyEvent.ACTION_UP -> {
                if (!seekingByKey) {
                    MpvDebug.log("[key] 快进抬起但未起手 kc=${event.keyCode} (DOWN 未进入快进分支)")
                    return
                }
                seekingByKey = false
                val target = seekTargetNow()
                val from = currentPosMs()
                lastSeekCommitAt = android.os.SystemClock.uptimeMillis()
                session.seekTo(target)
                MpvDebug.log(
                    "[key] 跳 base=${seekBaseMs}ms accum=${seekAccumMs}ms → target=${target}ms " +
                        "dur=${durNow()}ms from=${from}ms attached=${runCatching { (player?.mediaItemCount ?: 0) > 0 }.getOrDefault(false)}"
                )
                // 内核异步：250ms 后回读真实位置，这才是「到底跳没跳」的判据（日志看着生效不算数）
                uiHandler.postDelayed({
                    MpvDebug.log("[key] 跳+250ms pos=${currentPosMs()}ms")
                }, 250)
                seekAccumMs = 0L
                uiHandler.removeCallbacks(hideSeekCenterRun)
                uiHandler.postDelayed(hideSeekCenterRun, 2_200)
            }
        }
    }

/**
     */

/**
     */
    private var progressTick: Runnable? = null

    private var rxSample = -1L
    private var rxSampleAt = 0L

    /**
     * 实时网速文本（原版 view_widget_display 的 `@id/netspeed`，右下角 16sp）。
     *
     * 用 TrafficStats 的本应用收包字节差分算真实下载速率——这正是原版那一栏的语义。
     * 不用 player.bandwidthEstimate：那是媒体3 的**带宽估算**，缓冲满时会掉到 0，
     * 与原版"一直在动"的网速不是一回事。
     */
    private fun trafficText(): String {
        val rx = runCatching {
            android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        }.getOrDefault(-1L)
        if (rx < 0) return ""
        val now = android.os.SystemClock.uptimeMillis()
        val prev = rxSample
        val prevAt = rxSampleAt
        rxSample = rx
        rxSampleAt = now
        if (prev < 0 || prevAt == 0L || now <= prevAt) return ""
        val bps = (rx - prev).coerceAtLeast(0L) * 1000.0 / (now - prevAt)
        return when {
            bps >= 1048576.0 -> String.format(java.util.Locale.US, "%.1f MB/s", bps / 1048576.0)
            bps >= 1024.0 -> String.format(java.util.Locale.US, "%.0f KB/s", bps / 1024.0)
            else -> ""
        }
    }

    private fun startProgressTicker() {
        progressTick?.let { uiHandler.removeCallbacks(it) }
        // 视图引用只取一次：findViewById 每次都要遍历视图树，而这段 Runnable 是每秒一直在跑的
        val bar = findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.timeBar)
        val posText = findViewById<TextView>(R.id.timePos)
        val durText = findViewById<TextView>(R.id.timeDur)
        // 原版 @id/display 的常驻信息层：时钟/网速/总时长/底部 1dp 进度条，每秒都要刷新，
        //   引用同样只取一次（findViewById 会遍历视图树）。
        val clockTv = findViewById<TextView>(R.id.clock)
        val netTv = findViewById<TextView>(R.id.netspeed)
        val totalTv = findViewById<TextView>(R.id.playDuration)
        val bottomPb = findViewById<android.widget.ProgressBar>(R.id.bottomProgress)
        val progText = findViewById<TextView>(R.id.playProgressText)
        // 原版 utils/Clock.java：Clock.create(view) 默认 format("HH:mm:ss")，1 秒一次。
        // API 25 没有 java.time，用 SimpleDateFormat。
        val clockFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val tick = object : Runnable {
            override fun run() {
                val pos: Long
                val dur: Long
                if (isMpvMode()) {
                    pos = mpvPos.coerceAtLeast(0L)
                    dur = durNow()
                } else {
                    val p = player
                    if (p == null) {
                uiHandler.postDelayed(this, 1000)
                        return
                    }
                    pos = if (p.currentPosition > 0) p.currentPosition else 0L
                    dur = if (p.duration > 0) p.duration else 0L
                }
                bar?.setDuration(dur)
                bar?.setPosition(pos)
                posText?.text = fmtTime(pos)
                durText?.text = fmtTime(dur)
                // ── 原版 view_widget_display.xml 的四件常驻信息 ──
                clockTv?.text = clockFmt.format(java.util.Date())
                totalTv?.text = if (dur > 0) fmtTime(dur) else "--:--"
                if (dur > 0 && bottomPb != null) {
                    bottomPb.progress = ((pos * 100) / dur).coerceIn(0L, 100L).toInt()
                }
                // 网速同一份采样喂两个地方：右下角 netspeed + 转圈下面的 traffic
                val sp = trafficText()
                netTv?.text = sp
                progText?.text = sp.ifBlank { "加载中" }
                if (endingMs > 0 && dur > 0 && !endingDone && pos + endingMs >= dur) {
                    endingDone = true
                    if (directUrl.isBlank()) switchEpisode(1)
                }
                // 原版 Clock.java:25 是 1000ms
                uiHandler.postDelayed(this, 1000)
            }
        }
        progressTick = tick
        uiHandler.post(tick)
    }

    private fun fmtTime(ms: Long): String {
        val total = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    /**
     * 遥控器分发（对齐原版 VideoActivity.dispatchKeyEvent + CustomKeyDownVod.check）：
     *   控制条隐藏时 → OK 暂停/继续、↑↓ 呼出控制条、←→ 快退快进
     *   控制条显示时 → 交给系统做焦点导航，并重置 4 秒自动收起
     */
    // 键码判定照原版 KeyUtil（app/src/leanback/.../utils/KeyUtil.java）：
    //   OK 还认 SPACE，↑↓ 还认 CHANNEL/PAGE/MEDIA 类 —— 不同遥控器映射不同，
    //   只认 DPAD 时，把 ↑↓ 映射成 CHANNEL_UP/DOWN 的遥控会完全没反应。
    private fun isEnterKey(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_SPACE ||
        e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun isUpKey(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || e.keyCode == KeyEvent.KEYCODE_PAGE_UP ||
        e.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS

    private fun isDownKey(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        e.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || e.keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
        e.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT

    private fun isLeftKey(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
    private fun isRightKey(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    private val keyCnt = IntArray(10)
    // 最近一次方向键到达时的关键上下文（也写进快照）
    private var lastDpadInfo = ""

    fun onHostKeyEvent(event: KeyEvent): Boolean {
        // 离开页面前掐掉还没落地的 250ms seek，否则"已经退出了还跳一下位置"
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK) vodKey.release()
        if (vodKeyRoute(event)) return true
        // 计数放在所有分支之前，且不看 action / repeatCount：
        //   只要这个方法被调用，就一定计数。用来区分"事件没到"与"到了被日志裁剪"。
        if (event.repeatCount == 0) {
            val k = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 2
                KeyEvent.KEYCODE_MENU -> 3
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> 4
                else -> -1
            }
            if (k >= 0) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> keyCnt[k]++
                    KeyEvent.ACTION_UP -> keyCnt[k + 5]++
                }
            }
        }
        if (event.repeatCount == 0 &&
            event.keyCode in intArrayOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER
            )
        ) {
            // 方向键单独记一条，含"这次到底就绪没有 / 窗口有没有焦点 / 焦点在谁身上"。
            // 这条不受 [key] 轨迹的条数上限影响（轨迹上限已从 24 提到 64）。
            val act = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
            lastDpadInfo = "kc=${event.keyCode}($act) winFocus=${runCatching { window.decorView.hasWindowFocus() }.getOrElse { null }}" +
                " ready=${contentReady()} windowReady=${runCatching { windowReady() }.getOrElse { null }}" +
                " bar=${barShown()} focus=${host.currentFocus?.id}(${host.currentFocus?.javaClass?.simpleName ?: "null"})" +
                " sv=${runCatching { (currentSurface() as? android.view.SurfaceView)?.holder?.surface?.isValid }.getOrElse { null }}"
            MpvDebug.log("[key] 方向键到达 $lastDpadInfo")
        }
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            runCatching {
                MpvDebug.log(
                    "[key] kc=${event.keyCode} focus=${host.currentFocus?.id}" +
                        "(${host.currentFocus?.javaClass?.simpleName ?: "null"})" +
                        " bar=${barShown()}" +
                        " ready=${contentReady()} mpv=${mpv != null} exo=${player != null}"
                )
            }
        }
        // OK 的按下也记一行（上面只记了抬起）：用来分辨「键根本没到 Activity」与「到了但没效果」
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            MpvDebug.log(
                "[key] 按下 kc=${event.keyCode} winFocus=${runCatching { window.decorView.hasWindowFocus() }.getOrElse { null }}" +
                    " ready=${contentReady()} bar=${barShown()} loading=${preStart()} " +
                    "barVis=${controlBar?.visibility} barAlpha=${controlBar?.alpha} " +
                    "focus=${host.currentFocus?.id}(${host.currentFocus?.javaClass?.simpleName ?: "null"}) " +
                    "touchMode=${runCatching { window.decorView.isInTouchMode }.getOrNull()} mpv=${mpv != null} exo=${player != null}"
            )
        }
        // 窗口就绪闸门。真机 /debug 证据：每次进页面的第一下按键，窗口都还没有
        // hasWindowFocus() —— SurfaceView 一创建窗口就丢焦并重排一次，第一下按键正好
        // 落在首次合成那一拍上。菜单键由这段闸门推迟补救，←→/OK 没有闸门直接打上去。
        //
        // 控制条可见时先确保焦点在条内：起播/表面变化会把焦点抢回根布局。
        // 菜单键（对齐原版 5.0.8 VideoActivity.java:5630-5641 两分支 + KeyUtil.isMenuKey:43）
        //   Setting.fullscreenMenuKey == 0 → onToggle()：控制栏显隐
        //   == 1 → onEpisodes()：弹选集并收起控制栏
        if (isMenuKeyCode(event.keyCode)) {
            // 对齐 KeyUtil.isMenuKey:43 —— 只认 ACTION_UP（isActionUp && KEYCODE_MENU）
            if (event.action == KeyEvent.ACTION_UP) fireMenuKeyOnce()
            return true
        }

        when (event.keyCode) {
            // 只接 MEDIA_FAST_FORWARD / MEDIA_REWIND，且**只在抬起时**触发一次 ——
            //   原版 KeyUtil.isMediaFastForward(:59) / isMediaRewind(:55) 都带 isActionUp。
            //   之前把 MEDIA_NEXT / MEDIA_PREVIOUS 也绑成快进快退，等于把原版的
            //   「下一集/上一集」（KeyUtil:20/24 里它们是 ↑↓ 的同义键）整个吃掉。
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_UP) mediaSeekStep(1L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_UP) mediaSeekStep(-1L)
                return true
            }
        }

        val shown = barShown()
        val loading = preStart()

        // 返回键：对齐原版 onBackInvoked（5.6.0 :6336）的分层顺序
        //   ① lutQuick.hideIfVisible()  → 我们没有 LUT 快捷面板，跳过
        //   ② 控制条可见              → hideControl()
        //   ③ 居中提示可见            → hideCenter()
        //   ④ 全屏                    → exitFullscreen()（我们播放层本身即全屏 → 退出播放层）
        //   ⑤ 其余                    → finishVideoPlayback（saveHistory(true) + stopPlayback）
        // 只认 ACTION_UP，对齐 dispatchKeyEvent:5966 的 `isActionUp && isBackKey`。
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (barShown()) { hideControl(); return true }
            if (centerShown()) { hidePlayCenter(); return true }
            leaving = true
            runCatching { savePosition(force = true, reason = "退场") }
            MpvDebug.log("[key] back 退出播放页 pos=" + currentPosMs() + " shown=" + shown)
            onExit()
            runCatching { host.overridePendingTransition(0, 0) }
            return true
        }

        if (shown && !loading) {
            uiHandler.removeCallbacks(autoHide)
            uiHandler.postDelayed(autoHide, IMM_HIDE_MS)
            // 原版 CustomUpDownView：片头/片尾聚焦时 ↑+1s、↓-1s
            val fid = host.currentFocus?.id
            if (isLeftKey(event) || isRightKey(event)) {
                val cur = host.currentFocus
                val inBar = cur != null && cur.id != 0 && isInsideBar(cur)
                if (inBar) {
                    // 5.6.0 控制条内的 ←→ 没有代码，全靠原生焦点系统在按钮间移动。
                    return false
                }
                MpvDebug.log("[key] 条显示但焦点不在条内 → 快进/快退 kc=${event.keyCode} focus=${cur?.id}")
                handleSeekKey(if (isLeftKey(event)) -1L else 1L, event)
                return true
            }
            // 原版 CustomUpDownView 的三枚（speed / 字幕 / 片头 / 片尾）都在这里接上下键：
            if (fid == R.id.btnSpeed && (isUpKey(event) || isDownKey(event))) {
                if (event.action == KeyEvent.ACTION_DOWN) adjustSpeed(if (isUpKey(event)) 1 else -1)
                return true
            }
            // 字幕：原版 setUpListener/setDownListener 都指向同一个 onSubtitleClick（5.6.0:680/681），
            // 也就是上下键都等于"点一下字幕按钮"，只有两个方向对称才算对齐。
            if (fid == R.id.btnTrackText && (isUpKey(event) || isDownKey(event))) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    openTrack(androidx.media3.common.C.TRACK_TYPE_TEXT)
                }
                return true
            }
            if ((fid == R.id.btnOpening || fid == R.id.btnEnding) && (isUpKey(event) || isDownKey(event))) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val add = isUpKey(event)
                    val dur = player?.duration ?: 0L
                    val half = if (dur > 0) dur / 2 else Long.MAX_VALUE
                    if (fid == R.id.btnOpening) {
                        setOpening(if (add) minOf(openingMs + 1000, half) else maxOf(0L, openingMs - 1000))
                    } else {
                        setEnding(if (add) minOf(endingMs + 1000, half) else maxOf(0L, endingMs - 1000))
                    }
                }
                return true
            }
            host.currentFocus?.takeIf { it.id != 0 }?.let { lastBarFocusId = it.id }
            if (isEnterKey(event) && event.action == KeyEvent.ACTION_UP) {
                val v = host.currentFocus
                val hasClick = v?.hasOnClickListeners() == true
                MpvDebug.log(
                    "[key] OK 落在控制条上 id=${v?.id} cls=${v?.javaClass?.simpleName}" +
                        " text=" + ((v as? TextView)?.text?.toString() ?: "") +
                        " clickable=${v?.isClickable} hasClick=$hasClick"
                )
                // 5.6.0 没有 fallback：OK 在控制条上一律交给控件自己处理，
                // 落在没有 click 的标签上就是什么都不发生。
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                MpvDebug.log(
                    "[key] 交焦点系统 kc=${event.keyCode} focus=${host.currentFocus?.id}" +
                        " inBar=${isInsideBar(host.currentFocus)} lastBarFocus=$lastBarFocusId" +
                        " barAlpha=${controlBar?.alpha} barVis=${controlBar?.visibility}"
                )
            }
            return false
        }

        if (isEnterKey(event)) {
            // 长按倍速的收尾仍交给 onKeyUp（fastForwarding）
            if (fastForwarding) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    if (loading) {
                        // 原版 VideoActivity.onKeyCenter:6280 —— 空播放器 → onRefresh()：重新起播。
                        // 三分支：isPlaying → onPaused；isEmpty → onRefresh；否则 → onPlay。
                        MpvDebug.log("[key] 起播前 OK → 重新起播（原版 onKeyCenter 的 onRefresh 分支）")
                        startPlayback()
                    } else {
                        togglePlayPause()
                    }
                }
                return true
            }
            return true
        }
        if (isUpKey(event) || isDownKey(event)) {
            if (event.action == KeyEvent.ACTION_UP) {
                // 原版 onKeyUp:1755 / onKeyDown:1763：
                //   ↑ → showInfo + 控制条，焦点给片头（进度没过半）或片尾（过半）
                //   ↓ → showInfo + 控制条，焦点给"下集"（getFocus2 默认值）
                val dur = durNow()
                val half = if (dur > 0L) dur / 2 else Long.MAX_VALUE
                val atOpeningHalf = currentPosMs() < half
                val focusId = if (isUpKey(event)) {
                    if (atOpeningHalf) R.id.btnOpening else R.id.btnEnding
                } else {
                    R.id.btnNext
                }
                showControl(focusId, withTitle = true)
            }
            return true
        }
        if (isLeftKey(event) || isRightKey(event)) {
            handleSeekKey(if (isLeftKey(event)) -1L else 1L, event)
            return true
        }
        // 所有没被上面任何分支接住的键，在这里统一留痕。
        //   这条只是为了让"按了没反应"能立刻变成一行具体键码，不再靠猜。
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            MpvDebug.log("[key] 未接 kc=${event.keyCode}(${KeyEvent.keyCodeToString(event.keyCode)})")
        }
        return false
    }

    /**
     * 沉浸式全屏，照原版 Util.hideSystemUI(Window)：只设 systemUiVisibility，不碰窗口 flag。
     * 加 FLAG_FULLSCREEN / LAYOUT_IN_SCREEN 会让窗口属性可再次变更（呼条、焦点变化、
     * 关弹窗都会触发 onWindowFocusChanged），WMS 重新布局、SurfaceView 重新挖洞，
     * 本机 ROM 上重算结果是整屏被窗口盖住 —— 画面全黑、声音照走。
     * 白边由主题 windowFullscreen=true 兜，不需要窗口 flag。
     */
    /**
     * 沉浸式全屏 —— 完全照原版 `Util.hideSystemUI(Window)`：
     *
     *     只设 systemUiVisibility，**不碰任何窗口 flag**。
     *
     * 我们额外下发过 FLAG_FULLSCREEN | FLAG_LAYOUT_IN_SCREEN，并加了 LAYOUT_FULLSCREEN，
     * 只要窗口属性再变一次（呼出控制条、焦点变化、关弹窗回来都会触发 onWindowFocusChanged），
     * WindowManager 就重新布局窗口、SurfaceView 重新挖洞；这家 ROM 上重算的结果是
     * 整屏被窗口盖住 —— 表现为「画面全黑、声音照走」，按返回（窗口再变一次）才恢复。
     *
     * 主题里已经有 windowFullscreen=true，白边不靠这两条 flag 兜；
     * HDMI 改分辨率后若真出现白边，也由主题 + LAYOUT_STABLE 兜住，不需要窗口 flag。
     */
    private fun applyImmersive() {
        runCatching {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
            Log.i("XingHuiTV", "[play] 沉浸式全屏已应用（原版语义：只设 systemUiVisibility）")
        }.onFailure { Log.e("XingHuiTV", "[play] 沉浸式全屏设置失败", it) }
    }

    private fun switchEpisode(delta: Int) {
        // 直播直链没有「集」的概念，直接忽略（原版同样不提示）
        if (directUrl.isNotBlank()) return
        ensureLines()
        val line = if (vodLines.isNotEmpty()) vodLines.firstOrNull { it.name == flag } ?: vodLines.first() else return
        val eps = line.episodes
        if (eps.isEmpty()) return
        val cur = eps.indexOfFirst { it.second == epUrl }
        //   表现就是"按了上下集乱跳/像没反应"。原版 checkPrev/checkNext 也是拿当前 Episode 对象
        //   在本线路里取索引，取不到就只做按钮置灰，不跳。
        if (cur < 0) {
            MpvDebug.log("[ep] 当前集不在本线路（flag=$flag），不跳转")
            return
        }
        val next = if (delta > 0) (cur + 1).coerceAtMost(eps.lastIndex) else (cur - 1).coerceAtLeast(0)
        // 到头了就停在原地（原版 checkPrev/checkNext 只做按钮置灰，不弹提示）
        if (next == cur) return
        Thread { resolvePlay(line.name, eps[next].second) }.start()
    }

    /**
     * 线路/集列表补齐：先看本层有没有，没有就从宿主要。
     *
     * 原版集列表一直在 Flag 对象上（`getFlag().getEpisodes()`），从不二次请求；
     * 我们复用进入时不走 fetchAndPlay，所以必须在这里补，否则上下集/选集全废。
     */
    private fun ensureLines() {
        if (vodLines.isNotEmpty()) return
        val fromHost = runCatching { host.hostLines() }.getOrDefault(emptyList())
        if (fromHost.isEmpty()) return
        vodLines = fromHost
        if (flag.isBlank()) flag = host.hostFlagName().ifBlank { fromHost.firstOrNull()?.name.orEmpty() }
        MpvDebug.log("[lines] 从宿主补线路 ${fromHost.size} 条 flag=$flag")
    }

    private fun applySpeed() {
        val s = speedSteps[speedIdx]
        // 原版控制条上就显示档位数字（图里是 1.00），不写「倍速」
        findViewById<TextView>(R.id.btnSpeed).text =
            String.format(java.util.Locale.US, "%.2f", s)
        if (isMpvMode()) {
            mpv?.setSpeed(s)
            return
        }
        player?.setPlaybackSpeed(s)
    }

    fun onHostKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (fastForwarding && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            fastForwarding = false
            speedIdx = savedSpeedIdx
            applySpeed()
            return true
        }
        return false
    }

    private fun openEpisodes() {
        ensureLines()
        if (vodLines.isEmpty()) {
            showPlayError("暂未获取到线路信息（请稍候再点选集）")
            return
        }
        val cl = vodLines.indexOfFirst { it.name == flag }
        val clIdx = if (cl >= 0) cl else 0
        val ce = runCatching {
            vodLines.getOrNull(clIdx)?.episodes?.indexOfFirst { it.second == epUrl }
        }.getOrDefault(-1) ?: -1
        EpisodeDialog(host, vodLines, { li, ei ->
            val line = vodLines[li]
            val ep = line.episodes[ei].second
            Thread { resolvePlay(line.name, ep) }.start()
        }, clIdx, ce).show()
    }

    /**
     * 窗口重新获得焦点就重设一次沉浸式。
     * 原版 BaseActivity:136-139 就是这么做的 —— IMMERSIVE_STICKY 的 flag 在窗口失焦后会被系统清掉，
     * 只在 onCreate 设一次的话，弹过系统框、切过页面回来就成了非全屏，画面被压缩、四周露出边。
     */
    fun onHostWindowFocusChanged(hasFocus: Boolean) {
        val now = SystemClock.uptimeMillis()
        MpvDebug.log(
            "[ui] 窗口焦点 hasFocus=$hasFocus decor=${window?.decorView?.width}x${window?.decorView?.height}" +
                " 距onCreate=${if (tCreate > 0) now - tCreate else -1}ms" +
                " 距onResume=${if (tResume > 0) now - tResume else -1}ms"
        )
        if (hasFocus && host.currentFocus == null) {
            // 进页面时 focus=-1（真机日志确认），窗口拿到焦点但没有任何视图持有焦点，
            // 第一下方向键常常只是"把焦点捡回来"，表现就是"要再点一次才有效"。
            MpvDebug.log("[key] 窗口获焦但无焦点视图，补一次 requestFocus")
            (rootView ?: findViewById<View>(android.R.id.content))?.requestFocus()
        }
        if (hasFocus) {
            // 这里绝不能再调 setLocalFocus：窗口此刻已经持有焦点，再请求一次会让
            //   WindowManager 重新派发焦点事件 → 又进 onWindowFocusChanged → 无限循环。
            //   真机日志里就是 [ui] 窗口焦点 / [win] setLocalFocus 成对刷屏、每帧两条，
            //   主线程被日志与焦点遍历占满，按键反而更不灵。
            MpvDebug.log("[win] 窗口拿到焦点 decor=${window.decorView.width}x${window.decorView.height}")
            applyImmersive()
            // 关掉选集弹窗、从系统框回来时，mpv 的 vo 可能已经被窗口变化带掉；
            // 等布局稳定后再自检一次，避免黑屏只剩声音
            // 尺寸自检：若 内容区 < 屏幕，说明窗口仍未真正铺满（白边就是壁纸从这个缝里露出来的）
            runCatching {
                val dm = resources.displayMetrics
                val root = window.decorView
                Log.i(
                    "XingHuiTV",
                    "[play] 尺寸 内容=${root.width}x${root.height} 屏=${dm.widthPixels}x${dm.heightPixels}"
                )
            }
        }
    }

    fun onHostConfigurationChanged(newConfig: android.content.res.Configuration) {
        applyImmersive()
    }

    /**
     * 切后台：暂停**当前内核**并落盘。
     * 5.6.0 VideoActivity 实测没有覆写 onPause（全仓只有 onPaused:5788），这一段不是照搬原版，按官方 Player 契约实现：
     * 原版是统一暂停，不分内核；之前我们只 pause mpv，EXO 在后台会继续跑。
     */
    fun onHostPause() {
        seekingByKey = false
        hidePlayCenter()
        MpvDebug.log("[life] onPause 前 " + mpvDebugLine())
        if (isMpvMode()) runCatching { mpv?.setBackground(true) }
        // 两颗内核都停：isMpvMode() 只反映设置里选中的那颗，一旦和真正在跑的那颗不一致
        // （mpv 起播失败回退 EXO、切内核半途中断），只看它就会漏掉真正出声的内核 ——
        // 在真机上表现就是「回主页后后台还在播、按什么都没反应」。
        runCatching { mpv?.pause() }
        runCatching { player?.pause() }
        savePosition(reason = "onPause")
    }

    /**
     * 回到前台恢复播放。5.6.0 VideoActivity 没有覆写 onResume，这段按官方 Player 契约实现。
     * 两个前提要一起覆盖：
     *  1) onResume 常早于 surfaceCreated，此时 vo 为 null，内核侧 resume() 记账、
     *     等 attach 完再补发 pause=false；
     *  2) mpv 实例可能已被起播 watchdog 置空，此时 resume() 是空操作，需按当前位置重建。
     */
    fun onHostResume() {
        tResume = SystemClock.uptimeMillis()
        rootView?.post {
            runCatching {
                val f = host.currentFocus
                if (f == null) {
                    val ok = rootView?.requestFocus() ?: false
                    MpvDebug.log("[life] onResume 无焦点 → 兜回根布局 ok=$ok")
                }
            }
        }
        runCatching {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        MpvDebug.log("[life] onResume " + mpvDebugLine())
        // 首次进入时 url 还是空的，这里不能抢跑
        if (activeUrl.isBlank()) return
        if (isMpvMode()) {
            val m = mpv
            m?.let { runCatching { it.ensureSurface() } }
            if (m == null) {
                // 内核在后台没了：按当前进度重建，否则这里什么都不会发生
                startMpv(activeUrl, activeHeaders, mpvPos)
            } else {
                runCatching { m.setBackground(false) }
                runCatching { m.resume() }
            }
        } else {
            runCatching { player?.play() }
        }
    }

    fun onHostDestroy() {
        progressTick?.let { uiHandler.removeCallbacks(it) }
        progressTick = null
        uiHandler.removeCallbacks(snapTick)
        runCatching { uiHandler.removeCallbacks(autoHide) }
        runCatching { uiHandler.removeCallbacks(hideSeekCenterRun) }
        MpvDebug.snapshot = null
        uiHandler.removeCallbacks(mpvWatchdog)
        saveHandler.removeCallbacks(saveRunnable)
        // 先落盘再释放内核：mpv 一旦 destroy 就读不到 time-pos 了
        runCatching { savePosition(reason = "onDestroy") }
        runCatching { saveExecutor.shutdown() }
        runCatching { mpv?.release() }
        mpv = null
        sniffer?.cancel()
        sniffer = null
        runCatching { videoHost?.releaseOwner() }
        // 宿主那一份引用必须一起清。
        //   PlayLayer.player 与 host.player 是同一个实例（rebuildPlayerInternal 会回写），
        //   只把本层置空、宿主仍指着已 release 的实例 —— 详情页预览再碰它就是
        //   "Player is already released" 崩溃。原版 PlayerManager 也是 release 后置 null。
        val dying = player
        player = null
        if (host.player === dying) host.player = null
        runCatching { dying?.release() }
        // 播放器已释放，这时候清迅雷任务/分片才安全（文件不删，则 thunder/ 会无限增长）
        runCatching { ThunderPlay.stop() }
    }
}

/**
 * 软解选择器：只保留非硬件加速的 codec（对齐原版 Setting.getDecode()==SOFT 的语义）。
 * 注：media3 的 EXO 默认硬解优先、失败自动回退，这里是在用户明确选「软解」时强制走软件解码器。
 */
internal object SoftMediaCodecSelector : androidx.media3.exoplayer.mediacodec.MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        val all = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        val soft = all.filter { !it.hardwareAccelerated }
        // 设备上若只有硬解 codec，宁可回退全集也不能让播放器找不到解码器
        return if (soft.isEmpty()) all else soft
    }
}

internal class SoftRenderersFactory(context: android.content.Context) :
    androidx.media3.exoplayer.DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: android.content.Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<androidx.media3.exoplayer.Renderer>
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, SoftMediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out
        )
    }
}

/**
 * 原版 CustomKeyDownVod 逐字移植（leanback/.../custom/CustomKeyDownVod.java）。
 *
 *   hasEvent = ENTER | UP | DOWN | LEFT | RIGHT
 *   LEFT/RIGHT DOWN → onSeeking(hold ∓ 10s)     LEFT/RIGHT UP → App.post(onSeekEnd(hold), 250)
 *   UP 短按 → onKeyDown   UP 长按 → onSpeedUp   UP UP → changeSpeed ? onSpeedEnd : onKeyUp
 *   DOWN UP → onKeyDown   ENTER UP → onKeyCenter   reset() → hold = 0
 *
 * 左右按下只预告、松开 250ms 才落；上键短按出控制条、长按加速；五个键只在 ACTION_UP 做事。
 */
private class VodKeyMap(private val listener: Listener) {

    interface Listener {
        fun onSeeking(accum: Long)
        fun onSeekEnd(accum: Long)
        fun onSpeedUp()
        fun onSpeedEnd()
        fun onKeyUp()
        fun onKeyDown()
        fun onKeyCenter()
        fun onSingleTap()
        fun onDoubleTap()
    }

    private val STEP = 10_000L
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val commit = Runnable { listener.onSeekEnd(hold) }
    private var hold = 0L
    private var changeSpeed = false

    /**
     * 同义键逐字对齐 5.6.0 utils/KeyUtil.java：
     *   isEnterKey = DPAD_CENTER | ENTER | SPACE | NUMPAD_ENTER
     *   isUpKey    = DPAD_UP | CHANNEL_UP | PAGE_UP | MEDIA_PREVIOUS
     *   isDownKey  = DPAD_DOWN | CHANNEL_DOWN | PAGE_DOWN | MEDIA_NEXT
     *   isLeftKey / isRightKey = DPAD_LEFT / DPAD_RIGHT
     * 只认裸 DPAD_* 会让遥控器的上一页/下一页/上一曲/下一曲在控制条隐藏时进不来。
     */
    private fun isEnter(kc: Int): Boolean = kc == KeyEvent.KEYCODE_DPAD_CENTER ||
        kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_SPACE || kc == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun isUp(kc: Int): Boolean = kc == KeyEvent.KEYCODE_DPAD_UP ||
        kc == KeyEvent.KEYCODE_CHANNEL_UP || kc == KeyEvent.KEYCODE_PAGE_UP ||
        kc == KeyEvent.KEYCODE_MEDIA_PREVIOUS

    private fun isDown(kc: Int): Boolean = kc == KeyEvent.KEYCODE_DPAD_DOWN ||
        kc == KeyEvent.KEYCODE_CHANNEL_DOWN || kc == KeyEvent.KEYCODE_PAGE_DOWN ||
        kc == KeyEvent.KEYCODE_MEDIA_NEXT

    private fun isLeft(kc: Int): Boolean = kc == KeyEvent.KEYCODE_DPAD_LEFT

    private fun isRight(kc: Int): Boolean = kc == KeyEvent.KEYCODE_DPAD_RIGHT

    fun hasEvent(e: KeyEvent): Boolean =
        isLeft(e.keyCode) || isRight(e.keyCode) || isUp(e.keyCode) || isDown(e.keyCode) || isEnter(e.keyCode)

    fun reset() { hold = 0L }

    fun onKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP
        when {
            down && isLeft(e.keyCode) -> { hold -= STEP; listener.onSeeking(hold) }
            down && isRight(e.keyCode) -> { hold += STEP; listener.onSeeking(hold) }
            up && (isLeft(e.keyCode) || isRight(e.keyCode)) -> {
                ui.removeCallbacks(commit); ui.postDelayed(commit, 250L)
            }
            up && isUp(e.keyCode) -> {
                if (changeSpeed) listener.onSpeedEnd() else listener.onKeyUp()
                changeSpeed = false
            }
            up && isDown(e.keyCode) -> listener.onKeyDown()
            up && isEnter(e.keyCode) -> listener.onKeyCenter()
            e.isLongPress && isUp(e.keyCode) && !changeSpeed -> {
                listener.onSpeedUp(); changeSpeed = true
            }
        }
        return true
    }

    fun release() { ui.removeCallbacks(commit); hold = 0L; changeSpeed = false }
}
