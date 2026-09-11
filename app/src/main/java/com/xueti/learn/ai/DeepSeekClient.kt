package com.xueti.learn.ai

import com.xueti.learn.model.Chapter
import com.xueti.learn.model.Section
import com.xueti.learn.model.SectionContent
import com.xueti.learn.model.StudyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 对话补全客户端（OpenAI 兼容格式）。
 *
 * - 支持**多张图片**（`content` 数组中放多个 `image_url` 块，最多 600 张，单图 ≤32MiB）
 * - 结构化输出：`response_format = json_object`，模型不支持时自动去掉该参数重试
 * - 返回值携带 `usage`（token 用量），供计费统计使用
 */
object DeepSeekClient {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val BALANCE_ENDPOINT = "https://api.deepseek.com/user/balance"

    /** token 用量 */
    data class Usage(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
        val cacheHitTokens: Int
    )

    data class Review(val answer: String, val model: String, val usage: Usage?)

    data class OutlineResult(val chapters: List<Chapter>, val usage: Usage?)

    data class SectionResult(val content: SectionContent, val usage: Usage?)

    /** 账户余额 */
    data class BalanceInfo(
        val currency: String,
        val total: String,
        val granted: String,
        val toppedUp: String,
        val available: Boolean
    )

    private data class ChatResult(val text: String, val usage: Usage?)

    // ---------------- 账户余额 ----------------

