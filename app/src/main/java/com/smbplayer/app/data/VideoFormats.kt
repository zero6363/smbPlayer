package com.smbplayer.app.data

/**
 * 常见视频容器后缀。
 *
 * 用于目录列表展示和播放列表筛选，是否真的能解码取决于播放器内核（Media3 不支持 wmv/rmvb 等）。
 */
object VideoFormats {

    val EXTENSIONS: Set<String> = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "flv", "f4v",
        "ts", "m2ts", "mts", "mpg", "mpeg", "mpe", "3gp", "3g2",
        "ogv", "divx", "vob", "rm", "rmvb", "wmv", "asf", "mxf", "mpv"
    )

    private val IMAGE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "avif"
    )

    fun isVideo(fileName: String): Boolean = extOf(fileName) in EXTENSIONS

    fun isImage(fileName: String): Boolean = extOf(fileName) in IMAGE_EXTENSIONS

    fun extOf(fileName: String): String {
        val idx = fileName.lastIndexOf('.')
        return if (idx < 0 || idx == fileName.length - 1) "" else fileName.substring(idx + 1).lowercase()
    }

    /** 目录内所有视频，按文件名排序 */
    fun sortNames(names: List<String>): List<String> =
        names.sortedWith(String.CASE_INSENSITIVE_ORDER)
}
