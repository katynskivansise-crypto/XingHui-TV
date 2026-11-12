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

    /**
     * 诊断实验档位（由 /debug?x=N 设置，只用于排查，不改默认行为）：
     *   0 = 正常
     *   1 = 呼出控制条时不 requestFocus
     *   2 = 菜单键什么都不做（连控制条都不呼出），用于判断黑屏是否与控制条有关
     * 一次装包就能在手机浏览器切换，省掉反复打包。
     */
    @Volatile
    var experiment: Int = 0

    private const val MAX = 80

    /**
     * 按键/输入环境单独存一条更短的轨迹。
     *   现场最需要的往往不是"最近 80 条里挑那几条按键"，而是"我按了这三下，到底进了哪条分支"。
     *   主环形缓冲被 [sv2]/[hist]/[ui] 这些周期性行挤满后，按键行经常已经被挤出窗口，
     *   于是每次都要猜。这里单独留 24 条，/debug 第一屏就是它。
     */
    private const val KEY_MAX = 24

    private val keys = LinkedList<String>()

    private val lines = LinkedList<String>()

    /** 当前状态快照，由 PlayActivity 在 onCreate 时接上 */
    @Volatile
    var snapshot: (() -> String)? = null

    /** 环形日志：/debug 的「最近事件」段 */
    @Synchronized
    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$t  $msg")
        while (lines.size > MAX) lines.removeFirst()
        if (msg.startsWith("[key]") || msg.startsWith("[env]") || msg.startsWith("[trace]")) {
            keys.addLast("$t  $msg")
            while (keys.size > KEY_MAX) keys.removeFirst()
        }
        android.util.Log.i("XingHuiTV", msg)
    }

    @Synchronized
    fun dump(): String {
        val head = runCatching { snapshot?.invoke() ?: "（未接入）" }.getOrElse { "快照异常：$it" }
        return buildString {
            appendLine("== 按键轨迹（新→旧，最多 $KEY_MAX 条）==")
            if (keys.isEmpty()) {
                appendLine("（本进程还没有按键事件）")
            } else {
                val k = keys.descendingIterator()
                while (k.hasNext()) appendLine(k.next())
            }
            appendLine()
            appendLine("== 当前状态 ==")
            appendLine(head)
            appendLine()
            appendLine("== 最近事件（新→旧）==")
            val it = lines.descendingIterator()
            while (it.hasNext()) appendLine(it.next())
        }
    }
}
