package com.xueti.learn.ai

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
 * 图片输入：`deepseek-flash` 模型支持 `image_url` 内容块（base64 data URL），
 * 图片只能出现在 user 消息中。
 */
object DeepSeekClient {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

    data class Review(
        val answer: String,
        val model: String
    )

    /**
     * 提交一道题（文本 + 可选图片）
     * @param imageBase64 不含 `data:image/...;base64,` 前缀的原始 base64
     */
    suspend fun solve(
        apiKey: String,
        model: String,
        prompt: String,
        question: String,
        imageBase64: String?,
        imageMime: String = "image/jpeg"
    ): Result<Review> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildRequestBody(model, prompt, question, imageBase64, imageMime)
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 180_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    error(parseErrorMessage(text, code))
                }
                val answer = parseAnswer(text)
                if (answer.isBlank()) error("模型没有返回内容")
                Review(answer = answer, model = model)
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun buildRequestBody(
        model: String,
        prompt: String,
        question: String,
        imageBase64: String?,
        imageMime: String
    ): String {
        val json = JSONObject()
        json.put("model", model)
        json.put("stream", false)

        val messages = JSONArray()

        // 用户提示词与题目文本拼接
        val textBuilder = StringBuilder()
        if (prompt.isNotBlank()) textBuilder.append(prompt.trim())
        if (question.isNotBlank()) {
            if (textBuilder.isNotEmpty()) textBuilder.append("\n\n")
            textBuilder.append("【题目】\n").append(question.trim())
        } else if (imageBase64 != null) {
            if (textBuilder.isNotEmpty()) textBuilder.append("\n\n")
            textBuilder.append("【题目】见图片")
        }

        val userMessage = JSONObject().put("role", "user")
        if (imageBase64 == null) {
            userMessage.put("content", textBuilder.toString())
        } else {
            val content = JSONArray()
            content.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", textBuilder.toString())
            )
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:$imageMime;base64,$imageBase64")
                    )
            )
            userMessage.put("content", content)
        }
        messages.put(userMessage)
        json.put("messages", messages)
        return json.toString()
    }

    private fun parseAnswer(raw: String): String {
        val root = JSONObject(raw)
        val choices = root.optJSONArray("choices") ?: return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val content = message.optString("content").trim()
        if (content.isNotEmpty()) return content
        // 思考模式下内容可能在 reasoning_content
        return message.optString("reasoning_content").trim()
    }

    private fun parseErrorMessage(raw: String, code: Int): String {
        val fallback = "请求失败（HTTP $code）"
        if (raw.isBlank()) return fallback
        return runCatching {
            val error = JSONObject(raw).optJSONObject("error")
            val message = error?.optString("message").orEmpty()
            if (message.isNotBlank()) message else fallback
        }.getOrDefault(fallback)
    }
}