    /** 查询账户余额：GET /user/balance */
    suspend fun fetchBalance(apiKey: String): Result<BalanceInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(BALANCE_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                try {
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) error(parseErrorMessage(text, code))

                    val root = JSONObject(text)
                    val infos = root.optJSONArray("balance_infos")
                    if (infos == null || infos.length() == 0) error("接口未返回余额信息")
                    val first = infos.optJSONObject(0) ?: error("接口未返回余额信息")
                    BalanceInfo(
                        currency = first.optString("currency", "CNY"),
                        total = first.optString("total_balance", "0"),
                        granted = first.optString("granted_balance", "0"),
                        toppedUp = first.optString("topped_up_balance", "0"),
                        available = root.optBoolean("is_available", true)
                    )
                } finally {
                    runCatching { connection.disconnect() }
                }
            }
        }

    // ---------------- 解题（多图 / 文字） ----------------

    suspend fun solve(
        apiKey: String,
        model: String,
        prompt: String,
        question: String,
        images: List<String>,
        imageMime: String = "image/jpeg"
    ): Result<Review> = runCatching {
        val text = buildString {
            if (prompt.isNotBlank()) append(prompt.trim())
            if (question.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("【题目】\n").append(question.trim())
            }
            if (images.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("【题目图片】共 ").append(images.size).append(" 张，请综合所有图片作答")
            }
        }

        val result = if (images.isEmpty()) {
            chat(apiKey, model, text, jsonMode = false).getOrThrow()
        } else {
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", text))
            images.forEach { base64 ->
                content.put(
                    JSONObject().put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:$imageMime;base64,$base64")
                        )
                )
            }
            chat(apiKey, model, content, jsonMode = false).getOrThrow()
        }
        if (result.text.isBlank()) error("模型没有返回内容")
        Review(answer = result.text, model = model, usage = result.usage)
    }

    // ---------------- 全书目录 ----------------

    suspend fun generateOutline(
        apiKey: String,
        model: String,
        title: String,
        publisher: String,
        edition: String,
        outlineText: String?,
        images: List<String>,
        style: StudyStyle
    ): Result<OutlineResult> = runCatching {
        val prompt = buildString {
            append("你是教材目录整理助手。请为我整理这本书的完整目录（大章 → 小章两级）。\n")
            append("书名：《").append(title.ifBlank { "未提供" }).append("》\n")
            if (publisher.isNotBlank()) append("出版社：").append(publisher).append("\n")
            if (edition.isNotBlank()) append("版次：").append(edition).append("\n")
            if (!outlineText.isNullOrBlank()) {
                append("\n以下是用户提供的目录/大纲内容，请以此为准整理：\n")
                append(outlineText.trim()).append("\n")
            }
            if (images.isNotEmpty()) {
                append("\n用户提供了 ").append(images.size)
                append(" 张目录页照片，请识别其中所有章节目录，合并整理（可能有跨页内容）。\n")
            }
            append("\n要求：\n")
            append("1) 尽量完整覆盖全书，一级为大章（如「第一章 绪论」），二级为小章（如「1.1 研究背景」）；\n")
            append("2) 若信息不足，按该学科通用教材结构合理推断，但不要编造与书名明显无关的内容；\n")
            append("3) 语言风格：").append(style.prompt).append("\n")
            append("4) 只输出 JSON，不要任何解释文字。\n")
            append("JSON 格式：{\"chapters\":[{\"title\":\"第一章 绪论\",\"sections\":[\"1.1 xxx\",\"1.2 xxx\"]}]}")
        }

        val result = if (images.isEmpty()) {
            chat(apiKey, model, prompt, jsonMode = true).getOrThrow()
        } else {
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", prompt))
            images.forEach { base64 ->
                content.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))
                )
            }
            chat(apiKey, model, content, jsonMode = true).getOrThrow()
        }

        val root = JSONObject(extractJson(result.text))
        val chaptersArray = root.optJSONArray("chapters") ?: error("未解析到章节目录")
        val chapters = (0 until chaptersArray.length()).mapNotNull { i ->
            val chapterObj = chaptersArray.optJSONObject(i) ?: return@mapNotNull null
            val chapterTitle = chapterObj.optString("title").trim()
            if (chapterTitle.isEmpty()) return@mapNotNull null
            val sectionsArray = chapterObj.optJSONArray("sections") ?: JSONArray()
            val sections = (0 until sectionsArray.length()).mapNotNull { j ->
                val raw = sectionsArray.opt(j)
                val sectionTitle = when (raw) {
                    is String -> raw.trim()
                    is JSONObject -> raw.optString("title").trim()
                    else -> ""
                }
                if (sectionTitle.isEmpty()) null else Section(id = sectionTitle, title = sectionTitle)
            }
            Chapter(id = chapterTitle, title = chapterTitle, sections = sections)
        }
        if (chapters.isEmpty()) error("目录为空，请补充书名或上传目录照片后重试")
        OutlineResult(chapters, result.usage)
    }

    // ---------------- 小章三模块内容 ----------------

    suspend fun generateSectionContent(
        apiKey: String,
        model: String,
        bookTitle: String,
        chapterTitle: String,
        sectionTitle: String,
        style: StudyStyle
    ): Result<SectionResult> = runCatching {
        val prompt = buildString {
            append("你是教材讲解助手，请为下面这一小节整理学习内容。\n")
            append("教材：《").append(bookTitle).append("》\n")
            append("大章：").append(chapterTitle).append("\n")
            append("小节：").append(sectionTitle).append("\n\n")
            append("语言风格要求：").append(style.prompt).append("\n\n")
            append("请严格分成三个模块输出（没有内容时写明「本节无公式」等，不要留空）：\n")
            append("- knowledge：本节知识点（概念、定义、原理、公式的适用条件、易错点、记忆要点）\n")
            append("- formulas：本节涉及的公式，用纯文本排版（如 S = v·t），并逐条说明每个符号的含义与单位\n")
            append("- examples：2~3 道典型例题，每题给出「题目」与「分步解答」\n\n")
            append("只输出 JSON，不要任何解释文字。\n")
            append("JSON 格式：{\"knowledge\":\"...\",\"formulas\":\"...\",\"examples\":\"...\"}")
        }

        val result = chat(apiKey, model, prompt, jsonMode = true).getOrThrow()
        val root = JSONObject(extractJson(result.text))
        SectionResult(
            content = SectionContent(
                knowledge = root.optString("knowledge").trim().ifEmpty { "（未生成知识点）" },
                formulas = root.optString("formulas").trim().ifEmpty { "（未生成公式）" },
                examples = root.optString("examples").trim().ifEmpty { "（未生成例题）" },
                styleKey = style.key,
                generatedAt = System.currentTimeMillis()
            ),
            usage = result.usage
        )
    }

    // ---------------- HTTP ----------------

    private suspend fun chat(
        apiKey: String,
        model: String,
        userContent: Any,
        jsonMode: Boolean
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (jsonMode) {
                request(apiKey, model, userContent, jsonMode = true).getOrElse { error ->
                    if (error.message?.contains("response_format") == true ||
                        error.message?.contains("HTTP 400") == true
                    ) {
                        request(apiKey, model, userContent, jsonMode = false).getOrThrow()
                    } else {
                        throw error
                    }
                }
            } else {
                request(apiKey, model, userContent, jsonMode = false).getOrThrow()
            }
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        userContent: Any,
        jsonMode: Boolean
    ): Result<ChatResult> = runCatching {
        val body = JSONObject().apply {
            put("model", model)
            put("stream", false)
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "user").put("content", userContent))
            put("messages", messages)
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 300_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(parseErrorMessage(text, code))
            parseChatResult(text)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun parseChatResult(raw: String): ChatResult {
        val root = JSONObject(raw)
        val choices = root.optJSONArray("choices")
        val message = choices?.optJSONObject(0)?.optJSONObject("message")
        val content = message?.optString("content")?.trim().orEmpty()
        val answer = content.ifEmpty { message?.optString("reasoning_content")?.trim().orEmpty() }

        val usageObj = root.optJSONObject("usage")
        val usage = usageObj?.let {
            Usage(
                promptTokens = it.optInt("prompt_tokens", 0),
                completionTokens = it.optInt("completion_tokens", 0),
                totalTokens = it.optInt("total_tokens", 0),
                cacheHitTokens = it.optInt("prompt_cache_hit_tokens", 0)
            )
        }
        return ChatResult(answer, usage)
    }

    private fun parseErrorMessage(raw: String, code: Int): String {
        val fallback = "请求失败（HTTP $code）"
        if (raw.isBlank()) return fallback
        return runCatching {
            val message = JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
            if (message.isNotBlank()) message else fallback
        }.getOrDefault(fallback)
    }

    /** 从模型输出中提取 JSON（兼容 ```json 代码块与前后多余文字） */
    private fun extractJson(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val end = text.lastIndexOf("```")
            if (end >= 0) text = text.substring(0, end)
            text = text.trim()
        }
        val start = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (start >= 0 && last > start) {
            return text.substring(start, last + 1)
        }
        return text
    }
}
