package com.xueti.learn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.CuratedStore
import com.xueti.learn.data.MistakeStore
import com.xueti.learn.data.PdfTextExtractor
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.data.UsageStore
import com.xueti.learn.databinding.ActivitySectionStudyBinding
import com.xueti.learn.databinding.DialogEditExampleBinding
import com.xueti.learn.databinding.DialogEditModuleBinding
import com.xueti.learn.databinding.DialogPageRangeBinding
import com.xueti.learn.databinding.ItemExampleQuestionBinding
import com.xueti.learn.model.ExampleItem
import com.xueti.learn.model.SectionContent
import com.xueti.learn.model.StudyStyle
import com.xueti.learn.util.FormulaRenderer
import com.xueti.learn.util.PeakHours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小章学习：让 AI 把本节整理成「知识点 / 公式 / 例题」三个模块。
 * - 语言风格可选：直白易懂 / 严谨全面（切换后可重新生成）
 * - **若这本书上传过教材 PDF**，AI 只依据 PDF 原文讲解（显著减少幻觉）
 * - 公式用内置 KaTeX 渲染；生成时自动带上错题本里的相关易错点
 */
class SectionStudyActivity : BaseActivity() {

    private lateinit var binding: ActivitySectionStudyBinding
    private val store by lazy { TextbookStore(this) }
    private val settings: SettingsStore get() = (application as App).settings
    private val usageStore by lazy { UsageStore(this) }
    private val mistakeStore by lazy { MistakeStore(this) }
    private val curatedStore by lazy { CuratedStore(this) }

    /** 例题区的 WebView（离开页面时统一销毁） */
    private val exampleWebViews = mutableListOf<android.webkit.WebView>()

    /** 正在编辑的例题 id（null 表示新增） */
    private var editingExampleId: String? = null

    /** 当前打开的例题编辑弹窗（选图返回时需先关掉，避免叠层） */
    private var editingDialog: AlertDialog? = null

    private val bookId: String get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
    private val chapterTitle: String get() = intent.getStringExtra(EXTRA_CHAPTER_TITLE).orEmpty()
    private val sectionTitle: String get() = intent.getStringExtra(EXTRA_SECTION_TITLE).orEmpty()

    private var currentContent: SectionContent? = null

    /** 添加例题时拍照 / 选图的临时文件 */
    private var cameraUri: android.net.Uri? = null

