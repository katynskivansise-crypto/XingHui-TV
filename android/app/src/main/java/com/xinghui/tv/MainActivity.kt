package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.adapter.LibAdapter
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面（TVBox 风格）：顶部标题栏 + 功能按钮，左侧分类，右侧影片网格。
 * 点影片进详情/播放页。播放内核由设置决定（ExoPlayer / mpv）。
 */
class MainActivity : Activity() {

    companion object {
        /** 兜底订阅地址：旧数据缺 jar/subscribeUrl 时用它自动补齐 */
        const val DEFAULT_SUB_URL = "https://raw.githubusercontent.com/qist/tvbox/master/jsm.json"
    }

    private lateinit var catList: RecyclerView
    private lateinit var vodList: RecyclerView
    private lateinit var vodAdapter: VideoAdapter

    /** 当前引擎站点 key */
    private var siteKey: String = ""

    /** 当前分类翻页状态（对齐原版 CustomScroller：滚到底部自动加载下一页） */
    private var currentTid: String = ""
    private var currentPage: Int = 0
    /** 站点返回的总页数；0 表示站点未告知（视为可一直翻） */
    private var pageCount: Int = 0
    private var loadingMore: Boolean = false
    private var lastSiteKey: String? = null

    private val categories = mutableListOf<Pair<String, String>>() // (type_id, type_name)
    private val vods = mutableListOf<Vod>()
    private val recommends = mutableListOf<Vod>() // 首页更新推荐（引擎 home 的 list）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 本地服务（csp/js 站 /proxy 播放链路 + /file 本地文件 + assets 兜底）；启动失败不影响主流程
        runCatching { ProxyServer.startServer(this) }
            .onFailure { Log.e("XingHuiTV", "[proxy.server] startServer 异常", it) }
        setContentView(R.layout.activity_main)
        // 壁纸（对齐原版 BaseActivity.refreshWall：只设 window 背景，不额外加 View）
        runCatching { WallpaperStore.apply(this) }

        // 分类 tab（横向，对齐原版二级页）
        catList = findViewById<RecyclerView>(R.id.catList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = ListAdapter(categories) { tid -> onCategory(tid) }
        }

