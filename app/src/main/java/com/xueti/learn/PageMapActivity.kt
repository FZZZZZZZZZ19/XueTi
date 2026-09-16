package com.xueti.learn

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.PdfBookImporter
import com.xueti.learn.data.PdfFileStore
import com.xueti.learn.data.PdfTextExtractor
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.databinding.ActivityPageMapBinding
import com.xueti.learn.databinding.ItemPageChapterBinding
import com.xueti.learn.databinding.ItemPageSectionBinding
import com.xueti.learn.model.PageRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手动划分章节页数（v2.05）。
 *
 * 自动目录提取在扫描版 / 复杂版式上不可靠，所以这里让用户自己填：
 * - 按「章 → 节」顺序列出小节，每个小节填 起始页 - 结束页
 * - **只填一个边界就会自动顺延**：填了第 1 节的结束页 = 8，第 2 节起始页自动变 9；
 *   反过来填第 2 节起始页 = 9，第 1 节结束页自动变 8
 * - 可随时「预览 PDF」翻页确认，或「自动定位」尽力猜一遍再手动修
 * - 保存后写进 `Textbook.pageMap`；生成讲解时只把这几页原文发给 AI
 *
 * 没有 PDF 的书也能在这里直接补上传。
 */
class PageMapActivity : BaseActivity() {

    private lateinit var binding: ActivityPageMapBinding
    private val store by lazy { TextbookStore(this) }

    private val bookId: String get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()

    /** 一行 = 一个小节（含它的输入框） */
    private data class Row(
        val sectionTitle: String,
        val start: EditText,
        val end: EditText
    )

