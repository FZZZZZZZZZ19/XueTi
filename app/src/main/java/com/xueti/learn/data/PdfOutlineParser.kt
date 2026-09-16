package com.xueti.learn.data

import com.xueti.learn.model.TocEntry

/**
 * 教材 PDF 目录（TOC）解析：把扫描出来的"目录页"文本解析成
 * **章 / 节 + 起始页码**，并自动校正「书本印刷页码 ↔ PDF 物理页码」的偏移。
 *
 * 这是「几百页 PDF 怎么对应到每个小节」的关键：
 * 有了页码映射，讲解时只把该小节对应的那几页原文发给模型（而不是整本书或前几页），
 * 既省 token，也避免模型拿目录页当正文。
 */
object PdfOutlineParser {

    /** 解析结果 */
    data class Parsed(
        /** 按目录顺序排列的条目（页码为**物理页**，已加偏移） */
        val entries: List<TocEntry>,
        /** 检测到的目录页（1 起，物理页） */
        val tocPages: List<Int>,
        /** 印刷页码 → 物理页码的偏移 */
        val pageOffset: Int,
        /** 原始（未加偏移）页码，用于调试/展示 */
        val rawPages: List<Int>
    ) {
        val chapterCount: Int get() = entries.count { it.level == 0 }
        val sectionCount: Int get() = entries.count { it.level == 1 }
    }

    /** 「1.1 标题 …… 12」这类带点线引导的目录行 */
    private val dottedLine = Regex("^(.*?)[.·•…\\u2026\\uFF0E]{2,}\\s*(\\d{1,4})\\s*$")

    /** 「1.1 标题    12」用空白分隔的目录行 */
    private val spacedLine = Regex("^(.*?)\\s{2,}(\\d{1,4})\\s*$")

    /** 标题编号：1 / 1.2 / 1.2.3 */
    private val numberedTitle = Regex("^\\s*(\\d+(?:\\.\\d+)*)\\s*[.、,，]?\\s*(.+)$")

    /** 第 X 章 / 第 X 节 */
    private val chapterCn = Regex("^\\s*第\\s*[0-9一二三四五六七八九十百]+\\s*[章篇部分]")

    /** 目录页判定 */
    private val tocKeyword = Regex("目\\s*录|contents", RegexOption.IGNORE_CASE)

    /** 判断哪些页是目录页（只在前 12% 且不超过前 40 页里找） */
    fun detectTocPages(pages: List<String>): List<Int> {
        if (pages.isEmpty()) return emptyList()
        val limit = minOf(pages.size, maxOf(6, (pages.size * 0.12).toInt()), 40)
        val result = mutableListOf<Int>()
        for (index in 0 until limit) {
            val head = pages[index].take(600)
            val hasKeyword = tocKeyword.containsMatchIn(head)
            val dottedCount = pages[index].lineSequence()
                .count { dottedLine.matches(it.trim()) && it.trim().length > 4 }
            if (hasKeyword || dottedCount >= 3) result.add(index + 1)
        }
        return result
    }

    /**
     * 解析目录。返回 null 表示没解析出可用目录（例如扫描版 PDF 没有文字层）。
     */
    fun parse(pages: List<String>, maxTocPages: Int = 12): Parsed? {
        if (pages.isEmpty()) return null
        val tocPages = detectTocPages(pages)
        if (tocPages.isEmpty()) return null

        val raw = mutableListOf<Pair<String, Int>>() // title -> printed page
        // 目录可能跨好几页，取连续的目录页
        val startIndex = tocPages.first() - 1
        var used = 0
        for (index in startIndex until pages.size) {
            if (used >= maxTocPages) break
            // 目录页通常是连续的：遇到既没有点线也不是目录关键字的页就停
            val page = pages[index]
            val looksToc = tocPages.contains(index + 1) ||
                page.lineSequence().count { dottedLine.matches(it.trim()) } >= 2
            if (!looksToc && used > 0) break
            page.split('\n').forEach { line ->
                parseLine(line)?.let { raw.add(it) }
            }
            used++
        }
        if (raw.size < 3) return null

        // 去重（同一标题多次出现时保留第一次）
        val cleaned = mutableListOf<Pair<String, Int>>()
        raw.forEach { pair ->
            if (cleaned.none { it.first == pair.first }) cleaned.add(pair)
        }

        val offset = detectPageOffset(pages, tocPages, cleaned)
        val entries = cleaned.map { (title, printed) ->
            TocEntry(
                title = title,
                page = (printed + offset).coerceIn(1, pages.size),
                level = levelOf(title)
            )
        }.sortedBy { it.page }

        if (entries.isEmpty()) return null
        return Parsed(
            entries = entries,
            tocPages = tocPages,
            pageOffset = offset,
            rawPages = cleaned.map { it.second }
        )
    }

