package com.ghostphoto.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ghostphoto.app.databinding.ItemGhostCandidateBinding
import com.ghostphoto.app.matcher.LocalMediaRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GhostCandidateAdapter(
    private var items: List<LocalMediaRecord> = emptyList(),
    private val onItemClick: (LocalMediaRecord) -> Unit = {}
) : RecyclerView.Adapter<GhostCandidateAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(newList: List<LocalMediaRecord>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGhostCandidateBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemGhostCandidateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LocalMediaRecord) {
            binding.tvFilename.text = item.displayName
            val dateStr = dateFormat.format(Date(item.takenAtMillis))
            val sizeMb = item.sizeBytes.toDouble() / (1024.0 * 1024.0)
            val sizeMbStr = String.format(Locale.US, "%.1f MB", sizeMb)
            val resStr = if (item.width > 0 && item.height > 0) "${item.width}x${item.height} • " else ""
            binding.tvMeta.text = "$dateStr • $resStr$sizeMbStr"

            binding.root.setOnClickListener {
                onItemClick(item)
            }
            binding.btnOpenPhotos.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
