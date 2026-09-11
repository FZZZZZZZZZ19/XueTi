package com.xueti.learn.util

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xueti.learn.R
import com.xueti.learn.StudyActivity
import com.xueti.learn.data.ProgressStore

/** 每日学习提醒通知的构建与发送（定时触发与「测试提醒」共用） */
object ReminderNotifier {

    const val NOTIFICATION_ID = 1001

    fun canNotify(context: Context): Boolean {
        val permissionOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun show(context: Context, test: Boolean = false) {
        if (!canNotify(context)) return

        val store = ProgressStore(context)
        val goal = store.dailyGoal
        val learned = store.todayLearnedCount()
        val remain = (goal - learned).coerceAtLeast(0)

        val text = if (remain > 0) {
            context.getString(R.string.notify_text_remain, learned, goal, remain)
        } else {
            context.getString(R.string.notify_text_done)
        }
        val title = context.getString(
            if (test) R.string.notify_title_test else R.string.notify_title
        )
        post(context, title, text)
    }

    /** 谷时（优惠时段）开始提醒 */
    fun showOffPeak(context: Context) {
        if (!canNotify(context)) return
        post(
            context,
            context.getString(R.string.notify_offpeak_title),
            context.getString(R.string.notify_offpeak_text)
        )
    }

    private fun post(context: Context, title: String, text: String) {
        val intent = Intent(context, StudyActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        NotificationHelper.ensureChannel(context)

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
