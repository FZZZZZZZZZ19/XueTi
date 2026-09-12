package com.xueti.learn

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.TextbookAdapter
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.databinding.ActivityTextbookListBinding
import com.xueti.learn.model.Textbook

/** 全书学习：书本列表（新建 / 删除） */
class TextbookListActivity : BaseActivity() {

    private lateinit var binding: ActivityTextbookListBinding
    private val store by lazy { TextbookStore(this) }

    private val adapter = TextbookAdapter(
        onClick = { book ->
            startActivity(
                Intent(this, TextbookDetailActivity::class.java)
                    .putExtra(TextbookDetailActivity.EXTRA_BOOK_ID, book.id)
            )
        },
        onDelete = { book -> confirmDelete(book) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextbookListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.textbookList.layoutManager = LinearLayoutManager(this)
        binding.textbookList.adapter = adapter
        binding.fabAddBook.setOnClickListener {
            startActivity(Intent(this, TextbookCreateActivity::class.java))
        }
        binding.btnCurated.setOnClickListener {
            startActivity(Intent(this, CuratedActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val books = store.all().sortedByDescending { it.createdAt }
        adapter.submit(books)
        binding.emptyView.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(book: Textbook) {
        AlertDialog.Builder(this)
            .setTitle(R.string.textbook_delete)
            .setMessage(getString(R.string.textbook_delete_confirm, book.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(book.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
