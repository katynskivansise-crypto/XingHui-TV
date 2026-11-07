package com.xinghui.tv

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 弹幕设置子页（对齐原版 SettingDanmuActivity）。
 * 项与顺序取自原版 activity_setting_danmu.xml：
 * 弹幕加载 / 弹幕速度 / 弹幕大小 / 弹幕行数 / 弹幕透明。
 * 本项目目前没有弹幕渲染实现，因此这一页只呈现文字，点击不产生变化。
 */
class SettingsDanmuActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_danmu)
        WallpaperStore.apply(this)

        val s = Settings(this)
        val ON_OFF = arrayOf("关闭", "开启")
        findViewById<TextView>(R.id.valDnmLoad)?.text = ON_OFF[0]
        findViewById<TextView>(R.id.valDnmSpeed)?.text = "1.0 倍"
        findViewById<TextView>(R.id.valDnmSize)?.text = "中"
        findViewById<TextView>(R.id.valDnmLine)?.text = "6 行"
        findViewById<TextView>(R.id.valDnmAlpha)?.text = "80%"
    }
}
