package com.xueti.learn

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.MistakeStore
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.TextbookStore
import com.xueti.learn.databinding.ActivityStatsBinding
import com.xueti.learn.model.Familiarity
import com.xueti.learn.util.GoalCountEditor
import com.xueti.learn.util.GoalEditor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 学习统计：
 * - 概览（今日 / 连续 / 累计 / 认识率）
 * - **六级学习模块**：近 14 天学习曲线、掌握情况饼图、复习队列柱状图、覆盖率汇总
 * - **全书学习模块**：全书小节进度饼图、每本书进度分组柱状图、逐书明细
 * - **错题学习模块**：近 14 天新增错题柱状图、掌握情况饼图、学科分布柱状图、汇总
 * - 学习日历（点击日期查看当天内容并自定义目标）、今日学习内容
 */
class StatsActivity : BaseActivity() {

    private lateinit var binding: ActivityStatsBinding
    private val store: ProgressStore get() = (application as App).progressStore
    private val textbookStore by lazy { TextbookStore(this) }
    private val mistakeStore by lazy { MistakeStore(this) }

    private var year = 0
    private var month = 0

    /** 词库总词数（异步读取，用于覆盖率） */
    private var totalWords = 0

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 图表配色（跟随主题） */
    private val colorPrimary by lazy { ContextCompat.getColor(this, R.color.primary) }
    private val colorLabel by lazy { ContextCompat.getColor(this, R.color.stats_label) }
    private val colorKnown by lazy { ContextCompat.getColor(this, R.color.tag_known_text) }
    private val colorUnknown by lazy { ContextCompat.getColor(this, R.color.tag_unknown_text) }
    private val colorAccent by lazy { ContextCompat.getColor(this, R.color.stats_bar_today) }

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

