package com.xueti.learn.data

import android.content.Context
import android.util.JsonReader
import com.xueti.learn.model.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 词库读取（assets/cet6_words.json，约 6600 词 / 1.4MB）。
 *
 * 为性能与内存考虑：
 * - 使用 [JsonReader] 流式解析，不构建完整 JSON 树（比 org.json 快且省内存）
 * - 解析在 IO 线程执行，结果缓存，重复调用不重复解析
 */
class WordRepository(private val context: Context) {

    private val mutex = Mutex()

    @Volatile
    private var cache: List<Word>? = null

    /** 已缓存的词库（未加载完成时为空列表，不阻塞主线程） */
    fun cached(): List<Word> = cache ?: emptyList()

    /** 加载词库（首次会解析 JSON，之后直接返回缓存） */
    suspend fun load(): List<Word> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) { parse() }.also { cache = it }
        }
    }

    private fun parse(): List<Word> {
        val result = ArrayList<Word>(7000)
        context.assets.open(FILE_NAME).bufferedReader().use { reader ->
            JsonReader(reader).use { json ->
                json.beginObject()
                while (json.hasNext()) {
                    if (json.nextName() == "words") {
                        json.beginArray()
                        while (json.hasNext()) {
                            result.add(readWord(json))
                        }
                        json.endArray()
                    } else {
                        json.skipValue()
                    }
                }
                json.endObject()
            }
        }
        return result
    }

    private fun readWord(json: JsonReader): Word {
        var word = ""
        var phonetic = ""
        var pos = ""
        var meaning = ""
        var example = ""
        var exampleCn = ""
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "word" -> word = json.nextString()
                "phonetic" -> phonetic = json.nextString()
                "pos" -> pos = json.nextString()
                "meaning" -> meaning = json.nextString()
                "example" -> example = json.nextString()
                "exampleCn" -> exampleCn = json.nextString()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Word(word, phonetic, pos, meaning, example, exampleCn)
    }

    companion object {
        private const val FILE_NAME = "cet6_words.json"
    }
}
