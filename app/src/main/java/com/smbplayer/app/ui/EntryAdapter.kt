package com.smbplayer.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.app.R
import com.smbplayer.app.data.VideoFormats
import com.smbplayer.app.databinding.ItemEntryBinding
import com.smbplayer.app.smb.SmbEntry
import com.smbplayer.app.util.formatDate
import com.smbplayer.app.util.formatSize

class EntryAdapter(
    /** 点击回调带条目 View，方便记录它在列表中的位置（返回上一级时滚回原处） */
    private val onClick: (SmbEntry, View) -> Unit,
    /** 长按进入/操作多选，选中数量变化时回调 */
    private val onSelectionChanged: (Int) -> Unit,
) : RecyclerView.Adapter<EntryAdapter.Holder>() {

    private val items = mutableListOf<SmbEntry>()

    /** 播放进度：条目名 -> 0f..1f，没有记录的视频不在表里 */
    private var progressByName: Map<String, Float> = emptyMap()

    /** 多选模式：按条目名记录选中项 */
    private val selectedNames = linkedSetOf<String>()

    val selectionMode: Boolean
        get() = selectedNames.isNotEmpty()

    val selectedCount: Int
        get() = selectedNames.size

    fun submit(list: List<SmbEntry>, progress: Map<String, Float> = emptyMap()) {
        items.clear()
        items.addAll(list)
        progressByName = progress
        selectedNames.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectedEntries(): List<SmbEntry> =
        items.filter { it.name in selectedNames }

    fun selectAll() {
        if (items.isEmpty()) return
        selectedNames.clear()
        items.forEach { selectedNames.add(it.name) }
        notifyDataSetChanged()
        onSelectionChanged(selectedNames.size)
    }

    fun clearSelection() {
        if (selectedNames.isEmpty()) return
        selectedNames.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun toggle(entry: SmbEntry) {
        if (!selectedNames.remove(entry.name)) selectedNames.add(entry.name)
        notifyDataSetChanged()
        onSelectionChanged(selectedNames.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(items[position], position)

    inner class Holder(private val binding: ItemEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SmbEntry, position: Int) = with(binding) {
            val ctx = root.context
            // 列表序号：从 1 开始，按当前显示顺序编号
            tvName.text = "${position + 1}. ${entry.name}"

            if (entry.isDirectory) {
                icon.setImageResource(R.drawable.ic_folder)
                icon.setColorFilter(ctx.getColor(R.color.folder_tint))
                tvMeta.text = ctx.getString(R.string.item_folder)
            } else {
                val isVideo = VideoFormats.isVideo(entry.name)
                val isImage = !isVideo && VideoFormats.isImage(entry.name)
                icon.setImageResource(
                    when {
                        isVideo -> R.drawable.ic_movie
                        isImage -> R.drawable.ic_image
                        else -> R.drawable.ic_movie
                    }
                )
                icon.setColorFilter(
                    when {
                        isVideo -> ctx.getColor(R.color.video_tint)
                        isImage -> ctx.getColor(R.color.secondary)
                        else -> ctx.getColor(R.color.on_surface_variant)
                    }
                )
                tvMeta.text = buildString {
                    append(formatSize(entry.size))
                    val date = formatDate(entry.lastModified)
                    if (date.isNotEmpty()) {
                        append("　")
                        append(date)
                    }
                }
            }

            // 上次播放进度细线：只给有记录的视频显示，目录和无记录文件隐藏
            val progressValue = progressByName[entry.name]
            if (entry.isDirectory || progressValue == null) {
                progress.visibility = View.GONE
            } else {
                progress.visibility = View.VISIBLE
                progress.progress = (progressValue.coerceIn(0f, 1f) * progress.max).toInt()
            }

            // 选中态：高亮背景 + 勾选图标
            val selected = entry.name in selectedNames
            if (selected) {
                root.setBackgroundResource(R.drawable.bg_item_selected)
                check.visibility = ViewGroup.VISIBLE
            } else {
                root.setBackgroundResource(R.drawable.bg_item_normal)
                check.visibility = ViewGroup.GONE
            }

            root.setOnClickListener {
                if (selectionMode) toggle(entry) else onClick(entry, root)
            }
            // 长按进入多选模式（选中该项），已选中则切换勾选
            root.setOnLongClickListener {
                toggle(entry)
                true
            }
        }
    }
}
