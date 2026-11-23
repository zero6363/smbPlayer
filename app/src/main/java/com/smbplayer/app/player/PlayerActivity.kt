package com.smbplayer.app.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.app.R
import com.smbplayer.app.data.PlaybackProgressRepository
import com.smbplayer.app.data.ServerRepository
import com.smbplayer.app.data.SettingsRepository
import com.smbplayer.app.data.SmbServer
import com.smbplayer.app.data.SpeedOptions
import com.smbplayer.app.data.SubtitleFormats
import com.smbplayer.app.data.VideoFormats
import com.smbplayer.app.databinding.ActivityPlayerBinding
import com.smbplayer.app.databinding.DialogPlaylistBinding
import com.smbplayer.app.smb.SmbGateway
import com.smbplayer.app.smb.SmbReadHandle
import com.smbplayer.app.smb.SmbSession
import com.smbplayer.app.ui.PlaylistAdapter
import com.smbplayer.app.ui.PlaylistItem
import com.smbplayer.app.util.formatDuration
import com.smbplayer.app.util.formatSize
import com.smbplayer.app.util.isAlive
import com.smbplayer.app.util.joinPath
import com.smbplayer.app.util.logError
import com.smbplayer.app.util.logInfo
import com.smbplayer.app.util.sanitizeFileName
import com.smbplayer.app.util.toast
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 播放页：播放 [directory] 目录下的所有视频。
 *
 * 支持删除当前视频（删除后自动跳到下一个）、分类归档（移动到 1 / 2 / 3 文件夹后接着播下一个）、
 * 上一个/下一个、播放列表。
 */
