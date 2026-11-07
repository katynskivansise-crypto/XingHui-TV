package com.xinghui.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.adapter.SearchWordAdapter
import com.xinghui.tv.view.TvSoftKeyboard
import org.json.JSONObject

/**
 * 搜索页：
 * - 关键词输入 + 引擎 search → 影片网格结果 → 点开详情
 * - 内置 TV 软键盘（遥控器输入）
 * - 搜索历史：自动保存、点击复用、支持清空
 */
class SearchActivity : Activity() {

    private val results = mutableListOf<Vod>()
    private lateinit var adapter: VideoAdapter
    private lateinit var wordStore: SearchWordStore
    private val words = mutableListOf<String>()
    private lateinit var wordAdapter: SearchWordAdapter
    private lateinit var input: EditText

    private var currentKeyword = ""
    private var currentPage = 1
    private var loadingMore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        // 对齐原版 BaseActivity.refreshWall：全站 window 层壁纸，布局透明透出来
        runCatching { WallpaperStore.apply(this) }

        input = findViewById(R.id.searchInput)
        wordStore = SearchWordStore(this)

        // 结果网格
        val list = findViewById<RecyclerView>(R.id.searchList)
        adapter = VideoAdapter(results) { vod ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra("vod_id", vod.vodId))
        }
        list.layoutManager = GridLayoutManager(this, 4)
        list.adapter = adapter
        // 滚到末尾自动取下一页。原版搜索页同样是滚到底加载更多；
        // 聚合搜索的每一页 = 所有源的第 N 页合并，所以翻页时把同一个 pg 传给全部源。
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (loadingMore || currentKeyword.isEmpty()) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= results.size - 6) loadMore()
            }
        })

        // 搜索历史
        wordAdapter = SearchWordAdapter(words) { word ->
            input.setText(word)
            doSearch(word)
        }
        findViewById<RecyclerView>(R.id.searchWords).apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            adapter = this@SearchActivity.wordAdapter
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            doSearch(key)
        }

        // TV 软键盘：历史标题长按显示键盘（无实体键盘时）
        val keyboard = findViewById<TvSoftKeyboard>(R.id.keyboard)
        keyboard.listener = object : TvSoftKeyboard.Listener {
            override fun onChar(c: Char) {
                input.append(c.toString())
            }

            override fun onDelete() {
                val s = input.text.toString()
                if (s.isNotEmpty()) input.setText(s.dropLast(1))
            }

            override fun onConfirm() {
                keyboard.visibility = View.GONE
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) doSearch(key)
            }
        }
        // 输入框聚焦时显示软键盘（方便遥控器输入）
        input.setOnFocusChangeListener { _, has ->
            keyboard.visibility = if (has) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.histTitle).setOnClickListener {
            keyboard.visibility = if (keyboard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        refreshWords()
    }

    private fun refreshWords() {
        words.clear()
        words.addAll(wordStore.getWords())
        wordAdapter.notifyDataSetChanged()
        if (words.isEmpty()) findViewById<TextView>(R.id.histTitle).text = "搜索历史（空）"
        else findViewById<TextView>(R.id.histTitle).text = "搜索历史 · 点击标题切换软键盘"
    }

    private fun doSearch(keyword: String) {
        wordStore.addWord(keyword)
        refreshWords()
        currentKeyword = keyword
        currentPage = 1
        loadingMore = false
        Toast.makeText(this, "正在搜索：$keyword", Toast.LENGTH_SHORT).show()
        Thread {
            // 聚合搜索：遍历全部已注册源，引擎合并结果（对齐原项目聚合搜索）
            val params = JSONObject().put("key", keyword).toString()
            val json = Engine.execute("searchAll", "", params)
            val found = Vod.parseList(json).toMutableList()
            // csp_ 源不在 Rust 引擎内，由壳侧 CspLoader 搜索并合并
            val cspJson = CspLoader.searchAll(this, SubscriptionStore(this), keyword)
            found.addAll(Vod.parseList(cspJson))
            runOnUiThread {
                results.clear()
                results.addAll(found)
                adapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.searchLabel).text =
                    "搜索 “$keyword” 共 ${results.size} 条（全部源）"
            }
        }.start()
    }

    /**
     * 取下一页。把同一个 pg 传给引擎与全部 csp 源，结果按来源顺序追加到末尾。
     * 之前搜索只有第 1 页：CspLoader.search 只传 key+false 两个参数，
     * 而 spider 的签名是 searchContent(String key, boolean quick, String pg)，
     * pg 缺失时站点默认回第 1 页，所以一个关键词永远只能看到前 N 条。
     */
    private fun loadMore() {
        if (loadingMore) return
        val key = currentKeyword
        if (key.isEmpty()) return
        loadingMore = true
        val pg = (currentPage + 1).toString()
        Thread {
            val params = JSONObject().put("key", key).put("pg", pg).toString()
            val more = runCatching {
                val json = Engine.execute("searchAll", "", params)
                Vod.parseList(json) + Vod.parseList(
                    CspLoader.searchAll(this, SubscriptionStore(this), key, pg)
                )
            }.getOrElse { emptyList() }
            runOnUiThread {
                loadingMore = false
                val label = findViewById<TextView>(R.id.searchLabel)
                if (more.isEmpty()) {
                    label.text = "搜索 “$key” 共 ${results.size} 条（已到底）"
                    return@runOnUiThread
                }
                currentPage++
                results.addAll(more)
                adapter.notifyDataSetChanged()
                label.text = "搜索 “$key” 第 ${currentPage} 页 · 共 ${results.size} 条"
            }
        }.start()
    }
}
