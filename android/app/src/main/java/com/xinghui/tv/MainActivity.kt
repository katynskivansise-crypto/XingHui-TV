package com.xinghui.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        const val DEFAULT_KEY = "demo"
    }

    private lateinit var catList: RecyclerView
    private lateinit var vodList: RecyclerView
    private lateinit var vodAdapter: VideoAdapter

    /** 当前引擎站点 key */
    private var siteKey: String = DEFAULT_KEY
    private var lastSiteKey: String? = null

    private val categories = mutableListOf<Pair<String, String>>() // (type_id, type_name)
    private val vods = mutableListOf<Vod>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 左侧分类
        catList = findViewById<RecyclerView>(R.id.catList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
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

            // 1) 当前站点：注册并作为主界面浏览源
            val registered = mutableListOf<String>()
            val homeKey: String
            if (current != null) {
                if (registerSite(current, current.key, dir, ua, proxy)) {
                    homeKey = current.key
                    registered.add(current.key)
                } else {
                    // 当前站注册失败 → 回退内置演示源
                    homeKey = DEFAULT_KEY
                    registerDemo(dir, ua, proxy)
                    runOnUiThread {
                        Toast.makeText(this, "当前站点注册失败，已回退内置演示", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                homeKey = DEFAULT_KEY
                registerDemo(dir, ua, proxy)
            }
            siteKey = homeKey

            // 2) 额外注册其余站点（聚合搜索用；逐个容错）
            for (site in store.sites()) {
                if (site.key == homeKey || registered.contains(site.key)) continue
                if (registerSite(site, site.key, dir, ua, proxy)) registered.add(site.key)
            }

            // 3) 主界面用当前站点数据
            val home = Engine.execute("home", homeKey, "")
            runOnUiThread { parseHome(home) }
        }.start()
    }

    /** 注册单个站点到引擎；返回是否成功。不改变全局当前站 key。 */
    private fun registerSite(site: Site, key: String, dir: String, ua: String, proxy: String): Boolean {
        val params = JSONObject().put("dir", dir)
        if (ua.isNotBlank()) params.put("ua", ua)
        if (proxy.isNotBlank()) params.put("proxy", proxy)
        return try {
            when {
                site.ext.startsWith("http") -> {
                    val ruleText = JsonFetcher.get(site.ext)
                    if (ruleText.isNullOrBlank()) return false
                    params.put("rule", ruleText)
                    params.put("engine", readAsset("drpy2.min.js"))
                }
                site.siteJson.isNotBlank() -> params.put("site_json", site.siteJson)
                else -> {
                    params.put("rule", readAsset("drpy.js"))
                    params.put("engine", readAsset("drpy2.min.js"))
                }
            }
            Engine.execute("init", key, params.toString()).contains("\"code\":0")
        } catch (_: Exception) {
            false
        }
    }

    /** 注册内置演示源（无订阅时兜底） */
    private fun registerDemo(dir: String, ua: String, proxy: String) {
        val params = JSONObject().put("dir", dir)
        if (ua.isNotBlank()) params.put("ua", ua)
        if (proxy.isNotBlank()) params.put("proxy", proxy)
        params.put("rule", readAsset("drpy.js"))
        params.put("engine", readAsset("drpy2.min.js"))
        Engine.execute("init", DEFAULT_KEY, params.toString())
    }

    private fun onCategory(tid: String) {
        Thread {
            val params = JSONObject().put("tid", tid).put("pg", "1").toString()
            val cat = Engine.execute("category", siteKey, params)
            runOnUiThread { parseCategory(cat) }
        }.start()
    }

    private fun parseHome(json: String) {
        categories.clear()
        vods.clear()
        runCatching {
            val root = JSONObject(json)
            val classes = root.getJSONArray("class")
            for (i in 0 until classes.length()) {
                val c = classes.getJSONObject(i)
                categories.add(c.getString("type_id") to c.getString("type_name"))
            }
        }
        (catList.adapter as ListAdapter).notifyDataSetChanged()
        vodAdapter.notifyDataSetChanged()

        val siteName = SubscriptionStore(this).currentSite()?.name ?: "内置默认"
        findViewById<TextView>(R.id.title).text = getString(R.string.app_name) + " · " + siteName

        // 自动加载第一个分类
        categories.firstOrNull()?.let { onCategory(it.first) }
    }

    private fun parseCategory(json: String) {
        vods.clear()
        vods.addAll(Vod.parseList(json))
        vodAdapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.labelVod).text = "影片 ${vods.size} 条"
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