        // 词库总词数（六级覆盖率用），加载完成后刷新一次汇总
        lifecycleScope.launch {
            totalWords = runCatching { (application as App).wordRepository.load().size }.getOrDefault(0)
            renderCetModule()
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
        renderCetModule()
        renderTextbookModule()
        renderMistakeModule()
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

    // ---------------- 模块一：六级学习 ----------------

    private fun renderCetModule() {
        // ① 近 14 天学习曲线
        val days = store.recentDays(14)
        val entries = days.mapIndexed { index, (_, count) -> Entry(index.toFloat(), count.toFloat()) }
        styleLineChart(
            chart = binding.cetDailyChart,
            entries = entries,
            labels = days.map { it.first.substring(5) } // MM-dd
        )

        // ② 掌握情况饼图
        val known = store.knownCount()
        val unknown = store.unknownCount()
        stylePieChart(
            chart = binding.cetKnownPie,
            entries = listOf(
                PieEntry(known.toFloat(), getString(R.string.dict_status_known)),
                PieEntry(unknown.toFloat(), getString(R.string.status_unknown))
            ),
            colors = listOf(colorKnown, colorUnknown),
            centerText = if (known + unknown == 0) "" else "${store.knownRate()}%"
        )

        // ③ 复习队列柱状图（不认识 / 超期未练）
        val overdue = store.overdueKnownCount()
        styleBarChart(
            chart = binding.cetReviewChart,
            groups = listOf(
                getString(R.string.status_unknown) to listOf(unknown.toFloat()),
                getString(R.string.stats_bar_overdue) to listOf(overdue.toFloat())
            ),
            colors = listOf(colorUnknown, colorAccent),
            labels = listOf("")
        )

        // ④ 文本汇总
        binding.cetSummary.text = buildString {
            append(
                getString(
                    R.string.stats_cet_summary,
                    store.knownRate(),
                    store.streakDays(),
                    store.dueReviewCount()
                )
            )
            if (totalWords > 0) {
                append("\n")
                append(
                    getString(
                        R.string.stats_cet_coverage,
                        store.learnedCount(),
                        totalWords,
                        store.learnedCount() * 100 / totalWords
                    )
                )
            }
            append("\n")
            append(getString(R.string.stats_cet_plan, store.dailyGoalFor(store.today())))
        }
    }

    // ---------------- 模块二：全书学习 ----------------

    private fun renderTextbookModule() {
        val books = textbookStore.all()
        val totalSections = books.sumOf { it.sectionCount }
        val studiedSections = books.sumOf { it.studiedCount }

        // ① 全书小节进度饼图
        stylePieChart(
            chart = binding.bookTotalPie,
            entries = listOf(
                PieEntry(studiedSections.toFloat(), getString(R.string.stats_book_studied)),
                PieEntry((totalSections - studiedSections).coerceAtLeast(0).toFloat(), getString(R.string.stats_book_remaining))
            ),
            colors = listOf(colorPrimary, colorLabel),
            centerText = if (totalSections == 0) "" else "${studiedSections * 100 / totalSections}%"
        )

        // ② 每本书进度（已学 / 未学 小节，分组柱状图）
        val shown = books.take(5)
        if (shown.isEmpty()) {
            binding.bookProgressChart.clear()
            binding.bookProgressChart.setNoDataText(getString(R.string.stats_book_empty))
            binding.bookProgressChart.invalidate()
        } else {
            val studiedGroup = shown.mapIndexed { index, book ->
                BarEntry(index.toFloat(), book.studiedCount.toFloat())
            }
            val remainingGroup = shown.mapIndexed { index, book ->
                BarEntry(index.toFloat(), (book.sectionCount - book.studiedCount).coerceAtLeast(0).toFloat())
            }
            styleGroupedBarChart(
                chart = binding.bookProgressChart,
                groupNames = listOf(
                    getString(R.string.stats_book_studied),
                    getString(R.string.stats_book_remaining)
                ),
                groups = listOf(studiedGroup, remainingGroup),
                colors = listOf(colorPrimary, colorLabel),
                labels = shown.map { shortBookName(it.title) }
            )
        }

        // ③ 文本汇总
        binding.bookSummary.text = if (books.isEmpty()) {
            getString(R.string.stats_book_empty)
        } else {
            buildString {
                append(
                    getString(
                        R.string.stats_book_summary,
                        books.size,
                        totalSections,
                        studiedSections,
                        if (totalSections == 0) 0 else studiedSections * 100 / totalSections
                    )
                )
                books.take(5).forEach { book ->
                    val percent = if (book.sectionCount == 0) 0
                    else book.studiedCount * 100 / book.sectionCount
                    append("\n")
                    append(
                        getString(
                            R.string.stats_book_list_line,
                            book.title,
                            book.studiedCount,
                            book.sectionCount,
                            percent
                        )
                    )
                    if (book.hasPdf) append(getString(R.string.stats_book_pdf_tag))
                }
            }
        }
    }

    private fun shortBookName(title: String): String =
        if (title.length <= 6) title else title.take(6) + "…"

    // ---------------- 模块三：错题学习 ----------------

    private fun renderMistakeModule() {
        val all = mistakeStore.all()
        val pending = all.count { !it.mastered }
        val mastered = all.count { it.mastered }

        // ① 近 14 天新增错题
        val days = lastDays(14)
        val perDay = days.associateWith { date ->
            all.count { dateFormat.format(Date(it.createdAt)) == date }
        }
        val barEntries = days.mapIndexed { index, date ->
            BarEntry(index.toFloat(), (perDay[date] ?: 0).toFloat())
        }
        styleBarChart(
            chart = binding.mistakeDailyChart,
            groups = emptyList(),
            colors = listOf(colorAccent),
            labels = days.map { it.substring(5) },
            singleEntries = barEntries
        )

        // ② 掌握情况饼图
        stylePieChart(
            chart = binding.mistakePie,
            entries = listOf(
                PieEntry(pending.toFloat(), getString(R.string.mistake_pending)),
                PieEntry(mastered.toFloat(), getString(R.string.mistake_mastered))
            ),
            colors = listOf(colorUnknown, colorKnown),
            centerText = if (all.isEmpty()) "" else "${mastered * 100 / all.size}%"
        )

        // ③ 学科分布（取前 6）
        val subjectCounts = all.groupingBy { it.subject.ifBlank { "其他" } }
            .eachCount().entries.sortedByDescending { it.value }.take(6)
        if (subjectCounts.isEmpty()) {
            binding.mistakeSubjectChart.clear()
            binding.mistakeSubjectChart.setNoDataText(getString(R.string.stats_mistake_empty))
            binding.mistakeSubjectChart.invalidate()
        } else {
            styleBarChart(
                chart = binding.mistakeSubjectChart,
                groups = emptyList(),
                colors = listOf(colorPrimary),
                labels = subjectCounts.map { it.key },
                singleEntries = subjectCounts.mapIndexed { index, entry ->
                    BarEntry(index.toFloat(), entry.value.toFloat())
                }
            )
        }

        // ④ 文本汇总
        val reviewed = all.filter { it.reviewCount > 0 }
        val averageReviews = if (reviewed.isEmpty()) 0f
        else reviewed.sumOf { it.reviewCount }.toFloat() / reviewed.size
        binding.mistakeSummary.text = if (all.isEmpty()) {
            getString(R.string.stats_mistake_empty)
        } else {
            buildString {
                append(
                    getString(
                        R.string.stats_mistake_summary,
                        all.size,
                        pending,
                        mastered,
                        averageReviews
                    )
                )
                append("\n")
                append(getString(R.string.stats_mistake_hint))
            }
        }
    }

    private fun lastDays(count: Int): List<String> {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -(count - 1))
        return (0 until count).map {
            val date = dateFormat.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            date
        }
    }

