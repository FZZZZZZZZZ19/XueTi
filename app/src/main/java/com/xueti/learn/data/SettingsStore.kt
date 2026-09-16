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

    /** 自定义提醒句子（留空则只显示今日进度） */
    var reminderText: String
        get() = prefs.getString(KEY_REMINDER_TEXT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_REMINDER_TEXT, value.trim()).apply()

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

    // ---------------- 提醒自检（v2.03） ----------------

    /** 最近一次成功排定闹钟的时间（0 = 从未排定） */
    var reminderScheduledAt: Long
        get() = prefs.getLong(KEY_REMINDER_SCHEDULED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_REMINDER_SCHEDULED_AT, value).apply()

    /** 最近一次**实际触发**提醒的时间（0 = 从未触发，可用来判断闹钟是否被系统吃掉） */
    var reminderFiredAt: Long
        get() = prefs.getLong(KEY_REMINDER_FIRED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_REMINDER_FIRED_AT, value).apply()

    companion object {
        private const val KEY_THEME_STYLE = "theme_style"
        private const val KEY_BACKGROUND_URI = "background_uri"
        private const val KEY_BACKGROUND_DIM = "background_dim"
        private const val KEY_AUTO_UPDATE = "auto_check_update"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_REMINDER_MINUTE = "reminder_minute"
        private const val KEY_REMINDER_TEXT = "reminder_text"
        private const val KEY_DEEPSEEK_KEY = "deepseek_api_key"
        private const val KEY_AI_PROMPT = "ai_prompt"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_REMINDER_SCHEDULED_AT = "reminder_scheduled_at"
        private const val KEY_REMINDER_FIRED_AT = "reminder_fired_at"

        /** 默认提示词（可在 设置 → AI 接口 → 解题提示词 中修改） */
        const val DEFAULT_AI_PROMPT =
            "你是一位耐心、严谨的辅导老师。请根据用户提供的题目（文字或图片）回答：\n" +
                "1) 先给出**最终答案**（用「答案：」开头，一行写完）；\n" +
                "2) 再**分步骤**讲解解题思路，每步说清用了哪个定义、公式或定理；\n" +
                "3) 如果是英语题，说明词汇 / 语法考点并给出例句；\n" +
                "4) 最后指出**最容易出错的地方**（常见错解与原因）。\n" +
                "\n" +
                "排版要求（很重要）：\n" +
                "- 用 Markdown 组织内容（小标题、有序列表、必要时用表格）；\n" +
                "- **所有数学公式必须用 LaTeX**：行内公式写成 $...$，独立成行的公式写成 $$...$$；" +
                "不要用 Unicode 字符拼公式，不要用等宽文本画分数或根号；\n" +
                "- 矩阵、方程组、分段函数用 \\begin{pmatrix}、\\begin{cases}、\\begin{aligned} 等标准环境；\n" +
                "- 计算结果保留必要精度，单位和正负号写清楚。\n" +
                "\n" +
                "如果题目信息不足，请说明缺少什么，并给出在该假设下的解答。"
    }
}
