package com.smbplayer.app.util

import android.util.Log

private const val TAG = "SmbPlayer"

/** 写入 logcat + 应用内综合日志（日志页可查看/复制/清除） */
fun logError(message: String, e: Throwable? = null) {
    if (e != null) Log.e(TAG, message, e) else Log.e(TAG, message)
    AppLog.add("E", message, e)
}

fun logInfo(message: String) {
    Log.i(TAG, message)
    AppLog.add("I", message)
}

/**
 * 把异常及其 cause 链展开成多行文本。
 *
 * 连接失败的原因往往藏在 cause 里（例如 TransportException → SocketTimeoutException），
 * 只看最外层 message 通常不够。
 */
fun Throwable.describe(): String {
    val sb = StringBuilder()
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 8) {
        if (depth > 0) sb.append("\n↳ 由 ")
        sb.append(t.javaClass.simpleName)
        if (!t.message.isNullOrBlank()) sb.append("：").append(t.message)
        t = t.cause
        depth++
    }
    return sb.toString().ifBlank { toString() }
}
