package com.xueti.learn.model

/** 单词条目 */
data class Word(
    val word: String,
    val phonetic: String,
    val pos: String,
    val meaning: String,
    val example: String,
    val exampleCn: String
)

/** 熟悉程度 */
enum class Familiarity {
    /** 不认识（会重新排队练习） */
    UNKNOWN,

    /** 认识 */
    KNOWN
}

/** 单词学习记录 */
data class LearnRecord(
    val word: String,
    val familiarity: Familiarity,
    val firstLearnedAt: Long,
    val reviewCount: Int = 1,
    /** 复习时连续答对的次数（达到 2 次即视为掌握） */
    val reviewCorrect: Int = 0
)