        // 右侧影片网格
        vodAdapter = VideoAdapter(vods) { vod ->
            runCatching {
                startActivity(Intent(this, DetailActivity::class.java).putExtra("vod_id", vod.vodId))
            }.onFailure { e ->
                Toast.makeText(this, "无法打开详情：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        vodList = findViewById<RecyclerView>(R.id.vodList).apply {
            layoutManager = GridLayoutManager(this@MainActivity, uiColumns())
            adapter = vodAdapter
            // 对齐原版 CustomScroller：滚到倒数第 3 个就开始预加载下一页
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lm = rv.layoutManager as? GridLayoutManager ?: return
                    val last = lm.findLastVisibleItemPosition()
                    if (last >= vods.size - 3) maybeLoadMore()
                }
            })
        }

        // 首页更新推荐：对齐原版的多行海报网格（原版整个首页是一个竖向滚动的 VerticalGridView，
        // 区块竖向堆叠、可以继续往下浏览）。之前是横向单行 LinearLayout，只显示一排、下方大片空白。
        findViewById<RecyclerView>(R.id.recommendList).apply {
            layoutManager = GridLayoutManager(this@MainActivity, uiColumns())
            adapter = LibAdapter(recommends, { vod ->
                runCatching {
                    startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("vod_id", vod.vodId))
                }.onFailure { e ->
                    Toast.makeText(this@MainActivity, "无法打开详情：${e.message}", Toast.LENGTH_LONG).show()
                }
            }, { _ -> /* 推荐横排无删除模式 */ })
        }

        // 左上角站点名：点击弹出站点列表，选中即切换站源并自动刷新（对齐原版）
        findViewById<TextView>(R.id.title).setOnClickListener {
            runCatching { showSitePicker() }
                .onFailure { e -> Toast.makeText(this, "切换站源失败：${e.message}", Toast.LENGTH_LONG).show() }
        }

        // 点播入口（对齐原版：首页只显示五个入口，点「点播」进二级分类页）
        findViewById<View>(R.id.btnVod).setOnClickListener {
            runCatching { openCategoryPage() }
                .onFailure { e -> Toast.makeText(this, "打开点播失败：${e.message}", Toast.LENGTH_LONG).show() }
        }

        // 顶部功能按钮（全部 try/catch，失败只提示不崩溃）
        findViewById<TextView>(R.id.btnSites).setOnClickListener {
            runCatching { startActivity(Intent(this, SitesActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开站点管理：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            runCatching { startActivity(Intent(this, SearchActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开搜索：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<View>(R.id.btnLive).setOnClickListener {
            runCatching { startActivity(Intent(this, LiveActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开直播：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<View>(R.id.btnHistory).setOnClickListener {
            runCatching { startActivity(Intent(this, HistoryActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开历史：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<View>(R.id.btnCollect).setOnClickListener {
            runCatching { startActivity(Intent(this, CollectActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开收藏：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            runCatching { startActivity(Intent(this, SettingsActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开设置：${e.message}", Toast.LENGTH_LONG).show() }
        }

        loadQuickHistory()
        initEngine()
    }

    /** 首页是否显示历史横排（对齐原项目 SettingCustom 的 homeHistory） */
    private fun loadQuickHistory() {
        val quickRow = findViewById<View>(R.id.quickRow)
        if (!Settings(this).isHomeHistory) {
            quickRow.visibility = View.GONE
            return
        }
        val hist = HistoryStore(this).history().map { Vod.fromJson(it) }
        if (hist.isEmpty()) {
            quickRow.visibility = View.GONE
            return
        }
        quickRow.visibility = View.VISIBLE
        findViewById<RecyclerView>(R.id.quickList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = LibAdapter(hist, { vod ->
                runCatching {
                    if (vod.vodFlag.isNotBlank() && vod.vodEpUrl.isNotBlank()) {
                        startActivity(Intent(this@MainActivity, PlayActivity::class.java).apply {
                            putExtra("vod_id", vod.vodId)
                            putExtra("vod_name", vod.vodName)
                            putExtra("vod_pic", vod.vodPic)
                            putExtra("flag", vod.vodFlag)
                            putExtra("ep_url", vod.vodEpUrl)
                        })
                    } else {
                        startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("vod_id", vod.vodId))
                    }
                }.onFailure { e ->
                    Toast.makeText(this@MainActivity, "打开失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }, { vod ->
                HistoryStore(this@MainActivity).removeHistory(vod.vodId)
                loadQuickHistory()
            })
        }
    }

    override fun onResume() {
        super.onResume()
        // 顶部时钟
        clockTick.run()
        // 从站点管理返回后，若当前站点变化则重新加载
        val cur = SubscriptionStore(this).currentKey()
        if (lastSiteKey != null && cur != lastSiteKey) {
            initEngine()
        }
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.clock).text =
                SimpleDateFormat("HH:mm:ss MM/dd EEE", Locale.SIMPLIFIED_CHINESE).format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    /**
     * 全链路初始化：
     * 1) 注册当前站点（主界面浏览/播放用）；
     * 2) 额外注册全部订阅站点（供聚合搜索 searchAll，失败跳过不影响主界面）；
     * 3) 用当前站点拉取 home 并展示。
     */
    private fun initEngine() {
        Thread {
            val dir = filesDir.absolutePath
            copyAssetToFiles("drpy-core-lite.min.js", "$dir/drpy-core-lite.min.js")

            val store = SubscriptionStore(this)
            val current = store.currentSite()
            lastSiteKey = store.currentKey()

            // 全局 UA / 代理（对齐原项目设置，注入所有 drpy 源）
            val ua = Settings(this).ua
            val proxy = Settings(this).proxy

            // 兜底：旧版界面导入的订阅缺 jar/subscribeUrl（表现为 csp 站空白、drpy 站注册失败），
            // 启动时自动用默认订阅补齐，无需用户重导
            // 兜底：jar 缺失 / 非 http 地址 / csp 站点未 attach jar → 强制从订阅重解析并写回。
            // 旧版界面导入的订阅可能存了相对路径 "./jar/spider.jar"，loadJar 会下载失败 → 所有 csp 站空白，
            // 只判 isBlank() 会漏掉这种"非空但无效"的脏值，所以这里连同"站点未 attach"一起判。
            val cspSites = store.sites().filter { it.isCsp() }
            val jarBad = store.jar().let { it.isBlank() || !it.startsWith("http") }
            val siteJarBad = cspSites.any { it.jar.isBlank() || !it.jar.startsWith("http") }
            if (cspSites.isNotEmpty() && (jarBad || siteJarBad)) {
                Log.e("XingHuiTV", "[兜底] 触发 jarBad=$jarBad siteJarBad=$siteJarBad jar=${store.jar().take(70)}")
                try {
                    val baseUrl = store.subscribeUrl().ifBlank { DEFAULT_SUB_URL }
                    val text = JsonFetcher.get(baseUrl)
                    if (!text.isNullOrBlank()) {
                        val jarRaw = SubscriptionParser.parseJar(text)
                        val jarFull = SubscriptionParser.jarUrl(jarRaw, baseUrl)
                        if (jarFull.isNotBlank() && jarFull.startsWith("http")) {
                            store.setJar(jarFull)
                            if (store.subscribeUrl().isBlank()) store.setSubscribeUrl(baseUrl)
                            store.attachJar(jarFull) // 对齐原项目：jar 写进每个 csp 站点
                            Log.e("XingHuiTV", "[兜底] 已自动补齐 jar=$jarFull subUrl=${store.subscribeUrl()}")
                        } else {
                            Log.e("XingHuiTV", "[兜底] spider 无法解析为 URL：raw=$jarRaw base=$baseUrl")
                        }
                    } else {
                        Log.e("XingHuiTV", "[兜底] 订阅下载失败 baseUrl=$baseUrl")
                    }
                } catch (e: Exception) {
                    Log.e("XingHuiTV", "[兜底] 自动补齐异常", e)
                }
            }

            // csp_ 源准备：订阅里只要有 csp_ 站点，就先下载并加载 spider.jar（站点自带 jar 优先）
            val cspJar = store.jar().ifBlank { store.sites().firstOrNull { it.isCsp() }?.jar ?: "" }
            if (store.sites().any { it.isCsp() } && cspJar.isNotBlank()) {
                CspLoader.prepare(this, cspJar)
            } else {
                Log.e("XingHuiTV", "[csp] 跳过 prepare：有csp站=${store.sites().any { it.isCsp() }} jar=$cspJar")
            }

            // 1) 当前站点：注册并作为主界面浏览源（不做任何自建内容回退）
            val registered = mutableListOf<String>()
            if (current == null) {
                Log.e("XingHuiTV", "[home] 无可用站点，停止拉取（不回退内置内容）")
                runOnUiThread {
                    showEmpty("还没有可用站点，请导入订阅源")
                    Toast.makeText(this, "请先在「站点」中导入订阅源", Toast.LENGTH_LONG).show()
                    findViewById<TextView>(R.id.title).text =
                        getString(R.string.app_name) + " · 未配置站点"
                }
                return@Thread
            }
            val homeKey = current.key
            if (registerSite(current, current.key, dir, ua, proxy)) {
                registered.add(homeKey)
            } else {
                Log.e("XingHuiTV", "[init] 站点注册失败 key=$homeKey，继续直连尝试（csp 站无需注册）")
            }
            siteKey = homeKey

            // 2) 立即拉当前站点首页并上屏：先让用户看到内容，不被后面的批量注册阻塞
            val home = if (current?.isCsp() == true) {
                CspLoader.home(this, current)
            } else {
                Engine.execute("home", homeKey, "")
            }
            Log.e("XingHuiTV", "[home] homeKey=$homeKey 返回前800: ${home.take(800)}")
            runOnUiThread { parseHome(home) }

            // 3) 首屏已经上屏后，再补注册其余站点（聚合搜索用；逐个容错）
            // drpy 源每站都要下载一次规则脚本，并把整份 drpy2.min.js（109KB）作为 JNI 参数传进引擎；
            // 157 站全量预注册会让电视耗时与内存尖峰过大（实测跑到约 30 站进程即被杀，或首屏等待 30 秒以上）。
            // 这里只补注册轻量 json/api 源，drpy 源留到搜索页真正需要时再懒注册。
            for (site in store.sites()) {
                if (site.key == homeKey || registered.contains(site.key)) continue
                if (site.isDrpy() || site.ext.startsWith("http")) continue
                if (registerSite(site, site.key, dir, ua, proxy)) registered.add(site.key)
            }
        }.start()
    }

    /** drpy2.min.js 全文缓存：同一订阅下所有 drpy 源共用一份，只下载一次 */
    private var drpyEngineCache: String? = null

    /** 取 drpy 引擎脚本：首次下载，之后复用；下载失败回退内置 assets */
    private fun engineScript(engineUrl: String): String {
        drpyEngineCache?.let { return it }
        val downloaded = JsonFetcher.get(engineUrl)
        val text = if (downloaded.isNullOrBlank()) readAsset("drpy2.min.js") else downloaded
        drpyEngineCache = text
        return text
    }

    /** 注册单个站点到引擎；返回是否成功。不改变全局当前站 key。 */
    private fun registerSite(site: Site, key: String, dir: String, ua: String, proxy: String): Boolean {
        // csp_ 源是 spider.jar 内 Java 类，由壳侧 CspLoader 执行，不注册引擎
        if (site.isCsp()) return true
        val params = JSONObject().put("dir", dir)
        if (ua.isNotBlank()) params.put("ua", ua)
        if (proxy.isNotBlank()) params.put("proxy", proxy)
        return try {
            when {
                // JS 规则源（对齐原项目 BaseLoader：api 含 .js 即 js 源）。
                // api 一般是 drpy2.min.js 引擎，ext 是规则脚本；相对路径基于订阅 URL 解析，引擎下载失败回退内置
                site.isDrpy() -> {
                    val base = SubscriptionStore(this).subscribeUrl()
                    val engineUrl = SubscriptionParser.jarUrl(site.api, base)
                    val ruleUrl = SubscriptionParser.jarUrl(site.ext, base)
                    val ruleText = JsonFetcher.get(ruleUrl)
                    if (ruleText.isNullOrBlank()) return false
                    params.put("rule", ruleText)
                    params.put("engine", engineScript(engineUrl))
                    // 站点级 header（如"新闪雷/茶杯狐"配置了 UA，对齐原项目 getHeaders）
                    try {
                        val h = JSONObject(site.siteJson).optJSONObject("header")
                        if (h != null && h.length() > 0) params.put("header", h.toString())
                    } catch (_: Exception) {}
                }
                site.ext.startsWith("http") -> {
                    val ruleText = JsonFetcher.get(site.ext)
                    if (ruleText.isNullOrBlank()) return false
                    params.put("rule", ruleText)
                    params.put("engine", readAsset("drpy2.min.js"))
                }
                site.siteJson.isNotBlank() -> params.put("site_json", site.siteJson)
                else -> {
                    Log.e("XingHuiTV", "[init] key=$key 类型无法识别（api=${site.api}），跳过（不回退内置规则）")
                    return false
                }
            }
            val initRes = Engine.execute("init", key, params.toString())
            Log.e("XingHuiTV", "[init] key=$key 返回前500: ${initRes.take(500)}")
            initRes.contains("\"code\":0")
        } catch (e: Exception) {
            Log.e("XingHuiTV", "[init] key=$key 异常", e)
            false
        }
    }

    /** 界面大小 → 网格列数（对齐原版 Product：size 越大卡片越大、每行列数越少） */
    private fun uiColumns(): Int = when (Settings(this).uiSize) {
        0 -> 6
        1 -> 5
        2 -> 4
        else -> 3
    }

    /**
     * 按站点 Style 应用卡片形态（对齐原版 bean/Style + Product.getSpec）。
     * - list：整行一条横卡（体育/网盘/听书站），spanCount 强制 1
     * - rect/oval：网格，海报高 = 格宽 / ratio（默认 rect 0.75 竖版、oval 1.0 正方）
     */
    private fun applyCardStyle(site: Site) {
        val isList = site.isListStyle()
        val cols = if (isList) 1 else uiColumns()
        runCatching {
            vodAdapter.setStyle(site.styleType, site.styleRatio, cols)
            (findViewById<RecyclerView>(R.id.vodList)?.layoutManager as? GridLayoutManager)?.spanCount = cols
        }.onFailure { Log.e("XingHuiTV", "[style] 套用形态失败", it) }
        Log.e("XingHuiTV", "[style] ${site.name} type=${site.styleType.ifBlank { "rect" }} " +
            "ratio=${site.styleRatio} cols=$cols")
    }

    /** 左上角站点名点击：弹出站点列表，选中即切换站源并自动刷新（对齐原版） */
    private fun showSitePicker() {
        val store = SubscriptionStore(this)
        val sites = store.sites()
        if (sites.isEmpty()) {
            Toast.makeText(this, "没有可用站点，请先到「站点」导入订阅", Toast.LENGTH_LONG).show()
            return
        }
        val cur = store.currentKey()
        val labels = sites.map { s ->
            val tag = when {
                s.isCsp() -> "csp"
                s.isDrpy() -> "js"
                else -> "采集"
            }
            (if (s.key == cur) "● " else "   ") + s.name + "  [" + tag + "]"
        }
        AlertDialog.Builder(this)
            .setTitle("切换站源")
            .setItems(labels.toTypedArray()) { _, which ->
                val s = sites[which]
                store.setCurrent(s.key)
                switchToSite(s)
            }
            .setNegativeButton("管理站点") { _, _ ->
                runCatching { startActivity(Intent(this, SitesActivity::class.java)) }
            }
            .show()
    }

    /**
     * 切换站点（对齐原版 HomeActivity.setSite）：
     * 原版仅 VodConfig.setHome(item) 持久化当前站 + homeContent() 重新拉首页，
     * 不重新 init 全部站点。此处同样只注册该站（如未注册）并拉取它的 home。
     */
    private fun switchToSite(site: Site) {
        categories.clear()
        vods.clear()
        recommends.clear()
        (catList.adapter as ListAdapter).notifyDataSetChanged()
        vodAdapter.notifyDataSetChanged()
        (findViewById<RecyclerView>(R.id.recommendList).adapter as LibAdapter).notifyDataSetChanged()
        findViewById<View>(R.id.mainBody)?.visibility = View.GONE
        findViewById<View>(R.id.entryRow)?.visibility = View.VISIBLE
        findViewById<View>(R.id.recommendRow)?.visibility = View.GONE
        findViewById<TextView>(R.id.title).text = getString(R.string.app_name) + " · " + site.name
        showEmpty("")
        siteKey = site.key
        Toast.makeText(this, "已切换到「${site.name}」，正在刷新…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val dir = filesDir.absolutePath
                // csp_ 源由 CspLoader 执行（实例已缓存），其余站重新注册该站即可
                if (!site.isCsp()) {
                    registerSite(site, site.key, dir, Settings(this).ua, Settings(this).proxy)
                }
                val home = if (site.isCsp()) {
                    CspLoader.home(this, site)
                } else {
                    Engine.execute("home", site.key, "{}")
                }
                Log.e("XingHuiTV", "[switch] homeKey=${site.key} 返回前800: ${home.take(800)}")
                runOnUiThread { parseHome(home) }
            } catch (e: Exception) {
                Log.e("XingHuiTV", "[switch] 切换站点失败 key=${site.key}", e)
                runOnUiThread { showEmpty("切换失败：${e.message}") }
            }
        }.start()
    }

    /** 进入点播二级页：横向分类 tab + 影片网格（首页入口隐藏） */
    private fun openCategoryPage() {
        findViewById<View>(R.id.entryRow)?.visibility = View.GONE
        findViewById<View>(R.id.recommendRow)?.visibility = View.GONE
        findViewById<View>(R.id.quickRow)?.visibility = View.GONE
        findViewById<View>(R.id.mainBody)?.visibility = View.VISIBLE
        if (categories.isEmpty()) {
            showEmpty("未获取到分类列表（首页接口未返回 class 字段）\n按返回键回首页，看首页显示的失败原因")
            return
        }
        if (vods.isEmpty()) categories.firstOrNull()?.let { onCategory(it.first) }
        catList.requestFocus()
    }

    /** 返回一级首页：五个入口 + 更新推荐 */
    private fun backToHome() {
        findViewById<View>(R.id.mainBody)?.visibility = View.GONE
        findViewById<View>(R.id.entryRow)?.visibility = View.VISIBLE
        findViewById<View>(R.id.recommendRow)?.visibility =
            if (recommends.isNotEmpty()) View.VISIBLE else View.GONE
        loadQuickHistory() // 恢复首页历史行（历史/设置可能已变化）
        findViewById<View>(R.id.btnVod)?.requestFocus()
    }

    /** 空状态提示（空串则隐藏） */
    private fun showEmpty(msg: String) {
        findViewById<TextView>(R.id.tvEmpty)?.apply {
            text = msg
            visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (findViewById<View>(R.id.mainBody)?.visibility == View.VISIBLE) {
            backToHome()
            return
        }
        super.onBackPressed()
    }

    private fun onCategory(tid: String) {
        // 切分类先刷新该分类的筛选栏，再把用户已选条件带进请求（原版 FilterDialog → extend）
        renderFilters(tid)
        // 切分类 = 回到第 1 页并清空旧列表（原版 VodFragment.mPages.reset）
        currentTid = tid
        currentPage = 0
        pageCount = 0
        loadingMore = false
        vods.clear()
        vodAdapter.notifyDataSetChanged()
        loadCategoryPage(tid, 1, append = false)
    }

    /**
     * 拉取分类页第 page 页。
     * append=false 覆盖列表（切分类/切筛选），append=true 追加（滚到底翻页）。
     */
    private fun loadCategoryPage(tid: String, page: Int, append: Boolean) {
        if (loadingMore) return
        loadingMore = true
        val extend = HashMap(selectedFilters[tid].orEmpty())
        Log.e("XingHuiTV", "[category] tid=$tid pg=$page append=$append extend=$extend")
        Thread {
            var json = ""
            var err: String? = null
            try {
                val site = SubscriptionStore(this).sites().firstOrNull { it.key == siteKey }
                json = if (site?.isCsp() == true) {
                    CspLoader.category(this, site, tid, page.toString(), extend)
                } else {
                    val params = JSONObject().put("tid", tid).put("pg", page.toString()).toString()
                    Engine.execute("category", siteKey, params)
                }
            } catch (e: Exception) {
                err = e.message
                Log.e("XingHuiTV", "[category] 请求失败 tid=$tid pg=$page", e)
            }
            runOnUiThread {
                loadingMore = false
                if (err != null) {
                    if (!append) showEmpty("加载失败：$err")
                    return@runOnUiThread
                }
                currentPage = page
                parseCategory(json, append)
            }
        }.start()
    }

    /** 滚到接近底部时加载下一页（对齐原版 CustomScroller.onLoadMore） */
    private fun maybeLoadMore() {
        if (loadingMore) return
        if (currentTid.isBlank() || vods.isEmpty()) return
        // 原版 setEnable：page < pageCount || pageCount == 0 时才允许继续翻
        if (pageCount > 0 && currentPage >= pageCount) return
        loadCategoryPage(currentTid, currentPage + 1, append = true)
    }

    // ---------- 分类筛选（对齐原版 Result.filters / FilterDialog） ----------

    private data class FilterGroup(
        val key: String,
        val name: String,
        val values: List<Pair<String, String>>,  // (显示名, 传给站点的值)
        val init: String = ""                    // 原版 Filter.init：该组的默认选中值
    )

    private val filtersByType = mutableMapOf<String, List<FilterGroup>>()
    private val selectedFilters = mutableMapOf<String, MutableMap<String, String>>()

    private fun px(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 解析 homeContent 的 filters 字段；没有就清空（不同站点结构差异很大，全部 runCatching 兜住） */
    private fun parseFilters(root: JSONObject) {
        filtersByType.clear()
        selectedFilters.clear()
        val fo = runCatching { root.optJSONObject("filters") }.getOrNull() ?: return
        for (tid in fo.keys()) {
            val arr = fo.optJSONArray(tid) ?: continue
            val groups = (0 until arr.length()).mapNotNull { i ->
                val f = arr.optJSONObject(i) ?: return@mapNotNull null
                val key = f.optString("key")
                if (key.isBlank()) return@mapNotNull null
                val vals = f.optJSONArray("value")?.let { va ->
                    (0 until va.length()).mapNotNull { j ->
                        va.optJSONObject(j)?.let { v -> v.optString("n") to v.optString("v") }
                    }
                } ?: emptyList()
                FilterGroup(key, f.optString("name").ifBlank { key }, vals, f.optString("init"))
            }
            if (groups.isNotEmpty()) {
                filtersByType[tid] = groups
                // 对齐原版 Class.getExtend：带 init 的筛选组默认就计入请求参数。
                // 不带的话首次进分类页的请求比原版少几个参数，部分站点（如 AppDrama）
                // 会因此返回字段残缺的数据 —— 表现为标题/角标正常但海报整片空白。
                val sel = selectedFilters.getOrPut(tid) { mutableMapOf() }
                groups.forEach { g -> if (g.init.isNotBlank()) sel.putIfAbsent(g.key, g.init) }
            }
        }
        Log.e("XingHuiTV", "[filters] 解析到 ${filtersByType.size} 个分类的筛选项，" +
            "预置默认值 ${selectedFilters.values.sumOf { it.size }} 个")
    }

    /** 二级页顶部筛选栏：每个筛选组一个按钮，显示当前选中值 */
    private fun renderFilters(tid: String) {
        val row = findViewById<View>(R.id.filterRow) ?: return
        val list = findViewById<LinearLayout>(R.id.filterList) ?: return
        val groups = filtersByType[tid]
        if (groups.isNullOrEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        list.removeAllViews()
        val sel = selectedFilters.getOrPut(tid) { mutableMapOf() }
        groups.forEach { g ->
            val cur = sel[g.key].orEmpty()
            val label = g.values.firstOrNull { it.second == cur }?.first ?: g.name
            val btn = TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setPadding(px(14), px(6), px(14), px(6))
                background = getDrawable(R.drawable.selector_tv_btn)
                isFocusable = true
                setOnClickListener { showFilterDialog(tid, g) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(10) }
            }
            list.addView(btn)
        }
    }

    /** 点筛选组 → 弹出取值列表（TV 遥控器可直接上下选）；选中后重新请求该分类 */
    private fun showFilterDialog(tid: String, g: FilterGroup) {
        if (g.values.isEmpty()) return
        val labels = g.values.map { it.first.ifBlank { it.second }.ifBlank { "默认" } }
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(labels.toTypedArray()) { _, which ->
                selectedFilters.getOrPut(tid) { mutableMapOf() }[g.key] = g.values[which].second
                renderFilters(tid)
                onCategory(tid)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun parseHome(json: String) {
        categories.clear()
        vods.clear()
        recommends.clear()
        // 切站/刷新首页 = 翻页状态归零（原版换源后 VodFragment.mPages 清空）
        currentTid = ""
        currentPage = 0
        pageCount = 0
        loadingMore = false

        // 卡片形态跟随站点 Style（订阅里 51/157 站带 style）：
        // 体育/网盘/听书站 type=list 要整行横条，影视站 ratio=1.33、教育站 1.618 要压扁海报。
        // 放在 parseHome 入口统一执行，首屏与切站都会走到这里。
        runCatching {
            SubscriptionStore(this@MainActivity).currentSite()?.let { applyCardStyle(it) }
        }.onFailure { Log.e("XingHuiTV", "[style] 应用卡片形态失败", it) }

        // 首页 JSON 为空/不可解析时，不再留黑屏：直接显示原因（用户截图即可定位）
        val root = runCatching { JSONObject(json) }.getOrNull()
        if (root == null) {
            val reason = if (json.isBlank()) "站点未返回首页数据（返回为空）"
            else "站点返回无法解析：${json.take(100)}"
            Log.e("XingHuiTV", "[parseHome] $reason")
            refreshLists()
            updateTitle()
            showHomeHint("$reason\n请点「点播」浏览分类，或点右上角「站点」切换站源")
            return
        }

        // class / list 分开解析：任一字段缺失都不再拖垮另一个（原实现同块解析，class 抛异常会连带推荐行全丢）
        runCatching {
            root.optJSONArray("class")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    // 对齐原版 Class.java 的 @SerializedName(value="type_id", alternate="id")
                    // —— 兼容的是 id / name，不是驼峰 typeId/typeName
                    val id = c.optString("type_id").ifBlank { c.optString("id") }
                    val name = c.optString("type_name").ifBlank { c.optString("name") }
                    if (id.isNotBlank()) categories.add(id to name)
                }
            }
        }.onFailure { Log.e("XingHuiTV", "[parseHome] class 字段解析失败 json前200: ${json.take(200)}", it) }

        // 分类筛选（对齐原版 Result.filters：{type_id: [{key,name,value:[{n,v}]}]}）
        parseFilters(root)

        runCatching { recommends.addAll(Vod.parseList(json)) }
            .onFailure { Log.e("XingHuiTV", "[parseHome] list 字段解析失败 json前200: ${json.take(200)}", it) }

        refreshLists()
        updateTitle()

        if (recommends.isNotEmpty()) {
            findViewById<View>(R.id.recommendRow)?.visibility = View.VISIBLE
            findViewById<TextView>(R.id.recommendLabel).text = "更新推荐 · 共 ${recommends.size} 部"
        } else {
            // 首页无推荐时的可读提示（区分「站点没给推荐」与「接口没通」）
            val msg = root.optString("msg", "")
            val jar = SubscriptionStore(this).jar()
            val siteJar = SubscriptionStore(this).currentSite()?.jar ?: ""
            val jarState = when {
                jar.isBlank() -> "jar=空"
                !jar.startsWith("http") -> "jar=非URL(${jar.take(50)})"
                else -> "jar=${jar.substringAfterLast('/').take(40)}"
            }
            showHomeHint(
                (if (msg.isNotBlank()) "站点提示：$msg" else "本站未提供首页推荐（分类 ${categories.size} 个）")
                    + "\n$jarState / site.jar=" + (if (siteJar.isBlank()) "空" else siteJar.take(40))
                    + "\n请点「点播」浏览分类" +
                    (if (categories.isEmpty()) "；若点播也空，点右上角「站点」换源" else "")
            )
        }

        // 仅当已在点播二级页时才预载首个分类（首页不预载，避免无谓请求）
        if (findViewById<View>(R.id.mainBody)?.visibility == View.VISIBLE) {
            categories.firstOrNull()?.let { onCategory(it.first) }
        }
    }

    /** 首页提示行：复用 recommendRow/Label 位置，避免内容区纯黑无任何可读信息 */
    private fun showHomeHint(text: String) {
        findViewById<View>(R.id.recommendRow)?.visibility = View.VISIBLE
        findViewById<TextView>(R.id.recommendLabel)?.text = text
    }

    private fun refreshLists() {
        (catList.adapter as ListAdapter?)?.notifyDataSetChanged()
        vodAdapter.notifyDataSetChanged()
        (findViewById<RecyclerView>(R.id.recommendList)?.adapter as LibAdapter?)?.notifyDataSetChanged()
    }

    private fun updateTitle() {
        val siteName = SubscriptionStore(this).currentSite()?.name ?: "未配置站点"
        // 对齐原版 activity_home.xml：顶栏标题就是当前站点名（原版显示「瓜子·影视」）。
        // 之前把 app 名拼在前面（「星汇 TV · 苹果·APP」），长度翻倍把顶栏撑得很宽，
        // 与红圈标出的原版比例对不上；品牌由左侧 logo 承担。
        findViewById<TextView>(R.id.title).text = siteName
    }

    private fun parseCategory(json: String, append: Boolean = false) {
        if (!append) vods.clear()
        val parsed = runCatching { Vod.parseList(json) }
            .onFailure { e -> Log.e("XingHuiTV", "[parseCategory] 解析失败 json前300: ${json.take(300)}", e) }
            .getOrElse { emptyList() }
        vods.addAll(parsed)
        // 总页数（原版 Result.pagecount → CustomScroller.setEnable：page < pageCount || pageCount == 0）
        runCatching { pageCount = JSONObject(json).optInt("pagecount", 0) }.getOrNull()
        // 海报诊断：把首条原始 key 与所有图像类字段的值打出来。
        // 分类页海报空白而标题/角标正常时，看这两行即可判断是"字段名没兜住"还是"站点确实没给图"。
        runCatching {
            val first = org.json.JSONObject(json).optJSONArray("list")?.optJSONObject(0)
            if (first != null) {
                val keys = first.keys().asSequence().toList()
                Log.e("XingHuiTV", "[parseCategory] 首条 keys=$keys")
                val imgKeys = keys.filter { k ->
                    k.contains("pic", true) || k.contains("img", true) ||
                        k.contains("cover", true) || k.contains("image", true) || k.contains("poster", true)
                }
                Log.e("XingHuiTV", "[parseCategory] 图像类字段 => " +
                    imgKeys.joinToString(", ") { k -> "$k='${first.optString(k).take(80)}'" })
            }
            Log.e("XingHuiTV", "[parseCategory] 本页 ${parsed.size} 条，累计 ${vods.size} 条，" +
                "有封面 ${vods.count { it.vodPic.isNotBlank() }} 条，pg=$currentPage pagecount=$pageCount")
        }
        if (append && parsed.isNotEmpty()) {
            // 追加时只刷新新增区间，避免整表重绘导致焦点跳动
            vodAdapter.notifyItemRangeInserted(vods.size - parsed.size, parsed.size)
        } else {
            vodAdapter.notifyDataSetChanged()
        }
        findViewById<TextView>(R.id.labelVod).text = "影片 ${vods.size} 条"
        showEmpty(if (vods.isEmpty()) "该分类暂无内容（接口返回空）" else "")
    }

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    private fun copyAssetToFiles(name: String, dest: String) {
        val out = File(dest)
        if (out.exists()) return
        assets.open(name).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
