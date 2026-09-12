package com.xueti.learn.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 一条错题记录（错题本数据结构）
 *
 * - [question]  题目原文（AI 解题的题干，或复习时答错的单词/知识点）
 * - [aiAnswer]  AI 给出的解答（保留下来方便回顾与"再讲一遍"）
 * - [note]      用户自己补充的错因（可选）
 * - [source]    来源：`ai_solve` AI 解题 / `review` 复习 / `study` 每日学习
 * - [subject]   学科或关键词（用于归类统计，也用于匹配教材章节）
 * - [mastered]  是否已掌握（掌握后不再推送）
 */
data class MistakeItem(
    val id: String,
    val question: String,
    val aiAnswer: String,
    val note: String,
    val source: String,
    val subject: String,
    val createdAt: Long,
    val reviewedAt: Long = 0L,
    val reviewCount: Int = 0,
    val mastered: Boolean = false
) {
    val isReviewed: Boolean get() = reviewedAt > 0L
}

/**
 * 错题本：自动记录做错/算错的题目，并在生成学习内容时重点推送。
 *
 * 存储：`filesDir/mistakes.json`（最多保留 [MAX_ITEMS] 条，超出的先淘汰已掌握的老记录）
 */
class MistakeStore(context: Context) {

    private val file = File(context.filesDir, "mistakes.json")

    /** 全部错题（按时间倒序） */
    @Synchronized
    fun all(): List<MistakeItem> = load().sortedByDescending { it.createdAt }

    /** 未掌握的错题（推送用，时间倒序） */
    @Synchronized
    fun pending(): List<MistakeItem> = all().filterNot { it.mastered }

    @Synchronized
    fun pendingCount(): Int = load().count { !it.mastered }

    @Synchronized
    fun masteredCount(): Int = load().count { it.mastered }

    /**
     * 记录一条错题；题目为空则忽略。
     * @param subject 不传则自动从题干里粗略推断（数学 / 英语 / 其他）
     */
    @Synchronized
    fun add(
        question: String,
        aiAnswer: String = "",
        source: String = SOURCE_AI_SOLVE,
        subject: String = "",
        note: String = ""
    ): MistakeItem? {
        val q = question.trim()
        if (q.isEmpty()) return null
        val item = MistakeItem(
            id = UUID.randomUUID().toString(),
            question = q,
            aiAnswer = aiAnswer.trim(),
            note = note.trim(),
            source = source,
            subject = subject.ifBlank { guessSubject(q) },
            createdAt = System.currentTimeMillis()
        )
        val items = load().toMutableList()
        items.add(0, item)
        save(trim(items))
        return item
    }

    /** 复习过一遍（不算掌握） */
    @Synchronized
    fun markReviewed(id: String) {
        save(
            load().map {
                if (it.id == id) {
                    it.copy(reviewedAt = System.currentTimeMillis(), reviewCount = it.reviewCount + 1)
                } else {
                    it
                }
            }
        )
    }

    @Synchronized
    fun setMastered(id: String, mastered: Boolean = true) {
        save(load().map { if (it.id == id) it.copy(mastered = mastered) else it })
    }

    @Synchronized
    fun updateNote(id: String, note: String) {
        save(load().map { if (it.id == id) it.copy(note = note.trim()) else it })
    }

    @Synchronized
    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    /** 清空已掌握的错题，返回清理条数 */
    @Synchronized
    fun clearMastered(): Int {
        val items = load()
        val remain = items.filterNot { it.mastered }
        save(remain)
        return items.size - remain.size
    }

