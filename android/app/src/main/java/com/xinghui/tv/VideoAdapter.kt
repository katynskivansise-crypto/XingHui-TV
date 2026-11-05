package com.xinghui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 影片网格适配器：海报 + 片名 + 备注，TV 遥控器焦点高亮。
 */
class VideoAdapter(
    private val items: List<Vod>,
    private val onClick: (Vod) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val image: ImageView = root.findViewById(R.id.vodImage)
        val name: TextView = root.findViewById(R.id.vodName)
        val remarks: TextView = root.findViewById(R.id.vodRemarks)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        root.isFocusable = true
        root.isClickable = true
        return VH(root)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val vod = items[position]
        holder.name.text = vod.vodName
        val tag = vod.vodRemarks.ifBlank { vod.vodYear }.trim()
        holder.remarks.text = tag
        // 空备注不显示空绿块（原版也是只有有备注时才画角标）
        holder.remarks.visibility = if (tag.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        if (Settings(holder.root.context).showPoster) {
            AsyncImageLoader.load(holder.image, vod.vodPic)
        } else {
            holder.image.setImageDrawable(android.graphics.drawable.ColorDrawable(0xFF1A1A1A.toInt()))
        }
        holder.root.setOnClickListener { onClick(vod) }
    }

    override fun getItemCount(): Int = items.size
}
