package com.xueti.learn.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.xueti.learn.model.PageRange
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

    /**
     * 取指定页范围的原文（v2.02：这是「对应章节对应页数讲解」的核心）。
     *
     * @param range    物理页范围（1 起，含首含尾）
     * @param maxChars 最多取多少字符（超长时截断并标注）
     */
    fun pagesInRange(pages: List<String>, range: PageRange, maxChars: Int = 12_000): String {
        if (pages.isEmpty() || !range.isValid) return ""
        val start = range.start.coerceIn(1, pages.size)
        val end = range.end.coerceIn(start, pages.size)
        val builder = StringBuilder()
        var truncated = false
        for (page in start..end) {
            val text = pages.getOrNull(page - 1).orEmpty()
            if (text.isBlank()) continue
            val header = "【第 $page 页】\n"
            if (builder.length + header.length + text.length > maxChars) {
                val remain = maxChars - builder.length - header.length
                if (remain > 200) {
                    builder.append(header).append(text.take(remain)).append("…\n")
                }
                truncated = true
                break
            }
            builder.append(header).append(text).append("\n\n")
        }
        val result = builder.toString().trim()
        return if (truncated) "$result\n（原文较长，以上为该范围内的前部分内容）" else result
    }

    /**
     * 生成「页码索引」：每页一行「第 N 页 | 该页开头若干字」。
     *
     * 几百页的 PDF 也能一次交给模型（约 2~3 万字符），
     * 让它据此判断每个小节从**哪一页**开始——比投喂全文省几十倍 token。
     */
    fun pageIndexDigest(pages: List<String>, maxChars: Int = 30_000): String {
        if (pages.isEmpty()) return ""
        val snippet = (maxChars / pages.size).coerceIn(24, 80)
        val builder = StringBuilder()
        for ((index, text) in pages.withIndex()) {
            val head = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(snippet)
            if (head.isEmpty()) continue
            val line = "第 ${index + 1} 页 | $head\n"
            if (builder.length + line.length > maxChars) break
            builder.append(line)
        }
        return builder.toString().trim()
    }

    /**
     * 在正文里定位小节标题所在的物理页（**跳过目录页**，避免命中的是目录而不是正文）。
     *
     * @param skipPages 目录页（1 起），这些页不参与匹配
     */
    fun findSectionStart(
        pages: List<String>,
        sectionTitle: String,
        skipPages: Set<Int> = emptySet()
    ): Int? {
        if (pages.isEmpty()) return null
        val keys = buildList {
            add(sectionTitle)
            val noSpace = sectionTitle.replace(" ", "")
            if (noSpace != sectionTitle) add(noSpace)
            sectionTitle.split(' ', '　').lastOrNull()?.takeIf { it.length >= 4 }?.let { add(it) }
        }.filter { it.length >= 2 }

        for (key in keys) {
            for ((index, text) in pages.withIndex()) {
                val page = index + 1
                if (skipPages.contains(page)) continue
                if (isTocLike(text)) continue
                if (text.contains(key) && !isTocLine(text, key)) return page
            }
        }
        return null
    }

    private val tocLineRegex = Regex("[.·•…]{2,}\\s*\\d{1,4}\\s*$")

    /** 表格型目录（标题与页码之间只有空白，没有点线） */
    private val tocSpacedRegex = Regex("\\S.{0,60}\\s{2,}\\d{1,4}\\s*$")

    /** 一页里出现 2 行以上「标题 + 页码」就当作目录页 */
    private fun isTocLike(page: String): Boolean {
        var hits = 0
        page.lineSequence().forEach { line ->
            val text = line.trim()
            if (text.length < 6) return@forEach
            if (tocLineRegex.containsMatchIn(text) || tocSpacedRegex.containsMatchIn(text)) hits++
        }
        return hits >= 2
    }

    private fun isTocLine(page: String, key: String): Boolean =
        page.lineSequence().any { line ->
            val text = line.trim()
            line.contains(key) &&
                (tocLineRegex.containsMatchIn(text) || tocSpacedRegex.containsMatchIn(text))
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
