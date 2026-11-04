package com.xinghui.tv

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var tvPoster: TextView
    private lateinit var tvHomeHistory: TextView
    private lateinit var tvHistory: TextView
    private lateinit var tvFav: TextView
    private lateinit var tvAbout: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = Settings(this)
        store = HistoryStore(this)

        tvKernel = findViewById<TextView>(R.id.valKernel)
        tvTimeout = findViewById<TextView>(R.id.valTimeout)
        tvUa = findViewById<TextView>(R.id.valUa)
        tvProxy = findViewById<TextView>(R.id.valProxy)
        tvEpg = findViewById<TextView>(R.id.valEpg)
        tvPoster = findViewById<TextView>(R.id.valPoster)
        tvHomeHistory = findViewById<TextView>(R.id.valHomeHistory)
        tvHistory = findViewById<TextView>(R.id.valHistory)
        tvFav = findViewById<TextView>(R.id.valFav)
        tvAbout = findViewById<TextView>(R.id.valAbout)

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
        findViewById<View>(R.id.rowBackup).setOnClickListener { doBackup() }

        // 恢复：从备份 zip 还原配置
        findViewById<View>(R.id.rowRestore).setOnClickListener { doRestore() }

        refresh()
    }

    /** 代理设置对话框（对齐原项目 ProxyDialog）：输入地址保存；留空保存即关闭代理 */
    private fun showProxyDialog() {        val input = EditText(this)
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
        tvPoster.text = if (settings.showPoster) "开" else "关"
        tvHomeHistory.text = if (settings.isHomeHistory) "开" else "关"
        tvHistory.text = "${store.history().size} 条（点击清空）"
        tvFav.text = "${store.favorites().size} 条（点击清空）"
        tvAbout.text = "星汇TV v0.1.0（debug）"
    }
}
