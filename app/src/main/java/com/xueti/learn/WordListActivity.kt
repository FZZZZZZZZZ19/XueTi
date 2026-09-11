package com.xueti.learn

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.WordListAdapter
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.WordRepository
import com.xueti.learn.databinding.ActivityWordListBinding
import kotlinx.coroutines.launch

/** 我的词库：已学单词与掌握情况 */
class WordListActivity : BaseActivity() {

    private lateinit var binding: ActivityWordListBinding
    private val repository: WordRepository get() = (application as App).wordRepository
    private val store: ProgressStore get() = (application as App).progressStore

    private val adapter = WordListAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWordListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.wordList.layoutManager = LinearLayoutManager(this)
        binding.wordList.adapter = adapter
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
        val records = store.learnedRecords()
        binding.statText.text = getString(
            R.string.word_list_stat,
            store.learnedCount(),
            store.knownCount(),
            store.unknownCount()
        )
        binding.emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        lifecycleScope.launch {
            val wordMap = repository.load().associateBy { it.word }
            adapter.submit(records.map { it to wordMap[it.word] })
        }
    }
}
