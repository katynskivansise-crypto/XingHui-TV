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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.searchInput)
        wordStore = SearchWordStore(this)

        // 结果网格
        val list = findViewById<RecyclerView>(R.id.searchList)
        adapter = VideoAdapter(results) { vod ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra("vod_id", vod.vodId))
        }
        list.layoutManager = GridLayoutManager(this, 4)
        list.adapter = adapter

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
}
