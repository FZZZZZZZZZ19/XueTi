package com.xueti.learn

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.data.WordRepository
import com.xueti.learn.databinding.ActivityMainBinding
import com.xueti.learn.update.UpdateChecker
import com.xueti.learn.update.showUpdateDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：今日学习进度 + 功能入口
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository: WordRepository get() = (application as App).wordRepository
    private val store: ProgressStore get() = (application as App).progressStore
    private val settings: SettingsStore get() = (application as App).settings

    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subtitle.text = getString(R.string.home_date_prefix, dateFormat.format(Date()))

        binding.btnStartStudy.setOnClickListener {
            startActivity(Intent(this, StudyActivity::class.java))
        }
        binding.cardWordList.setOnClickListener {
            startActivity(Intent(this, WordListActivity::class.java))
        }
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.cardStats.setOnClickListener {
            toast(getString(R.string.coming_soon))
        }
        binding.cardAbout.setOnClickListener {
            toast(getString(R.string.about_slogan))
        }

        maybeAutoCheckUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshProgress()
    }

    private fun refreshProgress() {
        val goal = store.dailyGoal
        val todayLearned = store.todayLearnedCount().coerceAtMost(goal)

        binding.todayCount.text = todayLearned.toString()
        binding.goalText.text = getString(R.string.goal_of, goal)
        binding.todayProgress.max = goal
        binding.todayProgress.progress = todayLearned

        binding.progressHint.text = when {
            todayLearned >= goal -> getString(R.string.hint_goal_done)
            todayLearned == 0 -> getString(R.string.hint_not_started)
            else -> getString(R.string.hint_keep_going, goal - todayLearned)
        }
        binding.btnStartStudy.text = getString(
            if (todayLearned >= goal) R.string.review_more else R.string.start_study
        )

        // 词库总数需异步获取（约 6600 词，已在 Application 预加载）
        binding.wordBankText.text = getString(R.string.word_bank_loading)
        lifecycleScope.launch {
            val total = repository.load().size
            binding.wordBankText.text =
                getString(R.string.word_bank_stat, store.learnedCount(), total)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 启动时自动检查更新（每天最多一次，可在设置中关闭） */
    private fun maybeAutoCheckUpdate() {
        if (!settings.autoCheckUpdate) return
        val today = dayFormat.format(Date())
        if (settings.lastUpdateCheckDate == today) return
        lifecycleScope.launch {
            UpdateChecker.fetchLatest().onSuccess { info ->
                settings.lastUpdateCheckDate = today
                if (UpdateChecker.isNewer(info.version, UpdateChecker.currentVersion())) {
                    showUpdateDialog(info)
                }
            }
        }
    }
}
