package com.xueti.learn.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 教材 PDF 原文件管理（v2.04）。
 *
 * 之前只把 PDF 的文字抽出来存进 `pdftext_<bookId>.json`，没有保留 PDF 本身，
 * 所以既不能预览、也没法翻页找页码。现在导入时会把原文件复制一份到 App 私有目录：
 * `filesDir/pdf_<bookId>.pdf`，用它做**页面预览**（找起始页）与后续重排文字。
 */
object PdfFileStore {

    /** 导入阶段暂存（书还没创建时） */
    private fun stagingFile(context: Context) = File(context.cacheDir, "pdf_import.pdf")

    fun file(context: Context, bookId: String): File = File(context.filesDir, "pdf_$bookId.pdf")

    fun hasFile(context: Context, bookId: String): Boolean {
        val f = file(context, bookId)
        return f.exists() && f.length() > 0
    }

    fun sizeBytes(context: Context, bookId: String): Long =
        file(context, bookId).takeIf { it.exists() }?.length() ?: 0L

    /** 把用户选中的 PDF 复制到缓存目录（导入阶段用），返回是否成功 */
    fun stageFromUri(context: Context, uri: Uri): File? = runCatching {
        val target = stagingFile(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (target.length() > 0) target else null
    }.getOrNull()

    fun stagedFile(context: Context): File? =
        stagingFile(context).takeIf { it.exists() && it.length() > 0 }

    fun clearStaged(context: Context) {
        runCatching { stagingFile(context).delete() }
    }

    /** 书本创建完成后，把暂存的 PDF 归位成 `pdf_<bookId>.pdf` */
    fun commitStaged(context: Context, bookId: String): Boolean {
        val staged = stagedFile(context) ?: return false
        val target = file(context, bookId)
        val ok = runCatching {
            if (target.exists()) target.delete()
            staged.renameTo(target) || runCatching {
                staged.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                staged.delete()
                true
            }.getOrDefault(false)
        }.getOrDefault(false)
        return ok && target.exists() && target.length() > 0
    }

    fun delete(context: Context, bookId: String) {
        runCatching { file(context, bookId).delete() }
    }

    /** 给已存在的书补上 / 更换 PDF 原文件（v2.05：老书也能后补 PDF） */
    fun replaceFromUri(context: Context, bookId: String, uri: Uri): Boolean = runCatching {
        val target = file(context, bookId)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        target.exists() && target.length() > 0
    }.getOrDefault(false)
}

/**
 * 给一本书导入 / 更换教材 PDF（v2.05）。
 *
 * 抽文字与保存原文件都在这里做，书本目录页与「划分章节页数」页共用：
 * 原本没有 PDF 的书也能后来补上，补完再手动划分页范围。
 */
object PdfBookImporter {

    data class Imported(val pageCount: Int, val charCount: Int, val hasText: Boolean, val fileSaved: Boolean)

    suspend fun import(context: Context, store: TextbookStore, bookId: String, uri: Uri): Result<Imported> =
        runCatching {
            val extracted = PdfTextExtractor.extract(context, uri).getOrThrow()
            PdfTextExtractor.savePages(context, bookId, extracted.pages)
            val fileSaved = withContext(Dispatchers.IO) {
                PdfFileStore.replaceFromUri(context, bookId, uri)
            }
            val name = queryDisplayName(context, uri) ?: "教材.pdf"
            val book = store.get(bookId) ?: error("书本不存在")
            store.upsert(
                book.copy(
                    sourcePdfName = name,
                    sourcePdfPages = extracted.pageCount
                )
            )
            Imported(
                pageCount = extracted.pageCount,
                charCount = extracted.charCount,
                hasText = extracted.hasText,
                fileSaved = fileSaved
            )
        }

    fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
