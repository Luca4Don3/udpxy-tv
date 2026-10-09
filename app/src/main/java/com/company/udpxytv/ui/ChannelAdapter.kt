package com.company.udpxytv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.company.udpxytv.R
import com.company.udpxytv.data.Channel
import com.company.udpxytv.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = mutableListOf<Channel>()

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.b.tvName.text = ch.name
        holder.b.tvMeta.text = "局域网直播"
        holder.b.tvIndex.text = (position + 1).toString().padStart(2, '0')
        holder.b.tvMonogram.text = ch.name.trim().take(1).uppercase().ifEmpty { "TV" }
        holder.b.root.setOnClickListener { onClick(ch) }
        holder.b.root.setOnLongClickListener {
            onLongClick(ch)
            true
        }
    }
}
