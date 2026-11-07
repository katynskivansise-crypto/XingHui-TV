package com.xinghui.tv

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
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

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
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
        player = ExoPlayer.Builder(this).build().also { inlineView.player = it }
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
                Log.e("XingHuiTV", "[player] 播放失败 ${error.errorCodeName}，尝试自动换源")
                autoSwitchSource()
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
    }

    /** 显示当前线路的集数列表 */
    private fun renderEpisodes(line: PlayLine) {
        episodes = line.episodes
        // 原版这一行是线路 tab（超级无敌4K / 高清4K / …），选中项标黄
        markActiveFlag(line.name)
        findViewById<RecyclerView>(R.id.detailList).layoutManager = GridLayoutManager(this, 4)
        rebuildGroups()
        showGroup(0)
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
        if (keep >= 0) playInline(keep.coerceIn(0, (line.episodes.size - 1).coerceAtLeast(0)))
    }

    /** 播放失败自动换下一个源（原版同一站点多源自动切换） */
    private var sourceTried = 0
    private fun autoSwitchSource() {
        val v = vod ?: return
        val lines = v.parseLines()
        if (lines.size <= 1) {
            Toast.makeText(this, "该片只有一个源，播放失败", Toast.LENGTH_SHORT).show()
            return
        }
        if (sourceTried >= lines.size - 1) {
            sourceTried = 0
            Toast.makeText(this, "所有源都播放失败", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = lines.indexOfFirst { it.name == currentLine?.name }.let { if (it < 0) 0 else it }
        val next = lines[(cur + 1) % lines.size]
        sourceTried++
        Toast.makeText(this, "「${next.name}」重试", Toast.LENGTH_SHORT).show()
        currentLine = next
        renderEpisodes(next)
        val idx = playingIdx.coerceAtLeast(0)
        playInline(idx.coerceIn(0, (next.episodes.size - 1).coerceAtLeast(0)))
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
        if (playingIdx >= 0) playInline(playingIdx)
    }

    /** 当前线路的第 idx 集（对齐原版 setEpisodeActivated → onRefresh） */
    private fun playInline(idx: Int) {
        val v = vod ?: return
        val line = currentLine ?: v.parseLines().firstOrNull() ?: return
        val ep = line.episodes.getOrNull(idx) ?: return
        playingIdx = idx
        inlineView.visibility = View.VISIBLE
        findViewById<View>(R.id.detailImage)?.visibility = View.GONE
        showVodInfo(ep.first)
        Toast.makeText(this, "准备播放 ${ep.first}", Toast.LENGTH_SHORT).show()
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
                    // 解析不出来就退回独立播放器，让它走完整的嗅探/迅雷链路
                    Toast.makeText(this, "内嵌解析失败（$err），转播放页", Toast.LENGTH_SHORT).show()
                    openPlayPage(idx)
                    return@runOnUiThread
                }
                player?.setMediaItem(MediaItem.Builder().setUri(url).build())
                player?.prepare()
                player?.play()
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
        startActivity(Intent(this, PlayActivity::class.java).apply {
            putExtra("vod_id", v.vodId)
            putExtra("vod_name", v.vodName)
            putExtra("vod_pic", v.vodPic)
            putExtra("flag", line.name)
            putExtra("ep_url", ep.second)
        })
    }

    /** 进全屏：播放器铺满整页，其余区域隐藏（对齐原版 enterFullscreen） */
    private fun enterInlineFullscreen() {
        if (inlineFullscreen) return
        inlineFullscreen = true
        inlineView.visibility = View.VISIBLE
        val box = findViewById<FrameLayout>(R.id.playerBox)
        box.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
        )
        listOf(R.id.btnBack, R.id.detailName, R.id.detailInfo, R.id.detailDesc,
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
        box.layoutParams = android.widget.LinearLayout.LayoutParams(
            0, if (boxH > 0) boxH else (270 * resources.displayMetrics.density).toInt(), 1.2f
        )
        listOf(R.id.btnBack, R.id.detailName, R.id.detailInfo, R.id.detailDesc,
            R.id.btnDesc, R.id.btnFav, R.id.btnSource, R.id.flagRow, R.id.groupRow, R.id.detailList).forEach {
            findViewById<View>(it)?.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.detailList).requestFocus()
    }

    override fun onBackPressed() {
        // 全屏时返回键先退全屏，不直接退出页面（对齐原版 mKeyDown 的返回语义）
        if (inlineFullscreen) {
            exitInlineFullscreen()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
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
