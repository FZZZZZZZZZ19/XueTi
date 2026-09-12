package com.xueti.learn.util

import com.xueti.learn.model.ExampleItem
import java.util.UUID

/**
 * 把 AI 输出的「例题」整段文本拆成**单道题**，便于用户逐题点「+」加入精选题库。
 *
 * 兼容多种模型输出习惯（按优先级尝试）：
 * 1. `### 例题 1 ...` / `**例题1**` / `例 1` / `Example 1` 这类小标题
 * 2. `**题目**` / `题目：` 这类分题标记
 * 3. `1. ` / `1、` / `（1）` 这类编号列表
 * 4. 都识别不到时，整段作为一道题
 */
object ExampleParser {

    private val headerRegex = Regex(
        "^\\s*(?:#{1,4}\\s*)?(?:\\*\\*)?\\s*(?:例题|例|习题|练习|Example)\\s*([0-9０-９一二三四五六七八九十]+)?[：:.、\\s].*$",
        RegexOption.IGNORE_CASE
    )

    private val plainHeaderRegex = Regex(
        "^\\s*(?:#{1,4}\\s*)?(?:\\*\\*)?\\s*(?:例题|Example)\\s*([0-9０-９一二三四五六七八九十]+)?\\s*(?:\\*\\*)?\\s*$",
        RegexOption.IGNORE_CASE
    )

    private val questionRegex = Regex(
        "^\\s*(?:#{1,6}\\s*)?(?:\\*\\*)?\\s*(?:题目|问题|题干)\\s*(?:\\*\\*)?\\s*[：:]?\\s*$"
    )

    private val numberedRegex = Regex("^\\s*(?:\\d{1,2}|[（(]\\d{1,2}[)）])[.、：:)\\s]\\s*\\S.*$")

    /** 「解答 / 解析 / Solution」这类明确标记（可以带后面的内容） */
    private val longSolutionRegex = Regex(
        "^\\s*(?:#{1,6}\\s*)?(?:\\*\\*)?\\s*(?:解答|解析|答案解析|答案|Solution|Answer)\\s*(?:\\*\\*)?\\s*[：:]?"
    )

    /** 「解 / 答」这类太短太常见，必须带冒号才算解答标记（如「解：x = 1」） */
    private val shortSolutionRegex = Regex(
        "^\\s*(?:\\*\\*)?\\s*(?:解|答)\\s*(?:\\*\\*)?\\s*[：:]\\s*.*$"
    )

    fun split(raw: String): List<ExampleItem> {
        val text = raw.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return emptyList()

        // 1) 例题小标题（标题行本身不是题干内容，丢掉）
        val byHeader = splitBy(text) { line ->
            val trimmed = line.trim()
            when {
                plainHeaderRegex.matches(trimmed) -> Marker(cleanTitle(trimmed), keepLine = false)
                headerRegex.matches(trimmed) && trimmed.length <= 60 ->
                    Marker(cleanTitle(trimmed), keepLine = false)
                else -> null
            }
        }
        if (byHeader.size >= 2) return build(byHeader)

        // 2) 「题目」标记（标记行本身可能带内容，保留在题干里）
        val byQuestion = splitBy(text) { line ->
            if (questionRegex.matches(line)) Marker("题目", keepLine = true) else null
        }
        if (byQuestion.size >= 2) return build(byQuestion)

        // 3) 编号列表（编号行就是题干开头，必须保留）
        val byNumber = splitBy(text) { line ->
            val trimmed = line.trim()
            if (numberedRegex.matches(trimmed) && trimmed.length > 8) {
                Marker("题目", keepLine = true)
            } else {
                null
            }
        }
        if (byNumber.size >= 2) return build(byNumber)

        // 4) 兜底：整段一道题
        return listOf(
            ExampleItem(
                id = UUID.randomUUID().toString(),
                title = "",
                question = text,
                solution = ""
            )
        )
    }

    /** 分题标记：[title] 为标题；[keepLine] 表示该行属于题干内容（不能被丢掉） */
    private data class Marker(val title: String, val keepLine: Boolean)

    private fun splitBy(text: String, marker: (String) -> Marker?): List<Pair<String?, String>> {
        val result = mutableListOf<Pair<String?, String>>()
        val buffer = StringBuilder()
        var title: String? = null
        text.split('\n').forEach { line ->
            val hit = marker(line)
            if (hit != null) {
                if (buffer.isNotBlank()) result.add(title to buffer.toString().trim())
                buffer.setLength(0)
                title = hit.title
                if (hit.keepLine) buffer.append(line).append('\n')
            } else {
                buffer.append(line).append('\n')
            }
        }
        if (buffer.isNotBlank()) result.add(title to buffer.toString().trim())
        return result
    }

    private fun build(pairs: List<Pair<String?, String>>): List<ExampleItem> =
        pairs.filter { it.second.isNotBlank() }.mapIndexed { index, (title, body) ->
            val heading = title?.takeIf { it.isNotBlank() && it != "题目" }
            val parts = splitQuestionAndSolution(body)
            ExampleItem(
                id = UUID.randomUUID().toString(),
                title = heading ?: "例题 ${index + 1}",
                question = parts.first,
                solution = parts.second
            )
        }

    /**
     * 拆「题目」与「解答」：遇到「解答 / 解析 / 解 / Solution」标记就切开；
     * 找不到则把整段当题目（解答留空）。
     */
    private fun splitQuestionAndSolution(body: String): Pair<String, String> {
        val lines = body.split('\n')
        val index = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@indexOfFirst false
            longSolutionRegex.containsMatchIn(trimmed) || shortSolutionRegex.matches(trimmed)
        }
        if (index <= 0) return body.trim() to ""
        val question = lines.subList(0, index).joinToString("\n").trim()
        val solution = lines.subList(index, lines.size).joinToString("\n").trim()
        return question to solution
    }

    /** 去掉 Markdown 记号，让小标题干净可读 */
    private fun cleanTitle(line: String): String = line
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace("**", "")
        .trim()
}
