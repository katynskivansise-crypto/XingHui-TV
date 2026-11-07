package com.xinghui.tv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer

/**
 * 轨道选择弹窗，对齐原版 ui/dialog/TrackDialog.java + player/exo/ExoUtil.selectTrack。
 *
 * 原版入口：view_control_vod.xml 里 text/audio/video 三个按钮的 android:tag 分别是
 * 3 / 1 / 2 —— 正好是 C.TRACK_TYPE_TEXT / AUDIO / VIDEO。
 *
 * 选中逻辑与原版一致：
 *   select   → 把该 track 与「已选」的一起塞进 TrackSelectionOverride
 *   deselect → 只留「其它已选」的
 *   group 用 getMediaTrackGroup()，即 media3 的 TrackGroup
 */
class TrackDialog(
    context: Context,
    private val player: ExoPlayer?,
    private val type: Int,
    /** mpv 内核时传它，player 传 null；两者共用同一套弹窗外观 */
    private val mpv: MpvPlayer? = null
) : Dialog(context) {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(0xE6000000.toInt()))
        window?.setGravity(Gravity.CENTER)
        window?.setLayout(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(buildContent())
    }

    private fun title(): String = when (type) {
        C.TRACK_TYPE_TEXT -> "字幕"
        C.TRACK_TYPE_AUDIO -> "音轨"
        C.TRACK_TYPE_VIDEO -> "视轨"
        else -> "轨道"
    }

    private fun buildContent(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(TextView(context).apply {
            text = title()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(8), 0, dp(8), dp(10))
        })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var shown = if (mpv != null) fillMpv(list) else fillExo(list)
        if (shown == 0) {
            list.addView(item("本片没有可选的${title()}", false, null))
        }

        // 原版 TrackDialog 底部只对字幕给「关闭」开关；音轨同理，视轨关了就是黑屏，不给入口
        if (type != C.TRACK_TYPE_VIDEO) {
            list.addView(item("关闭${title()}", true) {
                if (mpv != null) {
                    mpv.disableTracks(kindOf())
                } else {
                    runCatching {
                        player?.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, true)
                            .build()
                    }
                }
                dismiss()
            })
        }

        root.addView(ScrollView(context).apply {
            addView(list)
            isFocusable = false
        })
        return root
    }

    /** mpv 的 kind：video / audio / sub */
    private fun kindOf(): String = when (type) {
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "sub"
        else -> "video"
    }

    /** EXO 侧：沿用原来的 Tracks.Group 遍历 */
    private fun fillExo(list: LinearLayout): Int {
        val groups: List<Tracks.Group> = player?.currentTracks?.groups ?: emptyList()
        var shown = 0
        for (gi in groups.indices) {
            val g = groups[gi]
            if (g.type != type) continue
            for (ti in 0 until g.length) {
                shown++
                val f = g.getTrackFormat(ti)
                list.addView(item(
                    (if (g.isTrackSelected(ti)) "✓ " else "   ") + nameOf(f, shown),
                    true
                ) { applyOverride(g, gi, ti, !g.isTrackSelected(ti)) })
            }
        }
        return shown
    }

    /** mpv 侧：读 track-list/N/xxx 形式的子属性 */
    private fun fillMpv(list: LinearLayout): Int {
        val m = mpv ?: return 0
        val kind = kindOf()
        var shown = 0
        for (t in m.tracks(kind)) {
            shown++
            list.addView(item((if (t.selected) "✓ " else "   ") + t.label, true) {
                m.selectTrack(kind, t.id)
                dismiss()
            })
        }
        return shown
    }

    private fun item(text: String, enabled: Boolean, onClick: (() -> Unit)?): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.selector_text)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = enabled
            isClickable = enabled
            if (onClick != null) setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

    /** 对齐 ExoUtil.selectTrack / deselectTrack：override 的索引集合 = 目标 + 其它已选 */
    private fun applyOverride(group: Tracks.Group, gi: Int, ti: Int, select: Boolean) {
        val p = player ?: return
        runCatching {
            val indices = (0 until group.length).filter { i ->
                if (select) i == ti || group.isTrackSelected(i)
                else i != ti && group.isTrackSelected(i)
            }
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, indices))
                .build()
        }
        dismiss()
    }

    /** 简化版轨道名（原版 TrackNameProvider 会拼分辨率/码率/语言，这里取最关键的几项） */
    private fun nameOf(f: Format, index: Int): String {
        val parts = ArrayList<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { parts.add(it) }
        if (f.height != Format.NO_VALUE) parts.add("${f.height}p")
        if (f.channelCount != Format.NO_VALUE) parts.add("${f.channelCount}ch")
        if (f.codecs != null && f.codecs!!.isNotBlank()) parts.add(f.codecs!!)
        return if (parts.isEmpty()) "轨道 $index" else parts.joinToString(" · ")
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
