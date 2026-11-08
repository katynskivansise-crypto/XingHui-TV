package com.xinghui.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.adapter.KeyboardAdapter
import com.xinghui.tv.adapter.RecordAdapter

import org.json.JSONObject

/**
 * 搜索页，结构与交互照原版 leanback/SearchActivity + CustomKeyboard：
 *
 *   左栏 128dp  历史（最多 8 条，点一下直接搜，长按删除）—— 原版 RecordAdapter
 *   中间        麦克风 + 输入框 + 6 列键盘（7 个功能图标 + 36 个字符）—— 原版 KeyboardAdapter
 *
 * 7 个图标的原版功能（CustomKeyboard.onIconClick）：
 *   ic_keyboard_remote → onRemote()   推屏
 *   ic_keyboard_left   → 光标左移
 *   ic_keyboard_right  → 光标右移
 *   ic_keyboard_back   → 删一个字（长按清空）
 *   ic_keyboard_search → onSearch()
 *   ic_setting_home    → showDialog() 选站
 *   ic_keyboard        → 切换注音/英文键盘
 *
 * 原版第三栏「热搜」按用户要求不做，所以热词接口、联想接口都没有接。
 */
class SearchActivity : Activity() {

    private val results = mutableListOf<Vod>()
    private lateinit var adapter: VideoAdapter
    private lateinit var store: SearchWordStore
    private val words = mutableListOf<String>()
    private lateinit var recordAdapter: RecordAdapter
    private lateinit var keyboardAdapter: KeyboardAdapter
    private lateinit var input: EditText
    private lateinit var title: TextView
    private lateinit var resultPanel: View
    private lateinit var searchPanel: View

    private var currentKeyword = ""
    private var currentPage = 1
    private var loadingMore = false
    private var zhuyin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        // 对齐原版 BaseActivity.refreshWall：全站 window 层壁纸，布局透明透出来
        runCatching { WallpaperStore.apply(this) }

        store = SearchWordStore(this)
        input = findViewById(R.id.keyword)
        title = findViewById(R.id.resultTitle)
        resultPanel = findViewById(R.id.resultPanel)
        searchPanel = findViewById(R.id.searchPanel)

