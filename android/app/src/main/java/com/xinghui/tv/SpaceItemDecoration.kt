package com.xinghui.tv

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 照原版 ui/custom/SpaceItemDecoration.java 移植。
 * spanCount = -1 时用于横排（首尾各留一半），> 0 时用于网格（列均分 + 行间距）。
 */
class SpaceItemDecoration(
    private val spanCount: Int,
    spacingDp: Int,
) : RecyclerView.ItemDecoration() {

    private val spacing: Int

    init {
        spacing = spacingDp
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        val spacingPx = (spacing * view.resources.displayMetrics.density).toInt()
        if (position >= 0 && spanCount == -1) {
            outRect.left = if (position == 0) 0 else spacingPx / 2
            outRect.right = if (position == parent.adapter?.itemCount?.minus(1)) 0 else spacingPx / 2
        } else if (position >= 0 && spanCount > 0) {
            val column = position % spanCount
            outRect.left = column * spacingPx / spanCount
            outRect.right = spacingPx - (column + 1) * spacingPx / spanCount
            if (position >= spanCount) outRect.top = spacingPx
        } else {
            outRect.set(0, 0, 0, 0)
        }
    }
}
