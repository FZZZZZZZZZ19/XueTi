package com.xueti.learn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
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
import com.xueti.learn.databinding.ItemExampleQuestionBinding
import com.xueti.learn.model.ExampleItem
import com.xueti.learn.model.SectionContent
import com.xueti.learn.model.StudyStyle
import com.xueti.learn.util.FormulaRenderer
import com.xueti.learn.util.PeakHours
import kotlinx.coroutines.launch

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

    private val bookId: String get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
    private val chapterTitle: String get() = intent.getStringExtra(EXTRA_CHAPTER_TITLE).orEmpty()
    private val sectionTitle: String get() = intent.getStringExtra(EXTRA_SECTION_TITLE).orEmpty()

    private var currentContent: SectionContent? = null

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
        binding.contextText.text = buildString {
            if (book != null) append("《").append(book.title).append("》")
            if (book?.publisher?.isNotBlank() == true) append(" · ").append(book.publisher)
            if (chapterTitle.isNotBlank()) append("\n").append(chapterTitle)
            if (book?.hasPdf == true) {
                append("\n").append(getString(R.string.section_grounded_by_pdf, book.sourcePdfName))
            }
        }

        val saved = book?.contents?.get(sectionTitle)
        val initialStyle = saved?.let { StudyStyle.fromKey(it.styleKey) }
            ?: StudyStyle.fromKey(book?.styleKey)
        binding.styleGroup.check(
            if (initialStyle == StudyStyle.RIGOROUS) binding.btnStyleRigorous.id
            else binding.btnStylePlain.id
        )

        binding.btnGenerate.setOnClickListener { generate() }
        binding.contextText.append(
            "\n${PeakHours.statusText()}"
        )

        if (saved != null) {
            render(saved)
            binding.statusText.text = getString(
                R.string.section_generated_at,
                StudyStyle.fromKey(saved.styleKey).label
            )
        }
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
        val book = store.get(bookId)
        val style = currentStyle()

        // 有教材 PDF：按小节标题定位原文（严格接地）；再带上相关错题本易错点
        val pdfPages = PdfTextExtractor.loadPages(this, bookId)
        val sourceText = if (pdfPages.isNotEmpty()) {
            PdfTextExtractor.sectionExcerpt(pdfPages, sectionTitle).ifBlank { null }
        } else {
            null
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
                        append(" · ").append(getString(R.string.section_grounded_short))
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

    /** 三个模块都用 KaTeX 渲染；例题拆成单题，每道题可单独加入精选题库 */
    private fun render(content: SectionContent) {
        currentContent = content
        renderModule(binding.webKnowledge, content.knowledge)
        renderModule(binding.webFormulas, content.formulas)
        renderExamples(content)
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

        val alreadyCount = items.count {
            curatedStore.isAdded(bookId, sectionTitle, it.question)
        }
        binding.exampleHint.text = if (alreadyCount > 0) {
            getString(R.string.example_added_hint, alreadyCount, items.size)
        } else {
            getString(R.string.example_add_hint)
        }

        items.forEachIndexed { index, example ->
            val row = ItemExampleQuestionBinding.inflate(layoutInflater, binding.exampleListContainer, false)
            row.exampleTitle.text = example.title.ifBlank { getString(R.string.example_index, index + 1) }
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
                val nowCount = items.count { curatedStore.isAdded(bookId, sectionTitle, it.question) }
                binding.exampleHint.text = if (nowCount > 0) {
                    getString(R.string.example_added_hint, nowCount, items.size)
                } else {
                    getString(R.string.example_add_hint)
                }
            }

            binding.exampleListContainer.addView(row.root)
        }
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
    }
}
