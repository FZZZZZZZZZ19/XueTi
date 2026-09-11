package com.xueti.learn.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.xueti.learn.R
import com.xueti.learn.StudyActivity
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.util.NotificationHelper

/**
 * 每日学习提醒：读取今日进度并推送通知，点击直接进入学习页。
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        // Android 13+ 未授予通知权限时直接跳过
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val store = ProgressStore(context)
        val goal = store.dailyGoal
        val learned = store.todayLearnedCount()
        val remain = (goal - learned).coerceAtLeast(0)

        val text = if (remain > 0) {
            context.getString(R.string.notify_text_remain, learned, goal, remain)
        } else {
            context.getString(R.string.notify_text_done)
        }

        val intent = Intent(context, StudyActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
        return Result.success()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
