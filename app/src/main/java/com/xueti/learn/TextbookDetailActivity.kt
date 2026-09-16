package com.xueti.learn

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.ChapterAdapter
import com.xueti.learn.adapter.ChapterRow
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.PdfFileStore
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.databinding.ActivityTextbookDetailBinding
import com.xueti.learn.model.StudyStyle

/** 书本目录：大章 / 小章，点击小章开始学习 */
class TextbookDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityTextbookDetailBinding
    private val store by lazy { TextbookStore(this) }
    private val bookId: String get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()

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
            R.id.action_book_pdf_preview -> {
                // v2.04：预览教材 PDF（翻页看内容，章节页里可直接标记起始页）
                if (!PdfFileStore.hasFile(this, bookId)) {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.pdf_preview_no_file),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
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
