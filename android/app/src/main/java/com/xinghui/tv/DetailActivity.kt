package com.xinghui.tv

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * 详情页：展示影片信息 + 播放线路/集数列表（遥控器切换）。
 * 点击线路 → 展开集数 → 点击集数 → 进入播放页。
 */
class DetailActivity : Activity() {

    private lateinit var store: HistoryStore
    private var vod: Vod? = null
    private var currentLine: PlayLine? = null

    private val pairs = mutableListOf<Pair<String, String>>() // (key, 显示名)
    private lateinit var adapter: ListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        store = HistoryStore(this)
        val vodId = intent.getStringExtra("vod_id") ?: ""

        val list = findViewById<RecyclerView>(R.id.detailList)
        adapter = ListAdapter(pairs) { key -> onPick(key) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnFav).setOnClickListener { toggleFav() }
        findViewById<View>(R.id.btnDesc).setOnClickListener { showDescDialog() }

        Thread {
            val key = SubscriptionStore(this).currentKey() ?: MainActivity.DEFAULT_KEY
            val params = JSONObject().put("ids", vodId).toString()
            val detail = Engine.execute("detail", key, params)
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
        val info = listOf(v.vodYear, v.vodArea, v.vodRemarks).filter { it.isNotBlank() }.joinToString(" · ")
        findViewById<TextView>(R.id.detailInfo).text = info.ifBlank { "暂无信息" }
        findViewById<TextView>(R.id.detailDesc).text = v.vodContent.ifBlank { "暂无简介" }
        if (Settings(this).showPoster) {
            AsyncImageLoader.load(findViewById(R.id.detailImage), v.vodPic)
        }
        findViewById<TextView>(R.id.btnFav).text = if (store.isFavorite(v.vodId)) "取消收藏" else "收藏"

        pairs.clear()
        val lines = v.parseLines()
        if (lines.isEmpty()) {
            pairs.add("none" to "无播放线路")
        } else {
            lines.forEachIndexed { i, line -> pairs.add("L:$i" to line.name) }
        }
        findViewById<TextView>(R.id.detailListLabel).text = "播放线路（${lines.size}）"
        findViewById<RecyclerView>(R.id.detailList).layoutManager = LinearLayoutManager(this)
        adapter.notifyDataSetChanged()
    }

    /** 显示当前线路的集数列表 */
    private fun renderEpisodes(line: PlayLine) {
        pairs.clear()
        if (line.episodes.isEmpty()) {
            pairs.add("none" to "无集数")
        } else {
            line.episodes.forEachIndexed { i, (name, _) ->
                pairs.add("E:$i" to name)
            }
        }
        findViewById<TextView>(R.id.detailListLabel).text = "集数（${line.name}）"
        findViewById<RecyclerView>(R.id.detailList).layoutManager = GridLayoutManager(this, 6)
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
        }
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
        findViewById<TextView>(R.id.btnFav).text = if (fav) "取消收藏" else "收藏"
        Toast.makeText(this, if (fav) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }
}
