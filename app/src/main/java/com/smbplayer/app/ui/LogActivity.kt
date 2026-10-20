package com.smbplayer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.smbplayer.app.R
import com.smbplayer.app.databinding.ActivityLogBinding
import com.smbplayer.app.util.AppLog
import com.smbplayer.app.util.toast

/** 综合日志查看页：查看 / 复制 / 清除应用日志。 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.log_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnCopy.setOnClickListener {
            val text = AppLog.text()
            if (text.isBlank()) {
                toast(R.string.log_empty)
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("SmbPlayer Log", text))
            toast(R.string.log_copied)
        }

        binding.btnClear.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.log_clear)
                .setMessage(R.string.log_clear_confirm)
                .setPositiveButton(R.string.log_clear) { _, _ ->
                    AppLog.clear()
                    refresh()
                    toast(R.string.log_cleared)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val text = AppLog.text()
        binding.tvLog.text = text.ifBlank { getString(R.string.log_empty) }
        binding.scroll.post { binding.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
