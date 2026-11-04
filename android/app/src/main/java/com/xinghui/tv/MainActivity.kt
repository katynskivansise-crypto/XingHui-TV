package com.xinghui.tv

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File

/**
 * 主界面：加载 Rust 引擎 → 读取当前订阅站点 → 下载 drpy 规则/JSON → init 注册
 * → home/category → 展示列表 → 点击播放。播放内核由设置决定（ExoPlayer / mpv）。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val DEFAULT_KEY = "demo"
    }

    private lateinit var catList: RecyclerView
    private lateinit var vodList: RecyclerView
    private var player: ExoPlayer? = null

    /** 当前引擎站点 key：无订阅时用内置默认 demo，有订阅时用选中站点的 key */
    private var siteKey: String = DEFAULT_KEY

    private val categories = mutableListOf<Pair<String, String>>() // (type_id, type_name)
    private val vods = mutableListOf<Pair<String, String>>() // (vod_id, vod_name)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val playerView = findViewById<PlayerView>(R.id.player)
        player = ExoPlayer.Builder(this).build().also { playerView.player = it }

        // 设置入口：包 try/catch，跳转失败只提示不崩溃
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            runCatching {
                startActivity(Intent(this, SettingsActivity::class.java))
            }.onFailure { e ->
                Toast.makeText(this, "无法打开设置：${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // 站点管理入口
        findViewById<TextView>(R.id.btnSites).setOnClickListener {
            runCatching {
                startActivity(Intent(this, SitesActivity::class.java))
            }.onFailure { e ->
                Toast.makeText(this, "无法打开站点管理：${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        catList = findViewById<RecyclerView>(R.id.catList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = ListAdapter(categories) { tid -> onCategory(tid) }
        }
        vodList = findViewById<RecyclerView>(R.id.vodList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ListAdapter(vods) { id -> onVod(id) }
        }

        initEngine()
    }

    /**
     * 全链路初始化：
     * 1. 从本地读取当前选中的订阅站点
     * 2. drpy 源：下载 ext 规则脚本 → 作为 rule 传给引擎
     *    JSON 源：把整个站点 JSON 传给引擎（site_json）
     * 3. 无订阅时使用 assets 内置 drpy.js
     * 4. Engine.init 注册该站点 → home 取分类
     */
    private fun initEngine() {
        Thread {
            val dir = filesDir.absolutePath
            copyAssetToFiles("drpy-core-lite.min.js", "$dir/drpy-core-lite.min.js")

            val store = SubscriptionStore(this)
            val site = store.currentSite()
            val params = JSONObject().put("dir", dir)

            if (site != null) {
                siteKey = site.key
                if (site.ext.startsWith("http")) {
                    // drpy 规则源：ext 是规则脚本 URL，下载脚本文本作为 rule
                    val ruleText = JsonFetcher.get(site.ext)
                    if (ruleText.isNullOrBlank()) {
                        runOnUiThread {
                            Toast.makeText(this, "下载规则失败：${site.name}（${site.ext}）", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    params.put("rule", ruleText)
                    params.put("engine", readAsset("drpy2.min.js"))
                } else if (site.siteJson.isNotBlank()) {
                    // JSON 规则源：整个站点 JSON 传给引擎
                    params.put("site_json", site.siteJson)
                } else {
                    // 兜底：内置 drpy.js
                    params.put("rule", readAsset("drpy.js"))
                    params.put("engine", readAsset("drpy2.min.js"))
                }
            } else {
                siteKey = DEFAULT_KEY
                params.put("rule", readAsset("drpy.js"))
                params.put("engine", readAsset("drpy2.min.js"))
            }

            val initRet = Engine.execute("init", siteKey, params.toString())
            val home = Engine.execute("home", siteKey, "")
            runOnUiThread {
                parseHome(home)
                if (!initRet.contains("\"code\":0")) {
                    Toast.makeText(this, "站点注册失败：$initRet", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun onCategory(tid: String) {
        Thread {
            val params = JSONObject().put("tid", tid).put("pg", "1").toString()
            val cat = Engine.execute("category", siteKey, params)
            runOnUiThread { parseCategory(cat) }
        }.start()
    }

    private fun onVod(vodId: String) {
        val name = vods.firstOrNull { it.first == vodId }?.second
        Thread {
            val params = JSONObject().put("flag", "play").put("id", vodId).toString()
            val play = Engine.execute("player", siteKey, params)
            runOnUiThread {
                val url = runCatching { JSONObject(play).getString("url") }.getOrNull()
                if (url.isNullOrBlank()) {
                    Toast.makeText(this, "未解析到播放地址：$play", Toast.LENGTH_LONG).show()
                } else {
                    playUrl(url, name)
                }
            }
        }.start()
    }

    /**
     * 统一播放入口：按设置选择内置 ExoPlayer 或外部 mpv。
     */
    private fun playUrl(url: String, title: String?) {
        // mpv 内核且已安装 → 交给 mpv
        if (PlayerManager.shouldUseMpv(this)) {
            if (PlayerManager.launchMpv(this, url, title)) {
                return // 已交给外部播放器
            }
            // 启动失败则回退内置
        }
        // 内置 ExoPlayer
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
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
        (vodList.adapter as ListAdapter).notifyDataSetChanged()
        val siteName = SubscriptionStore(this).currentSite()?.name ?: "内置默认"
        findViewById<TextView>(R.id.title).text =
            getString(R.string.app_name) + " — ${siteName} · 分类 ${categories.size} 个（引擎 home）"
    }

    private fun parseCategory(json: String) {
        vods.clear()
        runCatching {
            val root = JSONObject(json)
            val list = root.getJSONArray("list")
            for (i in 0 until list.length()) {
                val v = list.getJSONObject(i)
                vods.add(v.getString("vod_id") to v.getString("vod_name"))
            }
        }
        (vodList.adapter as ListAdapter).notifyDataSetChanged()
        findViewById<TextView>(R.id.labelVod).text = "列表 ${vods.size} 条（引擎 category）"
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

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
