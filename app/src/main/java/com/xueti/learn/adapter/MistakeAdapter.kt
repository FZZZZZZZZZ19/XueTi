package com.xueti.learn.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.xueti.learn.R
import com.xueti.learn.data.MistakeItem
import com.xueti.learn.data.MistakeStore
import com.xueti.learn.databinding.ItemMistakeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 错题本列表适配器 */
class MistakeAdapter(
    private val onClick: (MistakeItem) -> Unit
) : RecyclerView.Adapter<MistakeAdapter.VH>() {

    private val items = mutableListOf<MistakeItem>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submit(list: List<MistakeItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemMistakeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMistakeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val context = b.root.context

        b.subjectBadge.text = item.subject.ifBlank { "其他" }
        b.questionText.text = item.question
        b.sourceText.text = context.getString(
            R.string.mistake_source_line,
            sourceLabel(context, item.source),
            dateFormat.format(Date(item.createdAt))
        )

        if (item.mastered) {
            b.statusBadge.setText(R.string.mistake_mastered)
            b.statusBadge.setBackgroundResource(R.drawable.bg_tag_known)
            b.statusBadge.setTextColor(ContextCompat.getColor(context, R.color.tag_known_text))
            b.questionText.alpha = 0.6f
        } else {
            b.statusBadge.setText(R.string.mistake_pending)
            b.statusBadge.setBackgroundResource(R.drawable.bg_tag_unknown)
            b.statusBadge.setTextColor(ContextCompat.getColor(context, R.color.tag_unknown_text))
            b.questionText.alpha = 1f
        }

        b.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun sourceLabel(context: android.content.Context, source: String): String =
        when (source) {
            MistakeStore.SOURCE_REVIEW -> context.getString(R.string.mistake_source_review)
            MistakeStore.SOURCE_STUDY -> context.getString(R.string.mistake_source_study)
            else -> context.getString(R.string.mistake_source_ai)
        }
}
