package com.xueti.learn.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xueti.learn.databinding.ItemTextbookBinding
import com.xueti.learn.model.Textbook

/** 书本列表适配器 */
class TextbookAdapter(
    private val onClick: (Textbook) -> Unit,
    private val onDelete: (Textbook) -> Unit
) : RecyclerView.Adapter<TextbookAdapter.VH>() {

    private val items = mutableListOf<Textbook>()

    fun submit(list: List<Textbook>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemTextbookBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTextbookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val book = items[position]
        val b = holder.binding
        b.bookTitle.text = book.title
        b.bookMeta.text = buildString {
            append(book.publisher.ifBlank { "出版社未填" })
            if (book.edition.isNotBlank()) append(" · ").append(book.edition)
        }
        b.bookProgress.text = b.root.context.getString(
            com.xueti.learn.R.string.textbook_progress,
            book.chapterCount,
            book.sectionCount,
            book.studiedCount
        )
        b.root.setOnClickListener { onClick(book) }
        b.btnDeleteBook.setOnClickListener { onDelete(book) }
    }

    override fun getItemCount(): Int = items.size
}
