package com.netdisk.app.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.netdisk.app.R
import com.netdisk.app.models.DownloadRecord
import com.netdisk.app.models.DownloadStatus
import com.netdisk.app.storage.DownloadHistoryManager
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: DownloadAdapter
    private lateinit var historyManager: DownloadHistoryManager
    private lateinit var downloadManager: DownloadManager

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDownloadProgress()
            handler.postDelayed(this, UPDATE_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.downloads)

        historyManager = DownloadHistoryManager(this)
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)

        adapter = DownloadAdapter(
            onItemClick = { record -> openFile(record) },
            onDeleteClick = { record -> confirmDelete(record) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        loadDownloads()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun loadDownloads() {
        val records = historyManager.getRecords()
        adapter.submitList(records)
        updateEmptyViewVisibility(records.isEmpty())
    }

    private fun updateEmptyViewVisibility(isEmpty: Boolean) {
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateDownloadProgress() {
        val records = historyManager.getRecords().toMutableList()
        var hasChanges = false

        for (i in records.indices) {
            val record = records[i]
            if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.PENDING) {
                val query = DownloadManager.Query().setFilterById(record.downloadId)
                val cursor = downloadManager.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    if (statusIndex >= 0 && bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0) {
                        val status = cursor.getInt(statusIndex)
                        val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                        val bytesTotal = cursor.getLong(bytesTotalIndex)

                        val newStatus = when (status) {
                            DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                            DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                            DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                            DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                            DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                            else -> record.status
                        }

                        val progress = if (bytesTotal > 0) {
                            ((bytesDownloaded * 100) / bytesTotal).toInt()
                        } else {
                            0
                        }

                        if (newStatus != record.status || progress != record.progress ||
                            bytesDownloaded != record.downloadedSize || bytesTotal != record.totalSize) {
                            historyManager.updateRecord(record.downloadId, newStatus, progress, bytesDownloaded, bytesTotal)
                            hasChanges = true
                        }
                    }
                    cursor.close()
                } else {
                    // Download not found in system, mark as failed or completed
                    val file = File(record.filePath)
                    if (file.exists()) {
                        historyManager.updateRecordStatus(record.downloadId, DownloadStatus.COMPLETED)
                    } else {
                        historyManager.updateRecordStatus(record.downloadId, DownloadStatus.FAILED)
                    }
                    hasChanges = true
                }
            }
        }

        if (hasChanges) {
            loadDownloads()
        }
    }

    private fun openFile(record: DownloadRecord) {
        val file = File(record.filePath)
        if (!file.exists()) {
            Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.open_file_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "audio/*"
            "mp4", "mkv", "avi", "mov", "wmv", "flv" -> "video/*"
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "image/*"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    private fun confirmDelete(record: DownloadRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_download)
            .setMessage(R.string.delete_download_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteDownload(record)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteDownload(record: DownloadRecord) {
        // Cancel download if in progress
        if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.PENDING) {
            downloadManager.remove(record.downloadId)
        }

        // Remove from history
        historyManager.deleteRecord(record.downloadId)
        loadDownloads()

        Toast.makeText(this, R.string.download_deleted, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val UPDATE_INTERVAL = 1000L // 1 second
    }
}
