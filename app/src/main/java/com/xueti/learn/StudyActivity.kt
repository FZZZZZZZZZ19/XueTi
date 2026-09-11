package com.xueti.learn

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.base.BaseActivity
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
class StudyActivity : BaseActivity() {

    private lateinit var binding: ActivityStudyBinding
    private val repository: WordRepository get() = (application as App).wordRepository
    private val store: ProgressStore get() = (application as App).progressStore

    /** 本轮待学队列；counted 表示该词是否已计入今日进度；review 表示这是复习/巩固词 */
    private data class QueueItem(val word: Word, val counted: Boolean, val review: Boolean)

    private val queue = ArrayDeque<QueueItem>()
    private var current: QueueItem? = null
    private var showingBack = false
    private var sessionTotal = 0
    private var sessionFinished = 0
    private var reviewCount = 0
    private var newCount = 0

    /** 今日目标已完成，本轮只做复习 */
    private var goalMetMode = false

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

    /**
     * 组建今日学习队列：
     * 1) **复习/巩固词优先** —— 需复习的词（不认识）+ 很久没练可能遗忘的词（认识但超过 [ProgressStore.REVIEW_INTERVAL_DAYS] 天）
     * 2) 剩余额度用**新词**补足到每日目标
     * 3) 今日目标已完成时，仍可继续复习（此时只排复习词，不再影响今日进度）
     */
    private fun buildQueue(allWords: List<Word>) {
        // 目标可被日历按日期自定义（0 = 休息日）
        val goal = store.dailyGoalFor(store.today())
        if (goal <= 0) {
            sessionTotal = 0
            sessionFinished = 0
            showDoneState(noNewWords = true, restDay = true)
            return
        }
        val wordMap = allWords.associateBy { it.word }
        val remainToday = (goal - store.todayLearnedCount()).coerceAtLeast(0)
        goalMetMode = remainToday == 0
        val learnedWords = store.learnedRecords().map { it.word }.toSet()
        val dueWords = store.dueReviewWords().mapNotNull { wordMap[it.word] }

        val reviewPick: List<Word>
        val newPick: List<Word>
        if (remainToday == 0) {
            // 今日目标已完成：纯复习模式，单轮最多 50 个，避免一次过太多
            reviewPick = dueWords.take(REVIEW_ONLY_MAX)
            newPick = emptyList()
        } else {
            // 复习词先占约一半额度（保证新词也能推进），新词不够时再用复习词补满
            val budget = (remainToday + 1) / 2
            val firstReview = dueWords.take(minOf(remainToday, budget))
            val newPool = allWords.filter { it.word !in learnedWords }
            val pickedNew = newPool.take(remainToday - firstReview.size)
            val extraReview = dueWords.drop(firstReview.size)
                .take(remainToday - firstReview.size - pickedNew.size)
            reviewPick = firstReview + extraReview
            newPick = pickedNew
        }

        reviewPick.forEach { queue.addLast(QueueItem(it, counted = false, review = true)) }
        newPick.forEach { queue.addLast(QueueItem(it, counted = false, review = false)) }

        reviewCount = reviewPick.size
        newCount = newPick.size
        sessionTotal = queue.size
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

    /**
     * 作答：
     * - 新词「认识」→ 记为掌握；「不认识」→ 记为需复习并排到队尾再练
     * - 复习词「认识」→ 不认识的词需连续答对 2 次才算掌握；很久没练的词答对一次即刷新
     * - 复习词「不认识」→ 清零并排到队尾再练
     */
    private fun answer(familiarity: Familiarity) {
        val item = current ?: return
        val countToday = !item.counted

        if (familiarity == Familiarity.UNKNOWN) {
            store.record(item.word.word, Familiarity.UNKNOWN, countToday = countToday)
            sessionFinished++
            // 不认识的词排到队尾，本轮再练一次（不重复计入今日进度）；
            // 之后再答对按复习规则处理：需连续答对 2 次才算掌握
            queue.addLast(item.copy(counted = true, review = true))
            showNext()
            return
        }

        val mastered = if (item.review) {
            store.answerReviewCorrect(item.word.word)
        } else {
            store.record(item.word.word, Familiarity.KNOWN, countToday = countToday)
            true
        }
        sessionFinished++
        if (!mastered) {
            // 复习词还差一次连续答对，队尾再来一遍
            queue.addLast(item.copy(counted = true))
        }
        showNext()
    }

    private fun updateProgress() {
        binding.sessionProgress.max = sessionTotal
        binding.sessionProgress.progress = sessionFinished.coerceAtMost(sessionTotal)
        binding.sessionText.text = getString(R.string.session_progress, sessionFinished, sessionTotal)
        binding.sessionPlanText.text = when {
            reviewCount > 0 && newCount > 0 ->
                getString(R.string.session_plan_both, reviewCount, newCount)
            reviewCount > 0 && goalMetMode ->
                getString(R.string.session_plan_review_only, reviewCount)
            reviewCount > 0 -> getString(R.string.session_plan_review, reviewCount)
            else -> getString(R.string.session_plan_new, newCount)
        }
    }

    private fun showDoneState(noNewWords: Boolean, restDay: Boolean = false) {
        binding.studyContainer.visibility = View.GONE
        binding.doneContainer.visibility = View.VISIBLE
        val goal = store.dailyGoalFor(store.today())
        val goalMet = goal > 0 && store.todayLearnedCount() >= goal
        binding.doneTitle.text = when {
            restDay -> getString(R.string.rest_day_title)
            goalMet -> getString(R.string.done_title)
            noNewWords -> getString(R.string.done_no_new_words)
            else -> getString(R.string.done_title)
        }
        val due = store.dueReviewCount()
        binding.doneDetail.text = when {
            restDay -> getString(R.string.rest_day_detail)
            due > 0 -> getString(
                R.string.done_detail_due,
                store.todayLearnedCount(),
                store.learnedCount(),
                due
            )
            else -> getString(
                R.string.done_detail,
                store.todayLearnedCount(),
                store.learnedCount()
            )
        }
    }

    private companion object {
        /** 今日目标已完成后的纯复习模式下，单轮最多复习多少个词 */
        const val REVIEW_ONLY_MAX = 50
    }
}
