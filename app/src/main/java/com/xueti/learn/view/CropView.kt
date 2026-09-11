package com.xueti.learn.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 简易图片裁剪视图：
 * 图片按 fit-center 显示，裁剪框支持四角拖拽与整体移动，绘制三分线与暗化遮罩。
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { NONE, MOVE, LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM }

    private var bitmap: Bitmap? = null
    private val bitmapRect = RectF()
    private val cropRect = RectF()
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val minSize: Float get() = dp(60f)
    private val handleTouchRadius: Float get() = dp(28f)
    private val handleLength: Float get() = dp(18f)

    fun setBitmap(source: Bitmap) {
        bitmap?.recycle()
        bitmap = source
        requestLayout()
        post { resetCrop() }
        invalidate()
    }

    fun currentBitmap(): Bitmap? = bitmap

    /** 旋转原图（每次 90°），旋转后重置裁剪框 */
    fun rotate(degrees: Float) {
        val src = bitmap ?: return
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        bitmap = rotated
        if (rotated != src) src.recycle()
        requestLayout()
        post { resetCrop() }
        invalidate()
    }

    /** 裁剪框重置为图片区域的 80% */
    fun resetCrop() {
        if (bitmapRect.isEmpty) return
        val insetX = bitmapRect.width() * 0.08f
        val insetY = bitmapRect.height() * 0.08f
        cropRect.set(
            bitmapRect.left + insetX,
            bitmapRect.top + insetY,
            bitmapRect.right - insetX,
            bitmapRect.bottom - insetY
        )
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutBitmap()
        resetCrop()
    }

    private fun layoutBitmap() {
        val bmp = bitmap ?: return
        val viewWidth = width.toFloat() - paddingLeft - paddingRight
        val viewHeight = height.toFloat() - paddingTop - paddingBottom
        if (viewWidth <= 0 || viewHeight <= 0) return

        val scale = min(viewWidth / bmp.width, viewHeight / bmp.height)
        val drawWidth = bmp.width * scale
        val drawHeight = bmp.height * scale
        val left = paddingLeft + (viewWidth - drawWidth) / 2f
        val top = paddingTop + (viewHeight - drawHeight) / 2f
        bitmapRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, bitmapRect, imagePaint)

        // 暗化裁剪框以外区域
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)

        // 三分线
        val thirdW = cropRect.width() / 3f
        val thirdH = cropRect.height() / 3f
        for (i in 1..2) {
            val x = cropRect.left + thirdW * i
            val y = cropRect.top + thirdH * i
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, y, cropRect.right, y, gridPaint)
        }

        // 边框与四角把手
        canvas.drawRect(cropRect, borderPaint)
        val len = handleLength
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + len, cropRect.top, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left, cropRect.top + len, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right - len, cropRect.top, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + len, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + len, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left, cropRect.bottom - len, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right - len, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right, cropRect.bottom - len, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = detectMode(x, y)
                lastX = x
                lastY = y
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return false
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                applyDrag(dx, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectMode(x: Float, y: Float): Mode {
        val r = handleTouchRadius
        return when {
            near(x, y, cropRect.left, cropRect.top, r) -> Mode.LEFT_TOP
            near(x, y, cropRect.right, cropRect.top, r) -> Mode.RIGHT_TOP
            near(x, y, cropRect.left, cropRect.bottom, r) -> Mode.LEFT_BOTTOM
            near(x, y, cropRect.right, cropRect.bottom, r) -> Mode.RIGHT_BOTTOM
            cropRect.contains(x, y) -> Mode.MOVE
            else -> Mode.NONE
        }
    }

    private fun near(x: Float, y: Float, targetX: Float, targetY: Float, radius: Float): Boolean =
        abs(x - targetX) <= radius && abs(y - targetY) <= radius

    private fun applyDrag(dx: Float, dy: Float) {
        when (mode) {
            Mode.MOVE -> {
                val newLeft = (cropRect.left + dx).coerceIn(
                    bitmapRect.left,
                    bitmapRect.right - cropRect.width()
                )
                val newTop = (cropRect.top + dy).coerceIn(
                    bitmapRect.top,
                    bitmapRect.bottom - cropRect.height()
                )
                cropRect.offsetTo(newLeft, newTop)
            }
            Mode.LEFT_TOP -> {
                cropRect.left = (cropRect.left + dx).coerceIn(
                    bitmapRect.left,
                    cropRect.right - minSize
                )
                cropRect.top = (cropRect.top + dy).coerceIn(
                    bitmapRect.top,
                    cropRect.bottom - minSize
                )
            }
            Mode.RIGHT_TOP -> {
                cropRect.right = (cropRect.right + dx).coerceIn(
                    cropRect.left + minSize,
                    bitmapRect.right
                )
                cropRect.top = (cropRect.top + dy).coerceIn(
                    bitmapRect.top,
                    cropRect.bottom - minSize
                )
            }
            Mode.LEFT_BOTTOM -> {
                cropRect.left = (cropRect.left + dx).coerceIn(
                    bitmapRect.left,
                    cropRect.right - minSize
                )
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(
                    cropRect.top + minSize,
                    bitmapRect.bottom
                )
            }
            Mode.RIGHT_BOTTOM -> {
                cropRect.right = (cropRect.right + dx).coerceIn(
                    cropRect.left + minSize,
                    bitmapRect.right
                )
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(
                    cropRect.top + minSize,
                    bitmapRect.bottom
                )
            }
            Mode.NONE -> Unit
        }
    }

    /** 按当前裁剪框裁剪出位图 */
    fun cropBitmap(): Bitmap? {
        val src = bitmap ?: return null
        if (bitmapRect.width() <= 0f || cropRect.width() <= 0f) return null
        val scale = src.width / bitmapRect.width()

        val left = ((cropRect.left - bitmapRect.left) * scale).roundToInt()
        val top = ((cropRect.top - bitmapRect.top) * scale).roundToInt()
        val right = ((cropRect.right - bitmapRect.left) * scale).roundToInt()
        val bottom = ((cropRect.bottom - bitmapRect.top) * scale).roundToInt()

        val x = left.coerceIn(0, src.width - 1)
        val y = top.coerceIn(0, src.height - 1)
        val w = (right - x).coerceIn(1, src.width - x)
        val h = (bottom - y).coerceIn(1, src.height - y)

        return runCatching { Bitmap.createBitmap(src, x, y, w, h) }.getOrNull()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
