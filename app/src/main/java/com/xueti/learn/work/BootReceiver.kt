package com.xueti.learn.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xueti.learn.data.SettingsStore

/** 开机 / 应用更新后恢复每日提醒闹钟（闹钟在重启后会丢失） */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val settings = SettingsStore(context)
        if (settings.reminderEnabled) {
            ReminderScheduler.schedule(context, settings.reminderHour, settings.reminderMinute)
        }
    }
}
