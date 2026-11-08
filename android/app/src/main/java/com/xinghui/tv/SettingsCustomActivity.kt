package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 个性设置子页（排版照原版 SettingCustomActivity）。
 *
 * 原则：**每一行都必须有真实、可感知的作用**。我们只维持一套首页 UI，
 * 所以原版「首页风格（简洁/TVBox 两套）」这一项在我们这里没有意义，直接不放；
 * 同样，我们没有悬浮小窗、没有播放页信息栏、原版 getSearchUI() 本身也是零消费点，
 * 对应几项一并去掉，不留「未实现」占位。
 */
class SettingsCustomActivity : Activity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_custom)
        WallpaperStore.apply(this)
        settings = Settings(this)

        val store = HistoryStore(this)

        val SIZE = arrayOf("小", "中", "大", "特大")
        val FULLSCREEN_KEY = arrayOf("控制栏", "选集")
        val HOME_KEY = arrayOf(
            "选项弹窗", "切换站源", "切换线路", "直播页面", "历史页面",
            "搜索页面", "推送页面", "收藏页面", "设置页面"
        )
        // 0 选项弹窗 / 2 切换线路 / 6 推送页面 我们还没有对应实现，循环时跳过，
        // 免得用户选到空档后按菜单键没反应。
        val HOME_KEY_USABLE = intArrayOf(1, 3, 4, 5, 7, 8)

        // ---- 图片尺寸：首页列数（MainActivity.uiColumns / ProductColumn.of）----
        bindCycle(R.id.cusSize, R.id.valCusSize, SIZE,
            { settings.uiSize }, { settings.uiSize = it })

        // ---- 无痕：PlayActivity.recordHistory / setPosition 三处拦写 ----
        bindSwitch(R.id.cusIncognito, R.id.valCusIncognito,
            { settings.isIncognito }, { settings.isIncognito = it })

        // ---- 全屏菜单键：PlayActivity:1291 ----
        bindCycle(R.id.cusFullscreenKey, R.id.valCusFullscreenKey, FULLSCREEN_KEY,
            { settings.fullscreenMenuKey }, { settings.fullscreenMenuKey = it })

        // ---- 首页菜单键：MainActivity.dispatchKeyEvent 分发 ----
        bindCycle(R.id.cusHomeMenuKey, R.id.valCusHomeMenuKey, HOME_KEY,
            { settings.homeMenuKey }, { settings.homeMenuKey = it }, HOME_KEY_USABLE)

        // ---- 聚合搜索：SearchActivity，关时只请求当前站 ----
        bindSwitch(R.id.cusAggSearch, R.id.valCusAggSearch,
            { settings.aggregatedSearch }, { settings.aggregatedSearch = it })

        // ---- 嗅探内核：原版两档（系统 / X5 WebView），X5 是闭源 SDK 约 20MB，
        //      与「APK 32MB」红线冲突，只内置系统 WebView —— 单档只读，点击不变化 ----
        findViewById<TextView>(R.id.valCusParseWebview)?.text = "系统"
        findViewById<View>(R.id.cusParseWebview)?.setOnClickListener { /* 单档，无切换 */ }

        // ---- 缓存目录：写 thunder_cache_dir，Path.thunderCache() 派生迅雷下载目录 ----
        showCacheDir()
        findViewById<View>(R.id.cusCacheDir)?.setOnClickListener { pickCacheDir() }

        // ---- 首页历史：MainActivity:169 ----
        bindSwitch(R.id.cusHomeHistory, R.id.valCusHomeHistory,
            { settings.isHomeHistory }, { settings.isHomeHistory = it })

        // ---- 海报：DetailActivity:252 / VideoAdapter:79 ----
        bindSwitch(R.id.cusPoster, R.id.valCusPoster,
            { settings.showPoster }, { settings.showPoster = it })

        // ---- 历史 / 收藏：点击清空 ----
        findViewById<TextView>(R.id.valCusHistory)?.text = "${store.history().size} 条（点击清空）"
        findViewById<TextView>(R.id.valCusFav)?.text = "${store.favorites().size} 条（点击清空）"

        findViewById<View>(R.id.cusHistory)?.setOnClickListener {
            if (store.history().isEmpty()) {
                Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.clearHistory()
            findViewById<TextView>(R.id.valCusHistory)?.text = "0 条（点击清空）"
        }
        findViewById<View>(R.id.cusFav)?.setOnClickListener {
            if (store.favorites().isEmpty()) {
                Toast.makeText(this, "暂无收藏", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.clearFavorites()
            findViewById<TextView>(R.id.valCusFav)?.text = "0 条（点击清空）"
        }
    }

    /**
     * 点击循环切换。usable 为 null 表示全部档位可用，否则只在给定档位之间轮转
     * （首页菜单键用这个跳过我们还没有实现的 0/2/6 三档）。
     */
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

    // ---------------- 缓存目录 ----------------

    private fun thunderCacheDir(): String =
        runCatching { com.github.catvod.utils.Prefers.getString("thunder_cache_dir", "") }.getOrDefault("")

    /** 原版这一行直接回显 pref 原值（默认空串）；空串时显示实际生效目录，避免用户看不出落在哪 */
    private fun showCacheDir() {
        val saved = thunderCacheDir()
        findViewById<TextView>(R.id.valCusCacheDir)?.text =
            saved.ifBlank { applicationContext.cacheDir.absolutePath }
    }

    /** 原版 CacheDirDialog 的候选就是 getCacheDir() + 全部 getExternalCacheDirs() */
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
            .setTitle("缓存目录")
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
            "已切换到 ${dir.absolutePath}" + (if (free > 0) "（剩余 ${AppCache.format(free)}）" else "") +
                "\n新任务生效，已下载的文件不会自动搬移",
            Toast.LENGTH_LONG
        ).show()
    }
}
