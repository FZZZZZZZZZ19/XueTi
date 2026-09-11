package com.xueti.learn

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.WordRepository
import com.xueti.learn.databinding.ActivityStudyBinding
import com.xueti.learn.model.Familiarity
import com.xueti.learn.model.Word
import kotlinx.coroutines.launch

/**
 * 每日单词学习：
 * 卡片式流程 —— 正面单词/音标，点击翻转看释义；
 * 「认识」标记掌握，「不认识」重新排到本轮队尾再练一次。
 */
class StudyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudyBinding
    private val repository: WordRepository get() = (application as App).wordRepository
    private val store: ProgressStore get() = (application as App).progressStore

    /** 本轮待学队列；counted 表示该词是否已计入今日进度 */
    private data class QueueItem(val word: Word, val counted: Boolean)

    private val queue = ArrayDeque<QueueItem>()
    private var current: QueueItem? = null
    private var showingBack = false
    private var sessionTotal = 0
    private var sessionFinished = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.wordCard.setOnClickListener { flipCard() }
        binding.btnKnown.setOnClickListener { answer(Familiarity.KNOWN) }
        binding.btnUnknown.setOnClickListener { answer(Familiarity.UNKNOWN) }
        binding.btnBackHome.setOnClickListener { finish() }

        startSession()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 异步加载词库后组建今日学习队列（词库已在 Application 预加载，通常瞬时完成） */
    private fun startSession() {
        binding.loadingView.isVisible = true
        binding.studyContainer.isVisible = false
        lifecycleScope.launch {
            val allWords = repository.load()
            buildQueue(allWords)
            binding.loadingView.isVisible = false
            binding.studyContainer.isVisible = true
        }
    }

    /** 取今日尚未学习的单词组成学习队列 */
    private fun buildQueue(allWords: List<Word>) {
        val goal = store.dailyGoal
        val learnedWords = store.learnedRecords().map { it.word }.toSet()
        val remainToday = (goal - store.todayLearnedCount()).coerceAtLeast(0)
        val pool = allWords.filter { it.word !in learnedWords }

        val pick = pool.take(remainToday)
        pick.forEach { queue.addLast(QueueItem(it, counted = false)) }
        sessionTotal = pick.size
        sessionFinished = 0

        if (queue.isEmpty()) {
            showDoneState(noNewWords = true)
        } else {
            showNext()
        }
    }

    private fun showNext() {
        val item = queue.removeFirstOrNull()
        if (item == null) {
            showDoneState(noNewWords = false)
            return
        }
        current = item
        showingBack = false
        renderCard(item.word)
        updateProgress()
    }

    private fun renderCard(word: Word) {
        binding.wordText.text = word.word
        binding.phoneticText.text = word.phonetic
        binding.backWordText.text = word.word
        binding.posMeaningText.text = getString(R.string.word_pos_meaning, word.pos, word.meaning)
        binding.exampleText.text = word.example
        binding.exampleCnText.text = word.exampleCn
        // 部分词条没有例句，隐藏空白区域
        binding.exampleText.isVisible = word.example.isNotBlank()
        binding.exampleCnText.isVisible = word.exampleCn.isNotBlank()

        binding.frontSide.isVisible = !showingBack
        binding.backSide.isVisible = showingBack
    }

    private fun flipCard() {
        showingBack = !showingBack
        binding.frontSide.isVisible = !showingBack
        binding.backSide.isVisible = showingBack
    }

    private fun answer(familiarity: Familiarity) {
        val item = current ?: return
        store.record(item.word.word, familiarity, countToday = !item.counted)
        sessionFinished++

        if (familiarity == Familiarity.UNKNOWN) {
            // 不认识的词排到队尾，本轮再练一次（不重复计入今日进度）
            queue.addLast(item.copy(counted = true))
        }
        showNext()
    }

    private fun updateProgress() {
        binding.sessionProgress.max = sessionTotal
        binding.sessionProgress.progress = sessionFinished.coerceAtMost(sessionTotal)
        binding.sessionText.text = getString(R.string.session_progress, sessionFinished, sessionTotal)
    }

    private fun showDoneState(noNewWords: Boolean) {
        binding.studyContainer.visibility = View.GONE
        binding.doneContainer.visibility = View.VISIBLE
        binding.doneTitle.text = getString(
            if (noNewWords) R.string.done_no_new_words else R.string.done_title
        )
        binding.doneDetail.text = getString(
            R.string.done_detail,
            store.todayLearnedCount(),
            store.learnedCount()
        )
    }
}
