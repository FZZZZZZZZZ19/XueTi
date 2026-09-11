package com.xueti.learn.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xueti.learn.databinding.ItemHeaderBinding
import com.xueti.learn.databinding.ItemSectionBinding
import com.xueti.learn.model.Section

/** 目录行：大章标题 或 小章 */
sealed interface ChapterRow {
    data class Header(val title: String) : ChapterRow
    data class SectionRow(
        val chapterTitle: String,
        val section: Section,
        val studied: Boolean
    ) : ChapterRow
}

class ChapterAdapter(
    private val onSectionClick: (chapterTitle: String, section: Section) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<ChapterRow>()

    fun submit(list: List<ChapterRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is ChapterRow.Header) TYPE_HEADER else TYPE_SECTION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderVH(ItemHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            SectionVH(ItemSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ChapterRow.Header -> (holder as HeaderVH).binding.headerTitle.text = row.title
            is ChapterRow.SectionRow -> (holder as SectionVH).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVH(val binding: ItemHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    inner class SectionVH(val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ChapterRow.SectionRow) {
            binding.sectionTitle.text = row.section.title
            binding.sectionBadge.isVisible = row.studied
            binding.root.setOnClickListener { onSectionClick(row.chapterTitle, row.section) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SECTION = 1
    }
}
