package com.xueti.learn.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.UsageStore
import com.xueti.learn.util.ReminderNotifier

/** 提醒闹钟触发：每日学习提醒 / 谷时开始提醒，并自动续期到下一次 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsStore(context)
        when (intent.action) {
            ReminderScheduler.ACTION_REMIND -> {
                if (!settings.reminderEnabled) return
                ReminderNotifier.show(context)
                ReminderScheduler.schedule(context, settings.reminderHour, settings.reminderMinute)
            }
            ReminderScheduler.ACTION_OFF_PEAK -> {
                val usageStore = UsageStore(context)
                if (!usageStore.offPeakReminder) return
                ReminderNotifier.showOffPeak(context)
                ReminderScheduler.scheduleOffPeak(context)
            }
        }
    }
}
