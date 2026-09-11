package com.xueti.learn.util

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.xueti.learn.R
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.databinding.DialogCustomGoalBinding

/**
 * 每日练词数量编辑器：手输自定义数量（1-500），并提供常用数量快捷选择。
 * 设置页（默认每日目标）与学习日历（某天自定义数量）共用。
 */
object GoalCountEditor {

    /** 快捷数量 */
    val PRESETS = listOf(10, 20, 30, 50, 100)

    /**
     * @param initial 当前数量
     * @param allowZero 是否允许 0（0 = 休息日，仅日历按日设置时需要）
     */
    fun show(
        activity: AppCompatActivity,
        title: CharSequence,
        initial: Int,
        allowZero: Boolean = false,
        onSaved: (Int) -> Unit
    ) {
        val binding = DialogCustomGoalBinding.inflate(activity.layoutInflater)
        val min = if (allowZero) 0 else ProgressStore.MIN_GOAL
        val max = ProgressStore.MAX_GOAL

        binding.etGoalCount.setText(initial.toString())
        binding.etGoalCount.setSelection(binding.etGoalCount.text?.length ?: 0)
        binding.tvGoalCountTip.text =
            activity.getString(R.string.daily_goal_tip_range, min, max)

        val presets = if (allowZero) PRESETS + 0 else PRESETS
        presets.forEach { value ->
            val chip = Chip(activity).apply {
                text = if (value == 0) {
                    activity.getString(R.string.goal_rest_day)
                } else {
                    activity.getString(R.string.goal_words, value)
                }
                isCheckable = true
                isChecked = value == initial
                setOnClickListener { binding.etGoalCount.setText(value.toString()) }
            }
            binding.goalPresetGroup.addView(chip)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(R.string.goal_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = binding.etGoalCount.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value < min || value > max) {
                    binding.goalCountLayout.error =
                        activity.getString(R.string.daily_goal_tip_range, min, max)
                    return@setOnClickListener
                }
                binding.goalCountLayout.error = null
                onSaved(value)
                Toast.makeText(activity, R.string.stats_goal_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
