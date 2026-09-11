package com.xueti.learn.util

import java.util.Calendar

/**
 * DeepSeek 峰谷时段（错峰优惠）计算。
 *
 * 官方优惠时段：**UTC 16:30 – 次日 00:30**，换算北京时间（UTC+8）为 **00:30 – 08:30**。
 * 该时段内 deepseek-chat 约 5 折、deepseek-reasoner 约 2.5 折（以官方最新公告为准）。
 */
object PeakHours {

    /** 谷时开始（北京时间 00:30） */
    private const val OFF_PEAK_START_MINUTES = 30

    /** 谷时结束（北京时间 08:30） */
    private const val OFF_PEAK_END_MINUTES = 8 * 60 + 30

    fun now(): Calendar = Calendar.getInstance()

    /** 当前是否处于谷时（优惠时段） */
    fun isOffPeak(calendar: Calendar = now()): Boolean {
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return minutes >= OFF_PEAK_START_MINUTES && minutes < OFF_PEAK_END_MINUTES
    }

    /** 状态文案：「现在是谷时（00:30-08:30，约 5 折）」/「现在是峰时」 */
    fun statusText(): String =
        if (isOffPeak()) {
            "现在是【谷时】00:30–08:30，API 价格约 5 折，适合批量生成"
        } else {
            "现在是【峰时】标准价；谷时 00:30–08:30 约 5 折"
        }

    /** 距离下一次时段切换还有多久，如「距离谷时开始还有 3 小时 12 分」 */
    fun nextSwitchText(calendar: Calendar = now()): String {
        val offPeak = isOffPeak(calendar)
        val target = if (offPeak) nextOffPeakEnd(calendar) else nextOffPeakStart(calendar)
        val diff = target.timeInMillis - calendar.timeInMillis
        val hours = diff / (60 * 60 * 1000)
        val minutes = (diff / (60 * 1000)) % 60
        val remaining = when {
            hours > 0 -> "${hours} 小时 ${minutes} 分"
            else -> "${minutes} 分"
        }
        return if (offPeak) "距离恢复峰时还有 $remaining" else "距离谷时开始还有 $remaining"
    }

    /** 下一次谷时开始时间（用于定时提醒） */
    fun nextOffPeakStart(calendar: Calendar = now()): Calendar {
        val target = (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, OFF_PEAK_START_MINUTES / 60)
            set(Calendar.MINUTE, OFF_PEAK_START_MINUTES % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(calendar)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target
    }

    private fun nextOffPeakEnd(calendar: Calendar): Calendar {
        val target = (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, OFF_PEAK_END_MINUTES / 60)
            set(Calendar.MINUTE, OFF_PEAK_END_MINUTES % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(calendar)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target
    }
}
