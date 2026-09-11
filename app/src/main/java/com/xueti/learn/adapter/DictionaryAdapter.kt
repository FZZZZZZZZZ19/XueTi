package com.xueti.learn.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xueti.learn.R
import com.xueti.learn.databinding.ItemDictionaryWordBinding
import com.xueti.learn.model.Familiarity
import com.xueti.learn.model.LearnRecord
import com.xueti.learn.model.Word

/** 词典列表适配器（全览 / 搜索结果） */
class DictionaryAdapter(
    private val onClick: (Word) -> Unit
) : RecyclerView.Adapter<DictionaryAdapter.VH>() {

    private val items = mutableListOf<Word>()

    /** 学习状态查询：返回该词的学习记录（无则 null） */
    var recordProvider: (String) -> LearnRecord? = { null }

    fun submit(list: List<Word>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemDictionaryWordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDictionaryWordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val word = items[position]
        val b = holder.binding
        b.wordText.text = word.word
        b.phoneticText.text = word.phonetic
        b.meaningText.text = "${word.pos} ${word.meaning}".trim()

        val record = recordProvider(word.word)
        when {
            record == null -> {
                b.statusBadge.isVisible = true
                b.statusBadge.setText(R.string.dict_status_new)
                b.statusBadge.setBackgroundResource(R.drawable.bg_tag_unknown)
                b.statusBadge.setTextColor(
                    androidx.core.content.ContextCompat.getColor(b.root.context, R.color.tag_unknown_text)
                )
            }
            record.familiarity == Familiarity.UNKNOWN -> {
                b.statusBadge.isVisible = true
                b.statusBadge.setText(R.string.dict_status_review)
                b.statusBadge.setBackgroundResource(R.drawable.bg_tag_unknown)
                b.statusBadge.setTextColor(
                    androidx.core.content.ContextCompat.getColor(b.root.context, R.color.tag_unknown_text)
                )
            }
            else -> {
                b.statusBadge.isVisible = true
                b.statusBadge.setText(R.string.dict_status_known)
                b.statusBadge.setBackgroundResource(R.drawable.bg_tag_known)
                b.statusBadge.setTextColor(
                    androidx.core.content.ContextCompat.getColor(b.root.context, R.color.tag_known_text)
                )
            }
        }
        b.root.setOnClickListener { onClick(word) }
    }

    override fun getItemCount(): Int = items.size
}
