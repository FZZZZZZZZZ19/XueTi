package com.xueti.learn

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.DictionaryAdapter
import com.xueti.learn.adapter.WordListAdapter
import com.xueti.learn.ai.DeepSeekClient
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.MistakeStore
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.ToDoStore
import com.xueti.learn.data.TodoItem
import com.xueti.learn.data.UsageStore
import com.xueti.learn.data.WordRepository
import com.xueti.learn.databinding.ActivityStudyHubBinding
import com.xueti.learn.databinding.DialogReviewBinding
import com.xueti.learn.databinding.DialogTodoAddBinding
import com.xueti.learn.databinding.DialogWordDetailBinding
import com.xueti.learn.databinding.ItemTodoBinding
import com.xueti.learn.model.Familiarity
import com.xueti.learn.model.Word
import com.xueti.learn.util.AiResultDialog
import kotlinx.coroutines.launch

/**
 * 六级学习（合并页）：一个页面搞定六级相关的全部功能
 * - 今日进度 / 学习统计
 * - 独立复习：过一遍所有「需复习」的词，连续答对 2 次才算掌握并摘掉标记
 * - AI 工具：中英互译、AI 造句
 * - 词典：全览全部单词 + 页内搜索（英 / 中）
 * - 我的词库：已学单词列表
 */
class StudyHubActivity : BaseActivity() {

    private lateinit var binding: ActivityStudyHubBinding
    private val repository: WordRepository get() = (application as App).wordRepository
    private val store: ProgressStore get() = (application as App).progressStore
    private val settings by lazy { SettingsStore(this) }
    private val usageStore by lazy { UsageStore(this) }
    private val toDoStore by lazy { ToDoStore(this) }
    private val mistakeStore by lazy { MistakeStore(this) }

    private val adapter = WordListAdapter()
    private val dictAdapter = DictionaryAdapter { word -> showWordDetail(word) }

    /** 词典全量数据（6662 词），搜索在内存中过滤 */
    private var allWords: List<Word> = emptyList()

    // ---------------- 复习会话状态 ----------------
    private val reviewQueue = ArrayDeque<Word>()
    private var reviewTotal = 0
    private var reviewMastered = 0
    private var reviewDialog: AlertDialog? = null
    private var reviewBinding: DialogReviewBinding? = null
    /** 点「想不起来」后进入「看答案 → 下一个」状态，避免重复计数 */
    private var reviewAwaitingNext = false

    private val searchWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudyHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 我的词库
        binding.myWordList.layoutManager = LinearLayoutManager(this)
        binding.myWordList.adapter = adapter

        // 词典
        dictAdapter.recordProvider = { word -> store.recordOf(word) }
        binding.dictList.layoutManager = LinearLayoutManager(this)
        binding.dictList.adapter = dictAdapter
        binding.etSearch.addTextChangedListener(searchWatcher)

        binding.btnStartStudy.setOnClickListener {
            startActivity(Intent(this, StudyActivity::class.java))
        }
        binding.btnStartReview.setOnClickListener { startReview() }
        binding.todoStudyRow.setOnClickListener {
            startActivity(Intent(this, StudyActivity::class.java))
        }
        binding.todoStudyAction.setOnClickListener {
            startActivity(Intent(this, StudyActivity::class.java))
        }
        binding.todoReviewRow.setOnClickListener { startReview() }
        binding.todoReviewAction.setOnClickListener { startReview() }
        binding.btnAddTodo.setOnClickListener { showAddTodoDialog() }
        binding.todoClearDone.setOnClickListener { clearDoneTodos() }
        binding.btnAiTranslate.setOnClickListener {
            runAi(
                title = getString(R.string.ai_translate_title, shortInput()),
                progress = binding.aiProgress
            ) { key, model ->
                DeepSeekClient.translate(
                    apiKey = key,
                    model = model,
                    text = binding.etAiInput.text?.toString().orEmpty().trim(),
                    direction = DeepSeekClient.TranslateDirection.AUTO
                )
            }
        }
        binding.btnAiSentence.setOnClickListener {
            runAi(
                title = getString(R.string.ai_sentence_title, shortInput()),
                progress = binding.aiProgress
            ) { key, model ->
                DeepSeekClient.generateSentences(
                    apiKey = key,
                    model = model,
                    word = binding.etAiInput.text?.toString().orEmpty().trim()
                )
            }
        }

