package com.xinghui.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.adapter.LibAdapter

/**
 * 收藏页（海报墙）：点击进详情，取消收藏（删除），一键清空。
 */
class CollectActivity : Activity() {

    private lateinit var store: HistoryStore
    private val items = mutableListOf<Vod>()
    private lateinit var adapter: LibAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lib)
        // 对齐原版 BaseActivity.refreshWall：全站 window 层壁纸，布局透明透出来
        runCatching { WallpaperStore.apply(this) }

        store = HistoryStore(this)
        findViewById<TextView>(R.id.libTitle).text = "收藏"

        adapter = LibAdapter(items, { vod ->
            runCatching {
                startActivity(Intent(this, DetailActivity::class.java).putExtra("vod_id", vod.vodId))
            }.onFailure { e ->
                Toast.makeText(this, "打开失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, { vod ->
            store.toggleFavorite(vod.vodId, vod.vodName, vod.vodPic, vod.vodRemarks)
            refresh()
            Toast.makeText(this, "已取消收藏：${vod.vodName}", Toast.LENGTH_SHORT).show()
        })
        findViewById<RecyclerView>(R.id.libList).apply {
            layoutManager = GridLayoutManager(this@CollectActivity, 4)
            adapter = this@CollectActivity.adapter
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            if (items.isEmpty()) {
                Toast.makeText(this, "暂无收藏", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.clearFavorites()
            refresh()
            Toast.makeText(this, "收藏已清空", Toast.LENGTH_SHORT).show()
        }

        refresh()
    }

    private fun refresh() {
        items.clear()
        items.addAll(store.favorites().map { Vod.fromJson(it) })
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.libCount).text = "共 ${items.size} 条（点击查看 / 取消收藏）"
    }
}
