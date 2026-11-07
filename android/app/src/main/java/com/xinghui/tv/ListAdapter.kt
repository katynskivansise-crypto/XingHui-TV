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
            // 集数格：原版 adapter_episode 是无 padding 的 TextView，行高由 VerticalGridView 固定 44dp。
            // 之前这里 padding 12dp + 四边 margin 4dp，格子被撑到 45dp 以上，格子间隔看着很大。
            val h = (tv.context.resources.displayMetrics.density * 44 + 0.5f).toInt()
            tv.setPadding(0, 0, 0, 0)
            tv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h
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

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item.second
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
            // 之前这里用 0xFF335588 铺实心青块，真机上看就是「焦点格一块蓝」，原版没有填充色
            (v as TextView).isActivated = has
        }
    }

    override fun getItemCount(): Int = items.size
}
