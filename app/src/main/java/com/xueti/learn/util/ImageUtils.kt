package com.xueti.learn.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** 图片解码 / 编码工具（统一降采样，避免大图 OOM） */
object ImageUtils {

    const val MAX_DIMENSION = 1600

    /** 按最长边降采样解码 */
    fun decodeSampled(context: Context, uri: Uri, maxDimension: Int = MAX_DIMENSION): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxDimension) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()

    /** 压缩为 JPEG 并转 base64（不含 data URL 前缀） */
    fun toBase64(bitmap: Bitmap, quality: Int = 88): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
