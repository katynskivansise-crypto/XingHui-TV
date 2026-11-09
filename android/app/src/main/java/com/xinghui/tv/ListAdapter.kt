package com.xinghui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 通用单列文字列表适配器（分类、线路、集数等）。
 * items: [(key, 显示文本)]，点击回调 key。
 */
class ListAdapter(
    private val items: List<Pair<String, String>>,
    private val onLongClick: ((String) -> Unit)? = null,
    /** 聚焦时的文字色；0 表示沿用原来的纯背景高亮（分类条等不动） */
    private val focusTextColor: Int = 0,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<ListAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.textSize = 16f
        tv.setPadding(16, 12, 16, 12)
        tv.isFocusable = true
        tv.isClickable = true
        // 对齐原版适配器 adapter_episode：半透明黑底 + 4dp 圆角，聚焦时白色 1.5dp 描边
        tv.gravity = android.view.Gravity.CENTER
        tv.background = tv.context.getDrawable(R.drawable.selector_item)
        // 横向 tab（如点播页分类条）：simple_list_item_1 默认 match_parent，横排会撑满导致只显示一项
        val lm = (parent as? RecyclerView)?.layoutManager
        if (lm is androidx.recyclerview.widget.GridLayoutManager) {
            // 集数格：完全对齐原版 leanback/res/layout/adapter_episode.xml ——
            // layout_height=wrap_content / 16sp / gravity=center，高度只由文字 + shape_item 自带的 8dp 上下 padding 决定。
            // 之前这里固定 44dp，但 simple_list_item_1 自带 minHeight=?attr/listPreferredItemHeight
            // （Android TV 主题下是 64dp），44dp 被 minHeight 顶掉，实际一格 64dp ——
            // 这就是「集数之间间隔太大」的真因，改 layoutParams 高度根本压不下来。
            tv.minHeight = 0
            tv.setPadding(0, 0, 0, 0)
            tv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        if (lm is androidx.recyclerview.widget.LinearLayoutManager &&
            lm.orientation == androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL
        ) {
            val gap = (tv.context.resources.displayMetrics.density * 20 + 0.5f).toInt()
            tv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = gap }
            tv.setPadding(24, 14, 24, 14)
            tv.background = tv.context.getDrawable(R.drawable.selector_tv_btn)
        }
        return VH(tv)
    }

    /**
     * 当前「正在播」的那一项的 key。
     * 原版是 Episode.setActivated + Flag.setActivated（adapter 里 setSelected/setActivated 双设），
     * 外观由 selector_item 的 state_activated 分支给出（shape_item_activated：black_40 底 + 4dp 圆角）。
     * 列表里一直缺这个「当前集」标记，集数格看不出播的是第几集。
     */
    var activeKey: String? = null
        private set

    fun setActive(key: String?) {
        if (activeKey == key) return
        activeKey = key
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item.second
        // 只按 key 判"当前项"；焦点态仍由 state_focused 负责，两者互不伪装
        holder.text.isActivated = activeKey != null && item.first == activeKey
        holder.text.setOnClickListener { onClick(item.first) }
        holder.text.setOnLongClickListener {
            onLongClick?.invoke(item.first)
            true
        }
        holder.text.setOnFocusChangeListener { v, has ->
            // 对齐原版集数：聚焦那格是黄字，其余白字
            if (focusTextColor != 0) {
                (v as TextView).setTextColor(
                    if (has) focusTextColor else android.graphics.Color.WHITE
                )
            }
            // 焦点态交给 selector_item 的 state_focused（shape_item_focused：白 1.5dp 描边）。
            // 之前这里写 isActivated = has，把焦点伪装成「当前集」，state_focused 永远不触发，白描边也就永远不出现。
            (v as TextView).refreshDrawableState()
        }
    }

    override fun getItemCount(): Int = items.size
}
