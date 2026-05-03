package com.netdisk.app.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.netdisk.app.R
import com.netdisk.app.models.DownloadRecord
import com.netdisk.app.models.DownloadStatus
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class NetdiskDownloadManager(private val context: Context) {

    private val historyManager = DownloadHistoryManager(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadIdCounter = AtomicLong(System.currentTimeMillis())

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun enqueueDownload(url: String, filename: String): Long {
        val downloadId = downloadIdCounter.incrementAndGet()
        Log.d(TAG, "Enqueue download: id=$downloadId, url=$url, filename=$filename")

        // Get cookies for authentication
        val cookies = getCookieHeader(url)

        // Create download directory
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Netdisk"
        )
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        val destFile = File(downloadDir, filename)

        // Save download record
        val record = DownloadRecord(
            downloadId = downloadId,
            filename = filename,
            url = url,
            filePath = destFile.absolutePath,
            status = DownloadStatus.PENDING
        )
        historyManager.addRecord(record)

        // Show initial notification
        showProgressNotification(downloadId.toInt(), filename, 0, true)

        // Show toast
        mainHandler.post {
            Toast.makeText(context, "开始下载: $filename", Toast.LENGTH_SHORT).show()
        }

        // Build request with cookies
        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        if (cookies.isNotEmpty()) {
            requestBuilder.addHeader("Cookie", cookies)
        }

        val request = requestBuilder.build()

        // Execute download asynchronously
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Download failed: ${e.message}", e)

                historyManager.updateRecordStatus(downloadId, DownloadStatus.FAILED)

                mainHandler.post {
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }

                showCompletedNotification(downloadId.toInt(), filename, false, null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")

                    historyManager.updateRecordStatus(downloadId, DownloadStatus.FAILED)

                    mainHandler.post {
                        Toast.makeText(context, "下载失败: HTTP ${response.code}", Toast.LENGTH_LONG).show()
                    }

                    showCompletedNotification(downloadId.toInt(), filename, false, null)
                    return
                }

                try {
                    val body = response.body ?: throw IOException("Empty response body")
                    val contentLength = body.contentLength()

                    historyManager.updateRecordStatus(downloadId, DownloadStatus.DOWNLOADING)

                    FileOutputStream(destFile).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Long = 0
                            var lastProgress = 0
                            var read: Int

                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesRead += read

                                // Update progress
                                if (contentLength > 0) {
                                    val progress = ((bytesRead * 100) / contentLength).toInt()
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        showProgressNotification(downloadId.toInt(), filename, progress, false)
                                    }
                                }
                            }
                        }
                    }

                    Log.d(TAG, "Download completed: $filename")

                    historyManager.updateRecordStatus(downloadId, DownloadStatus.COMPLETED)

                    mainHandler.post {
                        Toast.makeText(context, "下载完成: $filename", Toast.LENGTH_SHORT).show()
                    }

                    showCompletedNotification(downloadId.toInt(), filename, true, destFile)

                } catch (e: Exception) {
                    Log.e(TAG, "Error saving file: ${e.message}", e)

                    historyManager.updateRecordStatus(downloadId, DownloadStatus.FAILED)

                    // Delete incomplete file
                    if (destFile.exists()) {
                        destFile.delete()
                    }

                    mainHandler.post {
                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }

                    showCompletedNotification(downloadId.toInt(), filename, false, null)
                }
            }
        })

        return downloadId
    }

    private fun showProgressNotification(notificationId: Int, filename: String, progress: Int, indeterminate: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(filename)
            .setContentText(if (indeterminate) "准备下载..." else "下载中 $progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun showCompletedNotification(notificationId: Int, filename: String, success: Boolean, file: File?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(filename)
            .setContentText(if (success) "下载完成" else "下载失败")
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)

        // Add click action to open file if successful
        if (success && file != null) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, getMimeType(filename))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating pending intent: ${e.message}")
            }
        }

        notificationManager.notify(notificationId, builder.build())
    }

    private fun getMimeType(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }

    private fun getCookieHeader(url: String): String {
        return CookieManager.getInstance().getCookie(url) ?: ""
    }

    companion object {
        private const val TAG = "NetdiskDownloadManager"
        private const val CHANNEL_ID = "netdisk_downloads"
    }
}
