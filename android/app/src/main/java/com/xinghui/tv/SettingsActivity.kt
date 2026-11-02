package com.xinghui.tv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * 设置界面 —— 兼容旧安卓 TV 盒子（斐讯T1 等）：
 * 1) 用原生 Activity 而非 AppCompatActivity，彻底避开 AppCompat 主题/控件兼容问题；
 * 2) 布局只用 API 21 之前的原生控件与属性；
 * 3) 所有交互包 try/catch，异常不崩溃，只弹提示。
 *
 * - 播放内核：内置 ExoPlayer / 外部 mpv（点击切换）
 * - mpv 播放器：显示是否已安装，未安装提示
 * - 关于：版本信息
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = Settings(this)

        val tvKernel = findViewById<TextView>(R.id.valKernel)
        val tvMpv = findViewById<TextView>(R.id.valMpv)
        val tvAbout = findViewById<TextView>(R.id.valAbout)

        // 刷新各行的当前值
        fun refresh() {
            tvKernel.text = settings.kernelLabel()
            tvMpv.text = if (PlayerManager.isMpvInstalled(this)) "已安装（is.xyz.mpv）" else "未安装"
            tvAbout.text = "星汇TV v0.1.0（debug）"
        }

        // 播放内核：点击在 ExoPlayer <-> mpv 之间切换
        findViewById<View>(R.id.rowKernel).setOnClickListener {
            try {
                settings.playerKernel =
                    if (settings.playerKernel == Settings.KERNEL_MPV) Settings.KERNEL_EXO else Settings.KERNEL_MPV
                refresh()
            } catch (e: Exception) {
                Toast.makeText(this, "切换内核失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // mpv 状态：未安装时点击提示去安装
        findViewById<View>(R.id.rowMpv).setOnClickListener {
            if (!PlayerManager.isMpvInstalled(this)) {
                Toast.makeText(
                    this,
                    "请先安装 mpv-android（包名 is.xyz.mpv），再在“播放内核”选择 mpv",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "mpv 已安装，可在“播放内核”启用", Toast.LENGTH_SHORT).show()
            }
        }

        try {
            refresh()
        } catch (_: Exception) {
            // 布局极端异常也不崩溃
        }
    }
}
