package com.xueti.learn.util

import android.app.DatePickerDialog
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.xueti.learn.R
import com.xueti.learn.data.ProgressStore
import java.util.Calendar
import java.util.Locale

/**
 * 目标编辑弹窗：选择目标日期 + 手写目标/激励语句。
 * 首页底部目标卡片与学习日历（点击某天）共用。
 */
object GoalEditor {

    /**
     * @param initialDate 初始目标日期（yyyy-MM-dd）
     * @param allowDateChange 是否允许在弹窗内改日期（首页可改；日历中已选好日期则固定）
     */
    fun show(
        activity: AppCompatActivity,
        store: ProgressStore,
        initialDate: String,
        allowDateChange: Boolean,
        onSaved: () -> Unit
    ) {
        val binding = activity.layoutInflater.inflate(R.layout.dialog_goal_edit, null)
        val dateView = binding.findViewById<TextView>(R.id.tvGoalDate)
        val dateHint = binding.findViewById<TextView>(R.id.tvGoalDateHint)
        val textInput = binding.findViewById<TextInputEditText>(R.id.etGoalText)

        var pickedDate = initialDate.ifBlank { store.today() }
        textInput.setText(store.goalText)
        renderDate(dateView, store, pickedDate)

        if (allowDateChange) {
            dateHint.visibility = View.VISIBLE
            dateView.setOnClickListener {
                val calendar = Calendar.getInstance()
                store.parse(pickedDate)?.let { calendar.time = it }
                DatePickerDialog(
                    activity,
                    { _, year, month, day ->
                        pickedDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                        renderDate(dateView, store, pickedDate)
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        } else {
            dateHint.visibility = View.GONE
            dateView.isClickable = false
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.goal_edit_title)
            .setView(binding)
            .setPositiveButton(R.string.goal_save, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.goal_clear, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = textInput.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(activity, R.string.goal_text_required, Toast.LENGTH_SHORT).show()
                    textInput.requestFocus()
                    return@setOnClickListener
                }
                store.goalDate = pickedDate
                store.goalText = text
                Toast.makeText(activity, R.string.goal_saved, Toast.LENGTH_SHORT).show()
                onSaved()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                store.clearGoal()
                Toast.makeText(activity, R.string.goal_cleared, Toast.LENGTH_SHORT).show()
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 日期行文案：日期 + 距离天数 */
    private fun renderDate(view: TextView, store: ProgressStore, date: String) {
        val days = store.daysUntil(date)
        val formatted = store.formatGoalDate(date)
        view.text = when {
            days > 0 -> view.context.getString(R.string.goal_date_with_days, formatted, days)
            days == 0 -> view.context.getString(R.string.goal_date_today, formatted)
            else -> view.context.getString(R.string.goal_date_passed, formatted, -days)
        }
    }
}
