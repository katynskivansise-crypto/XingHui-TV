package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView

/**
 * 播放核心子页（对齐原版 SettingPlayerActivity）。
 * 项与顺序取自原版 activity_setting_player.xml（EXO 变体）：
 * 播放核心 / 解码方式 / 渲染方式 / 缩放比例 / 字幕样式 / 线路播放 /
 * 隧道模式 / 连线方式 / 缓冲时间 / RTSP 通道 / User-Agent / 后台播放。
 *
 * 能真起作用的只有三项：播放核心（EXO/mpv 内核切换）、缩放比例、缓冲时间、User-Agent；
 * 其余按「只接入文字、点击不产生变化」处理，不假装能点。
 */
class SettingsPlayerActivity : Activity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_player)
        WallpaperStore.apply(this)
        settings = Settings(this)

        // 回显一律读真实设置值。之前这里好几个是硬编码的常量（decode 写死 1、caption 写死 0），
        // 点一下又没有任何点击绑定，看起来有那一行、实际点和显示都是假的。
        val DECODE = arrayOf("软解", "硬解")
        val CAPTION = arrayOf("预设", "系统")
        val FLAG = arrayOf("全自动", "手动")
        val ON_OFF = arrayOf("关闭", "开启")

        show(R.id.valPlyDecode, DECODE, settings.decodeMode)
        show(R.id.valPlyCaption, CAPTION, settings.captionMode)
        show(R.id.valPlyFlag, FLAG, settings.flagMode)
        show(R.id.valPlyTunnel, ON_OFF, settings.tunnelMode)
        refreshPlayCore()
        refreshScale()
        refreshBuffer()
        refreshUa()
        refreshProxy()
        refreshMpvParams()

        // 播放核心：EXO / mpv（mpv 已内嵌在壳里，不再走外部调用）
        findViewById<View>(R.id.plyCore)?.setOnClickListener {
            settings.playerKernel =
                if (settings.playerKernel == Settings.KERNEL_MPV) Settings.KERNEL_EXO
                else Settings.KERNEL_MPV
            refreshPlayCore()
            applyKernelGroup()
        }
        // 缩放比例：原版 5 档循环
        findViewById<View>(R.id.plyScale)?.setOnClickListener {
            settings.playerScale = if (settings.playerScale >= 4) 0 else settings.playerScale + 1
            refreshScale()
        }
        // 解码方式（原版 select_decode：Soft / Hard）
        findViewById<View>(R.id.plyDecode)?.setOnClickListener {
            settings.decodeMode = if (settings.decodeMode == 1) 0 else 1
            show(R.id.valPlyDecode, DECODE, settings.decodeMode)
        }

        // 隧道模式（原版 select 开/关 → ExoUtil.buildTrackSelector().setTunnelingEnabled）
        findViewById<View>(R.id.plyTunnel)?.setOnClickListener {
            settings.tunnelMode = if (settings.tunnelMode == 1) 0 else 1
            show(R.id.valPlyTunnel, ON_OFF, settings.tunnelMode)
        }

        // 字幕样式（原版 select_caption：Default / System → SubtitleView.setStyle）
        findViewById<View>(R.id.plyCaption)?.setOnClickListener {
            settings.captionMode = if (settings.captionMode == 1) 0 else 1
            show(R.id.valPlyCaption, CAPTION, settings.captionMode)
        }

        // 线路播放（原版 select_flag：Auto / Manual → 播完是否自动下一集）
        findViewById<View>(R.id.plyFlag)?.setOnClickListener {
            settings.flagMode = if (settings.flagMode == 1) 0 else 1
            show(R.id.valPlyFlag, FLAG, settings.flagMode)
        }

        // 缓冲时间：原版 exo_buffer 是 1~15 秒的滑条，这里做成固定档循环。
        // 之前这一行写的是 networkTimeoutSec（网络超时），语义不对：
        // 原版叫「缓冲」，它该管的是 ExoPlayer 起播前攒多少数据，现在真接上了。
        findViewById<View>(R.id.plyBuffer)?.setOnClickListener {
            val steps = arrayOf(1, 3, 5, 8, 10, 15)
            val cur = steps.indexOf(settings.exoBufferSec).let { if (it < 0) 2 else it }
            settings.exoBufferSec = steps[(cur + 1) % steps.size]
            refreshBuffer()
        }
        // 代理（原版中心页 Proxy 项，这里保留以免能力丢失）
        findViewById<View>(R.id.plyProxy)?.setOnClickListener { editProxy() }
        // User-Agent
        findViewById<View>(R.id.plyUa)?.setOnClickListener { editUa() }
    }

    private fun arrayOfList(): Array<String> = arrayOf("10 秒", "15 秒", "30 秒", "60 秒")

    private fun refreshPlayCore() {
        findViewById<TextView>(R.id.valPlyCore)?.text =
            if (settings.playerKernel == Settings.KERNEL_MPV) "mpv" else "EXO"
    }

    private fun refreshScale() {
        val s = arrayOf("原始", "16:9", "4:3", "填充", "裁剪")
        findViewById<TextView>(R.id.valPlyScale)?.text =
            s[settings.playerScale.coerceIn(0, s.size - 1)]
    }

    private fun refreshBuffer() {
        findViewById<TextView>(R.id.valPlyBuffer)?.text = "${settings.exoBufferSec} 秒"
    }

    private fun refreshUa() {
        val ua = settings.ua
        findViewById<TextView>(R.id.valPlyUa)?.text = if (ua.isBlank()) "默认" else ua
    }

    private fun editUa() {
        val input = android.widget.EditText(this).apply {
            setText(settings.ua)
            setHint("留空即恢复默认")
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("User-Agent")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.ua = input.text.toString().trim()
                refreshUa()
            }
            .setNegativeButton("取消", null)
            .show()
    }


    /**
     * 只显示当前内核那一组参数。
     * EXO 与 mpv 支持的东西不一样（EXO 是 RenderersFactory / resizeMode，mpv 是 hwdec / profile /
     * deband / interpolation / fastdecode / cache），混在一页里会让人以为某一项对两个内核都有效。
     */
    private fun applyKernelGroup() {
        val mpv = settings.playerKernel == Settings.KERNEL_MPV
        findViewById<View>(R.id.exoGroup)?.visibility = if (mpv) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mpvGroup)?.visibility = if (mpv) View.VISIBLE else View.GONE
    }

    /** mpv 侧全部参数：绑定点击 + 回显。每一项都来自官方 MPVView.initOptions() */
    private fun refreshMpvParams() {
        val DEBAND = arrayOf("关闭", "gradfun", "gpu")

        findViewById<TextView>(R.id.valPlyHwdec)?.text = if (settings.mpvHwdec) "开启" else "关闭"
        findViewById<TextView>(R.id.valPlyMpvFast)?.text =
            if (settings.mpvFastProfile) "开启" else "关闭"
        findViewById<TextView>(R.id.valPlyDeband)?.text = DEBAND[settings.mpvDeband.coerceIn(0, 2)]
        findViewById<TextView>(R.id.valPlyInterp)?.text =
            if (settings.mpvInterpolation) "开启" else "关闭"
        findViewById<TextView>(R.id.valPlyFastDecode)?.text =
            if (settings.mpvFastDecode) "开启" else "关闭"
        findViewById<TextView>(R.id.valPlyCache)?.text = "${settings.mpvCacheMb} MB"

        // 硬件解码（官方 key hardware_decoding：hwdec=mediacodec / no）
        findViewById<View>(R.id.plyHwdec)?.setOnClickListener {
            settings.mpvHwdec = !settings.mpvHwdec
            findViewById<TextView>(R.id.valPlyHwdec)?.text =
                if (settings.mpvHwdec) "开启" else "关闭"
        }
        // 快速档（官方 profile=fast）
        findViewById<View>(R.id.plyMpvFast)?.setOnClickListener {
            settings.mpvFastProfile = !settings.mpvFastProfile
            findViewById<TextView>(R.id.valPlyMpvFast)?.text =
                if (settings.mpvFastProfile) "开启" else "关闭"
        }
        // 去色带（官方 key video_debanding：关闭 / gradfun / gpu）
        findViewById<View>(R.id.plyDeband)?.setOnClickListener {
            settings.mpvDeband = if (settings.mpvDeband >= 2) 0 else settings.mpvDeband + 1
            findViewById<TextView>(R.id.valPlyDeband)?.text =
                DEBAND[settings.mpvDeband.coerceIn(0, 2)]
        }
        // 插帧（官方 key video_interpolation）
        findViewById<View>(R.id.plyInterp)?.setOnClickListener {
            settings.mpvInterpolation = !settings.mpvInterpolation
            findViewById<TextView>(R.id.valPlyInterp)?.text =
                if (settings.mpvInterpolation) "开启" else "关闭"
        }
        // 快速解码（官方 key video_fastdecode）
        findViewById<View>(R.id.plyFastDecode)?.setOnClickListener {
            settings.mpvFastDecode = !settings.mpvFastDecode
            findViewById<TextView>(R.id.valPlyFastDecode)?.text =
                if (settings.mpvFastDecode) "开启" else "关闭"
        }
        // 缓存（官方按 SDK 给 64 / 32）
        findViewById<View>(R.id.plyCache)?.setOnClickListener {
            settings.mpvCacheMb = if (settings.mpvCacheMb >= 64) 32 else 64
            findViewById<TextView>(R.id.valPlyCache)?.text = "${settings.mpvCacheMb} MB"
        }
    }

    private fun refreshProxy() {
        findViewById<TextView>(R.id.valPlyProxy)?.text =
            if (settings.proxy.isBlank()) "关闭" else "已启用"
    }

    private fun editProxy() {
        val input = android.widget.EditText(this).apply {
            setText(settings.proxy)
            setSingleLine()
            setHint("http://user:pass@host:port")
        }
        AlertDialog.Builder(this)
            .setTitle("代理")
            .setMessage("留空保存即关闭代理")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.proxy = input.text.toString().trim()
                refreshProxy()
                android.widget.Toast.makeText(
                    this,
                    if (settings.proxy.isBlank()) "代理已关闭" else "代理已启用",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun show(id: Int, options: Array<String>, index: Int) {
        findViewById<TextView>(id)?.text = options[index.coerceIn(0, options.size - 1)]
    }
}
