package com.xinghui.tv.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.R

/**
 * 搜索历史，照原版 leanback/adapter/RecordAdapter.java：
 * 最多 8 条，按过的词插到最前，点一下直接搜，长按删掉这一条。
 */
class RecordAdapter(
    private val items: MutableList<String>,
    private val onClick: (String) -> Unit,
    private val onChanged: (Int) -> Unit,
) : RecyclerView.Adapter<RecordAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.adapter_search_record, parent, false)
        return VH(v as TextView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = items[position]
    }

    override fun getItemCount(): Int = items.size

    /** 原版 checkToAdd：已存在就挪到最前，不重复；超过 8 条砍掉最后一条 */
    fun add(word: String) {
        val index = items.indexOf(word)
        if (index == -1) {
            items.add(0, word)
            notifyItemInserted(0)
        } else {
            items.removeAt(index)
            items.add(0, word)
            notifyItemRangeChanged(0, items.size)
        }
        if (items.size > 8) {
            items.removeAt(8)
            notifyItemRemoved(8)
        }
        onChanged(items.size)
    }

    fun refresh(list: List<String>) {
        items.clear()
        items.addAll(list.take(8))
        notifyDataSetChanged()
        onChanged(items.size)
    }

    fun snapshot(): List<String> = items.toList()
}
