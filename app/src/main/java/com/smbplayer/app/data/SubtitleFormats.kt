package com.smbplayer.app.data

/**
 * 字幕格式定义：自动识别为「字幕」的扩展名，以及查找同名字幕的优先级。
 *
 * 用于「自动加载视频同名字幕」：播放 `xxx.mp4` 时，在同级目录寻找
 * `xxx.srt` / `xxx.ass` 等同名文件，由播放页解析后叠加渲染。
 *
 * 只收内容明确、有可靠解析器的格式：`.sub` 实际常是 MicroDVD/idx+sub 二进制字幕、
 * `.txt` 内容不固定，硬按 SRT 解容易失败，不纳入自动匹配。
 */
object SubtitleFormats {

    /** 字幕扩展名（小写，无点） */
    private val SUBTITLE_EXTS: Set<String> = setOf(
        "srt", "ass", "ssa", "vtt", "ttml", "xml", "dfxp"
    )

    /** 查找字幕时的匹配优先级：靠前的先命中 */
    val PRIORITY: List<String> = listOf(
        "srt", "ass", "ssa", "vtt", "ttml", "xml", "dfxp"
    )

    fun isSubtitle(fileName: String): Boolean = VideoFormats.extOf(fileName) in SUBTITLE_EXTS

    /**
     * 在 [allNames]（同一目录的全部文件名）里找 [videoName] 的同名字幕。
     *
     * 命中规则（取第一条）：
     * 1. 严格同名：`<base>.<subExt>`，例如 `movie.mp4` -> `movie.srt`；
     * 2. 带语言/标签：`<base>.<tag>.<subExt>`，tag 长度 1..10，
     *    例如 `movie.zh.srt`、`movie.chi.srt`，同时避免误匹配
     *    `movie.trailer.srt` 这类（额外那段太长或基名对不上）。
     */
    fun findSubtitle(videoName: String, allNames: Set<String>): String? {
        val base = videoName.substringBeforeLast('.', missingDelimiterValue = "")
        if (base.isEmpty()) return null
        val baseLower = base.lowercase()

        val lowerByName = allNames.associateBy({ it.lowercase() }, { it })
        // 1. 严格同名：<base>.<subExt>
        for (ext in PRIORITY) {
            lowerByName["$baseLower.$ext"]?.let { return it }
        }

        // 2. 带语言/标签：<base>.<tag>.<subExt>
        for (name in allNames) {
            val lower = name.lowercase()
            val dotExt = lower.lastIndexOf('.')
            if (dotExt <= 0) continue
            val ext = lower.substring(dotExt + 1)
            if (ext !in SUBTITLE_EXTS) continue
            val core = lower.substring(0, dotExt) // 去掉 .<ext>
            val dotTag = core.indexOf('.')
            if (dotTag <= 0) continue
            val tag = core.substring(dotTag + 1)
            if (tag.length !in 1..10) continue
            if (core.substring(0, dotTag) == baseLower) return name
        }
        return null
    }
}
