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
class PlayActivity : Activity() {

    private var player: ExoPlayer? = null

    private var vodId = ""

    /**
     * 历史记录主键。
     *
     * 原版 History 的 key 是「vod_id 为空就用片名」（History.createHistory 里 cid/name 的组合），
     * 我们 HistoryStore.addHistory 内部也是这个规则；但 setPosition / marks / setOpeningOf 这些
     * 都先判 `vodId.isBlank() -> return`。
     *
     * 于是详情接口不返回 vod_id 的站点（很常见）会出现：
     *   addHistory 用片名建了记录 → 进度却因为 vodId 为空一次都没写进去 → 回到详情页/再进播放页
     *   读出来的 position 永远是 0，看起来就是「一按返回就跳回片头」。
     * 统一走这个 key 后，写入与读取是同一条记录。
     */
    // ★第三级兜底到剧集地址：从历史/收藏/推送/直链进来的入口常常 vodId 与 vodName 全空，
    //   key 变成空串 → recordHistory 不写、probe 查不到，现场就是 row=false 而 total=9。
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
    // ★不能用 by lazy 冻结：yield 出来的键只反映"第一次访问那一刻"的 vodId/vodName/epUrl。
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

    /** 键推导的任一输入被改写后必须调用，否则历史继续写到旧剧/旧集名下 */
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

    // 倍速档位（对齐原项目点击循环 addSpeed；Up/Down 加减 0.25）
    private val speedSteps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = 2 // 默认 1.0x
    private var fastForwarding = false
    private var savedSpeedIdx = 2
    private var videoHost: VideoHost? = null
    /** 播放状态唯一所有者：控制动作一律经它，Activity 不再直接判「能不能做」 */
    private lateinit var session: PlaybackSession
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tCreate = SystemClock.uptimeMillis()
        setContentView(R.layout.activity_play)
        // 根容器当画面容器用（原版 @id/video 的等价物）：可聚焦，收控制条时焦点回这里
        rootView = runCatching {
            (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)
        }.getOrNull()
        // 对齐原版 VideoActivity:614「进页面就把焦点放到画面容器」：
        // 有焦点时这条 ROM 上按键分发最稳，也是控制条收起时的落点。
        // 进页面这一拍的实际焦点状态写进 /debug：进页面到按菜单键之前，
        // 如果这里是 cur=-1 且 winFocus=false，就说明"按键无效"是窗口/焦点这一层没接上，
        // 而不是按键分支写错（分支层面 ←→/OK 根本不看焦点）。
        rootView?.post {
            // ★守卫：只在"没人持焦"时落点。
            //   这个 post 会等到首帧才执行；如果用户已经先按了菜单、焦点进了控制条，
            //   这里无条件 requestFocus 就会把它抢回根容器 —— 真机日志里表现为
            //   「呼出完成 nowFocus=DefaultTimeBar」→ 一秒后「was=-1(FrameLayout)」，
            //   然后靠 showControl 里的补救再落回条上。这一抢一补正是首键体感不稳定的来源。
            val hadFocus = currentFocus
            val rootOk = if (hadFocus == null) rootView?.requestFocus() == true else true
            val dv = window?.decorView
            if (dv != null && !dv.hasWindowFocus()) runCatching { dv.requestFocus() }
            MpvDebug.log(
                "[ui] 进页面焦点 rootFocus=$rootOk 原有焦点=${hadFocus?.id} cur=${currentFocus?.id}" +
                    " winFocus=${runCatching { dv?.hasWindowFocus() }.getOrElse { null }}" +
                    " rootFocusable=${rootView?.isFocusable} barVis=${controlBar?.visibility}"
            )
        }
        // 调试快照：手机浏览器打开 http://盒子IP:9978/debug 就能看到这份状态（界面零改动）
        MpvDebug.snapshot = { mpvDebugLine() }
        // 版本戳：/debug 里能确认装的到底是哪一包，避免「以为装了其实没装」
        // ★build 标记 = 本包的身份证。贴 /debug 时看这一行的 build 值就能判断
        //   装上的到底是不是这一版：tk4 = 画面层已脱离 PlayerView。
        //   仍是 tk3 说明包没覆盖到（或没重新编译），此时任何"问题依旧"都不成立。
        // 恢复：对齐原版 VideoActivity（继承 BaseActivity）—— 窗口同样挂壁纸，播放器自身黑底压住。
        // 之前为了压掉「四周白条」临时改成纯黑，属于主动偏离原版，按用户要求恢复。
        // 全屏靠主题的 windowFullscreen + 原版那套 systemUiVisibility（见 applyImmersive），
        // 不再额外下发窗口 flag —— 那两条 flag 会让窗口反复 relayout，正是黑屏的来源。
        // ★播放页不铺壁纸。原版这里是「小窗 + 详情」结构，壁纸在画面四周可见；
        //   我们是全屏画面，壁纸 100% 被画面盖住，唯一实际作用是**把窗口背景变成一张不透明 drawable** ——
        //   SurfaceView 靠窗口挖洞露画面，窗口不透明就等于把洞堵死。视觉无收益、风险全留下。
        //   其它页面（首页/详情/收藏/历史）继续铺壁纸，那里看得到。
        runCatching { window.setBackgroundDrawable(null) }
            .onFailure { MpvDebug.log("[boot] ★窗口背景置空失败：$it") }

        vodId = intent.getStringExtra("vod_id") ?: ""
        vodName = intent.getStringExtra("vod_name") ?: ""
        vodPic = intent.getStringExtra("vod_pic") ?: ""
        flag = intent.getStringExtra("flag") ?: ""
        epUrl = intent.getStringExtra("ep_url") ?: ""
        invalidateHistoryKey()
        directUrl = intent.getStringExtra("direct_url") ?: ""
        pushMode = intent.getBooleanExtra("push_mode", false)
        // 直播频道与手机推送写入历史时，vod_id 存的就是播放地址本身（原版同样如此）。
        // 从历史点回来时不会有 direct_url，这里补认一次，否则会去站点详情接口查一个 URL，必然失败。
        if (directUrl.isBlank() && (looksLikeMedia(vodId) || ThunderPlay.match(vodId))) directUrl = vodId
        siteKey = SubscriptionStore(this).currentKey() ?: ""

        session = PlaybackSession(
            exoFactory = { null },                        // 实例由 Activity 自己建，建好交给会话
            mpvProvider = { mpv },
            isMpvKernel = { isMpvMode() },
            bindSurfaceToExo = { p -> videoHost?.takeExo(p) },
            events = object : PlaybackSession.Events {
                override fun onState(state: PlaybackSession.State) = Unit
                override fun onError(message: String) = Unit
                override fun onEnded() = Unit
            }
        )
        session.cueSink = { group -> videoHost?.subtitleView?.setCues(group.cues) }
        session.setExoBuilder { h -> rebuildPlayerInternal(h) }
        // 这三处是"一处不中，整页静默"的关键路径，失败必须出声：
        // 之前一律 runCatching 吞掉，画面层没接上时 videoHost=null，表现就是黑屏 + 按键全无反应，
        // 而日志里一个字都没有 —— 这正是"改了没效果、看不出接线断没断"的来源。
        videoHost = runCatching {
            VideoHost(
                findViewById(R.id.videoFrame),
                findViewById(R.id.videoSurface),
                findViewById(R.id.subtitleView)
            )
        }.onFailure {
            MpvDebug.log("[boot] ★画面层构建失败，本页将黑屏且按键无反馈：" +
                "videoFrame=${findViewById<View>(R.id.videoFrame)?.javaClass?.simpleName} " +
                "videoSurface=${findViewById<View>(R.id.videoSurface)?.javaClass?.simpleName} " +
                "subtitle=${findViewById<View>(R.id.subtitleView)?.javaClass?.simpleName} err=$it")
        }.getOrNull()
        // 版本戳：/debug 里能确认装的到底是哪一包，避免「以为装了其实没装」。
        // ★必须打在 videoHost 赋值之后：以前它写在前面，host 恒为 null，把「画面层是否接上」
        //   这个最该看的信号变成了永远读不出来的假值。
        MpvDebug.log(
            "[boot] build=tk9 layer=aspect+surface+subtitle sdk=${android.os.Build.VERSION.SDK_INT}" +
                " 档位=${MpvDebug.experiment} host=${if (videoHost != null) "ok" else "null"}"
        )

