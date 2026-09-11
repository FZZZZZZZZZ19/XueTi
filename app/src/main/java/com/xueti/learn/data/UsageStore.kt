package com.xueti.learn.data

import android.content.Context
import com.xueti.learn.ai.DeepSeekClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * API 用量与费用统计。
 * 每次请求后累计 token 数，按用户设置的单价估算费用（峰时标准价）。
 */
class UsageStore(context: Context) {

    private val prefs = context.getSharedPreferences("xueti_usage", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 累计请求次数 */
    val requests: Int get() = prefs.getInt(KEY_REQUESTS, 0)

    /** 累计输入 token */
    val promptTokens: Long get() = prefs.getLong(KEY_PROMPT_TOKENS, 0)

    /** 累计输出 token */
    val completionTokens: Long get() = prefs.getLong(KEY_COMPLETION_TOKENS, 0)

    /** 缓存命中的输入 token（价格更低） */
    val cacheHitTokens: Long get() = prefs.getLong(KEY_CACHE_HIT_TOKENS, 0)

    /** 今日请求次数 */
    fun todayRequests(): Int {
        if (prefs.getString(KEY_TODAY_DATE, null) != today()) return 0
        return prefs.getInt(KEY_TODAY_REQUESTS, 0)
    }

    /** 今日消耗 token */
    fun todayTokens(): Long {
        if (prefs.getString(KEY_TODAY_DATE, null) != today()) return 0
        return prefs.getLong(KEY_TODAY_TOKENS, 0)
    }

    /** 输入单价（元 / 百万 token） */
    var inputPrice: Float
        get() = prefs.getFloat(KEY_INPUT_PRICE, DEFAULT_INPUT_PRICE)
        set(value) = prefs.edit().putFloat(KEY_INPUT_PRICE, value.coerceAtLeast(0f)).apply()

    /** 输出单价（元 / 百万 token） */
    var outputPrice: Float
        get() = prefs.getFloat(KEY_OUTPUT_PRICE, DEFAULT_OUTPUT_PRICE)
        set(value) = prefs.edit().putFloat(KEY_OUTPUT_PRICE, value.coerceAtLeast(0f)).apply()

    /** 谷时提醒开关（每天 00:30 通知） */
    var offPeakReminder: Boolean
        get() = prefs.getBoolean(KEY_OFF_PEAK_REMINDER, false)
        set(value) = prefs.edit().putBoolean(KEY_OFF_PEAK_REMINDER, value).apply()

    /** 记录一次请求的用量 */
    fun record(usage: DeepSeekClient.Usage?) {
        val today = today()
        val sameDay = prefs.getString(KEY_TODAY_DATE, null) == today
        val editor = prefs.edit()
            .putInt(KEY_REQUESTS, requests + 1)
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY_REQUESTS, if (sameDay) todayRequests() + 1 else 1)

        if (usage != null) {
            editor.putLong(KEY_PROMPT_TOKENS, promptTokens + usage.promptTokens)
            editor.putLong(KEY_COMPLETION_TOKENS, completionTokens + usage.completionTokens)
            editor.putLong(KEY_CACHE_HIT_TOKENS, cacheHitTokens + usage.cacheHitTokens)
            editor.putLong(KEY_TODAY_TOKENS, (if (sameDay) todayTokens() else 0) + usage.totalTokens)
        }
        editor.apply()
    }

    /** 估算累计费用（元）：输入按输入单价、输出按输出单价 */
    fun estimatedCost(): Double {
        val input = promptTokens / 1_000_000.0 * inputPrice
        val output = completionTokens / 1_000_000.0 * outputPrice
        return input + output
    }

    fun reset() {
        prefs.edit()
            .remove(KEY_REQUESTS)
            .remove(KEY_PROMPT_TOKENS)
            .remove(KEY_COMPLETION_TOKENS)
            .remove(KEY_CACHE_HIT_TOKENS)
            .remove(KEY_TODAY_REQUESTS)
            .remove(KEY_TODAY_TOKENS)
            .remove(KEY_TODAY_DATE)
            .apply()
    }

    private fun today(): String = dateFormat.format(Date())

    companion object {
        private const val KEY_REQUESTS = "requests"
        private const val KEY_PROMPT_TOKENS = "prompt_tokens"
        private const val KEY_COMPLETION_TOKENS = "completion_tokens"
        private const val KEY_CACHE_HIT_TOKENS = "cache_hit_tokens"
        private const val KEY_TODAY_REQUESTS = "today_requests"
        private const val KEY_TODAY_TOKENS = "today_tokens"
        private const val KEY_TODAY_DATE = "today_date"
        private const val KEY_INPUT_PRICE = "input_price"
        private const val KEY_OUTPUT_PRICE = "output_price"
        private const val KEY_OFF_PEAK_REMINDER = "off_peak_reminder"

        /** 默认单价（元/百万 token，deepseek-chat 标准价，可在设置中修改） */
        const val DEFAULT_INPUT_PRICE = 2f
        const val DEFAULT_OUTPUT_PRICE = 8f
    }
}
