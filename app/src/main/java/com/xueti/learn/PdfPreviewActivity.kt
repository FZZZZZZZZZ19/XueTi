package com.xueti.learn

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.databinding.ActivityPdfPreviewBinding
import com.xueti.learn.util.PdfDocumentHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 教材 PDF 预览（v2.04）：
 * - 翻页（上一页 / 下一页 / 输入页号跳转）、双指缩放、放大后拖动、双击复位
 * - 底部「设为起始页 / 设为结束页」：翻到某小节开头，直接标记，
 *   返回时把选好的页号带回给调用页（小节页 / 页范围弹窗）
 *
 * 之所以做这个：自动定位页码在扫描版或复杂版式上经常找不到，
 * 让用户**看着页面自己点**才是最可靠的。
 */
class PdfPreviewActivity : BaseActivity() {

    private lateinit var binding: ActivityPdfPreviewBinding
    private var handle: PdfDocumentHandle? = null
    private var currentPage = 0
    private var markedStart: Int? = null
    private var markedEnd: Int? = null

    /** 渲染宽度倍数：1 = 适应宽度，2.2 = 放大看细节 */
    private var zoomFactor = 1.0f

    private val bookId: String get() = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 返回键也要把标记的页号带回去（用现代 API，兼容预测式返回）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithResult()
        })

        binding.pdfEmpty.isVisible = false
        binding.pdfProgress.isVisible = true

        binding.btnPrevPage.setOnClickListener { loadPage(currentPage - 1) }
        binding.btnNextPage.setOnClickListener { loadPage(currentPage + 1) }
        binding.btnZoom.setOnClickListener { toggleZoom() }
        binding.btnMarkStart.setOnClickListener { markPage(isStart = true) }
        binding.btnMarkEnd.setOnClickListener { markPage(isStart = false) }
        binding.etPage.setOnEditorActionListener { _, _, _ ->
            val target = binding.etPage.text?.toString()?.trim()?.toIntOrNull()
            if (target == null) {
                toast(getString(R.string.pdf_invalid_page))
            } else {
                loadPage(target - 1)
            }
            true
        }

        lifecycleScope.launch {
            val opened = PdfDocumentHandle.open(this@PdfPreviewActivity, bookId)
            handle = opened
            binding.pdfProgress.isVisible = false
            if (opened == null || opened.pageCount == 0) {
                binding.pdfEmpty.isVisible = true
                binding.pdfPageImage.isVisible = false
                binding.pdfPageBadge.isVisible = false
                binding.btnZoom.isEnabled = false
                return@launch
            }
            val initial = intent.getIntExtra(EXTRA_START_PAGE, 1) - 1
            loadPage(initial)
        }
    }

    // ---------------- 翻页 ----------------

    private fun loadPage(index: Int) {
        val document = handle ?: return
        val target = index.coerceIn(0, document.pageCount - 1)
        binding.pdfProgress.isVisible = true
        lifecycleScope.launch {
            // 渲染比较费时，放到 IO 线程；宽度按缩放倍数走，放大也清晰
            val width = (resources.displayMetrics.widthPixels * zoomFactor).toInt()
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                document.renderPage(target, width)
            }
            binding.pdfProgress.isVisible = false
            if (bitmap == null) {
                toast(getString(R.string.pdf_render_failed))
                return@launch
            }
            currentPage = target
            binding.pdfPageImage.setImageBitmap(bitmap)
            binding.etPage.setText((target + 1).toString())
            binding.pdfPageBadge.text = getString(
                R.string.pdf_page_badge,
                target + 1,
                document.pageCount
            )
            binding.btnZoom.text = getString(
                if (zoomFactor > 1.2f) R.string.pdf_zoom_reset else R.string.pdf_zoom_in
            )
            updateMarkHint()
        }
    }

    /** 放大 / 复位：重新按更大宽度渲染（配合外层滚动查看细节） */
    private fun toggleZoom() {
        zoomFactor = if (zoomFactor > 1.2f) 1.0f else 2.2f
        loadPage(currentPage)
    }

    private fun markPage(isStart: Boolean) {
        if (handle == null) return
        val page = currentPage + 1
        if (isStart) {
            markedStart = page
            // 起始页总是 ≤ 结束页，顺手纠正
            markedEnd?.let { if (it < page) markedEnd = page }
            toast(getString(R.string.pdf_marked_start, page))
        } else {
            markedEnd = page
            markedStart?.let { if (it > page) markedStart = page }
            toast(getString(R.string.pdf_marked_end, page))
        }
        updateMarkHint()
    }

    private fun updateMarkHint() {
        val start = markedStart
        val end = markedEnd
        binding.pdfHint.text = when {
            start != null && end != null -> getString(R.string.pdf_marked_both, start, end)
            start != null -> getString(R.string.pdf_marked_only_start, start)
            end != null -> getString(R.string.pdf_marked_only_end, end)
            else -> getString(R.string.pdf_preview_hint)
        }
        binding.btnMarkStart.text = if (start != null) {
            getString(R.string.pdf_mark_start_done, start)
        } else {
            getString(R.string.pdf_mark_start)
        }
        binding.btnMarkEnd.text = if (end != null) {
            getString(R.string.pdf_mark_end_done, end)
        } else {
            getString(R.string.pdf_mark_end)
        }
    }

    // ---------------- 返回结果 ----------------

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishWithResult()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        finishWithResult()
    }

    private fun finishWithResult() {
        val start = markedStart
        val end = markedEnd
        if (start != null || end != null) {
            setResult(
                RESULT_OK,
                android.content.Intent().apply {
                    putExtra(EXTRA_RESULT_START, start ?: 0)
                    putExtra(EXTRA_RESULT_END, end ?: 0)
                }
            )
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun onDestroy() {
        handle?.close()
        handle = null
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_START_PAGE = "start_page"
        const val EXTRA_RESULT_START = "result_start"
        const val EXTRA_RESULT_END = "result_end"
    }
}