    private val rows = mutableListOf<Row>()
    private var suppressChain = false
    private var pdfPages: List<String> = emptyList()

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addPdf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPageMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnSaveMap.setOnClickListener { save() }
        binding.btnPreviewPdf.setOnClickListener { openPreview(1) }
        binding.btnAddPdf.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        binding.btnAutoLocate.setOnClickListener { autoLocate() }
        binding.btnClearMap.setOnClickListener { clearAll() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // 从预览页回来时可能已经标记过页码（这里只刷新 PDF 状态）
        pdfPages = PdfTextExtractor.loadPages(this, bookId)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------- 渲染列表 ----------------

    private fun render() {
        val book = store.get(bookId) ?: run {
            finish()
            return
        }
        pdfPages = PdfTextExtractor.loadPages(this, bookId)
        val hasPdf = book.hasPdf || pdfPages.isNotEmpty()

        binding.mapHint.text = if (hasPdf) {
            getString(R.string.page_map_hint_pdf, book.sourcePdfPages)
        } else {
            getString(R.string.page_map_hint)
        }
        binding.noPdfView.isVisible = !hasPdf
        binding.mapScroll.isVisible = hasPdf
        binding.btnPreviewPdf.isEnabled = PdfFileStore.hasFile(this, bookId)
        binding.btnAutoLocate.isEnabled = pdfPages.isNotEmpty()

        rows.clear()
        binding.mapContainer.removeAllViews()

        book.chapters.forEach { chapter ->
            val header = ItemPageChapterBinding.inflate(layoutInflater, binding.mapContainer, false)
            header.chapterTitle.text = chapter.title
            binding.mapContainer.addView(header.root)

            chapter.sections.forEach { section ->
                val row = ItemPageSectionBinding.inflate(layoutInflater, binding.mapContainer, false)
                row.sectionTitle.text = section.title
                book.pageRangeOf(section.title)?.let { range ->
                    row.etStart.setText(range.start.toString())
                    row.etEnd.setText(range.end.toString())
                }
                row.btnRowPreview.setOnClickListener {
                    val page = row.etStart.text?.toString()?.trim()?.toIntOrNull() ?: 1
                    openPreview(page)
                }
                binding.mapContainer.addView(row.root)
                rows.add(Row(section.title, row.etStart, row.etEnd))
            }
        }

        attachChaining()
        updateSummary()
    }

    /**
     * 顺序联动：只填一个边界，相邻小节自动顺延。
     * suppressChain 用来避免 setText 触发 watcher 造成递归。
     */
    private fun attachChaining() {
        rows.forEachIndexed { index, row ->
            row.end.addTextChangedListener(object : SimpleWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    if (suppressChain) return
                    val end = s?.toString()?.trim()?.toIntOrNull() ?: return
                    val next = rows.getOrNull(index + 1) ?: run {
                        updateSummary()
                        return
                    }
                    val nextStart = next.start.text?.toString()?.trim()?.toIntOrNull()
                    if (nextStart == null || nextStart <= end) {
                        suppressChain = true
                        next.start.setText((end + 1).toString())
                        // 顺手按上一节的长度给个建议结束页（用户可改）
                        val start = row.start.text?.toString()?.trim()?.toIntOrNull()
                        val nextEnd = next.end.text?.toString()?.trim()?.toIntOrNull()
                        if (start != null && end >= start && (nextEnd == null || nextEnd <= end)) {
                            next.end.setText((end + 1 + (end - start)).toString())
                        }
                        suppressChain = false
                    }
                    updateSummary()
                }
            })

            row.start.addTextChangedListener(object : SimpleWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    if (suppressChain) return
                    val start = s?.toString()?.trim()?.toIntOrNull() ?: return
                    val previous = rows.getOrNull(index - 1) ?: run {
                        updateSummary()
                        return
                    }
                    val prevEnd = previous.end.text?.toString()?.trim()?.toIntOrNull()
                    if (prevEnd == null || prevEnd >= start) {
                        suppressChain = true
                        previous.end.setText((start - 1).coerceAtLeast(1).toString())
                        suppressChain = false
                    }
                    updateSummary()
                }
            })
        }
    }

    private abstract class SimpleWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }

    private fun updateSummary() {
        val mapped = collectRanges().size
        binding.mapHint.text = when {
            rows.isEmpty() -> getString(R.string.page_map_empty_book)
            pdfPages.isNotEmpty() -> getString(R.string.page_map_progress_pdf, mapped, rows.size, pdfPages.size) +
                "\n" + getString(R.string.page_map_chain_tip)
            else -> getString(R.string.page_map_progress, mapped, rows.size) + "\n" +
                getString(R.string.page_map_chain_tip)
        }
    }

    private fun collectRanges(): Map<String, PageRange> {
        val map = mutableMapOf<String, PageRange>()
        rows.forEach { row ->
            val start = row.start.text?.toString()?.trim()?.toIntOrNull() ?: return@forEach
            val end = row.end.text?.toString()?.trim()?.toIntOrNull() ?: return@forEach
            if (start >= 1 && end >= start) map[row.sectionTitle] = PageRange(start, end)
        }
        return map
    }

    // ---------------- 操作 ----------------

    private fun save() {
        val ranges = collectRanges()
        val invalid = rows.count { row ->
            val start = row.start.text?.toString()?.trim()?.toIntOrNull()
            val end = row.end.text?.toString()?.trim()?.toIntOrNull()
            (start != null || end != null) && (start == null || end == null || end < start)
        }
        if (invalid > 0) {
            toast(getString(R.string.page_map_invalid, invalid))
            return
        }
        store.updatePageRanges(bookId, ranges)
        toast(getString(R.string.page_map_saved, ranges.size, rows.size))
        finish()
    }

    private fun clearAll() {
        suppressChain = true
        rows.forEach { row ->
            row.start.setText("")
            row.end.setText("")
        }
        suppressChain = false
        store.replacePageRanges(bookId, emptyMap())
        updateSummary()
        toast(R.string.page_map_cleared)
    }

    /** 尽力自动定位一遍（只在没填的小节上填），失败留给用户手动改 */
    private fun autoLocate() {
        if (pdfPages.isEmpty()) {
            toast(R.string.page_map_need_pdf)
            return
        }
        binding.mapProgress.isVisible = true
        lifecycleScope.launch {
            val hits = withContext(Dispatchers.Default) {
                val tocPages = com.xueti.learn.data.PdfOutlineParser.detectTocPages(pdfPages).toSet()
                rows.map { row ->
                    row.sectionTitle to PdfTextExtractor.findSectionStart(pdfPages, row.sectionTitle, tocPages)
                }
            }
            binding.mapProgress.isVisible = false
            suppressChain = true
            var filled = 0
            hits.forEachIndexed { index, (_, page) ->
                val row = rows.getOrNull(index) ?: return@forEachIndexed
                if (page == null) return@forEachIndexed
                if (row.start.text?.toString()?.trim()?.isNotEmpty() == true) return@forEachIndexed
                row.start.setText(page.toString())
                filled++
            }
            // 结束页按「下一节起始页 - 1」补齐，最后一节到 PDF 末尾
            rows.forEachIndexed { index, row ->
                val start = row.start.text?.toString()?.trim()?.toIntOrNull() ?: return@forEachIndexed
                if (row.end.text?.toString()?.trim()?.isNotEmpty() == true) return@forEachIndexed
                val nextStart = rows.drop(index + 1)
                    .firstNotNullOfOrNull { it.start.text?.toString()?.trim()?.toIntOrNull() }
                val end = ((nextStart ?: (pdfPages.size + 1)) - 1).coerceAtLeast(start)
                row.end.setText(end.toString())
            }
            suppressChain = false
            updateSummary()
            toast(getString(R.string.page_map_auto_done, filled))
        }
    }

    private fun openPreview(startPage: Int) {
        if (!PdfFileStore.hasFile(this, bookId)) {
            toast(R.string.pdf_preview_no_file)
            return
        }
        startActivity(
            Intent(this, PdfPreviewActivity::class.java)
                .putExtra(PdfPreviewActivity.EXTRA_BOOK_ID, bookId)
                .putExtra(PdfPreviewActivity.EXTRA_START_PAGE, startPage.coerceAtLeast(1))
        )
    }

    /** 老书补 PDF：导入后立刻可以划分页数 */
    private fun addPdf(uri: android.net.Uri) {
        binding.mapProgress.isVisible = true
        lifecycleScope.launch {
            val result = PdfBookImporter.import(this@PageMapActivity, store, bookId, uri)
            binding.mapProgress.isVisible = false
            result.onSuccess { imported ->
                toast(
                    getString(
                        if (imported.hasText) R.string.page_map_pdf_added else R.string.textbook_pdf_no_text,
                        imported.pageCount
                    )
                )
                render()
            }.onFailure { error ->
                toast(getString(R.string.textbook_pdf_failed, error.message ?: "未知错误"))
            }
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
