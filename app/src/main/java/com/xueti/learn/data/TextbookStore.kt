package com.xueti.learn.data

import android.content.Context
import com.xueti.learn.model.SectionContent
import com.xueti.learn.model.Textbook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 全书学习数据存储：保存书本目录（大章/小章）与小章 AI 生成的三模块内容。
 * 存放在应用私有目录的 JSON 文件中（内容可能较大，避免占用 SharedPreferences）。
 */
class TextbookStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun all(): List<Textbook> {
        val text = readFile() ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { Textbook.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Textbook? = all().firstOrNull { it.id == id }

    /** 新增或整体覆盖一本书 */
    fun upsert(textbook: Textbook) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == textbook.id }
        if (index >= 0) list[index] = textbook else list.add(textbook)
        write(list)
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
        // 同时清掉这本书的 PDF 原文缓存，避免占空间
        PdfTextExtractor.deletePages(context, id)
    }

    /** 保存某个小章的生成结果 */
    fun saveContent(textbookId: String, sectionId: String, content: SectionContent) {
        val textbook = get(textbookId) ?: return
        val contents = textbook.contents.toMutableMap()
        contents[sectionId] = content
        upsert(textbook.copy(contents = contents))
    }

    fun clearContent(textbookId: String, sectionId: String) {
        val textbook = get(textbookId) ?: return
        val contents = textbook.contents.toMutableMap()
        contents.remove(sectionId)
        upsert(textbook.copy(contents = contents))
    }

    private fun readFile(): String? =
        runCatching { if (file.exists()) file.readText(Charsets.UTF_8) else null }.getOrNull()

    private fun write(list: List<Textbook>) {
        runCatching {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            file.writeText(array.toString(), Charsets.UTF_8)
        }
    }

    companion object {
        private const val FILE_NAME = "textbooks.json"
    }
}
