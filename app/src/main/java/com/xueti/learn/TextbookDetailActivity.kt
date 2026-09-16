package com.xueti.learn

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.ChapterAdapter
import com.xueti.learn.adapter.ChapterRow
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.PdfBookImporter
import com.xueti.learn.data.PdfFileStore
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.databinding.ActivityTextbookDetailBinding
import com.xueti.learn.model.StudyStyle
import kotlinx.coroutines.launch

/** 书本目录：大章 / 小章，点击小章开始学习；菜单里可补上传 PDF、划分章节页数、预览 PDF */
class TextbookDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityTextbookDetailBinding
    private val store by lazy { TextbookStore(this) }
    private val bookId: String get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addPdf(uri)
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private val adapter = ChapterAdapter { chapterTitle, section ->
        startActivity(
            Intent(this, SectionStudyActivity::class.java)
                .putExtra(SectionStudyActivity.EXTRA_BOOK_ID, bookId)
                .putExtra(SectionStudyActivity.EXTRA_CHAPTER_TITLE, chapterTitle)
                .putExtra(SectionStudyActivity.EXTRA_SECTION_TITLE, section.title)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextbookDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.chapterList.layoutManager = LinearLayoutManager(this)
        binding.chapterList.adapter = adapter
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_book_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_book_page_map -> {
                startActivity(
                    Intent(this, PageMapActivity::class.java)
                        .putExtra(PageMapActivity.EXTRA_BOOK_ID, bookId)
                )
                true
            }
            R.id.action_book_add_pdf -> {
                // v2.05：原本没有 PDF 的书也能后来补上传
                pickPdf.launch(arrayOf("application/pdf"))
                true
            }
            R.id.action_book_pdf_preview -> {
                if (!PdfFileStore.hasFile(this, bookId)) {
                    toast(getString(R.string.pdf_preview_no_file))
                } else {
                    startActivity(
                        Intent(this, PdfPreviewActivity::class.java)
                            .putExtra(PdfPreviewActivity.EXTRA_BOOK_ID, bookId)
                            .putExtra(PdfPreviewActivity.EXTRA_START_PAGE, 1)
                    )
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 补上传 PDF：导入完成后引导去划分页数 */
    private fun addPdf(uri: android.net.Uri) {
        lifecycleScope.launch {
            val result = PdfBookImporter.import(this@TextbookDetailActivity, store, bookId, uri)
            result.onSuccess { imported ->
                toast(getString(R.string.book_pdf_added, imported.pageCount))
                render()
                AlertDialog.Builder(this@TextbookDetailActivity)
                    .setTitle(R.string.page_map_title)
                    .setMessage(R.string.book_pdf_added_ask_map)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        startActivity(
                            Intent(this@TextbookDetailActivity, PageMapActivity::class.java)
                                .putExtra(PageMapActivity.EXTRA_BOOK_ID, bookId)
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }.onFailure { error ->
                toast(getString(R.string.textbook_pdf_failed, error.message ?: "未知错误"))
            }
        }
    }

    private fun render() {
        val book = store.get(bookId) ?: run {
            finish()
            return
        }
        binding.toolbar.title = book.title
        binding.bookMeta.text = buildString {
            append(book.publisher.ifBlank { "出版社未填" })
            if (book.edition.isNotBlank()) append(" · ").append(book.edition)
            append(" · ").append(StudyStyle.fromKey(book.styleKey).label)
        }
        binding.bookProgress.text = getString(
            R.string.textbook_progress,
            book.chapterCount,
            book.sectionCount,
            book.studiedCount
        )

        val rows = mutableListOf<ChapterRow>()
        book.chapters.forEachIndexed { index, chapter ->
            rows.add(
                ChapterRow.Header(
                    getString(
                        R.string.chapter_header_with_count,
                        chapter.title,
                        chapter.sections.size
                    )
                )
            )
            chapter.sections.forEach { section ->
                rows.add(
                    ChapterRow.SectionRow(
                        chapterTitle = chapter.title,
                        section = section,
                        studied = book.contents.containsKey(section.id)
                    )
                )
            }
        }
        adapter.submit(rows)
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
