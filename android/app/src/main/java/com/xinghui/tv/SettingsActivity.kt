package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 设置界面（分组式）：播放内核 / 网络超时 / 界面 / 资料库 / 备份恢复 / 关于。
 * 原生 Activity，兼容旧 TV 盒子；点击行切换值或执行操作。
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var store: HistoryStore
    private lateinit var tvKernel: TextView
    private lateinit var tvTimeout: TextView
    private lateinit var tvUa: TextView
    private lateinit var tvProxy: TextView
    private lateinit var tvEpg: TextView
    private lateinit var tvLive: TextView
    private lateinit var tvPoster: TextView
    private lateinit var tvHomeHistory: TextView
    private lateinit var tvHistory: TextView
    private lateinit var tvFav: TextView
    private lateinit var tvBackup: TextView
    private lateinit var tvRestore: TextView
    private lateinit var tvAbout: TextView
    private lateinit var tvWallpaper: TextView
    private lateinit var tvSize: TextView
    private lateinit var tvScale: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // 对齐原版 BaseActivity.refreshWall：全站 window 层壁纸，布局透明透出来
        runCatching { WallpaperStore.apply(this) }

        settings = Settings(this)
        store = HistoryStore(this)

        tvKernel = findViewById<TextView>(R.id.valKernel)
        tvTimeout = findViewById<TextView>(R.id.valTimeout)
        tvUa = findViewById<TextView>(R.id.valUa)
        tvProxy = findViewById<TextView>(R.id.valProxy)
        tvEpg = findViewById<TextView>(R.id.valEpg)
        tvLive = findViewById<TextView>(R.id.valLive)
        tvPoster = findViewById<TextView>(R.id.valPoster)
        tvHomeHistory = findViewById<TextView>(R.id.valHomeHistory)
        tvHistory = findViewById<TextView>(R.id.valHistory)
        tvFav = findViewById<TextView>(R.id.valFav)
        tvBackup = findViewById<TextView>(R.id.valBackup)
        tvRestore = findViewById<TextView>(R.id.valRestore)
        tvAbout = findViewById<TextView>(R.id.valAbout)
        tvWallpaper = findViewById<TextView>(R.id.valWallpaper)
        tvSize = findViewById<TextView>(R.id.valSize)
        tvScale = findViewById<TextView>(R.id.valVideoScale)

        refresh()

        // 播放内核：ExoPlayer <-> mpv 切换
        findViewById<View>(R.id.rowKernel).setOnClickListener {
            settings.playerKernel =
                if (settings.playerKernel == Settings.KERNEL_MPV) Settings.KERNEL_EXO else Settings.KERNEL_MPV
            refresh()
        }

        // 网络超时：10/15/30 秒循环
        findViewById<View>(R.id.rowTimeout).setOnClickListener {
            settings.networkTimeoutSec = when (settings.networkTimeoutSec) {
                10 -> 15
                15 -> 30
                else -> 10
            }
            refresh()
        }

        // User-Agent：预设档位循环（默认 → TV → 手机 → iPhone → PC → 默认），对齐原项目设置 UA
        findViewById<View>(R.id.rowUa).setOnClickListener {
            val order = listOf("", Settings.UA_TV, Settings.UA_PHONE, Settings.UA_IOS, Settings.UA_PC)
            val next = order.indexOf(settings.ua).let { if (it < 0 || it >= order.size - 1) 0 else it + 1 }
            settings.ua = order[next]
            refresh()
            Toast.makeText(this, "UA：${settings.uaLabel()}", Toast.LENGTH_SHORT).show()
        }

        // 代理：弹出输入框设置/清除代理地址（对齐原项目 ProxyDialog）
        findViewById<View>(R.id.rowProxy).setOnClickListener { showProxyDialog() }

        // 直播 EPG 源：弹出输入框配置 XMLTV 地址（留空清除）
        findViewById<View>(R.id.rowEpg).setOnClickListener { showEpgDialog() }

        // 直播源：弹出输入框配置 diyp/txt/m3u 地址（留空清除）
        findViewById<View>(R.id.rowLive).setOnClickListener { showLiveDialog() }

        // 显示海报开关
        findViewById<View>(R.id.rowPoster).setOnClickListener {
            settings.showPoster = !settings.showPoster
            refresh()
        }

        // 首页显示历史横排开关（对齐原项目 SettingCustom 的 homeHistory）
        findViewById<View>(R.id.rowHomeHistory).setOnClickListener {
            settings.isHomeHistory = !settings.isHomeHistory
            refresh()
        }

        // 历史记录：查看/清空
        findViewById<View>(R.id.rowHistory).setOnClickListener {
            val n = store.history().size
            if (n == 0) {
                Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.clearHistory()
            refresh()
            Toast.makeText(this, "已清空 $n 条历史", Toast.LENGTH_SHORT).show()
        }

        // 收藏：查看/清空
        findViewById<View>(R.id.rowFav).setOnClickListener {
            val n = store.favorites().size
            if (n == 0) {
                Toast.makeText(this, "暂无收藏", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.clearFavorites()
            refresh()
            Toast.makeText(this, "已清空 $n 条收藏", Toast.LENGTH_SHORT).show()
        }

        // 备份：把 SharedPreferences 目录打包导出（对齐原项目 BackupDialog 导出配置）
        // 原版这三行右侧的图标按钮：壁纸 home=恢复默认，refresh=重新应用当前壁纸
        findViewById<View>(R.id.rowWallpaperHome)?.setOnClickListener {
            WallpaperStore.clear(this)   // clear 内部已含 apply
            refresh()
        }
        findViewById<View>(R.id.rowWallpaperRefresh)?.setOnClickListener {
            WallpaperStore.apply(this)
            refresh()
        }
        // 直播两枚（home/history）当前无对应数据源，按「只接入文字、点击不产生变化」处理
        findViewById<View>(R.id.rowLiveHome)?.setOnClickListener { }
        findViewById<View>(R.id.rowLiveHistory)?.setOnClickListener { }

        findViewById<View>(R.id.rowWallpaper).setOnClickListener { showWallpaperDialog() }
        findViewById<View>(R.id.rowSize).setOnClickListener {
            settings.uiSize = if (settings.uiSize >= 3) 0 else settings.uiSize + 1
            refresh()
        }
        findViewById<View>(R.id.rowVideoScale).setOnClickListener {
            settings.playerScale = if (settings.playerScale >= 4) 0 else settings.playerScale + 1
            refresh()
        }

        // ==== 操作（对齐原版 SettingCustomActivity 的取值枚举，仅展示）====
        bindDisplay(R.id.rowEpisodeStyle, EPISODE_STYLE) { Settings(this).episodeStyle }
        bindDisplay(R.id.rowHomeMenuKey, HOME_MENU_KEY) { Settings(this).homeMenuKey }
        bindDisplay(R.id.rowFullscreenMenuKey, FULLSCREEN_MENU_KEY) { Settings(this).fullscreenMenuKey }
        bindDisplay(R.id.rowSmallWindowBackKey, SMALL_WINDOW_BACK_KEY) { Settings(this).smallWindowBackKey }

        // ==== 加速与嗅探：功能未接入，只呈现状态文字 ====
        findViewById<TextView>(R.id.valThunder)?.text = "未接入"
        findViewById<TextView>(R.id.valSniff)?.text = "未接入"
        findViewById<TextView>(R.id.valThunderCache)?.text = "未接入"

        // ==== 播放参数（对齐原版 SettingPlayerActivity，按实际能力裁剪）====
        bindDisplay(R.id.rowDecodeMode, DECODE_MODE) { Settings(this).decodeMode }
        bindDisplay(R.id.rowRenderMode, RENDER_MODE) { Settings(this).renderMode }
        bindDisplay(R.id.rowTunnelMode, TUNNEL_MODE) { Settings(this).tunnelMode }
        bindDisplay(R.id.rowBackgroundPlay, BACKGROUND_PLAY) { Settings(this).backgroundPlay }

        findViewById<View>(R.id.rowBackup).setOnClickListener { doBackup() }

        // 恢复：从备份 zip 还原配置
        findViewById<View>(R.id.rowRestore).setOnClickListener { doRestore() }

        refresh()
    }

    /** 代理设置对话框（对齐原项目 ProxyDialog）：输入地址保存；留空保存即关闭代理 */
    /** 壁纸：本地选图 / 图片地址 / 恢复默认（对齐原版设置页的壁纸项，来源扩展为可自选） */

    // ==== 设置项枚举（文案/顺序照原版 select_* 数组）====
    private val EPISODE_STYLE = arrayOf("单行", "多行")
    private val HOME_MENU_KEY = arrayOf(
        "选项弹窗", "切换站源", "切换线路", "直播页面", "历史页面",
        "搜索页面", "推送页面", "收藏页面", "设置页面"
    )
    private val FULLSCREEN_MENU_KEY = arrayOf("控制栏", "选集")
    private val SMALL_WINDOW_BACK_KEY = arrayOf("返回", "焦点移动")
    // 本壳只走 MediaCodec 硬解；软解需要 FFmpeg 扩展，尚未接入
    private val DECODE_MODE = arrayOf("硬解")
    private val RENDER_MODE = arrayOf("Surface", "Texture")
    private val TUNNEL_MODE = arrayOf("关闭", "开启")
    private val BACKGROUND_PLAY = arrayOf("关闭", "开启")

    /**
     * 只显示当前值、不响应点击：这些设置项目前还没有行为接线，
     * 按用户要求「没覆盖到的功能只接入选项或文字，点击不产生变化」。
     */
    private fun bindDisplay(rowId: Int, options: Array<String>, get: () -> Int) {
        val row = findViewById<View>(rowId) ?: return
        val valView = findViewById<android.widget.TextView>(
            resources.getIdentifier("val" + resources.getResourceEntryName(rowId).removePrefix("row"),
                "id", packageName)
        )
        fun show() {
            val i = get().coerceIn(0, options.size - 1)
            valView?.text = options[i]
        }
        show()
        // 当前版本这些项只呈现选项文字，点击不做改动（功能接线另轮推进）
    }

    private fun showWallpaperDialog() {
        AlertDialog.Builder(this)
            .setTitle("壁纸")
            .setItems(arrayOf("选择本地图片", "输入图片地址", "恢复默认（深色渐变）")) { _, which ->
                when (which) {
                    0 -> pickWallpaper()
                    1 -> showWallpaperUrlDialog()
                    2 -> {
                        WallpaperStore.clear(this)
                        refresh()
                        Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun pickWallpaper() {
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_WALLPAPER)
        }.onFailure {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_LONG).show()
        }
    }

    private fun showWallpaperUrlDialog() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setHint("https://example.com/wall.jpg")
        input.requestFocus()
        AlertDialog.Builder(this)
            .setTitle("壁纸图片地址")
            .setMessage("支持 jpg/png 直链，图片会自动裁成屏幕比例")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                Toast.makeText(this, "正在下载壁纸…", Toast.LENGTH_SHORT).show()
                WallpaperStore.importFromUrl(this, url) { _, msg ->
                    refresh()
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_WALLPAPER || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        // 持久化读权限，重启后仍能读（ACTION_OPEN_DOCUMENT 支持）
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        WallpaperStore.importFromUri(this, uri) { _, msg ->
            refresh()
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProxyDialog() {
        val input = EditText(this)
        input.setText(settings.proxy)
        input.setSingleLine(true)
        input.setHint("http://user:pass@host:port")
        input.requestFocus()
        AlertDialog.Builder(this)
            .setTitle("代理地址")
            .setMessage("留空保存即关闭代理；地址如 http://user:pass@127.0.0.1:7890")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.proxy = input.text.toString().trim()
                refresh()
                Toast.makeText(
                    this,
                    if (settings.proxy.isBlank()) "代理已关闭" else "代理已启用",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 直播 EPG 源设置对话框：输入 XMLTV 地址保存；留空保存即清除 */
    private fun showEpgDialog() {
        val input = EditText(this)
        input.setText(settings.epgUrl)
        input.setSingleLine(true)
        input.setHint("https://xxx/epg.xml")
        input.requestFocus()
        AlertDialog.Builder(this)
            .setTitle("直播 EPG 源")
            .setMessage("填 XMLTV 节目单地址（如 diyp EPG 接口）；留空清除。配置后直播页可查节目单")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.epgUrl = input.text.toString().trim()
                refresh()
                Toast.makeText(
                    this,
                    if (settings.epgUrl.isBlank()) "EPG 源已清除" else "EPG 源已配置",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 直播源设置对话框：输入 diyp/txt/m3u 地址保存；留空保存即清除 */
    private fun showLiveDialog() {
        val input = EditText(this)
        input.setText(settings.liveUrl)
        input.setSingleLine(true)
        input.setHint("https://xxx/live.txt")
        input.requestFocus()
        AlertDialog.Builder(this)
            .setTitle("直播源")
            .setMessage("填直播源地址（diyp JSON / txt / m3u，如直播源文本）；留空清除。配置后直播页显示分组与频道")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.liveUrl = input.text.toString().trim()
                refresh()
                Toast.makeText(
                    this,
                    if (settings.liveUrl.isBlank()) "直播源已清除" else "直播源已配置",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 备份：将 shared_prefs 全部 xml 打包成 zip，存到可访问目录 */
    private fun doBackup() {
        runCatching {
            val prefsDir = File(filesDir.parentFile ?: filesDir, "shared_prefs")
            val outDir = getExternalFilesDir(null) ?: filesDir
            val out = File(outDir, "xinghui_backup.zip")
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                prefsDir.listFiles { f -> f.name.endsWith(".xml") }?.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            Toast.makeText(this, "已备份到：${out.absolutePath}", Toast.LENGTH_LONG).show()
            refresh()
        }.onFailure { e ->
            Toast.makeText(this, "备份失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 恢复：从备份 zip 解压覆盖 shared_prefs */
    private fun doRestore() {
        runCatching {
            val outDir = getExternalFilesDir(null) ?: filesDir
            val zip = File(outDir, "xinghui_backup.zip")
            if (!zip.exists()) {
                Toast.makeText(this, "未找到备份文件：$zip", Toast.LENGTH_LONG).show()
                return
            }
            val prefsDir = File(filesDir.parentFile ?: filesDir, "shared_prefs")
            ZipInputStream(FileInputStream(zip)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val target = File(prefsDir, entry.name)
                    if (entry.name.endsWith(".xml") && !entry.name.contains("/") && !entry.isDirectory) {
                        FileOutputStream(target).use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            Toast.makeText(this, "已恢复，请重启应用生效", Toast.LENGTH_LONG).show()
            refresh()
        }.onFailure { e ->
            Toast.makeText(this, "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 刷新设置值显示 */
    private fun refresh() {
        tvKernel.text = settings.kernelLabel()
        tvTimeout.text = "${settings.networkTimeoutSec} 秒"
        tvUa.text = settings.uaLabel()
        tvProxy.text = if (settings.proxy.isBlank()) "关闭" else "已启用"
        tvEpg.text = if (settings.epgUrl.isBlank()) "未配置" else "已配置"
        tvLive.text = if (settings.liveUrl.isBlank()) "未配置" else "已配置"
        tvPoster.text = if (settings.showPoster) "开" else "关"
        tvHomeHistory.text = if (settings.isHomeHistory) "开" else "关"
        tvHistory.text = "${store.history().size} 条（点击清空）"
        tvFav.text = "${store.favorites().size} 条（点击清空）"
        val backup = backupFile()
        tvBackup.text = if (backup != null && backup.exists()) "已备份（${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(backup.lastModified()))}）" else "未备份（点击导出）"
        tvRestore.text = if (backup != null && backup.exists()) "点击从备份恢复" else "暂无备份文件"
        tvWallpaper.text = if (WallpaperStore.has(this)) "自定义（点击更换）" else "默认渐变（点击设置）"
        tvSize.text = settings.uiSizeLabel() + "（点击切换）"
        tvScale.text = settings.playerScaleLabel() + "（点击切换）"
        tvAbout.text = "星汇TV v0.1.0（debug）"
    }

    private companion object {
        private const val REQ_WALLPAPER = 1001
    }

    /** 备份文件路径（与 doBackup/doRestore 一致） */
    private fun backupFile(): File? = getExternalFilesDir(null)?.let { File(it, "xinghui_backup.zip") }
}
