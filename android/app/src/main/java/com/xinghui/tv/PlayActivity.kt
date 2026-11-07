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
        // 恢复：对齐原版 VideoActivity（继承 BaseActivity）—— 窗口同样挂壁纸，播放器自身黑底压住。
        // 之前为了压掉「四周白条」临时改成纯黑，属于主动偏离原版，按用户要求恢复。
        // 白边的真正解法是让内容区真正全屏（Theme.TVBox + LAYOUT_FULLSCREEN），不是靠撤壁纸。
        runCatching { WallpaperStore.apply(this) }

        vodId = intent.getStringExtra("vod_id") ?: ""
        vodName = intent.getStringExtra("vod_name") ?: ""
        vodPic = intent.getStringExtra("vod_pic") ?: ""
        flag = intent.getStringExtra("flag") ?: ""
        epUrl = intent.getStringExtra("ep_url") ?: ""
        directUrl = intent.getStringExtra("direct_url") ?: ""
        siteKey = SubscriptionStore(this).currentKey() ?: ""

        playerView = findViewById<PlayerView>(R.id.playView)
        // 画面缩放（对齐原版 VideoActivity.getScale：本片记录优先，-1 才回落全局 Setting.getScale）
        scaleIdx = HistoryStore(this).scaleOf(vodId)
            .takeIf { it >= 0 } ?: Settings(this).playerScale
        scaleIdx = scaleIdx.coerceIn(0, scaleModes.size - 1)
        playerView?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale)?.text = scaleNames[scaleIdx]
        // mpv 内核下不必先建 EXO 实例：T1 只有 2GB 内存，两套解码器并存很吃紧。
        // 切回 EXO 时 rebuildPlayer() 会补建，所以这里可以省。
        if (!isMpvMode()) {
            player = ExoPlayer.Builder(this).build().also { playerView?.player = it }
            attachPlayerListeners()
        }
        findViewById<TextView>(R.id.playTitle).text = vodName.ifBlank { "播放中" }
        findViewById<View>(R.id.playTitle)?.visibility = View.GONE

        applyImmersive()
        // 原版播放页没有返回按钮（VideoActivity 靠系统返回键），这里同样不摆
        // 控制条按钮全部绑定（结构照原版 view_control_vod.xml）
        bindControlBar()
        updateDecodeText()
        updatePlayerText()
        openingMs = HistoryStore(this).openingOf(vodId)
        endingMs = HistoryStore(this).endingOf(vodId)
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
                Log.e("XingHuiTV", "[play] 迅雷体系 → ${url.take(70)}")
                ThunderPlay.ensureInit(this)
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
                    playUrl(toPlayableUrl(u) ?: u, merged)
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
                if (state == Player.STATE_ENDED) {
                    handleEnded()
                    return
                }
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
        if (isMpvMode()) {
            val pos = mpvPos
            if (pos <= 0 || pos == lastSavedPos) return
            val dur = mpvDur
            if (dur > 0 && pos >= dur - 10_000) return
            lastSavedPos = pos
            HistoryStore(this).setPosition(vodId, epUrl, pos)
            return
        }
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
        activeUrl = url
        // 片头/片尾按片记忆（对齐原版 History.getOpening/getEnding）
        openingMs = HistoryStore(this).openingOf(vodId)
        endingMs = HistoryStore(this).endingOf(vodId)
        endingDone = false
        updateOpeningText()
        updateEndingText()
        // 起播定位：原版 VideoActivity.setPosition(Math.max(opening, position))
        pendingSeek = if (resumeOverride >= 0) resumeOverride
                      else maxOf(openingMs, HistoryStore(this).position(vodId, epUrl))
        resumeOverride = -1
        lastSavedPos = 0
        // mpv 内核：本进程内 libmpv（内嵌），播放页控制条直接控它
        if (isMpvMode()) {
            startMpv(url, effHeaders, pendingSeek)
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
        if (applyMediaSource(itemBuilder)) {
            setKeepScreenOn(true)
            player?.playWhenReady = true
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

    private fun rebuildPlayer(headers: Map<String, String>) {
        player?.release()
        activeHeaders = headers
        val builder = ExoPlayer.Builder(this)
        // 解码方式：0=软解（过滤硬件 codec），1=硬解（默认）—— 对齐原版 Setting.getDecode
        if (Settings(this).decodeMode == 0) builder.setRenderersFactory(SoftRenderersFactory(this))
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
        scaleIdx = (scaleIdx + 1) % scaleModes.size
        if (isMpvMode()) mpv?.applyScale(scaleIdx) else playerView?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale).text = scaleNames[scaleIdx]
        // 对齐原版 VideoActivity.onScale：档位写进本片 History，不动全局 Setting
        if (!HistoryStore(this).setScaleOf(vodId, scaleIdx)) Settings(this).playerScale = scaleIdx
    }

    // ===================== 内嵌 mpv 内核（libmpv + SurfaceView） =====================

    private var mpv: MpvPlayer? = null
    private var mpvPos = 0L
    private var mpvDur = 0L
    private var mpvLoaded = false
    /** mpv 在本页起不来时临时退回 EXO，不改用户设置 */
    private var mpvFallbackExo = false

    /** 当前内核是不是 mpv（内嵌，不再依赖外部 app）；mpv 起播失败时本页临时回退 EXO */
    private fun isMpvMode(): Boolean =
        !mpvFallbackExo && PlayerManager.shouldUseMpv(this) &&
            android.os.Build.VERSION.SDK_INT >= 23   // 官方 libmpv 构建要求 API 23+

    /** 起播走 mpv：切显示层 → 建内核 → 下发地址与起始位置 */
    private fun startMpv(url: String, headers: Map<String, String>, startMs: Long) {
        // 关键：EXO 若还在跑，会和 mpv 同时出声。切内嵌 mpv 前必须把它停掉
        runCatching { player?.pause() }
        runCatching { player?.stop() }
        runCatching { player?.clearMediaItems() }
        playerView?.visibility = View.GONE
        val surface = findViewById<android.view.SurfaceView>(R.id.mpvView)
        surface.visibility = View.VISIBLE
        mpvLoaded = false
        val m = mpv ?: MpvPlayer(this, surface).also { p ->
            p.onLoaded = {
                mpvLoaded = true
                Log.i("XingHuiTV", "[mpv] 已加载 $url")
                recordHistory()
            }
            p.onEndFile = { handleEnded() }
            p.onPauseChanged = { paused -> setKeepScreenOn(!paused) }
            p.onFailed = { why ->
                Log.e("XingHuiTV", "[mpv] 播放失败：$why")
                onPlayFail("$why（$flag）")
            }
            p.onTimePos = { mpvPos = it }
            p.onDuration = { mpvDur = it }
            mpv = p
        }
        m.setSpeed(speedSteps[speedIdx])
        m.applyScale(scaleIdx)
        mpvPos = startMs
        mpvDur = 0
        setKeepScreenOn(true)
        m.play(url, headers, startMs, activeSubs.map { it.uri.toString() })
        Log.i("XingHuiTV", "[mpv] 起播（内嵌） headers=${headers.size} subs=${activeSubs.size} start=${startMs}ms")
        // 15 秒没等到文件加载：说明 so 没打进包 / 该片源 mpv 解不了 → 本页临时退回 EXO
        uiHandler.removeCallbacks(mpvWatchdog)
        uiHandler.postDelayed(mpvWatchdog, 15_000)
    }

    private val mpvWatchdog = Runnable {
        if (mpvLoaded || activeUrl.isBlank()) return@Runnable
        Log.e("XingHuiTV", "[mpv] 起播超时，回退 EXO：${activeUrl.take(60)}")
        Toast.makeText(this, "mpv 起播超时，本页改用 EXO 播放", Toast.LENGTH_LONG).show()
        mpvFallbackExo = true
        runCatching { mpv?.release() }
        mpv = null
        findViewById<android.view.SurfaceView>(R.id.mpvView)?.visibility = View.GONE
        playerView?.visibility = View.VISIBLE
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
            switchEpisode(1)
        }
    }

    // ===================== 底部控制条（对齐原版 view_control_vod + CustomKeyDownVod） =====================

    private var controlBar: View? = null
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
        findViewById<View>(R.id.btnEpisodes).setOnClickListener { openEpisodes() }
        findViewById<View>(R.id.btnReset).setOnClickListener { resetCurrentEpisode() }
        findViewById<View>(R.id.btnPlayer).setOnClickListener { openPlayerDialog() }
        findViewById<View>(R.id.btnDecode).setOnClickListener { toggleDecode() }
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
        findViewById<View>(R.id.btnDanmu).setOnClickListener {
            Toast.makeText(this, "本片暂无弹幕源（未接入弹幕库）", Toast.LENGTH_SHORT).show()
        }
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
     * 原版 PlayerDialog 的三种是 系统 / IJK / EXO；本包内置 EXO 与 mpv（mpv 已内嵌，
     * 不再是外部调用），IJK 没有编进来。
     */
    private fun openPlayerDialog() {
        val items = arrayOf("EXO（内置）", "mpv（内置）", "IJK（未编入）")
        val cur = if (isMpvMode()) 1 else 0
        android.app.AlertDialog.Builder(this)
            .setTitle("播放内核")
            .setSingleChoiceItems(items, cur) { d, which ->
                d.dismiss()
                when (which) {
                    2 -> Toast.makeText(this, "IJK 未编译进本包", Toast.LENGTH_SHORT).show()
                    1 -> if (isMpvMode()) return@setSingleChoiceItems else switchKernel(Settings.KERNEL_MPV)
                    0 -> if (!isMpvMode()) return@setSingleChoiceItems else switchKernel(Settings.KERNEL_EXO)
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
            findViewById<android.view.SurfaceView>(R.id.mpvView)?.visibility = View.GONE
            playerView?.visibility = View.VISIBLE
            if (player == null) rebuildPlayer(activeHeaders)
            // mpv 期间 scaleIdx 只作用在 mpv 上，切回 EXO 要把档位同步回 PlayerView
            playerView?.resizeMode = scaleModes[scaleIdx]
            resumeOverride = at
            playUrl(url, activeHeaders)
        }
    }

    /** 解码方式（对齐原版 onDecode → Players.toggleDecode + init + setMediaSource） */
    private fun toggleDecode() {
        if (isMpvMode()) {
            // 与 EXO 的软/硬解对齐：mpv 走 hwdec=mediacodec / no
            val st = Settings(this)
            st.decodeMode = if (st.decodeMode == 1) 0 else 1
            mpv?.setHardwareDecode(st.decodeMode == 1)
            updateDecodeText()
            Toast.makeText(this, if (st.decodeMode == 1) "mpv 已切到硬解" else "mpv 已切到软解",
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
        // 对齐原版 select_decode = [软解, 硬解]
        findViewById<TextView>(R.id.btnDecode)?.text =
            if (Settings(this).decodeMode == 1) "硬解" else "软解"
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
            dur = p.duration
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
            dur = p.duration
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
        HistoryStore(this).setOpeningOf(vodId, openingMs)
        updateOpeningText()
    }

    private fun setEnding(ms: Long) {
        endingMs = ms.coerceAtLeast(0)
        HistoryStore(this).setEndingOf(vodId, endingMs)
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

    /** 呼出控制条（原版 onKeyDown/onKeyUp → showInfo + showControl） */
    private fun showControl() {
        controlBar?.visibility = View.VISIBLE
        findViewById<View>(R.id.playTitle)?.visibility = View.VISIBLE
        findViewById<View>(R.id.btnNext)?.requestFocus()
        uiHandler.removeCallbacks(autoHide)
        uiHandler.postDelayed(autoHide, 5000)
    }

    /** 收起控制条（原版 hideControl(true)：控制条与顶部片名一起收，5 秒 INTERVAL_HIDE 自动触发） */
    private fun hideControl() {
        uiHandler.removeCallbacks(autoHide)
        if (controlBar?.visibility == View.VISIBLE) controlBar?.visibility = View.GONE
        findViewById<View>(R.id.playTitle)?.visibility = View.GONE
    }

    /** 菜单键：原版默认 fullscreen_menu_key=0 → onToggle（控制条开/关），不是直接弹选集 */
    private fun toggleControl() {
        if (controlBar?.visibility == View.VISIBLE) hideControl() else showControl()
    }

    /** OK 键短按：暂停 / 继续（对齐原版 onKeyCenter），EXO / mpv 双内核同语义 */
    private fun togglePlayPause() {
        if (isMpvMode()) {
            val m = mpv ?: return
            if (m.isPlaying()) {
                m.pause()
                setKeepScreenOn(false)
            } else {
                m.resume()
                setKeepScreenOn(true)
            }
            return
        }
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            setKeepScreenOn(false)
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
            setKeepScreenOn(true)
        }
    }

    /** 左右键：快退/快进 10 秒（对齐原版 CustomKeyDownVod 的 INTERVAL_SEEK） */
    private fun seekBy(deltaMs: Long) {
        val cur: Long
        val dur: Long
        if (isMpvMode()) {
            // 用回调缓存值：mpv 的 get_property 是同步的，seek 期间调用会卡住 UI
            cur = mpvPos
            dur = mpvDur
        } else {
            val p = player ?: return
            cur = p.currentPosition
            dur = p.duration
        }
        val target = (cur + deltaMs).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        if (isMpvMode()) mpv?.seekToMs(target) else player?.seekTo(target)
        mpvPos = target
        val secs = Math.abs(deltaMs) / 1000
        Toast.makeText(this, (if (deltaMs > 0) "快进 +" else "快退 -") + secs + " 秒", Toast.LENGTH_SHORT).show()
    }

    /** 时间栏刷新（原版 view_control_seek 的 position / timeBar / duration） */
    private fun startProgressTicker() {
        val tick = object : Runnable {
            override fun run() {
                if (isMpvMode()) {
                    val pos = mpvPos.coerceAtLeast(0L)
                    val dur = mpvDur.coerceAtLeast(0L)
                    findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.timeBar)?.apply {
                        setDuration(dur)
                        setPosition(pos)
                    }
                    findViewById<TextView>(R.id.timePos)?.text = fmtTime(pos)
                    findViewById<TextView>(R.id.timeDur)?.text = fmtTime(dur)
                    if (endingMs > 0 && dur > 0 && !endingDone && pos + endingMs >= dur) {
                        endingDone = true
                        if (directUrl.isBlank()) {
                            Toast.makeText(this@PlayActivity, "已到片尾，播下一集", Toast.LENGTH_SHORT).show()
                            switchEpisode(1)
                        }
                    }
                    uiHandler.postDelayed(this, 500)
                    return
                }
                val p = player
                if (p != null) {
                    val pos = if (p.currentPosition > 0) p.currentPosition else 0L
                    val dur = if (p.duration > 0) p.duration else 0L
                    findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.timeBar)?.apply {
                        setDuration(dur)
                        setPosition(pos)
                    }
                    findViewById<TextView>(R.id.timePos)?.text = fmtTime(pos)
                    findViewById<TextView>(R.id.timeDur)?.text = fmtTime(dur)
                    // 片尾：原版 onTimeChanged 的 ending + position >= duration → checkNext()
                    if (endingMs > 0 && dur > 0 && !endingDone && pos + endingMs >= dur) {
                        endingDone = true
                        if (directUrl.isBlank()) {
                            Toast.makeText(this@PlayActivity, "已到片尾，播下一集", Toast.LENGTH_SHORT).show()
                            switchEpisode(1)
                        }
                    }
                }
                uiHandler.postDelayed(this, 500)
            }
        }
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
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 菜单键（对齐原版 VideoActivity.dispatchKeyEvent:1686 + KeyUtil.isMenuKey:35 的 ACTION_UP 判定）
        //   Setting.getFullscreenMenuKey() == 0 → onToggle()：控制栏显示/隐藏
        //   == 1 → onEpisodes()：弹选集，并收起控制栏
        // 原版放在 dispatchKeyEvent 而不是 onKeyDown，因为控制栏显示时按键会先被 mKeyDown 分支接走
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (Settings(this).fullscreenMenuKey == 1) {
                hideControl()
                openEpisodes()
            } else {
                toggleControl()
            }
            return true
        }
        val shown = controlBar?.visibility == View.VISIBLE
        if (shown) {
            uiHandler.removeCallbacks(autoHide)
            uiHandler.postDelayed(autoHide, 5000)
            // 原版 CustomUpDownView：片头/片尾聚焦时 ↑+1s、↓-1s
            val fid = currentFocus?.id
            if ((fid == R.id.btnOpening || fid == R.id.btnEnding) &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val add = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
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
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // 长按倍速的收尾仍交给 onKeyUp（fastForwarding）
                if (fastForwarding) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_UP) showControl()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            else -> {
                // 遥控器上的菜单键并不都发 KEYCODE_MENU（斐讯 T1 这类盒子上常见别的码），
                // 认不出来时不能把键吞掉：控制条隐藏状态下，任何非方向键都先呼出控制条，
                // 对齐原版 CustomKeyDownVod「非方向键 → showControl」的行为，同时把码打出来便于定位。
                if (event.action == KeyEvent.ACTION_UP) {
                    Log.i("XingHuiTV", "[key] 未识别按键 code=${event.keyCode}，呼出控制条")
                    showControl()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 沉浸式全屏（对齐原版 BaseActivity.onCreate 的 Util.hideSystemUI）。
     * 缺这一步时内容区会被状态栏/导航栏占掉，PlayerView 铺不满窗口，
     * 四周露出的就是 window 背景（壁纸），浅色壁纸下表现为一圈白边。
     */
    private fun applyImmersive() {
        runCatching {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    // LAYOUT_FULLSCREEN 才是「布局真正延伸到系统栏区域」的那一位。
                    // 原版 Util.hideSystemUI 没写它，是因为原版 Activity 主题本身带 windowFullscreen；
                    // 少了它时 FULLSCREEN 只隐藏状态栏、内容区仍在栏下方，等于白做。
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
            Log.i("XingHuiTV", "[play] 沉浸式全屏已应用")
        }.onFailure { Log.e("XingHuiTV", "[play] 沉浸式全屏设置失败", it) }
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
        if (hasFocus) applyImmersive()
    }

    /** 原版 BaseActivity:130-133：HDMI 分辨率/HDR 等配置变化后也要重设一次 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
    }

    /** 切后台/被遮挡时立即落盘，避免只靠 15 秒定时那一次丢掉最后片段 */
    override fun onPause() {
        super.onPause()
        if (isMpvMode()) runCatching { mpv?.pause() }
        savePosition()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(mpvWatchdog)
        saveHandler.removeCallbacks(saveRunnable)
        // 先落盘再释放内核：mpv 一旦 destroy 就读不到 time-pos 了
        runCatching { savePosition() }
        runCatching { mpv?.release() }
        mpv = null
        super.onDestroy()
        sniffer?.cancel()
        sniffer = null
        player?.release()
        player = null
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