        // ---- 结果网格（原版跳 CollectActivity，这里用覆盖层，视觉等价） ----
        val list = findViewById<RecyclerView>(R.id.searchList)
        adapter = VideoAdapter(results) { vod ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra("vod_id", vod.vodId))
        }
        list.layoutManager = GridLayoutManager(this, 4)
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (loadingMore || currentKeyword.isEmpty()) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= results.size - 6) loadMore()
            }
        })
        findViewById<View>(R.id.resultBack).setOnClickListener { hideResults() }

        // ---- 左栏历史：原版 RecordAdapter ----
        words.clear()
        words.addAll(store.getWords())
        recordAdapter = RecordAdapter(words, { w ->
            input.setText(w)
            input.setSelection(input.length())
            doSearch(w)
        }, { size ->
            findViewById<View>(R.id.recordLayout).visibility =
                if (size == 0) View.GONE else View.VISIBLE
            store.putWords(words.take(SearchWordStore.MAX))
        })
        findViewById<RecyclerView>(R.id.recordRecycler).apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            addItemDecoration(SpaceItemDecoration(1, 16))
            adapter = recordAdapter
        }
        // 长按删除单条：原版在 ViewHolder.onLongClick 里直接移除
        // （RecyclerView 的 item 长按要在适配器里挂，这里用探针方式补上）
        attachLongClickDelete()
        findViewById<View>(R.id.recordLayout).visibility =
            if (words.isEmpty()) View.GONE else View.VISIBLE

        // ---- 键盘：6 列，照原版 CustomKeyboard.initView ----
        keyboardAdapter = KeyboardAdapter(
            onText = { insertText(it) },
            onIcon = { resId -> onIconClick(resId) },
            onIconLong = { resId -> onIconLongClick(resId) },
            zhuyin = zhuyin,
        )
        findViewById<RecyclerView>(R.id.keyboard).apply {
            itemAnimator = null
            layoutManager = GridLayoutManager(this@SearchActivity, 6)
            addItemDecoration(SpaceItemDecoration(6, 8))
            adapter = keyboardAdapter
        }

        // ---- 输入框回车即搜索（原版 setOnEditorActionListener） ----
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSearch()
                true
            } else false
        }

        // ---- 麦克风：原版 CustomMic 走语音识别，这里没接，点了给文案 ----
        findViewById<ImageView>(R.id.mic).setOnClickListener {
            Toast.makeText(this, "语音搜索：未接入", Toast.LENGTH_SHORT).show()
        }
    }

    /** 结果网格里的 item 长按 = 删掉这条历史（原版 RecordAdapter.ViewHolder.onLongClick） */
    private fun attachLongClickDelete() {
        val rv = findViewById<RecyclerView>(R.id.recordRecycler)
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val pos = rv.getChildAdapterPosition(view)
                view.setOnLongClickListener {
                    val p = rv.getChildAdapterPosition(it)
                    if (p in words.indices) {
                        words.removeAt(p)
                        recordAdapter.notifyItemRemoved(p)
                        store.putWords(words)
                        findViewById<View>(R.id.recordLayout).visibility =
                            if (words.isEmpty()) View.GONE else View.VISIBLE
                    }
                    true
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    /** 字符插入：原版 onTextClick（光标位置插入、光标后移、超过 19 字不再插） */
    private fun insertText(text: String) {
        if (input.length() > 19) return
        val sb = StringBuilder(input.text.toString())
        val cursor = input.selectionStart.coerceAtLeast(0)
        sb.insert(cursor, text)
        input.setText(sb.toString())
        input.setSelection((cursor + text.length).coerceAtMost(input.length()))
    }

    /** 原版 CustomKeyboard.onIconClick */
    private fun onIconClick(resId: Int) {
        when (resId) {
            R.drawable.ic_keyboard_remote ->
                Toast.makeText(this, "推屏：未接入", Toast.LENGTH_SHORT).show()

            R.drawable.ic_keyboard_left -> {
                val c = input.selectionStart
                input.setSelection(if (c - 1 < 0) 0 else c - 1)
            }

            R.drawable.ic_keyboard_right -> {
                val c = input.selectionStart
                input.setSelection((c + 1).coerceAtMost(input.length()))
            }

            R.drawable.ic_keyboard_back -> {
                val c = input.selectionStart
                if (c <= 0) return
                val sb = StringBuilder(input.text.toString())
                sb.deleteCharAt(c - 1)
                input.setText(sb.toString())
                input.setSelection(c - 1)
            }

            R.drawable.ic_keyboard_search -> onSearch()

            R.drawable.ic_setting_home ->
                Toast.makeText(this, "站内搜索：未接入（当前为全站聚合搜索）", Toast.LENGTH_SHORT).show()

            R.drawable.ic_keyboard -> zhuyin = keyboardAdapter.toggle()
        }
    }

    /** 原版 onLongClick：只有删除键有长按行为 = 清空 */
    private fun onIconLongClick(resId: Int): Boolean {
        if (resId != R.drawable.ic_keyboard_back) return false
        input.setText("")
        return true
    }

    /** 原版 onSearch：空关键词直接返回 */
    private fun onSearch() {
        val key = input.text.toString().trim()
        input.setSelection(input.length())
        if (key.isEmpty()) return
        doSearch(key)
    }

    private fun doSearch(keyword: String) {
        recordAdapter.add(keyword)
        currentKeyword = keyword
        currentPage = 1
        loadingMore = false
        hideKeyboardIfAny()
        showResults()
        title.text = "搜索 “$keyword” 查找中…"
        Thread {
            // 聚合搜索：Rust 引擎的 searchAll + 壳侧 csp 源合并
            val params = JSONObject().put("key", keyword).toString()
            val json = Engine.execute("searchAll", "", params)
            val found = Vod.parseList(json).toMutableList()
            val cspJson = CspLoader.searchAll(this, SubscriptionStore(this), keyword)
            found.addAll(Vod.parseList(cspJson))
            runOnUiThread {
                results.clear()
                results.addAll(found)
                adapter.notifyDataSetChanged()
                title.text = "搜索 “$keyword” 共 ${results.size} 条（全部源）"
            }
        }.start()
    }

    /**
     * 取下一页。把同一个 pg 传给引擎与全部 csp 源：
     * spider 的 searchContent 签名是 (key, quick, pg)，pg 缺失时站点永远回第 1 页。
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
                if (more.isEmpty()) {
                    title.text = "搜索 “$key” 共 ${results.size} 条（已到底）"
                    return@runOnUiThread
                }
                currentPage++
                results.addAll(more)
                adapter.notifyDataSetChanged()
                title.text = "搜索 “$key” 第 ${currentPage} 页 · 共 ${results.size} 条"
            }
        }.start()
    }

    private fun showResults() {
        resultPanel.visibility = View.VISIBLE
        findViewById<RecyclerView>(R.id.searchList).requestFocus()
    }

    private fun hideResults() {
        resultPanel.visibility = View.GONE
        input.requestFocus()
    }

    private fun hideKeyboardIfAny() {
        runCatching {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 结果层开着时，返回键先退回搜索面板（原版是另起页面，按下返回自然回搜索页）
        if (keyCode == KeyEvent.KEYCODE_BACK && resultPanel.visibility == View.VISIBLE) {
            hideResults()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        input.requestFocus()
    }
}
