package com.xueti.learn

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.MistakeStore
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.UsageStore
import com.xueti.learn.databinding.ActivityAiSolveBinding
import com.xueti.learn.util.FormulaRenderer
import com.xueti.learn.util.ImageEnhancer
import com.xueti.learn.util.ImageListController
import com.xueti.learn.util.PeakHours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI 识图解题：
 * - 支持**多张**题目图片（拍照 / 相册多选，最多 6 张），可裁剪与图像增强
 * - 提示词自定义；API Key / 模型在「设置 → AI 接口」中配置
 * - 提交后统计 token 用量与预估费用
 */
class AiSolveActivity : BaseActivity() {

    private lateinit var binding: ActivityAiSolveBinding
    private val settings: SettingsStore get() = (application as App).settings
    private val usageStore by lazy { UsageStore(this) }
    private val mistakeStore by lazy { MistakeStore(this) }

    private lateinit var imageController: ImageListController

    /** 最近一次解题结果（复制 / 记错题用） */
    private var lastAnswer: String = ""
    private var lastQuestion: String = ""

    /** 增强前的备份（用于「还原」） */
    private var enhanceBackup: Bitmap? = null
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

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val path = result.data?.getStringExtra(CropActivity.EXTRA_OUTPUT_PATH)
                ?: return@registerForActivityResult
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    com.xueti.learn.util.ImageUtils.decodeSampled(
                        this@AiSolveActivity,
                        Uri.fromFile(File(path))
                    )
                }
                if (bitmap != null) {
                    imageController.replaceSelected(bitmap)
                    binding.statusText.text = getString(R.string.crop_done)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSolveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        imageController = ImageListController(
            activity = this,
            container = binding.imageStrip,
            onAddRequested = { pickImages.launch(arrayOf("image/*")) },
            onSelectionChanged = { bitmap ->
                binding.imagePlaceholder.isVisible = bitmap == null
                binding.imageActionsRow.isVisible = bitmap != null
                binding.enhanceTip.isVisible = bitmap != null
                binding.imageCountHint.isVisible = bitmap != null
                binding.imageCountHint.text = getString(
                    R.string.image_count_hint,
                    imageController.count,
                    ImageListController.MAX_COUNT
                )
                enhanceBackup = null
            }
        )

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        binding.btnCrop.setOnClickListener { cropSelected() }
        binding.btnEnhance.setOnClickListener { showEnhanceDialog() }
        binding.btnRestore.setOnClickListener { restoreSelected() }
        binding.btnClearImage.setOnClickListener { imageController.clear() }
        binding.btnSolve.setOnClickListener { solve() }
        binding.btnCopyResult.setOnClickListener { copyResult() }
        binding.btnMarkWrong.setOnClickListener { markWrong() }
        binding.btnMistakes.setOnClickListener {
            startActivity(Intent(this, MistakeActivity::class.java))
        }
        binding.mistakeEntry.setOnClickListener {
            startActivity(Intent(this, MistakeActivity::class.java))
        }
        binding.promptHint.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.apiHint.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.imageStrip.setOnClickListener { }
        // 解答结果用内置 KaTeX 渲染，数学公式不会再是乱码
        FormulaRenderer.attach(binding.resultWeb, this)
    }

    override fun onResume() {
        super.onResume()
        renderApiHint()
        binding.peakHint.text = "${PeakHours.statusText()}；${PeakHours.nextSwitchText()}"
        binding.promptHint.text = getString(
            R.string.ai_prompt_in_settings,
            settings.aiPrompt.lineSequence().firstOrNull().orEmpty().take(24)
        )
        renderMistakeEntry()
    }

    private fun renderMistakeEntry() {
        binding.mistakeEntry.text =
            getString(R.string.mistake_entry_line, mistakeStore.pendingCount())
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        FormulaRenderer.release(binding.resultWeb)
        runCatching { binding.resultWeb.destroy() }
        super.onDestroy()
    }

    private fun renderApiHint() {
        binding.apiHint.text = if (settings.deepSeekApiKey.isBlank()) {
            getString(R.string.ai_api_not_configured)
        } else {
            getString(R.string.ai_api_configured, settings.aiModel)
        }
    }

    // ---------------- 图片 ----------------

    private fun launchCamera() {
        if (imageController.isFull()) {
            toast(R.string.image_limit_reached)
            return
        }
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

    private fun cropSelected() {
        val item = imageController.selected() ?: return
        // 裁剪需要 URI：把当前位图写入缓存再交给裁剪页
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "crop_input").apply { mkdirs() }
                    val f = File(dir, "input_${System.currentTimeMillis()}.jpg")
                    f.outputStream().use { item.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    f
                }.getOrNull()
            }
            if (file == null) {
                toast(R.string.crop_failed)
                return@launch
            }
            val intent = Intent(this@AiSolveActivity, CropActivity::class.java)
                .putExtra(CropActivity.EXTRA_IMAGE_URI, Uri.fromFile(file).toString())
            runCatching { cropLauncher.launch(intent) }
                .onFailure { toast(R.string.crop_failed) }
        }
    }

    private fun showEnhanceDialog() {
        if (imageController.selected() == null) return
        val defaults = ImageEnhancer.Options()
        val labels = arrayOf(
            getString(R.string.enhance_option_gray),
            getString(R.string.enhance_option_contrast),
            getString(R.string.enhance_option_sharpen),
            getString(R.string.enhance_option_binarize)
        )
        val checked = booleanArrayOf(
            defaults.grayscale,
            defaults.autoContrast,
            defaults.sharpen,
            defaults.binarize
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
        val source = imageController.selectedBitmap() ?: return
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
            enhanceBackup = source
            imageController.replaceSelected(enhanced)
            binding.statusText.text = getString(R.string.enhance_done)
        }
    }

    private fun restoreSelected() {
        val backup = enhanceBackup
        if (backup == null) {
            toast(R.string.enhance_no_backup)
            return
        }
        imageController.replaceSelected(backup)
        enhanceBackup = null
        binding.statusText.text = ""
    }

    // ---------------- 解题 ----------------

    private fun solve() {
        val apiKey = settings.deepSeekApiKey
        // 提示词统一在「设置 → AI 接口 → 解题提示词」里维护
        val prompt = settings.aiPrompt
        val question = binding.etQuestion.text?.toString()?.trim().orEmpty()
        val images = imageController.base64List()

        if (apiKey.isBlank()) {
            toast(R.string.ai_need_key)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (question.isEmpty() && images.isEmpty()) {
            toast(R.string.ai_need_question)
            return
        }

        setLoading(true)
        binding.statusText.text = getString(R.string.ai_solving)
        lifecycleScope.launch {
            val result = DeepSeekClient.solve(
                apiKey = apiKey,
                model = settings.aiModel,
                prompt = prompt,
                question = question,
                images = images
            )
            setLoading(false)
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { review ->
                binding.resultCard.isVisible = true
                lastAnswer = review.answer
                lastQuestion = question
                FormulaRenderer.render(binding.resultWeb, review.answer, this@AiSolveActivity)
                val tokens = review.usage?.totalTokens ?: 0
                binding.statusText.text = getString(R.string.ai_done_tokens, review.model, tokens)
            }.onFailure { error ->
                binding.statusText.text =
                    getString(R.string.ai_failed, error.message ?: "未知错误")
            }
        }
    }

    /** 这题我算错了：把题目 + AI 解答自动记进错题本 */
    private fun markWrong() {
        val question = lastQuestion.ifBlank {
            binding.etQuestion.text?.toString().orEmpty().trim().ifBlank {
                if (imageController.count > 0) getString(R.string.mistake_image_question) else ""
            }
        }
        if (question.isBlank()) {
            toast(R.string.mistake_need_question)
            return
        }
        val item = mistakeStore.add(
            question = question,
            aiAnswer = lastAnswer,
            source = MistakeStore.SOURCE_AI_SOLVE
        )
        if (item == null) {
            toast(R.string.mistake_need_question)
            return
        }
        renderMistakeEntry()
        // 顺手让用户补一句错因（可跳过）
        AlertDialog.Builder(this)
            .setTitle(R.string.mistake_mark_wrong)
            .setMessage(R.string.mistake_saved_ask_note)
            .setPositiveButton(R.string.mistake_edit_note) { _, _ ->
                val input = android.widget.EditText(this).apply {
                    hint = getString(R.string.mistake_note_hint)
                    setPadding(48, 24, 48, 24)
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.mistake_edit_note)
                    .setView(input)
                    .setPositiveButton(R.string.goal_save) { _, _ ->
                        mistakeStore.updateNote(item.id, input.text?.toString().orEmpty())
                        toast(R.string.mistake_note_saved)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.btnSolve.isEnabled = !loading
        binding.btnSolve.text = getString(
            if (loading) R.string.ai_solving else R.string.ai_solve_button
        )
    }

    private fun copyResult() {
        if (lastAnswer.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.ai_result_title), lastAnswer)
        )
        toast(R.string.ai_copied)
    }

    /** 全屏查看图片（点击缩略图时使用） */
    @Suppress("unused")
    private fun showFullPreview(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(imageView)
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(true)
        imageView.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
