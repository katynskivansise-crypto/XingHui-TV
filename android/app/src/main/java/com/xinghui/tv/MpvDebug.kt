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

    private val lines = LinkedList<String>()

    /** 当前状态快照，由 PlayActivity 在 onCreate 时接上 */
    @Volatile
    var snapshot: (() -> String)? = null

    /**
     * 黑帧探针结论。呼条那 1 秒到底"整窗没被合成"还是"画面层没出帧"，
     * 全由这一行回答，不再需要从长日志里翻 [dark]。
     *   barLum≈0  → 窗口层这一帧被整块跳过（挖洞区域重算 / 合成器丢了这层）
     *   barLum>0  → 窗口层在出画面，黑的是视频层
     */
    @Volatile
    var darkLine: String? = null

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
            appendLine("== 黑帧探针（最近一次呼条）==")
            appendLine(darkLine ?: "（还没按过菜单，或呼条时条是隐藏态）")
            appendLine()
            appendLine("== 最近事件（新→旧）==")
            val it = lines.descendingIterator()
            while (it.hasNext()) appendLine(it.next())
        }
    }
}
