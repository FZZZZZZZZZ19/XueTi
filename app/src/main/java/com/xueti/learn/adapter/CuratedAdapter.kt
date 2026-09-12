package com.xueti.learn.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.xueti.learn.R
import com.xueti.learn.data.CuratedQuestion
import com.xueti.learn.databinding.ItemCuratedBinding
import com.xueti.learn.databinding.ItemCuratedHeaderBinding

/**
 * 精选题库列表：按「书本」分组（组标题行）+ 题目行，
 * 题目行里显示 章节 › 小节 路径与题干摘要。
 */
class CuratedAdapter(
    private val onClick: (CuratedQuestion) -> Unit,
    private val onLongClick: (CuratedQuestion) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val title: String, val count: Int) : Row
        data class Item(val question: CuratedQuestion) : Row
    }

    private val rows = mutableListOf<Row>()

    /** 传入已过滤的题目（顺序会被保留），按书名自动插入分组标题 */
    fun submit(items: List<CuratedQuestion>) {
        rows.clear()
        items.groupBy { it.bookTitle }
            .toList()
            .sortedByDescending { (_, list) -> list.maxOf { it.addedAt } }
            .forEach { (book, list) ->
                rows.add(Row.Header(book, list.size))
                list.sortedByDescending { it.addedAt }.forEach { rows.add(Row.Item(it)) }
            }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemCuratedHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemVH(ItemCuratedBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).binding.headerText.text =
                holder.binding.root.context.getString(R.string.curated_book_header, row.title, row.count)

            is Row.Item -> {
                val b = (holder as ItemVH).binding
                val item = row.question
                val context = b.root.context
                b.exampleTitle.text = item.title.ifBlank {
                    context.getString(R.string.curated_default_title)
                }
                b.pathText.text = buildString {
                    if (item.chapterTitle.isNotBlank()) append(item.chapterTitle).append(" › ")
                    append(item.sectionTitle)
                }
                b.questionText.text = item.question
                if (item.mastered) {
                    b.statusBadge.setText(R.string.mistake_mastered)
                    b.statusBadge.setBackgroundResource(R.drawable.bg_tag_known)
                    b.statusBadge.setTextColor(ContextCompat.getColor(context, R.color.tag_known_text))
                } else {
                    b.statusBadge.setText(R.string.curated_pending)
                    b.statusBadge.setBackgroundResource(R.drawable.bg_tag_unknown)
                    b.statusBadge.setTextColor(ContextCompat.getColor(context, R.color.tag_unknown_text))
                }
                b.root.setOnClickListener { onClick(item) }
                b.root.setOnLongClickListener {
                    onLongClick(item)
                    true
                }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVH(val binding: ItemCuratedHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ItemVH(val binding: ItemCuratedBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
