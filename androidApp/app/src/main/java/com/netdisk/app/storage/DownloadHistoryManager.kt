package com.netdisk.app.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.netdisk.app.models.DownloadRecord
import com.netdisk.app.models.DownloadStatus

class DownloadHistoryManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun addRecord(record: DownloadRecord) {
        val records = getRecords().toMutableList()
        // Remove existing record with same downloadId if exists
        records.removeAll { it.downloadId == record.downloadId }
        // Add new record at the beginning
        records.add(0, record)
        saveRecords(records)
    }

    fun getRecords(): List<DownloadRecord> {
        val json = prefs.getString(KEY_DOWNLOAD_RECORDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DownloadRecord>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun updateRecord(downloadId: Long, status: DownloadStatus, progress: Int, downloadedSize: Long, totalSize: Long) {
        val records = getRecords().toMutableList()
        val index = records.indexOfFirst { it.downloadId == downloadId }
        if (index >= 0) {
            val record = records[index]
            records[index] = record.copy(
                status = status,
                progress = progress,
                downloadedSize = downloadedSize,
                totalSize = totalSize
            )
            saveRecords(records)
        }
    }

    fun updateRecordStatus(downloadId: Long, status: DownloadStatus) {
        val records = getRecords().toMutableList()
        val index = records.indexOfFirst { it.downloadId == downloadId }
        if (index >= 0) {
            val record = records[index]
            records[index] = record.copy(status = status)
            saveRecords(records)
        }
    }

    fun deleteRecord(downloadId: Long) {
        val records = getRecords().toMutableList()
        records.removeAll { it.downloadId == downloadId }
        saveRecords(records)
    }

    fun clearAllRecords() {
        prefs.edit().remove(KEY_DOWNLOAD_RECORDS).apply()
    }

    private fun saveRecords(records: List<DownloadRecord>) {
        val json = gson.toJson(records)
        prefs.edit().putString(KEY_DOWNLOAD_RECORDS, json).apply()
    }

    companion object {
        private const val PREFS_NAME = "download_history"
        private const val KEY_DOWNLOAD_RECORDS = "download_records"
    }
}
