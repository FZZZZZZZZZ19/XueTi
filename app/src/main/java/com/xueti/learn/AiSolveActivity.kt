package com.xueti.learn

import android.content.ClipData
import android.content.ClipboardManager
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
import com.xueti.learn.databinding.ActivityAiSolveBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * AI 识图解题：
 * 支持拍照 / 相册选图 / 纯文字输入，配合用户自定义提示词提交给 DeepSeek（默认 deepseek-flash 多模态模型）。
 */
class AiSolveActivity : BaseActivity() {

    private lateinit var binding: ActivityAiSolveBinding
    private val settings: SettingsStore get() = (application as App).settings

    private var imageBase64: String? = null
    private var cameraUri: Uri? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraUri
            if (success && uri != null) prepareImage(uri)
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { prepareImage(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSolveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.etApiKey.setText(settings.deepSeekApiKey)
        binding.etPrompt.setText(settings.aiPrompt)
        binding.etModel.setText(settings.aiModel)
        if (settings.deepSeekApiKey.isBlank()) {
            binding.statusText.text = getString(R.string.ai_need_key)
        }

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        binding.btnClearImage.setOnClickListener { clearImage() }
        binding.btnSolve.setOnClickListener { solve() }
        binding.btnCopyResult.setOnClickListener { copyResult() }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------- 图片 ----------------

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "question_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            toast(R.string.ai_camera_unavailable)
            return
        }
        cameraUri = uri
        runCatching { takePicture.launch(uri) }
            .onFailure { toast(R.string.ai_camera_unavailable) }
    }

    private fun prepareImage(uri: Uri) {
        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.ai_loading_image)
            val prepared = withContext(Dispatchers.IO) { decodeImage(uri) }
            if (prepared == null) {
                binding.statusText.text = ""
                toast(R.string.ai_image_read_failed)
                return@launch
            }
            imageBase64 = prepared.first
            binding.imagePreview.setImageBitmap(prepared.second)
            binding.imagePreview.isVisible = true
            binding.imagePlaceholder.isVisible = false
            binding.btnClearImage.isVisible = true
            binding.statusText.text = getString(R.string.ai_image_ready)
        }
    }

    /** 解码并降采样图片（最长边 1600px），压缩为 JPEG 后转 base64 */
    private fun decodeImage(uri: Uri): Pair<String, Bitmap>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDimension / sample > MAX_IMAGE_DIMENSION) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        base64 to bitmap
    }.getOrNull()

    private fun clearImage() {
        imageBase64 = null
        binding.imagePreview.setImageDrawable(null)
        binding.imagePreview.isVisible = false
        binding.imagePlaceholder.isVisible = true
        binding.btnClearImage.isVisible = false
        binding.statusText.text = ""
    }

    // ---------------- 解题 ----------------

    private fun solve() {
        val apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        val prompt = binding.etPrompt.text?.toString().orEmpty()
        val model = binding.etModel.text?.toString()?.trim().orEmpty().ifEmpty { "deepseek-flash" }
        val question = binding.etQuestion.text?.toString()?.trim().orEmpty()

        if (apiKey.isEmpty()) {
            toast(R.string.ai_need_key)
            binding.etApiKey.requestFocus()
            return
        }
        if (question.isEmpty() && imageBase64 == null) {
            toast(R.string.ai_need_question)
            return
        }

        settings.deepSeekApiKey = apiKey
        settings.aiPrompt = prompt
        settings.aiModel = model

        setLoading(true)
        binding.statusText.text = getString(R.string.ai_solving)
        lifecycleScope.launch {
            val result = DeepSeekClient.solve(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                question = question,
                imageBase64 = imageBase64
            )
            setLoading(false)
            result.onSuccess { review ->
                binding.resultCard.isVisible = true
                binding.tvResult.text = review.answer
                binding.statusText.text = getString(R.string.ai_done, review.model)
            }.onFailure { error ->
                binding.statusText.text =
                    getString(R.string.ai_failed, error.message ?: "未知错误")
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.btnSolve.isEnabled = !loading
        binding.btnSolve.text = getString(
            if (loading) R.string.ai_solving else R.string.ai_solve_button
        )
    }

    private fun copyResult() {
        val text = binding.tvResult.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.ai_result_title), text))
        toast(R.string.ai_copied)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_IMAGE_DIMENSION = 1600
    }
}
