package com.xueti.learn.model

import org.json.JSONArray
import org.json.JSONObject

/** AI 讲解语言风格 */
enum class StudyStyle(val key: String, val label: String, val prompt: String) {
    PLAIN(
        "plain",
        "直白易懂",
        "用大白话解释，多打比方、多举生活例子，避免堆砌术语；句子简短，重点用「一句话总结」收尾。"
    ),
    RIGOROUS(
        "rigorous",
        "严谨全面",
        "术语准确、定义完整，给出条件与边界情况、必要推导步骤与常见反例；覆盖全面不遗漏要点。"
    );

    companion object {
        fun fromKey(key: String?): StudyStyle =
            entries.firstOrNull { it.key == key } ?: PLAIN
    }
}

/** 小章（节） */
data class Section(
    val id: String,
    val title: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
    }

    companion object {
        fun fromJson(o: JSONObject): Section = Section(
            id = o.optString("id"),
            title = o.optString("title")
        )
    }
}

/** 大章 */
data class Chapter(
    val id: String,
    val title: String,
    val sections: List<Section>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        val arr = JSONArray()
        sections.forEach { arr.put(it.toJson()) }
        put("sections", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Chapter {
            val arr = o.optJSONArray("sections") ?: JSONArray()
            return Chapter(
                id = o.optString("id"),
                title = o.optString("title"),
                sections = (0 until arr.length()).map { Section.fromJson(arr.getJSONObject(it)) }
            )
        }
    }
}

/** 一道例题（从模块三拆出来的单题，可单独加入「精选题库」） */
data class ExampleItem(
    val id: String,
    val title: String,
    val question: String,
    val solution: String,
    /** 用户自己添加或编辑过的题（v2.00） */
    val userAdded: Boolean = false
) {
    /** 用于去重与展示的纯文本（去掉 Markdown 记号） */
    val plainText: String get() = "$question\n$solution"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("question", question)
        put("solution", solution)
        put("userAdded", userAdded)
    }

    companion object {
        fun fromJson(o: JSONObject): ExampleItem = ExampleItem(
            id = o.optString("id"),
            title = o.optString("title"),
            question = o.optString("question"),
            solution = o.optString("solution"),
            userAdded = o.optBoolean("userAdded", false)
        )
    }
}

/** 一个小章 AI 生成的三模块内容 */
data class SectionContent(
    val knowledge: String,
    val formulas: String,
    val examples: String,
    val styleKey: String,
    val generatedAt: Long,
    /** 例题拆分成单题（v1.92）：每道题可单独加入精选题库 */
    val exampleItems: List<ExampleItem> = emptyList(),
    /** 用户手动增删改过（v2.00）：重新生成会覆盖，需先确认 */
    val customized: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("knowledge", knowledge)
        put("formulas", formulas)
        put("examples", examples)
        put("styleKey", styleKey)
        put("generatedAt", generatedAt)
        put("customized", customized)
        val arr = JSONArray()
        exampleItems.forEach { arr.put(it.toJson()) }
        put("exampleItems", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): SectionContent {
            val arr = o.optJSONArray("exampleItems") ?: JSONArray()
            return SectionContent(
                knowledge = o.optString("knowledge"),
                formulas = o.optString("formulas"),
                examples = o.optString("examples"),
                styleKey = o.optString("styleKey", StudyStyle.PLAIN.key),
                generatedAt = o.optLong("generatedAt", System.currentTimeMillis()),
                exampleItems = (0 until arr.length()).mapNotNull { index ->
                    arr.optJSONObject(index)?.let { ExampleItem.fromJson(it) }
                },
                customized = o.optBoolean("customized", false)
            )
        }
    }
}

/** 某个小节在教材 PDF 中的**物理页范围**（1 起，含首含尾） */
data class PageRange(val start: Int, val end: Int) {
    val isValid: Boolean get() = start >= 1 && end >= start
    val label: String get() = if (start == end) "p.$start" else "p.$start–$end"
    val pageCount: Int get() = if (isValid) end - start + 1 else 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("start", start)
        put("end", end)
    }

    companion object {
        fun fromJson(o: JSONObject): PageRange = PageRange(
            start = o.optInt("start", 0),
            end = o.optInt("end", 0)
        )
    }
}

/** 目录解析出的一个条目（章 / 节 + 起始页） */
data class TocEntry(val title: String, val page: Int, val level: Int)

/** 一本书（目录 + 已生成的小章内容） */
data class Textbook(
    val id: String,
    val title: String,
    val publisher: String,
    val edition: String,
    val styleKey: String,
    val createdAt: Long,
    val chapters: List<Chapter>,
    val contents: Map<String, SectionContent> = emptyMap(),
    /** 已上传教材 PDF 的文件名（空表示没有 PDF，生成时靠模型知识） */
    val sourcePdfName: String = "",
    /** 该 PDF 的页数 */
    val sourcePdfPages: Int = 0,
    /** 小节标题 → PDF 物理页范围（v2.02：讲解只取对应页的原文） */
    val pageMap: Map<String, PageRange> = emptyMap()
) {
    val chapterCount: Int get() = chapters.size
    val sectionCount: Int get() = chapters.sumOf { it.sections.size }
    val studiedCount: Int get() = contents.size
    val hasPdf: Boolean get() = sourcePdfName.isNotBlank()
    val mappedCount: Int get() = pageMap.size

    fun pageRangeOf(sectionTitle: String): PageRange? = pageMap[sectionTitle]

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("publisher", publisher)
        put("edition", edition)
        put("styleKey", styleKey)
        put("createdAt", createdAt)
        put("sourcePdfName", sourcePdfName)
        put("sourcePdfPages", sourcePdfPages)
        val arr = JSONArray()
        chapters.forEach { arr.put(it.toJson()) }
        put("chapters", arr)
        val contentObj = JSONObject()
        contents.forEach { (key, value) -> contentObj.put(key, value.toJson()) }
        put("contents", contentObj)
        val pageObj = JSONObject()
        pageMap.forEach { (key, value) -> pageObj.put(key, value.toJson()) }
        put("pageMap", pageObj)
    }

    companion object {
        fun fromJson(o: JSONObject): Textbook {
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            val contentObj = o.optJSONObject("contents") ?: JSONObject()
            val contents = mutableMapOf<String, SectionContent>()
            contentObj.keys().forEach { key ->
                contentObj.optJSONObject(key)?.let { contents[key] = SectionContent.fromJson(it) }
            }
            val pageObj = o.optJSONObject("pageMap") ?: JSONObject()
            val pageMap = mutableMapOf<String, PageRange>()
            pageObj.keys().forEach { key ->
                pageObj.optJSONObject(key)?.let {
                    val range = PageRange.fromJson(it)
                    if (range.isValid) pageMap[key] = range
                }
            }
            return Textbook(
                id = o.optString("id"),
                title = o.optString("title"),
                publisher = o.optString("publisher"),
                edition = o.optString("edition"),
                styleKey = o.optString("styleKey", StudyStyle.PLAIN.key),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                chapters = (0 until arr.length()).map { Chapter.fromJson(arr.getJSONObject(it)) },
                contents = contents,
                sourcePdfName = o.optString("sourcePdfName"),
                sourcePdfPages = o.optInt("sourcePdfPages", 0),
                pageMap = pageMap
            )
        }
    }
}
