package com.xueti.learn.data

import android.content.Context

/**
 * 应用设置（界面风格 / 自定义背景 / 更新 / 每日提醒）
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("xueti_settings", Context.MODE_PRIVATE)

    /** 界面风格 key（见 ThemeStyle） */
    var themeStyle: String
        get() = prefs.getString(KEY_THEME_STYLE, "purple") ?: "purple"
        set(value) = prefs.edit().putString(KEY_THEME_STYLE, value).apply()

    /** 自定义背景图 URI（为空表示默认背景） */
    var backgroundUri: String?
        get() = prefs.getString(KEY_BACKGROUND_URI, null)
        set(value) = prefs.edit().putString(KEY_BACKGROUND_URI, value).apply()

    /** 背景图暗化/柔化强度（0-95，越大背景越淡，文字越清晰） */
    var backgroundDim: Int
        get() = prefs.getInt(KEY_BACKGROUND_DIM, 70)
        set(value) = prefs.edit().putInt(KEY_BACKGROUND_DIM, value.coerceIn(0, 95)).apply()

    /** 启动时自动检查更新 */
    var autoCheckUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** 上次自动检查更新的日期（yyyy-MM-dd，避免每天多次提示） */
    var lastUpdateCheckDate: String?
        get() = prefs.getString(KEY_LAST_UPDATE_CHECK, null)
        set(value) = prefs.edit().putString(KEY_LAST_UPDATE_CHECK, value).apply()

    /** 每日学习提醒开关 */
    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    /** 提醒时间：小时 */
    var reminderHour: Int
        get() = prefs.getInt(KEY_REMINDER_HOUR, 20)
        set(value) = prefs.edit().putInt(KEY_REMINDER_HOUR, value.coerceIn(0, 23)).apply()

    /** 提醒时间：分钟 */
    var reminderMinute: Int
        get() = prefs.getInt(KEY_REMINDER_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_REMINDER_MINUTE, value.coerceIn(0, 59)).apply()

    companion object {
        private const val KEY_THEME_STYLE = "theme_style"
        private const val KEY_BACKGROUND_URI = "background_uri"
        private const val KEY_BACKGROUND_DIM = "background_dim"
        private const val KEY_AUTO_UPDATE = "auto_check_update"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_REMINDER_MINUTE = "reminder_minute"
    }
}
