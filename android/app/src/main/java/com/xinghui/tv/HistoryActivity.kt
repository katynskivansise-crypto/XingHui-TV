package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.adapter.LibAdapter

/**
 * 历史记录页（海报墙，对应原项目 HistoryActivity 的删除模式交互）：
 * - 正常模式：点击卡片续播/进详情，长按或点顶部"删除"进入删除模式
 * - 删除模式：卡片显示删除角标，点击卡片删除单条；点顶部"确认清空"弹确认清空全部
 */
class HistoryActivity : Activity() {

    private lateinit var store: HistoryStore
    private val items = mutableListOf<Vod>()
    private lateinit var adapter: LibAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lib)
        // 对齐原版 BaseActivity.refreshWall：全站 window 层壁纸，布局透明透出来
        runCatching { WallpaperStore.apply(this) }

        store = HistoryStore(this)
        findViewById<TextView>(R.id.libTitle).text = "历史记录"

        val column = ProductColumn.of(this)
        val (cw, ch) = ProductColumn.cell(this, column)
        adapter = LibAdapter(
            items,
            { vod ->
                runCatching {
                    if (vod.vodFlag.isNotBlank() && vod.vodEpUrl.isNotBlank()) {
                        startActivity(Intent(this, PlayActivity::class.java).apply {
                            putExtra("vod_id", vod.vodId)
                            putExtra("vod_name", vod.vodName)
                            putExtra("vod_pic", vod.vodPic)
                            putExtra("flag", vod.vodFlag)
                            putExtra("ep_url", vod.vodEpUrl)
                        })
                    } else {
                        startActivity(Intent(this, DetailActivity::class.java).putExtra("vod_id", vod.vodId))
                    }
                }.onFailure { e ->
                    Toast.makeText(this, "打开失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            { vod ->
                store.removeHistory(vod.vodId)
                refresh()
                if (items.isEmpty()) exitDeleteMode()
            },
            { enterDeleteMode() },
            cw, ch,
        )
        findViewById<RecyclerView>(R.id.libList).apply {
            // 原版 HistoryActivity.setRecyclerView():
            //   GridLayoutManager(Product.getColumn()) + SpaceItemDecoration(列数, 16dp)
            layoutManager = GridLayoutManager(this@HistoryActivity, column)
            addItemDecoration(SpaceItemDecoration(column, 16))
            adapter = this@HistoryActivity.adapter
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener { onDeleteButton() }

        refresh()
    }

    private fun enterDeleteMode() {
        if (items.isEmpty()) {
            Toast.makeText(this, "暂无历史可删除", Toast.LENGTH_SHORT).show()
            return
        }
        adapter.deleteMode = true
        findViewById<TextView>(R.id.btnClear).text = "确认清空"
        findViewById<TextView>(R.id.libCount).text = "删除模式：点击卡片删除单条"
    }

    private fun exitDeleteMode() {
        adapter.deleteMode = false
        findViewById<TextView>(R.id.btnClear).text = "删除"
        refresh()
    }

    private fun onDeleteButton() {
        if (!adapter.deleteMode) {
            enterDeleteMode()
            return
        }
        if (items.isEmpty()) {
            exitDeleteMode()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空历史")
            .setMessage("确认清空全部 ${items.size} 条历史记录？")
            .setPositiveButton("清空") { _, _ ->
                store.clearHistory()
                exitDeleteMode()
                Toast.makeText(this@HistoryActivity, "历史已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refresh() {
        items.clear()
        items.addAll(store.history().map { Vod.fromJson(it) })
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.libCount).text =
            "共 ${items.size} 条（点击续播 / 长按删除）"
    }
}
