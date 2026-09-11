package com.xueti.learn.util

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 文字图像增强：
 * - 灰度化
 * - 自动对比度拉伸（直方图线性拉伸）
 * - 高斯模糊 + 反锐化掩模（unsharp mask）锐化，让文字边缘更清晰
 * - Otsu 自适应阈值二值化（文档/题目截图效果最好）
 *
 * 全部基于 ARGB 像素数组运算，可在子线程安全调用。
 */
object ImageEnhancer {

    data class Options(
        val grayscale: Boolean = true,
        val autoContrast: Boolean = true,
        val sharpen: Boolean = true,
        val binarize: Boolean = false
    )

    fun enhance(src: Bitmap, options: Options): Bitmap {
        val width = src.width
        val height = src.height
        if (width <= 1 || height <= 1) return src

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        var data = pixels
        if (options.grayscale || options.autoContrast || options.sharpen || options.binarize) {
            if (options.grayscale) data = toGrayscale(data)
            if (options.autoContrast) data = autoContrast(data)
            if (options.sharpen) data = unsharpMask(data, width, height, radius = 3, amount = 0.9f)
            if (options.binarize) data = otsuBinarize(data)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(data, 0, width, 0, 0, width, height)
        return result
    }

    // ---------------- 灰度 ----------------

    fun toGrayscale(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
            out[i] = argb(255, luma, luma, luma)
        }
        return out
    }

    // ---------------- 自动对比度 ----------------

    /** 按亮度直方图做线性拉伸，把最暗/最亮 1% 之外的部分映射到 0-255 */
    fun autoContrast(pixels: IntArray): IntArray {
        val histogram = IntArray(256)
        val lumas = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val luma = (((c shr 16) and 0xFF) * 299 +
                ((c shr 8) and 0xFF) * 587 +
                (c and 0xFF) * 114) / 1000
            lumas[i] = luma
            histogram[luma.coerceIn(0, 255)]++
        }
        val total = pixels.size
        val lowLimit = (total * 0.01).toInt()
        val highLimit = (total * 0.99).toInt()

        var cumulative = 0
        var low = 0
        var high = 255
        for (i in 0..255) {
            cumulative += histogram[i]
            if (cumulative > lowLimit) {
                low = i
                break
            }
        }
        cumulative = 0
        for (i in 0..255) {
            cumulative += histogram[i]
            if (cumulative >= highLimit) {
                high = i
                break
            }
        }
        if (high <= low) return pixels

        val scale = 255f / (high - low)
        val lookup = IntArray(256) { value ->
            (((value - low) * scale).roundToInt()).coerceIn(0, 255)
        }
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = lookup[((c shr 16) and 0xFF)]
            val g = lookup[((c shr 8) and 0xFF)]
            val b = lookup[(c and 0xFF)]
            out[i] = argb(255, r, g, b)
        }
        return out
    }

    // ---------------- 高斯模糊 + 反锐化掩模 ----------------

    /** 反锐化掩模：result = clamp(original + amount * (original - gaussianBlur)) */
    fun unsharpMask(pixels: IntArray, width: Int, height: Int, radius: Int, amount: Float): IntArray {
        val blurred = gaussianBlur(pixels, width, height, radius)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val o = pixels[i]
            val b = blurred[i]
            val r = sharpenChannel((o shr 16) and 0xFF, (b shr 16) and 0xFF, amount)
            val g = sharpenChannel((o shr 8) and 0xFF, (b shr 8) and 0xFF, amount)
            val bl = sharpenChannel(o and 0xFF, b and 0xFF, amount)
            out[i] = argb(255, r, g, bl)
        }
        return out
    }

    private fun sharpenChannel(original: Int, blurred: Int, amount: Float): Int =
        (original + amount * (original - blurred)).roundToInt().coerceIn(0, 255)

    /** 可分离高斯模糊（先水平后垂直），sigma 由半径推导 */
    fun gaussianBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val kernel = gaussianKernel(radius)
        val temp = IntArray(pixels.size)
        val out = IntArray(pixels.size)

        // 水平方向
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var weightSum = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    val c = pixels[rowStart + sx]
                    val w = kernel[k + radius]
                    sumR += ((c shr 16) and 0xFF) * w
                    sumG += ((c shr 8) and 0xFF) * w
                    sumB += (c and 0xFF) * w
                    weightSum += w
                }
                val r = (sumR / weightSum).roundToInt().coerceIn(0, 255)
                val g = (sumG / weightSum).roundToInt().coerceIn(0, 255)
                val b = (sumB / weightSum).roundToInt().coerceIn(0, 255)
                temp[rowStart + x] = argb(255, r, g, b)
            }
        }

        // 垂直方向
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var weightSum = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    val c = temp[sy * width + x]
                    val w = kernel[k + radius]
                    sumR += ((c shr 16) and 0xFF) * w
                    sumG += ((c shr 8) and 0xFF) * w
                    sumB += (c and 0xFF) * w
                    weightSum += w
                }
                val r = (sumR / weightSum).roundToInt().coerceIn(0, 255)
                val g = (sumG / weightSum).roundToInt().coerceIn(0, 255)
                val b = (sumB / weightSum).roundToInt().coerceIn(0, 255)
                out[y * width + x] = argb(255, r, g, b)
            }
        }
        return out
    }

    private fun gaussianKernel(radius: Int): FloatArray {
        val sigma = (radius / 2.0).coerceAtLeast(0.8)
        val size = radius * 2 + 1
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - radius).toDouble()
            kernel[i] = exp(-(x * x) / (2 * sigma * sigma)).toFloat()
            sum += kernel[i]
        }
        for (i in 0 until size) kernel[i] /= sum
        return kernel
    }

    // ---------------- Otsu 二值化 ----------------

    fun otsuBinarize(pixels: IntArray): IntArray {
        val histogram = IntArray(256)
        val lumas = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val luma = (((c shr 16) and 0xFF) * 299 +
                ((c shr 8) and 0xFF) * 587 +
                (c and 0xFF) * 114) / 1000
            lumas[i] = luma.coerceIn(0, 255)
            histogram[lumas[i]]++
        }
        val total = pixels.size

        var sumAll = 0.0
        for (i in 0..255) sumAll += i.toDouble() * histogram[i]

        var weightBackground = 0
        var sumBackground = 0.0
        var maxVariance = -1.0
        var threshold = 128

        for (i in 0..255) {
            weightBackground += histogram[i]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += i.toDouble() * histogram[i]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val variance = weightBackground.toDouble() * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = i
            }
        }

        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val value = if (lumas[i] > threshold) 255 else 0
            out[i] = argb(255, value, value, value)
        }
        return out
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