        loadDictionary()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ==================== 今日进度 / 统计 / 词库 ====================

    private fun refresh() {
        // 今日进度（目标可能被日历按日自定义，0 = 休息日）
        val goal = store.dailyGoalFor(store.today())
        val todayLearned = store.todayLearnedCount().coerceAtMost(maxOf(goal, 0))
        binding.todayCount.text = todayLearned.toString()
        binding.goalText.text = getString(R.string.goal_of, goal)
        binding.todayProgress.max = maxOf(goal, 1)
        binding.todayProgress.progress = todayLearned
        binding.progressHint.text = when {
            goal <= 0 -> getString(R.string.rest_day_title)
            todayLearned >= goal -> getString(R.string.hint_goal_done)
            todayLearned == 0 -> getString(R.string.hint_not_started)
            else -> getString(R.string.hint_keep_going, goal - todayLearned)
        }
        binding.btnStartStudy.text =
            getString(if (goal > 0 && todayLearned >= goal) R.string.review_more else R.string.start_study)

        // 统计概览
        binding.statToday.text = store.todayLearnedCount().toString()
        binding.statStreak.text = store.streakDays().toString()
        binding.statTotal.text = store.learnedCount().toString()
        binding.statRate.text = getString(R.string.percent_value, store.knownRate())

        // 需复习的词（不认识 + 很久没练可能遗忘的）
        val due = store.dueReviewWords()
        val unknownDue = due.count { it.familiarity == Familiarity.UNKNOWN }
        val overdueDue = due.size - unknownDue
        binding.reviewCountText.text =
            if (due.isEmpty()) {
                getString(R.string.review_none)
            } else {
                getString(R.string.review_title_count, due.size)
            }
        binding.reviewBreakdown.text = getString(
            R.string.review_breakdown,
            unknownDue,
            ProgressStore.REVIEW_INTERVAL_DAYS,
            overdueDue
        )
        binding.btnStartReview.isEnabled = due.isNotEmpty()
        binding.btnStartReview.alpha = if (due.isNotEmpty()) 1f else 0.5f

        // 今日待办
        renderTodo()

        // 我的词库
        val records = store.learnedRecords()
        binding.wordListTitle.text = getString(R.string.word_list_title_with_count, records.size)
        binding.knownRateText.text =
            getString(R.string.stats_known_rate_short, store.knownCount(), store.unknownCount())
        binding.emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.myWordList.isVisible = records.isNotEmpty()

        lifecycleScope.launch {
            val wordMap = repository.load().associateBy { it.word }
            adapter.submit(records.map { it to wordMap[it.word] })
        }
    }

    // ==================== 独立复习 ====================

    /** 进入复习：队列 = 所有待复习词（不认识的 + 很久没练可能遗忘的） */
    private fun startReview() {
        val records = store.dueReviewWords()
        if (records.isEmpty()) {
            toast(getString(R.string.review_none))
            return
        }
        lifecycleScope.launch {
            val wordMap = repository.load().associateBy { it.word }
            val words = records.mapNotNull { wordMap[it.word] }
            if (words.isEmpty()) {
                toast(getString(R.string.review_none))
                return@launch
            }
            reviewQueue.clear()
            reviewQueue.addAll(words)
            reviewTotal = words.size
            reviewMastered = 0
            showReviewDialog()
        }
    }

    private fun showReviewDialog() {
        val view = DialogReviewBinding.inflate(layoutInflater)
        reviewBinding = view
        reviewAwaitingNext = false

        view.btnReviewKnown.setOnClickListener {
            if (reviewAwaitingNext) {
                advanceReview()
            } else {
                markReviewCorrect()
            }
        }
        view.btnReviewUnknown.setOnClickListener { revealReviewAnswer() }
        view.btnReviewMistake.setOnClickListener { markReviewMistake() }
        view.btnReviewAiSentence.setOnClickListener {
            val word = reviewQueue.firstOrNull() ?: return@setOnClickListener
            runAi(
                title = getString(R.string.ai_sentence_title, word.word),
                progress = view.reviewAiProgress
            ) { key, model -> DeepSeekClient.generateSentences(key, model, word.word) }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view.root)
            .setNegativeButton(R.string.review_exit, null)
            .create()
        dialog.setOnDismissListener {
            reviewDialog = null
            reviewBinding = null
            refresh()
        }
        reviewDialog = dialog
        dialog.show()
        renderReviewCard()
    }

