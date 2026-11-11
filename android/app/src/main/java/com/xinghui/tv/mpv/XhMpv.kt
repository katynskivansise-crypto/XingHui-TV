package com.xinghui.tv.mpv

import android.view.Surface

/**
 * 官方 libmpv 的 render API 桥（我们自己的 JNI，零分叉）。
 *
 * 与 jniLibs 里 libplayer.so 的区别：
 *   libplayer.so 把 mpv_handle 锁在内部，只暴露 attachSurface；
 *   这里由我们自己 create handle，因此能拿到 mpv_render_context_*，
 *   **哪一帧上屏、什么时候上屏由我们决定**。
 *
 * 线程约定：attach / render / detach / destroy 必须在**同一个线程**上调用
 * （EGL 上下文是线程绑定的），渲染线程由 MpvPlayer 自己起。
 */
class XhMpv {

    /** mpv 有新帧时回调（来自 mpv 内部线程，调用方负责切回渲染线程） */
    var onFrameAvailable: (() -> Unit)? = null

    private var handle: Long = 0

    /** native 回调入口：名字必须是 onFrame、签名 ()V */
    @Suppress("unused")
    fun onFrame() {
        onFrameAvailable?.invoke()
    }

    fun create(): Boolean {
        if (handle != 0L) return true
        handle = nativeCreate(this)
        return handle != 0L
    }

    fun init(): Boolean = handle != 0L && nativeInit(handle)

    /** 实际拿到的 GLES 版本（2 或 3），要在 attach 之后才有意义 */
    fun esVersion(): Int = if (handle == 0L) 2 else nativeEsVersion(handle)

    /** 离屏目标位深：8 或 10（10 需要 GLES3，否则原生侧自动按 8 处理） */
    fun setColorBits(bits: Int) {
        if (handle != 0L) nativeSetColorBits(handle, bits)
    }

    fun setOption(name: String, value: String): Boolean =
        handle != 0L && nativeSetOption(handle, name, value)

    fun setProperty(name: String, value: String): Boolean =
        handle != 0L && nativeSetProperty(handle, name, value)

    fun getProperty(name: String): String? =
        if (handle == 0L) null else nativeGetProperty(handle, name)

    fun getBool(name: String): Boolean? = getProperty(name)?.let { it == "yes" || it == "true" }
    fun getLong(name: String): Long? = getProperty(name)?.let { it.substringBefore('.').toLongOrNull() }
    fun getDouble(name: String): Double? = getProperty(name)?.toDoubleOrNull()

    fun command(vararg args: String): Boolean =
        handle != 0L && nativeCommand(handle, arrayOf(*args))

    fun attach(surface: Surface, width: Int, height: Int): Boolean =
        handle != 0L && nativeAttach(handle, surface, width, height)

    /**
     * @param force true = 没有新帧也把上一帧重贴一次（窗口扰动后的重绘）；
     *              false = 只有 mpv 真出了新帧才画（默认路径，避免无意义 buffer 轮换）
     */
    fun render(width: Int, height: Int, force: Boolean = false): Boolean =
        handle != 0L && nativeRender(handle, width, height, force)

    fun detach() {
        if (handle != 0L) nativeDetach(handle)
    }

    fun destroy() {
        if (handle != 0L) {
            onFrameAvailable = null
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(listener: Any?): Long
    private external fun nativeInit(handle: Long): Boolean
    private external fun nativeEsVersion(handle: Long): Int
    private external fun nativeSetColorBits(handle: Long, bits: Int)
    private external fun nativeSetOption(handle: Long, name: String, value: String): Boolean
    private external fun nativeSetProperty(handle: Long, name: String, value: String): Boolean
    private external fun nativeGetProperty(handle: Long, name: String): String?
    private external fun nativeCommand(handle: Long, args: Array<String>): Boolean
    private external fun nativeAttach(handle: Long, surface: Surface, width: Int, height: Int): Boolean
    private external fun nativeRender(handle: Long, width: Int, height: Int, force: Boolean): Boolean
    private external fun nativeDetach(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        @Volatile
        var available: Boolean = false
            private set

        init {
            available = runCatching { System.loadLibrary("xhmpv"); true }.getOrDefault(false)
        }
    }
}
