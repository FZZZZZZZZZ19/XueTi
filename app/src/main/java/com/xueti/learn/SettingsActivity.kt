package com.xueti.learn

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xueti.learn.base.BaseActivity
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.databinding.ActivitySettingsBinding
import com.xueti.learn.update.UpdateChecker
import com.xueti.learn.update.showUpdateDialog
import com.xueti.learn.util.BackgroundHelper
import com.xueti.learn.util.ReminderNotifier
import com.xueti.learn.util.ThemeStyle
import com.xueti.learn.work.ReminderScheduler
import kotlinx.coroutines.launch

/**
 * 设置中心：学习目标 / 每日提醒 / 外观（界面风格、自定义背景）/ 更新 / 数据 / 关于
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val store: ProgressStore get() = (application as App).progressStore
    private val settings: SettingsStore get() = (application as App).settings

    private var suppressReminderListener = false
    private var checkingDialog: AlertDialog? = null

    /** 选择自定义背景图（持久化读取权限，重启后仍可用） */
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            settings.backgroundUri = uri.toString()
            BackgroundHelper.invalidate()
            applyAppBackground(binding.root)
            updateBackgroundState()
        }

    /** Android 13+ 通知权限 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                enableReminder()
            } else {
                suppressReminderListener = true
                binding.swReminder.isChecked = false
                suppressReminderListener = false
                toast(R.string.notification_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupGoalSpinner()
        setupReminder()
        setupStylePicker()
        setupBackground()
        setupUpdate()

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

    // ---------------- 学习目标 ----------------

    private fun setupGoalSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            GOAL_OPTIONS.map { getString(R.string.goal_words, it) }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.goalSpinner.adapter = adapter

        val index = GOAL_OPTIONS.indexOf(store.dailyGoal).let { if (it < 0) 2 else it }
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

    // ---------------- 每日提醒 ----------------

    private fun setupReminder() {
        suppressReminderListener = true
        binding.swReminder.isChecked = settings.reminderEnabled
        suppressReminderListener = false

        binding.swReminder.setOnCheckedChangeListener { _, checked ->
            if (suppressReminderListener) return@setOnCheckedChangeListener
            if (checked) requestOrEnableReminder() else disableReminder()
        }

        updateReminderTimeText()
        binding.reminderTimeRow.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    settings.reminderHour = hour
                    settings.reminderMinute = minute
                    updateReminderTimeText()
                    if (settings.reminderEnabled) {
                        ReminderScheduler.schedule(this, hour, minute)
                        toast(getString(R.string.reminder_enabled_toast, formatTime(hour, minute)))
                    }
                },
                settings.reminderHour,
                settings.reminderMinute,
                true
            ).show()
        }

        // 立即发一条测试通知，用于确认通知权限/渠道是否正常
        binding.testReminderRow.setOnClickListener { testReminder() }
        // 国产 ROM 后台限制排障入口
        binding.reminderHelpRow.setOnClickListener { openReminderHelp() }
    }

    private fun testReminder() {
        if (ReminderNotifier.canNotify(this)) {
            ReminderNotifier.show(this, test = true)
            toast(R.string.test_reminder_sent)
            return
        }
        val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (permissionMissing) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toast(R.string.notifications_disabled)
        }
    }

    private fun openReminderHelp() {
        toast(R.string.reminder_help_toast)
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
        }
    }

    private fun requestOrEnableReminder() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enableReminder()
        }
    }

    private fun enableReminder() {
        settings.reminderEnabled = true
        suppressReminderListener = true
        binding.swReminder.isChecked = true
        suppressReminderListener = false
        ReminderScheduler.schedule(this, settings.reminderHour, settings.reminderMinute)
        updateReminderTimeText()
        toast(
            getString(
                R.string.reminder_enabled_toast,
                formatTime(settings.reminderHour, settings.reminderMinute)
            )
        )
    }

    private fun disableReminder() {
        settings.reminderEnabled = false
        ReminderScheduler.cancel(this)
    }

    private fun updateReminderTimeText() {
        binding.reminderTimeValue.text =
            formatTime(settings.reminderHour, settings.reminderMinute)
        binding.reminderNextText.text = getString(
            R.string.reminder_next,
            ReminderScheduler.nextTriggerText(settings.reminderHour, settings.reminderMinute)
        )
    }

    private fun formatTime(hour: Int, minute: Int): String =
        getString(R.string.reminder_time_value, hour, minute)

    // ---------------- 界面风格 ----------------

    private fun setupStylePicker() {
        val styles = listOf(
            binding.stylePurple to ThemeStyle.PURPLE,
            binding.styleBlue to ThemeStyle.BLUE,
            binding.styleGreen to ThemeStyle.GREEN,
            binding.styleOrange to ThemeStyle.ORANGE,
            binding.stylePink to ThemeStyle.PINK
        )
        styles.forEach { pair ->
            val card = pair.first
            val style = pair.second
            card.isChecked = settings.themeStyle == style.key
            card.setOnClickListener {
                if (settings.themeStyle != style.key) {
                    settings.themeStyle = style.key
                    recreate()
                }
            }
        }
    }

    // ---------------- 自定义背景 ----------------

    private fun setupBackground() {
        binding.sbBackgroundDim.progress = settings.backgroundDim
        binding.sbBackgroundDim.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    settings.backgroundDim = progress
                    applyAppBackground(binding.root)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
        binding.pickBackgroundRow.setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        binding.resetBackgroundRow.setOnClickListener {
            settings.backgroundUri = null
            settings.backgroundDim = 70
            binding.sbBackgroundDim.progress = 70
            BackgroundHelper.invalidate()
            applyAppBackground(binding.root)
            updateBackgroundState()
        }
        updateBackgroundState()
    }

    private fun updateBackgroundState() {
        binding.backgroundStateText.text = getString(
            if (settings.backgroundUri == null) R.string.background_default
            else R.string.background_custom_set
        )
    }

    // ---------------- 检查更新 ----------------

    private fun setupUpdate() {
        binding.swAutoUpdate.isChecked = settings.autoCheckUpdate
        binding.swAutoUpdate.setOnCheckedChangeListener { _, checked ->
            settings.autoCheckUpdate = checked
        }
        binding.currentVersionText.text =
            getString(R.string.current_version, UpdateChecker.currentVersion())
        binding.checkUpdateRow.setOnClickListener { checkUpdate(manual = true) }
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) {
            checkingDialog = AlertDialog.Builder(this)
                .setMessage(R.string.checking_update)
                .setCancelable(false)
                .create()
                .also { it.show() }
        }
        lifecycleScope.launch {
            val result = UpdateChecker.fetchLatest()
            checkingDialog?.dismiss()
            checkingDialog = null
            result.onSuccess { info ->
                if (UpdateChecker.isNewer(info.version, UpdateChecker.currentVersion())) {
                    showUpdateDialog(info)
                } else if (manual) {
                    toast(getString(R.string.already_latest, UpdateChecker.currentVersion()))
                }
            }.onFailure {
                if (manual) toast(R.string.check_update_failed)
            }
        }
    }

    // ---------------- 数据 ----------------

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_progress)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                store.resetAll()
                toast(R.string.reset_done)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val GOAL_OPTIONS = listOf(5, 10, 20, 30, 50)
    }
}
