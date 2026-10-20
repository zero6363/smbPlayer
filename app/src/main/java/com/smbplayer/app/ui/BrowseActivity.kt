package com.smbplayer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.app.R
import com.smbplayer.app.data.FavoritePath
import com.smbplayer.app.data.FavoriteRepository
import com.smbplayer.app.data.ServerRepository
import com.smbplayer.app.data.SettingsRepository
import com.smbplayer.app.data.SmbServer
import com.smbplayer.app.data.SortOptions
import com.smbplayer.app.data.VideoFormats
import com.smbplayer.app.databinding.ActivityBrowseBinding
import com.smbplayer.app.databinding.DialogSharePickerBinding
import com.smbplayer.app.player.ImageViewerActivity
import com.smbplayer.app.player.PlayerActivity
import com.smbplayer.app.smb.SmbEntry
import com.smbplayer.app.smb.SmbGateway
import com.smbplayer.app.smb.SmbSession
import com.smbplayer.app.smb.SmbShareLister
import com.smbplayer.app.util.describe
import com.smbplayer.app.util.isAlive
import com.smbplayer.app.util.joinPath
import com.smbplayer.app.util.logError
import com.smbplayer.app.util.logInfo
import com.smbplayer.app.util.parentPath
import com.smbplayer.app.util.SmbPath
import com.smbplayer.app.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowseBinding
    private lateinit var adapter: EntryAdapter

    private lateinit var server: SmbServer
    private lateinit var session: SmbSession

    private var shareName: String = ""
    private var currentPath: String = ""
    private var entries: List<SmbEntry> = emptyList()

    private lateinit var layoutManager: LinearLayoutManager

    /** 每个目录里最后点进去的文件夹位置：父目录路径 → (序号, 名称, 距列表顶部偏移) */
    private data class ScrollPoint(val position: Int, val name: String, val offset: Int)

    /** 加载 targetPath 完成后要恢复的滚动位置 */
    private data class PendingRestore(val targetPath: String, val point: ScrollPoint)

    private val folderScroll = HashMap<String, ScrollPoint>()
    private var pendingRestore: PendingRestore? = null

    /** smbj 没有共享枚举接口，扫描时逐个试连这些常见名字 */
    private val candidateShares = listOf(
        // 通用
        "Media", "media", "Movies", "movies", "Movie", "Video", "Videos", "videos",
        "Share", "share", "Shares", "Public", "public", "Data", "data", "NAS", "nas",
        "Storage", "storage", "Files", "files", "Home", "home", "Users", "users",
        "资料", "视频", "影视", "电影", "媒体", "共享", "备份", "下载",
        "USB", "usbdisk", "harddisk", "archive", "backup", "Download", "download",
        "Music", "music", "Photos", "photos", "Pictures", "camera", "DCIM",
        "sda1", "sdb1", "Volume1", "volume1", "Homes",
        // 群辉 Synology 常用
        "video1", "music1", "photo1", "surveillance", "NetBackup", "homes1",
        // 威联通 QNAP 常用
        "Multimedia", "multimedia", "Web", "web", "Recordings", "Container",
        // 其他品牌 / 常见自定义
        "Entertainment", "entertainment", "shared", "Shared", "smb", "SMB",
        "disk", "Disk", "volume", "Volume", "hdd", "HDD", "usb1", "usb2",
        "Documents", "documents", "Movies2",
        // Windows 共享：盘符共享 + 系统常见共享 + 常见自定义名
        "C", "D", "E", "F", "G", "H",
        "ADMIN$", "C$", "D$", "E$", "print$", "fax$",
        "SharedFolder", "SharedDocs", "MyShare", "MyShareFolder",
        "WinShare", "winshare", "Temp", "temp", "Test", "test",
    )

    /** 主机名本身也常被用作共享名，扫描时一并试连 */
    private val hostAsShare: String
        get() = server.host.substringBefore('.').trim()

    private val openPlayer = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 播放页可能删过视频，回来时刷新
        if (shareName.isNotBlank()) loadDirectory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val loaded = serverId?.let { ServerRepository.get(it) }
        if (loaded == null) {
            finish()
            return
        }
        server = loaded
        session = SmbGateway.session(server)

        binding.toolbar.title = server.name.ifBlank { server.displayHost }
        binding.toolbar.inflateMenu(R.menu.menu_browse)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    loadDirectory()
                    true
                }
                R.id.action_play_all -> {
                    if (videoNames().isNotEmpty()) openPlayerAt(0) else toast(R.string.player_no_video)
                    true
                }
                R.id.action_view_images -> {
                    if (imageNames().isNotEmpty()) openImageViewerAt(0)
                    else toast(R.string.no_images)
                    true
                }
                R.id.action_add_favorite -> {
                    addFavorite()
                    true
                }
                R.id.action_close -> {
                    // 一键回首页：不用一级一级返回到共享根
                    finish()
                    true
                }
                R.id.action_switch_share -> {
                    switchShare()
                    true
                }
                R.id.action_sort -> {
                    showSortDialog()
                    true
                }
                else -> false
            }
        }

        adapter = EntryAdapter(
            onClick = { entry, view -> onEntryClick(entry, view) },
            onSelectionChanged = { count -> updateSelectionBar(count) },
        )
        layoutManager = LinearLayoutManager(this)
        binding.recycler.layoutManager = layoutManager
        binding.recycler.adapter = adapter

        binding.btnSelectAll.setOnClickListener { adapter.selectAll() }
        binding.btnCancelSelection.setOnClickListener { adapter.clearSelection() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 多选模式下先退出多选，再返回上级
                if (adapter.selectionMode) {
                    adapter.clearSelection()
                } else {
                    navigateUp()
                }
            }
        })

        // 重建（如切换系统夜间模式）时恢复到原目录，避免跳回初始路径
        val savedShare = savedInstanceState?.getString(STATE_SHARE)
        if (savedShare != null) {
            shareName = savedShare
            currentPath = savedInstanceState.getString(STATE_PATH).orEmpty()
            if (shareName.isBlank()) {
                showSharePicker()
            } else {
                connectThen { loadDirectory() }
            }
        } else {
            // 从收藏路径直达时，恢复上次浏览位置；
            // 没有收藏路径时，用服务器配置里粘贴完整路径解析出的直达目录
            val startPath = intent.getStringExtra(EXTRA_START_PATH)?.takeIf { it.isNotEmpty() }
                ?: server.startPath
            // 共享名优先用收藏时记录的那个（服务器配置里可能已清空）；
            // 收藏的是共享根本身（path 为空）时也一样要用
            val favoriteShare = intent.getStringExtra(EXTRA_START_SHARE)?.takeIf { it.isNotBlank() }
            shareName = normalizeShare(favoriteShare ?: server.share)
            if (startPath.isNotEmpty()) currentPath = startPath
            when {
                shareName.isNotBlank() -> connectThen { loadDirectory() }
                // 共享名也没有：自动扫描，扫到就直接进（单个）或列表选择（多个）
                else -> autoScanShares()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SHARE, shareName)
        outState.putString(STATE_PATH, currentPath)
    }

    // ------------------------------------------------------------------ 收藏路径

    private fun normalizeShare(name: String): String = name.trim().trim('/')

    private fun addFavorite() {
        if (shareName.isBlank()) {
            toast(R.string.share_required)
            return
        }
        val label = if (currentPath.isEmpty()) {
            shareName
        } else {
            "$shareName/$currentPath"
        }
        val favorite = FavoritePath(
            serverId = server.id,
            serverName = server.name.ifBlank { server.displayHost },
            shareName = shareName,
            path = currentPath,
            label = label,
        )
        if (FavoriteRepository.exists(server.id, shareName, currentPath)) {
            toast(R.string.favorite_already_exists)
            return
        }
        FavoriteRepository.save(favorite)
        toast(getString(R.string.favorite_added, label))
    }

    // ------------------------------------------------------------------ 导航

    private fun navigateUp() {
        if (currentPath.isEmpty()) {
            finish()
            return
        }
        val parent = parentPath(currentPath)
        // 返回上一级时滚回刚才进入的那个文件夹位置
        folderScroll[parent]?.let { pendingRestore = PendingRestore(parent, it) }
        currentPath = parent
        loadDirectory()
    }

    private fun onEntryClick(entry: SmbEntry, view: View) {
        if (entry.isDirectory) {
            // 记住它在当前列表里的位置，返回时好滚回来
            val position = entries.indexOfFirst { it.name == entry.name }
            folderScroll[currentPath] = ScrollPoint(position, entry.name, view.top)
            currentPath = joinPath(currentPath, entry.name)
            loadDirectory()
            return
        }
        if (VideoFormats.isVideo(entry.name)) {
            val index = videoNames().indexOfFirst { it.equals(entry.name, ignoreCase = true) }
            openPlayerAt(index.coerceAtLeast(0))
            return
        }
        if (VideoFormats.isImage(entry.name)) {
            val index = imageNames().indexOfFirst { it.equals(entry.name, ignoreCase = true) }
            openImageViewerAt(index.coerceAtLeast(0))
            return
        }
        toast(R.string.not_supported)
    }

    /**
     * 排序方式选择：文件名 / 大小 / 创建时间 × 升序 / 降序，选中即生效（无需再点确认）。
     * 目录始终排在文件前面。选择写回设置，下次进入沿用。
     */
    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.sort_name_asc),
            getString(R.string.sort_name_desc),
            getString(R.string.sort_size_asc),
            getString(R.string.sort_size_desc),
            getString(R.string.sort_created_asc),
            getString(R.string.sort_created_desc),
        )
        val checked = SortOptions.indexOf(SettingsRepository.sortField, SettingsRepository.sortAscending)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val (field, ascending) = SortOptions.LABELS[which]
                SettingsRepository.sortField = field
                SettingsRepository.sortAscending = ascending
                applySort()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    /** 按当前设置重排已加载的列表（不发网络请求） */
    private fun applySort() {
        if (entries.isEmpty()) return
        entries = SortOptions.sorted(
            entries,
            SettingsRepository.sortField,
            SettingsRepository.sortAscending,
        )
        adapter.submit(entries)
        updateHeader(entries)
        binding.recycler.scrollToPosition(0)
    }

    private fun videoNames(): List<String> =
        entries.asSequence()
            .filter { !it.isDirectory && VideoFormats.isVideo(it.name) }
            .map { it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()

    private fun imageNames(): List<String> =
        entries.asSequence()
            .filter { !it.isDirectory && VideoFormats.isImage(it.name) }
            .map { it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()

    private fun openPlayerAt(index: Int) {
        // 从播放页回来会重新加载列表，先记住当前滚动位置
        rememberCurrentScroll()
        openPlayer.launch(
            PlayerActivity.intent(
                context = this,
                serverId = server.id,
                share = shareName,
                directory = currentPath,
                index = index,
            )
        )
    }

    private fun openImageViewerAt(index: Int) {
        // 查看图片不改动远程文件，不需要刷新列表
        rememberCurrentScroll()
        startActivity(
            ImageViewerActivity.intent(
                context = this,
                serverId = server.id,
                share = shareName,
                directory = currentPath,
                index = index,
            )
        )
    }

    /** 记下当前第一个可见条目的位置与顶部偏移，供 loadDirectory 后恢复 */
    private fun rememberCurrentScroll() {
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first < 0) return
        val top = layoutManager.findViewByPosition(first)?.top ?: return
        pendingRestore = PendingRestore(
            currentPath,
            ScrollPoint(first, entries.getOrNull(first)?.name.orEmpty(), top),
        )
    }

    // ------------------------------------------------------------------ 数据

    private fun connectThen(onOk: () -> Unit) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                logInfo("连接服务器: ${server.host}:${server.port} 共享=$shareName")
                withContext(Dispatchers.IO) { session.ping() }
                showLoading(false)
                logInfo("连接成功: ${server.host}")
                onOk()
            } catch (e: CancellationException) {
                throw e // 用户离开页面：正常取消，不当成错误
            } catch (e: Exception) {
                showLoading(false)
                logError("连接失败: ${server.host}:${server.port}", e)
                showError(e)
            }
        }
    }

    private fun loadDirectory() {
        if (shareName.isBlank()) return
        showLoading(true)
        binding.emptyView.visibility = View.GONE
        lifecycleScope.launch {
            try {
                logInfo("加载目录: 共享=$shareName 路径=$currentPath")
                val list = withContext(Dispatchers.IO) { session.list(shareName, currentPath) }
                // 按用户选的排序方式排（默认文件名升序，目录在前）
                entries = SortOptions.sorted(
                    list,
                    SettingsRepository.sortField,
                    SettingsRepository.sortAscending,
                )
                adapter.submit(entries)
                showLoading(false)
                logInfo("目录加载完成: ${list.size} 项")
                updateHeader(list)
                applyPendingRestore(list)
            } catch (e: CancellationException) {
                throw e // 用户离开页面：正常取消，静默结束
            } catch (e: Exception) {
                showLoading(false)
                logError("加载目录失败: 共享=$shareName 路径=$currentPath", e)
                // 直达目录被改名/移走了：回落到共享根目录，别把用户挡死
                if (currentPath.isNotEmpty() && isNameNotFound(e)) {
                    toast(R.string.start_path_missing)
                    currentPath = ""
                    ServerRepository.save(server.copy(startPath = ""))
                    loadDirectory()
                    return@launch
                }
                showError(e)
            }
        }
    }

    /** 服务端回报「名称/路径不存在」类错误（直达目录失效时回落到共享根） */
    private fun isNameNotFound(e: Exception): Boolean {
        val text = e.describe()
        return text.contains("STATUS_OBJECT_NAME_NOT_FOUND", ignoreCase = true) ||
            text.contains("STATUS_OBJECT_PATH_NOT_FOUND", ignoreCase = true)
    }

    private fun updateHeader(list: List<SmbEntry>) {
        val prefix = if (currentPath.isEmpty()) "/" else "/$currentPath/"
        binding.tvPath.text = "$shareName$prefix"
        binding.tvCount.text = "${list.size} 项"
        binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.toolbar.menu.findItem(R.id.action_play_all)?.isVisible = videoNames().isNotEmpty()
        binding.toolbar.menu.findItem(R.id.action_view_images)?.isVisible = imageNames().isNotEmpty()
    }

    private fun showLoading(show: Boolean) {
        binding.progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 目录加载完成后，把列表滚回进入文件夹/播放前的位置 */
    private fun applyPendingRestore(list: List<SmbEntry>) {
        val restore = pendingRestore ?: return
        pendingRestore = null
        // 只对本次要加载的目录生效（连续快速返回时旧的恢复目标已过期）
        if (restore.targetPath != currentPath || list.isEmpty()) return
        val point = restore.point
        // 优先按名称定位，找不到再用记录的序号（目录内容可能有变化）
        val byName = if (point.name.isEmpty()) -1 else list.indexOfFirst { it.name == point.name }
        val index = (if (byName >= 0) byName else point.position).coerceIn(0, list.size - 1)
        layoutManager.scrollToPositionWithOffset(index, point.offset)
        logInfo("列表恢复位置: ${list[index].name} (序号 $index, 偏移 ${point.offset}px)")
    }

    private fun showError(e: Exception) {
        // 离开页面导致协程取消不是错误；Activity 已 finishing/destroyed 时弹窗会
        // BadTokenException 崩溃（token is not valid; is your activity running?）
        if (e is CancellationException) {
            logInfo("SMB 操作被取消（用户离开页面）")
            return
        }
        if (!isAlive()) {
            logError("SMB 操作失败（页面已销毁，不再提示）: ${e.describe()}")
            return
        }
        logError("SMB 操作失败", e)
        val detail = e.describe()
        // 同时给一个即时反馈，再弹详细对话框
        toast(detail.lineSequence().firstOrNull()?.take(120) ?: detail)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(detail)
            .setPositiveButton(R.string.error_copy) { _, _ ->
                copyToClipboard(detail)
                toast(R.string.error_copied)
            }
            .setNeutralButton(R.string.error_clear) { _, _ ->
                clearCache()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("SMB Error", text))
    }

    private fun clearCache() {
        // 清除连接缓存，下次重新连接
        SmbGateway.invalidate(server.id)
        toast(R.string.cache_cleared)
        logInfo("缓存已清除: ${server.id}")
    }

    // ------------------------------------------------------------------ 删除（多选批量）

    private fun updateSelectionBar(count: Int) {
        if (count <= 0) {
            binding.selectionBar.visibility = View.GONE
            return
        }
        binding.selectionBar.visibility = View.VISIBLE
        binding.tvSelectedCount.text = getString(R.string.selected_count, count)
    }

    private fun confirmDeleteSelected() {
        val targets = adapter.selectedEntries()
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_batch_message, targets.size))
            .setPositiveButton(R.string.delete_confirm) { _, _ -> doDeleteSelected(targets) }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    /** 逐个删除，失败不中断，最后汇总结果 */
    private fun doDeleteSelected(targets: List<SmbEntry>) {
        showLoading(true)
        lifecycleScope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            var firstError: String? = null
            for (entry in targets) {
                val path = joinPath(currentPath, entry.name)
                try {
                    withContext(Dispatchers.IO) {
                        session.delete(shareName, path, entry.isDirectory)
                    }
                    ok++
                    logInfo("已删除: $path")
                } catch (e: CancellationException) {
                    throw e // 取消就整体中断，剩下的不再删、不记失败
                } catch (e: Exception) {
                    failed.add(entry.name)
                    logError("删除失败: $path", e)
                    if (firstError == null) {
                        val reason = e.describe().lineSequence().firstOrNull()?.take(160) ?: "未知错误"
                        firstError = "路径: $path\n$reason"
                    }
                }
            }
            showLoading(false)
            adapter.clearSelection()
            // 删除期间用户可能已经离开页面：此时只求不崩，别再弹结果框
            if (!isAlive()) return@launch
            if (failed.isEmpty()) {
                toast(getString(R.string.deleted, "$ok"))
            } else {
                // 只报数量看不出原因，把第一条失败的具体错误一起显示出来
                val sample = failed.take(3).joinToString("、")
                val detail = getString(R.string.delete_partial_detail, ok, failed.size, sample, firstError ?: "")
                logError("批量删除结果: 成功 $ok, 失败 ${failed.size}: $detail")
                MaterialAlertDialogBuilder(this@BrowseActivity)
                    .setTitle(R.string.delete_failed_title)
                    .setMessage(detail)
                    .setPositiveButton(R.string.error_copy) { _, _ ->
                        copyToClipboard(detail)
                        toast(R.string.error_copied)
                    }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }
            loadDirectory()
        }
    }

    // ------------------------------------------------------------------ 共享选择

    /**
     * 扫描服务器上的共享：优先用 jcifs-ng 做**真实共享枚举**——服务器通过 MS-SRVS
     * 直接返回共享列表，不依赖常见名候选表（共享名不在表里也能扫到）；
     * 枚举失败或为空（如服务器禁匿名）时，回落到候选表逐个试连。
     */
    private suspend fun scanAllShares(): List<String> {
        val real = SmbShareLister.listShares(
            host = server.host,
            port = server.port,
            username = server.username,
            password = server.password,
            anonymous = server.anonymous,
        )
        if (real.isNotEmpty()) return real
        session.ping() // 确保已连接（scanShares 内部依赖已建立的连接）
        return session.scanShares(candidateShares + listOf(hostAsShare))
    }

    /**
     * 共享名留空时自动扫描：优先真实共享枚举，失败再逐个试连常见共享名。
     * - 扫到 1 个 → 直接进入；
     * - 扫到多个 → 列表选择；
     * - 扫到 0 个 → 回落到手动输入对话框。
     */
    private fun autoScanShares() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_scanning)
            .setMessage(R.string.share_scan_auto_hint)
            .setCancelable(true)
            .setNegativeButton(R.string.share_manual_input) { _, _ ->
                // 等不及扫描 / 扫描不到：转手动输入，不退出
                showSharePicker()
            }
            .setOnCancelListener { showSharePicker() }
            .create()
        dialog.show()

        lifecycleScope.launch {
            val found = try {
                withContext(Dispatchers.IO) {
                    scanAllShares()
                }
            } catch (e: CancellationException) {
                throw e // 用户离开页面：正常取消
            } catch (e: Exception) {
                logError("自动扫描共享失败: ${server.host}:${server.port}", e)
                dialog.dismiss()
                // 连接都失败了：保留手动对话框让用户重试/改配置
                showSharePicker()
                return@launch
            }
            dialog.dismiss()
            onSharesScanned(found, autoMode = true)
        }
    }

    /** 扫描结果分发：单个直接进，多个列表选择，没有则提示 */
    private fun onSharesScanned(found: List<String>, autoMode: Boolean) {
        when {
            found.size == 1 -> connectToShare(found.first())
            found.size > 1 -> showShareChoiceDialog(found)
            else -> if (autoMode) {
                showSharePicker(statusRes = R.string.share_none_found)
            } else {
                toast(R.string.share_none_found)
            }
        }
    }

    /** 扫到多个共享时弹列表，点哪个进哪个 */
    private fun showShareChoiceDialog(found: List<String>) {
        if (!isAlive()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_select_title)
            .setItems(found.toTypedArray()) { _, which -> connectToShare(found[which]) }
            .setOnCancelListener { finish() }
            .setNegativeButton(R.string.server_cancel) { _, _ -> finish() }
            .show()
    }

    /**
     * 切换共享：SMB 协议里共享之间互相不可见（进了 C 盘共享就看不到 D 盘），
     * 想看其他盘/其他共享必须显式切换。扫描后列表选择，当前共享置顶标注。
     */
    private fun switchShare() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_scanning)
            .setMessage(R.string.share_scan_auto_hint)
            .setCancelable(true)
            .setNegativeButton(R.string.server_cancel, null)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val found = try {
                withContext(Dispatchers.IO) {
                    scanAllShares()
                }
            } catch (e: CancellationException) {
                throw e // 用户离开页面：正常取消
            } catch (e: Exception) {
                logError("切换共享扫描失败: ${server.host}:${server.port}", e)
                dialog.dismiss()
                showError(e)
                return@launch
            }
            dialog.dismiss()
            if (!isAlive()) return@launch
            if (found.isEmpty()) {
                toast(R.string.share_none_found)
                return@launch
            }
            // 当前共享排到最前并标注，其余按扫描顺序
            val ordered = buildList {
                if (shareName.isNotBlank() && shareName in found) add(shareName)
                addAll(found.filterNot { it == shareName })
            }
            val labels = ordered
                .map { name -> if (name == shareName) getString(R.string.switch_share_current, name) else name }
                .toTypedArray()
            MaterialAlertDialogBuilder(this@BrowseActivity)
                .setTitle(R.string.share_select_title)
                .setItems(labels) { _, which ->
                    val target = ordered[which]
                    if (target != shareName) connectToShare(target, resetPath = true)
                }
                .setNegativeButton(R.string.server_cancel, null)
                .show()
        }
    }

    /** 校验共享可连后进入目录列表 */
    private fun connectToShare(name: String, resetPath: Boolean = false) {
        lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    session.shareBlocking(name)
                    true
                }
            } catch (e: Exception) {
                logError("连接共享失败: $name", e)
                showError(e)
                false
            }
            if (ok) {
                shareName = name
                // 切换共享时从头开始浏览（SMB 里各共享的目录互不相通）
                if (resetPath) currentPath = ""
                // 共享名只对本次会话生效，不写回服务器配置：下次进入仍重新扫描/选择
                loadDirectory()
            } else {
                // 单个共享直接进失败时给手动兜底；列表选择失败留在原页
                if (shareName.isBlank()) showSharePicker(preselect = name)
            }
        }
    }

    private fun showSharePicker(preselect: String? = null, statusRes: Int? = null) {
        // 页面已经走了就不要再弹（异步回调可能在 Activity 销毁后才跑到）
        if (!isAlive()) return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_share_picker, null)
        val b = DialogSharePickerBinding.bind(view)
        if (!preselect.isNullOrBlank()) b.etShare.setText(preselect)
        if (statusRes != null) b.tvScanStatus.setText(statusRes)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_select_title)
            .setView(view)
            .setPositiveButton(R.string.share_connect, null)
            .setNegativeButton(R.string.server_cancel) { _, _ -> finish() }
            .setOnDismissListener { if (shareName.isBlank()) finish() }
            .create()

        b.btnScan.setOnClickListener {
            b.tvScanStatus.text = getString(R.string.share_scanning)
            b.btnScan.isEnabled = false
            lifecycleScope.launch {
                val found = try {
                    withContext(Dispatchers.IO) {
                        session.ping()
                        session.scanShares(candidateShares + listOf(hostAsShare))
                    }
                } catch (e: CancellationException) {
                    throw e // 用户离开页面：正常取消
                } catch (e: Exception) {
                    emptyList<String>().also { showError(e) }
                }
                if (!isAlive()) return@launch
                b.btnScan.isEnabled = true
                when {
                    found.size == 1 -> {
                        // 只扫到一个，直接帮用户连上
                        val name = found.first()
                        val ok = try {
                            withContext(Dispatchers.IO) { session.shareBlocking(name); true }
                        } catch (e: Exception) {
                            showError(e)
                            false
                        }
                        if (ok) {
                            shareName = name
                            currentPath = ""
                            dialog.setOnDismissListener(null)
                            dialog.dismiss()
                            loadDirectory()
                        } else {
                            b.tvScanStatus.text = getString(R.string.share_none_found)
                        }
                    }
                    found.size > 1 -> {
                        // 扫到多个：关掉输入框，直接列表选
                        dialog.setOnDismissListener(null)
                        dialog.dismiss()
                        showShareChoiceDialog(found)
                    }
                    else -> b.tvScanStatus.text = getString(R.string.share_none_found)
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val raw = b.etShare.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    b.etShare.error = getString(R.string.share_required)
                    return@setOnClickListener
                }
                // 直接粘贴完整路径：指向别的服务器给提示，指向本服务器就拆出共享+子目录
                val pasted = if (SmbPath.isFullPath(raw)) SmbPath.parse(raw) else null
                if (pasted != null && !pasted.host.equals(server.host, ignoreCase = true) &&
                    pasted.host != server.displayHost
                ) {
                    b.tvScanStatus.text = getString(R.string.path_other_host, pasted.host)
                    return@setOnClickListener
                }
                val (name, subPath) = pasted?.let { it.share to it.path }
                    ?: SmbPath.splitShareInput(raw)
                if (name.isEmpty()) {
                    b.etShare.error = getString(R.string.share_required)
                    return@setOnClickListener
                }
                b.tvScanStatus.text = getString(R.string.browse_loading)
                lifecycleScope.launch {
                    val ok = try {
                        withContext(Dispatchers.IO) {
                            session.shareBlocking(name)
                            true
                        }
                    } catch (e: CancellationException) {
                        throw e // 用户离开页面：正常取消
                    } catch (e: Exception) {
                        showError(e)
                        false
                    }
                    if (!isAlive()) return@launch
                    if (ok) {
                        shareName = name
                        currentPath = subPath
                        dialog.setOnDismissListener(null)
                        dialog.dismiss()
                        loadDirectory()
                    } else {
                        b.tvScanStatus.text = getString(R.string.share_not_found, name)
                    }
                }
            }
        }
        dialog.show()
    }

    companion object {
        private const val EXTRA_SERVER_ID = "extra_server_id"
        private const val EXTRA_START_PATH = "extra_start_path"
        private const val EXTRA_START_SHARE = "extra_start_share"

        /** 重建时保存当前浏览位置（夜间模式切换等） */
        private const val STATE_SHARE = "state_share"
        private const val STATE_PATH = "state_path"

        fun intent(
            context: Context,
            serverId: String,
            startPath: String = "",
            startShare: String = "",
        ): Intent =
            Intent(context, BrowseActivity::class.java)
                .putExtra(EXTRA_SERVER_ID, serverId)
                .putExtra(EXTRA_START_PATH, startPath)
                .putExtra(EXTRA_START_SHARE, startShare)
    }
}
