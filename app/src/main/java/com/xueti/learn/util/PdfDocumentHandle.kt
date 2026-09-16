package com.xueti.learn.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.xueti.learn.data.PdfFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * PDF 页面渲染（用系统自带的 [PdfRenderer]，不需要额外依赖）。
 *
 * 用途：全书学习里**预览教材 PDF**，让用户直接翻页找到某小节的起始页，
 * 再手动填进页范围——不再依赖自动定位（自动定位在扫描版/复杂版式上本来就容易失败）。
 *
 * 注意：只在页面可见时按需渲染当前页，渲染在 IO 线程执行，用完 [close]。
 */
class PdfDocumentHandle private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    /** 渲染第 [pageIndex]（0 起）页，宽度按 [targetWidthPx] 等比缩放 */
    fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        if (pageIndex !in 0 until pageCount) return null
        return runCatching {
            renderer.openPage(pageIndex).use { page ->
                val width = targetWidthPx.coerceAtLeast(240).coerceAtMost(MAX_WIDTH_PX)
                val ratio = page.height.toFloat() / page.width.toFloat()
                val height = (width * ratio).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // PDF 页面是白底，先铺白再渲染（否则透明区域在深色主题下会发黑）
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        private const val MAX_WIDTH_PX = 2400

        /** 打开一本书的 PDF；没有保存文件或打开失败时返回 null */
        suspend fun open(context: Context, bookId: String): PdfDocumentHandle? =
            withContext(Dispatchers.IO) {
                if (!PdfFileStore.hasFile(context, bookId)) return@withContext null
                runCatching {
                    val descriptor = ParcelFileDescriptor.open(
                        PdfFileStore.file(context, bookId),
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    val renderer = PdfRenderer(descriptor)
                    PdfDocumentHandle(descriptor, renderer)
                }.getOrElse { null }
            }
    }
}
