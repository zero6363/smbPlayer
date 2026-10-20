package com.smbplayer.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.app.R
import com.smbplayer.app.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val items: List<String>,
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
            val name = items[position]
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
            root.setOnClickListener { onClick(position) }
        }
    }
}
