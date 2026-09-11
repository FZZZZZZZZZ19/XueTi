package com.xueti.learn.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.util.ReminderNotifier

/** 每日提醒闹钟触发：发通知并把下一次闹钟续期到明天 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        val settings = SettingsStore(context)
        if (!settings.reminderEnabled) return

        ReminderNotifier.show(context)

        // 一次性闹钟需自行续期
        ReminderScheduler.schedule(context, settings.reminderHour, settings.reminderMinute)
    }
}
