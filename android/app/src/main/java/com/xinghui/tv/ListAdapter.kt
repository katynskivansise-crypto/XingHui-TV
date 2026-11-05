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
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<ListAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.textSize = 15f
        tv.setPadding(16, 12, 16, 12)
        tv.isFocusable = true
        tv.isClickable = true
        // 横向 tab（如点播页分类条）：simple_list_item_1 默认 match_parent，横排会撑满导致只显示一项
        val lm = (parent as? RecyclerView)?.layoutManager
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
            v.setBackgroundColor(
                if (has) 0xFF335588.toInt() else android.graphics.Color.TRANSPARENT
            )
        }
    }

    override fun getItemCount(): Int = items.size
}
