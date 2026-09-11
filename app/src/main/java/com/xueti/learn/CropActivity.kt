package com.xueti.learn

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.databinding.ActivityCropBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 图片裁剪页：拖拽四角调整裁剪框，可旋转 90°；确定后把裁剪结果保存到缓存并回传路径。
 */
class CropActivity : BaseActivity() {

    private lateinit var binding: ActivityCropBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString.isNullOrBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(Uri.parse(uriString)) }
            if (bitmap == null) {
                Toast.makeText(
                    this@CropActivity,
                    R.string.ai_image_read_failed,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            binding.cropView.setBitmap(bitmap)
        }

        binding.btnRotate.setOnClickListener { binding.cropView.rotate(90f) }
        binding.btnResetCrop.setOnClickListener { binding.cropView.resetCrop() }
        binding.btnCancelCrop.setOnClickListener { finish() }
        binding.btnConfirmCrop.setOnClickListener { confirmCrop() }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmCrop() {
        val cropped = binding.cropView.cropBitmap()
        if (cropped == null) {
            Toast.makeText(this, R.string.crop_failed, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { save(cropped) }
            cropped.recycle()
            if (file == null) {
                Toast.makeText(this@CropActivity, R.string.crop_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_OUTPUT_PATH, file.absolutePath)
            )
            finish()
        }
    }

    /** 解码并限制最长边，避免大图占用过多内存 */
    private fun decode(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDimension / sample > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun save(bitmap: Bitmap): File? = runCatching {
        val dir = File(cacheDir, "cropped").apply { mkdirs() }
        val file = File(dir, "crop_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        file
    }.getOrNull()

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_OUTPUT_PATH = "output_path"
        private const val MAX_DIMENSION = 2000
    }
}
