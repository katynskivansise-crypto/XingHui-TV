package com.xinghui.tv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL

/**
 * 壁纸存储与生效（对齐原版 WallConfig + FileUtil.getWall + BaseActivity.refreshWall）：
 * - 落盘位置：filesDir/wallpaper_0（原版是 Path.files("wallpaper_" + index)）
 * - 导入后按屏幕尺寸 centerCrop 压成 JPEG 再存（原版 WallConfig.resize 用 Glide centerCrop + 100% JPEG）
 * - 生效方式：window.setBackgroundDrawable（原版 BaseActivity 也是设 window 背景，铺满整界面）
 * 我们比原版多一步"允许用户自选图片"——原版只有 1~4 号内置壁纸 + 订阅 wall 配置，
 * 内置图我们仓库里没有，改成"本地选图 / 图片地址 / 恢复默认"三种来源。
 */
object WallpaperStore {

    private const val TAG = "XingHuiTV"
    private const val FILE_NAME = "wallpaper_0"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun has(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    /** 应用到窗口；有自设壁纸用它，没有就回退原版内置 wallpaper_1（全站统一，对齐 BaseActivity.refreshWall） */
    fun apply(activity: Activity) {
        runCatching {
            val f = file(activity)
            if (f.exists() && f.length() > 0) {
                activity.window.setBackgroundDrawable(Drawable.createFromPath(f.absolutePath))
            } else {
                // 原版 Setting.getWall() 默认 1 → 用内置 wallpaper_1。
                // 之前这里设 null 会把 MainActivity 刚设好的壁纸清掉，首页退回纯黑，
                // 而详情页没调本方法所以仍是绿色 —— 就是「首页黑、播放页绿」的原因。
                activity.window.setBackgroundDrawableResource(R.drawable.wallpaper_1)
            }
        }.onFailure { Log.e(TAG, "[wall] apply 失败", it) }
    }

    /** 从系统文件选择器返回的 Uri 导入 */
    fun importFromUri(activity: Activity, uri: Uri, done: (Boolean, String) -> Unit) {
        Thread {
            val ok = runCatching { save(activity) { activity.contentResolver.openInputStream(uri) } }
                .getOrElse { e ->
                    Log.e(TAG, "[wall] 读取失败 uri=$uri", e)
                    false
                }
            activity.runOnUiThread {
                if (ok) apply(activity)
                done(ok, if (ok) "壁纸已设置" else "壁纸读取失败")
            }
        }.start()
    }

    /** 从网络地址导入 */
    fun importFromUrl(activity: Activity, url: String, done: (Boolean, String) -> Unit) {
        Thread {
            val ok = runCatching { save(activity) { URL(url).openStream() } }
                .getOrElse { e ->
                    Log.e(TAG, "[wall] 下载失败 url=$url", e)
                    false
                }
            activity.runOnUiThread {
                if (ok) apply(activity)
                done(ok, if (ok) "壁纸已设置" else "壁纸下载失败（地址是否可访问？）")
            }
        }.start()
    }

    fun clear(activity: Activity) {
        runCatching { file(activity).delete() }
        apply(activity)
    }

    /**
     * 解码 → 按屏幕 centerCrop → JPEG 落盘。
     * opener 会被调用两次（先只读尺寸算采样率，再真正解码），所以传"开流函数"而不是流本身。
     */
    private fun save(context: Context, opener: () -> InputStream?): Boolean {
        val dm = context.resources.displayMetrics
        val targetW = dm.widthPixels
        val targetH = dm.heightPixels
        if (targetW <= 0 || targetH <= 0) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = opener() ?: return false
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        // 采样率：解码尺寸不低于屏幕的 2 倍，避免 4K 原图直接进内存
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = opener()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false

        val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val nw = (src.width * scale).toInt().coerceAtLeast(targetW)
        val nh = (src.height * scale).toInt().coerceAtLeast(targetH)

        // 对齐原版语义（WallConfig.resize 的 catch → return file）：任何一步失败都降级而不是整体失败，
        // 保证"宁可降质也要把壁纸设上"。
        var scaled: Bitmap = src
        runCatching {
            val b = Bitmap.createScaledBitmap(src, nw, nh, true)
            if (b !== src) scaled = b
        }.onFailure { Log.e(TAG, "[wall] 缩放失败，改用原尺寸", it) }

        val x = ((scaled.width - targetW) / 2).coerceAtLeast(0)
        val y = ((scaled.height - targetH) / 2).coerceAtLeast(0)
        val w = minOf(targetW, scaled.width)
        val h = minOf(targetH, scaled.height)
        val out: Bitmap = runCatching {
            if (x == 0 && y == 0 && w == scaled.width && h == scaled.height) scaled
            else Bitmap.createBitmap(scaled, x, y, w, h)
        }.getOrElse {
            Log.e(TAG, "[wall] 裁剪失败，改用整图", it)
            scaled
        }

        FileOutputStream(file(context)).use { os ->
            out.compress(Bitmap.CompressFormat.JPEG, 92, os)
        }
        Log.e(TAG, "[wall] 已保存 ${out.width}x${out.height}（源 ${bounds.outWidth}x${bounds.outHeight}，采样 1/$sample）")
        return true
    }
}
