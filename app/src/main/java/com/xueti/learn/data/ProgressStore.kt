package com.xueti.learn.data

import android.content.Context
import com.xueti.learn.model.Familiarity
import com.xueti.learn.model.LearnRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 学习进度持久化：
 * - 每日目标（支持按日期自定义，0 表示休息日）
 * - 单词学习记录（认识 / 不认识）
 * - 每日学习内容（用于统计与日历）
 * - 今日已学数量（跨天自动归零）
 */
class ProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("xueti_progress", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 默认每日学习目标（个，1-500 可自定义） */
    var dailyGoal: Int
        get() = prefs.getInt(KEY_DAILY_GOAL, DEFAULT_GOAL)
        set(value) = prefs.edit().putInt(KEY_DAILY_GOAL, value.coerceIn(MIN_GOAL, MAX_GOAL)).apply()

    // ---------------- 主目标（目标日 + 手写目标/寄语） ----------------

    /** 目标日期（yyyy-MM-dd，为空表示未设置） */
    var goalDate: String?
        get() = prefs.getString(KEY_GOAL_DATE, null)
        set(value) = prefs.edit().putString(KEY_GOAL_DATE, value).apply()

    /** 用户手写的目标 / 激励语句 */
    var goalText: String
        get() = prefs.getString(KEY_GOAL_TEXT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GOAL_TEXT, value.trim()).apply()

    /** 距离指定日期还有多少天（负数表示已过） */
    fun daysUntil(date: String): Int {
        val target = parse(date) ?: return 0
        val today = parse(today()) ?: return 0
        return ((target.time - today.time) / (24L * 60L * 60L * 1000L)).toInt()
    }

    /** 日期格式化（如 2026年12月14日） */
    fun formatGoalDate(date: String): String {
        val target = parse(date) ?: return date
        return SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(target)
    }

    /** 距离目标日还有多少天（负数表示已过；未设置目标返回 null） */
    fun daysUntilGoal(): Int? {
        val date = goalDate ?: return null
        return daysUntil(date)
    }

    /** 目标日的中文格式（如 2026年12月14日） */
    fun goalDateFormatted(): String? {
        val date = goalDate ?: return null
        return formatGoalDate(date)
    }

    /** 清除主目标 */
    fun clearGoal() {
        prefs.edit().remove(KEY_GOAL_DATE).remove(KEY_GOAL_TEXT).apply()
    }

    // ---------------- 日期工具 ----------------

    fun today(): String = dateFormat.format(Date())

    fun format(date: Date): String = dateFormat.format(date)

    fun parse(date: String): Date? = runCatching { dateFormat.parse(date) }.getOrNull()

    // ---------------- 每日目标（含按日自定义） ----------------

    /** 指定日期的学习目标；自定义优先，0 表示休息日 */
    fun dailyGoalFor(date: String): Int = customGoalFor(date) ?: dailyGoal

    /** 该日期是否设置了自定义目标 */
    fun customGoalFor(date: String): Int? {
        val obj = loadCustomGoals()
        return if (obj.has(date)) obj.optInt(date, dailyGoal) else null
    }

    /** 设置某日的自定义目标；传 null 表示恢复跟随默认目标 */
    fun setCustomGoal(date: String, goal: Int?) {
        val obj = loadCustomGoals()
        if (goal == null) obj.remove(date) else obj.put(date, goal.coerceIn(0, MAX_GOAL))
        prefs.edit().putString(KEY_CUSTOM_GOALS, obj.toString()).apply()
    }

    private fun loadCustomGoals(): JSONObject {
        val raw = prefs.getString(KEY_CUSTOM_GOALS, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    // ---------------- 今日进度 ----------------

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
        val now = System.currentTimeMillis()
        records[word] = LearnRecord(
            word = word,
            familiarity = familiarity,
            firstLearnedAt = existing?.firstLearnedAt ?: now,
            reviewCount = (existing?.reviewCount ?: 0) + 1,
            lastSeenAt = now
        )
        saveRecords(records)
        addToDailyWords(today(), word)

        if (countToday) {
            val learnedToday = todayLearnedCount()
            prefs.edit()
                .putString(KEY_TODAY_DATE, today())
                .putInt(KEY_TODAY_LEARNED, learnedToday + 1)
                .apply()
        }
    }

    // ---------------- 每日学习内容（统计 / 日历） ----------------

    private fun addToDailyWords(date: String, word: String) {
        val all = loadDailyWords()
        val words = all.optJSONArray(date)?.let { array ->
            (0 until array.length()).map { array.optString(it) }.toMutableList()
        } ?: mutableListOf()
        if (!words.contains(word)) {
            words.add(word)
            all.put(date, JSONArray(words))
            // 仅保留最近 400 天，避免无限增长
            if (all.length() > MAX_HISTORY_DAYS) {
                val keys = all.keys().asSequence().sorted().toList()
                keys.take(all.length() - MAX_HISTORY_DAYS).forEach { all.remove(it) }
            }
            prefs.edit().putString(KEY_DAILY_WORDS, all.toString()).apply()
        }
    }

    private fun loadDailyWords(): JSONObject {
        val raw = prefs.getString(KEY_DAILY_WORDS, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    /** 某日学过的单词（按学习先后） */
    fun wordsLearnedOn(date: String): List<String> {
        val array = loadDailyWords().optJSONArray(date) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }
    }

    /** 某日学习数量 */
    fun learnedCountOn(date: String): Int = wordsLearnedOn(date).size

    /** 有学习记录的所有日期（升序） */
    fun historyDates(): List<String> = loadDailyWords().keys().asSequence().sorted().toList()

    /** 连续打卡天数（从今天或昨天往前数连续有记录的天数） */
    fun streakDays(): Int {
        val dates = historyDates().toSet()
        if (dates.isEmpty()) return 0
        val calendar = Calendar.getInstance()
        // 今天没学则从昨天开始算，避免"今天还没学"就把连续天数清零
        if (!dates.contains(dateFormat.format(calendar.time))) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        var streak = 0
        while (dates.contains(dateFormat.format(calendar.time))) {
            streak++
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    /** 最近 count 天（含今天）的日期与学习数量，按时间升序 */
    fun recentDays(count: Int): List<Pair<String, Int>> {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -(count - 1))
        return (0 until count).map {
            val date = dateFormat.format(calendar.time)
            date to learnedCountOn(date).also { _ -> calendar.add(Calendar.DAY_OF_YEAR, 1) }
        }
    }

    // ---------------- 汇总统计 ----------------

    fun isLearned(word: String): Boolean = loadRecords().containsKey(word)

    fun recordOf(word: String): LearnRecord? = loadRecords()[word]

    // ---------------- 复习（需复习的词 + 遗忘判定） ----------------

    /** 所有需复习的词（不认识，按最早学习时间排序） */
    fun reviewWords(): List<LearnRecord> =
        loadRecords().values
            .filter { it.familiarity == Familiarity.UNKNOWN }
            .sortedBy { it.firstLearnedAt }

    /** 距最近一次练习过去了几天 */
    fun daysSinceSeen(record: LearnRecord): Int {
        val days = (System.currentTimeMillis() - record.lastSeen) / (24L * 60L * 60L * 1000L)
        return days.coerceAtLeast(0).toInt()
    }

    /** 是否已「很久没练」（认识但超过 [intervalDays] 天没碰，可能遗忘） */
    fun isOverdue(record: LearnRecord, intervalDays: Int = REVIEW_INTERVAL_DAYS): Boolean =
        record.familiarity == Familiarity.KNOWN && daysSinceSeen(record) >= intervalDays

    /**
     * 待复习队列 = 需复习的词（不认识，越早学的越靠前）
     * + 很久没练可能遗忘的词（认识但超过 [intervalDays] 天未练，越久没碰越靠前）
     */
    fun dueReviewWords(intervalDays: Int = REVIEW_INTERVAL_DAYS): List<LearnRecord> {
        val records = loadRecords().values
        val unknown = records.filter { it.familiarity == Familiarity.UNKNOWN }
            .sortedBy { it.lastSeen }
        val overdue = records.filter { isOverdue(it, intervalDays) }
            .sortedBy { it.lastSeen }
        return unknown + overdue
    }

    /** 待复习词数量 */
    fun dueReviewCount(intervalDays: Int = REVIEW_INTERVAL_DAYS): Int =
        dueReviewWords(intervalDays).size

    /** 待复习词里「很久没练」的数量 */
    fun overdueKnownCount(intervalDays: Int = REVIEW_INTERVAL_DAYS): Int =
        loadRecords().values.count { isOverdue(it, intervalDays) }

    /** 更新某词最近练习时间（答对/答错都算练过） */
    private fun touch(records: MutableMap<String, LearnRecord>, word: String) {
        val existing = records[word] ?: return
        records[word] = existing.copy(lastSeenAt = System.currentTimeMillis())
    }

    /** 复习时答对：连续答对达到 [REVIEW_REQUIRED] 次即标记为掌握 */
    fun addReviewCorrect(word: String): Int {
        val records = loadRecords().toMutableMap()
        val existing = records[word] ?: return 0
        val streak = existing.reviewCorrect + 1
        records[word] = if (streak >= REVIEW_REQUIRED) {
            existing.copy(
                familiarity = Familiarity.KNOWN,
                reviewCorrect = REVIEW_REQUIRED,
                lastSeenAt = System.currentTimeMillis()
            )
        } else {
            existing.copy(reviewCorrect = streak, lastSeenAt = System.currentTimeMillis())
        }
        saveRecords(records)
        return streak
    }

    /**
     * 复习时答对（统一入口）：返回该词是否已「通关」，可以从复习队列移除。
     * - 不认识的词：需连续答对 [REVIEW_REQUIRED] 次
     * - 认识但很久没练的词：答对一次即刷新计时，重新变得「新鲜」
     */
    fun answerReviewCorrect(word: String): Boolean {
        val existing = loadRecords()[word] ?: return true
        return if (existing.familiarity == Familiarity.UNKNOWN) {
            addReviewCorrect(word) >= REVIEW_REQUIRED
        } else {
            val records = loadRecords().toMutableMap()
            touch(records, word)
            saveRecords(records)
            true
        }
    }

    /** 复习时答错：连续答对次数清零，标记为需复习 */
    fun resetReviewCorrect(word: String) {
        val records = loadRecords().toMutableMap()
        val existing = records[word] ?: return
        records[word] = existing.copy(
            familiarity = Familiarity.UNKNOWN,
            reviewCorrect = 0,
            lastSeenAt = System.currentTimeMillis()
        )
        saveRecords(records)
    }

    fun learnedCount(): Int = loadRecords().size

    fun learnedRecords(): List<LearnRecord> =
        loadRecords().values.sortedByDescending { it.firstLearnedAt }

    fun knownCount(): Int = loadRecords().values.count { it.familiarity == Familiarity.KNOWN }

    fun unknownCount(): Int = loadRecords().values.count { it.familiarity == Familiarity.UNKNOWN }

    /** 认识率（0-100） */
    fun knownRate(): Int {
        val total = learnedCount()
        return if (total == 0) 0 else knownCount() * 100 / total
    }

    /** 清空全部学习记录（保留每日目标与已配置的自定义目标） */
    fun resetAll() {
        prefs.edit()
            .remove(KEY_RECORDS)
            .remove(KEY_DAILY_WORDS)
            .putInt(KEY_TODAY_LEARNED, 0)
            .putString(KEY_TODAY_DATE, today())
            .apply()
    }

    // ---------------- 记录读写 ----------------

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
                    reviewCount = o.optInt("reviewCount", 1),
                    reviewCorrect = o.optInt("reviewCorrect", 0),
                    lastSeenAt = o.optLong("lastSeenAt", 0L)
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
                    put("reviewCorrect", record.reviewCorrect)
                    put("lastSeenAt", record.lastSeen)
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
        private const val KEY_DAILY_WORDS = "daily_words"
        private const val KEY_CUSTOM_GOALS = "custom_goals"
        private const val KEY_GOAL_DATE = "goal_date"
        private const val KEY_GOAL_TEXT = "goal_text"
        private const val MAX_HISTORY_DAYS = 400
        const val DEFAULT_GOAL = 20
        const val MIN_GOAL = 1
        const val MAX_GOAL = 500

        /** 复习时连续答对多少次算掌握 */
        const val REVIEW_REQUIRED = 2

        /** 多少天没练就认为「可能遗忘」，重新放回复习队列 */
        const val REVIEW_INTERVAL_DAYS = 14
    }
}