    /** 学科/关键词列表（按出现次数倒序），用于错题本筛选 */
    @Synchronized
    fun subjects(): List<String> =
        load().map { it.subject }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }

    /**
     * 生成给 AI 的「易错点」上下文块：生成教材目录 / 小节讲解时重点推送，
     * 让 AI 优先覆盖这些薄弱点。
     *
     * @param keyword 传入教材/小节标题时，只推送与之相关的错题（命中关键词或学科）
     */
    @Synchronized
    fun buildFocusPrompt(
        limit: Int = 12,
        maxChars: Int = 180,
        keyword: String = ""
    ): String {
        val candidates = pending().let { list ->
            if (keyword.isBlank()) {
                list
            } else {
                val hits = list.filter { matches(it, keyword) }
                if (hits.isEmpty()) emptyList() else hits
            }
        }
        if (candidates.isEmpty()) return ""
        val picked = candidates.take(limit)
        return buildString {
            append("【用户错题本 · 必须重点覆盖的易错点】\n")
            append("下面是用户近期做错/算错的题目（共 ${pendingCount()} 条，列出 ${picked.size} 条）：\n")
            picked.forEachIndexed { index, item ->
                append(index + 1).append(". ")
                if (item.subject.isNotBlank()) append("[").append(item.subject).append("] ")
                append(oneLine(item.question, maxChars))
                val wrong = item.note.ifBlank { "" }
                if (wrong.isNotEmpty()) append("（错因：").append(oneLine(wrong, 60)).append("）")
                append("\n")
            }
            append(
                "要求：生成内容时针对上述易错点给出对应的讲解、例题或提醒；" +
                    "如果与教材内容无关则忽略，不要编造。\n"
            )
        }
    }

    // ---------------- 内部 ----------------

    private fun matches(item: MistakeItem, keyword: String): Boolean {
        val key = keyword.trim()
        if (key.isEmpty()) return false
        if (item.subject.isNotBlank() && (key.contains(item.subject) || item.subject.contains(key))) {
            return true
        }
        val q = item.question
        // 标题里的 2 字以上片段命中题干即视为相关
        return key.split(' ', '，', '。', '、', '（', '(', '）', ')', '：', ':')
            .filter { it.length >= 2 }
            .any { q.contains(it) }
    }

    private fun oneLine(text: String, max: Int): String {
        val flat = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }

    private fun trim(items: List<MistakeItem>): List<MistakeItem> {
        if (items.size <= MAX_ITEMS) return items
        // 优先淘汰「已掌握 + 最旧」的记录
        val sorted = items.sortedWith(
            compareBy<MistakeItem> { it.mastered }.reversed()
                .thenByDescending { it.createdAt }
        )
        return sorted.take(MAX_ITEMS)
    }

    private fun guessSubject(question: String): String = when {
        question.any { it in "数学函数极限导数积分微分矩阵向量概率" } -> "数学"
        question.any { it in "物理力学电场磁场速度加速度" } -> "物理"
        question.any { it in "化学方程式摩尔溶液元素" } -> "化学"
        question.any { it in "单词词义翻译语法句式" } -> "英语"
        else -> "其他"
    }

    private fun load(): List<MistakeItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val question = o.optString("question").trim()
                if (question.isEmpty()) return@mapNotNull null
                MistakeItem(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    question = question,
                    aiAnswer = o.optString("aiAnswer"),
                    note = o.optString("note"),
                    source = o.optString("source", SOURCE_AI_SOLVE),
                    subject = o.optString("subject"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    reviewedAt = o.optLong("reviewedAt", 0L),
                    reviewCount = o.optInt("reviewCount", 0),
                    mastered = o.optBoolean("mastered", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<MistakeItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("question", item.question)
                    put("aiAnswer", item.aiAnswer)
                    put("note", item.note)
                    put("source", item.source)
                    put("subject", item.subject)
                    put("createdAt", item.createdAt)
                    put("reviewedAt", item.reviewedAt)
                    put("reviewCount", item.reviewCount)
                    put("mastered", item.mastered)
                }
            )
        }
        runCatching { file.writeText(array.toString()) }
    }

    companion object {
        const val SOURCE_AI_SOLVE = "ai_solve"
        const val SOURCE_REVIEW = "review"
        const val SOURCE_STUDY = "study"

        private const val MAX_ITEMS = 500
    }
}