        enableKeepContent()
        /**
         * keep_content_on_player_reset 不在这里设，保持 media3 默认（false），与点播场景一致。
         *
         * 原版 styles.xml 明确分了两档：
         *   Player.Vod  -> keep_content_on_player_reset=false
         *   Player.Live -> keep_content_on_player_reset=true
         * 点播要 false：换集时旧片画面必须清掉，否则上一集最后一帧会挂在新集起播前，
         * 看起来像卡住了；期间由 progress 转圈负责占位（见 showPlayProgress）。
         * 直播才需要 true，因为切台时希望画面连续、不闪。
         *
         * 之前这里设过 true，是错的：既偏离原版，又只在 rebuildPlayer（切线路/切内核，
         * 会 release 掉 player）这条路径上生效，正常换集走 applyMediaSource 反而用不到。
         */
        // 画面缩放（对齐原版 VideoActivity.getScale：本片记录优先，-1 才回落全局 Setting.getScale）
        scaleIdx = HistoryStore(this).scaleOf(historyKey)
            .takeIf { it >= 0 } ?: Settings(this).playerScale
        scaleIdx = scaleIdx.coerceIn(0, scaleModes.size - 1)
        videoHost?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale)?.text = scaleNames[scaleIdx]
        // mpv 内核下不必先建 EXO 实例：T1 只有 2GB 内存，两套解码器并存很吃紧。
        // 切回 EXO 时 rebuildPlayer() 会补建，所以这里可以省。
        if (!isMpvMode()) {
            player = ExoPlayer.Builder(this).build().also { p ->
                // 对齐原版 Players.java:155：setAudioAttributes(AudioAttributes.DEFAULT, !isPlayWithOthers())
                // 原版默认 play_with_others=false → 第二参数 true = 请求音频焦点。
                // 少了这一步，详情页小窗和播放页互不压制，就是「两个不同的声音」。
                p.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                session.adoptExo(p)
            }
            applyCaptionStyle()
            attachPlayerListeners()
        } else {
            // mpv 内核：先把原生实例建好，按键（暂停/快进）从第一秒起就有效。
            // 画面层这里先摆出来，真正的 play(url) 在地址解析完之后由 startMpv 触发。
            // ★路线 A（对齐 WebHomeTV 5.6）：mpv 与 EXO 共用 PlayerView 那一块 SurfaceView。
            // 播放页从此只有一套画面层，切内核 = 换内核、不换视图、不换 window layer。
            // 画面层里已不存在任何"盖黑"视图，onCreate 不再需要动快门
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
                    MpvDebug.log("[mpv] 首帧后建实例（绑定 PlayerView 的 SurfaceView，按键已可用）")
                }
            }
        }
        findViewById<TextView>(R.id.playTitle).text = vodName.ifBlank { "播放中" }
        // 顶部标题条也用 alpha 表显隐：它的 GONE↔VISIBLE 会带来一次 layout pass
        findViewById<View>(R.id.playTitle)?.let { it.visibility = View.VISIBLE; it.alpha = 0f }

        applyImmersive()
        // 窗口形态实验档（3/4），非 3/4 时什么都不做
        applyWindowShapeExperiment()
        // 原版播放页没有返回按钮（VideoActivity 靠系统返回键），这里同样不摆
        // 控制条按钮全部绑定（结构照原版 view_control_vod.xml）
        bindControlBar()
        videoHost?.post { setupSharedSurface() }
        uiHandler.postDelayed(snapTick, 1000)
        // ★共用画面层的生命周期：这条以前只在 mpv 分支里打（[mpv] surfaceCreated…），
        // 用 EXO 时完全看不见 surface 有没有被重建。画面黑一下如果是 surface 重建，
        // 这一行必然出现；如果不出现，黑就与 surface 无关，方向直接分叉。
        videoHost?.surfaceView?.holder?.addCallback(object :
            android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                surfGen++
                // ★色深能力恢复：API25 的 SurfaceView 默认是 RGB_565（16bit），
                //   之前我们是在播放中途 setFormat(RGBA_8888)，等于向 SurfaceFlinger
                //   申请换一整套 buffer（队列重建），那才是"动画面层拓扑"。
                //   正确做法是**在 surface 刚创建、还没有任何 buffer 的时候**设一次：
                //   之后分配的 buffer 天生就是 8888，播放中再也不用碰。
                //   8bit 链路靠它拿满 24bit；10bit 由 mpv 的离屏格式（RGBA10A2）负责。
                // ★不在这里 setFormat。
                //   SurfaceHolder.setFormat 在 surface 已经创建的情况下会让系统重分配 buffer，
                //   老 ROM 上等于把这块 surface 重建一次（EXO 解码器跟着重启、mpv 要重挂 EGL）。
                //   稳定优先：先保持官方默认 PixelFormat，色深另想办法（EXO 输出侧协商，不动 surface）。
                lastSurfCreateAt = android.os.SystemClock.uptimeMillis()
                val posNow = runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
                val playingNow = runCatching { player?.isPlaying }.getOrNull()
                MpvDebug.log("[sv2] created ${h.surfaceFrame.width()}x${h.surfaceFrame.height()} gen=$surfGen " +
                    "kernel=${if (isMpvMode()) "mpv" else "exo"} startGen=$startGen pos=${posNow}ms playing=$playingNow mpvPos=${mpvPos}ms")
                // 这里原本调一个"预热按键冷路径"的函数：读一次 SP、读一次 insets、拼一条日志串。
                // 它不改变任何状态，只在起播那一拍给主线程多压三件事，而那一拍正是
                // 第一次按键最容易被挤掉的时刻。已删。
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {
                MpvDebug.log("[sv2] changed ${w}x$hh fmt=$f kernel=${if (isMpvMode()) "mpv" else "exo"}")
            }
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                MpvDebug.log("[sv2] destroyed kernel=${if (isMpvMode()) "mpv" else "exo"}")
            }
        })
        // 首次呼出控制条会触发一次窗口重排，这家 ROM 上这一次要重建画面层的抠洞，表现为
        // 「第一次按菜单黑几秒、之后完全正常」。
        // ★这里以前有一段"用 alpha=0 预演一次显隐"的预热，已删：那种预热自己会触发收尾，
        //   收尾时把用户刚呼出的条也收掉，现场表现就是要按两次。现在起播前只用"加载中"
        //   把这段时间如实表达出来（不亮转圈的话，用户按菜单正好赶上这段，看到的是"按菜单把画面按黑了"，
        //   而实际是还没出帧）。
        showPlayProgress("进页面解析")
        // ★输入环境探针（只打一次，几乎零开销）。
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
        val marks0 = HistoryStore(this).marks(historyKey, epUrl)
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
            speedBtn.text = "快进 3x（松开恢复）"
            if (isMpvMode()) mpv?.setSpeed(3f) else player?.setPlaybackSpeed(3f)
            true
        }
        // 缩放档位循环（对齐原项目 onScale/setScale）
        findViewById<TextView>(R.id.btnScale).setOnClickListener { toggleScale() }
        // 上一集/下一集（对齐原项目 control.prev/next → checkPrev/checkNext）
        findViewById<View>(R.id.btnPrev).setOnClickListener { switchEpisode(-1) }
        findViewById<View>(R.id.btnPlayPause).setOnClickListener { togglePlayPause() }
        bindTimeBarScrub()
        findViewById<View>(R.id.btnNext).setOnClickListener { switchEpisode(1) }
        // 清晰度切换（对齐原项目 QualityAdapter/setQualityActivated）
        findViewById<View>(R.id.btnQuality).setOnClickListener { toggleQuality() }

        // 原版 VideoActivity:1269-1271 在起播前就把 History 建好（createHistory / updateHistory），
        // 不依赖抖源解析成功。之前只在各条「解析成功」分支里记，解析慢或中途退出就漏记，
        // 「看过的片子历史里没有」就是这么来的。
        // ★条件放宽到与 HistoryStore.addHistory 内部 key 规则一致（vodId 优先、退回片名）。
        //   原来要求 vodName 也非空：从历史/收藏/推送进来的入口常常只带 vodId，
        //   于是这部片从来没被写进历史 —— 现场就是 row=false 但 total=9。
        if (historyKey.isNotBlank()) recordHistory()

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

    /** 拉详情（取线路列表）→ 解析指定线路/集播放 */
    private fun fetchAndPlay(vid: String, fl: String, ep: String) {
        var playFlag = fl
        var playEp = ep
        runCatching {
            val store = SubscriptionStore(this)
            val site = store.sites().firstOrNull { it.key == siteKey }
            val detail = if (site?.isCsp() == true) {
                CspLoader.detail(this, site, vid)
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

    /** 调用引擎 player 接口解析出播放地址；csp_ 源走壳侧并带 header；失败进入自动换源重试链 */
    private fun resolvePlay(fl: String, ep: String) {
        // 解析要发网络请求（引擎 player / csp 源），这段到 ExoPlayer 第一次状态回调之间
        // 播放器还没接管，shutter 盖着就是纯黑。原版同样的空档是 progressLayout.showProgress()，
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
            val store = SubscriptionStore(this)
            val site = store.sites().firstOrNull { it.key == siteKey }
            val play = if (site?.isCsp() == true) {
                CspLoader.player(this, site, fl, if (ep.isNotBlank()) ep else vodId)
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
            // 原版 Result.jx：接口明确要求走外部解析（VideoActivity:568 的第二个条件）
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
                ThunderPlay.ensureInit(this)
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
            // 我们之前直接判失败，等于把 24 个可用的 WebView 解析源全扔了。
            // ★只在**确实没拿到地址**时才展示，不能因为"站点没配 playUrl"就打断本来能播的直链。
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
                runOnUiThread { Toast.makeText(this, "荐片 P2P 起播失败", Toast.LENGTH_LONG).show() }
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
            val local = JianPianPlay.fetch(this, raw)
            Log.e("XingHuiTV", "[jpa] ${raw.take(50)} → ${local.orEmpty().take(80)}")
            return local
        }
        return UrlConvert.convert(raw)
    }

    /** 判断地址是否已经是可直接播放的媒体直链 */
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
                val tv = android.widget.TextView(this).apply {
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
            Toast.makeText(this, "本站需要解析，请用遥控器选择一个解析源", Toast.LENGTH_LONG).show()
        }
    }

    /** 按解析源类型分流执行（type0 走嗅探，1/3/4 走裸露 HTTP / jar 反射） */
    private fun runParseItem(item: ParseSources.Item) {
        Log.e("XingHuiTV", "[play] 解析源 ${item.name} type=${item.type}")
        runOnUiThread { Toast.makeText(this, "正在尝试解析源：${item.name}", Toast.LENGTH_SHORT).show() }
        val mergedHeaders = parseHdrs + item.headers
        when (item.type) {
            0 -> startSniff(item.url + parseEpRaw, mergedHeaders, item.click)
            else -> Thread {
                val hit = ParseJob.run(item, parseEpRaw, flag)
                runOnUiThread {
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

    /** 对齐原版 checkParse：当前解析源失败就换下一个，全部失败才报错 */
    private fun tryNextParse() {
        val next = parseIdx + 1
        if (parseIdx < 0 || next >= parseList.size) {
            Log.e("XingHuiTV", "[play] 解析源全部失败（共 ${parseList.size} 个）")
            parseIdx = -1
            onPlayFail("解析源全部失败[$flag]")
            return
        }
        parseIdx = next
        runOnUiThread {
            Toast.makeText(this, "换下一个解析源：${parseList[next].name}", Toast.LENGTH_SHORT).show()
        }
        runParseItem(parseList[next])
    }

    /**
     * type=0 嗅探（对齐原版 ParseJob case 0 → CustomWebView）。
     * 打开 解析页地址 = playUrl + 真实地址，从 WebView 的网络请求里截获 m3u8/mp4。
     */
    private fun startSniff(target: String, hdrs: Map<String, String>, parseClick: String = "") {
        sniffer?.cancel()
        sniffer = SniffWebView(
            this,
            onFound = { u, h ->
                runOnUiThread {
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
        val curSite = SubscriptionStore(this).sites().firstOrNull { it.key == siteKey }
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
        // 对齐原版 SiteViewModel:197 的判定顺序：
        //   result.setParse(Sniffer.isVideoFormat(url) && playUrl.isEmpty() ? 0 : 1)
        // 即「先按订阅 rules 的三层规则判是不是视频」，命中就不该再弹嗅探。
        // 之前只用后缀判断，导致火山/抖音/农民/七新这类白名单地址被误判成解析页，
        // 白白弹一次全屏 WebView。这里把规则判定提到前面，后缀兜底。
        if (Sniffer.isVideoFormat(u)) return true
        // 后缀兜底必须锚定在路径结尾：原版用 contains 时，`/play/x.ts.html?u=` 这种会被误判成
        // 媒体直链，进而跳过嗅探直接送播放器，结果必然黑屏。带 token 的地址先砍掉 query。
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
    /** 加载层（转圈）是否还亮着 —— 也就是"画面还没出来" */
    private var progressShown = false

    private fun showPlayProgress(reason: String = "其它") {
        progressShown = true
        // 转圈是谁点亮的 —— 这是区分「EXO 真的重新缓冲」与「我们自己误亮」的唯一依据
        MpvDebug.log(
            "[prog] 转圈 显示 来源=$reason state=${runCatching { player?.playbackState }.getOrNull()}" +
                " playing=${runCatching { player?.isPlaying }.getOrNull()} pos=${runCatching { player?.currentPosition }.getOrNull()}" +
                " surfGen=$surfGen"
        )
        runOnUiThread {
            val v = findViewById<View>(R.id.playProgress)
            v?.alpha = 1f
            v?.visibility = View.VISIBLE
        }
    }

    private fun hidePlayProgress() {
        progressShown = false
        runOnUiThread { findViewById<View>(R.id.playProgress)?.visibility = View.GONE }
    }

    /**
     * 是否还在"起播前"（画面没出来）。
     *
     * 只判 contentReady() 不够：EXO 一 prepare 就有 duration，`ready` 立刻变 true，
     * 但那一刻还在 BUFFERING、屏幕上只有转圈 —— 而用户恰恰是在这段里按 OK 的。
     * 所以再加一条"转圈还亮着"。
     */
    // ★判据只用"内核里有没有内容"，不再把 progressShown 掺进来。
    //   掺进来的后果：EXO 每次 seek 都会进 BUFFERING → showPlayProgress() → preStart() 变 true →
    //   这一段时间按 OK 只记 pendingPauseIntent、永不 toggle —— 也就是「刚快进完按暂停没反应」。
    //   BUFFERING 只是缓冲，不是"起播前"，暂停/继续在这个阶段本来就该有效。
    private fun preStart(): Boolean = !contentReady()

    /** 播放/解析失败 → 先切下一条线路，再自动换站点；都失败才停止（对齐原项目 startFlow→checkFlag→checkSearch） */
    private fun onPlayFail(msg: String) {
        hidePlayProgress()
        if (tryAutoSwitch()) return
        if (siteSwitchCount < MAX_SITE_SWITCH && autoSwitchSite()) return
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun tryAutoSwitch(): Boolean {
        val next = nextLineIndex()
        if (next == null) return false
        val line = vodLines[next]
        Thread { resolvePlay(line.name, line.episodes.firstOrNull()?.second ?: vodId) }.start()
        return true
    }

    /** 当前线路的后一条；从当前线路下一项往后走，不循环回自身；无多线路/到底返回 null */
    private fun nextLineIndex(): Int? {
        if (vodLines.isEmpty()) return null
        val cur = vodLines.indexOfFirst { it.name == flag }
        val start = if (cur < 0) 0 else cur + 1
        return if (start < vodLines.size) start else null
    }

    /** 自动换站：线路走完后，用片名搜索其他站点同名影片并切换（对齐原项目 checkSearch→nextSite） */
    private fun autoSwitchSite(): Boolean {
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
        runOnUiThread {
            Toast.makeText(this, "自动切换到站点[${target.source}]，尝试播放《${target.vodName}》", Toast.LENGTH_LONG).show()
        }
        Thread { fetchAndPlay(target.vodId, "", "") }.start()
        return true
    }

    /** 清晰度：点击循环档位（对齐原项目 setQualityActivated） */
    private fun toggleQuality() {
        if (qualities.size < 2) return
        if (isMpvMode()) {
            Toast.makeText(this, "mpv 内核下清晰度由片源线路决定，请用「选集/换源」切换", Toast.LENGTH_SHORT).show()
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

    /** 清晰度按钮：多清晰度才显示，单清晰度隐藏 */
    private fun updateQualityBtn() {
        val btn = findViewById<TextView>(R.id.btnQuality)
        if (qualities.size > 1) {
            btn.visibility = View.VISIBLE
            btn.text = "清晰度 ${qualities[qualityIdx].first}"
        } else {
            btn.visibility = View.GONE
        }
    }

    /** 播放地址请求头；取播放接口返回的 Result.header（UA/Referer/Cookie 等） */
    private var activeHeaders: Map<String, String> = emptyMap()

    /** 字幕轨；取播放接口返回的 Result.subs，装配成 ExoPlayer 的 SubtitleConfiguration */
    private var activeSubs: List<MediaItem.SubtitleConfiguration> = emptyList()

    /** 待跳转的续播位置（毫秒）；播放器就绪后消费掉 */
    private var pendingSeek: Long = 0

    /**
     * 起播前按下 ←/→ 累积的位移（毫秒，可为负）。
     * 解析期 dur=0，没有任何绝对位置可算，旧实现把位移夹成 0 再 seekTo(0)，
     * 结果就是"按了确实有提示、画面一动不动"，用户判定为按键无效。
     * 这里把位移留着，起播那一刻叠加到续播点上再跳。
     */


    /** seek 下发时刻，用于量化"seek 到画面恢复播放"停了多久 */
    private var lastSeekCommitAt = 0L

    /**
     * 本次起播将要落到的那一点（续播点）。起播前按快进时用它当基准。
     *
     * 用例：片子的续播点在 1700s，用户刚进页面就按 →。
     * 若基准取 currentPosition（未起播恒为 0），目标就成了 10s，
     * 同时 playUrl 里"用户意图优先"会把续播点清零 —— 一按快进就回片头。
     */
    private var startPosMs: Long = 0

    /** 上次落盘的位置，避免重复写同一进度把 SharedPreferences 打爆 */
    private var lastSavedPos: Long = 0

    /**
     * ★同集重新起播的位置纪律。
     *   现场现象：播到 3 分钟退出 → 再进播到 1 分钟（合计 4 分钟）→ 第一次按菜单跳回 3 分钟。
     *   这个 3 分钟恰好是**上一次会话退出时落盘的续播点**。只要本次会话里再发生一次 playUrl
     *   （换内核、重建播放器、任何重建路径），旧的 at 计算会回头读磁盘上的 marks，画面被拽回旧点。
     *   正确语义：同一地址在本会话内再次起播，起点用**当前真实位置**；只有地址变了（换集/换源）才读历史。
     */
    private var lastPlayedUrl = ""

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 历史落盘专用单线程：顺序保证与主线程解耦（退场那条仍走同步） */
    private val saveExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "xh-history-save").apply { isDaemon = true }
        }
    private val saveRunnable = object : Runnable {
        override fun run() {
            // 这是每 15 秒的周期落盘，不是返回键。以前它和退场共用 "[back] 保存" 这个标签，
            // 真机日志里就会连着出现好几条 "[back] 保存"却没有任何 "[key] back 退出播放页"，
            // 看起来像"返回键被按了三次还没退出"。标签必须分开。
            savePosition(reason = "周期")
            saveHandler.postDelayed(this, 15_000)
        }
    }

    /** 统一挂播放器监听：onCreate 和 rebuildPlayer 都要挂，避免两处逻辑漂移 */
    private fun attachPlayerListeners() {
        // 字幕 cue 由 PlaybackSession 统一转发给 SubtitleView（见 session.cueSink）
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (directUrl.isNotBlank()) return // 直播直链不自动换线路
                onPlayFail("播放出错：${error.errorCodeName}")
            }

            /** 就绪后跳续播位置：太靠近片尾（不足 5 秒）就当作看完了，从头播 */
            override fun onPlaybackStateChanged(state: Int) {
                // 对齐原版 VideoActivity.onPlayerEvent：
                //   STATE_BUFFERING -> showProgress()
                //   STATE_READY     -> hideProgress()
                // 原版那 3 秒里显示的是居中的转圈，不是黑屏；我们之前没接这一段，
                // 所以 shutter 盖着的时候看起来就是纯黑。
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
                        uiHandler.postDelayed(autoHide, 3000)
                    }
                }
                // 起播前用户按过快进：这里才是真正能定位的时刻，先补跳它，再谈续播点
                if (pendingJumpMs >= 0) {
                    val at = pendingJumpMs
                    pendingJumpMs = -1
                    p.seekTo(at)
                    MpvDebug.log("[key] 起播前快进生效 → ${at}ms")
                }
                if (pendingSeek <= 0) return
                val target = pendingSeek
                pendingSeek = 0
                if (p.duration > 0 && target < p.duration - 5_000) p.seekTo(target)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // seek 会让 isPlaying 瞬时变 false（EXO 重建缓冲），那不是"用户暂停"。
                // 真机里每次 [key] 跳 之前都跟一条 保存[暂停]，纯属自造的无效写盘。
                if (seekingByKey) return
                // ★提交 seek 那一刻 seekingByKey 已经置回 false（否则下一次手势起不了手），
                //   所以上面那句拦不住 seekTo 触发的那一发 false。用提交时间兜住这个窗口：
                //   现场日志里每按一次 ←/→ 就紧跟一条 [hist] 保存[暂停] bg=true，
                //   等于每按一下都全量写一遍 200 条历史 —— 卡顿和"按键不灵"的体感就是从这来的。
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
        })
    }

    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 15_000)
    }

    /** 落盘当前播放位置；异常值/片尾不记（播完应从头看，不该一跳就跳结尾） */
    /**
     * 退出/切后台时落盘进度。
     *
     * ★force=true 用于返回键退场那一刻：忽略 lastSavedPos 去重，一定要写一次。
     * ★只有真的写成功才更新 lastSavedPos。旧实现是先赋值再写，而
     *   HistoryStore.setPosition 在"历史里没有这一行"时是**静默 return**——
     *   于是写入失败也会被标记成"已保存"，之后同一位置永远不再重试，
     *   详情页小窗读不到位置就只能从片头开始。这就是「按过菜单再返回，小窗回片头」的数据侧原因。
     */
    private fun savePosition(force: Boolean = false, reason: String = "自动") {
        // 退了就不再写：返回键那一刻已经 force 保存过一次（位置最准），
        // 之后 onPause / onDestroy 还会各调一次，而 pause 是异步的 —— 位置还在往后走几毫秒，
        // 于是同一部片会被"最后写一次"覆盖三遍（真机日志里 26647 → 26686 → 26691）。
        // 位置多跳几毫秒无害，但会让「最后一次到底存在哪」变得不可判读，日志也被刷屏。
        if (leaving && !force) return
        val pos = if (isMpvMode()) mpvPos else runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        if (pos <= 0) return
        if (!force && pos == lastSavedPos) return
        val dur = if (isMpvMode()) mpvDur else runCatching { player?.duration ?: 0L }.getOrDefault(0L)
        if (dur > 0 && pos >= dur - 10_000) return
        if (Settings(this).isIncognito) return
        if (historyKey.isBlank()) {
            MpvDebug.log("[hist] 保存[$reason]跳过：historyKey 为空 pos=$pos")
            return
        }
        val key = historyKey
        val name = vodName
        val pic = vodPic
        val fl = flag
        val ep = epUrl
        // ★退场/onPause 必须同步：详情页小窗就是靠这一条位置续播的，晚一个 post 就可能读到旧值。
        // ★周期（15s）与暂停这两条路径改到后台单线程：真机日志里 22:24:09 按菜单那一秒
        //   同时触发「暂停落盘」，而 addHistory + setPosition 每次都把 200 条历史全量 JSON 序列化
        //   再 apply，落在主线程就是那一秒 42 帧（掉 18 帧、约 300ms）。
        //   落盘和呼条抢同一个主线程帧，本身就是自造的卡顿。
        if (force) {
            // ★必须先把排队的后台写排空再同步写，否则顺序会反过来：
            //   后台那条带着更早的 pos（真机 22:41:06 bg pos=416924），退场同步写 417876，
            //   若后台任务晚一点才跑，就会把退场写好的准确位置**覆盖回旧值**。
            //   表现是"下次进来从小一点的位置续播"，而且很难复现（取决于线程调度）。
            runCatching { saveExecutor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            HistoryStore(this).addHistory(key, name, pic, "", fl, ep)
            HistoryStore(this).setPosition(key, ep, pos)
        } else {
            // executor 在 onDestroy 已 shutdown 后 execute 会抛 RejectedExecutionException，
            // 这里包住：宁可这一次后台写丢掉（退场那条是同步写，不会丢），也不要在 onPause 崩。
            runCatching {
                saveExecutor.execute {
                    runCatching {
                        HistoryStore(this).addHistory(key, name, pic, "", fl, ep)
                        HistoryStore(this).setPosition(key, ep, pos)
                    }
                }
            }
        }
        lastSavedPos = pos
        // ★probe 会把整份历史 JSONArray 重新解析一遍（200 条），而它以前挂在**每次**保存上 ——
        //   包括每 15 秒的周期保存。落盘本身已经在后台线程了，但这行是同步拼在主线程上的，
        //   等于每 15 秒白解析一次全量历史。现在只在必需要查的时候（force：退场/onPause/onDestroy）才做。
        MpvDebug.log(
            "[hist] 保存[$reason] pos=$pos key=${historyKey.take(24)} " +
                (if (force) HistoryStore(this).probe(historyKey, epUrl) else "period=true") +
                (if (force) " sync=true" else " bg=true")
        )
    }

    /**
     * 对齐原版 Players.java:605：headers 没带 UA 时补上用户设置的 UA。
     * 注释见调用点——media3 默认 UA 是 "ExoPlayerLib/1.3.1"，多数采集站/CDN 见它直接 403。
     * 抽成函数是为了让切档路径（toggleQuality）复用同一份判定，而不是各自写一遍。
     */
    private fun withUa(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", true) }) headers
        else Settings(this).ua.takeIf { it.isNotBlank() }?.let { headers + ("User-Agent" to it) } ?: headers

    private fun playUrl(url: String, headers: Map<String, String> = emptyMap()) {
        if (url.isBlank()) {
            Toast.makeText(this, "未解析到播放地址", Toast.LENGTH_LONG).show()
            return
        }
        // 对齐原版 Players.java:605：
        //   for (Map.Entry<String, String> header : headers.entrySet())
        //       if (HttpHeaders.USER_AGENT.equalsIgnoreCase(header.getKey())) return headers;
        //   headers.put(HttpHeaders.USER_AGENT, Setting.getUa());
        // 播放接口通常只返回 Referer，没有 UA。media3 默认 UA 是 "ExoPlayerLib/1.3.1"，
        // 多数采集站/CDN 见到这个标识会直接 403，表现为"能进详情、播放器一直转圈"。
        // 原版走 OkHttpDataSource，继承的是用户设置的 UA，所以不会踩这个坑。
        val effHeaders = withUa(headers)
        activeUrl = url
        startGen++
        logVideoCodecOnce()
        // 片头/片尾按片记忆（对齐原版 History.getOpening/getEnding）
        val marks = HistoryStore(this).marks(historyKey, epUrl)
        openingMs = marks[0]
        endingMs = marks[1]
        endingDone = false
        updateOpeningText()
        updateEndingText()
        // ★起播点只在这里算一次，三个来源按优先级取一个：
        //   显式指定（切内核/重建/换解码器）> 用户起播前按的快进 > 片头标记/历史续播点。
        //   以前是"先写成续播点、再被 pendingJumpMs 改成 0、再补跳"，三次改写导致
        //   "先出第一帧再跳回当前位置"；现在一次定下来，装载时就带着位置。
        // ★来源必须一起记：以前这行只写"续播点=xxx"，看不出这个值是三个入口里谁定的，
        //   于是每次"莫名跳回旧位置"都只能猜。现在把来源类别和调用点行号一并写出。
        val sameEpisodeAgain = url == lastPlayedUrl
        val atSrc = when {
            resumeOverride >= 0 -> "强制"
            pendingJumpMs >= 0 -> "快进"
            sameEpisodeAgain -> "续接当前"
            else -> "历史"
        }
        val at = when {
            resumeOverride >= 0 -> resumeOverride
            pendingJumpMs >= 0 -> pendingJumpMs
            // ★同集再次起播：用当前真实位置，绝不回头读磁盘上的旧续播点
            sameEpisodeAgain -> currentPosMs()
            else -> maxOf(openingMs, marks[2])
        }
        lastPlayedUrl = url
        val atFrom = runCatching {
            Throwable().stackTrace.getOrNull(2)?.let { "${it.fileName}:${it.lineNumber}" }
        }.getOrNull()
        val atArg = when {
            resumeOverride >= 0 -> "resumeOverride=$resumeOverride"
            pendingJumpMs >= 0 -> "pendingJumpMs=$pendingJumpMs"
            else -> "hist=${marks[2]}"
        }
        resumeOverride = -1
        pendingJumpMs = -1
        pendingSeek = at
        startPosMs = at
        lastSavedPos = 0
        // ★这条日志以前打在 at 计算之前，打的是还没赋值的 pendingSeek（永远是 0），
        //   把整条排查线带偏成"续播点没落盘"。现在打在算完之后，并把三个输入一并写出。
        MpvDebug.log(
            "[play] 起播 gen=$startGen 来源=$atSrc($atArg) 调用=$atFrom url=${url.take(48)} 续播点=${at}ms " +
                "(hist=${marks[2]} opening=${openingMs} ending=${endingMs}) " +
                HistoryStore(this).probe(historyKey, epUrl)
        )
        // mpv 内核：本进程内 libmpv（内嵌），播放页控制条直接控它
        if (isMpvMode()) {
            startMpv(url, effHeaders, at)
            return
        }
        // header 与当前播放器不一致时重建（播放接口返回的 UA/Referer 需注入数据源）；
        // player 为 null 时（mpv 内核对下我们不再预建 EXO）也必须补建，否则这条分支什么都不会播
        if (player == null || effHeaders != activeHeaders) {
            rebuildPlayer(effHeaders)
        }
        val itemBuilder = MediaItem.Builder().setUri(url).setMediaId(url)
        // 字幕轨：对齐原版 ExoUtil.getMediaItem → setSubtitleConfigurations(getSubtitleConfigs(subs))
        if (activeSubs.isNotEmpty()) itemBuilder.setSubtitleConfigurations(activeSubs)
        // 起播前按过暂停：媒体项照样装载，但不自动播（与正常暂停的观感一致）
        val wantPlay = pendingPauseIntent != true
        pendingPauseIntent = null
        if (session.playExo(itemBuilder.build(), at, wantPlay)) {
            setKeepScreenOn(wantPlay)
            if (!wantPlay) showPlayCenter()
        } else {
            Toast.makeText(this, "该片源当前内核不支持", Toast.LENGTH_LONG).show()
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

    /** 字幕 MIME 推断，对齐原版 ExoUtil.getMimeType(String) */
    private fun mimeOfSub(url: String): String = when {
        url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
        url.endsWith(".ass", true) || url.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
        url.endsWith(".ttml", true) || url.endsWith(".xml", true) || url.endsWith(".dfxp", true) -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /** 重建播放器；headers 为空时用默认（普通源），非空时注入请求头（csp_ 源） */
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
                runCatching {
                    Toast.makeText(this, "该片源当前内核不支持（${e.javaClass.simpleName}）", Toast.LENGTH_LONG).show()
                }
            }
            false
        }
    }

    /** 对外语义不变：丢掉旧的、按新请求头重建一个（实例归会话） */
    private fun rebuildPlayer(headers: Map<String, String>) {
        activeHeaders = headers
        session.ensureExo(headers, forceRebuild = true)
        player = session.player
    }

    /** 纯构造：只建实例、装监听、绑画面，不动 ownership（会话调用它） */
    private fun rebuildPlayerInternal(headers: Map<String, String>): ExoPlayer? {
        activeHeaders = headers
        val builder = ExoPlayer.Builder(this)
        builder.setRenderersFactory(buildRenderersFactory())
        builder.setTrackSelector(buildTrackSelector())
        builder.setLoadControl(buildLoadControl())
        // 工厂必须无条件设：之前写成 `if (headers.isNotEmpty())`，
        // 没有请求头的源就拿不到超时、拿不到跨协议重定向，设了等于没设。
        val timeoutMs = Settings(this).networkTimeoutSec.coerceIn(5, 120) * 1000
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(timeoutMs)
            .setReadTimeoutMs(timeoutMs)
            // 允许 302 跳到另一个协议。默认关着时，http→https 这种重定向会直接抛
            // HttpDataSourceException，表现为某些源「起播就报错」而别的源正常。
            // Manifest 已声明 usesCleartextTraffic="true"，https→http 也不会被系统拦。
            .setAllowCrossProtocolRedirects(true)
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)
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
        // 对齐原版 MediaSourceFactory:90：原版数据源是 DefaultDataSource.Factory(http)，
        // 除 http 外还认 file:// / content:// / asset:// / data://。
        // 只用 DefaultHttpDataSource 时，任何没被 UrlConvert 转写的本地路径都会起不来
        // （表现为 EXO 放不了、mpv 能放）。包一层后不依赖调用方自觉。
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
        builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
        val built = builder.build().also {
            // 官方 Players.java:158 setHandleAudioBecomingNoisy(true)：
            // HDMI / 蓝牙音频突然断开时自动暂停，而不是继续无声播放。
            it.setHandleAudioBecomingNoisy(true)
            // 同 onCreate：音频焦点必须请求（原版 Players.java:155 同一句）
            it.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            it.setPlaybackSpeed(speedSteps[speedIdx])
        }
        player = built
        session.adoptExo(built)
        // 玩家表面探针必须在绑定之后挂：media3 会在这时把 surface 交给播放器
        applyCaptionStyle()
        attachPlayerListeners()
        return built
    }

    /**
     * 渲染器工厂。
     *   1. 解码方式：0=软解（换成过滤硬件 codec 的选择器），1=硬解（默认）—— 对齐原版 Setting.getDecode
     *   2. setEnableDecoderFallback(true) —— 原版 NextRenderersFactory 构造里就开着这一条。
     *      硬解的 MediaCodec 初始化失败时（T1 上某些编码会），它会自动退到下一个候选解码器，
     *      而不是直接抛 ERROR_CODE_DECODER_INIT_FAILED 把整条播放打断。
     *      只开这个开关不引入任何 so，纯兜底。
     */
    private fun buildRenderersFactory(): androidx.media3.exoplayer.RenderersFactory {
        val factory = if (Settings(this).decodeMode == 0)
            SoftRenderersFactory(this)
        else
            androidx.media3.exoplayer.DefaultRenderersFactory(this)
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
        // 对齐原版 ExoUtil.buildLoadControl()：new DefaultLoadControl()，不做自定义。
        // 之前按设置项 exoBufferSec 编成 min=5s / max=10s，等于把缓冲上限从默认 50s 砍到 10s，
        // 弱网、长片、高码率下重缓冲明显变多 —— 这是我们自己造的负优化，已回滚。
        // 原版 Setting 里也有 exo_buffer，但原版 ExoUtil 没消费它；设置项保留（界面一致），
        // 只是不再驱动缓冲策略。
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
        val ts = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        ts.setParameters(
            ts.buildUponParameters()
                .setPreferredTextLanguage(java.util.Locale.getDefault().getISO3Language())
                .setForceHighestSupportedBitrate(true)
                .setTunnelingEnabled(Settings(this).tunnelMode == 1)
        )
        return ts
    }

    private fun recordHistory() {
        // 无痕模式：不写历史（对齐原版 Setting.isIncognito 的语义）
        if (Settings(this).isIncognito) return
        HistoryStore(this).addHistory(historyKey, vodName, vodPic, "", flag, epUrl)
    }

    /** 倍速：点击循环档位（对齐原项目 onSpeed()/addSpeed()） */
    private fun toggleSpeed() {
        speedIdx = (speedIdx + 1) % speedSteps.size
        applySpeed()
    }

    /** 缩放：点击循环档位（对齐原项目 onScale/setScale） */
    private fun toggleScale() {
        scaleIdx = (scaleIdx + 1) % scaleModes.size
        if (isMpvMode()) mpv?.applyScale(scaleIdx) else videoHost?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale).text = scaleNames[scaleIdx]
        // 对齐原版 VideoActivity.onScale：档位写进本片 History，不动全局 Setting
        if (!HistoryStore(this).setScaleOf(historyKey, scaleIdx)) Settings(this).playerScale = scaleIdx
    }

    // ===================== 内嵌 mpv 内核（libmpv + TextureView） =====================

    private var mpv: MpvPlayer? = null
    private var mpvPos = 0L
    /**
     * 全片时长唯一入口（毫秒，未知返回 0）。
     *
     * 之前各地各写一遍：mpv 侧读 mpvDur（由 mpv 桥 onDuration 赋值），EXO 侧读 player.duration。
     * 问题在于 mpvDur 只会被 mpv 桥写，EXO 模式下它**恒为 0** —— 诊断行 `dur=0ms` 就是这么来的，
     * 一旦某处漏了 isMpvMode() 分支就会拿 0 去算（片头/片尾判定直接误报"请播到片头附近"）。
     * 现在所有取值都走这里：
     *   - mpv：mpvDur 没拿到时回落到实时读内核 duration 属性（同名 durationMs()）
     *   - EXO：player.duration，TIME_UNSET（Long.MIN_VALUE）与负数一律折算成 0
     */
    private fun durNow(): Long = session.durationMs()

    private var mpvDur = 0L
    private var mpvLoaded = false
    /** mpv 在本页起不来时临时退回 EXO，不改用户设置 */
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

    /** 已经按返回键退场：之后不再补写位置（见 savePosition 开头） */
    private var leaving = false

    private fun handOver(to: String, how: String) {
        val from = surfaceOwner
        surfaceOwner = to
        MpvDebug.log("[surf] 画面层让渡 $from → $to（$how）")
    }

    /** 当前内核是不是 mpv（内嵌，不再依赖外部 app）；mpv 起播失败时本页临时回退 EXO */

    /**
     * 字幕样式 —— 原版 ExoUtil.applySubtitleStyle() 的等价实现。
     *   预设（默认）：白字 + 黑描边 + 透明底
     *   系统：跟随系统「字幕样式」设置（CaptioningManager.getUserStyle()）
     * 原版同时设了 setApplyEmbeddedStyles(!isCaption())：选「预设」时用 ASS 里自带样式，
     * 选「系统」时用系统样式覆盖内嵌样式。
     */
    private fun applyCaptionStyle() {
        val host = videoHost ?: return
        val sv = host.subtitleView
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
     * PlayerView 的 shutter 开关。
     *
     * media3 的 PlayerView 里有一个 exo_shutter（默认不透明黑），首帧渲染前挡着画面。
     * 但 mpv 不归 media3 管，它不会触发 onRenderedFirstFrame，shutter 会永远挡在前面 ——
     * 所以 mpv 模式下必须把它让开：背景透明 + 直接 GONE。
     * 切回 EXO 时恢复不透明黑，仍然让 media3 自己在首帧后隐藏它。
     */
    /**
     * 旧快门随 PlayerView 一起消失。画面层现在是
     * AspectRatioFrameLayout + SurfaceView，**里面没有任何会画黑的视图**。
     * 保留这个函数只为让历史调用点不必逐个删干净。
     */


    /**
     * 播放中永久释放快门。
     *
     * media3 的 exo_shutter 是一整块不透明黑，只要它被翻成 VISIBLE，画面区域立刻全黑
     * （控制条在它上层，所以看上去是"整屏黑、条还在"）。它的显隐由 media3 内部
     * updateShutter 逻辑决定，我们这边任何一次 setPlayer/setVisibility/布局都可能把它翻出来，
     * 而它翻出来之后再也不会自己退回去（EXO 只在 onRenderedFirstFrame 里关一次）。
     * 所以播放真正开始后，直接把它置透明 + GONE 并保持，起播前的遮挡交给自己的 playProgress。
     */

    /**
     * 永久把 exo_shutter 从视图树里摘掉。
     *
     * 只设透明+GONE 是不够的：media3 内部 updateShutter / closeShutter 会再次把它翻成 VISIBLE，
     * 而我们没有任何回调能拦（那是它自己那张表驱动的）。它一旦被翻出来，
     * 就是一块盖在 SurfaceView 上的不透明黑 —— **两个内核同时全黑**，
     * 直到下一次 onRenderedFirstFrame 或我们 1000ms 的进度时钟兜底才消失，
     * 表现就是"唤起菜单键黑一下，大概 1 秒"。
     * 摘出视图树后，它再 visibility=VISIBLE 也没有绘制效果。
     * 起播前的遮挡由我们自己的 playProgress 负责，本来就不依赖它。
     */

    /**
     * 黑闪取证：16ms 一拍，连续 1.1 秒，只在状态发生变化时打一行。
     * 目的是在"按菜单那一瞬间"抓到到底是哪个状态翻了 —— 快门 / 画面层 / 窗口 / 播放器。
     */
    private var flickLast = ""
    private fun armFlickSampler(tag: String) {
        flickLast = ""
        val total = 68
        for (i in 0 until total) {
            uiHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                val st = runCatching {
                    "host=${videoHost?.visibility} " +
                        "sv=${currentSurface()?.visibility}/${currentSurface()?.alpha} " +
                        "mpv=${mpv != null}/${mpv?.let { runCatching { it.isPausedActual() }.getOrNull() }} " +
                        "bar=${controlBar?.visibility} win=${window?.decorView?.windowVisibility} " +
                        "exo=${player?.isPlaying}"
                }.getOrElse { "?" }
                if (st != flickLast) {
                    flickLast = st
                    MpvDebug.log("[flick] $tag +${i * 16}ms $st")
                }
            }, (i * 16).toLong())
        }
    }

    /**
     * 打开官方接口 keepContentOnPlayerReset（media3 1.3.1 自带，不是分叉专有）。
     *
     * 官方 PlayerView.updateForCurrentTrackSelections()：
     *   if (player == null || tracks.isEmpty()) { if (!keepContentOnPlayerReset) closeShutter(); }
     *   if (isNewPlayer && !keepContentOnPlayerReset) closeShutter();
     * closeShutter() 就是把 exo_shutter 置 VISIBLE —— 一整块不透明黑。
     * 我们切内核时 `playerView.player = null` 正好命中“tracks 为空”，于是必然盖一块黑上去，
     * 直到 EXO 下一次 onRenderedFirstFrame 才打开。打开这个开关后，清空/替换播放器不关快门。
     */
    private fun enableKeepContent() {
        runCatching {
            // 已无 PlayerView，keep_content_on_player_reset 自然失效
            Unit
        }
    }

    /**
     * 已无快门可动：画面层不是 PlayerView 了。
     * 这里保留函数签名，调用点一行都不用改；将来若需要"遮挡窗口期"，
     * 只改这个函数的实现即可，不会再牵动播放器内部状态。
     */

    private fun isMpvMode(): Boolean =
        !mpvFallbackExo && PlayerManager.shouldUseMpv(this) &&
            android.os.Build.VERSION.SDK_INT >= 23   // 官方 libmpv 构建要求 API 23+

    /** 起播走 mpv：切显示层 → 建内核 → 下发地址与起始位置 */
    /** 文件还没加载完时用户快进的位置：先记下来，onLoaded 再补跳一次 */

    /**
     * 起播前按键意图（两个内核共用同一套）。
     *
     * 原版 VideoActivity 的按键不是直接打给播放器，而是先记在界面层再落到内核：
     *   onSeekTo(time) → mPlayers.seekTo(time) + showProgress() + onPlay()
     *   onPlay()       → mPlayers.play() + hideCenter()
     * 也就是「用户意图先成立，内核就绪再执行」。我们之前是直接 `player?.seekTo` / `mpv ?: return`，
     * 起播前这几秒既没有内核、也没有媒体项，按键全部打空 —— 表现为「刚进页面暂停/快进失效」。
     *
     * pendingPauseIntent: null = 未操作；true = 用户要求暂停；false = 用户要求继续
     * pendingJumpMs:      >=0 表示用户起播前按过快进，就绪后跳到这里
     */
    private var pendingPauseIntent: Boolean? = null
    private var pendingJumpMs = -1L

    /** 内容就绪（可以真正暂停/定位）的判断：EXO 看有没有时长，mpv 看文件有没有加载完 */
    private fun contentReady(): Boolean = session.hasContent()

    private fun unusedContentReady(): Boolean =
        if (isMpvMode()) mpvLoaded && mpv != null
        else runCatching {
            // ★不能用 duration > 0 当就绪判据。
            //   m3u8 / 切片源的 duration 在 media3 里可能整场都是 C.TIME_UNSET
            //   （= Long.MIN_VALUE），`> 0` 永远不成立 → 一直判成"没就绪" →
            //   togglePlayPause 的 EXO 分支被 `if (p != null && ready)` 挡掉，
            //   只记意图不下发 pause()/play()，表现就是"按 OK 暂停无效"，
            //   而且快进快退也会被记成"起播前意图"，要等起播后才补跳。
            //   就绪的正确判据是媒体项已 attach 且播放器离开了 IDLE（BUFFERING / READY 都算）。
            val p = player ?: return@runCatching false
            p.mediaItemCount > 0 && p.playbackState != androidx.media3.common.Player.STATE_IDLE
        }.getOrDefault(false)

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
        mpv?.let { return it }
        val p = MpvPlayer(this, surface).also { p ->
            p.onLoaded = {
                mpvLoaded = true
                // 起播完成：若控制条还开着，3 秒后自动收起。
                // 条显示时 OK 交给焦点按钮、左右移动焦点 —— 用户按"直接按 OK/快进"时
                // 全被条吃掉，现场日志里就是 kc=23 落在 text=EXO 的按钮上（还顺手切了内核）。
                uiHandler.post {
                    if (barShown()) {
                        uiHandler.removeCallbacks(autoHide)
                        uiHandler.postDelayed(autoHide, 3000)
                    }
                }
                // 注意：这里曾经在"出帧后 +100ms"再预演一次控制条显隐，已撤掉。
                // 那一下正好发生在首帧刚上屏的时刻，会给根布局（含 SurfaceView）再来两次重排，
                // 而真机回执证明它并没有治住"首次菜单黑"，等于拿播放画面去赌一个未证实的假设。
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
        // 这里用 release 而不是 stop：stop 只是把状态推到 IDLE，MediaCodec / Surface 还挂着，
        // 紧接着把 PlayerView 置 GONE 会让它的 Surface 被销毁，而播放器仍然持有那个句柄 ——
        // 这是从 EXO 热切 mpv 时黑屏、甚至进程直接挂掉的来源。
        // 彻底释放并置空，切回 EXO 时走 rebuildPlayer 重建，虽然慢一点但状态是干净的。
        // 顺序很重要：先把 PlayerView 与 EXO 解绑（media3 会把这块 surface 从旧播放器上 clear），
        // 再释放 EXO。反过来的话，release 期间 media3 还会去摸一块马上要被 mpv 接管的 surface。
        // ★官方 switchTargetView 的 javadoc 明确要求：先挂新的、再摘旧的，
        //   "without transitioning through a state where no surface is attached"。
        //   只有一块 surface 时做不到真的先挂后摘，但可以避免最坏的一步：
        //   不要用 setPlayer(null)（那会命中"tracks 为空 → closeShutter"，
        //   直接盖一整块不透明黑），而是只把这块 surface 从 EXO 身上 clear 掉，
        //   PlayerView 与 EXO 的绑定留着，release 时一起收。
        //   配合 setKeepContentOnPlayerReset(true)，清 surface 也不会关快门，画面保留最后一帧。
        runCatching {
            val sv = currentSurface() as? android.view.SurfaceView
        }
        // 顺序：先把这块 surface 从 EXO 身上摘掉，再释放播放器。
        // 反过来的话 release 期间 media3 还会去摸一块马上就要交给 mpv 的 surface。
        session.releaseExo()
        player = null
        handOver("mpv", "releaseExo 已解绑并释放，下一步 attach 给 mpv")
        // mpv 输出期间把 shutter 让开（不透明白 = 画面被它盖住）
        val surface = currentSurface()
        // 共用画面层是唯一前提，拿不到就本页退回 EXO（不能静默不播）
        if (surface == null) {
            MpvDebug.log("[mpv] 拿不到共用画面层（PlayerView 没有 surface view），本页退回 EXO")
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
        val startPaused = pendingPauseIntent == true
        // 意图只消费一次：留着的话下一次换集也会被按成暂停
        pendingPauseIntent = null
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

    /** 播放时保持常亮、暂停时让系统可以熄屏（对齐原版 VideoActivity 的 KEEP_SCREEN_ON 增删） */
    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 播完统一收口（对齐原版 checkEnded：循环激活重播本集，否则下一集） */
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
            if (Settings(this).flagMode == 0) switchEpisode(1)
        }
    }

    // ===================== 底部控制条（对齐原版 view_control_vod + CustomKeyDownVod） =====================

    private var controlBar: View? = null
    /**
     * 控制条"显示中"的唯一判定。
     * 控制条从进页面起就一直 VISIBLE，显隐只靠 alpha —— 见 hideControl() 里的长注释。
     */
    /** v 是否在 controlBar 这棵子树里（焦点是否还停在条上） */
    private fun isInsideBar(v: android.view.View?): Boolean {
        val bar = controlBar ?: return false
        var p: android.view.View? = v
        while (p != null) {
            if (p === bar) return true
            p = p.parent as? android.view.View
        }
        return false
    }

    private fun barShown(): Boolean {
        val b = controlBar ?: return false
        // 常驻 VISIBLE 之后，"显示中"只能按 alpha 判（同一个函数在 showControl/hideControl/按键
        // 三处都用，判据必须唯一，否则会出现"看着显示着、代码认为已隐藏"这种劈叉）
        return b.visibility == View.VISIBLE
    }
    /** 画面容器（根 FrameLayout）：照原版 @id/video 语义，可聚焦，控制条收起时焦点回到它 */
    private var rootView: View? = null
    // 进页面耗时打点：/debug 里直接看「onCreate→onResume→窗口焦点」各多少毫秒，
    // 用毫秒数据判断"第一下按键被吞"到底是 onCreate 重活、还是首帧后 WMS 焦点移交慢。
    private var tCreate = 0L
    private var tResume = 0L
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoHide = Runnable { hideControl() }

    // 片头/片尾（对齐原版 History.getOpening/getEnding，按片记忆）
    private var openingMs = 0L
    private var endingMs = 0L
    private var endingDone = false
    private var loopOn = false
    /** 切内核时用它覆盖续播位置（-1 = 按历史走） */
    private var resumeOverride = -1L
    // 最后一次起播的地址与请求头，供「硬解切换」重建播放器后重放
    private var activeUrl = ""

    /** 控制条 14 个按钮全部接上；未实现的能力按原版文字保留、点击给出说明 */
    private fun bindControlBar() {
        controlBar = findViewById(R.id.controlBar)
        // 档位 8：控制条从一开始就保持 VISIBLE，只用 alpha 显隐。
        // 目的是把"按菜单黑"这条现象里的两个变量拆开：
        //   a) GONE↔VISIBLE 触发的 requestLayout / 窗口挖洞区域重算
        //   b) 窗口里多了一个带 alpha 的图层（HWC 图层数变化 → GPU 合成回退）
        // 本档位只消除 a，b 仍然存在。若 8 不黑而 0 黑，a 就是根因；若 8 也黑，就是 b。
        // ★控制条从页面创建起就常驻 VISIBLE，只用 alpha 显隐（原来的诊断档位 8 现在就是默认行为）。
        //
        // 现场定性（21:59 / 22:00 两轮）：「只有第一次按菜单键黑，之后一直正常；退出重进又黑」。
        // 这说明黑的代价是**每个播放页生命周期的"第一次"**付的，不是每次呼条都付。
        // 第一次到底在付什么：
        //   a) 硬件加速下 INVISIBLE / GONE 的子视图不参与 dispatchDraw，整棵控制条
        //      （17 个按钮 + 进度条 + 文本）的 display list 从未被构建过，第一次 VISIBLE 要一次性建完；
        //   b) 窗口层的"有绘制内容区域"第一次从"只有画面层的洞"变成"洞 + 整条控制条"，
        //      ViewRootImpl 要重算挖洞区域并 setTransparentRegionHint，那一帧该窗口层被跳过 = 黑。
        // 常驻 VISIBLE + alpha=0 让这两件事都在**页面创建、画面还没出来**的时候一次性付掉：
        // 子视图照常构建 display list，挖洞区域从第一帧起就包含整条，之后只剩改 alpha（纯重绘）。
        // ★这不是"透明显示一次再收起"那种预热 —— 那种预热要收尾，收尾会把用户那次呼出的条收掉，
        //   之前踩过（要按两次）。这里从头到尾只有一种可见性状态，没有任何收尾动作。
        // ★回归官方/原版语义：控制条初始 GONE，呼出时 VISIBLE，收起时 GONE。
        //   之前那套"常驻 VISIBLE + 只用 alpha 显隐"是自造路径：官方 PlayerView 的 controller
        //   和原版 @id/control 都是 GONE/VISIBLE，全世界播放器都用这一个语义，没有任何一处
        //   需要为此改窗口形态。我们换成 alpha 之后并没有让现象消失，反而多了一个
        //   "窗口层永远有一整条带 alpha 的图层"的差异（HWC 图层数变化会让合成回退到 GPU）。
        controlBar?.visibility = View.GONE
        controlBar?.alpha = 1f
        runCatching {
            (controlBar as? android.view.ViewGroup)?.descendantFocusability =
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        findViewById<View>(R.id.playTitle)?.alpha = 0f
        // ★这行文案以前写的是「常驻 VISIBLE + alpha=0」，那是已废弃的自造路径；
        //   实际实现是 GONE + alpha=1（与官方 PlayerView controller、原版 @id/control 同一个形态）。
        //   文案必须跟着实现走 —— 日志骗人比没日志更糟。
        MpvDebug.log("[bar] 控制条初值 vis=${if (controlBar?.visibility == View.VISIBLE) "VISIBLE" else "GONE"} " +
            "alpha=${controlBar?.alpha} 形态=GONE/VISIBLE（非 alpha 常驻）")
        // ★nextFocus 链必须在页面创建时就建好。
        //   原来它只在 showControl() 里 post 一次，也就是说"第一次按菜单之前"，
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

    /** 刷新：重新解析当前集（原版 control.reset） */
    private fun resetCurrentEpisode() {
        if (epUrl.isBlank()) {
            Toast.makeText(this, "无当前集地址，无法刷新", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "刷新当前集", Toast.LENGTH_SHORT).show()
        Thread { resolvePlay(flag, epUrl) }.start()
    }

    /** 字幕/音轨/视轨：打开轨道选择（对齐原版 TrackDialog） */
    private fun openTrack(type: Int) {
        hideControl()
        if (isMpvMode()) {
            // mpv 也走同一套弹窗：读 track-list 列出全部可选轨道，点哪路切哪路
            val m = mpv
            if (m == null) {
                Toast.makeText(this, "播放器还没准备好", Toast.LENGTH_SHORT).show()
                return
            }
            TrackDialog(this, null, type, m).show()
            return
        }
        val p = player
        if (p == null) {
            Toast.makeText(this, "播放器还没准备好", Toast.LENGTH_SHORT).show()
            return
        }
        TrackDialog(this, p, type).show()
    }

    /**
     * 播放内核切换。
     * 本包只有两颗内核：内置 EXO 与内嵌 mpv，都真正可用，不存在「列出来但点不动」的项。
     */
    private fun openPlayerDialog() {
        val items = arrayOf("EXO（内置）", "mpv（内置）")
        val cur = if (isMpvMode()) 1 else 0
        android.app.AlertDialog.Builder(this)
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

    /** 切内核：写设置 → 释放旧内核 → 用当前地址在新内核上重播 */
    private fun switchKernel(kernel: String) {
        // 必须先取位置再改设置：isMpvMode() 读的就是设置值，改完再取会拿到另一颗内核的旧值
        val wasMpv = isMpvMode()
        val at = if (wasMpv) mpvPos else (player?.currentPosition ?: 0L)
        Settings(this).playerKernel = kernel
        mpvFallbackExo = false
        uiHandler.removeCallbacks(mpvWatchdog)
        updatePlayerText()
        val url = activeUrl
        if (url.isBlank()) {
            Toast.makeText(this, "已切换到 $kernel，下一集起生效", Toast.LENGTH_SHORT).show()
            return
        }
        if (isMpvMode()) {
            Toast.makeText(this, "已切换到 mpv（内置）", Toast.LENGTH_SHORT).show()
            startMpv(url, activeHeaders, at)
        } else {
            Toast.makeText(this, "已切换到 EXO", Toast.LENGTH_SHORT).show()
            mpv?.release()
            mpv = null
            handOver("exo", "mpv 已 release（同步 destroy），下一步 EXO 接管画面层")
            // mpv 走了：把 shutter 恢复成原版的黑色，EXO 出首帧前由它挡着
                if (player == null) rebuildPlayer(activeHeaders)
            // mpv 期间 scaleIdx 只作用在 mpv 上，切回 EXO 要把档位同步回 PlayerView
            videoHost?.resizeMode = scaleModes[scaleIdx]
            resumeOverride = at
            playUrl(url, activeHeaders)
        }
    }

    /** 解码方式（对齐原版 onDecode → Players.toggleDecode + init + setMediaSource） */
    private fun toggleDecode() {
        if (isMpvMode()) {
            // mpv 走 hwdec=mediacodec / no，开关单独存在 mpvHwdec（不再和 EXO 的 decodeMode 共用）
            val st = Settings(this)
            st.mpvHwdec = !st.mpvHwdec
            mpv?.setHardwareDecode(st.mpvHwdec)
            updateDecodeText()
            Toast.makeText(this, if (st.mpvHwdec) "mpv 已切到硬解" else "mpv 已切到软解",
                Toast.LENGTH_SHORT).show()
            return
        }
        val st = Settings(this)
        st.decodeMode = if (st.decodeMode == 1) 0 else 1
        updateDecodeText()
        if (activeUrl.isBlank()) {
            Toast.makeText(this, "解码已切换为${if (st.decodeMode == 1) "硬解" else "软解"}，下一集起生效", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "解码已切换为${if (st.decodeMode == 1) "硬解" else "软解"}，正在重启播放", Toast.LENGTH_SHORT).show()
        rebuildPlayer(activeHeaders)
        playUrl(activeUrl, activeHeaders)
    }

    private fun updateDecodeText() {
        // 对齐原版 select_decode = [软解, 硬解]；两内核各有各的开关，按当前内核取值显示
        val hard = if (isMpvMode()) Settings(this).mpvHwdec else Settings(this).decodeMode == 1
        findViewById<TextView>(R.id.btnDecode)?.text = if (hard) "硬解" else "软解"
    }

    /** 原版 onLoop 只翻转 activated；真正生效在 checkEnded（激活则重播本集，否则下一集） */
    private fun toggleLoop() {
        loopOn = !loopOn
        findViewById<View>(R.id.btnLoop)?.isActivated = loopOn
        Toast.makeText(this, if (loopOn) "单集循环已开启" else "单集循环已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun updatePlayerText() {
        // 对齐原版 control.player：显示当前内核名
        findViewById<TextView>(R.id.btnPlayer)?.text = if (isMpvMode()) "mpv" else "EXO"
    }

    /** 片头：当前位置在半段内才允许（原版 onOpening 的 current > duration / 2 直接 return） */
    private fun markOpening() {
        val cur: Long
        val dur: Long
        if (isMpvMode()) {
            cur = mpvPos
            dur = mpvDur
        } else {
            val p = player ?: return
            cur = p.currentPosition
            // TIME_UNSET 时 p.duration 是 Long.MIN_VALUE：不折算成 0 的话，下面 dur<=0 的守卫
            // 会把"未知时长"误判成"还没播到片头附近"，弹出错误的提示。
            dur = runCatching { p.duration }.getOrDefault(0L).takeIf { it > 0 } ?: 0L
        }
        if (cur <= 0 || dur <= 0 || cur > dur / 2) {
            Toast.makeText(this, "请播到片头附近再按（需在前半段）", Toast.LENGTH_SHORT).show()
            return
        }
        setOpening(cur)
        Toast.makeText(this, "片头设为 ${fmtClock(cur)}", Toast.LENGTH_SHORT).show()
    }

    /** 片尾：从结尾往回算剩余时长（原版 onEnding 的 duration - current） */
    private fun markEnding() {
        val cur: Long
        val dur: Long
        if (isMpvMode()) {
            cur = mpvPos
            dur = mpvDur
        } else {
            val p = player ?: return
            cur = p.currentPosition
            // TIME_UNSET 时 p.duration 是 Long.MIN_VALUE：不折算成 0 的话，下面 dur<=0 的守卫
            // 会把"未知时长"误判成"还没播到片头附近"，弹出错误的提示。
            dur = runCatching { p.duration }.getOrDefault(0L).takeIf { it > 0 } ?: 0L
        }
        if (cur <= 0 || dur <= 0 || cur < dur / 2) {
            Toast.makeText(this, "请播到片尾附近再按（需在后半段）", Toast.LENGTH_SHORT).show()
            return
        }
        setEnding(dur - cur)
        Toast.makeText(this, "片尾设为 ${fmtClock(dur - cur)}", Toast.LENGTH_SHORT).show()
    }

    private fun setOpening(ms: Long) {
        openingMs = ms.coerceAtLeast(0)
        HistoryStore(this).setOpeningOf(historyKey, openingMs)
        updateOpeningText()
    }

    private fun setEnding(ms: Long) {
        endingMs = ms.coerceAtLeast(0)
        HistoryStore(this).setEndingOf(historyKey, endingMs)
        updateEndingText()
        endingDone = false
    }

    private fun clearOpening() {
        setOpening(0)
        Toast.makeText(this, "片头已清除", Toast.LENGTH_SHORT).show()
    }

    private fun clearEnding() {
        setEnding(0)
        Toast.makeText(this, "片尾已清除", Toast.LENGTH_SHORT).show()
    }

    /** 0 显示「片头/片尾」（原版 play_op / play_ed），否则显示时间 */
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

    /** 对齐原版 KeyUtil.isMenuKey:35 —— 只判 KEYCODE_MENU */
    private fun isMenuKeyCode(code: Int): Boolean = code == KeyEvent.KEYCODE_MENU

    /**
     * 菜单键动作（原版 VideoActivity:1686-1687 两分支）：
     * Setting.getFullscreenMenuKey() == 0 → onToggle() 切控制栏显隐
     * == 1 → onEpisodes() 弹选集并收起控制栏
     */
    private var lastMenuKeyMs = 0L
    /** 窗口未就绪时按下的菜单意图（等窗口就绪补执行，不丢键） */
    private var pendingMenu = false
    /** 窗口未就绪时按下的其它操作键（同样补执行，不丢键） */
    private var pendingKeyCode = 0
    private val flushPendingKeyRun = Runnable { flushPendingKey() }

    /** 允许被闸门挂起的键：不含返回键（返回必须立刻响应）、不含菜单键（它有自己的闸门） */
    private val userKeys = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
    )

    /**
     * 补执行被挂起的那一下。
     * 用合成的 DOWN+UP 一对事件走原来的处理函数，语义与正常按键完全一致
     * （handleSeekKey 的起手/提交两段都在，不是另写一条捷径）。
     */
    private fun flushPendingKey() {
        val kc = pendingKeyCode
        if (kc == 0) return
        pendingKeyCode = 0
        MpvDebug.log("[key] 补执行挂起 kc=$kc winNow=${runCatching { window.decorView.hasWindowFocus() }.getOrNull()}")
        runCatching {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, kc)
            val up = KeyEvent(KeyEvent.ACTION_UP, kc)
            when (kc) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { handleSeekKey(-1, down); handleSeekKey(-1, up) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { handleSeekKey(1, down); handleSeekKey(1, up) }
                else -> togglePlayPause()
            }
        }
    }
    /** 返回键去重：DOWN/UP 两个入口都接，500ms 内只处理一次 */
    private var lastBackMs = 0L

    /** 400ms 内只触发一次，避免 DOWN/UP 双入口把动作做两遍 */
    private fun fireMenuKeyOnce() {
        val now = System.currentTimeMillis()
        if (now - lastMenuKeyMs < 400) return
        lastMenuKeyMs = now
        // ★窗口就绪闸门。
        //
        // 真机 tk6 日志（13:23:36 / 13:25:23 / 13:27:02 三次进页面完全同构）：
        //   [key] kc=82 focus=-1 bar=false ready=false      ← 菜单到达
        //   [bar] 呼出完成 nowFocus=DefaultTimeBar          ← 呼条在这一拍就做完了
        //   [sv2] created / changed 1920x1080
        //   [life] onResume ... decor=0x0                   ← 此刻窗口还没完成首次布局
        //   [ui] 进页面焦点 ... cur=-1 winFocus=false       ← 焦点被清空
        //   [ui] 窗口焦点 hasFocus=true decor=1920x1080     ← 窗口才真正就绪
        //   [win] 窗口拿到焦点
        //
        // 也就是：**第一次按菜单永远落在"窗口首次 attach→布局→获焦"这一拍里**，
        // 而后续每一次按键窗口都已稳定 —— 这就是"只有第一次"的全部来源。
        // 呼条本身要新增一个图层并重算挖洞区域，它叠在窗口自身的首次合成上，
        // 两者合成到同一帧，屏幕必然空一拍（黑），缓过来之后才继续出帧（转圈）。
        //
        // 处理：不丢弃这次意图，也不硬抢窗口，而是等窗口就绪后立刻执行（毫秒级，用户无感）。
        // 这样呼条发生在稳定窗口上，不再与首次布局叠加。
        if (!windowReady()) {
            pendingMenu = true
            MpvDebug.log("[key] 菜单推迟到窗口就绪 decor=${window?.decorView?.width}x${window?.decorView?.height}" +
                " winFocus=${runCatching { window?.decorView?.hasWindowFocus() }.getOrNull()}")
            // 兜底：万一窗口迟迟不获焦（例如被别的窗口挡住），500ms 后照常呼出，绝不吞键
            uiHandler.postDelayed({
                if (pendingMenu) { pendingMenu = false; handleMenuKey() }
            }, 500)
            return
        }
        handleMenuKey()
    }

    /** 窗口是否已经完成首次布局并拿到焦点 —— 呼条只在这之后做 */
    private fun windowReady(): Boolean = runCatching {
        val dv = window?.decorView
        dv != null && dv.width > 0 && dv.height > 0 && dv.hasWindowFocus()
    }.getOrDefault(false)

    /** 调试快照文本：只在 /debug 页面上出现，播放页 UI 不变 */
    private fun mpvDebugLine(): String {
        val m = mpv
        val surfaceVis = runCatching {
            val sv = currentSurface()
            "${sv?.width}x${sv?.height} vis=${sv?.visibility} valid=${(sv as? android.view.SurfaceView)?.holder?.surface?.isValid ?: (sv != null)}"
        }.getOrNull()
        return "mpv=${m != null} 存活实例=${MpvPlayer.liveCount} 设置内核=${Settings(this).playerKernel} " +
            "isMpvMode=${isMpvMode()} surfaceReady=${m?.surfaceReadyNow()} paused=${m?.isPausedActual()} " +
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
     * 要分清「三秒黑」到底是哪一种，只需要三个事实：
     *   1. EXO 的 SurfaceHolder 在按菜单那一刻有没有 destroyed —— 有 = 表面真丢了（重建+起帧要几秒）
     *   2. player.playbackState 有没有从 READY 掉到 BUFFERING —— 掉了 = 重缓冲（跟合成无关）
     *   3. 表面一直 valid、状态一直 READY，画面还黑 —— 那就是合成层挖洞被盖住，播放器侧没毛病
     * 这三条以前全靠猜，现在按一下菜单就同时打出来。
     */
    /** 给 EXO 的画面表面挂生命周期探针：只在 EXO 模式下挂，mpv 模式不动 */


    // ---------- 窗口形态实验档（3 / 4）：验证「整窗不透明」是不是黑屏的成因 ----------
    /**
     * 原版窗口：主题 windowBackground=@null，根布局没有背景 —— 窗口里除了画面那一格，
     * 其余区域是「什么都不画」，透明区是干净的。
     * 我们两处都是不透明黑（主题 @android:color/black + 根 FrameLayout @android:color/black），
     * 整块窗口 surface 全不透明。SurfaceView 是放在窗口 surface 之下、靠「挖洞」露出来的，
     * 洞一旦因为任何一次重排被系统算错，露出来的就是这层黑 —— 声音继续、画面消失，
     * 按返回触发一次彻底重建才恢复。原版没有这层黑，所以同样算错也看不出问题。
     *
     * 档位 3：只把窗口背景置空（根布局黑底保留）
     * 档位 4：窗口背景置空 + 根布局黑底也去掉（最接近原版的窗口形态）
     * 两个档位都需要**退出播放页再进**才生效（onCreate 才执行到这里）。
     * 副作用：画面四周的留边会露出窗口壁纸（原版就是这个样子），属于预期。
     */
    private fun applyWindowShapeExperiment() {
        /**
         * ★默认路径：**什么都不做**，窗口形态完全交给主题（Theme.TVBox：windowBackground=@null、
         * windowDisablePreview=true、无 windowAnimation）。这是官方形态，也是原版形态。
         *
         * 以前这里在默认档跑了两件事，都是负优化：
         *   · window.setFormat(TRANSLUCENT) —— 运行时改窗口 pixel format = 向 SurfaceFlinger
         *     申请换一整套窗口 buffer（整窗表面重建）；此后这个窗口永久是半透明合成路径，
         *     SurfaceView 的图层处理也跟着换了一套，属于为了绕一个没证实的假设而动最底层的东西；
         *   · rootView.setBackgroundColor(TRANSPARENT) —— 把根布局的底色也拆掉。
         * 两条都没有任何一次真机 A/B 证明过有效，只在"日志里看着合理"。
         *
         * 只在显式档位上保留实验能力，默认一个字符都不改窗口：
         *   3 / 4 / 6 / 7 —— 仅用于对照取证，需要退出播放页再进
         */
        val exp = MpvDebug.experiment
        if (exp == 7) {
            runCatching { window.setFormat(android.graphics.PixelFormat.OPAQUE) }
            runCatching { rootView?.setBackgroundColor(android.graphics.Color.BLACK) }
            MpvDebug.log("[exp7] 实验档：不透明黑窗口 + 黑根布局")
            return
        }
        if (exp == 3 || exp == 4) {
                if (exp == 4) runCatching { rootView?.setBackgroundColor(android.graphics.Color.TRANSPARENT) }
            MpvDebug.log("[exp$exp] 实验档：仅清背景")
            return
        }
        if (exp == 6) {
            runCatching { window.setFormat(android.graphics.PixelFormat.TRANSLUCENT) }
            runCatching { rootView?.setBackgroundColor(android.graphics.Color.TRANSPARENT) }
            MpvDebug.log("[exp6] 实验档：窗口 TRANSLUCENT + 根布局透明")
            return
        }
        MpvDebug.log("[exp$exp] 窗口形态走主题默认，不做任何运行时改动")
    }

    private fun handleMenuKey() {
        val toEpisodes = Settings(this).fullscreenMenuKey == 1
        // ★菜单这一帧只做「控制条显隐」这一件事，所有诊断全部推到下一帧。
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
        // ★这两段诊断必须离开"呼条后那 1 秒"。
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
     * 原版这一共四件事：控制条 VISIBLE、按钮可见性、`view.requestFocus()`、`setControlNextFocus()`。
     * **没有任何"恢复渲染"的动作** —— 之前这里调 `mpv.recoverRendering()`（detach → attach → 重设 vo）
     * 会让 mpv 真的重新出帧、重新缓冲，表现就是「第一次按菜单黑一下 + 看到加载中」，属自造负优化，已删。
     *
     * focusId / withTitle 也照原版分开：
     *   ↑(onKeyUp:1755) → 焦点给片头/片尾，并 showInfo（顶部片名信息）
     *   ↓(onKeyDown:1763) / 菜单(onToggle:1086) → 焦点给"下集"，不带 info
     */
    /** 控制条上次的焦点落点（原版 mFocus2）；菜单呼出时恢复到它，避免每次都跳回"下一集" */
    private var lastBarFocusId: Int = 0

    /* ★判黑取证：surface 代数 + UI 线程迟到检测。
       「黑的那一下连洋红方块都不在」已经排除了"某个视图画黑"这条分支，
       只剩两种可能，必须靠这两组数分开：
         surfGen 变大  → 视频 surface 真的被销毁重建（合成器/显示管线跟着重配 → 整屏黑）
         [stall] 迟到  → UI 线程被卡住（这一帧压根没提交，屏幕自然是上一层）
       两者都不出现却还黑 → 显示管线自己切了，代码里改视图没用。 */
    /* ★帧投递探针（区别于 [stall]）：
       [stall] 只看 UI 线程有没有迟到；它看不出一件事 —— 线程照常跑、帧照常提交，
       但屏幕就是不更新（整窗不参与合成）。有了这个每帧回调，黑屏期间的帧数变化可以定死：
         n 一直在涨 + 屏幕黑  → 我们的帧在出、是合成/窗口层把它丢了（代码改视图无用）
         n 停住             → 线程或 vsync 断了（配合 [stall] 定性）
       只在每满 1 秒打一行，播放中零额外开销。 */
    private var frameLastLog = 0L
    private var surfGen = 0
    /** 起播代数：只有真正走到 prepare 才算一次。用来分辨"首帧回放"是播放重启还是画面层重建 */
    private var startGen = 0
    private var lastSurfCreateAt = 0L

    // ★/debug 快照是在 NanoHttpd 线程里跑的函数式调用，直接读 EXO 会抛
    //   IllegalStateException(Player is accessed on the wrong thread)。
    //   位置/时长/播放态改由主线程每 500ms 落一份缓存，快照只读缓存。
    @Volatile private var snapPosMs = 0L
    @Volatile private var snapDurMs = 0L
    @Volatile private var snapPlaying = false
    private var snapAt = 0L

    /**
     * 每秒一次，只做一件事：把位置/时长/播放态落进缓存，
     * 让 /debug 快照能跨线程安全读（不能在 NanoHttpd 线程直接读 EXO）。
     * 不做逐帧 Choreographer 回调、不做 100ms 迟到检测 —— 那些都是纯诊断，
     * 一个跑在播放页上的逐帧回调本身就是负优化。
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
        // 诊断档位 2：菜单键完全不呼出控制条。用来判断黑屏到底跟控制条有没有关系——
        // 如果这个档位下按菜单仍然黑，那问题就不在控制条上，别再往这里改。
        if (MpvDebug.experiment == 2) {
            MpvDebug.log("[exp2] 菜单按下，按档位要求不呼出控制条 isMpv=${isMpvMode()}")
            return
        }
        // ★wasShown 必须在这里取：常驻 VISIBLE 之后 barShown() 只看 alpha，
        //   下面马上就把 alpha 抬到 1，若在那之后再判就恒为"已显示"，每次呼条都被幂等分支吞掉。
        val wasShown = barShown()
        // 呼出 = VISIBLE + 放开焦点进入（否则条内控件不接收焦点）
        controlBar?.visibility = View.VISIBLE
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
            uiHandler.postDelayed(autoHide, 5000)
            return
        }
        // ★ 呼出前先让画面层把上一帧重绘一次：
        //   接下来这一帧要同时合成「视频层 + 控制条」，如果视频层这次没提交新帧，
        //   合成器就会拿到空队列 → 整屏黑一下。先把同一帧重新贴上去，黑的那一下就不存在。
        // ★菜单路径上一个 native 调用都不做。
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
        // ★呼条前先记下真实位置：呼条这一拍若把播放点顶回旧值（现场"播了 4 分钟、
        //   第一次按菜单又回到 3 分钟"就是这种），下面的守卫会把它拉回来。
        val posBeforeBar = if (!wasShown) currentPosMs() else 0L
        // ★呼条前把进度条内部位置对齐真实位置。
        //   DefaultTimeBar 的 scrub 起点取它自己的 position（只在 setPosition 时更新）；
        //   进页面不久就呼条时那份值可能还是 0，焦点一进条、随后一次 ←，就会提交 0
        //   —— 现场「第一次按菜单视频回到开头 + 黑完出现加载中」正是它（seekTo(0) 触发重缓冲）。
        runCatching {
            findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.timeBar)
                ?.setPosition(currentPosMs())
        }
        // 诊断档位 1：呼出控制条但不 requestFocus（原版是 requestFocus 的，这里只为做 A/B）
        var focusOk = false
        if (MpvDebug.experiment != 1) {
            focusOk = runCatching { findViewById<View>(target)?.requestFocus() == true }.getOrDefault(false)
        }
        // 呼条是否真的把焦点落进条里，这一行说了算。以前只有"条出来了"这一条证据，
        // 焦点到底进没进条完全看不出来 —— 而"条出来但焦点没进条"就是我们反复踩的那个坑。
        MpvDebug.log(
            "[bar] 呼出完成 alpha=${controlBar?.alpha} target=$target focusOk=$focusOk" +
                " nowFocus=${currentFocus?.id}(${currentFocus?.javaClass?.simpleName})"
        )
        // ★次帧焦点确认。
        //   androidx/media issue #1828 现场记录过同一件事：在 dispatchKeyEvent 里调 requestFocus()
        //   **不会当帧落在视图上**，用户要等到"松开按键"才看到焦点真正过去。
        //   我们呼条也是在按键回调里 requestFocus 的，所以 focusOk=true 只代表"受理了"，
        //   不代表这一帧焦点已经在条里；下一按若走焦点系统（OK / 条内导航），就会打空。
        //   这里在下一帧补一次确认：只有在焦点仍为空或仍在根布局时才重试，避免把用户
        //   已经移进条内的焦点又拽回去。
        if (!wasShown && MpvDebug.experiment != 1) {
            val want = target
            uiHandler.post {
                val cur = currentFocus
                if (cur == null || cur === rootView) {
                    val ok2 = runCatching { findViewById<View>(want)?.requestFocus() == true }.getOrDefault(false)
                    MpvDebug.log("[bar] 次帧焦点确认 want=$want ok=$ok2 now=${currentFocus?.id}(${currentFocus?.javaClass?.simpleName})")
                }
            }
        }
        // ★位置守卫：呼条必然带着一次窗口重排，这一拍里任何"按历史续播点重新定位"
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
        uiHandler.postDelayed(autoHide, 5000)
        MpvDebug.log("[ui] showControl isMpv=${isMpvMode()} mpv=${mpv != null} focus=$focusId")
        // 注意：**不要**在这里重建画面层（INVISIBLE→VISIBLE）。
        // 那样每次呼出控制条都会销毁并重建 SurfaceView：EXO 会闪一次黑 shutter、
        // mpv 每次 detach/attach，等于把偶发黑屏换成每次必闪，属于负优化。
    }

    /**
     * 窗口层合成探针（PixelCopy）。
     *
     * 只有它能回答"按菜单那一下到底黑在哪一层"：
     *   PixelCopy.request(window, …) 抓的是**窗口层**的合成结果，不含 SurfaceView（那是独立层）。
     *   - 控制条可见时抓它自己的区域，若 barLum≈0 → 这一帧整个窗口层没被合成（挖洞/relayout 那条链）；
     *   - 若 barLum 正常 → 窗口层正常，黑的是画面层（解码器/帧没提交）。
     * 这两条以前只能靠"洋红方块"肉眼判断，现在直接落在日志里。
     *
     * 注意：INVISIBLE/GONE 时窗口层那片区域本来就是 0，所以探针只在 alpha>0 时才有判读意义。
     */
    /** 收起控制条：对齐 5.6 hideControl(:3502) —— 只置 GONE，不做任何焦点动作 */
    private fun hideControl() {
        uiHandler.removeCallbacks(autoHide)
        // 幂等：真机日志里同一毫秒能连进来 4 次（autoHide + 起播收条 + 按键兜底各调一次），
        // 每次 GONE 都给 root 记一次 requestLayout，= 多出 3 轮全树 traversal，
        // 每一轮父窗口重画黑底、SurfaceView 的洞晚一帧合成 = 白闪一格。
        // 5.6 的 hideControl 任意时刻只被调用一次，所以它从来不会连闪。
        // 常驻 VISIBLE 下"收起" = 清焦点 + 降 alpha + 关掉焦点进入。
        // 不用 GONE/INVISIBLE：那两种都会让窗口层的绘制内容区域变化（GONE 还额外触发 measure/layout）。
        if (!barShown()) return
        // ★收条只做一件事：置 GONE。与 5.6 hideControl(:3502) 逐字一致。
        // 之前的 alpha 方案（常驻 VISIBLE + alpha 0 + BLOCK）有个副作用：
        // 控制条子控件**仍然持有焦点**（BLOCK 只挡"新的焦点进来"，不夺走已有的），
        // 于是每次收条我们都要 post 一次 requestFocus 把焦点抢回来 —— 那就是**多出来的一轮 traversal**，
        // 父窗口要重画一遍整屏黑底，SurfaceView 的洞晚一帧才合成 = 黑一下。
        // 5.6 没有这一步：条一 GONE，焦点视图从树里消失，系统在**同一次** traversal 里
        // 自动把焦点交给可聚焦的父容器，不额外产生一轮。
        // 原版 hideControl(:3502) 只有两件事：置 GONE、撤掉自动收起定时器。
        // ★覆盖层收起前，必须把焦点显式交回根覆盖层。
        //   这是 Google TV 播放控件那条规范的要求：条一旦淡出，原来高亮的那颗按钮
        //   也跟着消失，若焦点还握在它身上，下一次遥控输入就会落到"一颗看不见的按钮"上
        //   —— 用户感受就是"按了没反应 / 得先按一次菜单才好"。
        //   以前这里刻意不碰焦点（照抄 5.6 只置 GONE），是因为 5.6 的按键语义不看焦点；
        //   我们的语义看焦点，所以这一步在我们这套结构里是必须的，不是多余动作。
        val fromId = currentFocus?.id
        val fromIn = isInsideBar(currentFocus)
        if (fromIn) {
            rootView?.requestFocus()
            // from 必须先在 requestFocus 之前取。之前这条日志取的是动作之后的值，
            // 真机上永远显示"从=-1"，看着像"焦点自己从条上漂走了"，实际是插值取晚了。
            MpvDebug.log(
                "[key] 收条前清焦点 从=$fromId inBar=$fromIn" +
                    " lastBarFocus=$lastBarFocusId barVis=${controlBar?.visibility}" +
                    " barAlpha=${controlBar?.alpha} → root ok=${currentFocus === rootView}"
            )
        }
        controlBar?.visibility = View.GONE
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
     * 真机日志里 hideControl 之后 currentFocus 是 -1（无任何视图持有焦点），
     * 说明 rootView.requestFocus() 一直没成功 —— 首键就成了"把焦点捡回来"，表现为要按两次。
     * 这里显式置可聚焦再请求，仍失败就退到 decorView，并把结果写进 /debug。
     */
    /** v 是不是 parent 的后代（含自身） */
    /** 从某个控件往上取 6 层（id/类名），用来判"焦点到底挂在哪棵子树下" */
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
        val before = currentFocus?.id
        val cur = currentFocus
        // 条内焦点每次按键都顺手记下来。原来只有在按 OK 的分支里才记（3024 行），
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
        // was 要在 requestFocus 之前取。以前取 currentFocus 是在落点之后，所以永远显示
        // "was=DefaultTimeBar" —— 看着像"焦点本来就在条上却判不在条上"，把排查方向带偏过一次。
        MpvDebug.log(
            "[ui] 控制条可见但焦点不在条上 → 重新落点 was=$before" +
                " lastBarFocus=$lastBarFocusId → id=${target?.id}" +
                "(${target?.javaClass?.simpleName}) ok=$ok"
        )
        return ok
    }

    /**
     * 收条/呼条前后各采三拍，判断"黑的那一下"到底是渲染层没了还是合成层没跟上。
     * 判读：三拍都 vo=gpu 且 pos 在推进 → 播放器一直活着，黑在合成；
     *       出现 vo=空 / surface valid=false → 表面真丢了。
     */
    /**
     * 锁住画面层的缓冲区尺寸 —— 对齐 WebHomeTV 5.6 的 syncVideoSurfaceSize()。
     *
     * 5.6 在 RENDER_SURFACE 下对 PlayerView 的 SurfaceView 调 holder.setFixedSize(width,height)，
     * 这样 Surface 的缓冲区大小由"视频尺寸"决定，不再跟着视图每帧 measure/layout 走。
     * 我们一直没做这一步，缓冲区尺寸始终由布局推导：只要窗口里发生一次布局
     * （EXO 内核呼出控制条就是 layouts=1），Surface 就可能按新尺寸重开缓冲区，
     * 重开那一帧生产者还没写入 → 画面层空白 → 整屏黑一下。
     * 锁死缓冲区后，布局再多也不会换缓冲区，这一帧就不存在。
     */
    private fun setupSharedSurface() {
        val sv = videoHost?.surfaceView ?: return
        // ★setFixedSize 这条是从 5.6 抄的，但 5.6 只在它自己那套自定义 render surface 上锁尺寸。
        //   我们把它挂在了 PlayerView 的 SurfaceView 上，结果真机日志里：
        //     [sv] setFixedSize 1920x1080 → surface=1920x1080   （此时视图还是满屏 1080）
        //     [exo] view=1920x799                              （视频比例算出 799 之后视图缩了）
        //   buffer 被钉死 1080、视图却是 799 → 合成时把画面纵向压扁（1080/799 ≈ 1.35 倍），
        //   并且每次布局都触发一次 [sv2] changed 重新分配 buffer。
        //   官方 PlayerView 自己会按 resize_mode=fit 算 AspectRatioFrameLayout 的尺寸，
        //   根本不需要我们锁死缓冲区。
        //   这里只做一件必要的事：把 Surface 的像素格式从默认 RGB_565（API25 默认）改成 8888，
        //   否则整条链路只有 16bit，色带明显，mpv 的 RGBA 帧也要被降位再上屏。
        // ★不再 setFormat(RGBA_8888)。
        //   改像素格式 = 向 SurfaceFlinger 申请换一套 buffer（buffer 队列重建），
        //   在播放中去改等于动了画面层的拓扑；原版与官方 PlayerView 全程不碰它。
        //   色带这一项先不计代价换稳定：真机验证黑屏消失后再决定要不要用别的办法拿 8888。
        runCatching {
            MpvDebug.log("[sv] 画面层沿用官方默认 PixelFormat（不再 setFormat）")
        }
    }

    /** 呼出控制条时把视图树的面板层级打一次：谁盖在画面层上面、谁是不透明的，一眼可查。 */
    private var treeDumped = 0
    private fun dumpPanelTree(tag: String) {
        if (treeDumped >= 3) return
        treeDumped++
        runCatching {
            val root = window.decorView
            fun walk(v: View, d: Int) {
                if (d > 6) return
                val id = runCatching {
                    if (v.id == View.NO_ID) "-" else resources.getResourceEntryName(v.id)
                }.getOrDefault("?")
                val bgCls = if (v.background == null) "-" else v.background.javaClass.simpleName
                val opaque = runCatching { v.javaClass.getMethod("isOpaque").invoke(v) as Boolean }.getOrNull()
                MpvDebug.log("[panel] $tag ${"  ".repeat(d)}${v.javaClass.simpleName} id=$id " +
                    "vis=${v.visibility} alpha=${v.alpha} bg=$bgCls opaque=$opaque size=${v.width}x${v.height}")
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1)
            }
            walk(root, 0)
            runCatching {
                val id = runCatching { resources.getIdentifier("exo_shutter", "id", packageName) }.getOrDefault(0)
                if (id != 0) {
                    val sh = findViewById<View>(id)
                    MpvDebug.log("[panel] $tag ★快门 exo_shutter vis=${sh?.visibility} alpha=${sh?.alpha} " +
                        "bg=${sh?.background?.javaClass?.simpleName} size=${sh?.width}x${sh?.height} " +
                        "shown=${sh?.isShown}")
                }
                val pv = findViewById<View>(R.id.videoFrame)
                MpvDebug.log("[panel] $tag ★playView vis=${pv?.visibility} alpha=${pv?.alpha} " +
                    "bg=${pv?.background?.javaClass?.simpleName} size=${pv?.width}x${pv?.height}")
            }
            MpvDebug.log("[panel] $tag 窗口 hasFocus=${root.hasWindowFocus()} winVis=${root.windowVisibility} " +
                "svShown=${currentSurface()?.isShown} svAlpha=${currentSurface()?.alpha} " +
                "decorBg=${root.background?.javaClass?.simpleName}")
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
        MpvDebug.log("[ui] focusRoot reason=$reason ok=$ok id=${v.id} focus=${currentFocus?.id}")
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

    /** 菜单键：原版默认 fullscreen_menu_key=0 → onToggle（控制条开/关），不是直接弹选集 */
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
        findViewById<View>(R.id.playCenter)?.visibility = View.VISIBLE
    }

    /** 恢复播放时收起（对齐原版 onPlay → hideCenter，同时把图标复位回 ic_widget_play） */
    private fun hidePlayCenter() {
        // 对齐原版 hideCenter()：图标复位回 ic_widget_play，再整块收起
        findViewById<ImageView>(R.id.playCenterIcon)?.setImageResource(R.drawable.ic_widget_play)
        findViewById<View>(R.id.playCenter)?.visibility = View.GONE
    }

    /** OK 键短按：暂停 / 继续（对齐原版 onKeyCenter），EXO / mpv 双内核同语义 */
    private fun togglePlayPause() {
        // ★这里不再有任何「就绪闸门」和意图变量。
        //   动作只有一句：把结果交给会话，会话直接读内核真实状态并下发。
        //   以前那两个 if (p != null && ready) 就是「按 OK 没反应、要按一次菜单才好」的成因。
        val wasPlaying = session.isPlaying()
        session.toggle()
        pendingPauseIntent = null
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

    /** 暂停/继续的界面同步（居中图标 + 常亮）：两条内核共用，避免两边各写一份走偏 */
    private fun applyPauseUi(pausing: Boolean) {
        findViewById<android.widget.TextView>(R.id.btnPlayPause)?.text = if (pausing) "继续" else "暂停"
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
    /** 长按重复事件的最小间隔：T1 的 key repeat 是 40~60ms 一个，原样累加会放大 10 倍 */
    private val SEEK_REPEAT_GAP_MS = 300L
    /** 最后一次方向键按下后多久自动提交（不依赖 ACTION_UP，只发 DOWN 的遥控也能跳） */
    // 已删除 SEEK_COMMIT_IDLE_MS：抬手即 seek 之后这条"空闲提交"的阈值没有任何使用点。
    private var lastSeekRepeatAt = 0L
    /** 一次按住期间已经计入的台阶数（用来放大后面的步长） */
    private var seekTicks = 0
    private var seekBaseMs = 0L          // 按下方向键那一刻的位置
    private var seekAccumMs = 0L         // 按住期间累计的偏移
    private var seekingByKey = false

    /**
     * 位置只从会话取：会话内部读内核真实值（mpv 读 time-pos，EXO 读 currentPosition）。
     * 以前 mpv 走一个本地缓存变量 mpvPos，切内核/重建时它会和真实位置脱节，
     * 「回到片头」里有一部分就是它造成的。
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
        // ★时长未知时不能显示「绝对位置」：那时 seekBaseMs=0，按 ← 的 -10000 会被夹成 0，
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
        findViewById<View>(R.id.playCenter)?.visibility = View.VISIBLE
        // 回读：这一行是"按 ←/→ 屏幕上到底有没有东西出现"的唯一证据。
        // 以前没有它，只能从"位置变了"推断按键生效，而用户看的是屏幕 —— 两边永远对不上。
        MpvDebug.log(
            "[ui] 居中提示 显示 " + (if (durMs > 0L) "target=${fmtTime(target)}" else "位移=${if (accum<0) "-" else "+"}${fmtTime(if(accum<0) -accum else accum)}") +
                " dur=${if (durMs > 0L) fmtTime(durMs) else "--:--"}" +
                " panelVis=${findViewById<View>(R.id.playCenter)?.visibility} accum=${accum}ms"
        )
    }



    /**
     * 进度条的 scrub 回调。
     *
     * ★现在左右键**不再**交给 DefaultTimeBar（见 dispatchKeyEvent 里合并后的分支），
     *   所以这条只剩触摸拖动会走到。留着是因为它仍然必须挂：DefaultTimeBar 在没有监听者时
     *   会**只消费输入、不向外报告**（内部只挪指示器），表现就是"拖了没反应"。
     *
     * ★DefaultTimeBar 1.3.1 的三个协议坑（源码 libraries/ui/.../DefaultTimeBar.java）：
     *   1) onKeyDown → scrubIncrementally：`if (duration <= 0) return false`，
     *      duration 为 0 时左右键**连 scrub 都不启动**，View 层一声不响 —— 起播前按 ← 就是这个。
     *   2) 按键 scrub 不是立即提交：`postDelayed(stopScrubbingRunnable, STOP_SCRUBBING_TIMEOUT_MS)`
     *      其中 STOP_SCRUBBING_TIMEOUT_MS = 1000。按一下要等**满 1 秒空闲**才触发 onScrubStop → seek。
     *      现场"第一次按下去黑约 1 秒才动"就是这一秒；而 seek 又会把 READY 打回 BUFFERING
     *      （ExoPlayerImpl.seekTo：READY 时 copyWithPlaybackState(STATE_BUFFERING)）→ 于是出现"加载中"。
     *   3) 起点取的是它自己的 `position` 字段（`previousPosition = scrubbing ? scrubPosition : position`），
     *      这个字段只由 setPosition() 喂。喂它的只有每秒一次的 ticker 和呼条那一次 ——
     *      ticker 一旦没跑，position 就停在旧值，按 ← 会从旧值往回跳，表现就是"跳回刚才那个位置"。
     *   把按键收回 Activity 处理，等于一次绕开这三个坑。
     */
    private fun bindTimeBarScrub() {
        val tb = findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.timeBar) ?: return
        // ★参数错在这里：DefaultTimeBar 的 keyTimeIncrement 默认是 C.TIME_UNSET，
        //   此时它按「总时长 / 20」算一步 —— 45 分钟的片子每按一次左右就跳 136 秒，
        //   现场日志 target=137554 / 1227811 就是这个（137554 ≈ 2725040/20）。
        //   显式给 10s，和我们的方向键步长一致；长按连发就按 10s 一档叠加。
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
                        MpvDebug.log("[key] ★进度条位置陈旧 条=${position}ms 内核=${livePos}ms 差=${position - livePos}ms")
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

    /** 拖动进度时的居中提示：图标沿用"快进"，数值直接给目标位置 */
    private fun showJumpCenter(target: Long) {
        val durMs = durNow()
        findViewById<ImageView>(R.id.playCenterIcon)?.setImageResource(R.drawable.ic_widget_forward)
        findViewById<TextView>(R.id.playCenterPos)?.text = fmtTime(target)
        findViewById<TextView>(R.id.playCenterDur)?.text = if (durMs > 0L) fmtTime(durMs) else "--:--"
        findViewById<View>(R.id.playCenter)?.visibility = View.VISIBLE
    }

    /** 媒体键（播放/快进/快退）走的一次性跳：只跳一档，不参与方向键的累加状态机 */
    private fun mediaSeekStep(dir: Long) {
        val dur = durNow()
        var target = (currentPosMs() + dir * SEEK_STEP_MS).coerceAtLeast(0L)
        if (dur > 0L) target = target.coerceAtMost(dur)
        MpvDebug.log("[key] 媒体键 seek dir=$dir → target=${target}ms dur=${dur}ms")
        if (!contentReady()) {
            pendingJumpMs = target
            showJumpCenter(target)
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

    /** 当前累加对应的目标位置（handleSeekKey 每次步进都用它算目标） */
    private fun seekTargetNow(): Long {
        val dur = durNow()
        var t = (seekBaseMs + seekAccumMs).coerceAtLeast(0L)
        if (dur > 0L) t = t.coerceAtMost(dur)
        return t
    }

    /** 跳转提示的收尾：固定停留 1.2s，避免"闪一下"等于没看见 */
    private val hideSeekCenterRun = Runnable { hidePlayCenter() }

    /**
     * 方向键 / 媒体快进键共用的累加逻辑。
     *
     * 参数只有一套：首按 +10s；长按每 300ms 再 +10s；最后一次按下后 400ms 自动提交。
     */
    /**
     * 隐藏态左右键：**完全对齐原版 CustomKeyDownVod.check()**（这是本段最大的一处结构修正）。
     *
     *   CustomKeyDownVod.java
     *     LEFT/RIGHT + ACTION_DOWN → listener.onSeeking(subTime()/addTime())
     *                                 —— 只累积 holdTime 并更新居中提示，**一个 seek 都不下发**
     *     LEFT/RIGHT + ACTION_UP   → App.post(() -> listener.onSeekEnd(holdTime), 250)
     *                                 —— 抬手 250ms 后，按「当前位置 + holdTime」**只跳一次**
     *
     * 我们原来做的是「每一档 DOWN 立刻 seekTo」：按住一秒就是 3~4 次 seekTo，
     * EXO 每次都重进 BUFFERING、互相打断，最后画面几乎不动 —— 现场"按键无效"就是它。
     * 而控制条显示时左右键走的是焦点系统，media3 DefaultTimeBar 只在**松开**才
     * 通过 onScrubStop 跳一次，天然没有重复下发，所以"按过一次菜单就正常了"。
     * 同一台机器、同一次按键，差别只在「重复下发 vs 一次下发」——这不是参数问题，是时序结构。
     *
     * 另一个结构性问题也在这里一起消掉：旧实现在 DOWN 分支里 seek，就意味着
     * 「按下那一刻的位置」必须被记住（seekBaseMs）。一旦这个变量被别的路径改掉
     * （切集、切内核、定时落盘），目标点就会算到错误的位置上。现在基点只在手势开始时取一次，
     * 抬手就清，不存在残留。
     */
    private fun handleSeekKey(dir: Long, event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val nowMs = android.os.SystemClock.uptimeMillis()
                if (!seekingByKey) {
                    seekingByKey = true
                    seekBaseMs = currentPosMs()
                    seekAccumMs = 0L
                    lastSeekRepeatAt = 0L
                    seekTicks = 0
                }
                // T1 的 key repeat 实测 40~60ms 一个。原样按「每来一个 DOWN 加 10s」累加，
                // 真机上一次 2 秒的按住会累到 27 次 → 一下跳掉 4 分半（现场 base=49021 accum=270000）。
                // 这里两件事：① repeat 事件按 300ms 节流（SEEK_REPEAT_GAP_MS 原来只声明没接线）；
                //            ② 台阶越按越大（前 3 阶 10s，之后 20s），长按仍然能快速走远，但不再失控。
                val stepped = event.repeatCount == 0 ||
                    nowMs - lastSeekRepeatAt >= SEEK_REPEAT_GAP_MS
                var step = 0L
                if (stepped) {
                    lastSeekRepeatAt = nowMs
                    seekTicks++
                    step = if (seekTicks <= 3) SEEK_STEP_MS else SEEK_STEP_MS * 2
                    seekAccumMs += dir * step
                }
                MpvDebug.log(
                    "[key] 快进起手 dir=$dir step=$step accum=${seekAccumMs}ms ticks=$seekTicks" +
                        " repeat=${event.repeatCount} 计入=$stepped dur=${durNow()}ms bar=${barShown()}"
                )
                // 夹在 [0, dur] 里：原版会开到越界再靠 seekTo 夹，我们提前夹，提示上的时间才不会出现负数。
                // ★未起播（dur=0）时不能夹：那时 seekBaseMs 恒为 0，夹下界会把 -10000 直接变成 0，
                //   位移就丢了 —— 现场"进页面按 ← 完全没反应"正是这里把它抹平了。
                val dur = durNow()
                if (dur > 0L) {
                    if (seekAccumMs < -seekBaseMs) seekAccumMs = -seekBaseMs
                    if (seekBaseMs + seekAccumMs > dur) seekAccumMs = dur - seekBaseMs
                }
                showSeekCenter(seekAccumMs)
            }
            KeyEvent.ACTION_UP -> {
                // DOWN 没进过这里 = 快进根本没起手（多半被上游分支吞掉/焦点不在这条路上）。
                // 以前这里直接 return，日志一片空白，只能看到"按了一下 ←"，判不出丢在哪一层。
                if (!seekingByKey) {
                    MpvDebug.log("[key] 快进抬起但未起手 kc=${event.keyCode} (DOWN 未进入快进分支)")
                    return
                }
                seekingByKey = false
                // ★这里不再判断「内容就绪」，也不再蓄积 —— 直接 seek，与原版一致。
                //   原版 VideoActivity.dispatchKeyEvent:5974 的唯一门槛是 service() != null（播放服务已绑定），
                //   而它的 onSeekEnd → seekTo(time) → PlayerManager.seekTo，全程不看 duration、不看是否起播。
                //   我们之前加过"未就绪先攒起来、READY 再补跳"：那既让起播前的按键只剩提示，
                //   又让那笔位移在 READY 时叠加到当时的 currentPosition 上而算错基点（现场"跳回旧点"就是它）。
                //   media3 的 ExoPlayer.seekTo 在没有 mediaItem 或未 prepare 时只是把目标记下来，
                //   prepare 完成后自然生效，所以根本不需要我们替它排队。
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
     * ★已删除 commitSeek：它做的是两件错事——
     *   1) 再下发一次同一个 target 的 seekTo（与 handleSeekKey 里的立即跳重复）；
     *   2) `player.playWhenReady = true` —— 暂停中按快进会被强行播起来。
     *      原版 onSeekEnd:6238-6241 只有 `if (seekTo(time)) hideCenter();`，从不 resume。
     */

    /** 时间栏刷新（原版 view_control_seek 的 position / timeBar / duration） */
    /**
     * ★进度条刷新 ticker 的句柄。
     *   以前它是 startProgressTicker() 里的局部对象，谁也拿不到引用，
     *   于是：每进一次播放页就多注册一个"每秒自我重启"的任务，且 onDestroy 清不掉它。
     *   uiHandler 是 Handler(getMainLooper())，Message 会把 Handler 连着 Activity 一起留在主线程队列里 ——
     *   结果是每次进出播放页，主线程就多一个永久每秒跑的 ticker（各自 setDuration/setPosition/两次 setText）。
     *   进得次数越多，主线程越忙，按键排队越久，体感就是"越用越不灵"。
     */
    private var progressTick: Runnable? = null

    private fun startProgressTicker() {
        progressTick?.let { uiHandler.removeCallbacks(it) }
        // 视图引用只取一次：findViewById 每次都要遍历视图树，而这段 Runnable 是每秒一直在跑的
        val bar = findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.timeBar)
        val posText = findViewById<TextView>(R.id.timePos)
        val durText = findViewById<TextView>(R.id.timeDur)
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
                // 片尾：原版 onTimeChanged 的 ending + position >= duration → checkNext()
                // 原版这一步没有任何提示，直接切下一集（之前那句 Toast 属于画蛇添足，已删）
                if (endingMs > 0 && dur > 0 && !endingDone && pos + endingMs >= dur) {
                    endingDone = true
                    if (directUrl.isBlank()) switchEpisode(1)
                }
                // 原版 Clock.java:66 是 1000ms
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 按键体检：收到了什么键、当时焦点在哪、控制条显不显示、内容就绪没有。
        // 现场判断「按了没反应」究竟是没收到键、还是收到了但状态分支走错，只靠这一行。
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            runCatching {
                MpvDebug.log(
                    "[key] kc=${event.keyCode} focus=${currentFocus?.id}" +
                        "(${currentFocus?.javaClass?.simpleName ?: "null"})" +
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
                    "focus=${currentFocus?.id}(${currentFocus?.javaClass?.simpleName ?: "null"}) " +
                    "touchMode=${runCatching { window.decorView.isInTouchMode }.getOrNull()} mpv=${mpv != null} exo=${player != null}"
            )
        }
        // ★★ 通用「窗口就绪闸门」——这一轮真机 /debug 的逐字证据：
        //   19:27:56 [key] kc=82 ... ready=false
        //   19:27:56 [key] 菜单推迟到窗口就绪 decor=1920x1080 winFocus=false
        //   19:27:56 [ui] 窗口焦点 hasFocus=true decor=1920x1080
        //   19:27:56 [key] 窗口就绪，补执行挂起的菜单
        //   19:29:34 第二次进页面，四行完全同构。19:29:10 那一按则是 winFocus=true ready=true。
        //
        // 也就是说：**每次进页面的第一下按键，窗口都还没有 hasWindowFocus()**。
        // 这块 SurfaceView 一创建（[sv2] created），窗口就会丢焦并重排一次，
        // 第一下按键正好落在"首次合成"那一拍上 —— 屏幕正在重排，用户看到的就是"按了没反应"。
        // 菜单键因为下面那段闸门被推迟补救，所以看起来"能用"；←→/OK 没有闸门，
        // 直接打在这一拍上。这就是"必须先按一次菜单之后所有键才正常"的全部来源：
        // 那一下菜单把窗口的首次合成代价付掉了，之后窗口一直稳，键就一直灵。
        //
        // 处理：把闸门推广到所有操作键。未就绪时挂起（500ms 兜底），窗口获焦后立刻补执行。
        // 不吞键、不加提示、不改任何视觉。
        if (!windowReady() && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode in userKeys
        ) {
            pendingKeyCode = event.keyCode
            MpvDebug.log("[key] 窗口未就绪，挂起 kc=${event.keyCode}")
            uiHandler.removeCallbacks(flushPendingKeyRun)
            uiHandler.postDelayed(flushPendingKeyRun, 500)
            return true
        }
        // 控制条可见时先确保焦点在条内：起播/表面变化会把焦点抢回根布局，
        // 那一瞬间开始方向键与 OK 就全落在根上，看起来就是"没反应"。
        // 菜单键（对齐原版 VideoActivity.dispatchKeyEvent:1686-1687 + KeyUtil.isMenuKey:35）
        //   Setting.fullscreenMenuKey == 0 → onToggle()：控制栏显隐
        //   == 1 → onEpisodes()：弹选集并收起控制栏
        if (isMenuKeyCode(event.keyCode)) {
            // 原版只认 ACTION_UP。这里 DOWN/UP 都接、400ms 内只算一次：
            // 只发 UP 的遥控器能响应，只发 DOWN 的也能响应，正常成对的不会双触发。
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) fireMenuKeyOnce()
            } else if (event.action == KeyEvent.ACTION_UP) {
                fireMenuKeyOnce()
            }
            return true
        }

        // ★遥控器上的「播放/暂停」「快进」「快退」发的是 MEDIA_* 码，不是 DPAD/OK。
        //   以前这三个键一路 return super，系统没人消费 → 按下去一点变化都没有。
        //   这不是参数不对，是这三个键从来没接线。
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    MpvDebug.log("[key] 媒体键 kc=${event.keyCode} → 暂停/继续")
                    togglePlayPause()
                }
                return true
            }
            // ★只接 MEDIA_FAST_FORWARD / MEDIA_REWIND，且**只在抬起时**触发一次 ——
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
        // 起播前（画面还没出来）控制条上的按钮全都没有意义：切内核会重建播放器、刷新会重解析、
        // 上下集还没开始播、片头片尾还没时间轴。这段时间里 OK / ←→ 一律按"暂停""快进"的意图处理，
        // 不再交给焦点控件 —— 现场日志里就是按到了 text=mpv/EXO 的内核按钮，看着像没反应。
        val loading = preStart()

        // 返回键：对齐原版 onBackPressed:1834 的优先级 —— 控制条 → 居中提示 → 退出页面。
        // 之前 BACK 只在"控制条隐藏"的分支里处理，控制条正显示着按返回会直接把播放页关掉。
        // ★BACK 的 DOWN/UP 都接（部分遥控器只发 DOWN，只认 UP 时第一下等于被吞）。
        //   500ms 内只处理一次，正常成对的事件不会双触发，这就是"按两次才退出"的来源。
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
        ) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastBackMs < 500) return true
            lastBackMs = nowMs
            // 一次返回直接回详情页 —— 不再"先收控制条"。
            // 原因：收条那一刻画面必然要重新合成一次，这家 ROM 上会露出根布局黑底（黑一下），
            // 而且第一次按返回看起来"什么都没发生"，还得再按一次才退出。
            // 控制条的显隐留给菜单键（isMenuKeyCode 分支里就是"可见就收、不可见就出"），
            // 返回键只做一件事：退出播放页。
            // ★不再收条：既然这一下就要 finish，先收条只是多一次窗口合成
            //（这家 ROM 上正是那一次合成会露出黑底）。与 5.6 的差别：5.6 分三层，
            // 我们按你的要求合成一跳。
            // 第三层：真的退出。不走 super —— 各 ROM 对 KEYCODE_BACK 的默认处理不一致，
            // 有的会先给 Activity 一次 onKeyDown/onKeyUp 往返（部分机型伴随窗口重建、画面回到首帧），
            // 有的直接 finish。这里先落盘进度再自己 finish，行为确定。
            // ★退场时强制落盘 + 先补建历史行：详情页小窗就是靠这一条位置续播的
            leaving = true
            runCatching { savePosition(force = true, reason = "退场") }
            // 现场对照：不呼出控制条直接返回 → 干净；呼出过控制条再返回 → 黑一下。
            // 差别只有一处：条显示时焦点落在条上的按钮里，条隐藏时焦点在根布局。
            // 退场时若还握着"条内焦点"，窗口要再做一次焦点遍历/重绘，这一帧就会露黑。
            // 所以退场前先把焦点收回根布局、把控制条收掉，动作全在同一个输入帧里完成，
            // 不产生第二帧合成。
            val cf = currentFocus
            val cfId = cf?.id
            runCatching { cf?.clearFocus() }
            runCatching {
                val ok = rootView?.requestFocus()
                MpvDebug.log("[key] back 退场前收回焦点 from=$cfId to=root ok=$ok")
            }
            runCatching {
                controlBar?.visibility = View.GONE
                controlBar?.alpha = 0f
                (controlBar as? android.view.ViewGroup)?.descendantFocusability =
                    android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            MpvDebug.log("[key] back 退出播放页 pos=" + currentPosMs() + " shown=" + shown)
            finish()
            // 关掉 Activity 转场动画：老 ROM 上退场动画那 200~300ms 里窗口先被清成背景色，
            // 看起来就是"黑一下再回详情页"。0,0 直接切过去，详情页立刻接管。
            runCatching { overridePendingTransition(0, 0) }
            return true
        }

        if (shown && !loading) {
            uiHandler.removeCallbacks(autoHide)
            uiHandler.postDelayed(autoHide, 5000)
            // 原版 CustomUpDownView：片头/片尾聚焦时 ↑+1s、↓-1s
            val fid = currentFocus?.id
            // ★条显示、但焦点怎么都拉不进条里时，方向键交给焦点系统 = "按了没反应"。
            //   现场表现正是「只有 OK 有反应、←→ 全废」。这种情况按播放语义处理。
            // ★←→ 从此不再"交焦点系统"。以前条显示时这里 return super，把方向键外包给
            //   DefaultTimeBar 的 onKeyDown；条收起时又回到 handleSeekKey —— 同一个键两个处理器，
            //   这就是"进页面按键无效、按一次菜单之后永久正常"的结构来源（菜单只是把处理器换了一个）。
            //   现在两种情况都由本 Activity 决定：焦点在条内 → 自己调 focusSearch 移焦点；
            //   焦点不在条内 → 直接快进/快退。绝不 return super，也就不再赌视图层的焦点链是否可用。
            if (isLeftKey(event) || isRightKey(event)) {
                val cur = currentFocus
                val inBar = cur != null && cur.id != 0 && isInsideBar(cur)
                if (inBar) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        val next = runCatching {
                            cur!!.focusSearch(if (isLeftKey(event)) View.FOCUS_LEFT else View.FOCUS_RIGHT)
                        }.getOrNull()
                        if (next != null && next !== cur) runCatching { next.requestFocus() }
                        MpvDebug.log(
                            "[key] 条内焦点移动 ${if (isLeftKey(event)) "←" else "→"} 从=${cur!!.id}" +
                                " 到=${next?.id} now=${currentFocus?.id}"
                        )
                    }
                    return true
                }
                MpvDebug.log("[key] 条显示但焦点不在条内 → 快进/快退 kc=${event.keyCode} focus=${cur?.id}")
                handleSeekKey(if (isLeftKey(event)) -1L else 1L, event)
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
            currentFocus?.takeIf { it.id != 0 }?.let { lastBarFocusId = it.id }
            if (isEnterKey(event) && event.action == KeyEvent.ACTION_UP) {
                val v = currentFocus
                val hasClick = v?.hasOnClickListeners() == true
                MpvDebug.log(
                    "[key] OK 落在控制条上 id=${v?.id} cls=${v?.javaClass?.simpleName}" +
                        " text=" + ((v as? TextView)?.text?.toString() ?: "") +
                        " clickable=${v?.isClickable} hasClick=$hasClick"
                )
                // 焦点落在没有点击响应的标签/容器上时，原版什么也不会发生，用户看到的就是
                // "按 OK 没反应"。这种空档直接当成暂停/继续，控件本身有点击的仍交给它自己处理。
                if (!hasClick) {
                    togglePlayPause()
                    return true
                }
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                MpvDebug.log(
                    "[key] 交焦点系统 kc=${event.keyCode} focus=${currentFocus?.id}" +
                        " inBar=${isInsideBar(currentFocus)} lastBarFocus=$lastBarFocusId" +
                        " barAlpha=${controlBar?.alpha} barVis=${controlBar?.visibility}"
                )
            }
            return super.dispatchKeyEvent(event)
        }

        if (isEnterKey(event)) {
            // 长按倍速的收尾仍交给 onKeyUp（fastForwarding）
            if (fastForwarding) return super.dispatchKeyEvent(event)
            // 改成「按下即生效」：进入播放页本身就是按 OK 进来的，
            // 进页面后同一个键的抬起事件最容易在窗口切换/首帧阶段丢掉 ——
            // 丢掉抬起，旧实现就等于没收到这一下，表现就是"进去按 OK 没反应"。
            // 现在 DOWN(repeat=0) 立刻切换，UP 只吞掉不再动作，也不会重复触发。
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    if (loading) {
                        // ★起播前按 OK：内核还是空的，toggle 一定是空操作。
                        //   这里只把"起播后不要自动播"记下来（playUrl 里 wantPlay 读它），
                        //   并复用原版居中提示给可见反馈。
                        pendingPauseIntent = true
                        MpvDebug.log("[key] 起播前 OK → 记 pendingPauseIntent bar=${barShown()}")
                        showPlayCenter()
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
        // ★所有没被上面任何分支接住的键，在这里统一留痕。
        //   这条只是为了让"按了没反应"能立刻变成一行具体键码，不再靠猜。
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            MpvDebug.log("[key] 未接 kc=${event.keyCode}(${KeyEvent.keyCodeToString(event.keyCode)})")
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 沉浸式全屏（对齐原版 BaseActivity.onCreate 的 Util.hideSystemUI）。
     * 缺这一步时内容区会被状态栏/导航栏占掉，PlayerView 铺不满窗口，
     * 四周露出的就是 window 背景（壁纸），浅色壁纸下表现为一圈白边。
     */
    /**
     * 沉浸式全屏 —— 完全照原版 `Util.hideSystemUI(Window)`：
     *
     *     只设 systemUiVisibility，**不碰任何窗口 flag**。
     *
     * 原版在同一台 T1 上「按菜单键」画面不黑，我们之前却黑，差别就在这里：
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

    /** 上一集/下一集：当前线路内切集，边界钳制（对齐原项目 onPrev/onNext + checkPrev/checkNext） */
    private fun switchEpisode(delta: Int) {
        // 直播直链没有「集」的概念，直接忽略（原版同样不提示）
        if (directUrl.isNotBlank()) return
        val line = if (vodLines.isNotEmpty()) vodLines.firstOrNull { it.name == flag } ?: vodLines.first() else return
        val eps = line.episodes
        if (eps.isEmpty()) return
        val cur = eps.indexOfFirst { it.second == epUrl }
        val next = if (delta > 0) (cur + 1).coerceAtMost(eps.lastIndex) else (cur - 1).coerceAtLeast(0)
        // 到头了就停在原地（原版 checkPrev/checkNext 只做按钮置灰，不弹提示）
        if (next == cur) return
        Thread { resolvePlay(line.name, eps[next].second) }.start()
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

    /** 松开 OK 键 → 若处于长按快进，恢复原速（对齐原项目 onSpeedEnd） */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (fastForwarding && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            fastForwarding = false
            speedIdx = savedSpeedIdx
            applySpeed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** 选集面板（原版 control.episodes → onEpisodes；控制条「选集」和菜单键都走这里） */
    private fun openEpisodes() {
        if (vodLines.isEmpty()) {
            Toast.makeText(this, "暂未获取到线路信息（请稍候再点选集）", Toast.LENGTH_LONG).show()
            return
        }
        val cl = vodLines.indexOfFirst { it.name == flag }
        val clIdx = if (cl >= 0) cl else 0
        val ce = runCatching {
            vodLines.getOrNull(clIdx)?.episodes?.indexOfFirst { it.second == epUrl }
        }.getOrDefault(-1) ?: -1
        EpisodeDialog(this, vodLines, { li, ei ->
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
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val now = SystemClock.uptimeMillis()
        MpvDebug.log(
            "[ui] 窗口焦点 hasFocus=$hasFocus decor=${window?.decorView?.width}x${window?.decorView?.height}" +
                " 距onCreate=${if (tCreate > 0) now - tCreate else -1}ms" +
                " 距onResume=${if (tResume > 0) now - tResume else -1}ms"
        )
        if (hasFocus && currentFocus == null) {
            // 进页面时 focus=-1（真机日志确认），窗口拿到焦点但没有任何视图持有焦点，
            // 第一下方向键常常只是"把焦点捡回来"，表现就是"要再点一次才有效"。
            MpvDebug.log("[key] 窗口获焦但无焦点视图，补一次 requestFocus")
            (rootView ?: findViewById<View>(android.R.id.content))?.requestFocus()
        }
        if (hasFocus) {
            // 窗口第一次就绪：把"进页面那一拍按下的菜单"补执行（见 fireMenuKeyOnce 的闸门说明）
            if (pendingMenu) {
                pendingMenu = false
                MpvDebug.log("[key] 窗口就绪，补执行挂起的菜单")
                handleMenuKey()
            }
            // 同理：窗口未就绪时按下的 ←→/OK 也在这里补执行（见 dispatchKeyEvent 的闸门说明）
            if (pendingKeyCode != 0) {
                uiHandler.removeCallbacks(flushPendingKeyRun)
                flushPendingKey()
            }
            // ★这里绝不能再调 setLocalFocus：窗口此刻已经持有焦点，再请求一次会让
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

    /** 原版 BaseActivity:130-133：HDMI 分辨率/HDR 等配置变化后也要重设一次 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
    }

    /**
     * 切后台：暂停**当前内核**并落盘。
     * 对齐原版 VideoActivity:1826-1831（onPause → setBackground(true) + mPlayers.pause() + mClock.stop()）。
     * 原版是统一暂停，不分内核；之前我们只 pause mpv，EXO 在后台会继续跑。
     */
    override fun onPause() {
        seekingByKey = false
        hidePlayCenter()
        super.onPause()
        // 先置后台标记再暂停：mpv 在「后台 + vo 已空」这段时间会短暂掉进 idle-active=true，
        // 内核侧的 END_FILE 异常判定要能看见这个标记，否则会误判成播放中断并静默换线路。
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
     * 回到前台：恢复播放。
     * 对齐原版 VideoActivity:1818-1823（onResume → setBackground(false) + onPlay()），
     * 而 onPlay() 里做的是「加 FLAG_KEEP_SCREEN_ON + mPlayers.play()」。
     *
     * 之前整个 PlayActivity 没有 onResume：onPause 里 pause 过之后就再没人叫醒，
     * 切后台回来画面停在暂停帧（mpv 尤其明显，看起来就像黑屏卡住）。
     *
     * 但只有「叫醒」还不够，还有两个前提要一起覆盖：
     *  1) onResume 往往早于 surfaceCreated，此时 vo 是 null（surfaceDestroyed 置的）。
     *     内核侧 resume() 会把这次恢复记账，等 surfaceCreated 里 attach 完再补发 pause=false。
     *  2) mpv 实例可能在后台被起播 watchdog 置空（超时回退 EXO）。这时 mpv?.resume() 是空操作，
     *     看起来就是「切回来还是死的」，所以 mpv 为 null 时按当前位置重建内核。
     */
    override fun onResume() {
        super.onResume()
        tResume = SystemClock.uptimeMillis()
        // 窗口背景由主题负责（Theme.TVBox windowBackground=@null）。
        // 以前这里在 onResume 里再 setBackgroundDrawable(null) 一次 —— 那是为了修"整窗不透明"，
        // 但同一个动作放在 onResume 等于每次回前台都动一次窗口属性，属于多余动作，已撤。
        // 回到播放页时把焦点兜回来：没有焦点视图的窗口在部分老 ROM 上收不到遥控键，
        // 表现就是"进页面什么都不响应，先按一下菜单键才好"。
        rootView?.post {
            runCatching {
                val f = currentFocus
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

    override fun onDestroy() {
        // ★所有"自我重启"的任务都必须在这里摘掉，否则它们会跨 Activity 生命周期一直跑：
        //   progressTick 每秒、snapTick 每秒、mpvWatchdog 15 秒，autoHide/hideSeekCenterRun 一次性。
        //   以前只清了 snapTick / mpvWatchdog / saveRunnable，progressTick 每次进页面都漏一个。
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
        super.onDestroy()
        sniffer?.cancel()
        sniffer = null
        runCatching { uiHandler.removeCallbacks(flushPendingKeyRun) }
        runCatching { videoHost?.releaseOwner() }
        player?.release()
        player = null
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

/** 只替换视频渲染器的 codec 选择器，音频保持默认（软解问题主要出在视频） */
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
