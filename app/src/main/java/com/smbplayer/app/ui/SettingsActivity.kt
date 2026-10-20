package com.smbplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.app.R
import com.smbplayer.app.data.SettingsRepository
import com.smbplayer.app.data.SpeedOptions
import com.smbplayer.app.databinding.ActivitySettingsBinding
import com.smbplayer.app.util.Changelog
import com.smbplayer.app.util.toast

/**
 * 设置页：后台播放开关、长按临时倍速、更新日志。
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
