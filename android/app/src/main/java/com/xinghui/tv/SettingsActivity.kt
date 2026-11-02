package com.xinghui.tv

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置界面：TV 遥控器（方向键 + OK）可操作。
 * - 播放内核：内置 ExoPlayer / 外部 mpv（点击切换）
 * - mpv 播放器：显示是否已安装，未安装提示
 * - 关于：版本信息
 */
class SettingsActivity : AppCompatActivity() {

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
            settings.playerKernel =
                if (settings.playerKernel == Settings.KERNEL_MPV) Settings.KERNEL_EXO else Settings.KERNEL_MPV
            refresh()
        }

        // mpv 状态：未安装时点击提示去安装
        findViewById<View>(R.id.rowMpv).setOnClickListener {
            if (!PlayerManager.isMpvInstalled(this)) {
                android.widget.Toast.makeText(
                    this,
                    "请先安装 mpv-android（包名 is.xyz.mpv），再在“播放内核”选择 mpv",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                android.widget.Toast.makeText(this, "mpv 已安装，可在“播放内核”启用", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        refresh()
    }
}
//（注：内容由AI生成）
