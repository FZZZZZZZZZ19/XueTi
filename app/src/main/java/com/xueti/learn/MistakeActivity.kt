package com.xueti.learn

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.MistakeAdapter
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.MistakeItem
import com.xueti.learn.data.MistakeStore
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.UsageStore
import com.xueti.learn.databinding.ActivityMistakesBinding
import com.xueti.learn.databinding.DialogMistakeDetailBinding
import com.xueti.learn.util.FormulaRenderer
import kotlinx.coroutines.launch

/**
 * 错题本：自动收集做错/算错的题目（AI 解题点「这题我算错了」、复习时标记），
 * 详情用 KaTeX 渲染公式，可「AI 再讲一遍」「补充错因」「标记已掌握」。
 */
class MistakeActivity : BaseActivity() {

    private lateinit var binding: ActivityMistakesBinding
    private val store by lazy { MistakeStore(this) }
    private val settings by lazy { SettingsStore(this) }
    private val usageStore by lazy { UsageStore(this) }

    private val adapter = MistakeAdapter { item -> showDetail(item) }

    private val searchWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMistakesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.mistakeList.layoutManager = LinearLayoutManager(this)
        binding.mistakeList.adapter = adapter
        binding.etSearch.addTextChangedListener(searchWatcher)
        binding.swPendingOnly.setOnCheckedChangeListener { _, _ -> render() }
        binding.btnClearMastered.setOnClickListener { clearMastered() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun render() {
        val keyword = binding.etSearch.text?.toString().orEmpty().trim()
        val pendingOnly = binding.swPendingOnly.isChecked
        val all = store.all()
        val filtered = all.filter { item ->
            (!pendingOnly || !item.mastered) && (
                keyword.isEmpty() ||
                    item.question.contains(keyword, ignoreCase = true) ||
                    item.aiAnswer.contains(keyword, ignoreCase = true) ||
                    item.subject.contains(keyword, ignoreCase = true)
                )
        }
        adapter.submit(filtered)
        binding.emptyView.isVisible = filtered.isEmpty()
        binding.countText.text = getString(
            R.string.mistake_count,
            all.size,
            all.count { !it.mastered }
        )
        val mastered = all.count { it.mastered }
        binding.btnClearMastered.isVisible = mastered > 0
        binding.btnClearMastered.text = getString(R.string.mistake_clear_mastered, mastered)
    }

    private fun clearMastered() {
        val removed = store.clearMastered()
        if (removed > 0) toast(getString(R.string.mistake_cleared, removed))
        render()
    }

    private fun showDetail(item: MistakeItem) {
        val view = DialogMistakeDetailBinding.inflate(layoutInflater)
        FormulaRenderer.attach(view.mistakeWeb, this)
        renderDetail(view, item)

        view.noteText.text = if (item.note.isBlank()) {
            getString(R.string.mistake_note_empty)
        } else {
            getString(R.string.mistake_note_label, item.note)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.mistake_detail_title)
            .setView(view.root)
            .setPositiveButton(
                if (item.mastered) R.string.mistake_unmark else R.string.mistake_mark_mastered,
                null
            )
            .setNegativeButton(R.string.close, null)
            .create()

        var current = item
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target = !current.mastered
                store.setMastered(current.id, target)
                current = current.copy(mastered = target)
                toast(getString(if (target) R.string.mistake_marked else R.string.mistake_unmarked))
                dialog.dismiss()
                render()
            }
        }
        dialog.setOnDismissListener {
            FormulaRenderer.release(view.mistakeWeb)
            view.mistakeWeb.destroy()
        }

        view.btnEditNote.setOnClickListener {
            editNote(current) { note ->
                current = current.copy(note = note)
                view.noteText.text = if (note.isBlank()) {
                    getString(R.string.mistake_note_empty)
                } else {
                    getString(R.string.mistake_note_label, note)
                }
            }
        }

        view.btnReExplain.setOnClickListener {
            reExplain(view, current)
        }

        dialog.show()
    }

    /** 详情内容：题目 + AI 原来的解答（用 KaTeX 渲染公式） */
    private fun renderDetail(view: DialogMistakeDetailBinding, item: MistakeItem) {
        val text = buildString {
            append("## 题目\n\n").append(item.question).append("\n\n")
            if (item.aiAnswer.isNotBlank()) {
                append("## AI 解答\n\n").append(item.aiAnswer).append("\n\n")
            }
            if (item.note.isNotBlank()) {
                append("## 我的错因\n\n").append(item.note).append("\n")
            }
        }
        FormulaRenderer.render(view.mistakeWeb, text, this)
    }

    private fun editNote(item: MistakeItem, onSaved: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(item.note)
            hint = getString(R.string.mistake_note_hint)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.mistake_edit_note)
            .setView(input)
            .setPositiveButton(R.string.goal_save) { _, _ ->
                val note = input.text?.toString().orEmpty().trim()
                store.updateNote(item.id, note)
                toast(getString(R.string.mistake_note_saved))
                onSaved(note)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** AI 再讲一遍：分析错因 + 完整解法，结果直接渲染进详情 */
    private fun reExplain(view: DialogMistakeDetailBinding, item: MistakeItem) {
        val apiKey = settings.deepSeekApiKey
        if (apiKey.isBlank()) {
            toast(getString(R.string.ai_need_key))
            return
        }
        val prompt = buildString {
            append("下面是一道用户做错的题目，请用中文讲清楚：\n")
            append("1) 最可能的错因（1-2 句）；\n")
            append("2) 完整、分步的正确解法；\n")
            append("3) 一句话总结这类题的通用方法或易错点。\n")
            append("要求：所有数学公式用 LaTeX 表示（行内 $...$，独立公式 $$...$$），不要用 Unicode 拼公式。\n\n")
            append("【题目】\n").append(item.question).append("\n")
            if (item.aiAnswer.isNotBlank()) {
                append("\n【之前的解答（可能有错，仅供参考）】\n").append(item.aiAnswer).append("\n")
            }
            if (item.note.isNotBlank()) {
                append("\n【用户自述错因】\n").append(item.note).append("\n")
            }
        }
        view.mistakeProgress.isVisible = true
        lifecycleScope.launch {
            val result = DeepSeekClient.ask(apiKey, settings.aiModel, prompt)
            view.mistakeProgress.isVisible = false
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { ask ->
                store.markReviewed(item.id)
                val text = buildString {
                    append("## 题目\n\n").append(item.question).append("\n\n")
                    append("## AI 再讲一遍\n\n").append(ask.text).append("\n")
                }
                FormulaRenderer.render(view.mistakeWeb, text, this@MistakeActivity)
                render()
            }.onFailure { error ->
                toast(getString(R.string.ai_failed, error.message ?: "未知错误"))
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
