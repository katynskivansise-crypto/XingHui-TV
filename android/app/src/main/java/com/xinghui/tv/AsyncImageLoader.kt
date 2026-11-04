package com.xinghui.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

/**
 * 海报图片异步加载（HttpURLConnection + Bitmap），失败/无图显示深色占位。
 * 必须在有 Looper 的线程设置（view.post）。不引第三方图片库。
 */
object AsyncImageLoader {

    private val placeHolder = ColorDrawable(0xFF1A1A1A.toInt())

    fun load(view: ImageView, url: String) {
        view.setImageDrawable(placeHolder)
        if (url.isBlank()) return
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; TV)")
                val code = conn.responseCode
                if (code in 200..299) {
                    val bmp: Bitmap? = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) {
                        view.post { view.setImageBitmap(bmp) }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // 占位图已设置，静默失败
            }
        }.start()
    }
}
