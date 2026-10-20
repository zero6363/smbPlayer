package com.smbplayer.app.player

import java.nio.charset.Charset

/**
 * 一条字幕：起止时间 + 文本。
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * 字幕文件解析（SRT / VTT / ASS / SSA / TTML）。
 *
 * 为什么自己解析而不用 Media3 的字幕轨：外挂字幕走 SingleSampleMediaSource +
 * MergingMediaSource 在部分设备上会把整个播放搞挂（报错或一直缓冲），
 * 且 Media3 解析器只认 UTF-8，国内大量 GBK 字幕会变乱码。这里播前把字幕
 * 解析成 cue 列表，由播放页用 TextView 叠加渲染，视频轨保持最简、最稳。
 */
object SubtitleTextParser {

    /** 解析字幕文件；格式不支持或内容为空时返回空列表（调用方按无字幕处理） */
    fun parse(fileName: String, bytes: ByteArray): List<SubtitleCue> {
        if (bytes.isEmpty()) return emptyList()
        val text = decode(bytes)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val cues = when (ext) {
            "vtt" -> parseVtt(text)
            "ass", "ssa" -> parseAss(text)
            "ttml", "xml", "dfxp" -> parseTtml(text)
            else -> parseSrt(text)
        }
        return cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedBy { it.startMs }
    }

    // ---------------------------------------------------------------- 编码

    /**
     * 解码字幕字节：先按 BOM 判 UTF-8/UTF-16，再尝试严格 UTF-8，
     * 出现替换符则按 GBK 解（国内字幕大量是 GBK/ANSI 编码）。
     */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
    }

    // ---------------------------------------------------------------- SRT

    private fun parseSrt(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        for (block in splitBlocks(text)) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timeIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeIdx < 0) continue
            val (start, end) = parseTimeRange(lines[timeIdx]) ?: continue
            val body = lines.drop(timeIdx + 1)
                .joinToString("\n") { it.replaceBreaks() }
                .trim()
            if (body.isNotEmpty()) cues += SubtitleCue(start, end, body)
        }
        return cues
    }

    // ---------------------------------------------------------------- VTT

    private fun parseVtt(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        for (block in splitBlocks(text)) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val head = lines[0].trim()
            if (head.startsWith("WEBVTT") || head.startsWith("NOTE") ||
                head.startsWith("STYLE") || head.startsWith("REGION")
            ) {
                continue
            }
            val timeIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeIdx < 0) continue
            val (start, end) = parseTimeRange(lines[timeIdx]) ?: continue
            val body = lines.drop(timeIdx + 1)
                .joinToString("\n") { it.replaceBreaks() }
                .replace(Regex("<[^>]+>"), "") // 去 <c> / <v> 等标签
                .replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .trim()
            if (body.isNotEmpty()) cues += SubtitleCue(start, end, body)
        }
        return cues
    }

    // ---------------------------------------------------------------- ASS / SSA

    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var inEvents = false
        var fields: List<String> = emptyList()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue

            val body = line.substringAfter(':')
            val textIdx = fields.indexOf("text").let { if (it < 0) 9 else it }
            val startIdx = fields.indexOf("start").let { if (it < 0) 1 else it }
            val endIdx = fields.indexOf("end").let { if (it < 0) 2 else it }
            // 文本字段自身可能含逗号，只切到文本字段为止，剩下的整体算文本
            val parts = body.split(",", limit = textIdx + 1)
            if (parts.size <= textIdx) continue
            val start = parseAssTime(parts[startIdx]) ?: continue
            val end = parseAssTime(parts[endIdx]) ?: continue
            val clean = parts[textIdx]
                .replace(Regex("\\{[^}]*\\}"), "") // 去 {\pos} {\b1} 等覆盖标签
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                .trim()
            if (clean.isNotEmpty()) cues += SubtitleCue(start, end, clean)
        }
        return cues
    }

    private fun parseAssTime(raw: String): Long? {
        val m = ASS_TIME.find(raw.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: return null
        val min = m.groupValues[2].toLongOrNull() ?: return null
        val sec = m.groupValues[3].toLongOrNull() ?: return null
        // ASS 小数部分是厘秒（两位）；补足三位按毫秒算
        val frac = m.groupValues[4].padEnd(3, '0').toLongOrNull() ?: 0L
        return ((h * 60 + min) * 60 + sec) * 1000 + frac
    }

    // ---------------------------------------------------------------- TTML

    private fun parseTtml(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        for (m in P_TAG.findAll(text)) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
                .replaceBreaks()
                .trim()
            if (body.isEmpty()) continue
            val begin = Regex("begin\\s*=\\s*\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
            val end = Regex("end\\s*=\\s*\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
            val start = begin?.let { parseTtmlTime(it) } ?: continue
            val endMs = end?.let { parseTtmlTime(it) } ?: (start + 3_000)
            cues += SubtitleCue(start, endMs, body)
        }
        return cues
    }

    /** TTML 时间：支持 时:分:秒.毫秒 / 时:分:秒:帧 / 纯秒数 */
    private fun parseTtmlTime(raw: String): Long? {
        val s = raw.trim()
        s.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        val m = TTML_TIME.find(s) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: return null
        val min = m.groupValues[2].toLongOrNull() ?: return null
        val sec = m.groupValues[3].toLongOrNull() ?: return null
        val ms = m.groupValues[4].padEnd(3, '0').toLongOrNull() ?: 0L
        return ((h * 60 + min) * 60 + sec) * 1000 + ms
    }

    // ---------------------------------------------------------------- 公共

    private fun splitBlocks(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))

    /** 把 <br> / <br/> / <br /> 统一成换行 */
    private fun String.replaceBreaks(): String =
        replace(Regex("<\\s*br\\s*/?\\s*>", RegexOption.IGNORE_CASE), "\n")

    /** "00:00:01,000 --> 00:00:03,500  X1:..." -> (1000, 3500) */
    private fun parseTimeRange(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size < 2) return null
        val start = parseTimestamp(parts[0].trim().split(Regex("[ \t]")).firstOrNull() ?: return null)
        val end = parseTimestamp(parts[1].trim().split(Regex("[ \t]")).firstOrNull() ?: return null)
        if (start == null || end == null) return null
        return start to end
    }

    /** 支持 HH:mm:ss,mmm / HH:mm:ss.mmm / mm:ss,mmm */
    private fun parseTimestamp(raw: String): Long? {
        val s = raw.trim().replace('.', ',')
        var ms = 0L
        var head = s
        val sep = s.indexOf(',')
        if (sep >= 0) {
            ms = s.substring(sep + 1).take(3).padEnd(3, '0').toLongOrNull() ?: return null
            head = s.substring(0, sep)
        }
        val parts = head.split(":")
        val h: Long
        val m: Long
        val sec: Long
        when (parts.size) {
            3 -> {
                h = parts[0].toLongOrNull() ?: return null
                m = parts[1].toLongOrNull() ?: return null
                sec = parts[2].toLongOrNull() ?: return null
            }
            2 -> {
                h = 0
                m = parts[0].toLongOrNull() ?: return null
                sec = parts[1].toLongOrNull() ?: return null
            }
            else -> return null
        }
        return ((h * 60 + m) * 60 + sec) * 1000 + ms
    }

    private val ASS_TIME = Regex("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,3}))?$")
    private val TTML_TIME = Regex("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:[.,:](\\d{1,3}))?$")
    private val P_TAG = Regex("<p\\b([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
}
