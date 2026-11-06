package com.smbplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.app.R
import com.smbplayer.app.data.SettingsRepository
import com.smbplayer.app.data.SpeedOptions
import com.smbplayer.app.databinding.ActivitySettingsBinding
import com.smbplayer.app.util.Changelog
import com.smbplayer.app.util.toast

/**
 * 设置页：首页播放记录（显示开关 / 显示条数）、后台播放开关、长按临时倍速、更新日志。
 *
 * 原本主页菜单里的「长按倍速」与「更新日志」入口都整合到这里。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 首页播放记录：显示开关
        binding.swHistoryVisible.isChecked = SettingsRepository.historyVisible
        binding.swHistoryVisible.setOnCheckedChangeListener { _, isChecked ->
            SettingsRepository.historyVisible = isChecked
        }

        // 首页播放记录：显示条数
        updateHistoryCountLabel()
        binding.rowHistoryCount.setOnClickListener { showHistoryCountDialog() }

        // 后台播放
        binding.swBackgroundPlay.isChecked = SettingsRepository.backgroundPlay
        binding.swBackgroundPlay.setOnCheckedChangeListener { _, isChecked ->
            SettingsRepository.backgroundPlay = isChecked
        }

        // 长按临时倍速
        updateLongPressSpeedLabel()
        binding.rowLongPressSpeed.setOnClickListener { showLongPressSpeedDialog() }

        // 更新日志
        binding.rowChangelog.setOnClickListener { showChangelog() }
    }

    private fun updateLongPressSpeedLabel() {
        binding.tvLongPressSpeedValue.text =
            getString(R.string.settings_speed_value, SettingsRepository.playbackSpeed)
    }

    private fun updateHistoryCountLabel() {
        binding.tvHistoryCountValue.text =
            getString(R.string.settings_history_count_value, SettingsRepository.historyCount)
    }

    /**
     * 首页播放记录条数：数字输入，默认 12 条，允许 1–100。
     * 只控制首页展示数量，底层播放进度记录照旧保留，不影响其它视频的续播。
     */
    private fun showHistoryCountDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(SettingsRepository.historyCount.toString())
            setSelection(text.length)
            hint = getString(R.string.settings_history_count_dialog_hint)
        }
        // 用带 padding 的容器包一层，避开已弃用的 setView(view, int, int, int, int)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_history_count)
            .setMessage(R.string.settings_history_count_hint)
            .setView(container)
            .setPositiveButton(R.string.server_save, null)
            .setNegativeButton(R.string.server_cancel, null)
            .create()

        dialog.setOnShowListener {
            // 自己接管确定按钮，输入不合法时不关闭对话框、直接标红
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null ||
                    value !in SettingsRepository.MIN_HISTORY_COUNT..SettingsRepository.MAX_HISTORY_COUNT
                ) {
                    input.error = getString(R.string.settings_history_count_invalid)
                    input.requestFocus()
                    return@setOnClickListener
                }
                SettingsRepository.historyCount = value
                updateHistoryCountLabel()
                toast(getString(R.string.settings_history_count_saved, value))
                dialog.dismiss()
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    /**
     * 长按屏幕时的临时倍速（区别于播放页常驻倍速）。
     * 选项与播放页倍速共用 [SpeedOptions]，选中即设置成功。
     */
    private fun showLongPressSpeedDialog() {
        val labels = SpeedOptions.VALUES
            .map { getString(R.string.settings_speed_value, it) }
            .toTypedArray()
        val checked = SpeedOptions.indexOf(SettingsRepository.playbackSpeed)
            .coerceAtLeast(SpeedOptions.DEFAULT_INDEX)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_speed_label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val speed = SpeedOptions.VALUES[which]
                SettingsRepository.playbackSpeed = speed
                updateLongPressSpeedLabel()
                toast(getString(R.string.settings_speed_value, speed))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    /** 更新日志（内容见 util/Changelog.kt，发版时同步维护） */
    private fun showChangelog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_changelog, null)
        com.smbplayer.app.databinding.DialogChangelogBinding.bind(view).tvText.text =
            Changelog.asText()
        val version = appVersion()
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (version.isEmpty()) {
                    getString(R.string.action_changelog)
                } else {
                    getString(R.string.changelog_title, version)
                }
            )
            .setView(view)
            .setPositiveButton(R.string.changelog_ok, null)
            .show()
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
