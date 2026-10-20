package com.smbplayer.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.app.data.SmbServer
import com.smbplayer.app.databinding.ItemServerBinding

class ServerAdapter(
    private val onClick: (SmbServer) -> Unit,
    private val onLongClick: (SmbServer) -> Unit,
    private val onEdit: (SmbServer) -> Unit,
    private val onDelete: (SmbServer) -> Unit,
) : RecyclerView.Adapter<ServerAdapter.Holder>() {

    private val items = mutableListOf<SmbServer>()

    fun submit(list: List<SmbServer>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemServerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(server: SmbServer) = with(binding) {
            tvName.text = server.name.ifBlank { server.displayHost }
            tvHost.text = server.displayHost
            tvMeta.text = buildString {
                if (server.share.isNotBlank()) append("共享：").append(server.share).append("　")
                append(server.displayAuth)
            }

            root.setOnClickListener { onClick(server) }
            root.setOnLongClickListener {
                onLongClick(server)
                true
            }
            btnEdit.setOnClickListener { onEdit(server) }
            btnDelete.setOnClickListener { onDelete(server) }
        }
    }
}
