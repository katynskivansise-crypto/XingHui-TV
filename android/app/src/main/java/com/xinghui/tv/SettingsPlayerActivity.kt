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

        val CORE = arrayOf("EXO", "IJK")
        val DECODE = arrayOf("软解", "硬解")
        val RENDER = arrayOf("Surface", "Texture")
        val SCALE = arrayOf("原始", "16:9", "4:3", "填充", "裁剪")
        val CAPTION = arrayOf("预设", "描边", "无")
        val FLAG = arrayOf("全自动", "手动")
        val ON_OFF = arrayOf("关闭", "开启")
        val HTTP = arrayOf("自动", "HTTP/1.1", "HTTP/2")
        val BUFFER = arrayOfList()
        val RTSP = arrayOf("关闭", "开启")

        show(R.id.valPlyDecode, DECODE, 1)
        show(R.id.valPlyRender, RENDER, settings.renderMode)
        show(R.id.valPlyCaption, CAPTION, 0)
        show(R.id.valPlyFlag, FLAG, 0)
        show(R.id.valPlyTunnel, ON_OFF, settings.tunnelMode)
        show(R.id.valPlyHttp, HTTP, 0)
        show(R.id.valPlyRtsp, ON_OFF, 0)
        show(R.id.valPlyBackground, ON_OFF, settings.backgroundPlay)
        refreshPlayCore()
        refreshScale()
        refreshBuffer()
        refreshUa()
        refreshProxy()

        // 播放核心：EXO / IJK（IJK 无内核可用，切换只改记录值）
        findViewById<View>(R.id.plyCore)?.setOnClickListener {
            settings.playCore = if (settings.playCore >= 1) 0 else 1
            refreshPlayCore()
        }
        // 缩放比例：原版 5 档循环
        findViewById<View>(R.id.plyScale)?.setOnClickListener {
            settings.playerScale = if (settings.playerScale >= 4) 0 else settings.playerScale + 1
            refreshScale()
        }
        // 缓冲时间
        findViewById<View>(R.id.plyBuffer)?.setOnClickListener {
            settings.networkTimeoutSec = when (settings.networkTimeoutSec) {
                10 -> 15
                15 -> 30
                30 -> 60
                else -> 10
            }
            refreshBuffer()
        }
        // 代理（原版中心页 Proxy 项，这里保留以免能力丢失）
        findViewById<View>(R.id.plyProxy)?.setOnClickListener { editProxy() }
        // User-Agent
        findViewById<View>(R.id.plyUa)?.setOnClickListener { editUa() }
    }

    private fun arrayOfList(): Array<String> = arrayOf("10 秒", "15 秒", "30 秒", "60 秒")

    private fun refreshPlayCore() {
        findViewById<TextView>(R.id.valPlyCore)?.text = if (settings.playCore >= 1) "IJK" else "EXO"
    }

    private fun refreshScale() {
        val s = arrayOf("原始", "16:9", "4:3", "填充", "裁剪")
        findViewById<TextView>(R.id.valPlyScale)?.text =
            s[settings.playerScale.coerceIn(0, s.size - 1)]
    }

    private fun refreshBuffer() {
        findViewById<TextView>(R.id.valPlyBuffer)?.text = "${settings.networkTimeoutSec} 秒"
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