    private fun renderReviewCard() {
        val view = reviewBinding ?: return
        val word = reviewQueue.firstOrNull()
        if (word == null) {
            finishReview()
            return
        }
        reviewAwaitingNext = false

        view.reviewProgressText.text = getString(
            R.string.review_progress,
            reviewMastered,
            reviewTotal,
            reviewQueue.size
        )
        view.reviewSessionProgress.max = maxOf(reviewTotal, 1)
        view.reviewSessionProgress.progress = reviewMastered
        view.reviewWord.text = word.word
        view.reviewPhonetic.text = word.phonetic
        view.reviewHint.text = if (store.recordOf(word.word)?.familiarity == Familiarity.KNOWN) {
            getString(R.string.review_rule_overdue, ProgressStore.REVIEW_INTERVAL_DAYS)
        } else {
            getString(R.string.review_rule_unknown)
        }
        view.reviewMeaning.text = "${word.pos} ${word.meaning}".trim()
        view.reviewExample.text = word.example
        view.reviewExampleCn.text = word.exampleCn
        view.reviewExample.isVisible = word.example.isNotBlank()
        view.reviewExampleCn.isVisible = word.exampleCn.isNotBlank()

        // 先回忆，答案默认隐藏
        view.reviewAnswerBox.isVisible = false
        view.reviewFeedback.isVisible = false
        view.btnReviewUnknown.isVisible = true
        view.btnReviewUnknown.setText(R.string.review_unknown)
        view.btnReviewKnown.setText(R.string.review_known)
    }

    /** 认识：不认识的词要连续答对 2 次；很久没练的词答对一次即刷新计时 */
    private fun markReviewCorrect() {
        val view = reviewBinding ?: return
        val word = reviewQueue.removeFirstOrNull() ?: return
        val mastered = store.answerReviewCorrect(word.word)
        if (mastered) {
            reviewMastered++
        } else {
            // 还差一次，本轮稍后再过一遍
            reviewQueue.addLast(word)
        }
        if (reviewQueue.isEmpty()) {
            finishReview()
        } else {
            renderReviewCard()
        }
        view.reviewSessionProgress.progress = reviewMastered
    }

    /** 想不起来：显示释义与例句，记为需复习，稍后再练 */
    private fun revealReviewAnswer() {
        val view = reviewBinding ?: return
        val word = reviewQueue.firstOrNull() ?: return
        store.resetReviewCorrect(word.word)
        reviewQueue.removeFirst()
        reviewQueue.addLast(word)
        reviewAwaitingNext = true

        view.reviewAnswerBox.isVisible = true
        view.reviewFeedback.isVisible = true
        view.reviewFeedback.text = getString(R.string.review_unknown_tip)
        view.btnReviewUnknown.isVisible = false
        view.btnReviewKnown.setText(R.string.review_next)
    }

    /** 看完答案后进入下一个词（该词已在「想不起来」时移到队尾） */
    private fun advanceReview() {
        if (reviewQueue.isEmpty()) finishReview() else renderReviewCard()
    }

    /** 这词我记错了：存进错题本，同时按答错处理（清零并排到队尾） */
    private fun markReviewMistake() {
        val view = reviewBinding ?: return
        val word = reviewQueue.firstOrNull() ?: return
        mistakeStore.add(
            question = "${word.word}  ${word.phonetic}".trim(),
            aiAnswer = buildString {
                append("${word.pos} ${word.meaning}".trim())
                if (word.example.isNotBlank()) {
                    append("\n\n例句：").append(word.example)
                    if (word.exampleCn.isNotBlank()) append("\n").append(word.exampleCn)
                }
            },
            source = MistakeStore.SOURCE_REVIEW,
            subject = "英语"
        )
        store.resetReviewCorrect(word.word)
        reviewQueue.removeFirst()
        reviewQueue.addLast(word)
        reviewAwaitingNext = true

        toast(getString(R.string.review_mistake_saved, word.word))
        view.reviewAnswerBox.isVisible = true
        view.reviewFeedback.isVisible = true
        view.reviewFeedback.text = getString(R.string.review_unknown_tip)
        view.btnReviewUnknown.isVisible = false
        view.btnReviewKnown.setText(R.string.review_next)
    }

