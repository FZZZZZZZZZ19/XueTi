package com.xueti.learn.util

import android.content.Context

/**
 * 模型目录：内置已知模型 + 缓存从 API 拉取的可用模型列表，并提供各模型的高峰默认单价。
 */
object ModelCatalog {

    private const val PREFS = "xueti_models"
    private const val KEY_MODELS = "models"

    /** 内置兜底模型（来自官方定价页） */
    val BUILT_IN = listOf("deepseek-flash", "deepseek-v4-pro")

    /** 各模型高峰时段默认单价（元/百万 tokens）：输入 to 输出 */
    private val PRICING = mapOf(
        "deepseek-flash" to (2f to 8f),
        "deepseek-v4-pro" to (9f to 27f)
    )

    /** 从缓存读取模型列表（没有则返回内置列表） */
    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MODELS, null) ?: return BUILT_IN
        val list = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) BUILT_IN else list
    }

    fun save(context: Context, models: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODELS, models.joinToString(","))
            .apply()
    }

    /** 某模型的高峰默认单价；未知模型返回 null */
    fun pricingFor(model: String): Pair<Float, Float>? {
        val key = model.trim().lowercase()
        PRICING[key]?.let { return it }
        return PRICING.entries.firstOrNull { key.startsWith(it.key) }?.value
    }

    /** 该模型是否支持图片理解 */
    fun supportsVision(model: String): Boolean =
        model.trim().lowercase().startsWith("deepseek-flash")

    /** 汇总下拉选项：缓存/API 列表 + 当前选择 + 内置模型 */
    fun options(context: Context, current: String): List<String> =
        (load(context) + listOf(current) + BUILT_IN)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
