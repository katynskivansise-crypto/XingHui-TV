package com.xinghui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 极简 TV 列表项：显示一行文本，支持焦点高亮与点击。 */
class ListAdapter(
    private val items: List<Pair<String, String>>,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<ListAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.textSize = 16f
        tv.setPadding(16, 14, 16, 14)
        tv.isFocusable = true
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (id, name) = items[position]
        holder.text.text = name
        holder.text.setOnClickListener { onClick(id) }
        holder.text.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) 0x33FFFFFF.toInt() else android.graphics.Color.TRANSPARENT)
        }
    }

    override fun getItemCount(): Int = items.size
}