    private fun finishReview() {
        val total = reviewTotal
        val mastered = reviewMastered
        reviewDialog?.dismiss()
        toast(getString(R.string.review_finished, total, mastered))
    }

    // ==================== 今日待办 ====================

    /** 待办卡片：2 项自动任务（学习新词 / 复习巩固）+ 自定义待办 */
    private fun renderTodo() {
        val goal = store.dailyGoalFor(store.today())
        val todayLearned = store.todayLearnedCount()
        val studyDone = goal > 0 && todayLearned >= goal
        binding.todoStudyIcon.text = if (studyDone) ICON_DONE else ICON_TODO
        binding.todoStudyState.text = if (goal <= 0) {
            getString(R.string.rest_day_title)
        } else {
            getString(R.string.todo_study_state, todayLearned, goal)
        }
        binding.todoStudyAction.setText(
            if (studyDone) R.string.todo_study_again else R.string.todo_go_study
        )

        val due = store.dueReviewCount()
        val reviewDone = due == 0
        binding.todoReviewIcon.text = if (reviewDone) ICON_DONE else ICON_TODO
        binding.todoReviewState.text = if (reviewDone) {
            getString(R.string.todo_review_state_done)
        } else {
            getString(R.string.todo_review_state, due)
        }
        binding.todoReviewAction.setText(
            if (reviewDone) R.string.todo_review_again else R.string.todo_go_review
        )

        val items = toDoStore.items()
        binding.todoListContainer.removeAllViews()
        items.forEach { item -> binding.todoListContainer.addView(buildTodoRow(item)) }
        binding.todoEmpty.isVisible = items.isEmpty()

        val doneCustom = items.count { it.done }
        binding.todoClearDone.isVisible = doneCustom > 0
        binding.todoClearDone.text = getString(R.string.todo_clear_done, doneCustom)

        val doneTotal = (if (studyDone) 1 else 0) + (if (reviewDone) 1 else 0) + doneCustom
        binding.todoSummary.text = getString(R.string.todo_summary, doneTotal, 2 + items.size)
    }

