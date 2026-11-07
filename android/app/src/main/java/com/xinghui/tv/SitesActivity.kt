package com.xinghui.tv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 站点管理：添加订阅（输入订阅 URL）→ 下载 JSON → 解析站点 → 列表展示；
 * 遥控器选中站点即切换为当前站点。原生 Activity，兼容旧 TV 盒子。
 */
class SitesActivity : Activity() {

    private lateinit var store: SubscriptionStore
    private val sitePairs = mutableListOf<Pair<String, String>>() // (key, name)
    private lateinit var siteAdapter: ListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sites)
        // 对齐原版 BaseActivity.refreshWall：全站 window 层壁纸，布局透明透出来
        runCatching { WallpaperStore.apply(this) }

        store = SubscriptionStore(this)

        val tvCurrent = findViewById<TextView>(R.id.valCurrent)
        val labelSites = findViewById<TextView>(R.id.labelSites)
        val inputUrl = findViewById<EditText>(R.id.inputUrl)

        siteAdapter = ListAdapter(sitePairs) { key ->
            store.setCurrent(key)
            refresh(tvCurrent, labelSites)
            Toast.makeText(this, "已切换到：${store.currentSite()?.name ?: key}", Toast.LENGTH_SHORT).show()
        }
        findViewById<RecyclerView>(R.id.siteList).apply {
            layoutManager = LinearLayoutManager(this@SitesActivity)
            adapter = siteAdapter
        }

        // 添加订阅：后台下载 → 解析 → 存储
        findViewById<View>(R.id.btnAdd).setOnClickListener {
            val url = inputUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "请先粘贴订阅地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            inputUrl.isEnabled = false
            Toast.makeText(this, "正在下载订阅…", Toast.LENGTH_SHORT).show()
            // 统一链路（对齐原版 ConfigDialog.onPositive → Config.find → VodConfig.load）：
            // 真拉订阅 → 解析 → 落库 → 记配置历史 → 放掉旧 spider
            SubscriptionImporter.import(this, url) { ok, msg ->
                inputUrl.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) refresh(tvCurrent, labelSites)
            }
        }

        // 删除当前站点
        findViewById<View>(R.id.btnRemove).setOnClickListener {
            val key = store.currentKey()
            if (key == null) {
                Toast.makeText(this, "当前没有站点", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.remove(key)
            refresh(tvCurrent, labelSites)
            Toast.makeText(this, "已删除当前站点", Toast.LENGTH_SHORT).show()
        }

        refresh(tvCurrent, labelSites)
    }

    private fun refresh(tvCurrent: TextView, labelSites: TextView) {
        val sites = store.sites()
        sitePairs.clear()
        sites.forEach { sitePairs.add(it.key to it.name) }
        siteAdapter.notifyDataSetChanged()
        val cur = store.currentSite()
        tvCurrent.text = cur?.let { "${it.name}（${it.key}）" } ?: "未选择（使用内置默认源）"
        labelSites.text = "已订阅 ${sites.size} 个站点（遥控器选择切换）"
    }
}
