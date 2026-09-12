package com.xueti.learn.data

import android.content.Context
import com.xueti.learn.model.ExampleItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 精选题库里的一道题（按书本 → 章 → 小节归类）
 *
 * @param bookId / bookTitle   来自哪本书
 * @param chapterTitle / sectionTitle 来自哪一章、哪一小节
 * @param title    题目标题（如「例题 1 求极限」）
 * @param question 题干
 * @param solution 分步解答
 */
data class CuratedQuestion(
    val id: String,
    val bookId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val sectionTitle: String,
    val title: String,
    val question: String,
    val solution: String,
    val addedAt: Long,
    val mastered: Boolean = false,
    val reviewedAt: Long = 0L
)

/**
 * 精选题库：用户在小节例题里点「+」收藏的题目，按书目 / 章节归类保存。
 *
 * 存储：`filesDir/curated.json`
 */
class CuratedStore(context: Context) {

    private val file = File(context.filesDir, "curated.json")

    /** 全部精选题目（按加入时间倒序） */
    @Synchronized
    fun all(): List<CuratedQuestion> = load().sortedByDescending { it.addedAt }

    @Synchronized
    fun count(): Int = load().size

    @Synchronized
    fun pendingCount(): Int = load().count { !it.mastered }

    /** 已在精选题库里的题（用于把小节里的「+」显示成「已加入」） */
    @Synchronized
    fun isAdded(bookId: String, sectionTitle: String, question: String): Boolean {
        val key = dedupeKey(sectionTitle, question)
        return load().any { it.bookId == bookId && dedupeKey(it.sectionTitle, it.question) == key }
    }

    /** 加入精选题库（同书同节的同一道题不会重复加入） */
    @Synchronized
    fun add(
        bookId: String,
        bookTitle: String,
        chapterTitle: String,
        sectionTitle: String,
        example: ExampleItem
    ): CuratedQuestion? {
        val question = example.question.trim()
        if (question.isEmpty() && example.solution.isBlank()) return null
        if (isAdded(bookId, sectionTitle, question)) return null

        val item = CuratedQuestion(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            bookTitle = bookTitle.ifBlank { "未命名书本" },
            chapterTitle = chapterTitle,
            sectionTitle = sectionTitle,
            title = example.title.ifBlank { "例题" },
            question = question,
            solution = example.solution.trim(),
            addedAt = System.currentTimeMillis()
        )
        val items = load().toMutableList()
        items.add(0, item)
        save(items)
        return item
    }

    @Synchronized
    fun setMastered(id: String, mastered: Boolean = true) {
        save(
            load().map {
                if (it.id == id) {
                    it.copy(mastered = mastered, reviewedAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        )
    }

    @Synchronized
    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    /** 清空已掌握的题，返回清理条数 */
    @Synchronized
    fun clearMastered(): Int {
        val items = load()
        val remain = items.filterNot { it.mastered }
        save(remain)
        return items.size - remain.size
    }

    /** 按书本分组（保持加入时间倒序），供列表页显示 */
    @Synchronized
    fun grouped(items: List<CuratedQuestion>): List<Pair<String, List<CuratedQuestion>>> =
        items.groupBy { it.bookTitle }
            .toList()
            .sortedByDescending { (_, list) -> list.maxOf { it.addedAt } }

    private fun dedupeKey(sectionTitle: String, question: String): String {
        val q = question.replace(Regex("\\s+"), "").take(120)
        return "$sectionTitle|$q"
    }

    private fun load(): List<CuratedQuestion> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val question = o.optString("question")
                if (question.isBlank() && o.optString("solution").isBlank()) return@mapNotNull null
                CuratedQuestion(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    bookId = o.optString("bookId"),
                    bookTitle = o.optString("bookTitle"),
                    chapterTitle = o.optString("chapterTitle"),
                    sectionTitle = o.optString("sectionTitle"),
                    title = o.optString("title"),
                    question = question,
                    solution = o.optString("solution"),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                    mastered = o.optBoolean("mastered", false),
                    reviewedAt = o.optLong("reviewedAt", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<CuratedQuestion>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("bookId", item.bookId)
                    put("bookTitle", item.bookTitle)
                    put("chapterTitle", item.chapterTitle)
                    put("sectionTitle", item.sectionTitle)
                    put("title", item.title)
                    put("question", item.question)
                    put("solution", item.solution)
                    put("addedAt", item.addedAt)
                    put("mastered", item.mastered)
                    put("reviewedAt", item.reviewedAt)
                }
            )
        }
        runCatching { file.writeText(array.toString()) }
    }
}
