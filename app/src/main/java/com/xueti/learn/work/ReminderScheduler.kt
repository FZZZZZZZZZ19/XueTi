package com.xueti.learn.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 每日提醒调度（AlarmManager 实现）。
 *
 * 相比 WorkManager，AlarmManager + setAndAllowWhileIdle 在国产 ROM 的后台清理策略下
 * 存活率明显更高；闹钟为一次性，触发后由 [ReminderReceiver] 自动续期到第二天。
 */
object ReminderScheduler {

    const val ACTION_REMIND = "com.xueti.learn.action.DAILY_REMIND"
    private const val REQUEST_CODE = 1001

    fun schedule(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = buildPendingIntent(context)
        val triggerAt = nextTriggerMillis(hour, minute)

        // setAndAllowWhileIdle：无需「精确闹钟」特殊权限，且在 Doze 下仍可触发
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }.onFailure {
            runCatching {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(buildPendingIntent(context)) }
    }

    /** 计算下一次触发时间（若今天该时刻已过则顺延到明天） */
    fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    /** 下一次提醒的可读文案（今天/明天 hh:mm） */
    fun nextTriggerText(hour: Int, minute: Int): String {
        val target = Calendar.getInstance().apply { timeInMillis = nextTriggerMillis(hour, minute) }
        val now = Calendar.getInstance()
        val sameDay = target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) &&
            target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val prefix = if (sameDay) "今天" else "明天"
        return String.format("%s %02d:%02d", prefix, hour, minute)
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 是否需要引导用户关闭电池优化（部分 ROM 会拦截后台闹钟） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(android.os.PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
