package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * 个性设置子页 —— 条目逐项对齐原版 `res/layout/activity_setting_custom.xml`（19 项）。
 *
 * 已经按原版证据落定的取值域（`res/values-zh-rCN/arrays.xml`）：
 *   语言       select_language               英文 / 简体中文 / 繁体中文
 *   图片尺寸   select_size                   小 / 中 / 大 / 特大
 *   选集样式   select_episode                单行 / 多行
 *   播放倍速   exo_controls_playback_speeds  0.25~2 共 7 档
 *   首页UI     select_home_ui                简洁风格 / TVBox风格
 *   搜索UI     select_search_ui              横向 / 纵向
 *   全屏菜单键 select_fullscreen_menu_key    控制栏 / 选集
 *   小窗返回键 select_small_window_back_key  返回 / 焦点移动
 * 其余为二值开关；「长按倍速」按 5.0.8 的 setOnLongClickListener 回写 R(1.0f)/R(3.0f) 定为两档。
 *
 * 两项按用户裁决不恢复：
 *   - 首页菜单键（原来那一格已删，MainActivity 里固定走「设置页面」）
 *   - DNS/DoH（固定走系统 DNS，不做选择）
 */
class SettingsCustomActivity : Activity() {

    private lateinit var settings: Settings

    private val SIZE = arrayOf("小", "中", "大", "特大")
    private val EPISODE = arrayOf("单行", "多行")
    private val HOME_UI = arrayOf("简洁风格", "TVBox风格")
    private val SEARCH_UI = arrayOf("横向", "纵向")
    private val FULLSCREEN_KEY = arrayOf("控制栏", "选集")
    private val SMALL_BACK = arrayOf("返回", "焦点移动")
    private val PRESS_SPEED = arrayOf("1.0 倍", "3.0 倍")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_custom)
        WallpaperStore.apply(this)
        settings = Settings(this)

        // ---- 语言：切换后立刻重建当前页（系统 Locale，影响 DateFormat / 系统控件）----
        bindCycle(R.id.cusLanguage, R.id.valCusLanguage, Settings.LANGUAGE_LABELS,
            { settings.language }, { settings.language = it; applyLanguage(it) })

        // ---- 图片尺寸：首页列数（MainActivity.uiColumns）----
        bindCycle(R.id.cusSize, R.id.valCusSize, SIZE,
            { settings.uiSize }, { settings.uiSize = it })

        // ---- 选集样式：详情页集数网格单行/多行 ----
        bindCycle(R.id.cusEpisode, R.id.valCusEpisode, EPISODE,
            { settings.episodeStyle }, { settings.episodeStyle = it })

        // ---- 无痕模式：不写播放历史/进度 ----
        bindSwitch(R.id.cusIncognito, R.id.valCusIncognito,
            { settings.isIncognito }, { settings.isIncognito = it })

        // ---- 播放倍速默认值（7 档）----
        bindCycle(R.id.cusPlaySpeed, R.id.valCusPlaySpeed, Settings.PLAY_SPEED_LABELS,
            { settings.playSpeed }, { settings.playSpeed = it })

        // ---- 长按倍速：1.0 / 3.0 两档 ----
        bindCycle(R.id.cusPressSpeed, R.id.valCusPressSpeed, PRESS_SPEED,
            { if (settings.pressSpeed >= 3.0f) 1 else 0 },
            { settings.pressSpeed = if (it == 1) 3.0f else 1.0f })

        // ---- 首页UI / 搜索UI ----
        bindCycle(R.id.cusHomeUi, R.id.valCusHomeUi, HOME_UI,
            { settings.homeUi }, { settings.homeUi = it })
        bindCycle(R.id.cusSearchUi, R.id.valCusSearchUi, SEARCH_UI,
            { settings.searchUi }, { settings.searchUi = it })

        // ---- 屏显：播放页常驻信息层（clock / netspeed / duration / 1dp 进度条）----
        bindSwitch(R.id.cusDisplay, R.id.valCusDisplay,
            { settings.display == 1 }, { settings.display = if (it) 1 else 0 })

        // ---- 最近观看：首页推荐位用播放历史还是站点推荐 ----
        bindSwitch(R.id.cusHomeHistory, R.id.valCusHomeHistory,
            { settings.isHomeHistory }, { settings.isHomeHistory = it })

        // ---- 全屏菜单键 ----
        bindCycle(R.id.cusFullscreenKey, R.id.valCusFullscreenKey, FULLSCREEN_KEY,
            { settings.fullscreenMenuKey }, { settings.fullscreenMenuKey = it })

        // ---- 首页源锁定 ----
        bindSwitch(R.id.cusHomeSiteLock, R.id.valCusHomeSiteLock,
            { settings.homeSiteLock == 1 }, { settings.homeSiteLock = if (it) 1 else 0 })

        // ---- 小窗返回键 ----
        bindCycle(R.id.cusSmallWindowBackKey, R.id.valCusSmallWindowBackKey, SMALL_BACK,
            { settings.smallWindowBackKey }, { settings.smallWindowBackKey = it })

        // ---- 首页按钮组显隐 ----
        bindSwitch(R.id.cusHomeButtons, R.id.valCusHomeButtons,
            { settings.homeButtons == 1 }, { settings.homeButtons = if (it) 1 else 0 })

        // ---- 聚合搜索 ----
        bindSwitch(R.id.cusAggSearch, R.id.valCusAggSearch,
            { settings.aggregatedSearch }, { settings.aggregatedSearch = it })

        // ---- AI 去广告（原版默认开启）----
        bindSwitch(R.id.cusRemoveAd, R.id.valCusRemoveAd,
            { settings.removeAd == 1 }, { settings.removeAd = if (it) 1 else 0 })

        // ---- 重置 App ----
        findViewById<View>(R.id.cusReset)?.setOnClickListener { confirmReset() }

        // ---- 嗅探内核：原版两档（系统 / X5 WebView），X5 是闭源 SDK 约 20MB，只内置系统 WebView ----
        findViewById<TextView>(R.id.valCusParseWebview)?.text = "系统"
        findViewById<View>(R.id.cusParseWebview)?.setOnClickListener { /* 单档，无切换 */ }

        // ---- 迅雷缓存目录 ----
        showCacheDir()
        findViewById<View>(R.id.cusCacheDir)?.setOnClickListener { pickCacheDir() }
    }

    private fun bindCycle(
        rowId: Int, valId: Int, options: Array<String>,
        get: () -> Int, set: (Int) -> Unit,
        usable: IntArray? = null
    ) {
        val tv = findViewById<TextView>(valId)
        fun render() {
            val i = get().coerceIn(0, options.size - 1)
            tv?.text = options[i]
        }
        render()
        findViewById<View>(rowId)?.setOnClickListener {
            val cur = get()
            val next = if (usable == null) {
                (cur + 1) % options.size
            } else {
                val idx = usable.indexOf(cur).let { if (it < 0) 0 else it }
                usable[(idx + 1) % usable.size]
            }
            set(next)
            render()
        }
    }

    private fun bindSwitch(rowId: Int, valId: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val tv = findViewById<TextView>(valId)
        fun render() { tv?.text = if (get()) "开启" else "关闭" }
        render()
        findViewById<View>(rowId)?.setOnClickListener {
            set(!get())
            render()
        }
    }

    // ---------------- 语言 ----------------

    private fun applyLanguage(idx: Int) {
        val loc = when (idx) {
            0 -> Locale.ENGLISH
            2 -> Locale.TRADITIONAL_CHINESE
            else -> Locale.SIMPLIFIED_CHINESE
        }
        Locale.setDefault(loc)
        val cfg = Configuration(resources.configuration)
        cfg.setLocale(loc)
        resources.updateConfiguration(cfg, resources.displayMetrics)
        recreate()
    }

    // ---------------- 重置 ----------------

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("重置 App")
            .setItems(arrayOf("重置设置（保留数据）", "重置并清空全部数据")) { _, which ->
                if (which == 0) {
                    getSharedPreferences("xinghui_prefs", MODE_PRIVATE).edit().clear().apply()
                    Toast.makeText(this, "设置已重置，请重新进入 App", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    runCatching { File(filesDir.parentFile, "").deleteRecursively() }
                    getSharedPreferences("xinghui_prefs", MODE_PRIVATE).edit().clear().apply()
                    runCatching { cacheDir.deleteRecursively() }
                    Toast.makeText(this, "已清空全部数据，请重新进入 App", Toast.LENGTH_LONG).show()
                    finishAffinity()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 迅雷缓存目录 ----------------

    private fun thunderCacheDir(): String =
        runCatching { com.github.catvod.utils.Prefers.getString("thunder_cache_dir", "") }.getOrDefault("")

    private fun showCacheDir() {
        val saved = thunderCacheDir()
        findViewById<TextView>(R.id.valCusCacheDir)?.text =
            saved.ifBlank { applicationContext.cacheDir.absolutePath }
    }

    private fun cacheCandidates(): List<File> {
        val out = mutableListOf<File>()
        out += applicationContext.cacheDir
        runCatching { applicationContext.externalCacheDirs?.filterNotNull()?.forEach { out += it } }
        return out.distinctBy { it.absolutePath }
    }

    private fun pickCacheDir() {
        runCatching { com.github.catvod.Init.set(applicationContext) }
        val dirs = cacheCandidates()
        val labels = dirs.map { d ->
            val free = runCatching { d.usableSpace }.getOrDefault(0L)
            d.absolutePath + (if (free > 0) "（剩余 ${AppCache.format(free)}）" else "")
        }
        AlertDialog.Builder(this)
            .setTitle("迅雷缓存目录")
            .setItems(labels.toTypedArray()) { _, which -> applyCacheDir(dirs[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyCacheDir(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "该目录不可写，已保留原设置", Toast.LENGTH_SHORT).show()
            return
        }
        com.github.catvod.utils.Prefers.put("thunder_cache_dir", dir.absolutePath)
        showCacheDir()
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)
        Toast.makeText(
            this,
            "已切换到 " + dir.absolutePath + (if (free > 0) "（剩余 ${AppCache.format(free)}）" else "") +
                "\n新任务生效，已下载的文件不会自动搬移",
            Toast.LENGTH_LONG
        ).show()
    }
}
