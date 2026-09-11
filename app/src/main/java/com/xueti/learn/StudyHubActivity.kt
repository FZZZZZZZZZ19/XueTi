package com.xueti.learn

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xueti.learn.adapter.WordListAdapter
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.WordRepository
import com.xueti.learn.databinding.ActivityStudyHubBinding
import kotlinx.coroutines.launch

/**
 * 六级学习（合并页）：
 * 今日进度 + 学习统计概览 + 我的词库（已学单词列表）
 */
class StudyHubActivity : BaseActivity() {

    private lateinit var binding: ActivityStudyHubBinding
    private val repository: WordRepository get() = (application as App).wordRepository
    private val store: ProgressStore get() = (application as App).progressStore

    private val adapter = WordListAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudyHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.wordList.layoutManager = LinearLayoutManager(this)
        binding.wordList.adapter = adapter
        binding.btnStartStudy.setOnClickListener {
            startActivity(Intent(this, StudyActivity::class.java))
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

        // 我的词库
        val records = store.learnedRecords()
        binding.wordListTitle.text = getString(R.string.word_list_title_with_count, records.size)
        binding.knownRateText.text =
            getString(R.string.stats_known_rate_short, store.knownCount(), store.unknownCount())
        binding.emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        lifecycleScope.launch {
            val wordMap = repository.load().associateBy { it.word }
            adapter.submit(records.map { it to wordMap[it.word] })
        }
    }
}
