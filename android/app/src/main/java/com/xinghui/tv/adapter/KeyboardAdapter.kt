package com.xinghui.tv.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xinghui.tv.R

/**
 * 搜索键盘，一行照原版 leanback/adapter/KeyboardAdapter.java：
 * 头 7 项是功能图标，后面 36 项是 A-Z + 0-9（注音模式换成注音符号），
 * 放在 6 列 GridLayoutManager 里，正好铺满一屏。
 */
class KeyboardAdapter(
    private val onText: (String) -> Unit,
    private val onIcon: (Int) -> Unit,
    private val onIconLong: (Int) -> Boolean,
    zhuyin: Boolean,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val icons = listOf(
        R.drawable.ic_keyboard_remote,
        R.drawable.ic_keyboard_left,
        R.drawable.ic_keyboard_right,
        R.drawable.ic_keyboard_back,
        R.drawable.ic_keyboard_search,
        R.drawable.ic_setting_home,
        R.drawable.ic_keyboard,
    )

    /** 与原版完全相同的 36 个字符 */
    private val enList = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
    )

    /** 与原版相同的注音符号表（切到注音时用，`ic_keyboard` 图标负责切换） */
    private val twList = listOf(
        "ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ", "ㄍ", "ㄎ", "ㄏ", "ㄐ", "ㄑ", "ㄒ",
        "ㄓ", "ㄔ", "ㄕ", "ㄖ", "ㄗ", "ㄘ", "ㄙ", "ㄧ", "ㄨ", "ㄩ", "ㄚ", "ㄛ", "ㄜ", "ㄝ",
        "ㄞ", "ㄟ", "ㄠ", "ㄡ", "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
    )

    private var isZhuyin = zhuyin
    private val items = ArrayList<Any>()

    init { rebuild() }

    private fun rebuild() {
        items.clear()
        items.addAll(icons)
        items.addAll(if (isZhuyin) twList else enList)
    }

    /** 返回切换后的状态，供外面写设置 */
    fun toggle(): Boolean {
        isZhuyin = !isZhuyin
        rebuild()
        notifyDataSetChanged()
        return isZhuyin
    }

    override fun getItemViewType(position: Int): Int = if (items[position] is String) 0 else 1

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 0) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.adapter_keyboard_text, parent, false)
            TextHolder(v as TextView)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.adapter_keyboard_icon, parent, false)
            IconHolder(v as ImageView)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is TextHolder -> holder.tv.text = item as String
            is IconHolder -> holder.iv.setImageResource(item as Int)
        }
    }

    inner class TextHolder(val tv: TextView) : RecyclerView.ViewHolder(tv), View.OnClickListener {
        init { tv.setOnClickListener(this) }
        override fun onClick(v: View) {
            onText(items[adapterPosition] as String)
        }
    }

    inner class IconHolder(val iv: ImageView) :
        RecyclerView.ViewHolder(iv), View.OnClickListener, View.OnLongClickListener {
        init {
            iv.setOnClickListener(this)
            iv.setOnLongClickListener(this)
        }
        override fun onClick(v: View) {
            onIcon(items[adapterPosition] as Int)
        }
        override fun onLongClick(v: View): Boolean = onIconLong(items[adapterPosition] as Int)
    }
}