    // ---------------- 图表样式（统一跟随主题） ----------------

    private fun styleLineChart(chart: LineChart, entries: List<Entry>, labels: List<String>) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setPinchZoom(false)
        chart.setScaleEnabled(false)
        chart.setNoDataText(getString(R.string.stats_chart_no_data))
        chart.setDrawGridBackground(false)

        val set = LineDataSet(entries, "").apply {
            color = colorPrimary
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = colorPrimary
            fillAlpha = 60
        }
        chart.data = LineData(set)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            valueFormatter = IndexAxisValueFormatter(labels)
            textColor = colorLabel
            textSize = 9f
            labelCount = minOf(labels.size, 7)
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            granularity = 1f
            textColor = colorLabel
            textSize = 9f
            setDrawGridLines(true)
            gridColor = Color.argb(30, 128, 128, 128)
        }
        chart.axisRight.isEnabled = false
        chart.invalidate()
    }

    private fun stylePieChart(
        chart: PieChart,
        entries: List<PieEntry>,
        colors: List<Int>,
        centerText: String
    ) {
        chart.description.isEnabled = false
        chart.setNoDataText(getString(R.string.stats_chart_no_data))
        chart.setUsePercentValues(false)
        chart.setDrawEntryLabels(false)
        chart.setHoleColor(Color.TRANSPARENT)
        chart.setHoleRadius(52f)
        chart.setTransparentCircleRadius(56f)
        chart.setCenterText(centerText)
        chart.setCenterTextColor(colorLabel)
        chart.setCenterTextSize(13f)
        chart.legend.apply {
            isEnabled = true
            textColor = colorLabel
            textSize = 10f
            horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.CENTER
            verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
            form = com.github.mikephil.charting.components.Legend.LegendForm.CIRCLE
            formSize = 8f
        }

        val hasData = entries.any { it.value > 0f }
        if (!hasData) {
            chart.clear()
            chart.setNoDataText(getString(R.string.stats_chart_no_data))
            chart.invalidate()
            return
        }
        val set = PieDataSet(entries, "").apply {
            this.colors = colors
            sliceSpace = 2f
            valueTextColor = colorLabel
            valueTextSize = 10f
            setDrawValues(true)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    if (value <= 0f) "" else value.toInt().toString()
            }
        }
        chart.data = PieData(set)
        chart.invalidate()
    }

    /** 单组柱状图（[singleEntries] 直接给出柱子） */
    private fun styleBarChart(
        chart: BarChart,
        groups: List<Pair<String, List<Float>>>,
        colors: List<Int>,
        labels: List<String> = groups.map { it.first },
        valueSuffix: String = "",
        singleEntries: List<BarEntry>? = null
    ) {
        chart.description.isEnabled = false
        chart.setNoDataText(getString(R.string.stats_chart_no_data))
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(false)
        chart.setScaleEnabled(false)

        val sets = if (singleEntries != null) {
            listOf(
                BarDataSet(singleEntries, "").apply {
                    this.colors = colors
                    setDrawValues(true)
                    valueTextColor = colorLabel
                    valueTextSize = 9f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String =
                            if (value <= 0f) "" else value.toInt().toString() + valueSuffix
                    }
                }
            )
        } else {
            groups.mapIndexed { index, (name, values) ->
                val entries = values.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }
                BarDataSet(entries, name).apply {
                    color = colors.getOrElse(index) { colorPrimary }
                    setDrawValues(true)
                    valueTextColor = colorLabel
                    valueTextSize = 9f
                }
            }
        }

        chart.data = BarData(sets).apply { barWidth = 0.55f }
        if (sets.size > 1) {
            // 分组柱状图：显式设定 x 轴范围，避免最后一组被裁掉
            val barData = chart.data as BarData
            val groupCount = sets.maxOf { it.entryCount }.coerceAtLeast(1)
            barData.groupBars(0f, GROUP_SPACE, BAR_SPACE)
            chart.xAxis.axisMinimum = 0f
            chart.xAxis.axisMaximum = barData.getGroupWidth(GROUP_SPACE, BAR_SPACE) * groupCount
            chart.legend.apply {
                isEnabled = true
                textColor = colorLabel
                textSize = 10f
                horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.CENTER
                verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
                form = com.github.mikephil.charting.components.Legend.LegendForm.SQUARE
                formSize = 8f
            }
        } else {
            chart.legend.isEnabled = false
            chart.xAxis.axisMinimum = -0.5f
            chart.xAxis.axisMaximum = (labels.size - 1).coerceAtLeast(0) + 0.5f
        }

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            valueFormatter = IndexAxisValueFormatter(labels)
            textColor = colorLabel
            textSize = 9f
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            granularity = 1f
            textColor = colorLabel
            textSize = 9f
            gridColor = Color.argb(30, 128, 128, 128)
        }
        chart.axisRight.isEnabled = false
        chart.invalidate()
    }

    /** 分组柱状图（每本书：已学 / 未学） */
    private fun styleGroupedBarChart(
        chart: BarChart,
        groupNames: List<String>,
        groups: List<List<BarEntry>>,
        colors: List<Int>,
        labels: List<String>
    ) {
        val sets = groups.mapIndexed { index, entries ->
            BarDataSet(entries, groupNames.getOrElse(index) { "" }).apply {
                color = colors.getOrElse(index) { colorPrimary }
                setDrawValues(true)
                valueTextColor = colorLabel
                valueTextSize = 9f
            }
        }
        chart.description.isEnabled = false
        chart.setNoDataText(getString(R.string.stats_chart_no_data))
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(false)
        chart.setScaleEnabled(false)
        chart.data = BarData(sets).apply { barWidth = 0.4f }
        val barData = chart.data as BarData
        val groupCount = sets.maxOf { it.entryCount }.coerceAtLeast(1)
        barData.groupBars(0f, GROUP_SPACE, BAR_SPACE)
        chart.xAxis.axisMinimum = 0f
        chart.xAxis.axisMaximum = barData.getGroupWidth(GROUP_SPACE, BAR_SPACE) * groupCount
        chart.legend.apply {
            isEnabled = true
            textColor = colorLabel
            textSize = 10f
            horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.CENTER
            verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
            form = com.github.mikephil.charting.components.Legend.LegendForm.SQUARE
            formSize = 8f
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            valueFormatter = IndexAxisValueFormatter(labels)
            textColor = colorLabel
            textSize = 9f
            labelRotationAngle = -20f
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            granularity = 1f
            textColor = colorLabel
            textSize = 9f
            gridColor = Color.argb(30, 128, 128, 128)
        }
        chart.axisRight.isEnabled = false
        chart.invalidate()
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

        /** 分组柱状图的组间距 / 柱间距 */
        private const val GROUP_SPACE = 0.20f
        private const val BAR_SPACE = 0.04f
    }
}
