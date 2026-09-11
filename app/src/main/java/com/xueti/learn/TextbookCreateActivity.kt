package com.xueti.learn

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.databinding.ActivityTextbookCreateBinding
import com.xueti.learn.model.StudyStyle
import com.xueti.learn.model.Textbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 新建书本：输入书名/出版社/版次（或拍照上传目录），由 AI 生成全书目录（大章 → 小章）
 */
class TextbookCreateActivity : BaseActivity() {

    private lateinit var binding: ActivityTextbookCreateBinding
    private val settings: SettingsStore get() = (application as App).settings
    private val store by lazy { TextbookStore(this) }

    private var photoBase64: String? = null
    private var photoBitmap: Bitmap? = null
    private var cameraUri: Uri? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraUri
            if (success && uri != null) preparePhoto(uri)
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { preparePhoto(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextbookCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.styleGroup.check(binding.btnStylePlain.id)
        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        binding.btnClearPhoto.setOnClickListener { clearPhoto() }
        binding.photoPreview.setOnClickListener { photoBitmap?.let { showFullPreview(it) } }
        binding.btnGenerate.setOnClickListener { generateOutline() }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------- 目录照片 ----------------

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "outline_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return
        cameraUri = uri
        runCatching { takePicture.launch(uri) }
            .onFailure { toast(R.string.ai_camera_unavailable) }
    }

    private fun preparePhoto(uri: Uri) {
        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.ai_loading_image)
            val prepared = withContext(Dispatchers.IO) { decode(uri) }
            if (prepared == null) {
                toast(R.string.ai_image_read_failed)
                return@launch
            }
            photoBase64 = prepared.first
            photoBitmap = prepared.second
            binding.photoPreview.setImageBitmap(prepared.second)
            binding.photoPreview.isVisible = true
            binding.btnClearPhoto.isVisible = true
            binding.statusText.text = getString(R.string.ai_image_ready)
        }
    }

    private fun clearPhoto() {
        photoBase64 = null
        photoBitmap = null
        binding.photoPreview.setImageDrawable(null)
        binding.photoPreview.isVisible = false
        binding.btnClearPhoto.isVisible = false
        binding.statusText.text = getString(R.string.textbook_generate_hint)
    }

    private fun decode(uri: Uri): Pair<String, Bitmap>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDimension / sample > 1600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to bitmap
    }.getOrNull()

    // ---------------- 生成目录 ----------------

    private fun generateOutline() {
        val title = binding.etBookTitle.text?.toString()?.trim().orEmpty()
        val publisher = binding.etPublisher.text?.toString()?.trim().orEmpty()
        val edition = binding.etEdition.text?.toString()?.trim().orEmpty()
        val outlineText = binding.etOutlineText.text?.toString()?.trim().orEmpty()
        val style = currentStyle()

        if (title.isEmpty()) {
            toast(R.string.textbook_need_title)
            binding.etBookTitle.requestFocus()
            return
        }
        val apiKey = settings.deepSeekApiKey
        if (apiKey.isBlank()) {
            toast(R.string.ai_need_key)
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
                imageBase64 = photoBase64,
                style = style
            )
            setLoading(false)
            result.onSuccess { chapters ->
                val book = Textbook(
                    id = "book_${System.currentTimeMillis()}",
                    title = title,
                    publisher = publisher,
                    edition = edition,
                    styleKey = style.key,
                    createdAt = System.currentTimeMillis(),
                    chapters = chapters
                )
                store.upsert(book)
                toast(getString(R.string.textbook_created, chapters.size, chapters.sumOf { it.sections.size }))
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

    private fun showFullPreview(bitmap: Bitmap) {
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(imageView)
        imageView.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
