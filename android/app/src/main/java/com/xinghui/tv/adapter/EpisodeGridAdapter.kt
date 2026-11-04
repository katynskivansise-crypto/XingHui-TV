package com.xinghui.tv.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 选集网格适配器（对应原项目 EpisodePresenter）：
 * 多列网格展示集数，当前播放集高亮（金色），焦点项亮蓝。
 */
class EpisodeGridAdapter(
    private val eps: List<Pair<String, String>>,
    private val activatedIdx: Int,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<EpisodeGridAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        tv.setTextColor(Color.WHITE)
        tv.textSize = 14f
        tv.gravity = android.view.Gravity.CENTER
        tv.setPadding(8, 14, 8, 14)
        tv.isFocusable = true
        tv.isClickable = true
        tv.setBackgroundColor(0xFF1A1A1A.toInt())
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = eps[position].first
        holder.tv.text = name
        val activated = position == activatedIdx
        holder.tv.setOnClickListener { onClick(position) }
        holder.tv.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(
                when {
                    activated -> 0xFFCC9900.toInt()   // 当前集：金色
                    has -> 0xFF335588.toInt()          // 焦点：亮蓝
                    else -> 0xFF1A1A1A.toInt()         // 普通：暗
                }
            )
        }
        if (activated) holder.tv.setBackgroundColor(0xFFCC9900.toInt())
    }

    override fun getItemCount(): Int = eps.size
}
