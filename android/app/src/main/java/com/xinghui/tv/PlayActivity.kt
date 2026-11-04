package com.xinghui.tv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.xinghui.tv.dialog.EpisodeDialog
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
    private var vodName = ""
    private var vodPic = ""
    private var flag = ""
    private var epUrl = ""
    private var directUrl = ""
    private var siteKey = ""

    private var vodLines: List<PlayLine> = emptyList()

    // 倍速档位（对齐原项目点击循环 addSpeed；Up/Down 加减 0.25）
    private val speedSteps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = 2 // 默认 1.0x
    private var fastForwarding = false
    private var savedSpeedIdx = 2
    private var playerView: PlayerView? = null
    private var scaleIdx = 0
    private val scaleModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val scaleNames = arrayOf("等比", "拉伸", "裁剪")

    // 清晰度（对齐原版 Url.values）：多清晰度切换；单清晰度时隐藏按钮
    private val qualities = mutableListOf<Pair<String, String>>() // (name, v)
    private var qualityIdx = 0

    // 自动换站（对齐原版 checkSearch→nextSite）：线路走完失败后自动搜同片名其他站点；限次防死循环
    private var siteSwitchCount = 0
    private val MAX_SITE_SWITCH = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play)

        vodId = intent.getStringExtra("vod_id") ?: ""
        vodName = intent.getStringExtra("vod_name") ?: ""
        vodPic = intent.getStringExtra("vod_pic") ?: ""
        flag = intent.getStringExtra("flag") ?: ""
        epUrl = intent.getStringExtra("ep_url") ?: ""
        directUrl = intent.getStringExtra("direct_url") ?: ""
        siteKey = SubscriptionStore(this).currentKey() ?: MainActivity.DEFAULT_KEY

        playerView = findViewById<PlayerView>(R.id.playView)
        player = ExoPlayer.Builder(this).build().also { playerView?.player = it }
        // 播放出错 → 自动换源重试（对齐原项目 onErrorEvent→startFlow→checkFlag 线路轮换）
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (directUrl.isNotBlank()) return // 直播直链不自动换线路
                onPlayFail("播放出错：${error.errorCodeName}")
            }
        })
        findViewById<TextView>(R.id.playTitle).text = vodName.ifBlank { "播放中" }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        // 倍速：点击循环档位，对齐原项目 addSpeed()
        val speedBtn = findViewById<TextView>(R.id.btnSpeed)
        speedBtn.setOnClickListener { toggleSpeed() }
        // 长按倍速快进（对齐原项目 onSpeedUp：长按 3x，松开恢复）
        speedBtn.setOnLongClickListener {
            if (PlayerManager.shouldUseMpv(this)) return@setOnLongClickListener true
            fastForwarding = true
            savedSpeedIdx = speedIdx
            speedBtn.text = "快进 3x（松开恢复）"
            player?.setPlaybackSpeed(3f)
            true
        }
        // 缩放档位循环（对齐原项目 onScale/setScale）
        findViewById<TextView>(R.id.btnScale).setOnClickListener { toggleScale() }
        // 上一集/下一集（对齐原项目 control.prev/next → checkPrev/checkNext）
        findViewById<View>(R.id.btnPrev).setOnClickListener { switchEpisode(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { switchEpisode(1) }
        // 清晰度切换（对齐原项目 QualityAdapter/setQualityActivated）
        findViewById<View>(R.id.btnQuality).setOnClickListener { toggleQuality() }

        // 直播直链：直接播放；影片：先拉详情拿线路（供选集面板），再解析播放
        if (directUrl.isNotBlank()) {
            playUrl(directUrl)
            recordHistory()
        } else {
            Thread { fetchAndPlay(vodId, flag, epUrl) }.start()
        }
    }

    /** 拉详情（取线路列表）→ 解析指定线路/集播放 */
    private fun fetchAndPlay(vid: String, fl: String, ep: String) {
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
        }
        resolvePlay(fl, ep)
    }

    /** 调用引擎 player 接口解析出播放地址；csp_ 源走壳侧并带 header；失败进入自动换源重试链 */
    private fun resolvePlay(fl: String, ep: String) {
        var url = ""
        var err = ""
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
            url = obj.optString("url", "")
            if (site?.isCsp() == true) headers = CspLoader.playerHeaders(play)
            // 多清晰度（引擎已把 TVBox `#` 分隔拆成 urls）
            val urls = obj.optJSONArray("urls")
            if (urls != null && urls.length() > 1) {
                for (i in 0 until urls.length()) {
                    val u = urls.optJSONObject(i) ?: continue
                    qualities.add(u.optString("name", "清晰度${i + 1}") to u.optString("v", ""))
                }
            } else if (url.isNotBlank()) {
                qualities.add("自动" to url)
            }
            flag = fl
            epUrl = ep
        }.onFailure { err = it.message ?: "解析失败" }
        if (url.isBlank()) {
            onPlayFail(if (err.isBlank()) "未解析到播放地址：$fl" else "解析失败[$fl]：$err")
            return
        }
        runOnUiThread {
            playUrl(url, headers)
            updateQualityBtn()
            recordHistory()
        }
    }

    /** 播放/解析失败 → 先切下一条线路，再自动换站点；都失败才停止（对齐原项目 startFlow→checkFlag→checkSearch） */
    private fun onPlayFail(msg: String) {
        if (tryAutoSwitch()) return
        if (siteSwitchCount < MAX_SITE_SWITCH && autoSwitchSite()) return
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun tryAutoSwitch(): Boolean {
        val next = nextLineIndex()
        if (next == null) return false
        val line = vodLines[next]
        runOnUiThread { Toast.makeText(this, "播放失败，自动切换线路：${line.name}", Toast.LENGTH_SHORT).show() }
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
        siteKey = target.source
        runOnUiThread {
            Toast.makeText(this, "自动切换到站点[${target.source}]，尝试播放《${target.vodName}》", Toast.LENGTH_LONG).show()
        }
        Thread { fetchAndPlay(target.vodId, "", "") }.start()
        return true
    }

    /** 清晰度：点击循环档位（对齐原项目 setQualityActivated） */
    private fun toggleQuality() {
        if (qualities.size < 2 || PlayerManager.shouldUseMpv(this)) return // mpv 外部播放器由播放器控制
        qualityIdx = (qualityIdx + 1) % qualities.size
        val v = qualities[qualityIdx].second
        updateQualityBtn()
        player?.apply {
            setMediaItem(MediaItem.fromUri(v))
            prepare()
            playWhenReady = true
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

    /** 播放地址；csp_ 源播放需按接口返回的 header（UA/Referer）构建请求 */
    private var activeHeaders: Map<String, String> = emptyMap()

    private fun playUrl(url: String, headers: Map<String, String> = emptyMap()) {
        if (url.isBlank()) {
            Toast.makeText(this, "未解析到播放地址", Toast.LENGTH_LONG).show()
            return
        }
        // mpv 内核：交给外部播放器
        if (PlayerManager.shouldUseMpv(this)) {
            if (PlayerManager.launchMpv(this, url, vodName)) {
                finish()
                return
            }
        }
        // header 与当前播放器不一致时重建（csp_ 源播放地址需带 UA/Referer）
        if (headers != activeHeaders) {
            rebuildPlayer(headers)
        }
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    /** 重建播放器；headers 为空时用默认（普通源），非空时注入请求头（csp_ 源） */
    private fun rebuildPlayer(headers: Map<String, String>) {
        player?.release()
        activeHeaders = headers
        val builder = ExoPlayer.Builder(this)
        if (headers.isNotEmpty()) {
            val dataSourceFactory =
                DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        }
        player = builder.build().also { playerView?.player = it }
        // 播放出错 → 自动换源重试（对齐原项目 onErrorEvent→startFlow→checkFlag 线路轮换）
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (directUrl.isNotBlank()) return // 直播直链不自动换线路
                onPlayFail("播放出错：${error.errorCodeName}")
            }
        })
        player?.setPlaybackSpeed(speedList[speedIdx])
    }

    private fun recordHistory() {
        HistoryStore(this).addHistory(vodId, vodName, vodPic, "", flag, epUrl)
    }

    /** 倍速：点击循环档位（对齐原项目 onSpeed()/addSpeed()） */
    private fun toggleSpeed() {
        speedIdx = (speedIdx + 1) % speedSteps.size
        applySpeed()
    }

    /** 缩放：点击循环档位（对齐原项目 onScale/setScale） */
    private fun toggleScale() {
        if (PlayerManager.shouldUseMpv(this)) return // 外部播放器由 mpv 控制
        scaleIdx = (scaleIdx + 1) % scaleModes.size
        playerView?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale).text = "缩放 ${scaleNames[scaleIdx]}"
    }

    /** 上一集/下一集：当前线路内切集，边界钳制（对齐原项目 onPrev/onNext + checkPrev/checkNext） */
    private fun switchEpisode(delta: Int) {
        if (directUrl.isNotBlank()) {
            Toast.makeText(this, "直播直链不支持切集", Toast.LENGTH_SHORT).show()
            return
        }
        val line = if (vodLines.isNotEmpty()) vodLines.firstOrNull { it.name == flag } ?: vodLines.first() else return
        val eps = line.episodes
        if (eps.isEmpty()) return
        val cur = eps.indexOfFirst { it.second == epUrl }
        val next = if (delta > 0) (cur + 1).coerceAtMost(eps.lastIndex) else (cur - 1).coerceAtLeast(0)
        if (next == cur) {
            Toast.makeText(this, if (delta > 0) "已是最后一集" else "已是第一集", Toast.LENGTH_SHORT).show()
            return
        }
        Thread { resolvePlay(line.name, eps[next].second) }.start()
    }

    private fun applySpeed() {
        val s = speedSteps[speedIdx]
        findViewById<TextView>(R.id.btnSpeed).text = "倍速 ${s}x"
        // mpv 内核交给外部，倍速由外部播放器控制
        if (PlayerManager.shouldUseMpv(this)) return
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

    /** 菜单键 → 选集/换源面板 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (vodLines.isNotEmpty()) {
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
                return true
            } else {
                Toast.makeText(this, "暂未获取到线路信息（请稍候再按菜单键）", Toast.LENGTH_LONG).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
