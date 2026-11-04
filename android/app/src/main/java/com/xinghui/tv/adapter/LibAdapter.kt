package com.xinghui.tv.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.AsyncImageLoader
import com.xinghui.tv.R
import com.xinghui.tv.Vod

/**
 * 历史/收藏共用卡片适配器：海报 + 片名 + 备注 + 删除角标，TV 焦点高亮。
 * 支持"删除模式"（对应原项目 HistoryActivity）：
 *   正常模式：点击 → 打开；长按 → 进入删除模式
 *   删除模式：卡片显示删除角标，点击卡片/角标 → 删除该项
 */
class LibAdapter(
    private val items: List<Vod>,
    private val onClick: (Vod) -> Unit,
    private val onDelete: (Vod) -> Unit,
    private val onLongClick: (() -> Unit)? = null,
) : RecyclerView.Adapter<LibAdapter.VH>() {

    var deleteMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val image: ImageView = root.findViewById(R.id.libImage)
        val name: TextView = root.findViewById(R.id.libName)
        val info: TextView = root.findViewById(R.id.libInfo)
        val del: TextView = root.findViewById(R.id.libDel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LayoutInflater.from(parent.context).inflate(R.layout.item_lib, parent, false)
        root.isFocusable = true
        root.isClickable = true
        return VH(root)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val vod = items[position]
        holder.name.text = vod.vodName
        holder.info.text = vod.vodRemarks.ifBlank { " " }
        AsyncImageLoader.load(holder.image, vod.vodPic)
        // 删除角标
        holder.del.visibility = if (deleteMode) View.VISIBLE else View.GONE
        holder.del.setOnClickListener { onDelete(vod) }
        // 卡片：删除模式下点击 = 删除；正常模式点击 = 打开
        holder.root.setOnClickListener {
            if (deleteMode) onDelete(vod) else onClick(vod)
        }
        holder.root.setOnLongClickListener {
            if (!deleteMode) onLongClick?.invoke()
            true
        }
        holder.root.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) 0xFF335588.toInt() else Color.TRANSPARENT)
        }
    }

    override fun getItemCount(): Int = items.size
}
