package com.smbplayer.app.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.roundToInt
import com.smbplayer.app.R
import com.smbplayer.app.data.PlaybackProgressRepository
import com.smbplayer.app.data.ServerRepository
import com.smbplayer.app.data.SettingsRepository
import com.smbplayer.app.data.SmbServer
import com.smbplayer.app.data.VideoFormats
import com.smbplayer.app.databinding.ActivityMpvPlayerBinding
import com.smbplayer.app.smb.SmbGateway
import com.smbplayer.app.smb.SmbSession
import com.smbplayer.app.util.sanitizeFileName
import dev.jdtech.mpv.MPVLib
import java.io.ByteArrayOutputStream

/**
 * [mpv 实验分支] 基于 libmpv 的 SMB 播放页。
 *
 * SMB 数据经 [SmbHttpProxy] 转成 127.0.0.1 上的 HTTP 流喂给 mpv——libmpv AAR
 * 不带 libsmbclient、MPVLib 也没开放 stream_cb，这是本项目能接 SMB 的方式
 * （与 reex 的 libsmb2 + stream_cb 架构等价）。
 *
 * 工具栏：顶栏 返回/文件名/时长/锁定/列表/更多；
 * 底栏 上一个/快退/播放/快进/下一个 + 进度/时间/截图/旋转。
 */
class MpvPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMpvPlayerBinding
    private var mpv: MPVLib? = null
    private var proxy: SmbHttpProxy? = null

    private lateinit var server: SmbServer
    private lateinit var session: SmbSession
    private var share = ""
    private var directory = ""
    private var videoNames: List<String> = emptyList()
    private var currentIndex = 0
    private var currentToken: String? = null
    private var currentPath = ""
    /** Surface 是否已 attach 到 mpv；loadfile 必须等它就绪，否则硬解拿不到 native_window */
    private var surfaceReady = false
    /** surface 就绪前挂起的 loadfile 参数 */
    private var pendingLoad: Pair<String, Long>? = null

    private val handler = Handler(Looper.getMainLooper())
    private var tickerPosted = false
    private var lastSaveMs = 0L
    private var controlsVisible = true
    private var seekingByUser = false

    /**
     * 屏蔽 loadfile replace 产生的伪 END_FILE：mpv 用 loadfile ... replace 切集时，
     * 旧文件会先发一个 END_FILE，若当「自然播完」处理就会连锁跳集。
     * loadCurrent 里置 true，新文件真正开始播放（time-pos 变化）后清除。
     */
    private var suppressEndFile = false
    private var loadFileAtMs = 0L
    /** 自然播完后的自动下一集节流，避免短片 eof 连锁触发多次 */
    private var lastEndFileMs = 0L

    /**
     * Activity 销毁标志：onDestroy 开头即置位。mpv destroy 时会从内部线程回调
     * observer（END_FILE 等），若不拦截会在销毁后 loadCurrent 触发 register/自动下一集，
     * 甚至 native 层访问已释放对象导致进程崩溃（返回主页后延迟跳出）。
     */
    @Volatile
    private var destroyed = false

    /** 常驻倍速（顶栏药丸选择；切集继承）。长按临时倍速见 isLongPressBoost。 */
    private var normalSpeed = SettingsRepository.normalSpeed
    private var isLongPressBoost = false
    /** reex 风格设置面板 */
    private var settingsPanel: MpvSettingsPanel? = null

    // ---- 手势：单击显隐控制栏 / 双击三分区 / 横滑调进度 / 左右纵滑调亮度音量 / 长按临时倍速 ----
    private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }
    private var gestureMode = GestureMode.NONE
    private var suppressTap = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartPositionMs = 0L
    private var swipeTargetPositionMs = 0L
    private var swipeStartBrightness = 0.5f
    private var swipeStartVolume = 0
    /** 与原版共用同一设置开关，默认关闭 */
    private var zoneVolumeBrightnessEnabled = false
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val swipeActivateDistancePx by lazy { 24f * resources.displayMetrics.density }

    /** 必须 lazy：构造时走 getResources，字段初始化早于 attach 会 NPE（与原版同坑） */
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (gestureMode == GestureMode.NONE && !isLongPressBoost) startSpeedBoost()
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (gestureMode != GestureMode.NONE || isLongPressBoost) return false
                // 全局双击 = 播放/暂停，不做分区快进快退
                togglePlayPause()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 等双击判定结束再切控制栏，避免双击时控制栏先闪一下
                if (suppressTap || gestureMode != GestureMode.NONE || isLongPressBoost) return false
                setControlsVisible(!controlsVisible)
                return true
            }
        })
    }

    private val hideGestureFeedbackRunnable = Runnable {
        binding.gestureFeedback.visibility = android.view.View.GONE
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            val m = mpv
            if (m != null) {
                // 切集后给新文件 1.5s 缓冲，期间忽略旧文件 loadfile replace 的伪 END_FILE
                if (suppressEndFile && System.currentTimeMillis() - loadFileAtMs > 1_500L) {
                    suppressEndFile = false
                }
                val posSec = runCatching { m.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
                val durSec = runCatching { m.getPropertyDouble("duration") }.getOrNull() ?: 0.0
                val posMs = (posSec * 1000).toLong()
                val durMs = (durSec * 1000).toLong()
                if (durMs > 0L) {
                    binding.tvTotal.text = formatTime(durMs)
                    binding.tvTopDuration.text = formatTime(durMs)
                    if (!seekingByUser && durSec > 0.0) {
                        binding.seekBar.progress = (posSec / durSec * 1000).toInt().coerceIn(0, 1000)
                    }
                    binding.loading.visibility = android.view.View.GONE
                }
                binding.tvCurrent.text = formatTime(posMs)
                // 播放图标随状态切换
                val playing = runCatching { m.getPropertyBoolean("pause") }.getOrNull() == false
                binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                // 单视频时隐藏上一个/下一个；多视频时到头置灰（与原版一致）
                val multi = videoNames.size > 1
                val prevVis = if (multi) android.view.View.VISIBLE else android.view.View.GONE
                val nextVis = if (multi) android.view.View.VISIBLE else android.view.View.GONE
                if (binding.btnPrevVideo.visibility != prevVis) binding.btnPrevVideo.visibility = prevVis
                if (binding.btnNextVideo.visibility != nextVis) binding.btnNextVideo.visibility = nextVis
                val hasPrev = currentIndex > 0
                val hasNext = currentIndex + 1 < videoNames.size
                binding.btnPrevVideo.isEnabled = hasPrev
                binding.btnNextVideo.isEnabled = hasNext
                binding.btnPrevVideo.alpha = if (hasPrev) 1f else 0.35f
                binding.btnNextVideo.alpha = if (hasNext) 1f else 0.35f
                if (posMs - lastSaveMs >= 5_000L) {
                    saveProgress(posMs, durMs)
                    lastSaveMs = posMs
                }
            }
            handler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMpvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
        share = intent.getStringExtra(EXTRA_SHARE).orEmpty()
        directory = intent.getStringExtra(EXTRA_DIR).orEmpty()
        // am -e 传的是 String；内部 intent() 传的是 Int，两种都要兼容
        val startIndex = intent.getStringExtra("debug_index")?.toIntOrNull()
            ?: intent.getIntExtra(EXTRA_INDEX, 0)
        val targetName = intent.getStringExtra(EXTRA_TARGET_NAME)

        // adb 调试入口：直接传连接参数，无需在 UI 里保存服务器配置。
        // am start -n com.smbplayer.app/.player.MpvPlayerActivity \
        //   -e debug_host 127.0.0.1 -e debug_share test -e debug_dir "" -e debug_index 0
        val debugHost = intent.getStringExtra("debug_host")
        val debugUrlMode = !intent.getStringExtra("debug_url").isNullOrEmpty()
        server = if (!debugHost.isNullOrEmpty() || debugUrlMode) {
            val debugUser = intent.getStringExtra("debug_user").orEmpty()
            SmbServer(
                id = "mpv-debug",
                host = debugHost.orEmpty(),
                // am -e 传的一律是 String，用 getIntExtra 会 ClassCastException
                port = intent.getStringExtra("debug_port")?.toIntOrNull() ?: 445,
                share = intent.getStringExtra("debug_share").orEmpty(),
                username = debugUser,
                password = intent.getStringExtra("debug_pass").orEmpty(),
                anonymous = debugUser.isEmpty(),
            )
        } else {
            ServerRepository.get(serverId) ?: run {
                Toast.makeText(this, "服务器配置不存在", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }
        if (intent.hasExtra("debug_dir")) directory = intent.getStringExtra("debug_dir").orEmpty()
        // session.list() 用的是 Activity 成员变量，debug 模式下要与 SmbServer 保持一致
        if (!debugHost.isNullOrEmpty()) {
            share = server.share
            directory = intent.getStringExtra("debug_dir").orEmpty()
        }
        session = SmbGateway.session(server)
        currentIndex = startIndex.coerceAtLeast(0)

        normalSpeed = SettingsRepository.normalSpeed
        zoneVolumeBrightnessEnabled = SettingsRepository.zoneVolumeBrightness
        updateSpeedButton()
        setupControls()
        setupBackExit()
        initMpv()

        // debug_url 模式：mpv 已在 initMpv 里加载，跳过 SMB 目录列举
        if (!intent.getStringExtra("debug_url").isNullOrEmpty()) return

        // SMB 目录列举是网络操作，放后台线程
        Thread {
            if (destroyed) return@Thread
            val listResult = runCatching { session.list(share, directory) }
            if (destroyed) return@Thread
            listResult.exceptionOrNull()?.let {
                android.util.Log.e("MpvPlayer", "SMB 目录列举失败: smb://$share/$directory", it)
            }
            val entries = listResult.getOrNull().orEmpty()
            android.util.Log.i("MpvPlayer", "SMB 列举到 ${entries.size} 项，share=$share dir=$directory")
            val names = VideoFormats.sortNames(
                entries.filter { !it.isDirectory && VideoFormats.isVideo(it.name) }.map { it.name }
            )
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                videoNames = names
                val idx = if (!targetName.isNullOrEmpty()) {
                    names.indexOfFirst { it.equals(targetName, ignoreCase = true) }
                        .takeIf { it >= 0 } ?: currentIndex
                } else currentIndex
                if (names.isEmpty()) {
                    binding.statusText.text = "该目录没有视频文件"
                    binding.statusText.visibility = android.view.View.VISIBLE
                    binding.loading.visibility = android.view.View.GONE
                    return@runOnUiThread
                }
                currentIndex = idx.coerceIn(0, names.lastIndex)
                loadCurrent()
            }
        }.start()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSpeed.setOnClickListener { showNormalSpeedDialog() }
        binding.btnPlaylist.setOnClickListener { showPlaylist() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        binding.btnPlayPause.setOnClickListener {
            mpv?.command(arrayOf("cycle", "pause"))
        }
        binding.btnRew.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.btnFfwd.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.btnPrevVideo.setOnClickListener { switchVideo(currentIndex - 1) }
        binding.btnNextVideo.setOnClickListener { switchVideo(currentIndex + 1) }
        binding.btnFrameSave.setOnClickListener { saveCurrentFrame() }
        // 旋转 = 切换横竖屏（像系统横竖屏切换那样，控制栏随之重新布局）
        binding.btnRotate.setOnClickListener {
            val portrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            requestedOrientation = if (portrait)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Toast.makeText(this, "已切换到${if (portrait) "横屏" else "竖屏"}", Toast.LENGTH_SHORT).show()
            // recreate() 会重建 Activity 并从 intent 重新读 index/targetName；
            // 必须先把「当前」切集状态写回 intent，否则旋转后会跳回最初进入时的那一集。
            intent.putExtra(EXTRA_INDEX, currentIndex)
            videoNames.getOrNull(currentIndex)?.let { intent.putExtra(EXTRA_TARGET_NAME, it) }
            recreate() // 让 layout-port / layout 重新 inflate（configChanges 下不会自动重载）
        }

        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) { seekingByUser = true }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val dur = runCatching { mpv?.getPropertyDouble("duration") }.getOrNull() ?: 0.0
                val p = (seekBar?.progress ?: 0) / 1000.0 * dur
                mpv?.command(arrayOf("seek", p.toString(), "absolute"))
                seekingByUser = false
            }
        })

        // 单击显隐控制栏 / 双击三分区 / 横滑调进度 / 长按临时倍速，全部由手势统一识别
        binding.tapArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureMode = GestureMode.NONE
                    suppressTap = false
                    swipeStartX = event.x
                    swipeStartY = event.y
                    swipeStartPositionMs = currentPositionMs()
                    swipeTargetPositionMs = swipeStartPositionMs
                    swipeStartBrightness = currentWindowBrightness()
                    swipeStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    handler.removeCallbacks(hideGestureFeedbackRunnable)
                    binding.gestureFeedback.visibility = android.view.View.GONE
                }
                MotionEvent.ACTION_MOVE -> handleGestureMove(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressBoost) stopSpeedBoost()
                    finishSeekGesture(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
                }
            }
            gestureDetector.onTouchEvent(event)
            true // 自己消费，单击/双击由 GestureDetector 回调处理
        }
    }

    /** 从 mpv 读当前播放位置（秒→毫秒） */
    private fun currentPositionMs(): Long =
        ((runCatching { mpv?.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0) * 1000).toLong()

    /** 判定手势主轴：横滑调进度；纵滑按设置开关注入左右分区调亮度/音量（与原版一致） */
    private fun handleGestureMove(event: MotionEvent) {
        val dx = event.x - swipeStartX
        val dy = event.y - swipeStartY
        if (gestureMode == GestureMode.NONE) {
            val absX = abs(dx)
            val absY = abs(dy)
            if (absX <= swipeActivateDistancePx && absY <= swipeActivateDistancePx) return
            gestureMode = when {
                absX > swipeActivateDistancePx && absX > absY -> GestureMode.SEEK
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
        }
        if (gestureMode == GestureMode.BRIGHTNESS || gestureMode == GestureMode.VOLUME) {
            updateLevelGesture(event)
            return
        }
        updateSeekPreview(event)
    }

    /** 左右分区纵滑：左半屏调当前 Activity 亮度，右半屏调媒体音量 */
    private fun updateLevelGesture(event: MotionEvent) {
        val height = binding.tapArea.height.toFloat().coerceAtLeast(1f)
        val delta = (swipeStartY - event.y) / height
        if (gestureMode == GestureMode.BRIGHTNESS) {
            val brightness = (swipeStartBrightness + delta).coerceIn(0.02f, 1f)
            window.attributes = window.attributes.apply { screenBrightness = brightness }
            binding.gestureFeedback.text = "亮度 ${(brightness * 100).roundToInt()}%"
        } else {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val volume = (swipeStartVolume + delta * max).roundToInt().coerceIn(0, max)
            if (volume != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            }
            binding.gestureFeedback.text = "音量 ${(volume.toFloat() / max * 100).roundToInt()}%"
        }
        binding.gestureFeedback.visibility = android.view.View.VISIBLE
    }

    private fun currentWindowBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0f) return w
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    /** 横滑：按位移非线性估算目标位置，松手前只预览、不真正 seek（与原版一致） */
    private fun updateSeekPreview(event: MotionEvent) {
        val dx = event.x - swipeStartX
        val durationMs = ((runCatching { mpv?.getPropertyDouble("duration") }.getOrNull() ?: 0.0) * 1000).toLong()
        val dist = abs(dx)
        val width = binding.tapArea.width.toFloat().coerceAtLeast(1f)
        // 滑满一屏约跳 1/10 时长，加速项再补 1/20，长视频也能快速定位
        val baseRate: Float
        val accel: Float
        if (durationMs > 0L) {
            baseRate = durationMs / (width * 10f)
            accel = durationMs / (width * width * 10f)
        } else {
            baseRate = 60f
            accel = 0.01f
        }
        val offsetMs = (baseRate * dist + accel * dist * dist / 2f) * if (dx >= 0f) 1f else -1f
        val target = swipeStartPositionMs + offsetMs.toLong()
        swipeTargetPositionMs = if (durationMs > 0L) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
        val deltaSec = (swipeTargetPositionMs - swipeStartPositionMs) / 1000
        val sign = if (deltaSec >= 0) "+" else "-"
        binding.gestureFeedback.text = "$sign${abs(deltaSec)} 秒\n${formatTime(swipeTargetPositionMs)}"
        binding.gestureFeedback.visibility = android.view.View.VISIBLE
    }

    /** 松手后一次性提交横滑目标，避免连续 seek 拖慢 SMB 网络流 */
    private fun finishSeekGesture(cancelled: Boolean) {
        val finished = gestureMode
        if (finished == GestureMode.SEEK && !cancelled) {
            val dur = ((runCatching { mpv?.getPropertyDouble("duration") }.getOrNull() ?: 0.0) * 1000).toLong()
            val target = if (dur > 0L) swipeTargetPositionMs.coerceIn(0L, dur) else swipeTargetPositionMs
            mpv?.command(arrayOf("seek", (target / 1000.0).toString(), "absolute"))
        }
        gestureMode = GestureMode.NONE
        if (finished != GestureMode.NONE) {
            handler.removeCallbacks(hideGestureFeedbackRunnable)
            handler.postDelayed(hideGestureFeedbackRunnable, 450L)
        }
    }

    /** 双击中间区：播放/暂停切换 */
    private fun togglePlayPause() {
        val m = mpv ?: return
        val playing = runCatching { m.getPropertyBoolean("pause") }.getOrNull() == false
        m.command(arrayOf("cycle", "pause"))
        binding.gestureFeedback.text = if (playing) "暂停" else "播放"
        binding.gestureFeedback.visibility = android.view.View.VISIBLE
        handler.removeCallbacks(hideGestureFeedbackRunnable)
        handler.postDelayed(hideGestureFeedbackRunnable, 450L)
    }

    /**
     * 播放页两次返回才退回：第一次提示，2 秒内第二次 finish() 只退播放页
     * （不关应用，避免回到主页后又叠加主页的双返回直接退出）。
     */
    private fun setupBackExit() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var lastBackMs = 0L
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackMs < 2_000L) {
                    destroyed = true // 先标记，让随后触发的 surfaceDestroyed 跳过 detachSurface 避免 vo 崩溃
                    finish()
                } else {
                    lastBackMs = now
                    Toast.makeText(this@MpvPlayerActivity, "再按一次返回", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.controls.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun initMpv() {
        // 调试入口：直接播 HTTP 源，跳过 SMB/代理，用于验证 mpv + 工具栏（Range 链路等价）
        val debugUrl = intent.getStringExtra("debug_url")
        if (!debugUrl.isNullOrEmpty()) {
            videoNames = listOf(debugUrl.substringAfterLast('/').substringBefore('?'))
            currentIndex = 0
        }
        val surfaceView = binding.mpvSurface
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                android.util.Log.i("MpvPlayer", "surfaceCreated -> attachSurface")
                mpv?.attachSurface(holder.surface)
                surfaceReady = true
                // 挂起的 loadfile（SMB register 比 surface 先完成时）在这里补发
                pendingLoad?.let { (url, resumeMs) ->
                    pendingLoad = null
                    doLoadFile(url, resumeMs)
                }
                if (videoNames.isNotEmpty() && pendingLoad == null && currentToken == null) loadCurrent()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                // 关键：Activity 正在销毁/返回时不要 detachSurface——libmpv 的 vo 线程仍持有
                // 这个 surface，detach 会把 native window 置 0，vo 线程随后断言 WinID!=0 失败
                // 触发 SIGABRT，表现为「返回主页后过一会整个应用跳出」。
                // 销毁路径交给 onDestroy 里的 mpv.destroy() 统一清理。
                if (destroyed || isFinishing) return
                mpv?.detachSurface()
            }
        })

        try {
            val instance = MPVLib.create(this)!!
            mpv = instance
            // 硬解(MediaCodec)在这个 AAR + HTTP 流下拿不到 native_window（video=eof 无画面）。
            // 软解不依赖 native_window，兼容性也更好（TS 里 MPEG-2/AC3 等都能解）。
            instance.setOptionString("hwdec", "no")
            instance.setOptionString("keep-open", "yes")
            instance.setOptionString("sub-auto", "no")
            instance.setOptionString("osd-level", "0") // 关闭 mpv 原生 OSD，避免与应用工具栏重叠
            instance.init()
            addMpvObserver(instance)
            proxy = SmbHttpProxy.create().also { it.start() }
            if (!tickerPosted) {
                tickerPosted = true
                handler.post(ticker)
            }
        } catch (t: Throwable) {
            binding.statusText.text = "mpv 初始化失败：${t.message}"
            binding.statusText.visibility = android.view.View.VISIBLE
            binding.loading.visibility = android.view.View.GONE
        }
    }

    private fun addMpvObserver(instance: MPVLib) {
        instance.addObserver(object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {}
            override fun eventProperty(property: String, value: Double) {}
            override fun eventProperty(property: String, value: String) {}
            override fun event(event: Int) {
                if (destroyed) return // 销毁中忽略 mpv 内部线程的回调，避免延迟崩溃
                if (event != MPVLib.MpvEvent.MPV_EVENT_END_FILE) return
                // loadfile replace 切集时旧文件也会发 END_FILE，必须忽略，
                // 否则点播放列表选一集会连锁跳到后面的集。
                if (suppressEndFile) return
                val now = System.currentTimeMillis()
                if (now - lastEndFileMs < 1500L) return // 短片 eof 连锁节流
                lastEndFileMs = now
                // 自然播完自动下一集
                if (videoNames.size > 1 && currentIndex < videoNames.lastIndex) {
                    runOnUiThread {
                        currentIndex++
                        loadCurrent()
                    }
                }
            }
        })
    }

    private fun loadCurrent() {
        if (videoNames.isEmpty() || currentIndex !in videoNames.indices) return
        val m = mpv ?: return
        val name = videoNames[currentIndex]

        // 调试：直接 HTTP 源，不经过 SMB 代理
        val debugUrl = intent.getStringExtra("debug_url")
        if (!debugUrl.isNullOrEmpty()) {
            binding.tvTitle.text = name
            if (surfaceReady) doLoadFile(debugUrl, 0L)
            else pendingLoad = debugUrl to 0L
            return
        }

        val p = proxy ?: return

        val path = if (directory.isBlank()) name else directory.trim('/') + "/" + name
        currentPath = path
        binding.tvTitle.text = name
        binding.loading.visibility = android.view.View.VISIBLE
        binding.statusText.visibility = android.view.View.GONE
        lastSaveMs = 0L

        // register() 内部 openAt 是网络操作，必须在后台线程做，否则 NetworkOnMainThreadException
        Thread {
            if (destroyed) return@Thread
            val token = runCatching { p.register(session, share, path) }.getOrElse { e ->
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    binding.loading.visibility = android.view.View.GONE
                    binding.statusText.text = "打开 SMB 文件失败：${e.message}"
                    binding.statusText.visibility = android.view.View.VISIBLE
                }
                return@Thread
            }
            runOnUiThread {
                if (destroyed) { p.unregister(token); return@runOnUiThread }
                currentToken?.let { p.unregister(it) }
                currentToken = token
                val resumeMs = PlaybackProgressRepository.get(server.id, share, path)?.resumePositionMs ?: 0L
                val url = p.url(token)
                android.util.Log.i("MpvPlayer", "register done, surfaceReady=$surfaceReady, pendingLoad=$pendingLoad")
                if (surfaceReady) doLoadFile(url, resumeMs)
                else pendingLoad = url to resumeMs
            }

            // 同名字幕（mpv 自带 libass 渲染，比自绘层效果好）
            if (destroyed) return@Thread
            val base = name.substringBeforeLast('.')
            val subName = listOf("srt", "ass", "ssa", "vtt")
                .mapNotNull { ext ->
                    try {
                        session.list(share, directory).firstOrNull {
                            !it.isDirectory && it.name.substringBeforeLast('.')
                                .equals(base, ignoreCase = true) &&
                                it.name.substringAfterLast('.').equals(ext, ignoreCase = true)
                        }?.name
                    } catch (_: Exception) { null }
                }.firstOrNull()
            if (!subName.isNullOrEmpty() && !destroyed) {
                val subPath = if (directory.isBlank()) subName else directory.trim('/') + "/" + subName
                runCatching {
                    val subToken = p.register(session, share, subPath)
                    m.command(arrayOf("sub-add", p.url(subToken), "select"))
                }
            }
        }.start()
    }

    /** 真正下发 loadfile：设置 start 并播放。要求 surface 已 attach。 */
    private fun doLoadFile(url: String, resumeMs: Long) {
        val m = mpv ?: return
        android.util.Log.i("MpvPlayer", "doLoadFile surfaceReady=$surfaceReady url=$url")
        // 切集/列表点选期间屏蔽 END_FILE，避免 loadfile replace 的旧文件结束事件连锁跳集
        suppressEndFile = true
        loadFileAtMs = System.currentTimeMillis()
        if (resumeMs > 0L) m.setOptionString("start", (resumeMs / 1000.0).toString())
        else m.setOptionString("start", "0")
        // 倍速在切集时继承常驻值（与原版一致）
        isLongPressBoost = false
        m.setPropertyDouble("speed", normalSpeed.toDouble())
        updateSpeedButton()
        m.command(arrayOf("loadfile", url, "replace"))
    }

    /** reex 风格播放列表：右侧半透明抽屉，序号 + 文件名，当前项蓝色高亮 */
    private fun showPlaylist() {
        if (videoNames.isEmpty()) return
        val d = Dialog(this, R.style.MpvSettingsDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_mpv_playlist)
        d.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setDimAmount(0f)
        }
        val scrim = d.findViewById<android.view.View>(R.id.playlist_scrim)
        scrim.setOnClickListener { d.dismiss() }
        d.setOnDismissListener { setControlsVisible(true) }
        setControlsVisible(false)
        // 面板宽度 = 屏幕 55%，右对齐
        val dm = android.util.DisplayMetrics()
        resources.displayMetrics.let { dm.setTo(it) }
        d.findViewById<android.view.View>(R.id.playlist_panel)?.layoutParams?.let { lp ->
            lp.width = (dm.widthPixels * 0.55f).toInt()
            d.findViewById<android.view.View>(R.id.playlist_panel)?.layoutParams = lp
        }

        val container = d.findViewById<LinearLayout>(R.id.playlist_container)
        videoNames.forEachIndexed { idx, name ->
            val row = TextView(this).apply {
                text = "${idx + 1}.  $name"
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE // 长文件名中间省略（与顶栏一致）
                setTextColor(if (idx == currentIndex) getColor(R.color.player_accent) else Color.WHITE)
                background = if (idx == currentIndex) getDrawable(R.drawable.bg_hint_pill) else null
                gravity = android.view.Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 4
                layoutParams = lp
                setPadding(12, 20, 12, 20)
                isClickable = true
                setOnClickListener {
                    d.dismiss()
                    if (idx != currentIndex) {
                        currentIndex = idx
                        loadCurrent()
                    }
                }
            }
            container.addView(row)
        }
        d.show()
        // 打开时滚动到正在播放的条目（布局测量完成后再滚）
        d.findViewById<android.widget.ScrollView>(R.id.playlist_scroller)?.post {
            container.getChildAt(currentIndex)?.let { row ->
                val scroller = d.findViewById<android.widget.ScrollView>(R.id.playlist_scroller) ?: return@post
                // 让当前项居中：行顶部 - (可视高度 - 行高)/2
                val target = row.top - (scroller.height - row.height) / 2
                scroller.smoothScrollTo(0, target.coerceAtLeast(0))
            }
        }
    }

    /** 更多按钮：打开 reex 风格设置面板 */
    private fun showMoreMenu() {
        val m = mpv ?: return
        if (settingsPanel == null) {
            settingsPanel = MpvSettingsPanel(
                activity = this,
                mpv = m,
                onSpeedChanged = { spd ->
                    normalSpeed = spd
                    SettingsRepository.normalSpeed = spd
                    updateSpeedButton()
                },
                getSpeed = { normalSpeed },
                onClosed = { setControlsVisible(true) },
            )
        }
        setControlsVisible(false) // 面板打开时隐藏控制栏，避免半透明面板透出控制栏重叠
        settingsPanel?.show()
    }

    /** 常驻倍速选择：0.25 步进 + 常用倍速快捷，应用到当前和后续视频 */
    private fun showNormalSpeedDialog() {
        val view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_speed_control, null)
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.player_speed_title)
            .setView(view)
            .setNegativeButton("取消", null)
            .create()

        // 与透明设置面板一致的 0.25 步进：index 0=0.25x .. 15=4.0x
        val step = 0.25f
        val maxIdx = 15
        val ticks = floatArrayOf(0.5f, 1.0f, 1.75f, 2.0f, 4.0f)
        val tickIds = listOf(R.id.speed_tick_0, R.id.speed_tick_1, R.id.speed_tick_2, R.id.speed_tick_3, R.id.speed_tick_4)
        val presets = floatArrayOf(0.3f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f)
        val presetIds = listOf(
            R.id.speed_preset_0, R.id.speed_preset_1, R.id.speed_preset_2, R.id.speed_preset_3,
            R.id.speed_preset_4, R.id.speed_preset_5, R.id.speed_preset_6,
        )
        val seek = view.findViewById<android.widget.SeekBar>(R.id.speed_seek)
        val tvValue = view.findViewById<TextView>(R.id.speed_value)

        fun applyValue(sp: Float, idx: Int) {
            val i = idx.coerceIn(0, maxIdx)
            seek.progress = i
            tvValue.text = String.format("%.2fx", sp)
            normalSpeed = sp
            SettingsRepository.normalSpeed = sp
            if (!isLongPressBoost) mpv?.setPropertyDouble("speed", sp.toDouble())
            updateSpeedButton()
            tickIds.forEachIndexed { ti, id ->
                val tv = view.findViewById<TextView>(id) ?: return@forEachIndexed
                val hit = kotlin.math.abs(sp - ticks[ti]) < 0.01f
                tv.setTextColor(if (hit) getColor(R.color.player_accent) else getColor(R.color.player_control_text_dim))
            }
            presetIds.forEachIndexed { pi, id ->
                val tv = view.findViewById<TextView>(id) ?: return@forEachIndexed
                val hit = kotlin.math.abs(sp - presets[pi]) < 0.01f
                tv.setTextColor(if (hit) getColor(R.color.player_accent) else getColor(R.color.player_control_text))
            }
        }
        fun apply(idx: Int) {
            val i = idx.coerceIn(0, maxIdx)
            applyValue(step * (i + 1), i)
        }

        seek.progress = ((normalSpeed / step).roundToInt() - 1).coerceIn(0, maxIdx)
        apply(seek.progress)
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) apply(p) }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        view.findViewById<android.view.View>(R.id.speed_minus).setOnClickListener { apply(seek.progress - 1) }
        view.findViewById<android.view.View>(R.id.speed_plus).setOnClickListener { apply(seek.progress + 1) }
        tickIds.forEachIndexed { i, id -> view.findViewById<android.view.View>(id)?.setOnClickListener { applyValue(ticks[i], (ticks[i] / step).roundToInt() - 1) } }
        presetIds.forEachIndexed { i, id -> view.findViewById<android.view.View>(id)?.setOnClickListener { applyValue(presets[i], (presets[i] / step).roundToInt() - 1) } }
        d.show()
    }

    /** 长按临时倍速：用设置页的「长按倍速」值，松手恢复到常驻倍速 */
    private fun startSpeedBoost() {
        val m = mpv ?: return
        isLongPressBoost = true
        val boost = SettingsRepository.playbackSpeed
        m.setPropertyDouble("speed", boost.toDouble())
        binding.tvSpeedHint.text = "${boost}x"
        binding.tvSpeedHint.visibility = android.view.View.VISIBLE
    }

    private fun stopSpeedBoost() {
        val m = mpv ?: return
        isLongPressBoost = false
        m.setPropertyDouble("speed", normalSpeed.toDouble())
        binding.tvSpeedHint.visibility = android.view.View.GONE
    }

    private fun updateSpeedButton() {
        binding.btnSpeed.text = getString(R.string.settings_speed_value, normalSpeed)
    }

    /** 相对快退/快进（毫秒），mpv seek relative 以秒为单位；手势来源用居中反馈替代 toast */
    private fun seekBy(deltaMs: Long, fromGesture: Boolean = false) {
        val m = mpv ?: return
        m.command(arrayOf("seek", (deltaMs / 1000.0).toString(), "relative"))
        if (fromGesture) {
            val target = currentPositionMs() + deltaMs
            binding.gestureFeedback.text =
                "${if (deltaMs > 0) "+" else "-"}${abs(deltaMs) / 1000} 秒\n${formatTime(target)}"
            binding.gestureFeedback.visibility = android.view.View.VISIBLE
            handler.removeCallbacks(hideGestureFeedbackRunnable)
            handler.postDelayed(hideGestureFeedbackRunnable, 450L)
        } else {
            Toast.makeText(this, if (deltaMs > 0) "快进 ${deltaMs / 1000} 秒" else "快退 ${-deltaMs / 1000} 秒", Toast.LENGTH_SHORT).show()
        }
    }

    /** 切换上一个/下一个视频，越界不动 */
    private fun switchVideo(target: Int) {
        if (target !in videoNames.indices) {
            Toast.makeText(this, if (target < 0) "已经是第一个" else "已经是最后一个", Toast.LENGTH_SHORT).show()
            return
        }
        currentIndex = target
        Toast.makeText(this, "播放：${videoNames[target]}", Toast.LENGTH_SHORT).show()
        loadCurrent()
    }

    /**
     * 导出当前帧：用 Android PixelCopy 抓 SurfaceView 画面，按设置写回 SMB 视频目录
     * 或保存到系统相册（Pictures/SMBPlayer）。PNG 无损。
     *
     * 不用 mpv screenshot-to-file：这个 AAR 精简版没带 libavcodec 图像编码器，写不出来。
     */
    private fun saveCurrentFrame() {
        val m = mpv ?: return
        android.util.Log.i("MpvPlayer", "saveCurrentFrame clicked")
        val name = videoNames.getOrNull(currentIndex)?.substringBeforeLast('.').orEmpty()
        val safeName = sanitizeFileName(name, 60)
        val posSec = runCatching { m.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
        val timeTag = formatTime((posSec * 1000).toLong()).replace(':', '-')
        val fileName = "${if (safeName.isEmpty()) "frame" else safeName}_$timeTag.png"
        val view = binding.mpvSurface
        if (view.width <= 0 || view.height <= 0) {
            Toast.makeText(this, "截图失败：画面未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        // SurfaceView 的帧异步提交，紧接截帧常遇「no previously queued frames」，失败时延迟重试。
        // 另外某些模拟器（如 nox）PixelCopy 回调可能永不触发，加超时兜底避免「点了没反应」。
        var finished = false
        val timeoutRunnable = Runnable {
            if (!finished) {
                finished = true
                android.util.Log.w("MpvPlayer", "PixelCopy 超时（模拟器可能不支持）")
                runOnUiThread { Toast.makeText(this, "截图失败：设备不支持抓帧（PixelCopy 超时）", Toast.LENGTH_SHORT).show() }
            }
        }
        handler.postDelayed(timeoutRunnable, 2000L)

        fun tryCapture(attempt: Int) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(view, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) {
                    android.util.Log.w("MpvPlayer", "PixelCopy 失败 result=$result attempt=$attempt")
                    if (attempt < 3) {
                        handler.postDelayed({ tryCapture(attempt + 1) }, 150L)
                    } else {
                        if (!finished) {
                            finished = true
                            handler.removeCallbacks(timeoutRunnable)
                            runOnUiThread { Toast.makeText(this, "截图失败（PixelCopy $result）", Toast.LENGTH_SHORT).show() }
                        }
                    }
                    return@request
                }
                if (!finished) {
                    finished = true
                    handler.removeCallbacks(timeoutRunnable)
                    saveFrameBitmap(bitmap, fileName)
                }
            }, handler)
        }
        tryCapture(1)
    }

    /** 把抓到的帧按设置保存：SMB 视频目录（重名加序号）或系统相册 */
    private fun saveFrameBitmap(bitmap: Bitmap, fileName: String) {
        Thread {
            try {
                val bytes = ByteArrayOutputStream().use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.toByteArray()
                }
                runOnUiThread { bitmap.recycle() }
                if (!SettingsRepository.frameSaveToGallery) {
                    // 关闭「存相册」= 写回 SMB 视频所在目录，重名加序号
                    var finalName = fileName
                    if (session.fileExists(share, if (directory.isBlank()) finalName else directory.trim('/') + "/" + finalName)) {
                        val stem = fileName.substringBeforeLast('.')
                        for (seq in 1..99) {
                            val candidate = "${stem}_$seq.png"
                            val p = if (directory.isBlank()) candidate else directory.trim('/') + "/" + candidate
                            if (!session.fileExists(share, p)) { finalName = candidate; break }
                        }
                    }
                    val p = if (directory.isBlank()) finalName else directory.trim('/') + "/" + finalName
                    session.writeFile(share, p, bytes)
                    runOnUiThread { Toast.makeText(this, "已保存到视频目录：$finalName", Toast.LENGTH_SHORT).show() }
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SMBPlayer")
                        }
                    }
                    val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) throw java.io.IOException("无法创建相册条目")
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw java.io.IOException("无法写入相册")
                    runOnUiThread { Toast.makeText(this, "已保存到相册：$fileName", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "截图失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun saveProgress(positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L || durationMs <= 0L || currentPath.isEmpty()) return
        PlaybackProgressRepository.save(server.id, share, currentPath, positionMs, durationMs)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$m:${s.toString().padStart(2, '0')}"
    }

    override fun onStop() {
        super.onStop()
        // 与原版一致：关闭「后台播放」时，息屏/切后台即暂停，避免后台出声
        if (!SettingsRepository.backgroundPlay) {
            mpv?.command(arrayOf("set", "pause", "yes"))
        }
        if (isLongPressBoost) stopSpeedBoost()
        val posMs = currentPositionMs()
        val durMs = ((runCatching { mpv?.getPropertyDouble("duration") }.getOrNull() ?: 0.0) * 1000).toLong()
        saveProgress(posMs, durMs)
    }

    override fun onDestroy() {
        destroyed = true // 先拦截所有异步回调，再开始销毁
        val posMs = ((runCatching { mpv?.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0) * 1000).toLong()
        val durMs = ((runCatching { mpv?.getPropertyDouble("duration") }.getOrNull() ?: 0.0) * 1000).toLong()
        saveProgress(posMs, durMs)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hideGestureFeedbackRunnable)
        tickerPosted = false
        // 先停播放再 destroy，避免 mpv 内部线程仍在读 HTTP 流时句柄被 proxy 关闭
        mpv?.let { runCatching { it.command(arrayOf("set", "pause", "yes")); it.command(arrayOf("stop")) } }
        currentToken?.let { proxy?.unregister(it) }
        runCatching { mpv?.destroy() }
        mpv = null
        proxy?.stop()
        proxy = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MpvPlayer"
        /** 快退/快进步长，与原版 PlayerActivity 一致 */
        private const val SEEK_STEP_MS = 5_000L

        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SHARE = "share"
        const val EXTRA_DIR = "dir"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TARGET_NAME = "target_name"

        fun intent(
            context: android.content.Context,
            serverId: String,
            share: String,
            directory: String,
            index: Int,
            targetName: String? = null,
        ): Intent = Intent(context, MpvPlayerActivity::class.java)
            .putExtra(EXTRA_SERVER_ID, serverId)
            .putExtra(EXTRA_SHARE, share)
            .putExtra(EXTRA_DIR, directory)
            .putExtra(EXTRA_INDEX, index)
            .apply { if (!targetName.isNullOrEmpty()) putExtra(EXTRA_TARGET_NAME, targetName) }
    }
}
