package com.xinghui.tv

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
 * 最小可运行壳：加载 Rust 引擎 → init drpy 规则（assets）→ home/category →
 * 展示列表 → 点击用 ExoPlayer 播放（player 返回的 url）。
 *
 * 真实源若不可达，播放可能失败，但整条数据流（Rust 引擎执行 drpy 规则）已打通。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val KEY = "demo"
    }

    private lateinit var catList: RecyclerView
    private lateinit var vodList: RecyclerView
    private var player: ExoPlayer? = null

    private val categories = mutableListOf<Pair<String, String>>() // (type_id, type_name)
    private val vods = mutableListOf<Pair<String, String>>() // (vod_id, vod_name)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val playerView = findViewById<PlayerView>(R.id.player)
        player = ExoPlayer.Builder(this).build().also { playerView.player = it }

        catList = findViewById(R.id.catList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = ListAdapter(categories) { tid -> onCategory(tid) }
        }
        vodList = findViewById(R.id.vodList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ListAdapter(vods) { id -> onVod(id) }
        }

        initEngine()
    }

    private fun initEngine() {
        Thread {
            val dir = filesDir.absolutePath
            copyAssetToFiles("drpy-core-lite.min.js", "$dir/drpy-core-lite.min.js")
            val rule = readAsset("drpy.js")
            val engine = readAsset("drpy2.min.js")
            val params = JSONObject()
                .put("dir", dir)
                .put("rule", rule)
                .put("engine", engine)
                .toString()
            Engine.execute("init", KEY, params)
            val home = Engine.execute("home", KEY, "")
            runOnUiThread { parseHome(home) }
        }.start()
    }

    private fun onCategory(tid: String) {
        Thread {
            val params = JSONObject().put("tid", tid).put("pg", "1").toString()
            val cat = Engine.execute("category", KEY, params)
            runOnUiThread { parseCategory(cat) }
        }.start()
    }

    private fun onVod(vodId: String) {
        Thread {
            val params = JSONObject().put("flag", "play").put("id", vodId).toString()
            val play = Engine.execute("player", KEY, params)
            runOnUiThread {
                val url = runCatching { JSONObject(play).getString("url") }.getOrNull()
                if (url.isNullOrBlank()) {
                    Toast.makeText(this, "未解析到播放地址：$play", Toast.LENGTH_LONG).show()
                } else {
                    playUrl(url)
                }
            }
        }.start()
    }

    private fun playUrl(url: String) {
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
        findViewById<TextView>(R.id.title).text = getString(R.string.app_name) + " — 分类 ${categories.size} 个（引擎 home）"
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
