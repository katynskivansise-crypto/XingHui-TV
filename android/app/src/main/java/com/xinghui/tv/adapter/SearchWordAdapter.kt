package com.xinghui.tv.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 搜索历史词适配器：一行一个词，焦点高亮，点击回调。 */
class SearchWordAdapter(
    private val words: List<String>,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<SearchWordAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.textSize = 15f
        tv.setPadding(16, 12, 16, 12)
        tv.isFocusable = true
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val word = words[position]
        holder.text.text = word
        holder.text.setOnClickListener { onClick(word) }
        holder.text.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) 0xFF335588.toInt() else android.graphics.Color.TRANSPARENT)
        }
    }

    override fun getItemCount(): Int = words.size
}
