package com.xueti.learn.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.util.PeakHours
import java.util.Calendar
import java.util.Locale

/**
 * 每日提醒调度（AlarmManager）。
 *
 * v2.03 起做了三件关键改进，解决"不打开 App 就收不到提醒"的问题：
 * 1. **精确闹钟**：有「闹钟与提醒」权限时用 `setAlarmClock` / `setExactAndAllowWhileIdle`，
 *    不会被系统按 App Standby 分桶延后或丢弃（原来只用 inexact 的 setAndAllowWhileIdle，
 *    久不打开 App 就会被降级成"限制"分桶，闹钟直接不触发）。
 * 2. **一次排 7 天**：不只排下一个，而是滚动排好未来 7 天，任何一次触发/打开 App 都会补满，
 *    少一次触发也不会断链。
 * 3. **可自检**：提供权限/电池优化/闹钟是否真的挂上了的查询，设置页能直接看到状态并一键修复。
 */
object ReminderScheduler {

    const val ACTION_REMIND = "com.xueti.learn.action.DAILY_REMIND"
    const val ACTION_OFF_PEAK = "com.xueti.learn.action.OFF_PEAK"

    private const val REQUEST_CODE = 1001
    private const val REQUEST_CODE_OFF_PEAK = 1002

    /** 一次排定未来多少天的提醒 */
    private const val DAYS_AHEAD = 7

    /** 安排未来 [DAYS_AHEAD] 天的每日提醒（会先清掉旧的，再重排） */
    fun schedule(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        cancel(context)

        val exactAllowed = canScheduleExactAlarms(context)
        for (dayOffset in 0 until DAYS_AHEAD) {
            val triggerAt = triggerMillis(hour, minute, dayOffset)
            val pendingIntent = buildPendingIntent(context, dayOffset)
            val scheduled = runCatching {
                if (exactAllowed) {
                    if (dayOffset == 0) {
                        // 最近一次用「闹钟」形式：系统按用户可见闹钟对待，最可靠
                        alarmManager.setAlarmClock(
                            AlarmManager.AlarmClockInfo(triggerAt, null),
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    }
                } else {
                    // 没拿到精确闹钟权限：退化为可在 Doze 下触发的非精确闹钟
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                true
            }.getOrElse {
                runCatching {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    true
                }.getOrDefault(false)
            }
            if (!scheduled) break
        }

        SettingsStore(context).reminderScheduledAt = System.currentTimeMillis()
    }

    /** 空闲时段（优惠时段）开始提醒：最近一个高峰时段结束点（工作日 12:00 或 18:00） */
    fun scheduleOffPeak(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = buildOffPeakPendingIntent(context)
        val triggerAt = PeakHours.nextOffPeakStart().timeInMillis
        runCatching {
            if (canScheduleExactAlarms(context)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.onFailure {
            runCatching { alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent) }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        for (dayOffset in 0 until DAYS_AHEAD) {
            runCatching { alarmManager.cancel(buildPendingIntent(context, dayOffset)) }
        }
    }

    fun cancelOffPeak(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(buildOffPeakPendingIntent(context)) }
    }

    /** 第 [dayOffset] 天（0 = 今天或明天，取决于时间是否已过）的触发时刻 */
    fun triggerMillis(hour: Int, minute: Int, dayOffset: Int = 0): Long {
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
        target.add(Calendar.DAY_OF_YEAR, dayOffset)
        return target.timeInMillis
    }

    /** 计算下一次触发时间（若今天该时刻已过则顺延到明天） */
    fun nextTriggerMillis(hour: Int, minute: Int): Long = triggerMillis(hour, minute, 0)

    /** 下一次提醒的可读文案（今天/明天 hh:mm） */
    fun nextTriggerText(hour: Int, minute: Int): String {
        val target = Calendar.getInstance().apply { timeInMillis = nextTriggerMillis(hour, minute) }
        val now = Calendar.getInstance()
        val sameDay = target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) &&
            target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val prefix = if (sameDay) "今天" else "明天"
        return String.format(Locale.getDefault(), "%s %02d:%02d", prefix, hour, minute)
    }

    // ---------------- 权限 / 状态自检 ----------------

    /** Android 12+ 需要「闹钟与提醒」特殊权限才能精确定时 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    /** 精确闹钟授权页（Android 12+） */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    /** 是否需要引导用户关闭电池优化（部分 ROM 会拦截后台闹钟） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 申请忽略电池优化（需在清单里声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS） */
    fun ignoreBatteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /** 电池优化设置列表页（申请对话框不可用时的兜底） */
    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 系统「应用信息」页：国产 ROM 的自启动/后台管理基本都在这里 */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /** 常见国产 ROM 的自启动管理页（能打开就打开，打不开就让用户去「应用信息」页） */
    fun autostartIntent(context: Context): Intent? {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC"
        )
        val manager = context.packageManager
        candidates.forEach { (pkg, cls) ->
            val intent = Intent().setClassName(pkg, cls).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolved = runCatching { manager.resolveActivity(intent, 0) }.getOrNull()
            if (resolved != null) return intent
        }
        return null
    }

    /** 闹钟是否真的还挂在系统里（用于自检文案） */
    fun hasPendingAlarm(context: Context): Boolean {
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_REMIND }
        val existing = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        return existing != null
    }

    private fun buildPendingIntent(context: Context, dayOffset: Int = 0): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE + dayOffset,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildOffPeakPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_OFF_PEAK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_OFF_PEAK,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
