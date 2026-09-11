package com.xueti.learn.util

import java.util.Calendar

/**
 * DeepSeek 高峰 / 空闲时段（官方定价页，2026 版）：
 *
 * > 空闲时段价格为高峰时段价格的一半。
 * > **高峰时段为北京时间周一至周五 9:00 - 12:00、14:00 - 18:00（其余为空闲时段）。**
 *
 * 也就是说：工作日午休（12:00-14:00）、18:00 之后、以及周末全天都属于**空闲时段**（约 5 折）。
 */
object PeakHours {

    /** 高峰时段（分钟数区间，左闭右开）：9:00-12:00、14:00-18:00 */
    private val PEAK_BLOCKS = listOf(9 * 60 to 12 * 60, 14 * 60 to 18 * 60)

    /** 工作日内的所有时段分界点（分钟数） */
    private val WEEKDAY_BOUNDARIES = listOf(9 * 60, 12 * 60, 14 * 60, 18 * 60)

    fun now(): Calendar = Calendar.getInstance()

    private fun minutesOf(calendar: Calendar): Int =
        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

    private fun isWeekday(calendar: Calendar): Boolean {
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        return day != Calendar.SATURDAY && day != Calendar.SUNDAY
    }

    /** 当前是否处于高峰时段（工作日 9:00-12:00、14:00-18:00） */
    fun isPeak(calendar: Calendar = now()): Boolean {
        if (!isWeekday(calendar)) return false
        val minutes = minutesOf(calendar)
        return PEAK_BLOCKS.any { (start, end) -> minutes >= start && minutes < end }
    }

    /** 当前是否处于空闲时段（价格约为高峰的一半） */
    fun isOffPeak(calendar: Calendar = now()): Boolean = !isPeak(calendar)

    /** 状态文案 */
    fun statusText(calendar: Calendar = now()): String =
        if (isPeak(calendar)) {
            "现在是【高峰时段】标准价（工作日 9:00-12:00、14:00-18:00）"
        } else {
            "现在是【空闲时段】价格约为高峰的一半，适合批量生成"
        }

    /** 距离下一次时段切换还有多久 */
    fun nextSwitchText(calendar: Calendar = now()): String {
        val target = nextTransition(calendar)
        val diff = target.timeInMillis - calendar.timeInMillis
        val hours = diff / (60 * 60 * 1000)
        val minutes = (diff / (60 * 1000)) % 60
        val remaining = when {
            hours > 0 -> "${hours} 小时 ${minutes} 分"
            else -> "${minutes} 分"
        }
        val nextIsPeak = isPeak(target)
        val nextState = if (nextIsPeak) "高峰时段" else "空闲时段"
        return "距离进入$nextState 还有 $remaining"
    }

    /**
     * 下一次时段切换时刻：
     * 工作日取当天的 9:00 / 12:00 / 14:00 / 18:00 中最近的一个未来时刻；
     * 否则顺延到下一个工作日的 9:00。
     */
    fun nextTransition(calendar: Calendar = now()): Calendar {
        val result = calendar.clone() as Calendar
        if (isWeekday(calendar)) {
            val minutes = minutesOf(calendar)
            WEEKDAY_BOUNDARIES.firstOrNull { minutes < it }?.let { boundary ->
                return result.apply {
                    set(Calendar.HOUR_OF_DAY, boundary / 60)
                    set(Calendar.MINUTE, boundary % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
        }
        // 当天已无切换点（或今天是周末）→ 找下一个工作日的 9:00
        do {
            result.add(Calendar.DAY_OF_YEAR, 1)
        } while (!isWeekday(result))
        return result.apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    /**
     * 下一次**空闲时段开始**时刻（用于「空闲时段开始提醒」）：
     * 即最近一个即将结束的高峰时段末点（工作日 12:00 或 18:00）。
     */
    fun nextOffPeakStart(calendar: Calendar = now()): Calendar {
        val cursor = calendar.clone() as Calendar
        // 最多向后找 8 天，逐个检查工作日 12:00 / 18:00
        repeat(8) {
            if (isWeekday(cursor)) {
                listOf(12 * 60, 18 * 60).forEach { boundary ->
                    val candidate = (cursor.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, boundary / 60)
                        set(Calendar.MINUTE, boundary % 60)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (candidate.after(calendar)) return candidate
                }
            }
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        return nextTransition(calendar)
    }

    /** 高峰时段说明（用于设置页文案） */
    fun scheduleDescription(): String =
        "高峰：工作日 9:00-12:00、14:00-18:00；其余（含午休、18:00 后、周末）为空闲时段，价格约为高峰的一半。"
}
