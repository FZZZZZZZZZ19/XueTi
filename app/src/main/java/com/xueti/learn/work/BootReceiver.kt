package com.xueti.learn.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xueti.learn.data.SettingsStore

/**
 * 恢复每日提醒闹钟。
 *
 * 触发场景：
 * - 开机 / 快速开机（闹钟在重启后会全部丢失）
 * - 应用更新后（MY_PACKAGE_REPLACED）
 * - 用户刚授予「闹钟与提醒」精确闹钟权限时（此时要用精确闹钟重排一遍）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        if (!relevant) return

        val settings = SettingsStore(context)
        if (settings.reminderEnabled) {
            ReminderScheduler.schedule(context, settings.reminderHour, settings.reminderMinute)
        }
    }
}
