package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * 直播页（对齐原项目 LiveActivity）：
 * - 左侧频道分组（含"我的收藏"），右侧频道列表；点频道直链播放。
 * - 长按频道 → 加入/移出收藏（KeepStore）。
 * 数据来自引擎 live/category 接口。
 */
class LiveActivity : Activity() {

    companion object {
        private const val KEEP_TID = "__keep__"
    }

    private val groups = mutableListOf<Pair<String, String>>() // (type_id, type_name)
    private val channels = mutableListOf<Pair<String, String>>() // (id, name)
    private lateinit var groupAdapter: ListAdapter
    private lateinit var channelAdapter: ListAdapter
    private var currentTid: String = ""
    private var currentChannel: String = ""
    private lateinit var keepStore: KeepStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)
        // 对齐原版 BaseActivity.refreshWall：全站 window 层壁纸，布局透明透出来
        runCatching { WallpaperStore.apply(this) }

        keepStore = KeepStore(this)

        groupAdapter = ListAdapter(groups) { tid ->
            if (tid == KEEP_TID) loadKeeps() else loadChannels(tid)
        }
        findViewById<RecyclerView>(R.id.liveGroups).apply {
            layoutManager = LinearLayoutManager(this@LiveActivity)
            adapter = groupAdapter
        }
        channelAdapter = ListAdapter(
            channels,
            onClick = { id -> playChannel(id) },
            onLongClick = { id -> toggleKeep(id) },
        )
        findViewById<RecyclerView>(R.id.liveChannels).apply {
            layoutManager = LinearLayoutManager(this@LiveActivity)
            adapter = channelAdapter
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        // 节目单：用配置的 EPG 源查当前频道节目（未配置源时提示）
        findViewById<View>(R.id.btnEpg).setOnClickListener { showEpg() }

        loadGroups()
    }

    override fun onResume() {
        super.onResume()
        // 收藏变化后刷新（保持"我的收藏"分组同步）
        loadGroups()
    }

    /** 独立直播源（设置 → 直播源）→ 引擎 live 解析分组；未配置时仅显示收藏并提示 */
    private fun loadGroups() {
        val liveUrl = Settings(this).liveUrl
        if (liveUrl.isBlank()) {
            groups.clear()
            groups.add(KEEP_TID to "我的收藏")
            groupAdapter.notifyDataSetChanged()
            findViewById<TextView>(R.id.liveLabel).text = "未配置直播源（设置 → 直播源）· 可长按收藏频道"
            currentTid = ""
            Toast.makeText(this, "请先在设置中配置直播源", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val json = Engine.execute(
                "live", "",
                JSONObject().put("url", liveUrl).put("ua", Settings(this).liveUa).toString()
            )
            val list = runCatching {
                val arr = JSONObject(json).optJSONArray("class") ?: JSONObject(json).optJSONArray("list")
                (0 until arr!!.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i)
                    o?.let { c -> 
                        val gid = c.optString("type_id").ifBlank { c.optString("id") }
                        val gname = c.optString("type_name").ifBlank { c.optString("name") }
                        gid to gname
                    }
                }
            }.getOrNull() ?: emptyList()
            runOnUiThread {
                groups.clear()
                groups.add(KEEP_TID to "我的收藏")
                groups.addAll(list)
                groupAdapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.liveLabel).text = "频道分组 ${groups.size} 个（长按频道收藏）"
                // 若上次在当前分组，保持选中
                if (currentTid != KEEP_TID) {
                    groups.firstOrNull { it.first == currentTid }?.let { loadChannels(it.first) }
                        ?: run { currentTid = ""; groups.getOrNull(1)?.let { loadChannels(it.first) } }
                }
            }
        }.start()
    }

    /** 显示"我的收藏"频道 */
    private fun loadKeeps() {
        currentTid = KEEP_TID
        channels.clear()
        val set = keepStore.channels()
        channels.addAll(set.map { it to it })
        channelAdapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.liveLabel).text = "我的收藏 ${channels.size} 个（长按取消收藏）"
    }

    /** 引擎 live（url+tid）→ 该分组频道列表 */
    private fun loadChannels(tid: String) {
        currentTid = tid
        Thread {
            val liveUrl = Settings(this).liveUrl
            val params = JSONObject()
                .put("url", liveUrl)
                .put("tid", tid)
                .put("ua", Settings(this).liveUa)
                .toString()
            val json = Engine.execute("live", "", params)
            val list = runCatching {
                val arr = JSONObject(json).optJSONArray("list") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i)
                    o?.let { it.optString("vod_id") to it.optString("vod_name") }
                }
            }.getOrNull() ?: emptyList()
            runOnUiThread {
                channels.clear()
                channels.addAll(list)
                channelAdapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.liveLabel).text = "频道 ${channels.size} 个（长按收藏）"
            }
        }.start()
    }

    /** 长按频道：加入/移出收藏 */
    private fun toggleKeep(id: String) {
        val name = channels.firstOrNull { it.first == id }?.second ?: id
        val fav = keepStore.toggle(name)
        Toast.makeText(this, if (fav) "已收藏：$name" else "已取消收藏：$name", Toast.LENGTH_SHORT).show()
        if (currentTid == KEEP_TID) loadKeeps()
    }

    /** 点频道：引擎 live 返回的 vod_id 即播放地址，直链播放 */
    private fun playChannel(id: String) {
        val name = channels.firstOrNull { it.first == id }?.second ?: id
        currentChannel = name
        startActivity(Intent(this, PlayActivity::class.java).apply {
            putExtra("vod_id", id)
            putExtra("vod_name", name)
            putExtra("direct_url", id)
        })
    }

    /** 节目单：用配置的 EPG 源查当前频道节目（引擎 epg 接口，XMLTV 解析） */
    private fun showEpg() {
        val epgUrl = Settings(this).epgUrl
        if (epgUrl.isBlank()) {
            Toast.makeText(this, "未配置 EPG 源（设置 → 直播 EPG 源）", Toast.LENGTH_SHORT).show()
            return
        }
        val name = currentChannel
        if (name.isBlank()) {
            Toast.makeText(this, "请先选择一个频道", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            var text = "未查询到节目单"
            try {
                val params = JSONObject().put("url", epgUrl).put("name", name).toString()
                val json = Engine.execute("epg", "", params)
                val arr = JSONObject(json).optJSONArray("list")
                if (arr != null && arr.length() > 0) {
                    text = buildString {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i)
                            val time = o?.optString("time").orEmpty()
                            val title = o?.optString("title").orEmpty()
                            append(if (time.isBlank()) title else "$time  $title")
                            append("\n")
                        }
                    }
                }
            } catch (_: Exception) { /* 保持默认提示 */ }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("节目单：$name")
                    .setMessage(text)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }.start()
    }
}
