package com.p2p

/**
 * 荐片（JianPian）P2P 引擎的 JNI 桥。
 *
 * ★类名与包名**必须**是 `com.p2p.P2PClass`，且 native 方法名必须与 so 里的符号一一对应：
 *     Java_com_p2p_P2PClass_doxstarthttpd
 *     Java_com_p2p_P2PClass_doxendhttpd
 *     Java_com_p2p_P2PClass_doxstart / doxadd / doxpause / doxdel
 * 所以这里用 Kotlin 写（本工程 kotlinc 不编译新增 .java），但 package/类名保持原样，
 * 编译出来的 .class 路径与 JNI 期望完全一致。
 *
 * 原版 P2PClass 的构造里调的是 `Path.jpa()`（spider.jar 内的 catvod 工具类）；
 * 我们没有那个类，改成由调用方传入目录。
 */
class P2PClass(dir: String) {

    /** 本地 http 服务端口（由 doxstarthttpd 返回） */
    @JvmField
    var port: Int = 0

    init {
        System.loadLibrary("jpa")
        port = doxstarthttpd(KEY, dir.toByteArray(Charsets.UTF_8))
    }

    fun startHttpd(): Int = port

    fun endHttpd() = doxendhttpd()

    fun start(path: ByteArray) = doxstart(path)

    fun add(path: ByteArray) = doxadd(path)

    fun pause(path: ByteArray) = doxpause(path)

    fun del(path: ByteArray) = doxdel(path)

    private external fun doxstarthttpd(key: ByteArray, dir: ByteArray): Int
    private external fun doxendhttpd(): Int
    private external fun doxstart(path: ByteArray): Int
    private external fun doxadd(path: ByteArray): Int
    private external fun doxpause(path: ByteArray): Int
    private external fun doxdel(path: ByteArray): Int

    private companion object {
        /** 原版硬编码的鉴权串（so 内部校验，改了服务起不来） */
        val KEY = "TEST3E63BAAECDAA79BEAA91853490A69F08".toByteArray(Charsets.UTF_8)
    }
}