    private val takePicture =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraUri
            if (success && uri != null) extractQuestionFromImages(listOf(uri))
        }

    private val pickImages =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (!uris.isNullOrEmpty()) extractQuestionFromImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySectionStudyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.title = sectionTitle

        // 公式渲染（内置 KaTeX）
        FormulaRenderer.attach(binding.webKnowledge, this)
        FormulaRenderer.attach(binding.webFormulas, this)

        val book = store.get(bookId)
        renderContextText(book)

        val saved = book?.contents?.get(sectionTitle)
        val initialStyle = saved?.let { StudyStyle.fromKey(it.styleKey) }
            ?: StudyStyle.fromKey(book?.styleKey)
        binding.styleGroup.check(
            if (initialStyle == StudyStyle.RIGOROUS) binding.btnStyleRigorous.id
            else binding.btnStylePlain.id
        )

        binding.btnGenerate.setOnClickListener { generate() }
        binding.btnEditKnowledge.setOnClickListener {
            editModule(getString(R.string.module_knowledge), currentContent?.knowledge.orEmpty()) { text ->
                saveContent { it.copy(knowledge = text, customized = true) }
            }
        }
        binding.btnEditFormulas.setOnClickListener {
            editModule(getString(R.string.module_formula), currentContent?.formulas.orEmpty()) { text ->
                saveContent { it.copy(formulas = text, customized = true) }
            }
        }
        binding.btnNewExample.setOnClickListener { showAddExampleDialog() }

        if (saved != null) {
            render(saved)
            binding.statusText.text = getString(
                R.string.section_generated_at,
                StudyStyle.fromKey(saved.styleKey).label
            )
        }
    }

    /**
     * 顶部信息：教材 / 章 / 教材 PDF + **本节对应的 PDF 页码**（点击可改）。
     * v2.02：几百页的教材靠这个页码映射只取本节那几页原文，避免拿错内容。
     */
    private fun renderContextText(book: com.xueti.learn.model.Textbook?) {
        val range = book?.pageRangeOf(sectionTitle)
        binding.contextText.text = buildString {
            if (book != null) append("《").append(book.title).append("》")
            if (book?.publisher?.isNotBlank() == true) append(" · ").append(book.publisher)
            if (chapterTitle.isNotBlank()) append("\n").append(chapterTitle)
            if (book?.hasPdf == true) {
                append("\n").append(getString(R.string.section_grounded_by_pdf, book.sourcePdfName))
                append("\n")
                append(
                    if (range != null) {
                        getString(R.string.section_pages_mapped, range.label, range.pageCount)
                    } else {
                        getString(R.string.section_pages_unmapped)
                    }
                )
            }
            append("\n").append(PeakHours.statusText())
        }
        binding.contextText.setOnClickListener {
            if (book?.hasPdf == true) showPageRangeDialog(range)
        }
    }

    /** 手动设置 / 修正本节的 PDF 页范围（自动定位不准时用） */
    private fun showPageRangeDialog(current: com.xueti.learn.model.PageRange?) {
        val book = store.get(bookId) ?: return
        val view = DialogPageRangeBinding.inflate(layoutInflater)
        view.etStart.setText(current?.start?.toString().orEmpty())
        view.etEnd.setText(current?.end?.toString().orEmpty())
        view.pageRangeHint.text = getString(R.string.page_range_hint, book.sourcePdfPages)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.page_range_title)
            .setView(view.root)
            .setPositiveButton(R.string.goal_save, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.page_range_auto, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val pages = PdfTextExtractor.loadPages(this, bookId)
                val found = if (pages.isEmpty()) null else {
                    PdfTextExtractor.findSectionStart(pages, sectionTitle)
                }
                if (found == null) {
                    toast(R.string.page_range_auto_failed)
                } else {
                    view.etStart.setText(found.toString())
                    view.etEnd.setText((found + 7).coerceAtMost(maxOf(pages.size, found)).toString())
                    toast(getString(R.string.page_range_auto_ok, found))
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val start = view.etStart.text?.toString()?.trim()?.toIntOrNull()
                val end = view.etEnd.text?.toString()?.trim()?.toIntOrNull()
                if (start == null || end == null || start < 1 || end < start) {
                    view.startLayout.error = getString(R.string.page_range_invalid)
                    return@setOnClickListener
                }
                store.updatePageRange(bookId, sectionTitle, com.xueti.learn.model.PageRange(start, end))
                toast(getString(R.string.page_range_saved, "$start–$end"))
                renderContextText(store.get(bookId))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_section, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_curated -> {
                startActivity(
                    Intent(this, CuratedActivity::class.java)
                        .putExtra(CuratedActivity.EXTRA_BOOK_ID, bookId)
                )
                true
            }
            R.id.action_chat -> {
                startActivity(
                    Intent(this, SectionChatActivity::class.java)
                        .putExtra(SectionChatActivity.EXTRA_BOOK_ID, bookId)
                        .putExtra(SectionChatActivity.EXTRA_CHAPTER_TITLE, chapterTitle)
                        .putExtra(SectionChatActivity.EXTRA_SECTION_TITLE, sectionTitle)
                )
                true
            }
            R.id.action_copy_section -> {
                copyAll()
                true
            }
            R.id.action_clear_section -> {
                store.clearContent(bookId, sectionTitle)
                currentContent = null
                binding.knowledgeCard.isVisible = false
                binding.formulaCard.isVisible = false
                binding.exampleCard.isVisible = false
                binding.statusText.text = getString(R.string.section_generate_hint)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun currentStyle(): StudyStyle =
        if (binding.styleGroup.checkedButtonId == binding.btnStyleRigorous.id) {
            StudyStyle.RIGOROUS
        } else {
            StudyStyle.PLAIN
        }

    private fun generate() {
        val apiKey = settings.deepSeekApiKey
        if (apiKey.isBlank()) {
            toast(R.string.ai_need_key)
            return
        }
        // 用户手动改过内容时，重新生成会覆盖，先确认
        if (currentContent?.customized == true) {
            AlertDialog.Builder(this)
                .setTitle(R.string.section_regenerate)
                .setMessage(R.string.section_regenerate_confirm)
                .setPositiveButton(R.string.confirm) { _, _ -> doGenerate(apiKey) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        doGenerate(apiKey)
    }

    private fun doGenerate(apiKey: String) {
        val book = store.get(bookId)
        val style = currentStyle()

        // v2.02：优先用「本节对应的 PDF 页码」取原文；老书没有映射时退回按标题定位
        val pdfPages = PdfTextExtractor.loadPages(this, bookId)
        val range = book?.pageRangeOf(sectionTitle)
        val sourceText = when {
            pdfPages.isEmpty() -> null
            range != null -> PdfTextExtractor.pagesInRange(pdfPages, range).ifBlank { null }
            else -> PdfTextExtractor.sectionExcerpt(pdfPages, sectionTitle).ifBlank { null }
        }
        val focusPoints = mistakeStore
            .buildFocusPrompt(limit = 8, maxChars = 140, keyword = sectionTitle)
            .ifBlank { null }

        setLoading(true)
        binding.statusText.text = getString(R.string.section_generating)
        lifecycleScope.launch {
            val result = DeepSeekClient.generateSectionContent(
                apiKey = apiKey,
                model = settings.aiModel,
                bookTitle = book?.title ?: "",
                chapterTitle = chapterTitle,
                sectionTitle = sectionTitle,
                style = style,
                sourceText = sourceText,
                focusPoints = focusPoints
            )
            setLoading(false)
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { sectionResult ->
                store.saveContent(bookId, sectionTitle, sectionResult.content)
                render(sectionResult.content)
                val tokens = sectionResult.usage?.totalTokens ?: 0
                binding.statusText.text = buildString {
                    append(getString(R.string.section_generated_tokens, style.label, tokens))
                    if (sourceText != null) {
                        append(" · ").append(
                            if (range != null) {
                                getString(R.string.section_grounded_pages, range.label)
                            } else {
                                getString(R.string.section_grounded_short)
                            }
                        )
                    }
                    if (focusPoints != null) {
                        append(" · ").append(getString(R.string.section_with_mistakes))
                    }
                }
                if (pdfPages.isNotEmpty() && sourceText == null) {
                    binding.statusText.text = getString(R.string.section_pdf_not_found) +
                        " · " + binding.statusText.text
                }
            }.onFailure { error ->
                binding.statusText.text =
                    getString(R.string.section_generate_failed, error.message ?: "未知错误")
            }
        }
    }

    // ==================== 用户编辑（v2.00） ====================

    /** 保存小节内容（统一入口：写入本地并刷新界面） */
    private fun saveContent(transform: (SectionContent) -> SectionContent) {
        val base = currentContent ?: SectionContent(
            knowledge = "",
            formulas = "",
            examples = "",
            styleKey = currentStyle().key,
            generatedAt = System.currentTimeMillis()
        )
        val updated = transform(base)
        store.saveContent(bookId, sectionTitle, updated)
        render(updated)
    }

    /** 编辑「知识点 / 公式」这类整段文本（可自由增删） */
    private fun editModule(title: String, initial: String, onSaved: (String) -> Unit) {
        val view = DialogEditModuleBinding.inflate(layoutInflater)
        view.etContent.setText(initial)
        view.editLayout.hint = title
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.module_edit_title, title))
            .setView(view.root)
            .setPositiveButton(R.string.goal_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = view.etContent.text?.toString().orEmpty()
                onSaved(text)
                toast(getString(R.string.module_saved, title))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 「+ 添加例题」：手动输入 / 拍照 / 相册（后两者用 AI 从图片提取题目） */
    private fun showAddExampleDialog() {
        val options = arrayOf(
            getString(R.string.example_add_manual),
            getString(R.string.example_add_camera),
            getString(R.string.example_add_gallery)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.example_new)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showExampleEditDialog(null)
                    1 -> launchCamera()
                    else -> pickImages.launch(arrayOf("image/*"))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchCamera() {
        val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
        val file = java.io.File(dir, "example_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return
        cameraUri = uri
        runCatching { takePicture.launch(uri) }
            .onFailure { toast(R.string.ai_camera_unavailable) }
    }

    /** 拍照 / 选图后用 AI（视觉模型）把题目文字提取出来，再让用户确认/编辑 */
    private fun extractQuestionFromImages(uris: List<android.net.Uri>) {
        val apiKey = settings.deepSeekApiKey
        if (apiKey.isBlank()) {
            toast(R.string.ai_need_key)
            return
        }
        // 从图片提取题目时先关掉可能开着的编辑弹窗，避免叠两层
        editingDialog?.dismiss()
        editingDialog = null
        setLoading(true)
        binding.statusText.text = getString(R.string.example_extracting)
        lifecycleScope.launch {
            val images = withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.take(MAX_EXTRACT_IMAGES).mapNotNull { uri ->
                    runCatching {
                        val bitmap = com.xueti.learn.util.ImageUtils.decodeSampled(this@SectionStudyActivity, uri)
                            ?: return@runCatching null
                        com.xueti.learn.util.ImageUtils.toBase64(bitmap)
                    }.getOrNull()
                }
            }
            if (images.isEmpty()) {
                setLoading(false)
                binding.statusText.text = ""
                toast(R.string.ai_image_read_failed)
                return@launch
            }
            val result = DeepSeekClient.solve(
                apiKey = apiKey,
                model = settings.aiModel,
                prompt = getString(R.string.example_extract_prompt),
                question = "",
                images = images
            )
            setLoading(false)
            binding.statusText.text = ""
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { extracted ->
                showExampleEditDialog(null, extracted.answer)
            }.onFailure { error ->
                toast(getString(R.string.ai_failed, error.message ?: "未知错误"))
            }
        }
    }

    /**
     * 例题编辑弹窗：可自己写题干与做题步骤，也可让 AI 生成答案与步骤；
     * 还能在弹窗里直接选图让 AI 提取题目（用户自上传题目的场景）。
     */
    private fun showExampleEditDialog(existing: ExampleItem?, prefillQuestion: String = "") {
        val view = DialogEditExampleBinding.inflate(layoutInflater)
        val isNew = existing == null
        editingExampleId = existing?.id
        view.etQuestion.setText(existing?.question ?: prefillQuestion)
        view.etSolution.setText(existing?.solution.orEmpty())

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) R.string.example_new else R.string.example_edit)
            .setView(view.root)
            .setPositiveButton(R.string.goal_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        view.btnAiSolution.setOnClickListener {
            val question = view.etQuestion.text?.toString().orEmpty().trim()
            if (question.isEmpty()) {
                toast(R.string.example_need_question)
                return@setOnClickListener
            }
            val apiKey = settings.deepSeekApiKey
            if (apiKey.isBlank()) {
                toast(R.string.ai_need_key)
                return@setOnClickListener
            }
            view.dialogProgress.isVisible = true
            lifecycleScope.launch {
                val result = DeepSeekClient.solve(
                    apiKey = apiKey,
                    model = settings.aiModel,
                    prompt = getString(R.string.example_solution_prompt, sectionTitle),
                    question = question,
                    images = emptyList()
                )
                view.dialogProgress.isVisible = false
                usageStore.record(result.getOrNull()?.usage)
                result.onSuccess { answer ->
                    view.etSolution.setText(answer.answer)
                    toast(R.string.example_solution_done)
                }.onFailure { error ->
                    toast(getString(R.string.ai_failed, error.message ?: "未知错误"))
                }
            }
        }

        view.btnPickImage.setOnClickListener {
            toast(R.string.example_pick_image_tip)
            pickImages.launch(arrayOf("image/*"))
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val question = view.etQuestion.text?.toString().orEmpty().trim()
                val solution = view.etSolution.text?.toString().orEmpty().trim()
                if (question.isEmpty()) {
                    view.layoutQuestion.error = getString(R.string.example_need_question)
                    return@setOnClickListener
                }
                view.layoutQuestion.error = null
                val targetId = editingExampleId
                saveContent { content ->
                    val items = content.exampleItems.toMutableList()
                    val index = items.indexOfFirst { it.id == targetId }
                    val item = ExampleItem(
                        id = targetId ?: "user_${System.currentTimeMillis()}",
                        title = existing?.title?.ifBlank { null }
                            ?: getString(R.string.example_user_title, items.size + 1),
                        question = question,
                        solution = solution,
                        userAdded = existing?.userAdded ?: true
                    )
                    if (index >= 0) items[index] = item else items.add(item)
                    content.copy(
                        exampleItems = items,
                        examples = rebuildExamplesText(items),
                        customized = true
                    )
                }
                toast(if (isNew) R.string.example_saved_new else R.string.example_saved)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            if (editingDialog === dialog) editingDialog = null
        }
        editingDialog = dialog
        dialog.show()
    }

    /** 删除某道例题 */
    private fun deleteExample(example: ExampleItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.example_delete)
            .setMessage(getString(R.string.example_delete_confirm, example.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                saveContent { content ->
                    val items = content.exampleItems.filterNot { it.id == example.id }
                    content.copy(
                        exampleItems = items,
                        examples = rebuildExamplesText(items),
                        customized = true
                    )
                }
                toast(R.string.example_deleted)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 为某道例题生成答案与做题步骤（写回该题） */
    private fun generateSolutionFor(example: ExampleItem) {
        val apiKey = settings.deepSeekApiKey
        if (apiKey.isBlank()) {
            toast(R.string.ai_need_key)
            return
        }
        setLoading(true)
        binding.statusText.text = getString(R.string.example_solution_generating)
        lifecycleScope.launch {
            val result = DeepSeekClient.solve(
                apiKey = apiKey,
                model = settings.aiModel,
                prompt = getString(R.string.example_solution_prompt, sectionTitle),
                question = example.question,
                images = emptyList()
            )
            setLoading(false)
            binding.statusText.text = ""
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { answer ->
                saveContent { content ->
                    val items = content.exampleItems.map {
                        if (it.id == example.id) it.copy(solution = answer.answer) else it
                    }
                    content.copy(exampleItems = items, examples = rebuildExamplesText(items), customized = true)
                }
                toast(R.string.example_solution_done)
            }.onFailure { error ->
                toast(getString(R.string.ai_failed, error.message ?: "未知错误"))
            }
        }
    }

    /** 例题列表变化后，同步整段文本（复制全文 / 兼容旧逻辑用） */
    private fun rebuildExamplesText(items: List<ExampleItem>): String =
        items.joinToString("\n\n") { item ->
            buildString {
                append("### ").append(item.title.ifBlank { "例题" }).append("\n\n")
                append(item.question)
                if (item.solution.isNotBlank()) append("\n\n**解答**\n\n").append(item.solution)
            }
        }

    /** 三个模块都用 KaTeX 渲染；例题拆成单题，可编辑 / 删除 / 加入精选题库 */
    private fun render(content: SectionContent) {
        // 老缓存只有整段例题文本：这里拆成单题并回写，保证题目 id 稳定（便于后续编辑）
        val normalized = if (content.exampleItems.isEmpty() && content.examples.isNotBlank()) {
            content.copy(exampleItems = com.xueti.learn.util.ExampleParser.split(content.examples))
        } else {
            content
        }
        currentContent = normalized
        if (normalized !== content) {
            store.saveContent(bookId, sectionTitle, normalized)
        }
        renderModule(binding.webKnowledge, normalized.knowledge)
        renderModule(binding.webFormulas, normalized.formulas)
        renderExamples(normalized)
        binding.knowledgeCard.isVisible = true
        binding.formulaCard.isVisible = true
        binding.exampleCard.isVisible = true
    }

    /**
     * 例题区：每道题一张卡片（题干 + 解答用 KaTeX 渲染），
     * 右上角「+ 加入精选」把这道题收藏进精选题库（按书 / 章 / 节归类）。
     */
    private fun renderExamples(content: SectionContent) {
        val book = store.get(bookId)
        val items = content.exampleItems.ifEmpty {
            com.xueti.learn.util.ExampleParser.split(content.examples)
        }
        binding.exampleListContainer.removeAllViews()
        exampleWebViews.clear()

        if (items.isEmpty()) {
            binding.exampleListContainer.addView(
                android.widget.TextView(this).apply {
                    text = getString(R.string.example_empty)
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.stats_label))
                    textSize = 13f
                    setPadding(0, dp(8), 0, 0)
                }
            )
            binding.exampleHint.text = ""
            return
        }

        items.forEachIndexed { index, example ->
            val row = ItemExampleQuestionBinding.inflate(layoutInflater, binding.exampleListContainer, false)
            row.exampleTitle.text = buildString {
                append(example.title.ifBlank { getString(R.string.example_index, index + 1) })
                if (example.userAdded) append(" ").append(getString(R.string.example_user_tag))
            }
            FormulaRenderer.attach(row.exampleWeb, this)
            exampleWebViews.add(row.exampleWeb)
            renderExampleWeb(row.exampleWeb, example)

            fun refreshButton() {
                val added = curatedStore.isAdded(bookId, sectionTitle, example.question)
                row.btnAddExample.setText(if (added) R.string.example_added else R.string.example_add)
                row.btnAddExample.isEnabled = !added
                row.btnAddExample.alpha = if (added) 0.6f else 1f
            }
            refreshButton()

            row.btnAddExample.setOnClickListener {
                val added = curatedStore.add(
                    bookId = bookId,
                    bookTitle = book?.title ?: "",
                    chapterTitle = chapterTitle,
                    sectionTitle = sectionTitle,
                    example = example
                )
                if (added == null) {
                    toast(R.string.example_already_added)
                } else {
                    toast(getString(R.string.example_added_ok, added.title))
                }
                refreshButton()
                updateExampleHint(items)
            }

            row.btnEditExample.setOnClickListener { showExampleEditDialog(example) }
            row.btnAiSolveExample.setOnClickListener { generateSolutionFor(example) }
            row.btnDeleteExample.setOnClickListener { deleteExample(example) }

            binding.exampleListContainer.addView(row.root)
        }
        updateExampleHint(items)
    }

    /** 顶部提示：已收藏 x / y 道 + 是否手动编辑过 */
    private fun updateExampleHint(items: List<ExampleItem>) {
        val added = items.count { curatedStore.isAdded(bookId, sectionTitle, it.question) }
        val text = if (added > 0) {
            getString(R.string.example_added_hint, added, items.size)
        } else {
            getString(R.string.example_edit_hint)
        }
        binding.exampleHint.text = text
    }

    private fun renderExampleWeb(web: android.webkit.WebView, example: ExampleItem) {
        val text = buildString {
            append(example.question)
            if (example.solution.isNotBlank()) {
                append("\n\n**解答**\n\n").append(example.solution)
            }
        }
        FormulaRenderer.render(web, text, this) { heightCss ->
            val target = (heightCss * resources.displayMetrics.density).toInt() +
                (resources.displayMetrics.density * 10).toInt()
            val params = web.layoutParams
            val minimum = (70 * resources.displayMetrics.density).toInt()
            val finalHeight = maxOf(target, minimum)
            if (params.height != finalHeight) {
                params.height = finalHeight
                web.layoutParams = params
            }
        }
    }

    private fun renderModule(web: android.webkit.WebView, text: String) {
        FormulaRenderer.render(web, text, this) { heightCss ->
            val target = (heightCss * resources.displayMetrics.density).toInt() +
                (resources.displayMetrics.density * 18).toInt()
            val params = web.layoutParams
            val minimum = (96 * resources.displayMetrics.density).toInt()
            val finalHeight = maxOf(target, minimum)
            if (params.height != finalHeight) {
                params.height = finalHeight
                web.layoutParams = params
            }
        }
    }

    private fun copyAll() {
        val content = currentContent ?: return
        val text = buildString {
            append("【").append(sectionTitle).append("】\n\n")
            append("■ ").append(getString(R.string.module_knowledge)).append("\n")
            append(content.knowledge).append("\n\n")
            append("■ ").append(getString(R.string.module_formula)).append("\n")
            append(content.formulas).append("\n\n")
            append("■ ").append(getString(R.string.module_example)).append("\n")
            append(content.examples)
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(sectionTitle, text))
        toast(R.string.ai_copied)
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.btnGenerate.isEnabled = !loading
        binding.btnGenerate.text = getString(
            if (loading) R.string.section_generating else R.string.section_start_study
        )
    }

    override fun onDestroy() {
        listOf(binding.webKnowledge, binding.webFormulas).forEach { web ->
            FormulaRenderer.release(web)
            runCatching { web.destroy() }
        }
        exampleWebViews.forEach { web ->
            FormulaRenderer.release(web)
            runCatching { web.destroy() }
        }
        exampleWebViews.clear()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER_TITLE = "chapter_title"
        const val EXTRA_SECTION_TITLE = "section_title"

        /** 从图片提取题目时最多送几张（控制 token 与耗时） */
        private const val MAX_EXTRACT_IMAGES = 3
    }
}
