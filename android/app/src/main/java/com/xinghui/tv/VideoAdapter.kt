package com.xinghui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 影片列表适配器：按站点 Style 自适应卡片形态。
 *
 * 对齐原版：
 * - `bean/Style`：rect（海报，默认 ratio 0.75）/ list（横条）/ oval（圆形 ratio 1.0）
 * - `Product.getSpec`：格宽 = (屏宽 - 48dp - 16dp*(列数-1)) / 列数，海报高 = 格宽 / ratio
 * - `VodFragment.addVideo`：style.isList() 走列表，否则走网格
 *
 * 之前写死 200dp × 250dp，导致订阅里标了 style 的 51 个站点（体育站 list、
 * 影视站 ratio 1.33、教育站 1.618、听书站 1.0）形态全部错成竖版海报。
 */
class VideoAdapter(
    private val items: List<Vod>,
    private var styleType: String = "rect",
    private var styleRatio: Float = 0.75f,
    private var columns: Int = 4,
    private val onClick: (Vod) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    companion object {
        private const val TYPE_RECT = 0
        private const val TYPE_LIST = 1
    }

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val image: ImageView = root.findViewById(R.id.vodImage)
        val name: TextView = root.findViewById(R.id.vodName)
        val remarks: TextView = root.findViewById(R.id.vodRemarks)
        /** 仅 rect 布局有海报容器，list 布局为 null */
        val posterBox: View? = root.findViewById(R.id.posterBox)
    }

    /** 是否列表形态（整行一条） */
    val isList: Boolean get() = styleType.equals("list", true)

    /** 有效宽高比，对齐 Style.getRatio()：未指定时 oval = 1.0、其余 = 0.75，上限 4 */
    private val effRatio: Float
        get() {
            val r = if (styleRatio > 0f) styleRatio else if (styleType.equals("oval", true)) 1.0f else 0.75f
            return r.coerceIn(0.1f, 4f)
        }

    /** 切站时更新形态；列数随之变化（list 恒为 1 列） */
    fun setStyle(type: String, ratio: Float, newColumns: Int) {
        val t = type.ifBlank { "rect" }
        if (t == styleType && ratio == styleRatio && newColumns == columns) return
        styleType = t
        styleRatio = ratio
        columns = newColumns
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (isList) TYPE_LIST else TYPE_RECT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_LIST) R.layout.item_video_list else R.layout.item_video
        val root = LayoutInflater.from(parent.context).inflate(layout, parent, false)
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
        holder.remarks.visibility = if (tag.isBlank()) View.GONE else View.VISIBLE
        if (Settings(holder.root.context).showPoster) {
            AsyncImageLoader.load(holder.image, vod.vodPic)
        } else {
            holder.image.setImageDrawable(android.graphics.drawable.ColorDrawable(0xFF1A1A1A.toInt()))
        }
        // 海报高度按 ratio 动态算（对齐原版 Product.getSpec: height = width / ratio）
        holder.posterBox?.let { box ->
            holder.root.post {
                val w = gridItemWidth(box, holder.root)
                if (w > 0) {
                    val lp = box.layoutParams
                    val h = (w / effRatio).toInt()
                    if (lp.height != h) {
                        lp.height = h
                        box.layoutParams = lp
                    }
                }
            }
        }
        holder.root.setOnClickListener { onClick(vod) }
    }

    override fun getItemCount(): Int = items.size

    /** 格宽 = (屏宽 - 48dp - 16dp*(列数-1)) / 列数，对齐原版 Product.getSpec */
    private fun gridItemWidth(anchor: View, root: View): Int {
        val density = anchor.resources.displayMetrics.density
        val cols = columns.coerceAtLeast(1)
        val space = (48 + 16 * (cols - 1)) * density
        val base = anchor.resources.displayMetrics.widthPixels - space
        val w = (base / cols).toInt()
        return if (w > 0) w else root.width
    }
}
