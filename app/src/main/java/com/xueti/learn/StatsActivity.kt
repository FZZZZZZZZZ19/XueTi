package com.xueti.learn

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.databinding.ActivityStatsBinding
import com.xueti.learn.model.Familiarity
import com.xueti.learn.util.GoalCountEditor
import com.xueti.learn.util.GoalEditor
import java.util.Calendar

/**
 * 学习统计：
 * 今日/连续/累计概览、近 7 天柱状图、学习日历（点击日期查看当天内容并自定义目标）、今日学习内容。
 */
class StatsActivity : BaseActivity() {

    private lateinit var binding: ActivityStatsBinding
    private val store: ProgressStore get() = (application as App).progressStore

    private var year = 0
    private var month = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val now = Calendar.getInstance()
        year = now.get(Calendar.YEAR)
        month = now.get(Calendar.MONTH) + 1

        binding.btnPrevMonth.setOnClickListener {
            month--
            if (month < 1) {
                month = 12
                year--
            }
            renderCalendar()
        }
        binding.btnNextMonth.setOnClickListener {
            month++
            if (month > 12) {
                month = 1
                year++
            }
            renderCalendar()
        }

        renderAll()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun renderAll() {
        renderOverview()
        renderChart()
        renderCalendar()
        renderTodayWords()
    }

    // ---------------- 概览 ----------------

    private fun renderOverview() {
        binding.statToday.text = store.todayLearnedCount().toString()
        binding.statStreak.text = store.streakDays().toString()
        binding.statTotal.text = store.learnedCount().toString()
        binding.knownRateText.text = getString(
            R.string.stats_known_rate,
            store.knownCount(),
            store.unknownCount(),
            store.knownRate()
        )
    }

    // ---------------- 近 7 天柱状图 ----------------

    private fun renderChart() {
        binding.chartContainer.removeAllViews()
        val days = store.recentDays(7)
        val maxValue = maxOf(days.maxOfOrNull { it.second } ?: 0, 1)
        val barMaxHeight = dp(90)
        val today = store.today()

        days.forEach { (date, count) ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            val countLabel = TextView(this).apply {
                text = count.toString()
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.stats_label))
            }
            column.addView(countLabel)