@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var server: SmbServer
    private lateinit var session: SmbSession

    private var shareName: String = ""
    private var directory: String = ""

    /** 当前目录下的全部视频文件名（已排序） */
    private val videos = mutableListOf<String>()
    private var index: Int = 0

    /** 从首页播放记录进入时要定位的文件名；只在首次加载播放列表时用一次 */
    private var targetName: String? = null

    /** 当前播放器里媒体的 SMB 路径：切集/删文件时列表索引会变，保存记录只认它 */
    private var currentVideoPath: String? = null

    /** 上次写入播放记录的位置，用于节流（每 5 秒最多写一次） */
    private var lastSavedProgressMs = -1L

    /**
     * 本次播放中用户是否主动调整过位置（拖进度条 / 快进快退 / 滑动进度）。
     * 用户明确选过的位置要以用户为准，不受「片头 10 秒不记录」「看完视频前 30 秒不覆盖」限制。
     */
    private var userAdjustedPosition = false

    /** 当前目录下的全部非目录文件名（用于在同级目录里找同名字幕） */
    private var allFileNames: Set<String> = emptySet()

    /** 当前视频的同名字幕（播前解析成 cue 列表，由叠加层渲染） */
    private var subtitleCues: List<SubtitleCue> = emptyList()

    /** 字幕叠加层刷新（100ms 一帧，cue 切换更及时） */
    private val subtitleTickRunnable = object : Runnable {
        override fun run() {
            updateSubtitleOverlay()
            mainHandler.postDelayed(this, SUBTITLE_TICK_MS)
        }
    }

    private var player: ExoPlayer? = null
    private var userSeeking = false
    private var playlistDialog: android.app.Dialog? = null

    /** 正在把当前视频移到分类文件夹：期间按钮置灰，避免连点重复移动 */
    private var moveInProgress = false

    /** 播放页常驻倍速（区别于长按临时倍速），取自设置，切到下一集时继承 */
    private var normalSpeed = SettingsRepository.normalSpeed
    private var isLongPressBoost = false

    /** 是否启用「左右分区纵滑调音量/亮度」，进入播放页时读一次设置 */
    private var zoneVolumeBrightnessEnabled = false

    /** 播放页手势：横滑进度，左侧纵滑亮度，右侧纵滑音量。 */
    private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    /**
     * 滑动调进度采用非线性映射，目标始终以按下点为基准计算。
     * 拖动过程中只预览进度，松手后才执行一次 seek，避免 SMB 网络流被连续定位请求拖慢。
     */
    private var gestureMode = GestureMode.NONE
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartPosition = 0L
    private var swipeTargetPosition = 0L
    private var swipeStartBrightness = 0.5f
    private var swipeStartVolume = 0

    /** 一次触摸发生过拖动或长按后，不再触发单击控制条。 */
    private var suppressTap = false

    /** 手势激活的最小位移，且主轴位移必须大于另一轴。 */
    private val swipeActivateDistancePx by lazy { 24f * resources.displayMetrics.density }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 底部操作提示与居中手势反馈自动隐藏。 */
    private val hideHintRunnable = Runnable { binding.tvHint.visibility = View.GONE }
    private val hideGestureFeedbackRunnable = Runnable { binding.gestureFeedback.visibility = View.GONE }

    /** 底部显示一条操作提示（不挡画面），稍后自动隐藏 */
    private fun showHint(text: String) {
        binding.tvHint.text = text
        binding.tvHint.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHintRunnable)
        mainHandler.postDelayed(hideHintRunnable, HINT_HIDE_DELAY_MS)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            // 播放中每 5 秒落一次播放记录（saveCurrentProgress 内部节流）
            saveCurrentProgress()
            // 横滑调进度期间不自动刷新：实际位置尚未改变，避免覆盖手势预览目标。
            if (gestureMode != GestureMode.SEEK) updateProgress()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val hideControlsRunnable = Runnable {
        if (player?.isPlaying == true && !userSeeking && gestureMode == GestureMode.NONE && !isFinishing) {
            hideControls()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.progress.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_ENDED) {
                // 播放到结尾：先把当前视频记成「已看完」，进度线才会走到底
                markCurrentCompleted()
                playNext(fromUser = false)
            }
            updateControls()
            if (playbackState == Player.STATE_READY) scheduleHideControls()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateControls()
            if (isPlaying) scheduleHideControls()
        }

        override fun onPlayerError(error: PlaybackException) {
            binding.progress.visibility = View.GONE
            showPlaybackError(error)
        }
    }

    // 必须 lazy：GestureDetector 构造时会走 context.getResources()，
    // 而字段初始化器在 Activity 构造函数中执行，早于 attach()，mBase 还是 null，会直接 NPE。
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (gestureMode == GestureMode.NONE) startSpeedBoost()
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (gestureMode != GestureMode.NONE || isLongPressBoost) return false
                val width = binding.tapArea.width.toFloat().takeIf { it > 0f }
                    ?: resources.displayMetrics.widthPixels.toFloat()
                when {
                    e.x < width * DOUBLE_TAP_SIDE_FRACTION -> {
                        seekBy(-DOUBLE_TAP_SEEK_MS, fromGesture = true)
                    }
                    e.x > width * (1f - DOUBLE_TAP_SIDE_FRACTION) -> {
                        seekBy(DOUBLE_TAP_SEEK_MS, fromGesture = true)
                    }
                    else -> togglePlayPause()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 等双击判定结束再切换控制条，避免双击时控制条先闪一下。
                if (suppressTap || gestureMode != GestureMode.NONE || isLongPressBoost) return false
                if (binding.topBar.visibility == View.VISIBLE) hideControls() else showControls()
                return true
            }
        })
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logInfo("PlayerActivity onCreate")
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        volumeControlStream = AudioManager.STREAM_MUSIC
        zoneVolumeBrightnessEnabled = SettingsRepository.zoneVolumeBrightness

        setupPlayer()
        setupControls()

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val loaded = serverId?.let { ServerRepository.get(it) }
        if (loaded == null) {
            logError("服务器不存在: $serverId")
            finish()
            return
        }
        server = loaded
        session = SmbGateway.session(server)
        shareName = intent.getStringExtra(EXTRA_SHARE).orEmpty()
        directory = intent.getStringExtra(EXTRA_DIR).orEmpty()
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        targetName = intent.getStringExtra(EXTRA_TARGET_NAME)?.takeIf { it.isNotEmpty() }

        logInfo("播放: 服务器=${server.host} 共享=$shareName 目录=$directory 索引=$index")

        if (shareName.isBlank()) {
            logError("共享名为空")
            toast(R.string.player_no_video)
            finish()
            return
        }
        loadPlaylist()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        // 有字幕就恢复叠加层刷新（后台播放时也保持，暂停状态下 cue 不变，开销可忽略）
        if (subtitleCues.isNotEmpty()) {
            mainHandler.removeCallbacks(subtitleTickRunnable)
            mainHandler.post(subtitleTickRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        // 切后台 / 息屏前落一次记录，避免进程被回收时丢掉最后一段进度
        saveCurrentProgress(force = true)
    }

    override fun onStop() {
        super.onStop()
        saveCurrentProgress(force = true)
        // 关闭后台播放时，离开播放页（息屏 / 切后台）就暂停，避免后台出声
        if (!SettingsRepository.backgroundPlay) {
            player?.pause()
        }
        if (isLongPressBoost) stopSpeedBoost()
        finishGesture(cancelled = true)
        binding.gestureFeedback.visibility = View.GONE
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.removeCallbacks(subtitleTickRunnable)
    }

    override fun onDestroy() {
        saveCurrentProgress(force = true)
        mainHandler.removeCallbacksAndMessages(null)
        player?.let { p ->
            p.removeListener(playerListener)
            p.release()
        }
        player = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 删除当前视频

    private fun confirmDeleteCurrent() {
        val name = videos.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_file_message, name))
            .setPositiveButton(R.string.delete_confirm) { _, _ -> deleteCurrent(name) }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    /** 删除当前视频后自动跳到下一个；没有下一个则退出播放页 */
    private fun deleteCurrent(name: String) {
        val path = pathOf(name)
        val resumePosition = player?.currentPosition ?: 0L
        // 先停止播放、释放 SMB 读句柄：句柄还开着时删除只是「删除挂起」，
        // 文件仍占着目录，之后连整个文件夹都会删不掉（STATUS_DIRECTORY_NOT_EMPTY）
        player?.stop()
        lifecycleScope.launch {
            try {
                logInfo("删除视频: $path")
                // SmbSession.delete 内部已带「占用重试 + 兼容模式删除」，
                // 句柄释放慢导致的 STATUS_SHARING_VIOLATION 会自动重删
                withContext(Dispatchers.IO) { session.delete(shareName, path, false) }
                // 文件已经不存在，对应的播放记录一并清掉
                PlaybackProgressRepository.remove(server.id, shareName, path)
                if (currentVideoPath == path) currentVideoPath = null
                toast(getString(R.string.deleted, name))
                videos.removeAt(index)
                if (videos.isEmpty()) {
                    finish()
                    return@launch
                }
                index = index.coerceIn(0, videos.lastIndex)
                playAt(index)
            } catch (e: CancellationException) {
                throw e // 用户离开播放页：正常取消，别在原位置续播（player 可能已释放）
            } catch (e: Exception) {
                logError("删除视频失败: $path", e)
                toast(getString(R.string.player_delete_failed, e.message ?: e.toString()))
                // 删除失败，从原位置继续播放
                playAt(index, startPositionMs = resumePosition)
            }
        }
    }

    // ------------------------------------------------------------------ 分类归档（1 / 2 / 3）

    /**
     * 把当前视频归档到当前目录下的 [folder]（1 / 2 / 3）：文件夹不存在就在服务端新建，
     * 移动完成后按**原有播放顺序**接着播下一个视频。
     *
     * 移动是服务端 rename，不搬数据（几个 G 也是瞬间完成）。先停播释放 SMB 读句柄，
     * 否则服务端会以「文件被占用」拒绝改名；占用重试在 [SmbSession.moveFile] 里。
     */
    private fun moveCurrentTo(folder: String) {
        if (moveInProgress) return
        val name = videos.getOrNull(index) ?: return
        // 已经在同名文件夹里（比如在「1」里又点 1）：不建 1/1，也不切集
        if (directory.trim('/').substringAfterLast('/') == folder) {
            showHint(getString(R.string.player_already_in_folder, folder))
            return
        }
        val srcPath = pathOf(name)
        val destDir = joinPath(directory, folder)
        val p = player
        // 停播前先取位置：播放记录要跟着文件一起搬到新路径
        val position = p?.currentPosition ?: 0L
        val duration = p?.duration ?: 0L

        moveInProgress = true
        updateControls()
        p?.stop()
        // 当前播放路径作废，避免 playAt 里的强制落盘把旧路径又写回记录
        currentVideoPath = null

        lifecycleScope.launch {
            try {
                val movedPath = withContext(Dispatchers.IO) {
                    session.moveFile(shareName, srcPath, joinPath(destDir, name))
                }
                logInfo("已归档到「$folder」: $name -> $movedPath")

                // 播放记录跟着文件搬家：旧路径的记录作废，新路径沿用当前位置
                PlaybackProgressRepository.remove(server.id, shareName, srcPath)
                if (duration > 0L && position > 0L) {
                    PlaybackProgressRepository.save(server.id, shareName, movedPath, position, duration)
                }
                allFileNames = allFileNames - name

                // 从播放列表里摘掉当前视频：后一个自动顶到同一位置，顺序不变
                val removedAt = videos.indexOf(name)
                if (removedAt >= 0) videos.removeAt(removedAt)
                if (videos.isEmpty() || removedAt < 0 || removedAt > videos.lastIndex) {
                    // 刚移动的就是最后一个：没有下一个可播
                    toast(getString(R.string.player_moved_last, folder))
                    finish()
                    return@launch
                }
                toast(getString(R.string.player_moved, folder, name))
                playAt(removedAt)
            } catch (e: CancellationException) {
                throw e // 用户离开播放页：正常取消
            } catch (e: Exception) {
                logError("移动到「$folder」失败: $srcPath", e)
                toast(getString(R.string.player_move_failed, e.message ?: e.toString()))
                // 移动失败：从原位置继续播当前视频
                playAt(index, startPositionMs = position)
            } finally {
                moveInProgress = false
                if (!isFinishing) updateControls()
            }
        }
    }

    // ------------------------------------------------------------------ 播放列表

    private fun loadPlaylist() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                logInfo("加载播放列表: 共享=$shareName 目录=$directory")
                val entries = withContext(Dispatchers.IO) {
                    session.list(shareName, directory)
                }
                // 记下同级目录里全部非目录文件名，供「同名字幕」查找（不必再为字幕多发一次列举请求）
                allFileNames = entries
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .toSet()
                val list = entries
                    .filter { !it.isDirectory && VideoFormats.isVideo(it.name) }
                    .map { it.name }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    .toList()
                videos.clear()
                videos.addAll(list)
                if (videos.isEmpty()) {
                    binding.progress.visibility = View.GONE
                    logError("播放列表为空: 共享=$shareName 目录=$directory")
                    toast(R.string.player_no_video)
                    finish()
                    return@launch
                }
                index = index.coerceIn(0, videos.lastIndex)
                // 从首页播放记录进来：按文件名找到对应的一集，点卡片就能直接续播。
                // 文件已被删/改名时留在本目录，从第一个视频开始，不让点击落空。
                val target = targetName
                targetName = null
                if (target != null) {
                    val found = videos.indexOfFirst { it.equals(target, ignoreCase = true) }
                    if (found >= 0) {
                        index = found
                    } else {
                        toast(getString(R.string.history_target_missing, target))
                    }
                }
                logInfo("播放列表加载完成: ${list.size} 个视频")
                playAt(index)
            } catch (e: CancellationException) {
                throw e // 用户离开播放页：正常取消
            } catch (e: Exception) {
                binding.progress.visibility = View.GONE
                logError("加载播放列表失败: 共享=$shareName 目录=$directory", e)
                toast(getString(R.string.player_error, e.message ?: e.toString()))
                finish()
            }
        }
    }

    private fun pathOf(name: String) = joinPath(directory, name)

    /**
     * 仅用于展示的 SMB URI。路径段按 URI 规则转义（`|`、`#`、`?`、空格、中文等），
     * 否则 Uri 里出现非法字符会让后续解析/展示出错。真正的读写用 [pathOf] 的原始路径。
     */
    private fun displayUri(name: String): Uri {
        val encodedPath = pathOf(name)
            .split("/")
            .filter { it.isNotEmpty() }
            .joinToString("/") { Uri.encode(it) }
        return Uri.parse("smb://${server.host}/$shareName/$encodedPath")
    }

    /**
     * 顶部栏文字：标题恒为「当前播放的视频文件名」，副标题为「集数 / 总数 + 目录」。
     *
     * 全页只有这一处改标题，切集 / 删集 / 控制栏重新出现都会按 [videos] + [index] 重算，
     * 保证标题永远跟着当前视频走（曾经出现过切集后标题停在上一集的情况）。
     */
    private fun updateHeader() {
        val name = videos.getOrNull(index) ?: return
        binding.tvTitle.text = name
        binding.tvSubtitle.text = getString(
            R.string.player_title_format,
            index + 1,
            videos.size,
            if (directory.isEmpty()) "/" else directory
        )
    }

    private fun playAt(newIndex: Int, startPositionMs: Long? = null) {
        if (newIndex !in videos.indices) {
            logError("播放索引越界: $newIndex, 列表大小=${videos.size}")
            return
        }
        // 切走之前先把当前视频的播放进度记下来
        saveCurrentProgress(force = true)
        currentVideoPath = null
        userAdjustedPosition = false

        index = newIndex
        val name = videos[newIndex]
        // 没指定起点时接着上次的播放记录：看完的视频从头重播
        val resumePosition = startPositionMs
            ?: PlaybackProgressRepository.get(server.id, shareName, pathOf(name))?.resumePositionMs
            ?: 0L
        lastSavedProgressMs = resumePosition
        logInfo("开始播放: $name 位置=${resumePosition}ms")

        // 换视频：作换上一条字幕的刷新
        mainHandler.removeCallbacks(subtitleTickRunnable)

        // 先同步更新标题/进度等 UI；字幕的 SMB 读取放 IO 线程，避免卡主线程
        updateHeader()
        binding.tvError.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        updateControls()
        showControls()

        lifecycleScope.launch {
            try {
                // 自动加载视频同名字幕：在同级目录找同名（或带语言标签）的 .srt/.ass 等文件，
                // 播前在 IO 线程整体读入内存并解析成 cue 列表，由播放页叠加层渲染。
                // 字幕只做「锦上花」：读取/解析失败都按无字幕播放，绝不影响视频本身。
                var subtitleError: String? = null
                val subtitleName = SubtitleFormats.findSubtitle(name, allFileNames)
                val cues = if (subtitleName != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val bytes = readSubtitleBytes(subtitleName)
                            SubtitleTextParser.parse(subtitleName, bytes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logError("字幕加载失败，按无字幕播放: $subtitleName", e)
                            subtitleError = subtitleName
                            emptyList()
                        }
                    }
                } else {
                    emptyList()
                }

                val smbUri = displayUri(name)
                val factory = SmbDataSource.Factory(session, shareName, pathOf(name), smbUri)
                // TS/M2TS/MTS 的 SPS 常缺色彩范围标注，按 full range 渲染会发灰，
                // 用带兜底的 extractor 工厂补 limited；其它容器用默认工厂。
                val isTs = VideoFormats.extOf(name) in setOf("ts", "m2ts", "mts")
                val extractorsFactory = if (isTs) TsColorAwareExtractorsFactory() else DefaultExtractorsFactory()
                val videoSource = ProgressiveMediaSource.Factory(factory, extractorsFactory)
                    .createMediaSource(MediaItem.fromUri(smbUri))

                // 期间用户可能又切了别的视频：被取代的这次不再动手
                if (index != newIndex) return@launch

                val p = player ?: return@launch
                currentVideoPath = pathOf(name)
                lastSavedProgressMs = resumePosition
                p.setMediaSource(videoSource, resumePosition)
                p.prepare()
                p.playWhenReady = true
                if (resumePosition > 0L) {
                    showHint(getString(R.string.player_resume_from, formatDuration(resumePosition)))
                }

                // 应用播放页常驻倍速（每次切集都继承，保证「上一集的速度带到下一集」）
                normalSpeed = SettingsRepository.normalSpeed
                p.playbackParameters = PlaybackParameters(normalSpeed)
                updateSpeedButton()

                // 字幕：解析成功就挂上叠加层；失败只提示，视频照常播
                subtitleCues = cues
                val loadedSubtitle = if (subtitleName != null && cues.isNotEmpty()) subtitleName else null
                mainHandler.removeCallbacks(subtitleTickRunnable)
                if (loadedSubtitle != null) {
                    logInfo("已加载同名字幕: $loadedSubtitle（${cues.size} 条）")
                    showHint(getString(R.string.subtitle_loaded, loadedSubtitle))
                    updateSubtitleOverlay()
                    mainHandler.post(subtitleTickRunnable)
                } else {
                    binding.tvSubtitleOverlay.visibility = View.GONE
                }
                if (subtitleError != null) {
                    toast(getString(R.string.subtitle_load_failed, subtitleError))
                }
            } catch (e: CancellationException) {
                throw e // 用户离开播放页：正常取消
            } catch (e: Exception) {
                logError("播放失败: $name", e)
                if (index == newIndex) {
                    binding.progress.visibility = View.GONE
                    toast(getString(R.string.player_error, e.message ?: e.toString()))
                }
            }
        }
    }

    // ------------------------------------------------------------------ 播放记录

    /**
     * 保存当前视频的播放进度。
     *
     * [force] 为 false 时按位置变化节流（每 5 秒最多写一次），供播放中的 tick 调用；
     * 切集、暂停、离开页面等关键节点传 true。
     */
    private fun saveCurrentProgress(force: Boolean = false) {
        val p = player ?: return
        val path = currentVideoPath ?: return
        val duration = p.duration
        if (duration <= 0L) return
        val position = p.currentPosition
        if (position < 0L) return
        // 位置没变化就不用重复落盘；但用户主动拖回原位置（例如把看完的视频拖回 0）
        // 时仍要写一次，否则「看完」状态会一直留在记录里
        if (position == lastSavedProgressMs && !userAdjustedPosition) return
        if (!force && abs(position - lastSavedProgressMs) < PROGRESS_SAVE_INTERVAL_MS) return
        PlaybackProgressRepository.save(
            serverId = server.id,
            shareName = shareName,
            path = path,
            positionMs = position,
            durationMs = duration,
            explicit = userAdjustedPosition,
        )
        lastSavedProgressMs = position
    }

    /** 播放到结尾：强制记成已看完，播放列表里的进度线会走到底 */
    private fun markCurrentCompleted() {
        val p = player ?: return
        val path = currentVideoPath ?: return
        val duration = p.duration
        if (duration <= 0L) return
        PlaybackProgressRepository.save(server.id, shareName, path, duration, duration)
        lastSavedProgressMs = duration
    }

    /** 刷新字幕叠加层：按当前播放位置找 active cue */
    private fun updateSubtitleOverlay() {
        val overlay = binding.tvSubtitleOverlay
        if (subtitleCues.isEmpty()) {
            overlay.visibility = View.GONE
            return
        }
        val pos = player?.currentPosition ?: return
        val cue = subtitleCues.firstOrNull { pos >= it.startMs && pos < it.endMs }
        if (cue != null) {
            if (overlay.text?.toString() != cue.text) overlay.text = cue.text
            overlay.visibility = View.VISIBLE
        } else {
            overlay.visibility = View.GONE
        }
    }

    /** 把同名字幕整体读进内存；文件为空/过大时抛错，由调用方按「无字幕」处理 */
    private fun readSubtitleBytes(name: String): ByteArray {
        val path = pathOf(name)
        SmbReadHandle(session, shareName, path).use { handle ->
            handle.openAt(0)
            val total = handle.length
            if (total <= 0L) throw IOException("字幕文件为空: $name")
            if (total > MAX_SUBTITLE_BYTES) throw IOException("字幕文件过大（${total} 字节）: $name")
            val buffer = ByteArray(total.toInt())
            var read = 0
            while (read < buffer.size) {
                val n = handle.read(buffer, read, buffer.size - read)
                if (n < 0) break
                if (n == 0) throw IOException("字幕读取停滞: $name")
                read += n
            }
            return if (read == buffer.size) buffer else buffer.copyOf(read)
        }
    }

    // ------------------------------------------------------------------ 播放控制

    private fun setupPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val p = ExoPlayer.Builder(this)
            // 来电或其它应用抢音频时让出焦点，耳机拔出时自动暂停。
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(playerListener)
        binding.playerView.player = p
        player = p
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnRew.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.btnFfwd.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.btnPrevVideo.setOnClickListener { playPrev() }
        binding.btnNextVideo.setOnClickListener { playNext(fromUser = true) }
        binding.btnPlaylist.setOnClickListener { showPlaylist() }
        binding.btnDelete.setOnClickListener { confirmDeleteCurrent() }

        // 分类归档：把当前视频移到当前目录下的 1 / 2 / 3 文件夹（没有就新建）
        binding.btnMove1.setOnClickListener { moveCurrentTo("1") }
        binding.btnMove2.setOnClickListener { moveCurrentTo("2") }
        binding.btnMove3.setOnClickListener { moveCurrentTo("3") }

        binding.btnOrientation.setOnClickListener { rotateScreen() }

        // 播放页常驻倍速：单独设置，区别于长按临时倍速；设置后会继承到下一集
        binding.btnSpeed.setOnClickListener { showNormalSpeedDialog() }
        updateSpeedButton()

        // 帧工具：整组（图标 + 文字标签）都是点击区域
        binding.groupFramePrev.setOnClickListener { stepFrame(-1) }
        binding.groupFrameNext.setOnClickListener { stepFrame(1) }
        binding.groupFrameSave.setOnClickListener { saveCurrentFrame() }

        // 单击 / 双击 / 长按由 GestureDetector 识别，连续位移在这里统一处理。
        binding.tapArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureMode = GestureMode.NONE
                    suppressTap = false
                    swipeStartX = event.x
                    swipeStartY = event.y
                    swipeStartPosition = player?.currentPosition ?: 0L
                    swipeTargetPosition = swipeStartPosition
                    swipeStartBrightness = currentWindowBrightness()
                    swipeStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    mainHandler.removeCallbacks(hideGestureFeedbackRunnable)
                    binding.gestureFeedback.visibility = View.GONE
                }
                MotionEvent.ACTION_MOVE -> updateGesture(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressBoost) stopSpeedBoost()
                    finishGesture(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
                }
            }
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.seekProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = player?.duration ?: 0L
                if (duration > 0) {
                    binding.tvPosition.text = formatDuration(duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                mainHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                val duration = player?.duration ?: 0L
                if (duration > 0) {
                    userAdjustedPosition = true
                    player?.seekTo(duration * sb.progress / 1000)
                }
                scheduleHideControls()
            }
        })
    }

    // ------------------------------------------------------------------ 滑动手势

    /** 根据主轴激活手势，后续移动只更新预览，不再重复判定方向。 */
    private fun updateGesture(event: MotionEvent) {
        val dx = event.x - swipeStartX
        val dy = event.y - swipeStartY
        if (gestureMode == GestureMode.NONE) {
            val absX = abs(dx)
            val absY = abs(dy)
            if (absX <= swipeActivateDistancePx && absY <= swipeActivateDistancePx) return
            gestureMode = when {
                absX > swipeActivateDistancePx && absX > absY -> GestureMode.SEEK
                // 分区纵滑调音量/亮度关闭时，纵向手势不生效（仅保留横滑调进度）
                !zoneVolumeBrightnessEnabled -> return
                absY > swipeActivateDistancePx && absY > absX -> {
                    val width = binding.tapArea.width.toFloat().takeIf { it > 0f }
                        ?: resources.displayMetrics.widthPixels.toFloat()
                    if (swipeStartX < width / 2f) GestureMode.BRIGHTNESS else GestureMode.VOLUME
                }
                else -> return
            }
            suppressTap = true
            if (isLongPressBoost) stopSpeedBoost()
            // 手势期间保持控制栏几何尺寸不变，避免动画改变 tapArea 高度导致纵向位移跳变。
            mainHandler.removeCallbacks(hideControlsRunnable)
        }

        when (gestureMode) {
            GestureMode.SEEK -> updateSeekGesture(event)
            GestureMode.BRIGHTNESS, GestureMode.VOLUME -> updateLevelGesture(event)
            GestureMode.NONE -> Unit
        }
    }

    /** 横滑仅计算并展示目标位置，真正的 seek 在 [finishGesture] 中执行一次。 */
    private fun updateSeekGesture(event: MotionEvent) {
        val p = player ?: return
        val duration = p.duration
        val dx = event.x - swipeStartX
        val dist = abs(dx)
        val width = binding.tapArea.width.toFloat().coerceAtLeast(1f)
        val baseRate: Float
        val accel: Float
        if (duration > 0L) {
            // 滑满一屏约跳转 1/10 时长，加速项再补 1/20，长视频也能快速定位。
            baseRate = duration / (width * 10f)
            accel = duration / (width * width * 10f)
        } else {
            baseRate = 60f
            accel = 0.01f
        }
        val offsetMs = (baseRate * dist + accel * dist * dist / 2f) * if (dx >= 0f) 1f else -1f
        val target = swipeStartPosition + offsetMs.toLong()
        swipeTargetPosition = if (duration > 0L) {
            target.coerceIn(0L, duration)
        } else {
            target.coerceAtLeast(0L)
        }
        previewSeekProgress(swipeTargetPosition, duration)
        showSeekGesture(swipeTargetPosition, swipeTargetPosition - swipeStartPosition, duration)
    }

    /** 左半屏调整当前 Activity 亮度，右半屏调整媒体音量；均不修改系统持久设置。 */
    private fun updateLevelGesture(event: MotionEvent) {
        val height = binding.tapArea.height.toFloat().coerceAtLeast(1f)
        val delta = (swipeStartY - event.y) / height
        if (gestureMode == GestureMode.BRIGHTNESS) {
            val brightness = (swipeStartBrightness + delta).coerceIn(MIN_BRIGHTNESS, 1f)
            val attributes = window.attributes
            attributes.screenBrightness = brightness
            window.attributes = attributes
            showLevelGesture(GestureMode.BRIGHTNESS, brightness)
        } else if (gestureMode == GestureMode.VOLUME) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val volume = (swipeStartVolume + delta * maxVolume).roundToInt().coerceIn(0, maxVolume)
            if (volume != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            }
            showLevelGesture(GestureMode.VOLUME, volume.toFloat() / maxVolume)
        }
    }

    /** 松手后一次性提交横滑目标；系统取消触摸时不改变播放位置。 */
    private fun finishGesture(cancelled: Boolean) {
        val finishedMode = gestureMode
        if (finishedMode == GestureMode.SEEK) {
            val p = player
            if (!cancelled && p != null && swipeTargetPosition != p.currentPosition) {
                userAdjustedPosition = true
                p.seekTo(swipeTargetPosition)
            }
            updateProgress()
        }
        gestureMode = GestureMode.NONE
        if (finishedMode != GestureMode.NONE) {
            scheduleGestureFeedbackHide()
            scheduleHideControls()
        }
    }

    private fun currentWindowBrightness(): Float {
        val windowBrightness = window.attributes.screenBrightness
        if (windowBrightness >= 0f) return windowBrightness
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(MIN_BRIGHTNESS, 1f)
    }

    private fun previewSeekProgress(position: Long, duration: Long) {
        binding.tvPosition.text = formatDuration(position)
        if (duration > 0L) {
            binding.tvDuration.text = formatDuration(duration)
            binding.seekProgress.progress = (position * 1000 / duration).toInt().coerceIn(0, 1000)
        }
    }

    private fun showSeekGesture(position: Long, deltaMs: Long, duration: Long) {
        binding.ivGestureIcon.setImageResource(
            if (deltaMs < 0L) R.drawable.ic_fast_rewind else R.drawable.ic_fast_forward
        )
        binding.tvGestureText.text = if (duration > 0L) {
            getString(R.string.gesture_seek_position, formatDuration(position), formatDuration(duration))
        } else {
            formatDuration(position)
        }
        val sign = if (deltaMs < 0L) "-" else "+"
        binding.tvGestureDetail.text = getString(
            R.string.gesture_seek_delta,
            sign,
            formatDuration(abs(deltaMs)),
        )
        binding.tvGestureDetail.visibility = View.VISIBLE
        binding.gestureProgress.progress = if (duration > 0L) {
            (position * 1000 / duration).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        presentGestureFeedback()
    }

    private fun showLevelGesture(mode: GestureMode, fraction: Float) {
        val percent = (fraction.coerceIn(0f, 1f) * 100f).roundToInt()
        if (mode == GestureMode.BRIGHTNESS) {
            binding.ivGestureIcon.setImageResource(R.drawable.ic_brightness)
            binding.tvGestureText.text = getString(R.string.gesture_brightness, percent)
        } else {
            binding.ivGestureIcon.setImageResource(R.drawable.ic_volume)
            binding.tvGestureText.text = getString(R.string.gesture_volume, percent)
        }
        binding.tvGestureDetail.visibility = View.GONE
        binding.gestureProgress.progress = (fraction.coerceIn(0f, 1f) * 1000).roundToInt()
        presentGestureFeedback()
    }

    private fun presentGestureFeedback() {
        mainHandler.removeCallbacks(hideGestureFeedbackRunnable)
        binding.gestureFeedback.visibility = View.VISIBLE
    }

    private fun scheduleGestureFeedbackHide() {
        mainHandler.removeCallbacks(hideGestureFeedbackRunnable)
        mainHandler.postDelayed(hideGestureFeedbackRunnable, GESTURE_FEEDBACK_HIDE_DELAY_MS)
    }

    // ------------------------------------------------------------------ 倍速控制

    private fun startSpeedBoost() {
        val p = player ?: return
        if (isLongPressBoost) return
        isLongPressBoost = true
        suppressTap = true
        normalSpeed = p.playbackParameters.speed
        val boostSpeed = SettingsRepository.playbackSpeed
        p.playbackParameters = PlaybackParameters(boostSpeed)
        binding.tvSpeedHint.text = "${boostSpeed}x"
        binding.tvSpeedHint.visibility = View.VISIBLE
        logInfo("倍速: ${boostSpeed}x")
    }

    private fun stopSpeedBoost() {
        val p = player ?: return
        if (!isLongPressBoost) return
        isLongPressBoost = false
        p.playbackParameters = PlaybackParameters(normalSpeed)
        binding.tvSpeedHint.visibility = View.GONE
    }

    /** 刷新顶部栏常驻倍速按钮上的数值 */
    private fun updateSpeedButton() {
        binding.btnSpeed.text = getString(R.string.settings_speed_value, SettingsRepository.normalSpeed)
    }

    /** 播放页常驻倍速选择：与长按临时倍速分开，选中即设置成功并立即应用到当前视频 */
    private fun showNormalSpeedDialog() {
        // 选项与设置页长按倍速共用同一份（SpeedOptions），保证两处一致
        val labels = SpeedOptions.VALUES
            .map { getString(R.string.settings_speed_value, it) }
            .toTypedArray()
        val checked = SpeedOptions.indexOf(SettingsRepository.normalSpeed)
            .coerceAtLeast(SpeedOptions.DEFAULT_INDEX)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_speed_title)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                val sp = SpeedOptions.VALUES[which]
                SettingsRepository.normalSpeed = sp
                normalSpeed = sp
                player?.playbackParameters = PlaybackParameters(sp)
                updateSpeedButton()
                toast(getString(R.string.settings_speed_value, sp))
                dlg.dismiss()
            }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 关键帧跳转与保存

    /**
     * 上一帧 / 下一帧：按视频真实帧率步进。
     * 帧率取自当前视频轨的 Format.frameRate，取不到时按 30fps 兜底。
     */
    private fun stepFrame(direction: Int) {
        val p = player ?: return
        val fps = currentFrameRate()
        val stepMs = (1000.0 / fps).toLong().coerceAtLeast(1L)
        val duration = p.duration
        val target = (p.currentPosition + direction * stepMs).coerceAtLeast(0L)
        userAdjustedPosition = true
        p.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        updateProgress()
        showControls()
        showHint(getString(R.string.hint_frame, formatDuration(p.currentPosition)))
        logInfo("帧步进 ${if (direction < 0) "-" else "+"}1 帧 (${fps}fps): ${formatDuration(p.currentPosition)}")
    }

    /** 当前视频帧率，取不到时 30fps */
    private fun currentFrameRate(): Float {
        val p = player ?: return 30f
        p.currentTracks.groups.forEach { group ->
            for (i in 0 until group.length) {
                val frameRate = group.getTrackFormat(i).frameRate
                if (frameRate > 0f) return frameRate
            }
        }
        return 30f
    }

    /**
     * 导出当前视频帧。
     *
     * 视频画面渲染在 TextureView 上（布局里 surface_type=texture_view），
     * 必须从 TextureView 取位图——root.draw 截的是 View 层，抓不到视频图层，所以之前全黑。
     *
     * 保存位置由设置决定：默认写进手机相册 Pictures/SMBPlayer；
     * 开启「截图保存到视频文件夹」后写回视频所在的 SMB 目录（同样 PNG 无损）。
     */
    private fun saveCurrentFrame() {
        val texture = binding.playerView.videoSurfaceView as? TextureView
        val frame = texture?.bitmap
        if (frame == null) {
            showHint(getString(R.string.keyframe_not_ready))
            return
        }
        // 保存期间置灰，防止连点重复导出同一帧
        binding.groupFrameSave.isEnabled = false
        binding.groupFrameSave.alpha = 0.4f
        val filename = frameFileName()
        if (SettingsRepository.frameSaveToVideoFolder) {
            saveFrameToVideoFolder(frame, filename)
        } else {
            saveBitmapToGallery(frame, filename)
        }
    }

    /**
     * 导出帧到视频所在文件夹：PNG 无损写回当前 SMB 目录，和视频放在一起。
     * 远端已有同名文件时自动加序号（`xxx_1.png`），不覆盖之前导出的帧。
     */
    private fun saveFrameToVideoFolder(bitmap: Bitmap, filename: String) {
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val bytes = ByteArrayOutputStream().use { os ->
                        // PNG 无损，画质不打折
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        os.toByteArray()
                    }
                    val name = uniqueRemoteFrameName(filename)
                    session.writeFile(shareName, pathOf(name), bytes)
                    name to bytes.size
                }
                val savedName = saved.first
                // 同名字幕查找共用这份文件名集合，保存后同步一下，避免后续判断滞后
                allFileNames = allFileNames + savedName
                val msg = getString(R.string.keyframe_saved_folder, savedName)
                showHint(msg)
                toast(msg)
                logInfo(
                    "已导出帧到视频目录 ${if (directory.isEmpty()) "/" else directory}: " +
                        "$savedName (${bitmap.width}x${bitmap.height} PNG, ${formatSize(saved.second.toLong())})"
                )
            } catch (e: CancellationException) {
                throw e // 用户离开播放页：正常取消
            } catch (e: Exception) {
                logError("导出帧到视频目录失败: $filename", e)
                val msg = getString(R.string.keyframe_save_failed, e.message ?: "未知错误")
                showHint(msg)
                toast(msg)
            } finally {
                binding.groupFrameSave.isEnabled = true
                binding.groupFrameSave.alpha = 1f
            }
        }
    }

    /** 在远端目录里挑一个不重复的帧文件名：重名时在扩展名前加序号。阻塞，需在 IO 线程调用 */
    private fun uniqueRemoteFrameName(filename: String): String {
        if (!session.fileExists(shareName, pathOf(filename))) return filename
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        for (seq in 1..MAX_FRAME_SEQ) {
            val candidate = "${stem}_$seq$ext"
            if (!session.fileExists(shareName, pathOf(candidate))) return candidate
        }
        throw IOException("同名截图过多，请先清理该目录")
    }

    /**
     * 导出帧的文件名：视频名_时间点.png，例如「我的视频_00-12-34.png」。
     * 视频名先过滤文件系统非法字符、超长截断；取不到视频名时退化为 frame_时间点。
     */
    private fun frameFileName(): String {
        val videoName = videos.getOrNull(index)?.substringBeforeLast('.', missingDelimiterValue = "").orEmpty()
        val safeName = sanitizeFileName(videoName, MAX_FILENAME_BASE)
        val position = formatDuration(player?.currentPosition ?: 0L).replace(':', '-')
        val base = if (safeName.isEmpty()) "frame_$position" else "${safeName}_$position"
        return "$base.png"
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, filename: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SMBPlayer")
                        }
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let { imageUri ->
                        contentResolver.openOutputStream(imageUri)?.use { os ->
                            // PNG 无损，画质不打折
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    }
                }
                val msg = getString(R.string.keyframe_saved_name, filename)
                showHint(msg)
                toast(msg)
                logInfo("已导出帧到相册 Pictures/SMBPlayer: $filename (${bitmap.width}x${bitmap.height} PNG)")
            } catch (e: Exception) {
                logError("导出帧失败: $filename", e)
                val msg = getString(R.string.keyframe_save_failed, e.message ?: "未知错误")
                showHint(msg)
                toast(msg)
            } finally {
                binding.groupFrameSave.isEnabled = true
                binding.groupFrameSave.alpha = 1f
            }
        }
    }

    // ------------------------------------------------------------------ 屏幕方向

    /**
     * 屏幕旋转：每点一次转 90°，依次「竖屏 → 横屏 → 倒置竖屏 → 倒置横屏 → 竖屏」。
     *
     * 当前档位以 [requestedOrientation] 为准（不读 display.rotation）：系统旋转有动画，
     * 连点几下时 display 还没更新，按它算会「原地踏步」。第一次点击时还没手动转过
     * （跟随系统），按当前实际朝向取档，保证点下去就是转 90°。
     */
    private fun rotateScreen() {
        val known = ROTATION_ORDER.indexOf(requestedOrientation)
        val index = if (known >= 0) known else displayRotationIndex()
        val next = ROTATION_ORDER[(index + 1) % ROTATION_ORDER.size]
        requestedOrientation = next
        showHint(getString(rotationHint(next)))
    }

    /** 当前物理朝向在 [ROTATION_ORDER] 里的下标（0 竖屏 / 90 横屏 / 180 倒竖 / 270 倒横） */
    private fun displayRotationIndex(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 0
        }
    }

    private fun rotationHint(orientation: Int): Int = when (orientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> R.string.hint_orientation_landscape
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> R.string.hint_orientation_reverse_portrait
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> R.string.hint_orientation_reverse_landscape
        else -> R.string.hint_orientation_portrait
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            saveCurrentProgress(force = true)
            showHint(getString(R.string.hint_pause))
            showControls()
        } else {
            p.play()
            showHint(getString(R.string.hint_play))
            scheduleHideControls()
        }
    }

    private fun seekBy(deltaMs: Long, fromGesture: Boolean = false) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = p.duration
        val clamped = if (duration > 0L) target.coerceAtMost(duration) else target
        userAdjustedPosition = true
        p.seekTo(clamped)
        updateProgress()
        if (fromGesture) {
            showSeekGesture(clamped, deltaMs, duration)
            scheduleGestureFeedbackHide()
            scheduleHideControls()
        } else {
            showControls()
            showHint(getString(R.string.hint_seek, formatDuration(clamped)))
        }
    }

    private fun playNext(fromUser: Boolean): Boolean {
        if (index + 1 >= videos.size) {
            if (fromUser) toast(R.string.player_no_more)
            return false
        }
        playAt(index + 1)
        return true
    }

    private fun playPrev(): Boolean {
        if (index == 0) return false
        playAt(index - 1)
        return true
    }

    // ------------------------------------------------------------------ 播放列表弹窗

    private fun showPlaylist() {
        // 打开列表前先落一次当前进度，正在播放的视频也能看到最新的线
        saveCurrentProgress(force = true)
        val progressByPath = PlaybackProgressRepository.getAll(
            server.id,
            shareName,
            videos.map { pathOf(it) },
        )
        val items = videos.map { name ->
            PlaylistItem(name, progressByPath[pathOf(name)]?.displayFraction)
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_playlist, null)
        val b = DialogPlaylistBinding.bind(view)
        val layoutManager = LinearLayoutManager(this)
        b.recyclerPlaylist.layoutManager = layoutManager
        b.recyclerPlaylist.adapter = PlaylistAdapter(items, index) { position ->
            playlistDialog?.dismiss()
            if (position != index) playAt(position)
        }
        playlistDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_playlist_title)
            .setView(view)
            .setNegativeButton(R.string.server_cancel, null)
            .create()
            .also { it.show() }
        // 打开时定位到正在播放的视频（show 之后列表才有尺寸，post 一下再滚）
        b.recyclerPlaylist.post {
            if (index in videos.indices) layoutManager.scrollToPositionWithOffset(index, 0)
        }
    }

    // ------------------------------------------------------------------ 错误提示

    private fun showPlaybackError(error: PlaybackException) {
        // 播放器错误回调可能在页面销毁后陆续到达：不活着就不弹，避免 BadTokenException
        if (!isAlive()) return
        val message = error.message ?: error.errorCodeName
        logError("播放失败", error)
        binding.tvError.text = getString(R.string.player_error, message)
        binding.tvError.visibility = View.VISIBLE

        val hasNext = index + 1 < videos.size
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_error_title)
            .setMessage(getString(R.string.player_error, message))
            .setCancelable(false)
            .setPositiveButton(
                if (hasNext) R.string.player_error_action_next else R.string.player_error_action_retry
            ) { _, _ ->
                if (hasNext) playNext(fromUser = true) else playAt(index)
            }
            .setNegativeButton(R.string.player_error_action_close) { _, _ -> finish() }
            .show()
    }

    // ------------------------------------------------------------------ 控制条显隐

    private fun updateControls() {
        val p = player ?: return
        binding.btnPlayPause.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        val hasPrev = index > 0
        val hasNext = index + 1 < videos.size
        binding.btnPrevVideo.isEnabled = hasPrev
        binding.btnNextVideo.isEnabled = hasNext
        binding.btnPrevVideo.alpha = if (hasPrev) 1f else 0.35f
        binding.btnNextVideo.alpha = if (hasNext) 1f else 0.35f

        binding.btnPlaylist.isEnabled = videos.isNotEmpty()

        // 归档按钮：有视频且不在移动过程中才可点
        val canMove = videos.isNotEmpty() && !moveInProgress
        val moveButtons = arrayOf(binding.btnMove1, binding.btnMove2, binding.btnMove3)
        for (btn in moveButtons) {
            btn.isEnabled = canMove
            btn.alpha = if (canMove) 1f else 0.35f
        }

        updateProgress()
    }

    private fun updateProgress() {
        val p = player ?: return
        if (userSeeking) return
        val position = p.currentPosition
        val duration = p.duration
        binding.tvPosition.text = formatDuration(position)
        binding.tvDuration.text = if (duration > 0) formatDuration(duration) else "--:--"
        if (duration > 0 && !binding.seekProgress.isPressed) {
            binding.seekProgress.progress = (position * 1000 / duration).toInt().coerceIn(0, 1000)
            binding.seekProgress.secondaryProgress =
                (p.bufferedPosition * 1000 / duration).toInt().coerceIn(0, 1000)
        }
    }

    private fun showControls() {
        // 控制栏每次出现都按当前视频重刷一次顶部标题：切集时控制栏多半是隐藏状态，
        // 一露头就必须是对的视频名（也顺带修正任何漏更新，标题不会停在上一集）
        updateHeader()
        animateBarIn(binding.topBar, -1)
        animateBarIn(binding.bottomBar, 1)
        setBottomMargin(binding.tvSubtitleOverlay, 148)
        setBottomMargin(binding.tvHint, 168)
        scheduleHideControls()
    }

    private fun hideControls() {
        animateBarOut(binding.topBar, -1)
        animateBarOut(binding.bottomBar, 1)
        setBottomMargin(binding.tvSubtitleOverlay, 40)
        setBottomMargin(binding.tvHint, 72)
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    /** 滑动位移基准：优先用控件实际高度，未布局完成时按 100dp 兜底 */
    private fun barSlideOffset(bar: View, sign: Int): Float {
        val h = if (bar.height > 0) bar.height.toFloat() else 100f * resources.displayMetrics.density
        return sign * h
    }

    /** 控制栏滑入（顶部从上、底部从下）并淡入 */
    private fun animateBarIn(bar: View, fromSign: Int) {
        bar.animate().cancel()
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.translationY = barSlideOffset(bar, fromSign)
        }
        bar.animate().translationY(0f).alpha(1f).setDuration(200L).start()
    }

    /** 控制栏滑出（顶部向上、底部向下）并淡出，结束后真正 GONE */
    private fun animateBarOut(bar: View, toSign: Int) {
        bar.animate().cancel()
        if (bar.visibility != View.VISIBLE) return
        bar.animate().translationY(barSlideOffset(bar, toSign))
            .alpha(0f).setDuration(200L)
            .withEndAction {
                bar.visibility = View.GONE
                bar.translationY = 0f
                bar.alpha = 1f
            }.start()
    }

    /** 调整叠加层距底部的距离：控制条显示时避让，隐藏时贴近底部 */
    private fun setBottomMargin(view: View, dp: Int) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val px = (dp * resources.displayMetrics.density).toInt()
        if (params.bottomMargin != px) {
            params.bottomMargin = px
            view.layoutParams = params
        }
    }

    private fun scheduleHideControls() {
        if (binding.topBar.visibility != View.VISIBLE) return
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, CONTROL_HIDE_DELAY_MS)
    }

    companion object {
        private const val EXTRA_SERVER_ID = "extra_server_id"
        private const val EXTRA_SHARE = "extra_share"
        private const val EXTRA_DIR = "extra_dir"
        private const val EXTRA_INDEX = "extra_index"
        private const val EXTRA_TARGET_NAME = "extra_target_name"

        private const val TICK_INTERVAL_MS = 500L
        private const val CONTROL_HIDE_DELAY_MS = 4_000L
        private const val HINT_HIDE_DELAY_MS = 1_500L
        private const val GESTURE_FEEDBACK_HIDE_DELAY_MS = 450L

        /** 左右 1/3 区域双击快退 / 快进，中间区域双击播放 / 暂停。 */
        private const val DOUBLE_TAP_SEEK_MS = 10_000L
        private const val DOUBLE_TAP_SIDE_FRACTION = 0.33f

        /** 防止亮度过低导致屏幕全黑；只影响当前播放页。 */
        private const val MIN_BRIGHTNESS = 0.02f

        /** 屏幕方向按 90° 递增的顺序：竖屏 0° → 横屏 90° → 倒置竖屏 180° → 倒置横屏 270° */
        private val ROTATION_ORDER = intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        )

        /** 快进 / 快退步长 */
        private const val SEEK_STEP_MS = 5_000L

        /** 导出帧文件名中视频名部分的最大长度 */
        private const val MAX_FILENAME_BASE = 60

        /** 保存到视频文件夹时，同名文件最多往后找多少个序号 */
        private const val MAX_FRAME_SEQ = 99

        /** 同名字幕整体读进内存的大小上限（超过就按无字幕播放，避免异常大文件） */
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024

        /** 字幕叠加层刷新间隔 */
        private const val SUBTITLE_TICK_MS = 100L

        /** 播放记录落盘的最小位置变化（避免每 500ms 都写 SharedPreferences） */
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L

        /**
         * [targetName] 供首页播放记录使用：加载播放列表后按文件名定位到这一集；
         * 不传时沿用 [index]（目录列表点视频 / 播放全部走原来的逻辑）。
         */
        fun intent(
            context: Context,
            serverId: String,
            share: String,
            directory: String,
            index: Int,
            targetName: String? = null,
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_SERVER_ID, serverId)
            .putExtra(EXTRA_SHARE, share)
            .putExtra(EXTRA_DIR, directory)
            .putExtra(EXTRA_INDEX, index)
            .apply {
                if (!targetName.isNullOrEmpty()) putExtra(EXTRA_TARGET_NAME, targetName)
            }
    }
}
