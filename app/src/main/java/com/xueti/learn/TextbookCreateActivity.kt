package com.xueti.learn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.MistakeStore
import com.xueti.learn.data.PdfTextExtractor
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.data.UsageStore
import com.xueti.learn.databinding.ActivityTextbookCreateBinding
import com.xueti.learn.model.StudyStyle
import com.xueti.learn.model.Textbook
import com.xueti.learn.util.ImageListController
import com.xueti.learn.util.PeakHours
import kotlinx.coroutines.launch
import java.io.File

/**
 * 新建书本：
 * - 输入书名/出版社/版次，或拍照 / 相册多选上传目录页 → AI 生成全书目录（大章 → 小章）
 * - **支持上传教材 PDF**：解析出原文后，目录由 AI **依据 PDF 原文**整理（而不是内训记忆），显著减少幻觉
 * - 生成时会带上「错题本」里的易错点，重点覆盖
 */
class TextbookCreateActivity : BaseActivity() {

    private lateinit var binding: ActivityTextbookCreateBinding
    private val settings: SettingsStore get() = (application as App).settings
    private val store by lazy { TextbookStore(this) }
    private val usageStore by lazy { UsageStore(this) }
    private val mistakeStore by lazy { MistakeStore(this) }

    private lateinit var imageController: ImageListController
    private var cameraUri: Uri? = null

