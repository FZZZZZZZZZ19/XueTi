package com.xueti.learn.data

import android.content.Context
import com.xueti.learn.model.Familiarity
import com.xueti.learn.model.LearnRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 学习进度持久化：
 * - 每日目标数量
 * - 单词学习记录（认识 / 不认识）
 * - 今日已学数量（跨天自动归零）
 */
class ProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("xueti_progress", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 每日学习目标（个） */
    var dailyGoal: Int
        get() = prefs.getInt(KEY_DAILY_GOAL, DEFAULT_GOAL)
        set(value) = prefs.edit().putInt(KEY_DAILY_GOAL, value.coerceIn(5, 100)).apply()

    private fun today(): String = dateFormat.format(Date())

    /** 今日已学数量（跨天自动归零） */
    fun todayLearnedCount(): Int {
        val storedDate = prefs.getString(KEY_TODAY_DATE, null)
        if (storedDate != today()) {
            prefs.edit()
                .putString(KEY_TODAY_DATE, today())
                .putInt(KEY_TODAY_LEARNED, 0)
                .apply()
            return 0
        }
        return prefs.getInt(KEY_TODAY_LEARNED, 0)
    }

    /**
     * 记录一个单词的学习结果
     * @param countToday 是否计入"今日已学"（同一词重复练习时传 false）
     */
    fun record(word: String, familiarity: Familiarity, countToday: Boolean = true) {
        val records = loadRecords().toMutableMap()
        val existing = records[word]
        records[word] = LearnRecord(
            word = word,
            familiarity = familiarity,
            firstLearnedAt = existing?.firstLearnedAt ?: System.currentTimeMillis(),
            reviewCount = (existing?.reviewCount ?: 0) + 1
        )
        saveRecords(records)

        if (countToday) {
            val learnedToday = todayLearnedCount()
            prefs.edit()
                .putString(KEY_TODAY_DATE, today())
                .putInt(KEY_TODAY_LEARNED, learnedToday + 1)
                .apply()
        }
    }

    fun isLearned(word: String): Boolean = loadRecords().containsKey(word)

    fun learnedCount(): Int = loadRecords().size

    /** 已学单词记录（按首次学习时间倒序） */
    fun learnedRecords(): List<LearnRecord> =
        loadRecords().values.sortedByDescending { it.firstLearnedAt }

    fun knownCount(): Int = loadRecords().values.count { it.familiarity == Familiarity.KNOWN }

    fun unknownCount(): Int = loadRecords().values.count { it.familiarity == Familiarity.UNKNOWN }

    /** 清空全部学习记录（保留每日目标设置） */
    fun resetAll() {
        prefs.edit()
            .remove(KEY_RECORDS)
            .putInt(KEY_TODAY_LEARNED, 0)
            .putString(KEY_TODAY_DATE, today())
            .apply()
    }

    private fun loadRecords(): Map<String, LearnRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, LearnRecord>()
            obj.keys().forEach { key ->
                val o = obj.getJSONObject(key)
                map[key] = LearnRecord(
                    word = key,
                    familiarity = runCatching {
                        Familiarity.valueOf(o.optString("familiarity", "KNOWN"))
                    }.getOrDefault(Familiarity.KNOWN),
                    firstLearnedAt = o.optLong("firstLearnedAt", System.currentTimeMillis()),
                    reviewCount = o.optInt("reviewCount", 1)
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun saveRecords(records: Map<String, LearnRecord>) {
        val obj = JSONObject()
        records.forEach { (word, record) ->
            obj.put(
                word,
                JSONObject().apply {
                    put("familiarity", record.familiarity.name)
                    put("firstLearnedAt", record.firstLearnedAt)
                    put("reviewCount", record.reviewCount)
                }
            )
        }
        prefs.edit().putString(KEY_RECORDS, obj.toString()).apply()
    }

    companion object {
        private const val KEY_DAILY_GOAL = "daily_goal"
        private const val KEY_RECORDS = "records"
        private const val KEY_TODAY_DATE = "today_date"
        private const val KEY_TODAY_LEARNED = "today_learned"
        const val DEFAULT_GOAL = 20
    }
}
