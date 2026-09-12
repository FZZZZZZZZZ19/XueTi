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

/** 一个小章 AI 生成的三模块内容 */
data class SectionContent(
    val knowledge: String,
    val formulas: String,
    val examples: String,
    val styleKey: String,
    val generatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("knowledge", knowledge)
        put("formulas", formulas)
        put("examples", examples)
        put("styleKey", styleKey)
        put("generatedAt", generatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SectionContent = SectionContent(
            knowledge = o.optString("knowledge"),
            formulas = o.optString("formulas"),
            examples = o.optString("examples"),
            styleKey = o.optString("styleKey", StudyStyle.PLAIN.key),
            generatedAt = o.optLong("generatedAt", System.currentTimeMillis())
        )
    }
}

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
    val sourcePdfPages: Int = 0
) {
    val chapterCount: Int get() = chapters.size
    val sectionCount: Int get() = chapters.sumOf { it.sections.size }
    val studiedCount: Int get() = contents.size
    val hasPdf: Boolean get() = sourcePdfName.isNotBlank()

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
    }

    companion object {
        fun fromJson(o: JSONObject): Textbook {
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            val contentObj = o.optJSONObject("contents") ?: JSONObject()
            val contents = mutableMapOf<String, SectionContent>()
            contentObj.keys().forEach { key ->
                contentObj.optJSONObject(key)?.let { contents[key] = SectionContent.fromJson(it) }
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
                sourcePdfPages = o.optInt("sourcePdfPages", 0)
            )
        }
    }
}