    /** 已上传的教材 PDF（本地解析出的原文） */
    private var pdfName: String = ""
    private var pdfPageCount: Int = 0
    private var pdfPages: List<String> = emptyList()

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPdf(uri)
        }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraUri
            if (success && uri != null) imageController.addUris(listOf(uri))
        }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) imageController.addUris(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextbookCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.styleGroup.check(binding.btnStylePlain.id)

        imageController = ImageListController(
            activity = this,
            container = binding.imageStrip,
            onAddRequested = { pickImages.launch(arrayOf("image/*")) },
            onSelectionChanged = {
                binding.imageCountHint.isVisible = imageController.count > 0
                binding.imageCountHint.text = getString(
                    R.string.image_count_hint,
                    imageController.count,
                    ImageListController.MAX_COUNT
                )
            }
        )

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        binding.btnPickPdf.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        binding.btnRemovePdf.setOnClickListener { clearPdf() }
        binding.btnGenerate.setOnClickListener { generateOutline() }
        binding.peakHint.text = "${PeakHours.statusText()}；${PeakHours.nextSwitchText()}"
        renderPdfStatus()
    }

    // ---------------- 教材 PDF ----------------

    private fun importPdf(uri: Uri) {
        binding.pdfStatus.text = getString(R.string.textbook_pdf_reading)
        binding.btnPickPdf.isEnabled = false
        lifecycleScope.launch {
            val result = PdfTextExtractor.extract(this@TextbookCreateActivity, uri)
            binding.btnPickPdf.isEnabled = true
            result.onSuccess { extracted ->
                pdfPages = extracted.pages
                pdfPageCount = extracted.pageCount
                pdfName = queryDisplayName(uri) ?: "教材.pdf"
                binding.btnRemovePdf.isVisible = true
                renderPdfStatus()
                if (!extracted.hasText) {
                    toast(getString(R.string.textbook_pdf_no_text, pdfName))
                }
            }.onFailure { error ->
                pdfPages = emptyList()
                pdfPageCount = 0
                pdfName = ""
                binding.btnRemovePdf.isVisible = false
                binding.pdfStatus.text =
                    getString(R.string.textbook_pdf_failed, error.message ?: "未知错误")
            }
        }
    }

    private fun clearPdf() {
        pdfPages = emptyList()
        pdfPageCount = 0
        pdfName = ""
        binding.btnRemovePdf.isVisible = false
        renderPdfStatus()
    }

    private fun renderPdfStatus() {
        binding.pdfStatus.text = if (pdfPages.isNotEmpty()) {
            getString(
                R.string.textbook_pdf_ready,
                pdfName,
                pdfPageCount,
                pdfPages.sumOf { it.length }
            )
        } else {
            getString(R.string.textbook_pdf_none)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun launchCamera() {
        if (imageController.isFull()) {
            toast(R.string.image_limit_reached)
            return
        }
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "outline_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return
        cameraUri = uri
        runCatching { takePicture.launch(uri) }
            .onFailure { toast(R.string.ai_camera_unavailable) }
    }

    private fun generateOutline() {
        val title = binding.etBookTitle.text?.toString()?.trim().orEmpty()
        val publisher = binding.etPublisher.text?.toString()?.trim().orEmpty()
        val edition = binding.etEdition.text?.toString()?.trim().orEmpty()
        val outlineText = binding.etOutlineText.text?.toString()?.trim().orEmpty()
        val style = currentStyle()
        val images = imageController.base64List()

        if (title.isEmpty()) {
            toast(R.string.textbook_need_title)
            binding.etBookTitle.requestFocus()
            return
        }
        val apiKey = settings.deepSeekApiKey
        if (apiKey.isBlank()) {
            toast(R.string.ai_need_key)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        // 有 PDF 就用 PDF 原文（唯一依据）；错题本易错点一并带上重点覆盖
        val sourceText = if (pdfPages.isNotEmpty()) {
            PdfTextExtractor.outlineDigest(pdfPages)
        } else {
            null
        }
        val focusPoints = mistakeStore
            .buildFocusPrompt(limit = 10, keyword = title)
            .ifBlank { null }

        setLoading(true)
        binding.statusText.text = when {
            sourceText != null && focusPoints != null ->
                getString(R.string.textbook_generating_grounded_mistakes)
            sourceText != null -> getString(R.string.textbook_generating_grounded)
            focusPoints != null -> getString(R.string.textbook_generating_mistakes)
            else -> getString(R.string.textbook_generating)
        }
        lifecycleScope.launch {
            val result = DeepSeekClient.generateOutline(
                apiKey = apiKey,
                model = settings.aiModel,
                title = title,
                publisher = publisher,
                edition = edition,
                outlineText = outlineText.ifBlank { null },
                images = images,
                style = style,
                sourceText = sourceText,
                focusPoints = focusPoints
            )
            setLoading(false)
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { outline ->
                val bookId = "book_${System.currentTimeMillis()}"
                if (pdfPages.isNotEmpty()) {
                    PdfTextExtractor.savePages(this@TextbookCreateActivity, bookId, pdfPages)
                }
                val book = Textbook(
                    id = bookId,
                    title = title,
                    publisher = publisher,
                    edition = edition,
                    styleKey = style.key,
                    createdAt = System.currentTimeMillis(),
                    chapters = outline.chapters,
                    sourcePdfName = if (pdfPages.isNotEmpty()) pdfName else "",
                    sourcePdfPages = if (pdfPages.isNotEmpty()) pdfPageCount else 0
                )
                store.upsert(book)
                val sections = outline.chapters.sumOf { it.sections.size }
                toast(
                    getString(
                        R.string.textbook_created_tokens,
                        outline.chapters.size,
                        sections,
                        outline.usage?.totalTokens ?: 0
                    )
                )
                startActivity(
                    Intent(this@TextbookCreateActivity, TextbookDetailActivity::class.java)
                        .putExtra(TextbookDetailActivity.EXTRA_BOOK_ID, book.id)
                )
                finish()
            }.onFailure { error ->
                binding.statusText.text =
                    getString(R.string.textbook_generate_failed, error.message ?: "未知错误")
            }
        }
    }

    private fun currentStyle(): StudyStyle =
        if (binding.styleGroup.checkedButtonId == binding.btnStyleRigorous.id) {
            StudyStyle.RIGOROUS
        } else {
            StudyStyle.PLAIN
        }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.btnGenerate.isEnabled = !loading
        binding.btnGenerate.text = getString(
            if (loading) R.string.textbook_generating else R.string.textbook_generate
        )
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
