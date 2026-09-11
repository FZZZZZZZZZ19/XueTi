package com.xueti.learn.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 一条待办 */
data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean,
    val createdAt: Long,
    val doneAt: Long = 0L
)

/**
 * 每日待办：用户自己添加的待办事项（勾选完成 / 删除 / 清理已完成）。
 *
 * 跨天自动整理：新的一天开始时，已完成的待办被清掉，**未完成的顺延**到当天，
 * 这样昨天的遗留任务不会丢，也不会让列表越来越长。
 */
class ToDoStore(context: Context) {

    private val prefs = context.getSharedPreferences("xueti_todo", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 当天待办（会先做跨天整理） */
    fun items(): List<TodoItem> {
        rollOverIfNewDay()
        return load()
    }

    fun add(text: String): TodoItem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val item = TodoItem(
            id = UUID.randomUUID().toString(),
            text = trimmed,
            done = false,
            createdAt = System.currentTimeMillis()
        )
        save(load() + item)
        return item
    }

    fun setDone(id: String, done: Boolean) {
        save(
            load().map {
                if (it.id == id) {
                    it.copy(done = done, doneAt = if (done) System.currentTimeMillis() else 0L)
                } else {
                    it
                }
            }
        )
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    /** 清理已完成的待办，返回清理数量 */
    fun clearDone(): Int {
        val all = load()
        val remain = all.filterNot { it.done }
        save(remain)
        return all.size - remain.size
    }

    /** 未完成数量（可顺延到明天） */
    fun pendingCount(): Int = load().count { !it.done }

    // ---------------- 内部 ----------------

    private fun rollOverIfNewDay() {
        if (prefs.getString(KEY_DATE, null) == today()) return
        // 新的一天：已完成的不再保留，未完成的顺延
        val pending = load().filterNot { it.done }
        save(pending)
        prefs.edit().putString(KEY_DATE, today()).apply()
    }

    private fun today(): String = dateFormat.format(Date())

    private fun load(): List<TodoItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val text = o.optString("text").trim()
                if (text.isEmpty()) return@mapNotNull null
                TodoItem(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    text = text,
                    done = o.optBoolean("done", false),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    doneAt = o.optLong("doneAt", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<TodoItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("done", item.done)
                    put("createdAt", item.createdAt)
                    put("doneAt", item.doneAt)
                }
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).putString(KEY_DATE, today()).apply()
    }

    private companion object {
        const val KEY_ITEMS = "items"
        const val KEY_DATE = "date"
    }
}
