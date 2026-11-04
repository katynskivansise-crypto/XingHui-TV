package com.xinghui.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.util.Base64
import android.widget.ImageView
import org.json.JSONObject

/**
 * 海报图片异步加载：走引擎海报代理（proxy_image），复用设置的 UA/代理，
 * 比壳侧 HttpURLConnection 直连更稳（统一 ureq 网络层，可带 UA/代理防部分防盗链）。
 * 失败/无图显示深色占位。必须在有 Looper 的线程设置（view.post）。不引第三方图片库。
 */
object AsyncImageLoader {

    private val placeHolder = ColorDrawable(0xFF1A1A1A.toInt())

    fun load(view: ImageView, url: String) {
        view.setImageDrawable(placeHolder)
        if (url.isBlank()) return
        Thread {
            try {
                // view.context 拿应用上下文 → 读设置的 UA/代理，传给引擎代理拉字节
                val s = Settings(view.context)
                val params = JSONObject()
                    .put("url", url)
                    .put("ua", s.ua)
                    .put("proxy", s.proxy)
                    .toString()
                val b64 = Engine.execute("proxy_image", "", params)
                if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        view.post { view.setImageBitmap(bmp) }
                    }
                }
            } catch (_: Exception) {
                // 占位图已设置，静默失败
            }
        }.start()
    }
}
