package com.xueti.learn.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自定义背景图：从用户选择的图片解码（带降采样），
 * 叠加一层主题背景色蒙版以保证文字可读性。
 */
object BackgroundHelper {

    private const val MAX_WIDTH = 1440

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var cachedUri: String? = null
    private var cachedBitmap: Bitmap? = null

    /** 清除缓存（更换/移除背景图后调用） */
    fun invalidate() {
        cachedUri = null
        cachedBitmap = null
    }

    fun apply(root: View, uriString: String?, dimPercent: Int) {
        if (uriString.isNullOrBlank()) {
            root.background = null
            return
        }
        val cached = cachedBitmap
        if (cached != null && cachedUri == uriString) {
            root.background = buildLayer(root, cached, dimPercent)
            return
        }
        val appContext = root.context.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(appContext, uriString) }
            if (bitmap != null) {
                cachedUri = uriString
                cachedBitmap = bitmap
                root.background = buildLayer(root, bitmap, dimPercent)
            } else {
                root.background = null
            }
        }
    }

    private fun decode(context: Context, uriString: String): Bitmap? = runCatching {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (bounds.outWidth / sample > MAX_WIDTH) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun buildLayer(root: View, bitmap: Bitmap, dimPercent: Int): Drawable {
        val image = BitmapDrawable(root.resources, bitmap).apply { gravity = Gravity.FILL }
        val bgColor = themeBackgroundColor(root.context)
        val alpha = dimPercent.coerceIn(0, 95) * 255 / 100
        val scrim = ColorDrawable(ColorUtils.setAlphaComponent(bgColor, alpha))
        return LayerDrawable(arrayOf(image, scrim))
    }

    /** 读取当前主题的背景色，用作背景图蒙版颜色（深浅色主题自动适配） */
    private fun themeBackgroundColor(context: Context): Int {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val color = attrs.getColor(0, Color.GRAY)
        attrs.recycle()
        return color
    }
}
