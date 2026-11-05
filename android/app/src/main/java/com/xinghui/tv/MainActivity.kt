package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
    private var lastSiteKey: String? = null

    private val categories = mutableListOf<Pair<String, String>>() // (type_id, type_name)
    private val vods = mutableListOf<Vod>()
    private val recommends = mutableListOf<Vod>() // 首页更新推荐（引擎 home 的 list）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 本地播放代理（csp/js 站 /proxy 播放链路）；启动失败不影响主流程
        runCatching { ProxyServer.startServer() }
            .onFailure { Log.e("XingHuiTV", "[proxy.server] startServer 异常", it) }
        setContentView(R.layout.activity_main)

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
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = vodAdapter
        }

        // 首页更新推荐横排（home 返回的推荐影片，点击进详情）
        findViewById<RecyclerView>(R.id.recommendList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
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
        findViewById<TextView>(R.id.btnVod).setOnClickListener {
            runCatching { openCategoryPage() }
                .onFailure { e -> Toast.makeText(this, "打开点播失败：${e.message}", Toast.LENGTH_LONG).show() }
        }

        // 顶部功能按钮（全部 try/catch，失败只提示不崩溃）
        findViewById<TextView>(R.id.btnSites).setOnClickListener {
            runCatching { startActivity(Intent(this, SitesActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开站点管理：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<TextView>(R.id.btnSearch).setOnClickListener {
            runCatching { startActivity(Intent(this, SearchActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开搜索：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<TextView>(R.id.btnLive).setOnClickListener {
            runCatching { startActivity(Intent(this, LiveActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开直播：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<TextView>(R.id.btnHistory).setOnClickListener {
            runCatching { startActivity(Intent(this, HistoryActivity::class.java)) }
                .onFailure { e -> Toast.makeText(this, "无法打开历史：${e.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<TextView>(R.id.btnCollect).setOnClickListener {
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
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
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
            if (store.jar().isBlank() && store.sites().any { it.isCsp() }) {
                try {
                    val baseUrl = store.subscribeUrl().ifBlank { DEFAULT_SUB_URL }
                    val text = JsonFetcher.get(baseUrl)
                    if (!text.isNullOrBlank()) {
                        val jarRaw = SubscriptionParser.parseJar(text)
                        if (jarRaw.isNotBlank()) {
                            val jarFull = SubscriptionParser.jarUrl(jarRaw, baseUrl)
                            store.setJar(jarFull)
                            if (store.subscribeUrl().isBlank()) store.setSubscribeUrl(baseUrl)
                            store.attachJar(jarFull) // 对齐原项目：jar 写进每个 csp 站点
                            Log.e("XingHuiTV", "[兜底] 已自动补齐 jar=$jarFull subUrl=${store.subscribeUrl()}")
                        } else {
                            Log.e("XingHuiTV", "[兜底] 订阅无 spider 字段，baseUrl=$baseUrl")
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

            // 2) 额外注册其余站点（聚合搜索用；逐个容错）
            for (site in store.sites()) {
                if (site.key == homeKey || registered.contains(site.key)) continue
                if (registerSite(site, site.key, dir, ua, proxy)) registered.add(site.key)
            }

            // 3) 主界面用当前站点数据
            val home = if (current?.isCsp() == true) {
                CspLoader.home(this, current)
            } else {
                Engine.execute("home", homeKey, "")
            }
            Log.e("XingHuiTV", "[home] homeKey=$homeKey 返回前800: ${home.take(800)}")
            runOnUiThread { parseHome(home) }
        }.start()
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
                    val engineText = JsonFetcher.get(engineUrl)
                    params.put("rule", ruleText)
                    params.put("engine", if (engineText.isNullOrBlank()) readAsset("drpy2.min.js") else engineText)
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
            showEmpty("站点内容加载中或为空…\n若长时间无内容，请返回首页检查订阅与站点")
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
        findViewById<TextView>(R.id.btnVod)?.requestFocus()
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
        Thread {
            val site = SubscriptionStore(this).sites().firstOrNull { it.key == siteKey }
            val cat = if (site?.isCsp() == true) {
                CspLoader.category(this, site, tid, "1")
            } else {
                val params = JSONObject().put("tid", tid).put("pg", "1").toString()
                Engine.execute("category", siteKey, params)
            }
            runOnUiThread { parseCategory(cat) }
        }.start()
    }

    private fun parseHome(json: String) {
        categories.clear()
        vods.clear()
        recommends.clear()
        runCatching {
            val root = JSONObject(json)
            val classes = root.getJSONArray("class")
            for (i in 0 until classes.length()) {
                val c = classes.getJSONObject(i)
                categories.add(c.getString("type_id") to c.getString("type_name"))
            }
            // 更新推荐（原项目首页推荐行）：home 的 list
            recommends.addAll(Vod.parseList(json))
        }.onFailure { e ->
            Log.e("XingHuiTV", "[parseHome] 解析失败 json前300: ${json.take(300)}", e)
        }
        (catList.adapter as ListAdapter).notifyDataSetChanged()
        vodAdapter.notifyDataSetChanged()
        (findViewById<RecyclerView>(R.id.recommendList).adapter as LibAdapter).notifyDataSetChanged()

        val siteName = SubscriptionStore(this).currentSite()?.name ?: "未配置站点"
        findViewById<TextView>(R.id.title).text = getString(R.string.app_name) + " · " + siteName

        // 推荐横排：有数据才显示
        findViewById<View>(R.id.recommendRow).visibility =
            if (recommends.isNotEmpty()) View.VISIBLE else View.GONE
        if (recommends.isNotEmpty()) {
            findViewById<TextView>(R.id.recommendLabel).text = "更新推荐 · 共 ${recommends.size} 部"
        }

        // 仅当已在点播二级页时才预载首个分类（首页不预载，避免无谓请求）
        if (findViewById<View>(R.id.mainBody)?.visibility == View.VISIBLE) {
            categories.firstOrNull()?.let { onCategory(it.first) }
        }
    }

    private fun parseCategory(json: String) {
        vods.clear()
        runCatching { vods.addAll(Vod.parseList(json)) }
            .onFailure { e -> Log.e("XingHuiTV", "[parseCategory] 解析失败 json前300: ${json.take(300)}", e) }
        vodAdapter.notifyDataSetChanged()
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
