package com.xueti.learn

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.databinding.ActivitySettingsBinding

/** 设置：每日学习目标、清空学习记录、关于 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val store: ProgressStore get() = (application as App).progressStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupGoalSpinner()

        binding.resetRow.setOnClickListener { confirmReset() }
        binding.aboutVersion.text = BuildConfig.VERSION_NAME
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupGoalSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            GOAL_OPTIONS.map { getString(R.string.goal_words, it) }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.goalSpinner.adapter = adapter

        val index = GOAL_OPTIONS.indexOf(store.dailyGoal).let { if (it < 0) 1 else it }
        binding.goalSpinner.setSelection(index)
        binding.goalSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    store.dailyGoal = GOAL_OPTIONS[position]
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_progress)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                store.resetAll()
                Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private val GOAL_OPTIONS = listOf(5, 10, 20, 30, 50)
    }
}
