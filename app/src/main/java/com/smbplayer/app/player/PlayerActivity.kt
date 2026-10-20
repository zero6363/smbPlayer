package com.smbplayer.app.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.app.R
import com.smbplayer.app.data.ServerRepository
import com.smbplayer.app.data.SettingsRepository
import com.smbplayer.app.data.SmbServer
import com.smbplayer.app.data.SubtitleFormats
import com.smbplayer.app.data.VideoFormats
import com.smbplayer.app.databinding.ActivityPlayerBinding
import com.smbplayer.app.databinding.DialogPlaylistBinding
import com.smbplayer.app.smb.SmbGateway
import com.smbplayer.app.smb.SmbReadHandle
import com.smbplayer.app.smb.SmbSession
import com.smbplayer.app.ui.PlaylistAdapter
import com.smbplayer.app.util.formatDuration
import com.smbplayer.app.util.isAlive
import com.smbplayer.app.util.joinPath
import com.smbplayer.app.util.logError
import com.smbplayer.app.util.logInfo
import com.smbplayer.app.util.sanitizeFileName
import com.smbplayer.app.util.toast
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 播放页：播放 [directory] 目录下的所有视频。
 *
 * 支持删除当前视频（删除后自动跳到下一个）、上一个/下一个、播放列表。
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

    /** 当前目录下的全部非目录文件名（用于在同级目录里找同名字幕） */
    private var allFileNames: Set<String> = emptySet()

    /** 当前播放挂载的同名字幕（出错时用于回退为无字幕重播） */
    private var currentSubtitleName: String? = null

    /** 当前视频是否已因字幕失败回退过一次（只回退一次，避免死循环） */
    private var subtitleRetried = false

    /** 下一次播放不要字幕（字幕失败回退时一次性置位） */
    private var skipSubtitleOnce = false

    /** 当前这档媒体自从设置后是否进入过 READY（用于字幕卡住兜底判断） */
    private var playbackEverReady = false

    /** 字幕挂载后播放卡住的兜底：10 秒还没起播就按无字幕重播一次 */
    private val subtitleStallRunnable = Runnable {
        val p = player ?: return@Runnable
        val sub = currentSubtitleName
        if (sub != null && !subtitleRetried && !playbackEverReady &&
            p.playbackState == Player.STATE_BUFFERING
        ) {
            subtitleRetried = true
            skipSubtitleOnce = true
            val resumeAt = p.currentPosition
            logError("字幕挂载后播放卡住，回退为无字幕播放: $sub")
            toast(getString(R.string.subtitle_fallback_hint))
            playAt(index, resumeAt)
        }
    }

    private var player: ExoPlayer? = null
    private var userSeeking = false
    private var playlistDialog: android.app.Dialog? = null

    /** 播放页常驻倍速（区别于长按临时倍速），取自设置，切到下一集时继承 */
    private var normalSpeed = SettingsRepository.normalSpeed
    private var isLongPressBoost = false

    /**
     * 滑动调进度：非线性映射——滑得越远，每像素对应的秒数越多
     * （基础速率 + 距离平方加速项），目标始终以按下点为基准计算，
     * 不用逐次增量累加——网络流 seek 后 currentPosition 有延迟，
     * 增量法会把延迟算进去，快速滑动时进度越漂越偏。
     */
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartPosition = 0L
    private var isSwiping = false

    /** 滑动激活的最小水平位移（24dp 换算成 px），且要求水平位移大于竖直位移 */
    private val swipeActivateDistancePx by lazy { 24f * resources.displayMetrics.density }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 居中提示自动隐藏 */
    private val hideCenterHintRunnable = Runnable { binding.tvCenterHint.visibility = View.GONE }

    /** 居中显示一条操作提示，稍后自动隐藏 */
    private fun showCenterHint(text: String) {
        binding.tvCenterHint.text = text
        binding.tvCenterHint.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideCenterHintRunnable)
        mainHandler.postDelayed(hideCenterHintRunnable, HINT_HIDE_DELAY_MS)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            // 滑动调进度期间不自动刷新：seek 后的实际位置有延迟，
            // tick 会把进度条拉回旧位置，和滑动目标互相覆盖造成抖动
            if (!isSwiping) updateProgress()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val hideControlsRunnable = Runnable {
        if (player?.isPlaying == true && !userSeeking && !isSwiping && !isFinishing) hideControls()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.progress.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_ENDED) {
                playNext(fromUser = false)
            } else if (playbackState == Player.STATE_READY) {
                playbackEverReady = true
                mainHandler.removeCallbacks(subtitleStallRunnable)
            } else if (playbackState != Player.STATE_BUFFERING) {
                mainHandler.removeCallbacks(subtitleStallRunnable)
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
            // 挂了同名字幕的：先按「无字幕」重播一次（保留当前进度）。
            // 字幕的问题（读取/解析/合并）不该让视频本身也看不了。
            val p = player
            if (p != null && currentSubtitleName != null && !subtitleRetried) {
                subtitleRetried = true
                skipSubtitleOnce = true
                val resumeAt = p.currentPosition
                logError("字幕导致播放失败，回退为无字幕播放: $currentSubtitleName", error)
                toast(getString(R.string.subtitle_fallback_hint))
                playAt(index, resumeAt)
                return
            }
            showPlaybackError(error)
        }

        override fun onTracksChanged(tracksInfo: Tracks) {
            // 外部同名字幕默认打了 SELECTION_FLAG_DEFAULT，但部分设备/格式下默认选择器
            // 不会自动把它选上；这里兜底：存在文本轨就强制选中，确保字幕显示。
            val p = player ?: return
            val textGroup = tracksInfo.groups.firstOrNull {
                it.type == C.TRACK_TYPE_TEXT && it.isSupported && it.length > 0
            } ?: return
            val group = textGroup.mediaTrackGroup
            val desired = TrackSelectionOverride(group, listOf(0))
            if (p.trackSelectionParameters.overrides[group] != desired) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(desired)
                    .build()
            }
        }
    }

    // 必须 lazy：GestureDetector 构造时会走 context.getResources()，
    // 而字段初始化器在 Activity 构造函数中执行，早于 attach()，mBase 还是 null，会直接 NPE。
    private val gestureDetector by lazy { GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            startSpeedBoost()
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (!isSwiping) return false
            val p = player ?: return false
            val duration = p.duration
            // 非线性映射：目标 = 按下时的进度 + f(水平位移)。
            // f = 基础速率×距离 + 加速系数×距离²/2，滑得越远每像素跳转越多；
            // 右滑快进、左滑快退，全程只以按下点为基准，不逐次累加
            val dx = e2.x - swipeStartX
            val dist = abs(dx)
            val screenW = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
            val baseRate: Float
            val accel: Float
            if (duration > 0) {
                // 滑满一屏 ≈ 跳 1/10 时长，加速项再补 1/20；视频越长单位距离跳得越多
                baseRate = duration / (screenW * 10f)
                accel = duration / (screenW * screenW * 10f)
            } else {
                // 时长未知（直播等）：固定基础速率 60ms/px
                baseRate = 60f
                accel = 0.01f
            }
            val offsetMs = (baseRate * dist + accel * dist * dist / 2f) * if (dx >= 0) 1f else -1f
            val target = swipeStartPosition + offsetMs.toLong()
            val clamped = if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
            p.seekTo(clamped)
            updateProgress()
            // 滑动过程中实时提示滑到了几分几秒、相对按下点偏移多少
            val sign = if (clamped >= swipeStartPosition) "+" else "-"
            val diffSec = (abs(clamped - swipeStartPosition) / 1000)
            showCenterHint(
                getString(
                    R.string.hint_swipe_seek,
                    formatDuration(clamped),
                    "$sign${diffSec}s"
                )
            )
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 双击播放 / 暂停
            if (isSwiping) return false
            togglePlayPause()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // 单击切换控制条显示/隐藏（长按倍速、滑动进度不触发）
            if (isLongPressBoost || isSwiping) return false
            if (binding.topBar.visibility == View.VISIBLE) hideControls() else showControls()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            return false
        }
    }) }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logInfo("PlayerActivity onCreate")
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        logInfo("播放: 服务器=${server.host} 共享=$shareName 目录=$directory 索引=$index")

        setupPlayer()
        setupControls()

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
    }

    override fun onStop() {
        super.onStop()
        // 关闭后台播放时，离开播放页（息屏 / 切后台）就暂停，避免后台出声
        if (!SettingsRepository.backgroundPlay) {
            player?.pause()
        }
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.removeCallbacks(subtitleStallRunnable)
    }

    override fun onDestroy() {
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
                toast(getString(R.string.deleted, name))
                videos.removeAt(index)
                if (videos.isEmpty()) {
                    finish()
                    return@launch
                }
                index = index.coerceIn(0, videos.lastIndex)
                playAt(index, startPositionMs = 0)
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
                logInfo("播放列表加载完成: ${list.size} 个视频")
                playAt(index, startPositionMs = 0)
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

    private fun playAt(newIndex: Int, startPositionMs: Long = 0) {
        if (newIndex !in videos.indices) {
            logError("播放索引越界: $newIndex, 列表大小=${videos.size}")
            return
        }
        index = newIndex
        val name = videos[newIndex]
        val uri = displayUri(name)
        logInfo("开始播放: $name 位置=${startPositionMs}ms")

        // 换视频：作废上一条卡住兜底，重置「是否起播过」标记
        mainHandler.removeCallbacks(subtitleStallRunnable)
        playbackEverReady = false

        // 先同步更新标题/进度等 UI；字幕的 SMB 读取放 IO 线程，避免卡主线程
        binding.tvTitle.text = name
        binding.tvSubtitle.text = getString(
            R.string.player_title_format,
            newIndex + 1,
            videos.size,
            if (directory.isEmpty()) "/" else directory
        )
        binding.tvError.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        updateControls()
        showControls()

        lifecycleScope.launch {
            try {
                // 自动加载视频同名字幕：在同级目录找同名（或带语言标签）的 .srt/.ass 等文件。
                // 字幕先整体读进内存再挂轨——读取失败/文件为空/过大都按「无字幕」播放，
                // 绝不让字幕问题拖垮视频播放。
                val subtitleName = if (skipSubtitleOnce) {
                    // 上一次就是字幕把播放搞挂了：这次明确不要字幕
                    skipSubtitleOnce = false
                    logInfo("字幕加载失败过，本次按无字幕播放: $name")
                    null
                } else {
                    SubtitleFormats.findSubtitle(name, allFileNames)
                }
                val subtitleBytes = if (subtitleName != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { readSubtitleBytes(subtitleName) }
                            .onFailure { logError("字幕读取失败，按无字幕播放: $subtitleName", it) }
                            .getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                    }
                } else {
                    null
                }

                val factory = SmbDataSource.Factory(session, shareName, pathOf(name), uri)
                val videoSource = ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory())
                    .createMediaSource(MediaItem.fromUri(uri))

                val source: MediaSource =
                    if (subtitleName != null && subtitleBytes != null) {
                        val subUri = displayUri(subtitleName)
                        val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                            .setMimeType(SubtitleFormats.mimeOf(subtitleName) ?: MimeTypes.APPLICATION_SUBRIP)
                            .setLanguage("und")
                            .setLabel(subtitleName)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                        // 字幕数据已在内存里，用 ByteArrayDataSource 挂成单独的字幕轨，
                        // 再和视频轨合并——播放过程中不再走 SMB，杜绝字幕流把播放搞挂。
                        val subSource = SingleSampleMediaSource.Factory { ByteArrayDataSource(subtitleBytes) }
                            .createMediaSource(subConfig, C.TIME_UNSET)
                        MergingMediaSource(videoSource, subSource)
                    } else {
                        videoSource
                    }

                // 期间用户可能又切了别的视频：被取代的这次不再动手
                if (index != newIndex) return@launch

                val p = player ?: return@launch
                p.setMediaSource(source, startPositionMs)
                p.prepare()
                p.playWhenReady = true

                // 应用播放页常驻倍速（每次切集都继承，保证「上一集的速度带到下一集」）
                normalSpeed = SettingsRepository.normalSpeed
                p.playbackParameters = PlaybackParameters(normalSpeed)
                updateSpeedButton()

                val loadedSubtitle =
                    if (subtitleName != null && subtitleBytes != null) subtitleName else null
                currentSubtitleName = loadedSubtitle
                subtitleRetried = false
                if (loadedSubtitle != null) {
                    logInfo("已加载同名字幕: $loadedSubtitle")
                    showCenterHint(getString(R.string.subtitle_loaded, loadedSubtitle))
                    // 兜底：个别设备/格式下合并字幕轨会让播放一直卡在缓冲起不来，
                    // 10 秒还没起播就按无字幕重播一次，保证视频能看。
                    mainHandler.postDelayed(
                        subtitleStallRunnable,
                        SUBTITLE_STALL_TIMEOUT_MS,
                    )
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
        val p = ExoPlayer.Builder(this).build()
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
        binding.btnOrientation.setOnClickListener { toggleOrientation() }

        // 播放页常驻倍速：单独设置，区别于长按临时倍速；设置后会继承到下一集
        binding.btnSpeed.setOnClickListener { showNormalSpeedDialog() }
        updateSpeedButton()

        // 帧工具：整组（图标 + 文字标签）都是点击区域
        binding.groupFramePrev.setOnClickListener { stepFrame(-1) }
        binding.groupFrameNext.setOnClickListener { stepFrame(1) }
        binding.groupFrameSave.setOnClickListener { saveCurrentFrame() }

        // 手势检测：长按倍速 + 滑动调进度
        binding.tapArea.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                    swipeStartPosition = player?.currentPosition ?: 0L
                    isSwiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isSwiping) {
                        val dx = abs(event.x - swipeStartX)
                        val dy = abs(event.y - swipeStartY)
                        // 水平位移超过阈值且明显大于竖直位移，才认定为滑动调进度，
                        // 避免竖直拖动（或斜着碰一下）误触发
                        if (dx > swipeActivateDistancePx && dx > dy) {
                            isSwiping = true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressBoost) {
                        stopSpeedBoost()
                    }
                    isSwiping = false
                }
            }
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
                    player?.seekTo(duration * sb.progress / 1000)
                }
                scheduleHideControls()
            }
        })
    }

    // ------------------------------------------------------------------ 倍速控制

    private fun startSpeedBoost() {
        val p = player ?: return
        if (isLongPressBoost) return
        isLongPressBoost = true
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
        val speeds = floatArrayOf(0.3f, 0.5f, 0.7f, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f)
        val labels = speeds.map { getString(R.string.settings_speed_value, it) }.toTypedArray()
        val current = SettingsRepository.normalSpeed
        var checked = speeds.indexOfFirst { it == current }.coerceAtLeast(3)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_speed_title)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                val sp = speeds[which]
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
        p.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        updateProgress()
        showControls()
        showCenterHint(getString(R.string.hint_frame, formatDuration(p.currentPosition)))
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
     * 导出当前视频帧到相册（PNG 无损，最高品质）。
     *
     * 视频画面渲染在 TextureView 上（布局里 surface_type=texture_view），
     * 必须从 TextureView 取位图——root.draw 截的是 View 层，抓不到视频图层，所以之前全黑。
     */
    private fun saveCurrentFrame() {
        val texture = binding.playerView.videoSurfaceView as? TextureView
        val frame = texture?.bitmap
        if (frame == null) {
            showCenterHint(getString(R.string.keyframe_not_ready))
            return
        }
        // 保存期间置灰，防止连点重复导出同一帧
        binding.groupFrameSave.isEnabled = false
        binding.groupFrameSave.alpha = 0.4f
        saveBitmapToGallery(frame, frameFileName())
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
                showCenterHint(msg)
                toast(msg)
                logInfo("已导出帧到相册 Pictures/SMBPlayer: $filename (${bitmap.width}x${bitmap.height} PNG)")
            } catch (e: Exception) {
                logError("导出帧失败: $filename", e)
                val msg = getString(R.string.keyframe_save_failed, e.message ?: "未知错误")
                showCenterHint(msg)
                toast(msg)
            } finally {
                binding.groupFrameSave.isEnabled = true
                binding.groupFrameSave.alpha = 1f
            }
        }
    }

    // ------------------------------------------------------------------ 屏幕方向

    private fun toggleOrientation() {
        val toLandscape = requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        requestedOrientation = if (toLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        showCenterHint(
            getString(if (toLandscape) R.string.hint_orientation_landscape else R.string.hint_orientation_portrait)
        )
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            showCenterHint(getString(R.string.hint_pause))
            showControls()
        } else {
            p.play()
            showCenterHint(getString(R.string.hint_play))
            scheduleHideControls()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = p.duration
        p.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        updateProgress()
        showControls()
        showCenterHint(getString(R.string.hint_seek, formatDuration(p.currentPosition)))
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
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_playlist, null)
        val b = DialogPlaylistBinding.bind(view)
        val layoutManager = LinearLayoutManager(this)
        b.recyclerPlaylist.layoutManager = layoutManager
        b.recyclerPlaylist.adapter = PlaylistAdapter(videos, index) { position ->
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
        }
    }

    private fun showControls() {
        binding.topBar.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        mainHandler.removeCallbacks(hideControlsRunnable)
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

        private const val TICK_INTERVAL_MS = 500L
        private const val CONTROL_HIDE_DELAY_MS = 4_000L
        private const val HINT_HIDE_DELAY_MS = 1_500L

        /** 快进 / 快退步长 */
        private const val SEEK_STEP_MS = 5_000L

        /** 导出帧文件名中视频名部分的最大长度 */
        private const val MAX_FILENAME_BASE = 60

        /** 同名字幕整体读进内存的大小上限（超过就按无字幕播放，避免异常大文件） */
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024

        /** 字幕挂载后多久还没起播就按无字幕重播（兜底） */
        private const val SUBTITLE_STALL_TIMEOUT_MS = 10_000L

        fun intent(
            context: Context,
            serverId: String,
            share: String,
            directory: String,
            index: Int,
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_SERVER_ID, serverId)
            .putExtra(EXTRA_SHARE, share)
            .putExtra(EXTRA_DIR, directory)
            .putExtra(EXTRA_INDEX, index)
    }
}
