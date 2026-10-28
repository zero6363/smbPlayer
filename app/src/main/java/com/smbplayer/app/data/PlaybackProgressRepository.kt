package com.smbplayer.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条视频的播放记录。
 *
 * [positionMs] 是上次离开时的位置，[durationMs] 是当时的视频总时长。
 * 看完的视频在保存时会把位置归一为总时长，播放列表里的进度线因此会走到底。
 */
data class PlaybackProgress(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    companion object {
        /** 列表细线的最低展示比例：不足 5% 的短进度也按 5% 画，保证看得见 */
        private const val MIN_DISPLAY_FRACTION = 0.05f
    }

    /** 0f..1f，播放列表细进度线的比例 */
    val fraction: Float
        get() = if (durationMs <= 0L) {
            0f
        } else {
            (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        }

    /**
     * 列表里细线实际展示的比例。
     *
     * 只看了几秒的视频，真实比例可能不到 1%，线短到几乎看不见；这里给一个
     * 最低展示比例（5%），让人一眼能看出「这个视频看过」。实际续播位置
     * 仍然用 [positionMs]，不受这个展示下限影响。
     */
    val displayFraction: Float
        get() = when {
            durationMs <= 0L || positionMs <= 0L -> 0f
            completed -> 1f
            else -> fraction.coerceAtLeast(MIN_DISPLAY_FRACTION)
        }

    /** 已看完（保存时位置已归一为总时长） */
    val completed: Boolean
        get() = durationMs > 0L && positionMs >= durationMs

    /** 下次打开应从哪开始：看完的从头重播，其余从上次位置继续 */
    val resumePositionMs: Long
        get() = if (completed) 0L else positionMs.coerceAtLeast(0L)
}

/**
 * 播放记录的本地持久化（普通 SharedPreferences：只存位置，不含敏感信息）。
 *
 * 记录按「服务器 ID + 共享名 + 文件路径」唯一标识，与目录浏览、排序方式无关。
 */
object PlaybackProgressRepository {

    private const val PREFS_NAME = "smb_playback_progress"
    private const val KEY_LIST = "progress"

    /** 最多保留的记录数，超出后按最近更新时间淘汰最旧的 */
    private const val MAX_RECORDS = 500

    /** 播放到剩余 2% 或最后 10 秒即视为看完 */
    private const val COMPLETE_RATIO = 0.98
    private const val COMPLETE_TAIL_MS = 10_000L

    /** 复合 key 的分隔符：NUL 不可能出现在服务器 ID / 共享名 / 路径里 */
    private const val KEY_SEPARATOR = '\u0000'

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 内存缓存：避免播放列表每次绑定都重新解析整份 JSON */
    private var cache: MutableMap<String, PlaybackProgress>? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun store(): SharedPreferences =
        prefs ?: throw IllegalStateException("PlaybackProgressRepository.init() 未调用")

    private fun key(serverId: String, shareName: String, path: String): String =
        serverId + KEY_SEPARATOR + shareName + KEY_SEPARATOR + path

    fun get(serverId: String, shareName: String, path: String): PlaybackProgress? =
        synchronized(lock) { records()[key(serverId, shareName, path)] }

    /** 一次性取出一批路径的记录，供播放列表批量展示 */
    fun getAll(
        serverId: String,
        shareName: String,
        paths: Collection<String>,
    ): Map<String, PlaybackProgress> = synchronized(lock) {
        val records = records()
        buildMap {
            paths.forEach { path ->
                records[key(serverId, shareName, path)]?.let { put(path, it) }
            }
        }
    }

    /**
     * 保存一条播放记录。
     *
     * - 视频看完（剩余 2% 或最后 10 秒）时位置记为总时长，进度线走到底；
     * - 刚点开还没播（位置 0）不新建记录；看了几秒也照常记录；
     * - 已看完的视频再次播放时，和普通视频一样按当前位置更新记录，
     *   列表进度线会随这次播放收缩/前进（不会一直停在满格）。
     *
     * [explicit] 表示这个位置是用户主动拖动进度条 / 快进快退选出来的。
     */
    fun save(
        serverId: String,
        shareName: String,
        path: String,
        positionMs: Long,
        durationMs: Long,
        explicit: Boolean = false,
    ) {
        if (serverId.isEmpty() || path.isEmpty() || durationMs <= 0L || positionMs < 0L) return
        synchronized(lock) {
            val records = records()
            val k = key(serverId, shareName, path)
            // 短于 10 秒的视频只看比例，避免「剩余 10 秒」把片头也算成结尾
            val nearEnd = durationMs > COMPLETE_TAIL_MS &&
                positionMs >= durationMs - COMPLETE_TAIL_MS
            val completed = nearEnd ||
                positionMs.toDouble() >= durationMs * COMPLETE_RATIO

            if (!completed && positionMs <= 0L && !explicit) return

            records[k] = PlaybackProgress(
                positionMs = if (completed) durationMs else positionMs.coerceAtMost(durationMs),
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            )
            pruneAndWrite(records)
        }
    }

    /** 删除一条记录；[includeChildren] 为 true 时同时清掉该目录下所有视频的记录 */
    fun remove(
        serverId: String,
        shareName: String,
        path: String,
        includeChildren: Boolean = false,
    ) {
        synchronized(lock) {
            val records = records()
            val exact = key(serverId, shareName, path)
            val targets = if (includeChildren) {
                records.keys.filter { it == exact || it.startsWith("$exact/") }
            } else {
                listOf(exact).filter { records.containsKey(it) }
            }
            if (targets.isEmpty()) return
            targets.forEach { records.remove(it) }
            write(records)
        }
    }

    /** 读取整份记录（首次调用时从 SharedPreferences 解析） */
    private fun records(): MutableMap<String, PlaybackProgress> {
        cache?.let { return it }
        val map = mutableMapOf<String, PlaybackProgress>()
        val raw = store().getString(KEY_LIST, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val k = o.optString("key")
                    val duration = o.optLong("duration", 0L)
                    if (k.isEmpty() || duration <= 0L) continue
                    map[k] = PlaybackProgress(
                        positionMs = o.optLong("position", 0L).coerceIn(0L, duration),
                        durationMs = duration,
                        updatedAt = o.optLong("updatedAt", 0L),
                    )
                }
            } catch (_: Throwable) {
                // 数据损坏时按无记录处理，不影响播放
            }
        }
        cache = map
        return map
    }

    private fun pruneAndWrite(records: MutableMap<String, PlaybackProgress>) {
        if (records.size > MAX_RECORDS) {
            records.entries
                .sortedBy { it.value.updatedAt }
                .take(records.size - MAX_RECORDS)
                .forEach { records.remove(it.key) }
        }
        write(records)
    }

    private fun write(records: Map<String, PlaybackProgress>) {
        val arr = JSONArray()
        records.forEach { (k, progress) ->
            arr.put(JSONObject().apply {
                put("key", k)
                put("position", progress.positionMs)
                put("duration", progress.durationMs)
                put("updatedAt", progress.updatedAt)
            })
        }
        store().edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
