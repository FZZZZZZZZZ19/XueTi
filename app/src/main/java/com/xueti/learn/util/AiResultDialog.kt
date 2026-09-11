package com.xueti.learn.util

import android.content.ClipData
import android.content.ClipboardManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.xueti.learn.R
import com.xueti.learn.databinding.DialogAiTextBinding

/** AI 文本结果弹窗（翻译 / 造句等共用，可一键复制） */
object AiResultDialog {

    fun show(
        activity: AppCompatActivity,
        title: String,
        text: String,
        meta: String? = null
    ) {
        val binding = DialogAiTextBinding.inflate(activity.layoutInflater)
        binding.tvAiText.text = text
        binding.tvAiMeta.text = meta.orEmpty()
        binding.tvAiMeta.isVisible = !meta.isNullOrBlank()

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(R.string.confirm, null)
            .setNeutralButton(R.string.ai_copy) { _, _ -> copy(activity, title, text) }
            .show()
    }

    fun copy(activity: AppCompatActivity, label: String, text: String) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
