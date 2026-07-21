package com.netdisk.app.webview

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.netdisk.app.models.AudioTrack
import com.netdisk.app.services.AudioPlaybackService
import com.netdisk.app.storage.NetdiskDownloadManager
import com.netdisk.app.storage.PreferencesManager

class JavaScriptBridge(
    private val context: Context,
    private val downloadManager: NetdiskDownloadManager,
    private val preferencesManager: PreferencesManager
) {

    @JavascriptInterface
    fun playAudio(url: String, title: String, playlistJson: String) {
        playAudio(url, title, playlistJson, null)
    }

    @JavascriptInterface
    fun playAudio(url: String, title: String, playlistJson: String, playMode: String?) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "JavaScriptBridge.playAudio() called")
        Log.d(TAG, "  URL: $url")
        Log.d(TAG, "  Title: $title")
        Log.d(TAG, "  PlayMode: $playMode")
        Log.d(TAG, "  Playlist items: ${parsePlaylist(playlistJson).size}")
        Log.d(TAG, "========================================")

        try {
            // Parse playlist from JSON
            val playlist = parsePlaylist(playlistJson)

            // Send intent to AudioPlaybackService
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = AudioPlaybackService.ACTION_PLAY
                putExtra(AudioPlaybackService.EXTRA_URL, url)
                putExtra(AudioPlaybackService.EXTRA_TITLE, title)
                putExtra(AudioPlaybackService.EXTRA_PLAYLIST, playlistJson)
                playMode?.let {
                    putExtra(AudioPlaybackService.EXTRA_PLAY_MODE, it)
                    Log.d(TAG, "  Including playMode in intent: $it")
                }
            }
            context.startService(intent)
            Log.d(TAG, "AudioPlaybackService started")

        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio", e)
        }
    }

    @JavascriptInterface
    fun pauseAudio() {
        Log.d(TAG, "pauseAudio called")
        sendServiceAction(AudioPlaybackService.ACTION_PAUSE)
    }

    @JavascriptInterface
    fun resumeAudio() {
        Log.d(TAG, "resumeAudio called")
        sendServiceAction(AudioPlaybackService.ACTION_RESUME)
    }

    @JavascriptInterface
    fun seekTo(positionMs: Long) {
        Log.d(TAG, "seekTo called: position=$positionMs")
        val intent = Intent(context, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_SEEK
            putExtra(AudioPlaybackService.EXTRA_POSITION, positionMs)
        }
        context.startService(intent)
    }

    @JavascriptInterface
    fun nextTrack() {
        Log.d(TAG, "nextTrack called")
        sendServiceAction(AudioPlaybackService.ACTION_NEXT)
    }

    @JavascriptInterface
    fun previousTrack() {
        Log.d(TAG, "previousTrack called")
        sendServiceAction(AudioPlaybackService.ACTION_PREVIOUS)
    }

    @JavascriptInterface
    fun setPlayMode(mode: String) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "JavaScriptBridge.setPlayMode() called")
        Log.d(TAG, "  Mode from JavaScript: '$mode'")
        Log.d(TAG, "  Mode uppercase: '${mode.uppercase()}'")
        Log.d(TAG, "========================================")

        val intent = Intent(context, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_SET_MODE
            putExtra(AudioPlaybackService.EXTRA_PLAY_MODE, mode)
        }
        context.startService(intent)
        Log.d(TAG, "setPlayMode intent sent to service")
    }

    @JavascriptInterface
    fun stopAudio() {
        Log.d(TAG, "stopAudio called")
        sendServiceAction(AudioPlaybackService.ACTION_STOP)
    }

    @JavascriptInterface
    fun downloadFile(url: String, filename: String) {
        Log.d(TAG, "downloadFile called: url=$url, filename=$filename")
        try {
            downloadManager.enqueueDownload(url, filename)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading file", e)
        }
    }

    @JavascriptInterface
    fun saveStreamToken(token: String) {
        Log.d(TAG, "saveStreamToken called")
        try {
            preferencesManager.saveStreamToken(token)
            Log.d(TAG, "Stream token saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving stream token", e)
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(context, AudioPlaybackService::class.java).apply {
            this.action = action
        }
        context.startService(intent)
    }

    private fun parsePlaylist(playlistJson: String): List<AudioTrack> {
        return try {
            val type = object : TypeToken<List<AudioTrack>>() {}.type
            Gson().fromJson(playlistJson, type)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing playlist", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "JavaScriptBridge"
    }
}
