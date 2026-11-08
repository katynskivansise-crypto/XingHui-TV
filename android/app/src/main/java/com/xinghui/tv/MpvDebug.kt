package com.xinghui.tv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * 真机排查通道（只服务于调试，界面上一行都不加）。
 *
 * 背景：盒子是斐讯 T1，没有 adb，logcat 看不到；mpv 那几个问题时灵时不灵，
 * 光靠「黑屏/没反应」这种描述分不清是 surface 没了、内核假暂停，还是实例重复。
 * 这里把关键事件攒进环形缓冲，随现有 9978 本地服务暴露成 http://盒子IP:9978/debug，
 * 手机浏览器打开就能截图，不用装任何工具、不改播放页 UI、不弹任何提示。
 */
object MpvDebug {

    private const val MAX = 80

    private val lines = LinkedList<String>()

    /** 当前状态快照，由 PlayActivity 在 onCreate 时接上 */
    @Volatile
    var snapshot: (() -> String)? = null

    @Synchronized
    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$t  $msg")
        while (lines.size > MAX) lines.removeFirst()
        android.util.Log.i("XingHuiTV", msg)
    }

    @Synchronized
    fun dump(): String {
        val head = runCatching { snapshot?.invoke() ?: "（未接入）" }.getOrElse { "快照异常：$it" }
        return buildString {
            appendLine("== 当前状态 ==")
            appendLine(head)
            appendLine()
            appendLine("== 最近事件（新→旧）==")
            val it = lines.descendingIterator()
            while (it.hasNext()) appendLine(it.next())
        }
    }
}
