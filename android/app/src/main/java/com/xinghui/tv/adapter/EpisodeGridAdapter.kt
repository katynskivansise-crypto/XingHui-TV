package com.xinghui.tv.adapter

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.R

/**
 * 选集网格适配器（对应原项目 EpisodePresenter + leanback/res/layout/adapter_episode.xml）。
 *
 * 尺寸全部按原版 item 布局来，不再套 android.R.layout.simple_list_item_1：
 *   simple_list_item_1 的高度取自 ?attr/listPreferredItemHeight（TV 主题下 64dp），
 *   再叠加 setPadding(8,14,8,14) 后一行接近 70dp；原版一行只有 16sp 文字 + 8dp 上下 padding ≈ 37dp。
 *   这就是「集数与集数之间间隔太大」的直接原因。
 *
 * 原版 item 的关键点：
 *   layout_height=wrap_content / textSize=16sp / gravity=center / singleLine /
 *   ellipsize=marquee / background=@drawable/selector_item（三个 shape 自带 12dp|8dp padding）
 * 焦点态、当前集态都交给 selector 的 state_focused / state_activated，不再手写背景色。
 */
class EpisodeGridAdapter(
    private val eps: List<Pair<String, String>>,
    private val activatedIdx: Int,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<EpisodeGridAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 16f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            setTextColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            // 原版 adapter_episode.xml 的 android:background="@drawable/selector_item"；
            // shape_item_* 自带 padding 12dp|8dp，这里不再另设 padding，否则会翻倍撑高
            setBackgroundResource(R.drawable.selector_item)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = eps[position].first
        // 当前播放集：走 selector 的 state_activated（原版 EpisodePresenter 也是这个语义）
        holder.tv.isActivated = position == activatedIdx
        holder.tv.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = eps.size
}
