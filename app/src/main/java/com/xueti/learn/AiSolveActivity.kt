package com.xueti.learn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.databinding.ActivityAiSolveBinding
import com.xueti.learn.util.ImageEnhancer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * AI 识图解题：
 * 支持拍照 / 相册选图 / 纯文字输入，可先裁剪与图像增强（灰度、自动对比度、高斯锐化、二值化），
 * 再配合用户自定义提示词提交给 DeepSeek（默认 deepseek-flash 多模态模型）。
 */
class AiSolveActivity : BaseActivity() {

    private lateinit var binding: ActivityAiSolveBinding
    private val settings: SettingsStore get() = (application as App).settings

    /** 原图（裁剪/增强前的基准）与当前工作图 */
    private var originalBitmap: Bitmap? = null
    private var workingBitmap: Bitmap? = null

    /** 当前图片 URI（用于裁剪页输入） */
    private var currentUri: Uri? = null
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

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val path = result.data?.getStringExtra(CropActivity.EXTRA_OUTPUT_PATH)
                ?: return@registerForActivityResult
            prepareImage(Uri.fromFile(File(path)))
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
        binding.btnCrop.setOnClickListener { cropImage() }
        binding.btnEnhance.setOnClickListener { showEnhanceDialog() }
        binding.btnRestore.setOnClickListener { restoreImage() }
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

    // ---------------- 图片获取与显示 ----------------

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
            val decoded = withContext(Dispatchers.IO) { decodeImage(uri) }
            if (decoded == null) {
                binding.statusText.text = ""
                toast(R.string.ai_image_read_failed)
                return@launch
            }
            originalBitmap = decoded
            workingBitmap = decoded
            currentUri = uri
            showBitmap(decoded)
            binding.statusText.text = getString(R.string.ai_image_ready)
        }
    }

    private fun showBitmap(bitmap: Bitmap) {
        binding.imagePreview.setImageBitmap(bitmap)
        binding.imagePreview.isVisible = true
        binding.imagePlaceholder.isVisible = false
        binding.imageActionsRow.isVisible = true
        binding.enhanceTip.isVisible = true
    }

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDimension / sample > MAX_IMAGE_DIMENSION) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun clearImage() {
        originalBitmap = null
        workingBitmap = null
        currentUri = null
        binding.imagePreview.setImageDrawable(null)
        binding.imagePreview.isVisible = false
        binding.imagePlaceholder.isVisible = true
        binding.imageActionsRow.isVisible = false
        binding.enhanceTip.isVisible = false
        binding.statusText.text = ""
    }

    // ---------------- 裁剪 ----------------

    private fun cropImage() {
        val uri = currentUri ?: return
        val intent = Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_IMAGE_URI, uri.toString())
        runCatching { cropLauncher.launch(intent) }
            .onFailure { toast(R.string.crop_failed) }
    }

    // ---------------- 图像增强 ----------------

    private fun showEnhanceDialog() {
        if (workingBitmap == null) return
        val options = ImageEnhancer.Options()
        val labels = arrayOf(
            getString(R.string.enhance_option_gray),
            getString(R.string.enhance_option_contrast),
            getString(R.string.enhance_option_sharpen),
            getString(R.string.enhance_option_binarize)
        )
        val checked = booleanArrayOf(
            options.grayscale,
            options.autoContrast,
            options.sharpen,
            options.binarize
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.enhance_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.enhance_apply) { _, _ ->
                applyEnhance(
                    ImageEnhancer.Options(
                        grayscale = checked[0],
                        autoContrast = checked[1],
                        sharpen = checked[2],
                        binarize = checked[3]
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyEnhance(options: ImageEnhancer.Options) {
        val source = originalBitmap ?: return
        binding.statusText.text = getString(R.string.enhance_processing)
        lifecycleScope.launch {
            val enhanced = withContext(Dispatchers.Default) {
                runCatching { ImageEnhancer.enhance(source, options) }.getOrNull()
            }
            if (enhanced == null) {
                binding.statusText.text = ""
                toast(R.string.enhance_failed)
                return@launch
            }
            workingBitmap = enhanced
            showBitmap(enhanced)
            binding.statusText.text = getString(R.string.enhance_done)
        }
    }

    private fun restoreImage() {
        val original = originalBitmap ?: return
        workingBitmap = original
        showBitmap(original)
        binding.statusText.text = ""
    }

    // ---------------- 解题 ----------------

    private fun solve() {
        val apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        val prompt = binding.etPrompt.text?.toString().orEmpty()
        val model = binding.etModel.text?.toString()?.trim().orEmpty().ifEmpty { "deepseek-flash" }
        val question = binding.etQuestion.text?.toString()?.trim().orEmpty()
        val bitmap = workingBitmap

        if (apiKey.isEmpty()) {
            toast(R.string.ai_need_key)
            binding.etApiKey.requestFocus()
            return
        }
        if (question.isEmpty() && bitmap == null) {
            toast(R.string.ai_need_question)
            return
        }

        settings.deepSeekApiKey = apiKey
        settings.aiPrompt = prompt
        settings.aiModel = model

        setLoading(true)
        binding.statusText.text = getString(R.string.ai_solving)
        lifecycleScope.launch {
            val base64 = withContext(Dispatchers.IO) { bitmap?.let { toBase64(it) } }
            val result = DeepSeekClient.solve(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                question = question,
                imageBase64 = base64
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

    /** 压缩为 JPEG 并转为 base64（不含 data URL 前缀） */
    private fun toBase64(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
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

    /** 保存增强后的图片到缓存（便于排查/分享，可选使用） */
    @Suppress("unused")
    private fun saveToCache(bitmap: Bitmap): File? = runCatching {
        val dir = File(cacheDir, "enhanced").apply { mkdirs() }
        val file = File(dir, "enhanced_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        file
    }.getOrNull()

    companion object {
        private const val MAX_IMAGE_DIMENSION = 1600
    }
}
