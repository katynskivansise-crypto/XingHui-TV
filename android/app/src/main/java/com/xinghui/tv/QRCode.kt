package com.xinghui.tv

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.util.EnumMap

/**
 * 二维码生成（照原版 com.fongmi.android.tv.utils.QRCode 逐行移植）。
 * 原版 getBitmap(contents, size, margin) 的 size 单位是 dp，内部走 ResUtil.dp2px；
 * 这里由调用方换算成像素传入，其余语义一致。
 */
object QRCode {

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    fun createBitmap(matrix: BitMatrix): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun getBitmap(contents: String, size: Int, margin: Int): Bitmap? {
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.MARGIN] = margin
            createBitmap(MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints))
        } catch (e: Exception) {
            null
        }
    }
}
