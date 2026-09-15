package com.xueti.learn

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.ChatAdapter
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.SectionChatMessage
import com.xueti.learn.data.SectionChatStore
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.data.UsageStore
import com.xueti.learn.databinding.ActivitySectionChatBinding
import kotlinx.coroutines.launch

/**
 * 小节 AI 实时对话：
 * 每次发送都会把**当前小节的知识点 / 公式 / 例题**当作上下文（从本地最新内容读取，
 * 所以手动编辑过的内容也会带上），用于追问疑点、补充缺漏。
 */
class SectionChatActivity : BaseActivity() {

    private lateinit var binding: ActivitySectionChatBinding
    private val textbookStore by lazy { TextbookStore(this) }
    private val chatStore by lazy { SectionChatStore(this) }
    private val settings: SettingsStore get() = (application as App).settings
    private val usageStore by lazy { UsageStore(this) }

    private val adapter = ChatAdapter { text -> copy(text) }
    private val messages = mutableListOf<SectionChatMessage>()

    private val bookId: String get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
    private val chapterTitle: String get() = intent.getStringExtra(EXTRA_CHAPTER_TITLE).orEmpty()
    private val sectionTitle: String get() = intent.getStringExtra(EXTRA_SECTION_TITLE).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySectionChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.title = getString(R.string.chat_title_section, sectionTitle)

        binding.chatList.layoutManager = LinearLayoutManager(this)
        binding.chatList.adapter = adapter

        binding.btnSend.setOnClickListener { send() }
        binding.contextToggle.setOnClickListener {
            val expanded = binding.contextText.maxLines == Int.MAX_VALUE
            binding.contextText.maxLines = if (expanded) 2 else Int.MAX_VALUE
            binding.contextText.ellipsize = if (expanded) android.text.TextUtils.TruncateAt.END else null
        }

        messages.clear()
        messages.addAll(chatStore.load(bookId, sectionTitle))
        adapter.submit(messages)
        renderContext()
        if (messages.isEmpty()) {
            binding.etQuestion.setText(getString(R.string.chat_first_hint))
        }
        scrollToBottom()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        R.id.action_clear_chat -> {
            AlertDialog.Builder(this)
                .setTitle(R.string.chat_clear)
                .setMessage(R.string.chat_clear_confirm)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    chatStore.clear(bookId, sectionTitle)
                    messages.clear()
                    adapter.submit(messages)
                    toast(getString(R.string.chat_cleared))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** 顶部显示本次会带给 AI 的上下文明细（知识点 / 公式 / 例题字数） */
    private fun renderContext() {
        val content = textbookStore.get(bookId)?.contents?.get(sectionTitle)
        val book = textbookStore.get(bookId)
        val knowledgeLen = content?.knowledge?.length ?: 0
        val formulaLen = content?.formulas?.length ?: 0
        val exampleCount = content?.exampleItems?.size ?: 0
        binding.contextText.text = buildString {
            append(getString(R.string.chat_context_summary, book?.title ?: "", sectionTitle))
            append("\n")
            append(
                getString(
                    R.string.chat_context_detail,
                    knowledgeLen,
                    formulaLen,
                    exampleCount
                )
            )
            if (content == null) {
                append("\n").append(getString(R.string.chat_no_content))
            }
        }
    }

    private fun send() {
        val question = binding.etQuestion.text?.toString().orEmpty().trim()
        if (question.isEmpty()) {
            toast(R.string.chat_need_question)
            return
        }
        val apiKey = settings.deepSeekApiKey
        if (apiKey.isBlank()) {
            toast(R.string.ai_need_key)
            return
        }

        // 每次发送都重新读取最新小节内容（包含用户手动编辑的部分）
        val book = textbookStore.get(bookId)
        val content = book?.contents?.get(sectionTitle)
        val knowledge = content?.knowledge.orEmpty()
        val formulas = content?.formulas.orEmpty()
        val examples = content?.examples.orEmpty()

        val userMessage = SectionChatMessage(SectionChatMessage.ROLE_USER, question)
        messages.add(userMessage)
        adapter.submit(messages)
        binding.etQuestion.setText("")
        scrollToBottom()

        val history = messages.dropLast(1).map {
            DeepSeekClient.ChatTurn(it.role, it.text)
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = DeepSeekClient.askAboutSection(
                apiKey = apiKey,
                model = settings.aiModel,
                bookTitle = book?.title ?: "",
                chapterTitle = chapterTitle,
                sectionTitle = sectionTitle,
                knowledge = knowledge,
                formulas = formulas,
                examples = examples,
                history = history,
                question = question
            )
            setLoading(false)
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { answer ->
                val aiMessage = SectionChatMessage(SectionChatMessage.ROLE_AI, answer.text)
                chatStore.append(bookId, sectionTitle, listOf(userMessage, aiMessage))
                messages.add(aiMessage)
                adapter.submit(messages)
                scrollToBottom()
                // 顶部的资料可能已被编辑，重新统计
                renderContext()
            }.onFailure { error ->
                toast(getString(R.string.ai_failed, error.message ?: "未知错误"))
                // 失败时把问题退回输入框，方便重发
                binding.etQuestion.setText(question)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.btnSend.isEnabled = !loading
        binding.btnSend.text = getString(if (loading) R.string.chat_sending else R.string.chat_send)
    }

    private fun scrollToBottom() {
        if (messages.isEmpty()) return
        binding.chatList.post {
            binding.chatList.smoothScrollToPosition(messages.size - 1)
        }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.chat_title), text))
        toast(R.string.ai_copied)
    }

    override fun onDestroy() {
        adapter.releaseWebViews()
        super.onDestroy()
    }

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
