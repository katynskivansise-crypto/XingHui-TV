package com.xinghui.tv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView

/**
 * 个性设置子页（对齐原版 SettingCustomActivity）。
 * 当前只做呈现：能读的设置项显示真实值，没接线的显示原版默认枚举文字，点击不改变状态。
 */
class SettingsCustomActivity : Activity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_custom)
        WallpaperStore.apply(this)
        settings = Settings(this)

        val store = HistoryStore(this)

        val EPISODE = arrayOf("单行", "多行")
        val SIZE = arrayOf("小", "中", "大", "特大")
        val ON_OFF = arrayOf("关闭", "开启")
        val HOME_UI = arrayOf("简洁风格", "TVBox风格")
        val SEARCH_UI = arrayOf("纵向", "横向")
        val FULLSCREEN_KEY = arrayOf("控制栏", "选集")
        val SMALL_BACK = arrayOf("返回", "焦点移动")
        val HOME_KEY = arrayOf(
            "选项弹窗", "切换站源", "切换线路", "直播页面", "历史页面",
            "搜索页面", "推送页面", "收藏页面", "设置页面"
        )

        show(R.id.valCusEpisode, EPISODE, settings.episodeStyle)
        show(R.id.valCusSize, SIZE, settings.uiSize)
        show(R.id.valCusIncognito, ON_OFF, 0)
        show(R.id.valCusHomeUI, HOME_UI, 0)
        show(R.id.valCusSearchUI, SEARCH_UI, 0)
        show(R.id.valCusScreen, ON_OFF, 0)
        show(R.id.valCusFullscreenKey, FULLSCREEN_KEY, settings.fullscreenMenuKey)
        show(R.id.valCusSmallBack, SMALL_BACK, settings.smallWindowBackKey)
        show(R.id.valCusHomeMenuKey, HOME_KEY, settings.homeMenuKey)
        show(R.id.valCusAggSearch, ON_OFF, 0)
        show(R.id.valCusHomeHistory, ON_OFF, if (settings.isHomeHistory) 1 else 0)
        show(R.id.valCusPoster, ON_OFF, if (settings.showPoster) 1 else 0)

        findViewById<TextView>(R.id.valCusHistory)?.text = "${store.history().size} 条（点击清空）"
        findViewById<TextView>(R.id.valCusFav)?.text = "${store.favorites().size} 条（点击清空）"

        findViewById<View>(R.id.cusHistory)?.setOnClickListener {
            val n = store.history().size
            if (n == 0) {
                android.widget.Toast.makeText(this, "暂无历史记录", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.clearHistory()
            findViewById<TextView>(R.id.valCusHistory)?.text = "0 条（点击清空）"
        }
        findViewById<View>(R.id.cusFav)?.setOnClickListener {
            val n = store.favorites().size
            if (n == 0) {
                android.widget.Toast.makeText(this, "暂无收藏", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.clearFavorites()
            findViewById<TextView>(R.id.valCusFav)?.text = "0 条（点击清空）"
        }
    }

    private fun show(id: Int, options: Array<String>, index: Int) {
        findViewById<TextView>(id)?.text = options[index.coerceIn(0, options.size - 1)]
    }
}
