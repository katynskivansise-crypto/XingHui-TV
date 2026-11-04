package com.xinghui.tv.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.xinghui.tv.R

/**
 * TV 软键盘：遥控器方向键移动、OK 键输入。
 * 覆盖数字 A-Z + 0-9 + 删除/确认，回调给宿主（对齐原项目 KeyboardAdapter）。
 */
class TvSoftKeyboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onChar(c: Char)
        fun onDelete()
        fun onConfirm()
    }

    var listener: Listener? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_tv_keyboard, this, true)
        bindKeys()
    }

    /** 遍历各行 TextView：数字/字母回调字符（字母转小写），删除/确认走专用回调 */
    private fun bindKeys() {
        for (i in 0 until childCount) {
            val row = getChildAt(i)
            if (row !is LinearLayout) continue
            for (j in 0 until row.childCount) {
                val key = row.getChildAt(j)
                if (key !is TextView) continue
                when (key.id) {
                    R.id.k_del -> key.setOnClickListener { listener?.onDelete() }
                    R.id.k_ok -> key.setOnClickListener { listener?.onConfirm() }
                    else -> {
                        val t = key.text.toString().trim()
                        if (t.isNotEmpty()) {
                            val c = t[0].lowercaseChar()
                            key.setOnClickListener { listener?.onChar(c) }
                        }
                    }
                }
            }
        }
    }
}