    private fun buildTodoRow(item: TodoItem): View {
        val row = ItemTodoBinding.inflate(layoutInflater, binding.todoListContainer, false)
        row.todoCheck.isChecked = item.done
        row.todoText.text = item.text
        row.todoText.paint.isStrikeThruText = item.done
        row.root.alpha = if (item.done) 0.6f else 1f
        row.root.setOnClickListener {
            toDoStore.setDone(item.id, !item.done)
            renderTodo()
        }
        row.root.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.todo_delete_title)
                .setMessage(getString(R.string.todo_delete_confirm, item.text))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    toDoStore.remove(item.id)
                    renderTodo()
                }
                .show()
            true
        }
        return row.root
    }

    private fun showAddTodoDialog() {
        val view = DialogTodoAddBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.todo_add_title)
            .setView(view.root)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = view.etTodoText.text?.toString().orEmpty().trim()
                if (text.isEmpty()) {
                    view.todoInputLayout.error = getString(R.string.todo_add_required)
                    return@setOnClickListener
                }
                toDoStore.add(text)
                renderTodo()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun clearDoneTodos() {
        val removed = toDoStore.clearDone()
        if (removed > 0) toast(getString(R.string.todo_cleared, removed))
        renderTodo()
    }

    // ==================== 词典（全览 + 搜索） ====================

    private fun loadDictionary() {
        binding.countText.text = getString(R.string.dictionary_loading)
        lifecycleScope.launch {
            allWords = repository.load()
            applyFilter(binding.etSearch.text?.toString().orEmpty())
        }
    }

    /** 按英文单词（不区分大小写）或中文释义过滤 */
    private fun applyFilter(query: String) {
        val keyword = query.trim()
        val filtered = if (keyword.isEmpty()) {
            allWords
        } else {
            val lower = keyword.lowercase()
            allWords.filter { word ->
                word.word.lowercase().contains(lower) ||
                    word.meaning.contains(keyword) ||
                    word.pos.contains(keyword)
            }
        }
        dictAdapter.submit(filtered)
        binding.countText.text =
            getString(R.string.dictionary_count, filtered.size, allWords.size)
    }

    private fun showWordDetail(word: Word) {
        val detail = DialogWordDetailBinding.inflate(layoutInflater)
        detail.detailWord.text = word.word
        detail.detailPhonetic.text = word.phonetic
        detail.detailMeaning.text = "${word.pos} ${word.meaning}".trim()
        detail.detailExample.text = word.example
        detail.detailExampleCn.text = word.exampleCn
        detail.detailExample.isVisible = word.example.isNotBlank()
        detail.detailExampleCn.isVisible = word.exampleCn.isNotBlank()

        fun renderStatus() {
            val record = store.recordOf(word.word)
            val unknown = record?.familiarity == Familiarity.UNKNOWN
            when {
                record == null -> {
                    detail.detailStatus.setText(R.string.dict_status_new)
                    detail.detailStatus.setBackgroundResource(R.drawable.bg_tag_unknown)
                    detail.btnToggleMark.setText(R.string.dict_mark_review)
                }
                unknown -> {
                    detail.detailStatus.setText(R.string.dict_status_review)
                    detail.detailStatus.setBackgroundResource(R.drawable.bg_tag_unknown)
                    detail.btnToggleMark.setText(R.string.dict_mark_known)
                }
                else -> {
                    detail.detailStatus.setText(R.string.dict_status_known)
                    detail.detailStatus.setBackgroundResource(R.drawable.bg_tag_known)
                    detail.btnToggleMark.setText(R.string.dict_mark_review)
                }
            }
        }
        renderStatus()

        val dialog = AlertDialog.Builder(this)
            .setView(detail.root)
            .setPositiveButton(R.string.confirm, null)
            .create()

        detail.btnToggleMark.setOnClickListener {
            val record = store.recordOf(word.word)
            if (record?.familiarity == Familiarity.UNKNOWN) {
                // 标记已掌握：直接加入已学并摘掉需复习标记
                store.resetReviewCorrect(word.word)
                store.record(word.word, Familiarity.KNOWN, countToday = false)
                toast(getString(R.string.dict_marked_known))
            } else {
                // 标记需复习：计入需复习队列，连续答对 2 次才会摘掉
                store.record(word.word, Familiarity.UNKNOWN, countToday = false)
                toast(getString(R.string.dict_marked_review))
            }
            renderStatus()
            dictAdapter.notifyDataSetChanged()
            refresh()
        }

        detail.btnAiSentence.setOnClickListener {
            runAi(
                title = getString(R.string.ai_sentence_title, word.word),
                progress = detail.detailProgress
            ) { key, model -> DeepSeekClient.generateSentences(key, model, word.word) }
        }

        detail.btnTranslate.setOnClickListener {
            runAi(
                title = getString(R.string.ai_translate_title, word.word),
                progress = detail.detailProgress
            ) { key, model ->
                DeepSeekClient.translate(
                    apiKey = key,
                    model = model,
                    text = word.word,
                    direction = DeepSeekClient.TranslateDirection.EN_TO_ZH
                )
            }
        }

        dialog.show()
    }

    // ==================== AI 通用调用 ====================

    private fun shortInput(): String {
        val text = binding.etAiInput.text?.toString().orEmpty().trim()
        return if (text.length > 12) text.take(12) + "…" else text
    }

    /** 统一的 AI 调用：校验 Key、显示进度、统计用量、弹出结果 */
    private fun runAi(
        title: String,
        progress: ProgressBar,
        call: suspend (String, String) -> Result<DeepSeekClient.AskResult>
    ) {
        val apiKey = settings.deepSeekApiKey
        if (apiKey.isBlank()) {
            toast(getString(R.string.ai_need_key))
            return
        }
        val word = binding.etAiInput.text?.toString().orEmpty().trim()
        if (progress === binding.aiProgress && word.isEmpty()) {
            toast(getString(R.string.ai_need_input))
            return
        }
        progress.isVisible = true
        lifecycleScope.launch {
            val result = call(apiKey, settings.aiModel)
            progress.isVisible = false
            usageStore.record(result.getOrNull()?.usage)
            result.onSuccess { ask ->
                AiResultDialog.show(
                    activity = this@StudyHubActivity,
                    title = title,
                    text = ask.text,
                    meta = getString(
                        R.string.ai_done_tokens,
                        settings.aiModel,
                        ask.usage?.totalTokens ?: 0
                    )
                )
            }.onFailure { error ->
                toast(getString(R.string.ai_failed, error.message ?: "未知错误"))
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val ICON_DONE = "✓"
        const val ICON_TODO = "○"
    }
}
