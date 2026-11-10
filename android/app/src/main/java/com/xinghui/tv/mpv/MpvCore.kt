package com.xinghui.tv.mpv

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.xinghui.tv.MpvDebug

/**
 * mpv 控制核心：官方 libmpv + 我们自己的 render API 上屏（零分叉）。
 *
 * 与原来 `is.xyz.mpv.MPVLib`（libplayer.so）的关系：
 *   那一层把 mpv_handle 锁在 so 内部，只给 attachSurface；
 *   这里 handle 由我们 create，EGL/GLES2 也由我们持有，
 *   于是 `mpv_render_context_render` + `report_swap` 在我们手里 ——
 *   **哪一帧上屏、什么时候上屏由我们决定**。
 *
 * 线程模型：
 *   * GL 线程（xh-mpv-gl）：attach / detach / render 全在这条线程上（EGL 是线程绑定的）
 *   * 轮询线程（xh-mpv-poll）：200ms 读一次属性，模拟原来的事件回调
 */
class MpvCore(private val appContext: Context) {

    interface EventObserver {
        fun event(eventId: Int)
        fun eventProperty(property: String, value: Double)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: String)
    }

    object MpvFormat {
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_DOUBLE = 5
        const val MPV_FORMAT_INT64 = 4
    }

    object MpvEvent {
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_END_FILE = 7
    }

    private val native = XhMpv()

    private val glThread = HandlerThread("xh-mpv-gl").apply { start() }
    private val gl = Handler(glThread.looper)

    private val pollThread = HandlerThread("xh-mpv-poll").apply { start() }
    private val pollHandler = Handler(pollThread.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var observer: EventObserver? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    @Volatile private var renderPending = false

    @Volatile private var lastPause: Boolean? = null
    @Volatile private var lastEof: Boolean? = null
    @Volatile private var loaded = false

    fun create(): Boolean {
        if (!XhMpv.available) {
            MpvDebug.log("[mpv] xhmpv 原生库不可用")
            return false
        }
        if (!native.create()) return false
        native.onFrameAvailable = { requestRender() }
        return true
    }

    fun init(): Boolean = native.setProperty("pause", "yes")

    fun setOptionString(name: String, value: String) {
        if (!native.setOption(name, value)) MpvDebug.log("[mpv] 选项未生效 $name=$value")
    }

    fun setPropertyString(name: String, value: String) {
        native.setProperty(name, value)
    }

    fun setPropertyBoolean(name: String, value: Boolean) {
        native.setProperty(name, if (value) "yes" else "no")
    }

    fun setPropertyDouble(name: String, value: Double) {
        native.setProperty(name, value.toString())
    }

    fun getPropertyString(name: String): String? = native.getProperty(name)

    fun getPropertyBoolean(name: String): Boolean? = native.getBool(name)

    fun getPropertyInt(name: String): Int? = native.getProperty(name)?.toIntOrNull()

    fun getPropertyDouble(name: String): Double? = native.getDouble(name)

    fun command(args: Array<String>) {
        native.command(*args)
    }

    fun observeProperty(name: String, format: Int) = Unit

    fun addObserver(o: EventObserver) {
        observer = o
        startPolling()
    }

    fun removeObserver(o: EventObserver) {
        if (observer === o) {
            observer = null
            pollHandler.removeCallbacksAndMessages(null)
        }
    }

    // ---------------------------------------------------------------- 画面

    fun attachSurface(s: Surface, width: Int, height: Int) {
        surface = s
        surfaceW = width
        surfaceH = height
        gl.post {
            native.attach(s, width, height)
            native.render(width, height)
            MpvDebug.log("[mpv] render 挂载 ${width}x${height}")
        }
    }

    fun detachSurface() {
        surface = null
        gl.post { native.detach() }
    }

    /** 窗口被扰动后主动重绘上一帧：这一帧本来就在 mpv 手里，重绘不产生新帧 */
    fun redrawNow() {
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return
        requestRender()
        gl.post { native.render(w, h) }
    }

    private fun requestRender() {
        if (renderPending) return
        renderPending = true
        gl.post {
            renderPending = false
            val w = surfaceW
            val h = surfaceH
            if (surface != null && w > 0 && h > 0) native.render(w, h)
        }
    }

    fun destroy() {
        observer = null
        pollHandler.removeCallbacksAndMessages(null)
        gl.post { native.detach(); native.destroy() }
        surface = null
    }

    // ---------------------------------------------------------------- 属性轮询 → 事件

    private fun startPolling() {
        pollHandler.removeCallbacksAndMessages(null)
        pollHandler.post(object : Runnable {
            override fun run() {
                if (observer == null) return
                poll()
                pollHandler.postDelayed(this, 200)
            }
        })
    }

    private fun poll() {
        val o = observer ?: return
        val idle = native.getBool("idle-active") ?: true
        val dur = native.getDouble("duration") ?: 0.0

        if (!loaded && !idle && dur > 0.0) {
            loaded = true
            main.post { runCatching { o.event(MpvEvent.MPV_EVENT_FILE_LOADED) } }
        }

        val eof = native.getBool("eof-reached") ?: false
        if (loaded && eof) {
            loaded = false
            main.post { runCatching { o.event(MpvEvent.MPV_EVENT_END_FILE) } }
        } else if (loaded && idle) {
            loaded = false
            main.post { runCatching { o.event(MpvEvent.MPV_EVENT_END_FILE) } }
        }

        val pause = native.getBool("pause")
        if (pause != null && pause != lastPause) {
            lastPause = pause
            main.post { runCatching { o.eventProperty("pause", pause) } }
        }
        if (eof != lastEof) {
            lastEof = eof
            main.post { runCatching { o.eventProperty("eof-reached", eof) } }
        }
        val pos = native.getDouble("time-pos")
        if (pos != null) main.post { runCatching { o.eventProperty("time-pos", pos) } }
        if (dur > 0.0) main.post { runCatching { o.eventProperty("duration", dur) } }
    }

}
