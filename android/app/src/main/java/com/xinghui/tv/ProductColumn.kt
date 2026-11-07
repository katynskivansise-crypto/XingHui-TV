package com.xinghui.tv

import android.content.Context

/**
 * 照原版 leanback/java/.../Product.java 与 HistoryAdapter.setLayoutSize() 移植。
 *
 * 原版列数不写死：Product.getColumn() = |Setting.getSize() - 7|，
 * Setting.getSize() 默认 2 → 默认 5 列。之前各页硬编码 4 列，
 * 卡片比原版大一圈，就是这个差。
 */
object ProductColumn {

    /** 原版 Product.getColumn() */
    fun of(context: Context): Int = maxOf(1, Math.abs(Settings(context).uiSize - 7))

    /**
     * 原版 HistoryAdapter / HistoryPresenter 的 setLayoutSize()：
     *   space = dp2px(48) + dp2px(16 * (column - 1))
     *   base  = 屏幕宽 - space
     *   width = base / column
     *   height = width / 0.75
     */
    fun cell(context: Context, column: Int = of(context)): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val space = 48f * dm.density + 16f * (column - 1) * dm.density
        val base = dm.widthPixels - space
        val width = (base / column).toInt()
        return width to (width / 0.75f).toInt()
    }
}
