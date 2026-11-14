package com.xinghui.tv.mpv

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.xinghui.tv.MpvDebug
import `is`.xyz.mpv.MPVLib

/**
 * mpv 控制核心：**官方 libmpv + 官方 libplayer.so（MPVLib）+ vo=gpu**。
 *
 * 这条路和 mpv-android 官方 App 完全一致（README 原文：
 *   "If you'd like to use libmpv in your app you can use our code as inspiration.
 *    The important parts are MPVLib, MPVView and the native code"）。
 *
 * 以前这里走的是自研 render API（vo=libmpv + mpv_render_context_render + 自持 EGL，
 * 见已废弃的 xh_mpv.cpp / XhMpv.kt）：那条路要把 EGL 生命周期、渲染线程、
 * 手工 report_swap 全部自己管，任何一环错位都表现为"黑屏有声"。
 * 官方 vo=gpu 把这一整层交给 mpv 自己，我们只负责把 Surface 交过去。
 *
 * 线程模型现在很简单：
 *   * 画面：mpv 内部渲染线程（vo=gpu + gpu-context=android）
 *   * 事件：libplayer.so 通过 MPVLib 静态回调直接推上来，不再 200ms 轮询
 *   * 所有回调都 post 到主线程，调用方不用关心线程
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

    companion object {
        /** MPVLib 的句柄是 so 内静态的：真正能 destroy 的只有最后一个核心 */
        @Volatile private var liveCores = 0
    }

    /** 与官方 MPVLib.MpvFormat 同一套取值，上层沿用旧的 MpvCore.MpvFormat 写法 */
    object MpvFormat {
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
    }

    object MpvEvent {
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
    }

    @Volatile private var observer: EventObserver? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var dead = false
    @Volatile private var created = false

    @Volatile var colorBits = 8
        private set

    private val main = Handler(Looper.getMainLooper())

    /** libplayer.so 的原生回调 → 主线程 + 我们自己的 EventObserver */
    private val libObserver = object : MPVLib.EventObserver {
        override fun event(eventId: Int) {
            main.post { runCatching { observer?.event(eventId) } }
        }

        override fun eventProperty(property: String) {
            main.post { runCatching { observer?.eventProperty(property) } }
        }

        override fun eventProperty(property: String, value: Long) {
            main.post { runCatching { observer?.eventProperty(property, value) } }
        }

        override fun eventProperty(property: String, value: Boolean) {
            main.post { runCatching { observer?.eventProperty(property, value) } }
        }

        override fun eventProperty(property: String, value: String) {
            main.post { runCatching { observer?.eventProperty(property, value) } }
        }

        override fun eventProperty(property: String, value: Double) {
            main.post { runCatching { observer?.eventProperty(property, value) } }
        }
    }

    /**
     * 位深：官方 vo=gpu 自己按 target-colorspace-hint 决定，
     * 我们不再往 GL 侧塞 RGBA_1010102（那是自研 render API 才需要做的事）。
     * 这里只回报"应该用多少"，供诊断日志对照。
     */
    fun applyColorDepth(wantTenBit: Boolean): Int {
        colorBits = if (wantTenBit && Build.VERSION.SDK_INT >= 26) 10 else 8
        return colorBits
    }

    fun create(): Boolean {
        if (dead || created) return created
        val ok = runCatching {
            MPVLib.create(appContext)
            MPVLib.addObserver(libObserver)
            true
        }.getOrElse {
            MpvDebug.log("[mpv] MPVLib.create 失败 ${it.message}")
            false
        }
        if (ok) {
            created = true
            liveCores++
        }
        return ok
    }

    fun init(): Boolean = runCatching { MPVLib.init(); true }.getOrElse {
        MpvDebug.log("[mpv] MPVLib.init 失败 ${it.message}")
        false
    }

    fun setOptionString(name: String, value: String) {
        if (dead || !created) return
        runCatching { MPVLib.setOptionString(name, value) }
            .onFailure { MpvDebug.log("[mpv] 选项失败 $name=$value ${it.message}") }
    }

    fun setPropertyString(name: String, value: String) {
        if (dead || !created) return
        runCatching { MPVLib.setPropertyString(name, value) }
    }

    fun setPropertyBoolean(name: String, value: Boolean) {
        if (dead || !created) return
        runCatching { MPVLib.setPropertyBoolean(name, value) }
    }

    fun setPropertyDouble(name: String, value: Double) {
        if (dead || !created) return
        runCatching { MPVLib.setPropertyDouble(name, value) }
    }

    fun getPropertyString(name: String): String? =
        if (dead || !created) null else runCatching { MPVLib.getPropertyString(name) }.getOrNull()

    fun getPropertyBoolean(name: String): Boolean? =
        if (dead || !created) null else runCatching { MPVLib.getPropertyBoolean(name) }.getOrNull()

    fun getPropertyInt(name: String): Int? =
        if (dead || !created) null else runCatching { MPVLib.getPropertyInt(name) }.getOrNull()

    fun getPropertyDouble(name: String): Double? =
        if (dead || !created) null else runCatching { MPVLib.getPropertyDouble(name) }.getOrNull()

    fun command(args: Array<String>) {
        if (dead || !created) return
        runCatching { MPVLib.command(args) }
    }

    fun observeProperty(name: String, format: Int) {
        if (dead || !created) return
        runCatching { MPVLib.observeProperty(name, format) }
    }

    fun addObserver(o: EventObserver) {
        observer = o
        if (!created) return
        // 官方观察机制：设一次就持续推，不需要轮询
        observeProperty("pause", 3)        // MPV_FORMAT_FLAG
        observeProperty("eof-reached", 3)
        observeProperty("time-pos", 5)     // MPV_FORMAT_DOUBLE
        observeProperty("duration", 5)
        observeProperty("idle-active", 3)
    }

    fun removeObserver(o: EventObserver) {
        if (observer === o) observer = null
    }

    // ---------------------------------------------------------------- 画面

    /**
     * 官方挂面：attachSurface 之后 mpv 自己起 vo=gpu 渲染线程往这块 Surface 推帧。
     * android-surface-size 一定要在 attach 之后设，并且每次窗口尺寸变化都要重设。
     */
    fun attachSurface(s: Surface, width: Int, height: Int) {
        if (dead || !created) return
        applyColorDepth(true)
        surface = s
        val ok = runCatching {
            MPVLib.attachSurface(s)
            if (width > 0 && height > 0) {
                MPVLib.setPropertyString("android-surface-size", "${width}x$height")
            }
            true
        }.getOrElse {
            MpvDebug.log("[mpv] attachSurface 失败 ${it.message}")
            false
        }
        MpvDebug.log("[mpv] vo=gpu attach ${width}x${height} ok=$ok")
    }

    /** 尺寸变了但 Surface 没换：只更新 android-surface-size */
    fun updateSurfaceSize(width: Int, height: Int) {
        if (dead || !created || surface == null) return
        if (width <= 0 || height <= 0) return
        runCatching { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
    }

    fun detachSurface() {
        surface = null
        if (dead || !created) return
        runCatching { MPVLib.detachSurface() }
        MpvDebug.log("[mpv] vo=gpu detach")
    }

    /** vo=gpu 自己上屏；保留这个方法让上层调用点不用改 */
    fun redrawNow() = Unit

    fun destroy() {
        if (dead) return
        dead = true
        observer = null
        surface = null
        if (!created) return
        runCatching { MPVLib.removeObserver(libObserver) }
        liveCores = (liveCores - 1).coerceAtLeast(0)
        if (liveCores == 0) {
            runCatching { MPVLib.destroy() }
            created = false
        }
    }
}
