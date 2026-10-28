package com.smbplayer.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.app.R
import com.smbplayer.app.databinding.ItemPlaylistBinding

/**
 * 播放列表条目。
 *
 * [progress] 是上次播放进度（0f..1f），null 表示还没有播放记录；
 * 有记录时在文件名下方显示一根细线，线到底表示已看完。
 */
data class PlaylistItem(
    val name: String,
    val progress: Float?,
)

class PlaylistAdapter(
    private val items: List<PlaylistItem>,
    private val selectedIndex: Int,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

    inner class Holder(private val binding: ItemPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) = with(binding) {
            val item = items[position]
            val name = item.name
            val active = position == selectedIndex
            tvIndex.text = (position + 1).toString()
            tvName.text = name
            tvName.setTextColor(
                root.context.getColor(if (active) R.color.primary else R.color.on_surface)
            )
            tvName.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            icon.setImageResource(
                if (active) R.drawable.ic_play else R.drawable.ic_movie
            )

            val progressValue = item.progress
            if (progressValue == null) {
                progress.visibility = View.GONE
            } else {
                progress.visibility = View.VISIBLE
                progress.progress = (progressValue.coerceIn(0f, 1f) * progress.max).toInt()
            }

            root.setOnClickListener { onClick(position) }
        }
    }
}
