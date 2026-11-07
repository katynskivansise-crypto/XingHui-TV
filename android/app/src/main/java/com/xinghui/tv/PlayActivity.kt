package com.xinghui.tv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
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
import androidx.media3.ui.PlayerView
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
        setContentView(R.layout.activity_play)
        // 对齐原版 VideoActivity（继承 BaseActivity）：窗口同样是壁纸，播放器自身黑底压住
        runCatching { WallpaperStore.apply(this) }

        vodId = intent.getStringExtra("vod_id") ?: ""
        vodName = intent.getStringExtra("vod_name") ?: ""
        vodPic = intent.getStringExtra("vod_pic") ?: ""
        flag = intent.getStringExtra("flag") ?: ""
        epUrl = intent.getStringExtra("ep_url") ?: ""
        directUrl = intent.getStringExtra("direct_url") ?: ""
        siteKey = SubscriptionStore(this).currentKey() ?: ""

        playerView = findViewById<PlayerView>(R.id.playView)
        // 画面缩放（对齐原版 VideoActivity.setScale：读 Setting.getScale 并 apply）
        scaleIdx = Settings(this).playerScale.coerceIn(0, scaleModes.size - 1)
        playerView?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale)?.text = "缩放 ${scaleNames[scaleIdx]}"
        player = ExoPlayer.Builder(this).build().also { playerView?.player = it }
        attachPlayerListeners()
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
        var sniffTarget = ""
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
            //   playUrl 以 "json:" 开头  → type=1 JSON 解析（GET 前缀+url，返回 {"url":"真实m3u8"}）
            //   playUrl 以 "parse:" 开头 → 使用指定名称的解析器
            //   parse=1 且两者都没有 → 嗅探（type=0，需 WebView）
            // 实测当前订阅：parses 共 29 条，24 条 type=0 需 WebView、4 条 type=1 站点已全部失联
            // （pandown.pro / mfjx.iwsyy.xyz 拒连、api.tyun77.cn 403、zy.qiaoji8.com 404）、
            // 1 条 type=3 依赖 jar，因此这里不做解析实现，只识别并给出准确原因，
            // 避免"黑屏但不知道是解析站挂了"。
            val playUrlRaw = obj.optString("playUrl", "")
            when {
                // json: / parse: 协议 → 实测订阅里这两类解析站全不可用，给准确原因即可
                playUrlRaw.startsWith("json:") || playUrlRaw.startsWith("parse:") -> {
                    err = "该片源需要解析接口（${playUrlRaw.take(40)}…），订阅里的解析站已失效"
                    Log.e("XingHuiTV", "[play] 需要二次解析但解析站不可用 prefix=${playUrlRaw.take(60)}")
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
                Log.e("XingHuiTV", "[play] 迅雷体系 → ${url.take(70)}")
                url = ThunderPlay.resolve(url)
                if (qualities.isEmpty()) qualities.add("自动" to url)
            }
            flag = fl
            epUrl = ep
        }.onFailure { err = it.message ?: "解析失败" }
        // 嗅探分支：url 不是媒体直链、playUrl 是解析页 → 交给 WebView 抓真实地址
        if (sniffTarget.isNotBlank()) {
            runOnUiThread { startSniff(sniffTarget, headers) }
            return
        }
        if (url.isBlank() || (err.isNotBlank() && !looksLikeMedia(url))) {
            // err 非空且 url 不是媒体直链：把"需要解析接口"这类原因直接抛给用户，
            // 而不是硬塞一个网页地址给 ExoPlayer 变成无限转圈
            onPlayFail(if (err.isBlank()) "未解析到播放地址：$fl" else "解析失败[$fl]：$err")
            return
        }
        runOnUiThread {
            // spider 可能返回 proxy://xxx 这类本地 scheme，进播放器前统一重写成 http://127.0.0.1:9978/...
            playUrl(UrlConvert.convert(url), headers)
            updateQualityBtn()
            recordHistory()
        }
    }

    /** 判断地址是否已经是可直接播放的媒体直链 */
    private var sniffer: SniffWebView? = null

    /**
     * type=0 嗅探（对齐原版 ParseJob case 0 → CustomWebView）。
     * 打开 解析页地址 = playUrl + 真实地址，从 WebView 的网络请求里截获 m3u8/mp4。
     */
    private fun startSniff(target: String, hdrs: Map<String, String>) {
        sniffer?.cancel()
        sniffer = SniffWebView(
            this,
            onFound = { u, h ->
                runOnUiThread {
                    val merged = if (hdrs.isEmpty()) h else hdrs + h
                    playUrl(UrlConvert.convert(u), merged)
                    updateQualityBtn()
                    recordHistory()
                }
            },
            onFail = { why ->
                runOnUiThread { onPlayFail("嗅探失败[$flag]：$why") }
            },
        )
        sniffer?.start(target, hdrs)
    }

    private fun looksLikeMedia(u: String): Boolean {
        val lower = u.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv") ||
            lower.contains(".mkv") || lower.contains(".ts") || lower.contains(".mp3")
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
        player?.apply {
            val b = MediaItem.Builder().setUri(v).setMediaId(v)
            if (activeSubs.isNotEmpty()) b.setSubtitleConfigurations(activeSubs)
            setMediaItem(b.build())
            prepare()
            if (pos > 0) seekTo(pos)
            playWhenReady = wasPlaying
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

    /** 上次落盘的位置，避免重复写同一进度把 SharedPreferences 打爆 */
    private var lastSavedPos: Long = 0

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnable = object : Runnable {
        override fun run() {
            savePosition()
            saveHandler.postDelayed(this, 15_000)
        }
    }

    /** 统一挂播放器监听：onCreate 和 rebuildPlayer 都要挂，避免两处逻辑漂移 */
    private fun attachPlayerListeners() {
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (directUrl.isNotBlank()) return // 直播直链不自动换线路
                onPlayFail("播放出错：${error.errorCodeName}")
            }

            /** 就绪后跳续播位置：太靠近片尾（不足 5 秒）就当作看完了，从头播 */
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY || pendingSeek <= 0) return
                val target = pendingSeek
                pendingSeek = 0
                val p = player ?: return
                if (p.duration > 0 && target < p.duration - 5_000) {
                    p.seekTo(target)
                    Toast.makeText(this@PlayActivity, "续播 ${target / 1000} 秒", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) scheduleSave() else savePosition()
            }
        })
    }

    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 15_000)
    }

    /** 落盘当前播放位置；异常值/片尾不记（播完应从头看，不该一跳就跳结尾） */
    private fun savePosition() {
        val p = player ?: return
        val pos = runCatching { p.currentPosition }.getOrDefault(0)
        if (pos <= 0 || pos == lastSavedPos) return
        val dur = runCatching { p.duration }.getOrDefault(0)
        if (dur > 0 && pos >= dur - 10_000) return
        lastSavedPos = pos
        HistoryStore(this).setPosition(vodId, epUrl, pos)
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
        // 续播：同一集上次看到哪儿，就绪后跳过去
        pendingSeek = HistoryStore(this).position(vodId, epUrl)
        lastSavedPos = 0
        // mpv 内核：交给外部播放器
        if (PlayerManager.shouldUseMpv(this)) {
            if (PlayerManager.launchMpv(this, url, vodName)) {
                finish()
                return
            }
        }
        // header 与当前播放器不一致时重建（播放接口返回的 UA/Referer 需注入数据源）
        if (effHeaders != activeHeaders) {
            rebuildPlayer(effHeaders)
        }
        player?.apply {
            val itemBuilder = MediaItem.Builder().setUri(url).setMediaId(url)
            // 字幕轨：对齐原版 ExoUtil.getMediaItem → setSubtitleConfigurations(getSubtitleConfigs(subs))
            if (activeSubs.isNotEmpty()) itemBuilder.setSubtitleConfigurations(activeSubs)
            setMediaItem(itemBuilder.build())
            prepare()
            playWhenReady = true
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
        attachPlayerListeners()
        player?.setPlaybackSpeed(speedSteps[speedIdx])
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
        Settings(this).playerScale = scaleIdx // 对齐原版 putScale：退出后仍保持
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

    /** 切后台/被遮挡时立即落盘，避免只靠 15 秒定时那一次丢掉最后片段 */
    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onDestroy() {
        saveHandler.removeCallbacks(saveRunnable)
        savePosition()
        super.onDestroy()
        sniffer?.cancel()
        sniffer = null
        player?.release()
        player = null
    }
}
