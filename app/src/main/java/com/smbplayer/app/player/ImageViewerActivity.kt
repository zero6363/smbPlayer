package com.smbplayer.app.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.app.R
import com.smbplayer.app.data.ServerRepository
import com.smbplayer.app.data.SmbServer
import com.smbplayer.app.data.VideoFormats
import com.smbplayer.app.databinding.ActivityImageViewerBinding
import com.smbplayer.app.databinding.ItemImagePageBinding
import com.smbplayer.app.smb.SmbGateway
import com.smbplayer.app.smb.SmbReadHandle
import com.smbplayer.app.smb.SmbSession
import com.smbplayer.app.util.joinPath
import com.smbplayer.app.util.logError
import com.smbplayer.app.util.logInfo
import com.smbplayer.app.util.sanitizeFileName
import com.smbplayer.app.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 图片查看页：左右滑动浏览 [directory] 目录下的所有图片。
 *
 * - 图片经 SMB 流式读取后整字节解码（API 28+ 走 ImageDecoder，支持 GIF/WebP 动图；
 *   更低版本退回 BitmapFactory，动图只显示首帧）；
 * - 双击放大、双指缩放、放大后可拖拽平移；未放大时左右滑动翻页；
 * - 「保存」把原图字节写入相册 Pictures/SMBPlayer，保留原始格式与画质。
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private lateinit var server: SmbServer
    private lateinit var session: SmbSession

    private var shareName: String = ""
    private var directory: String = ""

    /** 当前目录下的全部图片文件名（已排序） */
    private val names = mutableListOf<String>()
    private var index = 0

    private lateinit var adapter: PagerAdapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var saving = false
    private var deleting = false

    private val hideHintRunnable = Runnable { binding.tvHint.visibility = View.GONE }
    private val hideControlsRunnable = Runnable { binding.topBar.visibility = View.GONE }

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            index = position
            updateHeader()
        }
    }

    private fun showHint(text: String) {
        binding.tvHint.text = text
        binding.tvHint.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHintRunnable)
        mainHandler.postDelayed(hideHintRunnable, HINT_HIDE_DELAY_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logInfo("ImageViewerActivity onCreate")
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
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

        adapter = PagerAdapter()
        binding.pager.adapter = adapter
        binding.pager.registerOnPageChangeCallback(pageCallback)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnDelete.setOnClickListener { confirmDeleteCurrent() }
        binding.btnSave.setOnClickListener { saveCurrentImage() }
        binding.topBar.setOnClickListener { /* 吞掉点击，不穿透到图片 */ }

        if (shareName.isBlank()) {
            logError("共享名为空")
            toast(R.string.no_images)
            finish()
            return
        }
        loadImages()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        binding.pager.unregisterOnPageChangeCallback(pageCallback)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 目录加载

    private fun pathOf(name: String) = joinPath(directory, name)

    private fun loadImages() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                logInfo("加载图片列表: 共享=$shareName 目录=$directory")
                val list = withContext(Dispatchers.IO) {
                    session.list(shareName, directory)
                        .asSequence()
                        .filter { !it.isDirectory && VideoFormats.isImage(it.name) }
                        .map { it.name }
                        .sortedWith(String.CASE_INSENSITIVE_ORDER)
                        .toList()
                }
                binding.progress.visibility = View.GONE
                if (list.isEmpty()) {
                    logError("图片列表为空: 共享=$shareName 目录=$directory")
                    toast(R.string.no_images)
                    finish()
                    return@launch
                }
                names.clear()
                names.addAll(list)
                index = index.coerceIn(0, names.lastIndex)
                adapter.submit(names)
                binding.pager.setCurrentItem(index, false)
                updateHeader()
                showHint(getString(R.string.image_zoom_hint))
                logInfo("图片列表加载完成: ${list.size} 张")
            } catch (e: Exception) {
                binding.progress.visibility = View.GONE
                logError("加载图片列表失败: 共享=$shareName 目录=$directory", e)
                toast(getString(R.string.image_load_failed, e.message ?: e.toString()))
                finish()
            }
        }
    }

    private fun updateHeader() {
        binding.tvTitle.text = names.getOrNull(index).orEmpty()
        binding.tvIndex.text = getString(R.string.image_index_format, index + 1, names.size)
    }

    // ------------------------------------------------------------------ 读取与解码

    /** 读整个远程文件到内存（有大小上限，防止超大的图把内存撑爆） */
    private fun readAllBytes(path: String): ByteArray {
        SmbReadHandle(session, shareName, path).use { handle ->
            handle.openAt(0)
            val len = handle.length
            if (len <= 0L) throw IOException("图片为空或不存在")
            if (len > MAX_IMAGE_BYTES) throw IOException(getString(R.string.image_too_large))
            val buf = ByteArray(len.toInt())
            var read = 0
            while (read < buf.size) {
                val n = handle.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            if (read != buf.size) throw IOException("图片读取不完整（$read/${buf.size}）")
            return buf
        }
    }

    /** 解码成 Drawable；API 28+ 用 ImageDecoder（支持 GIF/WebP 动图），否则退回 BitmapFactory */
    private fun decodeImage(data: ByteArray): Drawable {
        val maxTexture = maxTextureSize()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(data))
                return android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    decoder.setTargetSampleSize(
                        sampleSizeOf(info.size.width, info.size.height, maxTexture)
                    )
                }
            } catch (e: IOException) {
                // 该格式 ImageDecoder 解不了（极少见），继续走 BitmapFactory
                logInfo("ImageDecoder 解码失败，退回 BitmapFactory: ${e.message}")
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法解码该图片")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeOf(bounds.outWidth, bounds.outHeight, maxTexture)
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)?.let {
            BitmapDrawable(resources, it)
        } ?: throw IOException("无法解码该图片")
    }

    /** 解码目标上限：两倍屏幕长边、封顶 4096 */
    private fun maxTextureSize(): Int {
        val dm = resources.displayMetrics
        return (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceAtMost(4096)
    }

    private fun sampleSizeOf(width: Int, height: Int, maxTexture: Int): Int {
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfW / sample >= maxTexture && halfH / sample >= maxTexture) {
            sample *= 2
        }
        return sample
    }

    private suspend fun loadImageDrawable(name: String): Drawable =
        withContext(Dispatchers.IO) {
            val data = readAllBytes(pathOf(name))
            decodeImage(data)
        }

    // ------------------------------------------------------------------ 保存到相册

    private fun saveCurrentImage() {
        if (saving) return
        val name = names.getOrNull(index) ?: return
        saving = true
        binding.btnSave.isEnabled = false
        binding.btnSave.alpha = 0.4f
        lifecycleScope.launch {
            try {
                val msg = withContext(Dispatchers.IO) {
                    val bytes = readAllBytes(pathOf(name))
                    val filename = writeToGallery(bytes, name)
                    getString(R.string.image_saved, filename)
                }
                showHint(msg)
                toast(msg)
                logInfo("已保存图片到相册 Pictures/SMBPlayer: $name")
            } catch (e: CancellationException) {
                throw e // 用户离开查看页：正常取消，别提示「保存失败」
            } catch (e: Exception) {
                logError("保存图片失败: $name", e)
                val msg = getString(R.string.image_save_failed, e.message ?: "未知错误")
                showHint(msg)
                toast(msg)
            } finally {
                saving = false
                binding.btnSave.isEnabled = true
                binding.btnSave.alpha = 1f
            }
        }
    }

    // ------------------------------------------------------------------ 删除当前图片

    /** 删除前弹确认框，防止误触 */
    private fun confirmDeleteCurrent() {
        val name = names.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.image_delete_title)
            .setMessage(getString(R.string.image_delete_message, name))
            .setPositiveButton(R.string.delete_confirm) { _, _ -> deleteCurrent(name) }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    /**
     * 删除当前图片，成功后自动跳到相邻图片（删最后一张则显示前一张）；
     * 目录里没有图片了就直接退出查看页。删除期间按钮置灰防连点。
     */
    private fun deleteCurrent(name: String) {
        if (deleting) return
        deleting = true
        binding.btnDelete.isEnabled = false
        binding.btnDelete.alpha = 0.4f
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    logInfo("删除图片: ${pathOf(name)}")
                    session.delete(shareName, pathOf(name), false)
                }
                imageCache.remove(name)
                names.removeAt(index)
                adapter.submit(names)
                val msg = getString(R.string.deleted, name)
                if (names.isEmpty()) {
                    toast(msg)
                    finish()
                    return@launch
                }
                val next = index.coerceIn(0, names.lastIndex)
                binding.pager.setCurrentItem(next, false)
                updateHeader()
                showHint(msg)
                toast(msg)
                logInfo("图片已删除，剩余 ${names.size} 张")
            } catch (e: CancellationException) {
                throw e // 用户离开查看页：正常取消，别提示「删除失败」
            } catch (e: Exception) {
                logError("删除图片失败: $name", e)
                val msg = getString(R.string.player_delete_failed, e.message ?: "未知错误")
                showHint(msg)
                toast(msg)
            } finally {
                deleting = false
                binding.btnDelete.isEnabled = true
                binding.btnDelete.alpha = 1f
            }
        }
    }

    /** 原图字节直接写入相册，保留原始格式与画质；返回最终文件名 */
    private fun writeToGallery(bytes: ByteArray, name: String): String {
        val ext = VideoFormats.extOf(name)
        val base = sanitizeFileName(name.substringBeforeLast('.', missingDelimiterValue = name))
        val filename = if (ext.isEmpty()) base else "$base.$ext"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(ext))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SMBPlayer")
            }
        }
        val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("相册插入失败")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IOException("相册打开失败")
        return filename
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        else -> "image/*"
    }

    // ------------------------------------------------------------------ 控制条显隐

    private fun toggleControls() {
        if (binding.topBar.visibility == View.VISIBLE) {
            binding.topBar.visibility = View.GONE
            mainHandler.removeCallbacks(hideControlsRunnable)
        } else {
            binding.topBar.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideControlsRunnable)
            mainHandler.postDelayed(hideControlsRunnable, CONTROL_HIDE_DELAY_MS)
        }
    }

    // ------------------------------------------------------------------ 翻页适配器

    private inner class PagerAdapter : RecyclerView.Adapter<PagerAdapter.PageHolder>() {

        private val items = mutableListOf<String>()

        fun submit(list: List<String>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(
                ItemImagePageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

        override fun onBindViewHolder(holder: PageHolder, position: Int) =
            holder.bind(items[position])

        override fun onViewRecycled(holder: PageHolder) {
            holder.cancelLoad()
        }

        inner class PageHolder(private val b: ItemImagePageBinding) :
            RecyclerView.ViewHolder(b.root) {

            private var loadJob: Job? = null

            fun bind(name: String) {
                cancelLoad()
                b.image.onTap = { toggleControls() }
                b.image.setImageDrawable(null)
                b.progress.visibility = View.VISIBLE
                b.error.visibility = View.GONE

                val cached = imageCache.get(name)
                if (cached != null) {
                    showImage(cached)
                    return
                }
                loadJob = lifecycleScope.launch {
                    try {
                        val drawable = loadImageDrawable(name)
                        imageCache.put(name, drawable)
                        showImage(drawable)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logError("图片加载失败: $name", e)
                        b.progress.visibility = View.GONE
                        b.error.text = getString(R.string.image_load_failed, e.message ?: "未知错误")
                        b.error.visibility = View.VISIBLE
                    }
                }
            }

            private fun showImage(drawable: Drawable) {
                b.progress.visibility = View.GONE
                b.error.visibility = View.GONE
                b.image.setImageDrawable(drawable)
                // GIF / 动画 WebP：装上就开始播
                (drawable as? Animatable)?.start()
            }

            fun cancelLoad() {
                loadJob?.cancel()
                loadJob = null
            }
        }
    }

    companion object {
        private const val EXTRA_SERVER_ID = "extra_server_id"
        private const val EXTRA_SHARE = "extra_share"
        private const val EXTRA_DIR = "extra_dir"
        private const val EXTRA_INDEX = "extra_index"

        private const val HINT_HIDE_DELAY_MS = 2_000L
        private const val CONTROL_HIDE_DELAY_MS = 4_000L

        /** 单张图片读取上限，超了直接提示，避免内存溢出 */
        private const val MAX_IMAGE_BYTES = 30L * 1024 * 1024

        /** 已解码图片缓存（按字节计容，最多用堆的 1/8），来回滑动不重新读 SMB */
        private val imageCache = object : LruCache<String, Drawable>(
            (Runtime.getRuntime().maxMemory() / 8).toInt()
        ) {
            override fun sizeOf(key: String, value: Drawable): Int {
                val bitmap = (value as? BitmapDrawable)?.bitmap
                return bitmap?.byteCount
                    ?: (value.intrinsicWidth.coerceAtLeast(1) * value.intrinsicHeight.coerceAtLeast(1) * 4)
            }
        }

        fun intent(
            context: Context,
            serverId: String,
            share: String,
            directory: String,
            index: Int,
        ): Intent = Intent(context, ImageViewerActivity::class.java)
            .putExtra(EXTRA_SERVER_ID, serverId)
            .putExtra(EXTRA_SHARE, share)
            .putExtra(EXTRA_DIR, directory)
            .putExtra(EXTRA_INDEX, index)
    }
}
