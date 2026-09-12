package com.xueti.learn

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.UsageStore
import com.xueti.learn.data.WordRepository
import com.xueti.learn.util.NotificationHelper
import com.xueti.learn.work.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var wordRepository: WordRepository
        private set
    lateinit var progressStore: ProgressStore
        private set
    lateinit var settings: SettingsStore
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // PDF 文本抽取需要先初始化 PDFBox 的资源（字体/映射表）
        runCatching { PDFBoxResourceLoader.init(this) }
        wordRepository = WordRepository(this)
        progressStore = ProgressStore(this)
        settings = SettingsStore(this)

        // 后台预加载词库（约 6600 词），用户点进学习页时通常已就绪
        appScope.launch { wordRepository.load() }

        // 通知渠道 + 恢复每日提醒
        NotificationHelper.ensureChannel(this)
        if (settings.reminderEnabled) {
            ReminderScheduler.schedule(this, settings.reminderHour, settings.reminderMinute)
        }
        // 恢复谷时（优惠时段）提醒
        val usageStore = UsageStore(this)
        if (usageStore.offPeakReminder) {
            ReminderScheduler.scheduleOffPeak(this)
        }
    }
}
