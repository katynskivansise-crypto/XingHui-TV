package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
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
 * 设置·中心页（对齐原版 SettingActivity + activity_setting.xml）。
 *
 * ★ 结构必须和原版一致：这一页只放**入口**，点进去才是子页，不把所有选项平铺在一屏。
 *   原版 activity_setting.xml 的行序（2 列网格）：
 *     点播（值=站点地址，右侧 home/history 图标）
 *     直播（值=直播源，右侧 home/history 图标）
 *     壁纸（值=当前壁纸，右侧 home/refresh 图标）
 *     播放设置 | 弹幕设置
 *     个性设置 | DoH
 *     备份 | 恢复
 *     缓存 | 版本
 *     关于
 *   子页：SettingsPlayerActivity / SettingsDanmuActivity / SettingsCustomActivity。
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        WallpaperStore.apply(this)
        settings = Settings(this)

        // 点播：显示当前站点配置来源，点击进入站点列表（原版点播行点开就是站源页）
        findViewById<View>(R.id.entryVod)?.setOnClickListener {
            startActivity(Intent(this, SitesActivity::class.java))
        }
        // 直播源主页图标 / 历史图标（本工程无直播体系数据，只做入口占位）
        findViewById<View>(R.id.entryVodHome)?.setOnClickListener { toast("暂无直播主页源") }
        findViewById<View>(R.id.entryVodHistory)?.setOnClickListener { toast("暂无直播历史") }

        // 直播：值显示直播源是否已配，点击可编辑（原版点开是直播源列表）
        findViewById<View>(R.id.entryLive)?.setOnClickListener { showLiveDialog() }
        findViewById<View>(R.id.entryLiveHome)?.setOnClickListener { toast("暂无直播主页源") }
        findViewById<View>(R.id.entryLiveHistory)?.setOnClickListener { toast("暂无直播历史") }

        // 壁纸：点行弹选择，右侧 home=恢复默认、refresh=重新应用
        findViewById<View>(R.id.entryWall)?.setOnClickListener { showWallpaperDialog() }
        findViewById<View>(R.id.entryWallHome)?.setOnClickListener {
            WallpaperStore.clear(this)
            refresh()
            toast("已恢复默认壁纸")
        }
        findViewById<View>(R.id.entryWallRefresh)?.setOnClickListener {
            WallpaperStore.apply(this)
            toast("已重新应用壁纸")
        }

        // 三个子页入口 —— 这就是用户要的「点击进去」
        findViewById<View>(R.id.entryPlayerPage)?.setOnClickListener {
            startActivity(Intent(this, SettingsPlayerActivity::class.java))
        }
        findViewById<View>(R.id.entryDanmuPage)?.setOnClickListener {
            startActivity(Intent(this, SettingsDanmuActivity::class.java))
        }
        findViewById<View>(R.id.entryCustomPage)?.setOnClickListener {
            startActivity(Intent(this, SettingsCustomActivity::class.java))
        }

        // DoH：仅展示（原版可选 系统 / 阿里 / 腾讯）
        findViewById<View>(R.id.entryDoh)?.setOnClickListener { toast("DoH 当前固定使用系统解析") }

        findViewById<View>(R.id.entryBackup)?.setOnClickListener { doBackup() }
        findViewById<View>(R.id.entryRestore)?.setOnClickListener { doRestore() }
        findViewById<View>(R.id.entryCache)?.setOnClickListener { clearCache() }

        findViewById<View>(R.id.entryVersion)?.setOnClickListener { toast("星汇TV v0.1.0") }
        findViewById<View>(R.id.entryAbout)?.setOnClickListener { toast("星汇TV · leanback-arm64_v8a") }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ==== 壁纸 ====

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
                        toast("已恢复默认背景")
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
        }.onFailure { toast("无法打开文件选择器") }
    }

    private fun showWallpaperUrlDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setHint("https://example.com/wall.jpg")
            requestFocus()
        }
        AlertDialog.Builder(this)
            .setTitle("壁纸图片地址")
            .setMessage("支持 jpg/png 直链，图片会自动裁成屏幕比例")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                toast("正在下载壁纸…")
                WallpaperStore.importFromUrl(this, url) { _, msg ->
                    refresh()
                    toast(msg)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_WALLPAPER || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        WallpaperStore.importFromUri(this, uri) { _, msg ->
            refresh()
            toast(msg)
        }
    }

    // ==== 直播源 ====

    private fun showLiveDialog() {
        val input = EditText(this).apply {
            setText(settings.liveUrl)
            setSingleLine(true)
            setHint("https://xxx/live.txt")
            requestFocus()
        }
        AlertDialog.Builder(this)
            .setTitle("直播")
            .setMessage("填直播源地址（diyp JSON / txt / m3u）；留空清除")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.liveUrl = input.text.toString().trim()
                refresh()
                toast(if (settings.liveUrl.isBlank()) "直播源已清除" else "直播源已配置")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==== 备份 / 恢复 ====

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
            toast("已备份到：${out.absolutePath}")
            refresh()
        }.onFailure { e -> toast("备份失败：${e.message}") }
    }

    private fun doRestore() {
        runCatching {
            val outDir = getExternalFilesDir(null) ?: filesDir
            val zip = File(outDir, "xinghui_backup.zip")
            if (!zip.exists()) {
                toast("未找到备份文件：$zip")
                return
            }
            val prefsDir = File(filesDir.parentFile ?: filesDir, "shared_prefs")
            ZipInputStream(FileInputStream(zip)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".xml") && !entry.name.contains("/") && !entry.isDirectory) {
                        FileOutputStream(File(prefsDir, entry.name)).use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            toast("已恢复，请重启应用生效")
            refresh()
        }.onFailure { e -> toast("恢复失败：${e.message}") }
    }

    private fun clearCache() {
        runCatching {
            val n = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                (externalCacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L)
            cacheDir.deleteRecursively()
            externalCacheDir?.deleteRecursively()
            toast("已清理缓存 ${formatSize(n)}")
            refresh()
        }.onFailure { e -> toast("清理失败：${e.message}") }
    }

    // ==== 刷新显示 ====

    private fun refresh() {
        findViewById<TextView>(R.id.valVod)?.text =
            SubscriptionStore(this).subscribeUrl().ifBlank { "未配置" }
        findViewById<TextView>(R.id.valLive)?.text = if (settings.liveUrl.isBlank()) "未配置" else settings.liveUrl
        findViewById<TextView>(R.id.valWallpaper)?.text =
            if (WallpaperStore.has(this)) "自定义" else "默认渐变"

        findViewById<TextView>(R.id.valPlayerPage)?.text = settings.kernelLabel()
        findViewById<TextView>(R.id.valDanmuPage)?.text = "关闭"
        findViewById<TextView>(R.id.valCustomPage)?.text = settings.uiSizeLabel()
        findViewById<TextView>(R.id.valDoh)?.text = "系统"

        val backup = backupFile()
        findViewById<TextView>(R.id.valBackup)?.text =
            if (backup != null && backup.exists()) {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(backup.lastModified()))
            } else "未备份"
        findViewById<TextView>(R.id.valRestore)?.text =
            if (backup != null && backup.exists()) "点击恢复" else "暂无备份"

        findViewById<TextView>(R.id.valCache)?.text = formatSize(cacheDirSize())
        findViewById<TextView>(R.id.valVersion)?.text = "0.1.0"
        findViewById<TextView>(R.id.valAbout)?.text = "leanback-arm64_v8a"
    }

    private fun cacheDirSize(): Long =
        cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun formatSize(b: Long): String = when {
        b >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1024.0 / 1024.0)
        b >= 1L shl 10 -> String.format(java.util.Locale.getDefault(), "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun backupFile(): File? = getExternalFilesDir(null)?.let { File(it, "xinghui_backup.zip") }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        private const val REQ_WALLPAPER = 1001
    }
}