    /** 解析一行目录，得到「标题 + 印刷页码」 */
    private fun parseLine(line: String): Pair<String, Int>? {
        val text = line.trim()
        if (text.length < 4) return null
        val match = dottedLine.find(text) ?: spacedLine.find(text) ?: return null
        val title = cleanTitle(match.groupValues[1])
        val page = match.groupValues[2].toIntOrNull() ?: return null
        if (title.length < 2 || page <= 0 || page > 5000) return null
        // 排除"目录 1"这类噪声与纯数字
        if (title.all { it.isDigit() }) return null
        // 页码不可能比标题行更长（避免把正文里的句子误判）
        if (title.length > 60) return null
        return title to page
    }

    /** 层级：第 X 章 / 无编号 → 0；1.2 这类多段编号 → 1 */
    private fun levelOf(title: String): Int {
        if (chapterCn.containsMatchIn(title)) return 0
        val number = numberedTitle.find(title)?.groupValues?.get(1) ?: return 1
        return if (number.contains('.')) 1 else 0
    }

    /**
     * 估计「印刷页码 → 物理页码」的偏移：
     * 在前几章的标题里找一个能在正文页中定位到的，用 物理页 - 印刷页 作为偏移。
     */
    private fun detectPageOffset(
        pages: List<String>,
        tocPages: List<Int>,
        entries: List<Pair<String, Int>>
    ): Int {
        val searchFrom = (tocPages.maxOrNull() ?: 1) // 0-based 起点
        val candidates = entries.take(8)
        val votes = mutableListOf<Int>()
        for ((title, printed) in candidates) {
            val key = normalize(title)
            if (key.length < 3) continue
            for (index in searchFrom until pages.size) {
                val page = pages[index]
                if (isTocLike(page)) continue
                if (normalize(page).contains(key)) {
                    val physical = index + 1
                    val delta = physical - printed
                    if (delta >= 0) votes.add(delta)
                    break
                }
            }
        }
        if (votes.isEmpty()) {
            // 定位不到：按"目录页数"粗略估计前置页数
            return (tocPages.maxOrNull() ?: 0)
        }
        // 取众数，避免个别标题定位错误影响全局
        return votes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
    }

    private fun isTocLike(page: String): Boolean =
        page.lineSequence().count { dottedLine.matches(it.trim()) } >= 3

    /**
     * 标题清洗：只去掉 Markdown 记号、点线与首尾杂符，
     * **保留「第 X 章」「1.1」这类编号**——层级判断、展示与和正文匹配都依赖它。
     */
    fun cleanTitle(raw: String): String = raw
        .replace(Regex("^[\\s\\-–—·•*#>]+"), "")
        .replace(Regex("[\\s\\-–—·•*#]+$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalize(text: String): String =
        text.replace(Regex("\\s+"), "").replace(Regex("[·•.…\\-—_]"), "").lowercase()

    /**
     * 把解析结果整理成「章 → 节 + 页范围」。
     *
     * - 大章条目只作为章标题，其下的小节各自成为一节（章的引言页不单独成节）
     * - 如果某个大章下面没有任何小节，就把它自己当成一节，避免内容丢失
     *
     * @param pageCount PDF 总页数（用于最后一节的结束页）
     */
    fun buildOutline(
        parsed: Parsed,
        pageCount: Int
    ): List<Pair<String, List<Triple<String, Int, Int>>>> {
        if (parsed.entries.isEmpty()) return emptyList()

        // 先给每个条目算好页范围：结束页 = 下一条目起始页 - 1
        val ranged = parsed.entries.mapIndexed { index, entry ->
            val nextStart = parsed.entries.getOrNull(index + 1)?.page ?: (pageCount + 1)
            val end = (nextStart - 1).coerceAtLeast(entry.page).coerceAtMost(pageCount)
            Triple(entry.title, entry.page, end) to entry.level
        }

        val groups = mutableListOf<Pair<String, MutableList<Triple<String, Int, Int>>>>()
        var chapterTitle: String? = null
        var chapterRange: Triple<String, Int, Int>? = null
        var chapterSections: MutableList<Triple<String, Int, Int>>? = null

        fun flush() {
            val title = chapterTitle ?: return
            val list = chapterSections ?: return
            // 章下没有小节 → 把章本身当成一节
            val sections = if (list.isEmpty() && chapterRange != null) {
                mutableListOf(chapterRange!!)
            } else {
                list
            }
            if (sections.isNotEmpty()) groups.add(title to sections)
            chapterSections = null
        }

        ranged.forEach { (range, level) ->
            if (level == 0) {
                flush()
                chapterTitle = range.first
                chapterRange = range
                chapterSections = mutableListOf()
            } else {
                if (chapterSections == null) {
                    chapterTitle = "正文"
                    chapterRange = null
                    chapterSections = mutableListOf()
                }
                chapterSections?.add(range)
            }
        }
        flush()

        // 只解析出一层（没有章标记）时，按编号首段分组
        if (groups.size == 1 && groups[0].second.size > 1) {
            val single = groups[0].second
            val byPrefix = single.groupBy { item ->
                numberedTitle.find(item.first)?.groupValues?.get(1)?.substringBefore('.') ?: ""
            }
            if (byPrefix.keys.count { it.isNotBlank() } >= 2) {
                return byPrefix.entries
                    .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                    .map { (prefix, items) -> "第 $prefix 章" to items }
            }
        }
        return groups.map { it.first to it.second.toList() }
    }
}
