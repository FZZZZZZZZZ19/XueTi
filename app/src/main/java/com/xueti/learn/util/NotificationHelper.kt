package com.xueti.learn.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.xueti.learn.R

/** 通知渠道管理 */
object NotificationHelper {

    /**
     * 使用 v2 渠道：重要级别为「高」（横幅弹出 + 提示音），
     * 避免旧渠道被系统/用户静默或被关闭后无法恢复。
     */
    const val CHANNEL_ID = "xueti_daily_reminder_v2"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notify_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }
}