            val bar = View(this).apply {
                val height = if (count == 0) dp(3) else (barMaxHeight * count / maxValue).coerceAtLeast(dp(6))
                layoutParams = LinearLayout.LayoutParams(dp(18), height).apply { topMargin = dp(2) }
                setBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        if (date == today) R.color.stats_bar_today else R.color.stats_bar
                    )
                )
            }
            column.addView(bar)

            val dayLabel = TextView(this).apply {
                text = date.substring(8) // dd
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.stats_label))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            column.addView(dayLabel)

            binding.chartContainer.addView(column)
        }
    }

    // ---------------- 日历 ----------------

    private fun renderCalendar() {
        binding.monthTitle.text = getString(R.string.month_title, year, month)

        // 星期表头
        if (binding.weekHeader.childCount == 0) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                val text = TextView(this).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, R.color.stats_label))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                binding.weekHeader.addView(text)
            }
        }

        binding.calendarGrid.removeAllViews()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // 1 = 周日
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = store.today()

        // 月初空白
        for (i in 1 until firstDayOfWeek) {
            binding.calendarGrid.addView(emptyCell())
        }
        for (day in 1..daysInMonth) {
            binding.calendarGrid.addView(dayCell(String.format("%04d-%02d-%02d", year, month, day), day, today))
        }
    }

    private fun emptyCell(): View = View(this).apply {
        layoutParams = cellParams()
    }

    private fun dayCell(date: String, day: Int, today: String): View {
        val count = store.learnedCountOn(date)
        val goal = store.dailyGoalFor(date)
        val goalMet = goal > 0 && count >= goal

        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = cellParams()
            setPadding(dp(1), dp(3), dp(1), dp(3))
            when {
                date == today -> setBackgroundResource(R.drawable.bg_day_today)
                date == store.goalDate -> setBackgroundResource(R.drawable.bg_day_goal)
                goalMet -> setBackgroundResource(R.drawable.bg_day_done)
            }
            isClickable = true
            setOnClickListener { showDayDialog(date) }
        }

        cell.addView(
            TextView(this).apply {
                text = day.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (date == today) R.color.primary else R.color.on_surface_calendar
                    )
                )
            }
        )
        if (count > 0) {
            cell.addView(
                TextView(this).apply {
                    text = count.toString()
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, R.color.primary))
                }
            )
        }
        return cell
    }

    private fun cellParams(): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(46)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
    }

    /** 点击日期：查看当天学习内容、写目标寄语、自定义该日学习量 */
    private fun showDayDialog(date: String) {
        val words = store.wordsLearnedOn(date)
        val currentGoal = store.dailyGoalFor(date)
        val isCustom = store.customGoalFor(date) != null
        val isGoalDate = date == store.goalDate

        val message = buildString {
            append(getString(R.string.stats_day_learned, words.size))
            if (words.isNotEmpty()) {
                append("\n")
                append(words.take(40).joinToString("、"))
                if (words.size > 40) append(" …")
            }
            append("\n\n")
            append(getString(R.string.stats_day_goal, currentGoal))
            if (isCustom) append(getString(R.string.stats_day_custom_suffix))
            if (isGoalDate && store.goalText.isNotBlank()) {
                append("\n\n★ ")
                append(getString(R.string.goal_card_title))
                append("：")
                append(store.goalText)
            }
            append("\n")
            append(getString(R.string.stats_day_pick_goal))
        }

        val options = mutableListOf(
            getString(R.string.goal_set_for_day),
            getString(R.string.goal_follow_default),
            getString(R.string.goal_rest_day)
        )
        options.addAll(GOAL_OPTIONS.map { getString(R.string.goal_words, it) })
        options.add(getString(R.string.goal_custom_count))
        val customIndex = options.lastIndex

        AlertDialog.Builder(this)
            .setTitle(date)
            .setMessage(message)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        // 写目标/寄语，并把该日期设为目标日
                        GoalEditor.show(
                            activity = this,
                            store = store,
                            initialDate = date,
                            allowDateChange = false
                        ) { renderAll() }
                        return@setItems
                    }
                    1 -> store.setCustomGoal(date, null)
                    2 -> store.setCustomGoal(date, 0)
                    customIndex -> {
                        // 自定义该日练词数量
                        GoalCountEditor.show(
                            activity = this,
                            title = getString(R.string.goal_custom_count),
                            initial = currentGoal,
                            allowZero = true
                        ) { value ->
                            store.setCustomGoal(date, value)
                            renderAll()
                        }
                        return@setItems
                    }
                    else -> store.setCustomGoal(date, GOAL_OPTIONS[which - 3])
                }
                Toast.makeText(this, R.string.stats_goal_saved, Toast.LENGTH_SHORT).show()
                renderAll()
            }
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    // ---------------- 今日学习内容 ----------------

    private fun renderTodayWords() {
        val today = store.today()
        val goal = store.dailyGoalFor(today)
        val words = store.wordsLearnedOn(today)
        val records = store.learnedRecords().associateBy { it.word }

        binding.todayGoalText.text = getString(
            R.string.stats_today_goal,
            goal,
            words.size
        )
        binding.todayEmptyText.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
        binding.todayWordsContainer.removeAllViews()

        words.forEach { word ->
            val known = records[word]?.familiarity == Familiarity.KNOWN
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(
                TextView(this).apply {
                    text = word
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(
                TextView(this).apply {
                    text = getString(if (known) R.string.status_known else R.string.status_unknown)
                    textSize = 11f
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (known) R.color.tag_known_text else R.color.tag_unknown_text
                        )
                    )
                }
            )
            binding.todayWordsContainer.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val GOAL_OPTIONS = listOf(5, 10, 20, 30, 50)
    }
}
