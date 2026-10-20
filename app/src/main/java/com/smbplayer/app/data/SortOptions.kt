package com.smbplayer.app.data

import com.smbplayer.app.smb.SmbEntry

/**
 * 目录列表排序方式：按文件名 / 大小 / 创建时间，支持升序降序。
 *
 * 目录始终排在文件前面（文件浏览器的惯例），同一类里再按选定字段排序。
 */
object SortOptions {

    /** 按文件名 */
    const val FIELD_NAME = 0

    /** 按大小 */
    const val FIELD_SIZE = 1

    /** 按创建时间 */
    const val FIELD_CREATED = 2

    const val DEFAULT_FIELD = FIELD_NAME
    const val DEFAULT_ASCENDING = true

    /** 6 种组合，用于单选列表；索引即对话框里的选项顺序 */
    val LABELS: List<Pair<Int, Boolean>> = listOf(
        FIELD_NAME to true,
        FIELD_NAME to false,
        FIELD_SIZE to true,
        FIELD_SIZE to false,
        FIELD_CREATED to true,
        FIELD_CREATED to false,
    )

    fun indexOf(field: Int, ascending: Boolean): Int =
        LABELS.indexOfFirst { it.first == field && it.second == ascending }.coerceAtLeast(0)

    /** 按给定字段与方向排序（目录永远在最前） */
    fun sorted(entries: List<SmbEntry>, field: Int, ascending: Boolean): List<SmbEntry> {
        val dirsFirst = compareByDescending<SmbEntry> { it.isDirectory }
        val key: Comparator<SmbEntry> = when (field) {
            FIELD_SIZE -> compareBy { it.size }
            FIELD_CREATED -> compareBy { createdTimeOf(it) }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        // 字段相同时按文件名兜底，保证顺序稳定、不随服务端返回顺序抖动
        val comparator =
            if (ascending) dirsFirst.then(key).thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            else dirsFirst.thenDescending(key).thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        return entries.sortedWith(comparator)
    }

    /** 创建时间；服务端没给（0）就用最后修改时间 */
    private fun createdTimeOf(entry: SmbEntry): Long =
        entry.createdAt.takeIf { it > 0L } ?: entry.lastModified
}
