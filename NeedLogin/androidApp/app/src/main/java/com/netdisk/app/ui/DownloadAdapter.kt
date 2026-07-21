package com.netdisk.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.netdisk.app.R
import com.netdisk.app.models.DownloadRecord
import com.netdisk.app.models.DownloadStatus
import java.text.DecimalFormat

class DownloadAdapter(
    private val onItemClick: (DownloadRecord) -> Unit,
    private val onDeleteClick: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, DownloadAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.iconView)
        private val filenameView: TextView = itemView.findViewById(R.id.filenameView)
        private val statusView: TextView = itemView.findViewById(R.id.statusView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val sizeView: TextView = itemView.findViewById(R.id.sizeView)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)

        fun bind(record: DownloadRecord) {
            filenameView.text = record.filename

            // Set icon based on status
            when (record.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                    iconView.setImageResource(android.R.drawable.stat_sys_download)
                }
                DownloadStatus.COMPLETED -> {
                    iconView.setImageResource(android.R.drawable.stat_sys_download_done)
                }
                DownloadStatus.FAILED -> {
                    iconView.setImageResource(android.R.drawable.stat_notify_error)
                }
                DownloadStatus.PAUSED -> {
                    iconView.setImageResource(android.R.drawable.ic_media_pause)
                }
            }

            // Set status text
            val context = itemView.context
            statusView.text = when (record.status) {
                DownloadStatus.PENDING -> context.getString(R.string.download_pending)
                DownloadStatus.DOWNLOADING -> context.getString(R.string.download_downloading)
                DownloadStatus.COMPLETED -> context.getString(R.string.download_completed)
                DownloadStatus.FAILED -> context.getString(R.string.download_failed)
                DownloadStatus.PAUSED -> context.getString(R.string.download_paused)
            }

            // Show/hide progress bar
            if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.PENDING) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = record.progress
                sizeView.visibility = View.VISIBLE
                sizeView.text = formatSize(record.downloadedSize, record.totalSize)
            } else {
                progressBar.visibility = View.GONE
                if (record.status == DownloadStatus.COMPLETED && record.totalSize > 0) {
                    sizeView.visibility = View.VISIBLE
                    sizeView.text = formatFileSize(record.totalSize)
                } else {
                    sizeView.visibility = View.GONE
                }
            }

            // Click listeners
            itemView.setOnClickListener {
                if (record.status == DownloadStatus.COMPLETED) {
                    onItemClick(record)
                }
            }

            deleteButton.setOnClickListener {
                onDeleteClick(record)
            }
        }

        private fun formatSize(downloaded: Long, total: Long): String {
            return "${formatFileSize(downloaded)} / ${formatFileSize(total)}"
        }

        private fun formatFileSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            val formatter = DecimalFormat("#,##0.#")
            return "${formatter.format(size / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord): Boolean {
            return oldItem.downloadId == newItem.downloadId
        }

        override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord): Boolean {
            return oldItem == newItem
        }
    }
}
