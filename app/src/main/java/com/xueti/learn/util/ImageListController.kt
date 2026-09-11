package com.xueti.learn.util

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.xueti.learn.R
import com.xueti.learn.databinding.ItemImageThumbBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多图选择与缩略图管理：
 * 支持拍照/相册多选追加、点选当前图、删除、清空、替换（裁剪/增强后）。
 */
class ImageListController(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val maxCount: Int = MAX_COUNT,
    private val onAddRequested: () -> Unit,
    private val onSelectionChanged: (Bitmap?) -> Unit
) {

    data class Item(val bitmap: Bitmap, var base64: String)

    private val items = mutableListOf<Item>()

    var selectedIndex: Int = -1
        private set

    val count: Int get() = items.size

    fun isFull(): Boolean = items.size >= maxCount

    fun base64List(): List<String> = items.map { it.base64 }

    fun selected(): Item? = items.getOrNull(selectedIndex)

    /** 从相册多选或拍照结果追加图片 */
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = maxCount - items.size
        val accepted = uris.take(room.coerceAtLeast(0))
        if (accepted.isEmpty()) return
        activity.lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                accepted.mapNotNull { uri ->
                    ImageUtils.decodeSampled(activity, uri)?.let { bitmap ->
                        Item(bitmap, ImageUtils.toBase64(bitmap))
                    }
                }
            }
            items.addAll(decoded)
            selectedIndex = items.lastIndex
            render()
            onSelectionChanged(selected()?.bitmap)
        }
    }

    fun remove(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        selectedIndex = when {
            items.isEmpty() -> -1
            selectedIndex >= items.size -> items.lastIndex
            else -> selectedIndex
        }
        render()
        onSelectionChanged(selected()?.bitmap)
    }

    fun clear() {
        items.clear()
        selectedIndex = -1
        render()
        onSelectionChanged(null)
    }

    /** 用处理后的图片替换当前选中的图片（裁剪 / 增强） */
    fun replaceSelected(bitmap: Bitmap) {
        val index = if (selectedIndex in items.indices) selectedIndex else items.lastIndex
        if (index !in items.indices) return
        val old = items[index].bitmap
        items[index] = Item(bitmap, ImageUtils.toBase64(bitmap))
        if (old !== bitmap) runCatching { old.recycle() }
        selectedIndex = index
        render()
        onSelectionChanged(bitmap)
    }

    /** 当前选中图片的原始 Bitmap（用于增强的还原） */
    fun selectedBitmap(): Bitmap? = selected()?.bitmap

    private fun render() {
        container.removeAllViews()
        items.forEachIndexed { index, item ->
            val binding = ItemImageThumbBinding.inflate(
                activity.layoutInflater, container, false
            )
            binding.thumbImage.setImageBitmap(item.bitmap)
            binding.thumbRoot.setBackgroundResource(
                if (index == selectedIndex) R.drawable.bg_thumb_selected else R.drawable.bg_thumb
            )
            binding.thumbRoot.setOnClickListener {
                selectedIndex = index
                render()
                onSelectionChanged(item.bitmap)
            }
            binding.btnRemoveThumb.setOnClickListener { remove(index) }
            container.addView(binding.root)
        }

        // 末尾的「添加」按钮
        if (!isFull()) {
            val addButton = MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = activity.getString(R.string.image_add)
                isAllCaps = false
                minWidth = 0
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener { onAddRequested() }
            }
            container.addView(addButton)
        }
        container.visibility = if (items.isEmpty()) ViewGroup.GONE else ViewGroup.VISIBLE
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        const val MAX_COUNT = 6
    }
}
