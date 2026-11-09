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
class DetailActivity : Activity() {

    private lateinit var store: HistoryStore
    private var vod: Vod? = null
    private var currentLine: PlayLine? = null

    /** 内嵌播放器（对齐原版 mBinding.video） */
    private var player: ExoPlayer? = null
    private lateinit var inlineView: PlayerView
    private var inlineFullscreen = false
    /** 预览是否因为离开页面被我们主动暂停（回来时不自动续播） */
    private var inlinePausedByLeave = false
    /** 本次离开是「跳去独立播放页」，回来要把预览续上；按返回退出详情页则不用 */
    private var inlineResumeOnReturn = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        store = HistoryStore(this)
        val vodId = intent.getStringExtra("vod_id") ?: ""

        val list = findViewById<RecyclerView>(R.id.detailList)
        // 集数聚焦用黄字（对齐原版 01[1080P] 的高亮），分类条等其它地方不受影响
        adapter = ListAdapter(pairs, null, Color.rgb(255, 204, 0)) { key -> onPick(key) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // 返回按钮按原版去掉（原版 VodActivity 靠系统返回键），布局里也不再声明
        findViewById<View>(R.id.btnFav).setOnClickListener { toggleFav() }
        findViewById<View>(R.id.btnDesc).setOnClickListener { showDescDialog() }
        findViewById<View>(R.id.btnSource).setOnClickListener { switchSource() }
        // 倒序：整段集数反向排（原版「倒序 →」那个按钮）
        findViewById<View>(R.id.btnOrder).setOnClickListener {
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
        inlineView = findViewById(R.id.inlinePlayer)
        // 内嵌播放器之前是裸 build()：没有轨道选择参数，没有解码方式，也没有请求头。
        // 照原版 ExoUtil 补齐 TrackSelector（最高码率 / 字幕语言 / 隧道模式），
        // 解码方式跟播放页共用同一个值。
        player = buildInlinePlayer()
        // 播放器框按 16:9 收口：之前 height 写死 270dp、宽度随 weight 撑开，
        // 比例约 2.6:1，画面只能居中留大黑边（真机上看着就是「缩放不对」）。
        findViewById<View>(R.id.playerBox)?.let { boxView ->
            boxView.post {
                boxH = boxView.width * 9 / 16
                boxView.layoutParams = boxView.layoutParams.apply { height = boxH }
            }
        }
        // 原版同一个站点有多个播放源时，某个源播不动会自动切下一个
        player?.addListener(object : androidx.media3.common.Player.Listener {
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
                    findViewById<TextView>(R.id.vodInfoSize)?.text = "$w x $h"
                }
            }
        })
        findViewById<View>(R.id.playerBox).setOnClickListener {
            if (!inlineFullscreen) enterInlineFullscreen()
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
                val rf = if (Settings(this@DetailActivity).decodeMode == 0)
                    SoftRenderersFactory(this@DetailActivity)
                else
                    androidx.media3.exoplayer.DefaultRenderersFactory(this@DetailActivity)
                setRenderersFactory(rf.setEnableDecoderFallback(true))
                // 与播放页同源，对齐原版 ExoUtil.buildLoadControl()：new DefaultLoadControl()。
                // 之前按 exoBufferSec 把缓冲砍到 5s/10s 是负优化，已回滚（默认 50s）。
                setLoadControl(androidx.media3.exoplayer.DefaultLoadControl())
                val ts = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this@DetailActivity)
                ts.setParameters(
                    ts.buildUponParameters()
                        .setPreferredTextLanguage(java.util.Locale.getDefault().getISO3Language())
                        .setForceHighestSupportedBitrate(true)
                        .setTunnelingEnabled(Settings(this@DetailActivity).tunnelMode == 1)
                )
                setTrackSelector(ts)
                // 内嵌播放器之前是裸 build()：没有超时、没有跨协议重定向。
                val timeoutMs = Settings(this@DetailActivity).networkTimeoutSec.coerceIn(5, 120) * 1000
                val http = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(timeoutMs)
                    .setReadTimeoutMs(timeoutMs)
                    .setAllowCrossProtocolRedirects(true)
                // 同播放页：包一层 DefaultDataSource（原版 MediaSourceFactory:90），
                // 本地路径不必再依赖 UrlConvert 转写
                setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        androidx.media3.datasource.DefaultDataSource.Factory(this@DetailActivity, http)
                    )
                )
            }
            .build().also {
                // 官方 Players.java:158：HDMI / 蓝牙音频断开时自动暂停
                it.setHandleAudioBecomingNoisy(true)
                // 内嵌预览同样请求音频焦点：进播放页时播放页抢焦点，这一路会自动 LOSS → 暂停，
                // 不再依赖手动 pauseInlinePlayback 兜底（原版 Players.java:155 同一句）。
                it.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                inlineView.player = it
            }
        return p
    }

    private fun applyCaptionStyle() {
        val sv = inlineView.subtitleView ?: return
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

        findViewById<TextView>(R.id.detailName).text = v.vodName
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
        findViewById<TextView>(R.id.detailInfo).text = info.ifBlank { "暂无信息" }
        findViewById<TextView>(R.id.detailDesc).text = v.vodContent.ifBlank { "暂无简介" }
        if (Settings(this).showPoster) {
            AsyncImageLoader.load(findViewById(R.id.detailImage), v.vodPic)
        }
        applyFavState(store.isFavorite(v.vodId))

        pairs.clear()
        val lines = v.parseLines()
        if (lines.isEmpty()) {
            showLines = true
            pairs.add("none" to "无播放线路")
            buildFlags(emptyList())
            findViewById<RecyclerView>(R.id.detailList).layoutManager = LinearLayoutManager(this)
            adapter.notifyDataSetChanged()
            return
        }
        // 对齐原版：进详情页直接展开第一条线路的集数网格，焦点落在第一集，
        // 按确认即起播。原版没有「先选线路、再选集数」这一层，多条线路用「换源」切换。
        currentLine = lines.first()
        showLines = false
        buildFlags(lines)
        renderEpisodes(lines.first())
        findViewById<RecyclerView>(R.id.detailList)?.let { rv ->
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
        val hKey = (v0?.vodId.orEmpty()).ifBlank { v0?.vodName.orEmpty() }
        val last = runCatching { HistoryStore(this).epUrlOf(hKey) }.getOrDefault("")
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
        val rv = findViewById<RecyclerView>(R.id.detailList)
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
        val info = findViewById<View>(R.id.vodInfo) ?: return
        val name = findViewById<TextView>(R.id.vodInfoTitle) ?: return
        name.text = if (currentLine != null) "${currentLine!!.name} · $title" else title
        info.visibility = View.VISIBLE
        info.postDelayed({ info.visibility = View.GONE }, 3000)
    }

    /** 填充播放源 tab：原版 flag 行就是同一站点的全部播放源（超级无敌4K / 高清4K / …） */
    private fun buildFlags(lines: List<PlayLine>) {
        val box = findViewById<android.widget.LinearLayout>(R.id.flagItems) ?: return
        box.removeAllViews()
        val row = findViewById<View>(R.id.flagRow)
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
        val box = findViewById<android.widget.LinearLayout>(R.id.flagItems) ?: return
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
        val box = findViewById<android.widget.LinearLayout>(R.id.groupItems)
        box.removeAllViews()
        val row = findViewById<View>(R.id.groupRow)
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
                // 对齐原版 shouldEnterFullscreen：点到「正在播的这一集」就进全屏，
                // 点到别的集才是换集。所以集数上按一次是换集，再按一次是全屏。
                if (idx == playingIdx) {
                    enterInlineFullscreen()
                    return
                }
                playInline(idx)
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
    private fun playInline(idx: Int, auto: Boolean = false, depth: Int = 0) {
        val v = vod ?: return
        val line = currentLine ?: v.parseLines().firstOrNull() ?: return
        // 集数列表以「当前展示的 episodes」为准：磁力/种子被 expandThunder 展开后，
        // line.episodes 还是原始未展开的那份，用它会取错集或越界（点了不播）。
        val ep = episodes.getOrNull(idx)
            ?: line.episodes.getOrNull(idx)
            ?: run { Log.e("XingHuiTV", "[detail] 集索引越界 idx=$idx eps=${episodes.size}"); return }
        playingIdx = idx
        inlineView.visibility = View.VISIBLE
        findViewById<View>(R.id.detailImage)?.visibility = View.GONE
        showVodInfo(ep.first)
        Toast.makeText(this, "准备播放 ${ep.first}", Toast.LENGTH_SHORT).show()
        // 续播：原版 VideoActivity 打开即 setPosition(Math.max(opening, history.position))。
        // 这里只在「和上次是同一集」时才带位置（HistoryStore.position 内部会核对集地址），
        // 换集就是从 0 开始，不会出现点第 3 集跳到第 1 集进度的情况。
        val resumeMs = runCatching {
            HistoryStore(this).marks(v.vodId.ifBlank { v.vodName }, ep.second)[2]
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
        releaseInlinePlayback()
        // 这是「去播放页」，不是「离开详情页」：回来应当自动续上
        inlineResumeOnReturn = true
        startActivity(Intent(this, PlayActivity::class.java).apply {
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
        inlineView.visibility = View.VISIBLE
        val box = findViewById<FrameLayout>(R.id.playerBox)
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
            findViewById<View>(it)?.visibility = View.GONE
        }
        box.requestFocus()
    }

    /** 退出全屏：恢复小窗与其它区域（原版退出全屏走返回键，这里同样） */
    private fun exitInlineFullscreen() {
        if (!inlineFullscreen) return
        inlineFullscreen = false
        val box = findViewById<FrameLayout>(R.id.playerBox)
        // 还原进全屏时保存的那份参数（类型与父容器天然匹配，不做任何强转）
        savedBoxParams?.let { box.layoutParams = it }
        // 对齐原版 exitFullscreen():866 —— setForeground(selector_video) 还原小窗焦点边框
        box.foreground = savedBoxForeground ?: runCatching {
            getDrawable(R.drawable.selector_video)
        }.getOrNull()
        listOf(R.id.detailName, R.id.detailInfo, R.id.detailDesc,
            R.id.btnDesc, R.id.btnFav, R.id.btnSource, R.id.flagRow, R.id.groupRow, R.id.detailList).forEach {
            findViewById<View>(it)?.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.detailList).requestFocus()
    }

    /**
     * 菜单键（原版 VideoActivity.dispatchKeyEvent:1686-1687 只在全屏时分流）。
     * 详情页内嵌播放器没有原版那套控制条 UI，所以菜单键直接把当前集转独立播放页
     * PlayActivity —— 那才是原版 VideoActivity 的形态，控制条、选集、选轨全都在。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val menu = event.keyCode == KeyEvent.KEYCODE_MENU
        if (inlineFullscreen && menu) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                openPlayPageFromCurrent()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** 把当前正在播的这一集原样交给独立播放页 */
    private fun openPlayPageFromCurrent() {
        val v = vod ?: return
        val line = currentLine ?: return
        val ep = episodes.getOrNull(playingIdx) ?: line.episodes.getOrNull(playingIdx) ?: return
        // 同上：交接给播放页之前先停掉预览
        releaseInlinePlayback()
        inlineResumeOnReturn = true
        startActivity(Intent(this, PlayActivity::class.java).apply {
            putExtra("vod_id", v.vodId)
            putExtra("vod_name", v.vodName)
            putExtra("vod_pic", v.vodPic)
            putExtra("flag", line.name)
            putExtra("ep_url", ep.second)
        })
    }

    override fun onBackPressed() {
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
            HistoryStore(this).setPosition(v.vodId.ifBlank { v.vodName }, ep.second, pos)
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
    private fun releaseInlinePlayback() {
        runCatching {
            inlineView.player = null
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
        // 退到后台、按返回、切播放页，都先落一次进度；否则只有正常看完才记得住
        saveInlinePosition()
        // 同时停声：只落进度不停播放，离开页面后音频会继续在后台响
        pauseInlinePlayback()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // 分两种「回来」：
        //   1. 从我们自己的播放页返回 → 预览要继续播（否则小窗停在离开时那一帧，
        //      看着像卡住/退回第一屏）；这是本页自己发起的跳转，续播是预期行为
        //   2. 从别处回来（首页回来、系统弹窗回来）→ 保持原样，不自动出声
        if (inlineResumeOnReturn) {
            inlineResumeOnReturn = false
            runCatching {
                // 进播放页时预览已释放，这里就地重建（参数与首次完全一致）
                val p = player ?: buildInlinePlayer().also { player = it; applyCaptionStyle() }
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
                    val key = v.vodId.ifBlank { v.vodName }
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
            }
        }
        inlinePausedByLeave = false
    }

    override fun onDestroy() {
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
        val btn = findViewById<TextView>(R.id.btnFav) ?: return
        btn.text = "收藏"
        btn.setCompoundDrawablesWithIntrinsicBounds(
            if (fav) R.drawable.ic_detail_keep_on else R.drawable.ic_detail_keep_off,
            0, 0, 0
        )
        btn.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
    }
}
