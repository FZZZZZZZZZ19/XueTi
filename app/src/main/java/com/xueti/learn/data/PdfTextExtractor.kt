package com.xueti.learn.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 教材 PDF 文本抽取（本地完成，不上传文件）。
 *
 * 全书学习支持上传教材 PDF：把 PDF 的**原文**作为 AI 生成目录 / 讲解的唯一依据，
 * 这样生成的内容来自你上传的教材，而不是模型内训记忆，能显著减少幻觉。
 */
object PdfTextExtractor {

    /** 单个 PDF 最多解析多少页（防止超大文件卡住） */
    private const val MAX_PAGES = 800

    /** 抽取结果最多保留多少字符 */
    private const val MAX_CHARS = 2_000_000

    data class Extracted(
        val pages: List<String>,
        val pageCount: Int,
        val charCount: Int
    ) {
        val hasText: Boolean get() = charCount > 200
    }

    /** 读取 PDF 并逐页抽取文本（在 IO 线程执行） */
    suspend fun extract(context: Context, uri: Uri): Result<Extracted> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: error("无法打开该文件")
                stream.use { input ->
                    PDDocument.load(input).use { document ->
                        val total = document.numberOfPages
                        val limit = minOf(total, MAX_PAGES)
                        val pages = ArrayList<String>(limit)
                        val stripper = PDFTextStripper()
                        var chars = 0
                        for (page in 1..limit) {
                            val text = runCatching {
                                stripper.startPage = page
                                stripper.endPage = page
                                stripper.getText(document)
                            }.getOrDefault("")
                            val cleaned = cleanUp(text)
                            pages.add(cleaned)
                            chars += cleaned.length
                            if (chars > MAX_CHARS) break
                        }
                        Extracted(pages = pages, pageCount = total, charCount = chars)
                    }
                }
            }
        }

    /**
     * 生成目录用的「原文摘要」：优先取书前部的目录页（含「目录 / Contents」的页与其后 3 页），
     * 不足时再补书首若干页，保证 AI 能拿到真实的章节编号。
     */
    fun outlineDigest(pages: List<String>, maxChars: Int = 18_000): String {
        if (pages.isEmpty()) return ""
        val tocIndex = pages.indexOfFirst { page ->
            val head = page.take(400)
            head.contains("目录") || head.contains("目 录") ||
                head.contains("Contents", ignoreCase = true) ||
                head.contains("CONTENTS")
        }
        val picked = StringBuilder()
        val used = mutableSetOf<Int>()

        fun appendPage(index: Int) {
            if (index !in pages.indices || !used.add(index)) return
            val text = pages[index]
            if (text.isBlank()) return
            if (picked.length + text.length > maxChars) return
            picked.append("【第 ").append(index + 1).append(" 页】\n").append(text).append("\n\n")
        }

        if (tocIndex >= 0) {
            for (i in tocIndex..minOf(tocIndex + 3, pages.lastIndex)) appendPage(i)
        }
        // 再补书首部分（前言/章节起页），仍不足则从头顺序补
        for (i in pages.indices) {
            if (picked.length >= maxChars) break
            appendPage(i)
        }
        return picked.toString().trim()
    }

    /**
     * 生成小节讲解用的原文：按小节标题在 PDF 里定位，取该处开始的一段原文；
     * 找不到标题时退回按章节标题定位；仍找不到则返回空（由调用方决定是否放弃接地）。
     */
    fun sectionExcerpt(
        pages: List<String>,
        sectionTitle: String,
        maxChars: Int = 9_000
    ): String {
        if (pages.isEmpty()) return ""
        val candidates = buildList {
            add(sectionTitle)
            // 「1.1 xxx」这类编号标题，用编号与标题正文分别匹配
            val noSpace = sectionTitle.replace(" ", "")
            if (noSpace != sectionTitle) add(noSpace)
            sectionTitle.split(' ', '　').lastOrNull()?.takeIf { it.length >= 4 }?.let { add(it) }
        }.filter { it.length >= 2 }

        for (key in candidates) {
            val pageIndex = pages.indexOfFirst { it.contains(key) }
            if (pageIndex >= 0) {
                val start = pages[pageIndex].indexOf(key).coerceAtLeast(0)
                val builder = StringBuilder()
                builder.append(pages[pageIndex].substring(start))
                var i = pageIndex + 1
                while (i < pages.size && builder.length < maxChars) {
                    builder.append("\n").append(pages[i])
                    i++
                }
                return builder.toString().take(maxChars).trim()
            }
        }
        return ""
    }

    /** 去掉 PDF 里常见的页眉页脚噪声，压缩空行 */
    private fun cleanUp(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.replace("\r", "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { line ->
                // 纯页码
                line.length <= 6 && line.all { it.isDigit() || it == '-' || it == '—' }
            }
        return lines.joinToString("\n")
    }

    // ---------------- 原文的本地存储（按书保存，便于反复生成） ----------------

    private fun textFile(context: Context, bookId: String) =
        java.io.File(context.filesDir, "pdftext_$bookId.json")

    fun savePages(context: Context, bookId: String, pages: List<String>) {
        runCatching {
            val array = JSONArray()
            pages.forEach { array.put(it) }
            textFile(context, bookId).writeText(array.toString())
        }
    }

    fun loadPages(context: Context, bookId: String): List<String> {
        val file = textFile(context, bookId)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { array.optString(it) }
        }.getOrDefault(emptyList())
    }

    fun hasPages(context: Context, bookId: String): Boolean =
        textFile(context, bookId).exists()

    fun deletePages(context: Context, bookId: String) {
        runCatching { textFile(context, bookId).delete() }
    }
}
