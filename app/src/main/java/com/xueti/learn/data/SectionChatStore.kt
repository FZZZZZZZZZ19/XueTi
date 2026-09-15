package com.xueti.learn.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条小节对话消息 */
data class SectionChatMessage(
    val role: String,
    val text: String,
    val at: Long = System.currentTimeMillis()
) {
    val isUser: Boolean get() = role == ROLE_USER

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_AI = "assistant"
    }
}

/**
 * 小节 AI 对话记录（按「书 + 小节」分别保存），下次进来自动带出上次的讨论。
 *
 * 存储：`filesDir/chat_<bookId>_<hash>.json`
 */
class SectionChatStore(context: Context) {

    private val dir = context.filesDir

    @Synchronized
    fun load(bookId: String, sectionTitle: String): List<SectionChatMessage> {
        val file = fileFor(bookId, sectionTitle)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val text = o.optString("text")
                if (text.isBlank()) return@mapNotNull null
                SectionChatMessage(
                    role = o.optString("role", SectionChatMessage.ROLE_USER),
                    text = text,
                    at = o.optLong("at", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun append(
        bookId: String,
        sectionTitle: String,
        messages: List<SectionChatMessage>
    ): List<SectionChatMessage> {
        val all = load(bookId, sectionTitle).toMutableList()
        all.addAll(messages)
        save(bookId, sectionTitle, all)
        return all
    }

    @Synchronized
    fun clear(bookId: String, sectionTitle: String) {
        runCatching { fileFor(bookId, sectionTitle).delete() }
    }

    private fun save(bookId: String, sectionTitle: String, messages: List<SectionChatMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject().apply {
                    put("role", message.role)
                    put("text", message.text)
                    put("at", message.at)
                }
            )
        }
        runCatching { fileFor(bookId, sectionTitle).writeText(array.toString()) }
    }

    private fun fileFor(bookId: String, sectionTitle: String): File {
        val key = (bookId + "_" + sectionTitle).hashCode().toUInt().toString(16)
        return File(dir, "chat_$key.json")
    }
}
