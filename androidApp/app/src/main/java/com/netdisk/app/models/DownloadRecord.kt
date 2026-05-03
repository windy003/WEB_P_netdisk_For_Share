package com.netdisk.app.models

data class DownloadRecord(
    val downloadId: Long,
    val filename: String,
    val url: String,
    val filePath: String,
    var status: DownloadStatus,
    var progress: Int = 0,
    var totalSize: Long = 0,
    var downloadedSize: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}
