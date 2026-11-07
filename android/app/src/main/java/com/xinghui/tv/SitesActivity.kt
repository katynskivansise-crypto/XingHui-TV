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
            Thread {
                val text = JsonFetcher.get(url)
                runOnUiThread {
                    inputUrl.isEnabled = true
                    if (text == null) {
                        Toast.makeText(this, "下载失败，请检查地址或网络", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    // csp_ 源需要 spider.jar：先解析订阅顶层 spider 字段，再解析站点（站点自带 jar，对齐原项目）
                    val jarRaw = SubscriptionParser.parseJar(text)
                    val jarFull = SubscriptionParser.jarUrl(jarRaw, url)
                    val sites = SubscriptionParser.parse(text, jarFull, url)
                    if (sites.isEmpty()) {
                        Toast.makeText(this, "未解析到站点（需 JSON 订阅格式）", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    store.addAll(sites)
                    if (jarFull.isNotBlank()) {
                        store.setJar(jarFull)
                        store.attachJar(jarFull)
                    }
                    // 保存订阅 URL：drpy2.js 规则源（./js/*.js / ./lib/*.js）相对路径要基于它解析
                    store.setSubscribeUrl(url)
                    // 换订阅=换 jar 与站点集合，旧的 spider 实例（各自挂着一个 DexClassLoader）
                    // 再也不会被用到，必须在这里放掉，否则每换一次订阅就多留一整套类加载器。
                    CspLoader.destroy()
                    refresh(tvCurrent, labelSites)
                    Toast.makeText(this, "已添加 ${sites.size} 个站点：${sites.joinToString("、") { it.name }}", Toast.LENGTH_LONG).show()
                }
            }.start()
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
