package com.smbplayer.app.util

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Activity 是否还活着（未 finishing、未 destroyed）。
 *
 * SMB 操作都在 IO 线程跑。用户按返回或「关闭」离开页面时 lifecycleScope 取消，
 * 协程在 catch 里继续执行——此时再弹对话框会抛
 * BadTokenException（token is not valid; is your activity running?）直接闪退。
 * 异步回调里弹窗前必须先过这个闸。
 */
fun Activity.isAlive(): Boolean = !isFinishing && !isDestroyed

fun Context.toast(message: CharSequence) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun Context.toast(@StringRes resId: Int) {
    Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}

/** 12.3 MB 这种可读大小 */
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digit = (log10(bytes.toDouble()) / 3).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1000.0.pow(digit)
    return if (digit == 0) "%d B".format(bytes) else "%.1f %s".format(value, units[digit])
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

fun formatDate(millis: Long): String =
    if (millis <= 0) "" else dateFormat.format(Date(millis))

/** 毫秒 -> mm:ss 或 h:mm:ss */
fun formatDuration(ms: Long): String {
    if (ms < 0) return "--:--"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 过滤文件系统非法字符（\ / : * ? " < > | 和控制字符），超长截断。
 *
 * 用于导出帧、保存图片等写文件的场景：远程文件名可能带这些字符，
 * 直接拿去做 MediaStore.DISPLAY_NAME 会插入失败。
 */
fun sanitizeFileName(name: String, maxLength: Int = 80): String {
    val cleaned = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .trimEnd('.')
    return if (cleaned.length > maxLength) cleaned.take(maxLength).trim() else cleaned
}

/**
 * 拼接 SMB 路径，去掉多余分隔符。
 *
 * 约定**最后一段是文件名**：
 * - 目录段：trim 掉首尾空白和斜杠（多余的分隔符会让服务端找不到目录）
 * - 文件名段：只去掉首尾斜杠，**保留前后空白**——Linux/Samba 上文件名
 *   可以带前后空格，trim 掉会导致按路径打开/删除时报「文件不存在」。
 */
fun joinPath(vararg parts: String): String {
    val nonEmpty = parts.filter { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) return ""
    return nonEmpty
        .mapIndexed { i, raw ->
            if (i == nonEmpty.lastIndex) raw.trim('/') else raw.trim().trim('/')
        }
        .joinToString("/")
}

/** 取目录部分（不含最后一段文件名） */
fun parentPath(path: String): String {
    val clean = path.trim('/')
    val idx = clean.lastIndexOf('/')
    return if (idx <= 0) "" else clean.substring(0, idx)
}
