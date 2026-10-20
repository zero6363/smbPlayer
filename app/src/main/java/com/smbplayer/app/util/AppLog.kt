package com.smbplayer.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 综合日志：收集应用所有关键路径的日志，内存 + 文件双写。
 *
 * - 内存保留最近 [MAX_ENTRIES] 条，供日志页快速显示
 * - 文件追加写入 filesDir/app_log.txt，进程重启后仍可查看
 * - 提供 clear() / text() 给日志页的「清除」「复制」入口
 */
object AppLog {

    private const val MAX_ENTRIES = 2000
    private const val FILE_NAME = "app_log.txt"

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<String>()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        // 恢复上次的日志（最多读最后 200KB，避免启动变慢）
        runCatching {
            if (f.exists() && f.length() > 0) {
                val bytes = f.length()
                val skip = if (bytes > 200_000) bytes - 200_000 else 0L
                f.inputStream().use { input ->
                    input.skip(skip)
                    input.bufferedReader(Charsets.UTF_8).readLines().forEach { line ->
                        if (line.isNotBlank()) push(line)
                    }
                }
            }
        }
    }

    @Synchronized
    fun add(level: String, message: String, throwable: Throwable? = null) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable.describe().replace('\n', ' '))
            }
        }
        push(line)
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun text(): String = entries.joinToString("\n")

    @Synchronized
    fun clear() {
        entries.clear()
        runCatching { file?.delete() }
    }

    private fun push(line: String) {
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }
}
