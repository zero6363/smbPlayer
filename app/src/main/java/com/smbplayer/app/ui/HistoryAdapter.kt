package com.smbplayer.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.app.R
import com.smbplayer.app.data.PlaybackHistory
import com.smbplayer.app.databinding.ItemHistoryBinding

/** 首页播放记录卡片的数据：原始记录 + 展示用的服务器名和进度文案 */
data class HistoryCard(
    val record: PlaybackHistory,
    val serverName: String,
    val progressLabel: String,
)

class HistoryAdapter(
    private val onClick: (PlaybackHistory) -> Unit,
    private val onLongClick: (PlaybackHistory) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    private val items = mutableListOf<HistoryCard>()

    fun submit(list: List<HistoryCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryCard) = with(binding) {
            tvName.text = item.record.name
            tvSubtitle.text = root.context.getString(
                R.string.history_subtitle,
                item.serverName,
                item.progressLabel,
            )
            progress.progress = (item.record.displayFraction * 1000).toInt()
            root.setOnClickListener { onClick(item.record) }
            root.setOnLongClickListener {
                onLongClick(item.record)
                true
            }
        }
    }
}
