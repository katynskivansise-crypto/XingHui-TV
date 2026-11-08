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

            // 点播：原版 SettingActivity.onVod → ConfigDialog.create(this).type(0).show()
        // 弹窗内含 二维码 + 「使用手机扫描二维码 或于浏览器访问地址 …」+ 接口输入框 + 选择/确定/取消
        findViewById<View>(R.id.entryVod)?.setOnClickListener { showConfigDialog(0) }
        // 点播行的 home 图标：原版是 SiteDialog（首页源锁定），本工程用它进站点列表
        findViewById<View>(R.id.entryVodHome)?.setOnClickListener {
            startActivity(Intent(this, SitesActivity::class.java))
        }
        // 点播配置历史（原版 SettingActivity.onVodHistory → HistoryDialog type=0）
        findViewById<View>(R.id.entryVodHistory)?.setOnClickListener { showConfigHistory(0) }

        // 直播：值显示直播源是否已配，点击可编辑（原版点开是直播源列表）
        // 直播：原版 SettingActivity.onLive → ConfigDialog.create(this).type(1).show()
        findViewById<View>(R.id.entryLive)?.setOnClickListener { showConfigDialog(1) }
        // 直播行的 home 图标：原版 SettingActivity.onLiveHome → LiveDialog.action()（列出
        // 订阅里的 lives 供选择）。之前这里只弹「暂无直播主页源」，而订阅其实带了
        // migu / live / ipv6 三个源 —— 属于"有数据却没接"，本轮接上。
        findViewById<View>(R.id.entryLiveHome)?.setOnClickListener { showLiveHomeDialog() }
        // 直播配置历史（原版 SettingActivity.onLiveHistory → HistoryDialog type=1）
        findViewById<View>(R.id.entryLiveHistory)?.setOnClickListener { showConfigHistory(1) }

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

        // DoH：固定走系统解析，不做 DoH（2026-11-08 决定）。
        // 这一行只展示当前取值、不可聚焦：原来点了会弹「当前固定使用系统解析」，
        // 等于用一个提示假装成开关，属于"空有选项"，这里改回纯展示。

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

    /**
     * 原版点「缓存」= 把 Path.cache() 整个删掉（FileUtil.clearCache），完成后只 setCacheText()
     * 让数字归 0，不弹任何提示。这里同样只刷新数字。
     */
    private fun clearCache() {
        runCatching {
            AppCache.clear(this)
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

        refreshCacheText()
        findViewById<TextView>(R.id.valVersion)?.text = "0.1.0"
        findViewById<TextView>(R.id.valAbout)?.text = "leanback-arm64_v8a"
    }

    /**
     * 统计放到子线程：图片缓存上限 100MB、几百个文件，主线程同步遍历会有明显卡顿
     * （原版也是 App.execute 里算完再 App.post 回填）。
     */
    private fun refreshCacheText() {
        val tv = findViewById<TextView>(R.id.valCache) ?: return
        Thread {
            val size = AppCache.size(this)
            runOnUiThread { runCatching { if (!isFinishing) tv.text = AppCache.format(size) } }
        }.apply { isDaemon = true }.start()
    }

    private fun backupFile(): File? = getExternalFilesDir(null)?.let { File(it, "xinghui_backup.zip") }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        private const val REQ_WALLPAPER = 1001
    }

    /** 本机局域网 IPv4 地址（原版 Util.getIp()）；取不到时回 127.0.0.1 */
    private fun serverAddress(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
            ?: "127.0.0.1"
    }.getOrDefault("127.0.0.1")

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 点播/直播配置弹窗（照原版 ConfigDialog + dialog_config.xml）：
     * 左边 180dp 二维码 → 扫出来是 http://IP:9978?tab=3（原版 Server.getAddress(3)，直达手机网页「設定」）；
     * 右边三行提示「使用手机扫描二维码 / 或于浏览器访问地址 / http://IP:9978」（原版 push_info）；
     * 下方接口输入框预填当前配置；底部「选择 / 确定 / 取消」。
     * 确定 = 原版 onPositive → Config.find(text,type) → VodConfig/LiveConfig.load()。
     */
    /**
     * 直播主页源选择（对齐原版 LiveDialog.action()）。
     *
     * 数据来自订阅顶层的 `lives`：[{name, url}]。SubscriptionStore 存的是**原文**，
     * 相对地址（如 `./tvboxtv.txt`）在这里按订阅地址补全（原版 Decoder.fix 的规则）。
     */
    private fun showLiveHomeDialog() {
        // 解析已下沉到引擎，这里只拿结果
        val lives = SubscriptionParser.lives(
            SubscriptionStore(this).subscriptionText(),
            SubscriptionStore(this).subscribeUrl()
        )
        if (lives.isEmpty()) {
            toast("订阅里没有直播主页源")
            return
        }
        val cur = Settings(this).liveUrl
        val selected = lives.indexOfFirst { it.url == cur }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("直播主页源")
            .setSingleChoiceItems(lives.map { it.name }.toTypedArray(), selected) { d, which ->
                val live = lives[which]
                Settings(this).liveUrl = live.url
                Settings(this).liveUa = live.ua
                // 对齐原版：EPG 地址跟着直播源走（Live.epg），选中就自动填好节目单源。
                // 源里没给 epg 时不动原值，避免把用户手填的地址清掉。
                if (live.epg.isNotBlank()) Settings(this).epgUrl = live.epg
                d.dismiss()
                refresh()
                toast("直播主页源已设为 ${live.name}" +
                    if (live.epg.isNotBlank()) "（已带上节目单）" else "")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showConfigDialog(type: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_config, null)
        val code = view.findViewById<android.widget.ImageView>(R.id.code)
        val info = view.findViewById<TextView>(R.id.info)
        val text = view.findViewById<EditText>(R.id.text)
        val positive = view.findViewById<TextView>(R.id.positive)
        val negative = view.findViewById<TextView>(R.id.negative)
        val storage = view.findViewById<TextView>(R.id.storage)

        val base = "http://${serverAddress()}:9978"
        // 原版 QRCode.getBitmap(Server.get().getAddress(3), 200, 0)
        runCatching { QRCode.getBitmap("$base?tab=3", px(180), 0) }
            .getOrNull()?.let { code.setImageBitmap(it) }
        // 原版 push_info：「使用手机扫描二维码，或于浏览器访问地址 %s」，中文逗号被换成换行
        info.text = "使用手机扫描二维码\n或于浏览器访问地址\n$base"

        val cur = if (type == 0) SubscriptionStore(this).subscribeUrl() else settings.liveUrl
        text.setText(cur)
        text.setSelection(cur.length)

        // 原版 storage 是申请存储权限（为读本地 file:// 配置）；本工程订阅只走网络地址
        storage.setOnClickListener { toast("本工程订阅只支持网络地址") }

        val dlg = AlertDialog.Builder(this).setView(view).create()
        positive.setOnClickListener {
            val url = text.text.toString().trim()
            dlg.dismiss()
            if (url.isEmpty()) {
                toast(if (type == 0) "请输入点播接口" else "请输入直播接口")
                return@setOnClickListener
            }
            if (type == 1) {
                settings.liveUrl = url
                SubscriptionStore(this).saveConfig(url, "", 1)
                refresh()
                toast("已保存直播源")
            } else {
                toast("正在加载订阅…")
                SubscriptionImporter.import(this, url) { ok, msg ->
                    if (ok) refresh()
                    toast(msg)
                }
            }
        }
        negative.setOnClickListener { dlg.dismiss() }
        dlg.setOnShowListener {
            // 原版：宽度 = 屏幕 55%，dimAmount = 0（不把底层压暗）
            runCatching {
                dlg.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.55f).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                dlg.window?.setDimAmount(0f)
            }
            text.requestFocus()
        }
        dlg.show()
    }

    /**
     * 配置历史列表（对齐原版 HistoryDialog + dialog_history.xml）：
     * 单列 RecyclerView，项文案取 Config.getDesc()（有 name 显示 name，否则显示 url），
     * 点击 = ConfigAdapter.onTextClick → callback.setConfig(item)，长按 = onDeleteClick。
     */
    private fun showConfigHistory(type: Int) {
        val store = SubscriptionStore(this)
        val items = store.configHistory(type)
        if (items.isEmpty()) {
            toast(if (type == 0) "暂无点播配置历史" else "暂无直播配置历史")
            return
        }
        val pairs = items.map { it.first to it.second }
        val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@SettingsActivity)
            setPadding(px(16), px(16), px(16), px(16))
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            clipToPadding = false
        }
        rv.adapter = ListAdapter(
            items = pairs,
            onLongClick = { u ->
                store.deleteConfig(u, type)
                toast("已删除该配置（观看历史不受影响）")
            },
            focusTextColor = android.graphics.Color.rgb(255, 204, 0),
            onClick = { u -> applyConfig(u, type) }
        )
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (type == 0) "点播配置历史" else "直播配置历史")
            .setView(rv)
            .setNegativeButton("关闭", null)
            .create()
        dlg.setOnShowListener { rv.post { rv.getChildAt(0)?.requestFocus() } }
        dlg.show()
    }

    /**
     * 切换配置（对齐原版 ConfigCallback.setConfig）：
     * 点播 → VodConfig.load(config)：clear 内存 + 强制重拉 + 重建站点；
     * 直播 → LiveConfig.load(config)：换直播源地址。
     * 观看历史/收藏按 vodId 存（HistoryStore），不随配置变，切回旧配置历史仍在。
     */
    private fun applyConfig(url: String, type: Int) {
        if (type == 1) {
            settings.liveUrl = url
            SubscriptionStore(this).saveConfig(url, "", 1)
            refresh()
            toast("已切换直播源")
            return
        }
        toast("正在加载订阅…")
        SubscriptionImporter.import(this, url) { ok, msg ->
            if (ok) refresh()
            toast(msg)
        }
    }
}
