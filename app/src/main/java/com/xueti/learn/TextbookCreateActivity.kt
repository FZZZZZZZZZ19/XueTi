package com.xueti.learn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
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
 * 新建书本：输入书名/出版社/版次（或拍照、相册多选上传目录页），由 AI 生成全书目录（大章 → 小章）
 */
class TextbookCreateActivity : BaseActivity() {

    private lateinit var binding: ActivityTextbookCreateBinding
    private val settings: SettingsStore get() = (application as App).settings
    private val store by lazy { TextbookStore(this) }
    private val usageStore by lazy { UsageStore(this) }

    private lateinit var imageController: ImageListController
    private var cameraUri: Uri? = null

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
        binding.btnGenerate.setOnClickListener { generateOutline() }
        binding.peakHint.text = "${PeakHours.statusText()}（${PeakHours.nextSwitchText()}）"
    }

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

        setLoading(true)
        binding.statusText.text = getString(R.string.textbook_generating)
        lifecycleScope.launch {
            val result = DeepSeekClient.generateOutline(
                apiKey = apiKey,
                model = settings.aiModel,
                title = title,
                publisher = publisher,
                edition = edition,
                outlineText = outlineText.ifBlank { null },
                images = images,
                style = style
            )
            setLoading(false)
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { outline ->
                val book = Textbook(
                    id = "book_${System.currentTimeMillis()}",
                    title = title,
                    publisher = publisher,
                    edition = edition,
                    styleKey = style.key,
                    createdAt = System.currentTimeMillis(),
                    chapters = outline.chapters
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
