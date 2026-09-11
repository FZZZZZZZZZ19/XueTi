package com.xueti.learn.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xueti.learn.R
import com.xueti.learn.databinding.ItemWordBinding
import com.xueti.learn.model.Familiarity
import com.xueti.learn.model.LearnRecord
import com.xueti.learn.model.Word

/** 已学单词列表（把学习记录与词库释义配对展示） */
class WordListAdapter : RecyclerView.Adapter<WordListAdapter.VH>() {

    private val items = mutableListOf<Pair<LearnRecord, Word?>>()

    fun submit(list: List<Pair<LearnRecord, Word?>>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemWordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (record, word) = items[position]
        val b = holder.binding
        b.wordText.text = record.word
        b.phoneticText.text = word?.phonetic.orEmpty()
        b.meaningText.text = word?.let { "${it.pos} ${it.meaning}" }.orEmpty()
        val isKnown = record.familiarity == Familiarity.KNOWN
        b.statusText.setText(if (isKnown) R.string.status_known else R.string.status_unknown)
        b.statusText.setBackgroundResource(
            if (isKnown) R.drawable.bg_tag_known else R.drawable.bg_tag_unknown
        )
    }

    override fun getItemCount(): Int = items.size
}
