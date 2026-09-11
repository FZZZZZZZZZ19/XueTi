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

    // ---------------- AI 解题（用户自备 Key / 提示词） ----------------

    /** DeepSeek API Key（由用户自行填写） */
    var deepSeekApiKey: String
        get() = prefs.getString(KEY_DEEPSEEK_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DEEPSEEK_KEY, value.trim()).apply()

    /** 用户自定义提示词 */
    var aiPrompt: String
        get() = prefs.getString(KEY_AI_PROMPT, null) ?: DEFAULT_AI_PROMPT
        set(value) = prefs.edit().putString(KEY_AI_PROMPT, value).apply()

    /** 使用的模型（deepseek-flash 支持图片理解） */
    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, null) ?: "deepseek-flash"
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value.trim().ifEmpty { "deepseek-flash" }).apply()

    companion object {
        private const val KEY_THEME_STYLE = "theme_style"
        private const val KEY_BACKGROUND_URI = "background_uri"
        private const val KEY_BACKGROUND_DIM = "background_dim"
        private const val KEY_AUTO_UPDATE = "auto_check_update"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_REMINDER_MINUTE = "reminder_minute"
        private const val KEY_DEEPSEEK_KEY = "deepseek_api_key"
        private const val KEY_AI_PROMPT = "ai_prompt"
        private const val KEY_AI_MODEL = "ai_model"

        /** 默认提示词（用户可在界面中修改） */
        const val DEFAULT_AI_PROMPT =
            "你是一位耐心的辅导老师。请根据用户提供的题目（文字或图片）：\n" +
                "1) 先给出正确答案；\n" +
                "2) 再分步骤讲解解题思路与关键知识点；\n" +
                "3) 如果是英语题，请说明词汇/语法考点并给出例句；\n" +
                "4) 指出容易出错的地方。\n" +
                "用中文简洁回答，公式或代码用等宽文本排版。"
    }
}
