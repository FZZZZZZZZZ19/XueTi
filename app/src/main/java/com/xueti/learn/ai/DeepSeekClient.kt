package com.xueti.learn.ai

import com.xueti.learn.model.Chapter
import com.xueti.learn.model.ExampleItem
import com.xueti.learn.model.Section
import com.xueti.learn.model.SectionContent
import com.xueti.learn.model.StudyStyle
import com.xueti.learn.util.ExampleParser
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
    private const val MODELS_ENDPOINT = "https://api.deepseek.com/models"

    /** 小节对话最多带上多少轮历史（避免上下文过长） */
    private const val MAX_CHAT_HISTORY = 12

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

    /** 纯文本问答结果（翻译 / 造句等） */
    data class AskResult(val text: String, val usage: Usage?)

    /** 翻译方向 */
    enum class TranslateDirection(val label: String, val instruction: String) {
        AUTO("自动识别", "如果原文是中文就翻译成地道的英文，如果是英文就翻译成中文"),
        ZH_TO_EN("中 → 英", "把下面的中文翻译成地道的英文"),
        EN_TO_ZH("英 → 中", "把下面的英文翻译成中文")
    }

    private data class ChatResult(val text: String, val usage: Usage?)

    // ---------------- 通用文本问答 ----------------

    /** 通用问答（非 JSON 输出），供翻译、造句等使用 */
    suspend fun ask(apiKey: String, model: String, prompt: String): Result<AskResult> = runCatching {
        val result = chat(apiKey, model, prompt, jsonMode = false).getOrThrow()
        if (result.text.isBlank()) error("模型没有返回内容")
        AskResult(result.text, result.usage)
    }

    /** 中英互翻 */
    suspend fun translate(
        apiKey: String,
        model: String,
        text: String,
        direction: TranslateDirection
    ): Result<AskResult> = ask(
        apiKey,
        model,
        buildString {
            append("你是专业翻译。请").append(direction.instruction).append("。\n")
            append("要求：\n")
            append("1) 只输出译文，不要任何解释或前后缀；\n")
            append("2) 如果是单词，请补充词性与常见义项（用「；」分隔）；\n")
            append("3) 保留原有的专有名词、数字与换行格式；\n")
            append("4) 若有多义，最多列出 3 个最常用的译法。\n\n")
            append("【原文】\n").append(text.trim())
        }
    )

    /** AI 造句：为某个单词生成例句（含中文翻译与搭配总结） */
    suspend fun generateSentences(
        apiKey: String,
        model: String,
        word: String
    ): Result<AskResult> = ask(
        apiKey,
        model,
        buildString {
            append("你是英语老师。请用单词「").append(word.trim()).append("」造 3 个地道的例句。\n")
            append("要求：\n")
            append("1) 每句先给英文，再在下一行给出中文翻译；\n")
            append("2) 难度适合大学英语六级，句子体现该词最常见的搭配；\n")
            append("3) 三句分别对应不同的词性/义项（如该词只有一种，则体现不同语境）；\n")
            append("4) 最后用「搭配提示：」总结常见搭配与易错点（一句话）。\n")
        }
    )

    // ---------------- 小节 AI 对话（多轮，带当前小节上下文） ----------------

    /** 一条对话消息（role: "user" / "assistant"） */
    data class ChatTurn(val role: String, val text: String)

    /**
     * 小节实时对话：每次调用都把**当前小节的知识点 / 公式 / 例题**当作上下文，
     * 再带上历史对话，让 AI 能针对本小节答疑、补充缺漏。
     */
    suspend fun askAboutSection(
        apiKey: String,
        model: String,
        bookTitle: String,
        chapterTitle: String,
        sectionTitle: String,
        knowledge: String,
        formulas: String,
        examples: String,
        history: List<ChatTurn>,
        question: String
    ): Result<AskResult> {
        val prompt = buildString {
            append("你是这本教材的答疑老师。下面是学生正在学的这一小节的**完整资料**，")
            append("回答必须紧扣这些资料，并优先使用资料里的定义、符号与结论。\n\n")
            append("【教材】《").append(bookTitle.ifBlank { "未提供" }).append("》\n")
            if (chapterTitle.isNotBlank()) append("【大章】").append(chapterTitle).append("\n")
            append("【小节】").append(sectionTitle).append("\n\n")

            append("====== 本小节资料（知识点）======\n")
            append(knowledge.trim().ifEmpty { "（本节暂无知识点内容）" }).append("\n\n")
            append("====== 本小节资料（公式）======\n")
            append(formulas.trim().ifEmpty { "（本节暂无公式）" }).append("\n\n")
            append("====== 本小节资料（例题）======\n")
            append(examples.trim().ifEmpty { "（本节暂无例题）" }).append("\n")
            append("\n====== 资料结束 ======\n\n")

            if (history.isNotEmpty()) {
                append("【此前对话】\n")
                history.takeLast(MAX_CHAT_HISTORY).forEach { turn ->
                    append(if (turn.role == "user") "学生：" else "你：")
                        .append(turn.text.trim()).append("\n")
                }
                append("\n")
            }

            append("【学生现在的问题】\n").append(question.trim()).append("\n\n")
            append("回答要求：\n")
            append("1) 先直接回答学生的问题；\n")
            append("2) 如果学生指出资料有缺漏或错误，请指出**具体是知识点 / 公式 / 例题中的哪一条**，")
            append("并给出**可以直接补充进资料的完整内容**（方便他复制粘贴）；\n")
            append("3) 需要时举例说明；\n")
            append("4) 所有数学公式用 LaTeX（行内 \$...\$，独立公式 \$\$...\$\$）；\n")
            append("5) 中文回答，条理清晰，不要说客套话。")
        }
        return ask(apiKey, model, prompt)
    }

    // ---------------- 可用模型列表 ----------------

    /** 获取当前账号可用的模型：GET /models */
    suspend fun fetchModels(apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(MODELS_ENDPOINT).openConnection() as HttpURLConnection).apply {
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

                    val data = JSONObject(text).optJSONArray("data")
                        ?: error("接口未返回模型列表")
                    val models = (0 until data.length()).mapNotNull { i ->
                        data.optJSONObject(i)?.optString("id")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    if (models.isEmpty()) error("可用模型列表为空")
                    models
                } finally {
                    runCatching { connection.disconnect() }
                }
            }
        }

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
            // 统一追加排版要求：App 端用 KaTeX 渲染公式，必须是标准 LaTeX
            if (isNotEmpty()) append("\n\n")
            append("【排版要求】用 Markdown 组织；所有数学公式用 LaTeX（行内 $...$，独立公式 $$...$$），")
            append("不要用 Unicode 拼公式；步骤分行写清楚。")
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
        style: StudyStyle,
        /** 教材 PDF 抽取的原文片段（提供时以它为准，显著减少幻觉） */
        sourceText: String? = null,
        /** 错题本易错点（生成时重点覆盖） */
        focusPoints: String? = null
    ): Result<OutlineResult> = runCatching {
        val grounded = !sourceText.isNullOrBlank()
        val prompt = buildString {
            append("你是教材目录整理助手。请为我整理这本书的完整目录（大章 → 小章两级）。\n")
            append("书名：《").append(title.ifBlank { "未提供" }).append("》\n")
            if (publisher.isNotBlank()) append("出版社：").append(publisher).append("\n")
            if (edition.isNotBlank()) append("版次：").append(edition).append("\n")
            if (grounded) {
                append("\n【教材原文（唯一依据）】\n")
                append("下面是用户上传的教材 PDF 中抽取的原文片段：\n")
                append("<<<PDF\n").append(sourceText!!.trim()).append("\nPDF>>>\n")
            }
            if (!outlineText.isNullOrBlank()) {
                append("\n以下是用户提供的目录/大纲内容，请以此为准整理：\n")
                append(outlineText.trim()).append("\n")
            }
            if (!focusPoints.isNullOrBlank()) {
                append("\n").append(focusPoints.trim()).append("\n")
            }
            if (images.isNotEmpty()) {
                append("\n用户提供了 ").append(images.size)
                append(" 张目录页照片，请识别其中所有章节目录，合并整理（可能有跨页内容）。\n")
            }
            append("\n要求：\n")
            append("1) 尽量完整覆盖全书，一级为大章（如「第一章 绪论」），二级为小章（如「1.1 研究背景」）；\n")
            if (grounded) {
                append("2) **只使用上面 PDF 原文中真实出现的章节标题与编号**；原文没有的内容一律不要添加；\n")
                append("3) 如果原文片段只覆盖了部分章节，就只输出覆盖到的部分，不要用「常识」补全；\n")
                append("4) 如果错题本里的易错点正好落在某个小节，请在该小节标题后追加「（易错·重点）」；\n")
            } else {
                append("2) 若信息不足，按该学科通用教材结构合理推断，但不要编造与书名明显无关的内容；\n")
                append("3) 如果错题本里的易错点正好落在某个小节，请在该小节标题后追加「（易错·重点）」；\n")
            }
            append("5) 语言风格：").append(style.prompt).append("\n")
            append("6) 只输出 JSON，不要任何解释文字。\n")
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
        style: StudyStyle,
        /** 教材 PDF 里与本小节相关的原文（提供时以它为准） */
        sourceText: String? = null,
        /** 错题本易错点（本小节相关） */
        focusPoints: String? = null
    ): Result<SectionResult> = runCatching {
        val grounded = !sourceText.isNullOrBlank()
        val prompt = buildString {
            append("你是教材讲解助手，请为下面这一小节整理学习内容。\n")
            append("教材：《").append(bookTitle).append("》\n")
            append("大章：").append(chapterTitle).append("\n")
            append("小节：").append(sectionTitle).append("\n\n")
            if (grounded) {
                append("【教材原文（唯一依据）】\n")
                append("下面是该教材 PDF 中与本小节相关的原文：\n")
                append("<<<PDF\n").append(sourceText!!.trim()).append("\nPDF>>>\n\n")
                append("**必须严格依据上面的原文讲解**：原文没有出现的定义、公式、例题一律不要添加；\n")
                append("如果原文信息不足，请直接写「原文未提及」，不要用你自己的知识补充。\n\n")
            } else {
                append("注意：本次没有教材原文，请按该学科的通用知识讲解，不要编造具体页码或原文引用。\n\n")
            }
            if (!focusPoints.isNullOrBlank()) {
                append(focusPoints.trim()).append("\n")
                append("请在讲解中针对这些易错点额外给出提醒或例题。\n\n")
            }
            append("语言风格要求：").append(style.prompt).append("\n\n")
            append("请严格分成三个模块输出（没有内容时写明「本节无公式」等，不要留空）：\n")
            append("- knowledge：本节知识点（概念、定义、原理、公式的适用条件、易错点、记忆要点）\n")
            append("- formulas：本节涉及的公式；**必须用 LaTeX 书写**（行内 $...$，独立公式 \$\$...\$\$），" +
                "例如 \$\$S = v_0 t + \\frac{1}{2} a t^2\$\$，并逐条说明每个符号的含义与单位；不要用纯文本凑公式\n")
            append("- examples：2~3 道典型例题，**每题单独一个对象**（公式同样用 LaTeX）：\n")
            append("  · title：题目标题，如「例题 1 求极限」；\n")
            append("  · question：题干（只写题目，不要写解答）；\n")
            append("  · solution：分步解答。\n\n")
            append("只输出 JSON，不要任何解释文字。\n")
            append(
                "JSON 格式：{\"knowledge\":\"...\",\"formulas\":\"...\"," +
                    "\"examples\":[{\"title\":\"例题 1 ...\",\"question\":\"...\",\"solution\":\"...\"}]}"
            )
        }

        val result = chat(apiKey, model, prompt, jsonMode = true).getOrThrow()
        val root = JSONObject(extractJson(result.text))
        val rawExamples = root.opt("examples")
        val exampleItems: List<ExampleItem>
        val examplesText: String
        when (rawExamples) {
            // 新格式：结构化的多道例题
            is JSONArray -> {
                exampleItems = (0 until rawExamples.length()).mapNotNull { index ->
                    val o = rawExamples.optJSONObject(index) ?: return@mapNotNull null
                    val question = o.optString("question").trim()
                    val solution = o.optString("solution").trim()
                    val title = o.optString("title").trim().ifEmpty { "例题 ${index + 1}" }
                    if (question.isEmpty() && solution.isEmpty()) {
                        null
                    } else {
                        ExampleItem(
                            id = "ex_${System.currentTimeMillis()}_$index",
                            title = title,
                            question = question.ifEmpty { "（题干见解答）" },
                            solution = solution
                        )
                    }
                }
                examplesText = exampleItems.joinToString("\n\n") { item ->
                    "### ${item.title}\n\n${item.question}\n\n${item.solution}".trim()
                }
            }
            // 兼容：模型仍返回整段文本
            else -> {
                examplesText = root.optString("examples").trim().ifEmpty { "（未生成例题）" }
                exampleItems = ExampleParser.split(examplesText)
            }
        }

        SectionResult(
            content = SectionContent(
                knowledge = root.optString("knowledge").trim().ifEmpty { "（未生成知识点）" },
                formulas = root.optString("formulas").trim().ifEmpty { "（未生成公式）" },
                examples = examplesText.ifEmpty { "（未生成例题）" },
                styleKey = style.key,
                generatedAt = System.currentTimeMillis(),
                exampleItems = exampleItems
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
