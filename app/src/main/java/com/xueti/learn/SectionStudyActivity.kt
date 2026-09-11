package com.xueti.learn

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.databinding.ActivitySectionStudyBinding
import com.xueti.learn.model.SectionContent
import com.xueti.learn.model.StudyStyle
import kotlinx.coroutines.launch

/**
 * 小章学习：让 AI 把本节整理成「知识点 / 公式 / 例题」三个模块，分别显示在三个框中。
 * 语言风格可选：直白易懂 / 严谨全面（切换后可重新生成）。
 */
class SectionStudyActivity : BaseActivity() {

    private lateinit var binding: ActivitySectionStudyBinding
    private val store by lazy { TextbookStore(this) }
    private val settings: SettingsStore get() = (application as App).settings

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

        val book = store.get(bookId)
        binding.contextText.text = buildString {
            if (book != null) append("《").append(book.title).append("》")
            if (book?.publisher?.isNotBlank() == true) append(" · ").append(book.publisher)
            if (chapterTitle.isNotBlank()) append("\n").append(chapterTitle)
        }

        val saved = book?.contents?.get(sectionTitle)
        val initialStyle = saved?.let { StudyStyle.fromKey(it.styleKey) }
            ?: StudyStyle.fromKey(book?.styleKey)
        binding.styleGroup.check(
            if (initialStyle == StudyStyle.RIGOROUS) binding.btnStyleRigorous.id
            else binding.btnStylePlain.id
        )

        binding.btnGenerate.setOnClickListener { generate() }

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
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

        setLoading(true)
        binding.statusText.text = getString(R.string.section_generating)
        lifecycleScope.launch {
            val result = DeepSeekClient.generateSectionContent(
                apiKey = apiKey,
                model = settings.aiModel,
                bookTitle = book?.title ?: "",
                chapterTitle = chapterTitle,
                sectionTitle = sectionTitle,
                style = style
            )
            setLoading(false)
            result.onSuccess { content ->
                store.saveContent(bookId, sectionTitle, content)
                render(content)
                binding.statusText.text = getString(R.string.section_generated_at, style.label)
            }.onFailure { error ->
                binding.statusText.text =
                    getString(R.string.section_generate_failed, error.message ?: "未知错误")
            }
        }
    }

    private fun render(content: SectionContent) {
        currentContent = content
        binding.tvKnowledge.text = content.knowledge
        binding.tvFormulas.text = content.formulas
        binding.tvExamples.text = content.examples
        binding.knowledgeCard.isVisible = true
        binding.formulaCard.isVisible = true
        binding.exampleCard.isVisible = true
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

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER_TITLE = "chapter_title"
        const val EXTRA_SECTION_TITLE = "section_title"
    }
}
