package com.xinghui.tv.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.ListAdapter
import com.xinghui.tv.PlayLine
import com.xinghui.tv.R
import com.xinghui.tv.adapter.EpisodeGridAdapter

/**
 * 选集/换源弹窗（TV 遥控器操作，对应原项目 EpisodeDialog）：
 * 左线路列表（单列），右集数网格（多列、当前集高亮）。
 * 方向键左右切换分区，OK 键选中集数播放。
 */
class EpisodeDialog(
    context: Context,
    private val lines: List<PlayLine>,
    private val onSelect: (lineIdx: Int, epIdx: Int) -> Unit,
    private val curLine: Int = 0,
    private val curEp: Int = -1,
) : Dialog(context) {

    private var currentLine = curLine
    private val linePairs = mutableListOf<Pair<String, String>>()
    private lateinit var epAdapter: EpisodeGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_episode)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        val rvLine = findViewById<RecyclerView>(R.id.rvLine)
        rvLine.layoutManager = LinearLayoutManager(context)
        rvLine.adapter = ListAdapter(linePairs) { key ->
            key.removePrefix("L:").toIntOrNull()?.let { currentLine = it; showEpisodes() }
        }
        rvLine.nextFocusRightId = R.id.rvEpisode

        val rvEpisode = findViewById<RecyclerView>(R.id.rvEpisode)
        rvEpisode.layoutManager = GridLayoutManager(context, 6)
        epAdapter = EpisodeGridAdapter(emptyList(), -1) { ep ->
            onSelect(currentLine, ep)
            dismiss()
        }
        rvEpisode.adapter = epAdapter
        rvEpisode.nextFocusLeftId = R.id.rvLine

        buildLines()
    }

    private fun buildLines() {
        linePairs.clear()
        lines.forEachIndexed { i, l -> linePairs.add("L:$i" to l.name) }
        findViewById<RecyclerView>(R.id.rvLine).adapter?.notifyDataSetChanged()
        showEpisodes()
    }

    private fun showEpisodes() {
        val line = lines.getOrNull(currentLine)
        val eps = line?.episodes ?: emptyList()
        // 仅当前线路的当前集高亮；切线路时不沿用旧集
        val act = if (currentLine == curLine) curEp else -1
        epAdapter = EpisodeGridAdapter(eps, act) { ep ->
            onSelect(currentLine, ep)
            dismiss()
        }
        findViewById<RecyclerView>(R.id.rvEpisode).adapter = epAdapter
        epAdapter.notifyDataSetChanged()
    }
}
