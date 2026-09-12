package com.xueti.learn

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.CuratedAdapter
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.CuratedQuestion
import com.xueti.learn.data.CuratedStore
import com.xueti.learn.databinding.ActivityCuratedBinding
import com.xueti.learn.databinding.DialogCuratedDetailBinding
import com.xueti.learn.util.FormulaRenderer

/**
 * 精选题库：用户在小节例题里点「+」收藏的题目，按**书目 → 章节 → 小节**归类。
 *
 * - 列表：按书本分组，显示 章节 › 小节 路径与题干；支持搜索、只看未掌握
 * - 详情：题干 + 解答用 KaTeX 渲染，可标记已掌握 / 移除
 */
class CuratedActivity : BaseActivity() {

    private lateinit var binding: ActivityCuratedBinding
    private val store by lazy { CuratedStore(this) }

    private val adapter = CuratedAdapter(
        onClick = { item -> showDetail(item) },
        onLongClick = { item -> confirmRemove(item) }
    )

    private val searchWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = render()
    }

    /** 从某本书进去时只看这本书 */
    private val filterBookId: String get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCuratedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.curatedList.layoutManager = LinearLayoutManager(this)
        binding.curatedList.adapter = adapter
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
        val all = store.all().filter { filterBookId.isBlank() || it.bookId == filterBookId }
        val filtered = all.filter { item ->
            (!pendingOnly || !item.mastered) && (
                keyword.isEmpty() ||
                    item.question.contains(keyword, ignoreCase = true) ||
                    item.solution.contains(keyword, ignoreCase = true) ||
                    item.title.contains(keyword, ignoreCase = true) ||
                    item.bookTitle.contains(keyword, ignoreCase = true) ||
                    item.chapterTitle.contains(keyword, ignoreCase = true) ||
                    item.sectionTitle.contains(keyword, ignoreCase = true)
                )
        }
        adapter.submit(filtered)
        binding.emptyView.isVisible = filtered.isEmpty()
        binding.countText.text = getString(
            R.string.curated_count,
            all.size,
            all.count { !it.mastered }
        )
        val mastered = all.count { it.mastered }
        binding.btnClearMastered.isVisible = mastered > 0
        binding.btnClearMastered.text = getString(R.string.curated_clear_mastered, mastered)
    }

    private fun clearMastered() {
        val removed = store.clearMastered()
        if (removed > 0) toast(getString(R.string.curated_cleared, removed))
        render()
    }

    private fun confirmRemove(item: CuratedQuestion) {
        AlertDialog.Builder(this)
            .setTitle(R.string.curated_remove)
            .setMessage(getString(R.string.curated_remove_confirm, item.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.remove(item.id)
                toast(getString(R.string.curated_removed))
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDetail(item: CuratedQuestion) {
        val view = DialogCuratedDetailBinding.inflate(layoutInflater)
        val path = buildString {
            append(item.bookTitle)
            if (item.chapterTitle.isNotBlank()) append(" › ").append(item.chapterTitle)
            append(" › ").append(item.sectionTitle)
        }
        view.curatedPath.text = path
        FormulaRenderer.attach(view.curatedWeb, this)
        renderDetail(view, item)

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.title.ifBlank { getString(R.string.curated_default_title) })
            .setView(view.root)
            .setPositiveButton(
                if (item.mastered) R.string.mistake_unmark else R.string.mistake_mark_mastered,
                null
            )
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.curated_remove, null)
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
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                store.remove(current.id)
                toast(getString(R.string.curated_removed))
                dialog.dismiss()
                render()
            }
        }
        dialog.setOnDismissListener {
            FormulaRenderer.release(view.curatedWeb)
            view.curatedWeb.destroy()
        }
        dialog.show()
    }

    private fun renderDetail(view: DialogCuratedDetailBinding, item: CuratedQuestion) {
        val text = buildString {
            append("## 题目\n\n").append(item.question).append("\n\n")
            if (item.solution.isNotBlank()) {
                append("## 解答\n\n").append(item.solution).append("\n")
            } else {
                append(getString(R.string.curated_no_solution))
            }
        }
        FormulaRenderer.render(view.curatedWeb, text, this)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
