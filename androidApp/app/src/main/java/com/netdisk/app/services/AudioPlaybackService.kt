package com.netdisk.app.services

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.netdisk.app.models.AudioTrack
import com.netdisk.app.models.PlayMode
import com.netdisk.app.models.PlaybackState
import com.netdisk.app.notifications.MediaNotificationManager
import com.netdisk.app.storage.PreferencesManager
import kotlin.random.Random

class AudioPlaybackService : Service() {

    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: MediaNotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private lateinit var preferencesManager: PreferencesManager

    private var currentTrack: AudioTrack? = null
    private var playlist: List<AudioTrack> = emptyList()
    private var currentIndex: Int = -1
    private var playMode: PlayMode = PlayMode.LOOP

    // 单曲循环模式下的导航历史（与 Web 版本保持一致）
    private val loopHistory = mutableListOf<Int>()
    private var loopHistPos = -1

    private val binder = AudioServiceBinder()

    // 用于定期更新进度的 Handler
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            if (mediaPlayer.isPlaying) {
                notifyWebViewOfStateChange()
                progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        preferencesManager = PreferencesManager(this)
        setupMediaPlayer()
        setupMediaSession()
        setupAudioFocus()

        notificationManager = MediaNotificationManager(this, mediaSession)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "handleIntent: action=${intent.action}")
        when (intent.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Unknown"
                val playlistJson = intent.getStringExtra(EXTRA_PLAYLIST)
                val mode = intent.getStringExtra(EXTRA_PLAY_MODE)

                // 如果Intent中包含播放模式，先设置播放模式
                mode?.let {
                    Log.d(TAG, "Setting play mode from playAudio intent: $it")
                    setPlayMode(it)
                }

                playlistJson?.let {
                    playlist = parsePlaylist(it)
                    currentIndex = playlist.indexOfFirst { track -> track.url == url }
                    initLoopHistory(currentIndex)
                }

                playTrack(url, title)
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_SEEK -> {
                val position = intent.getLongExtra(EXTRA_POSITION, 0L)
                seekTo(position)
            }
            ACTION_SET_MODE -> {
                val mode = intent.getStringExtra(EXTRA_PLAY_MODE)
                Log.d(TAG, "ACTION_SET_MODE received, mode='$mode'")
                if (mode == null) {
                    Log.e(TAG, "MODE is NULL!")
                    return
                }
                setPlayMode(mode)
            }
            ACTION_STOP -> stop()
        }
    }

    private fun setupMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            setOnCompletionListener {
                handleTrackCompletion()
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                notifyWebViewOfError()
                true
            }

            setOnPreparedListener {
                it.start()
                updateMediaSession()
                updateNotification()
                notifyWebViewOfStateChange()
                startProgressUpdates()
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = playNext()
                override fun onSkipToPrevious() = playPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stop()
            })

            isActive = true
        }
    }

    private fun setupAudioFocus() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
            setAudioAttributes(
                AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    build()
                }
            )
            build()
        }
    }

    private fun playTrack(url: String, title: String) {
        currentTrack = AudioTrack(url, title)

        Log.d(TAG, "========================================")
        Log.d(TAG, "playTrack() called")
        Log.d(TAG, "  Track: $title")
        Log.d(TAG, "  URL: $url")
        Log.d(TAG, "  Current PlayMode: $playMode")
        Log.d(TAG, "========================================")

        // Request audio focus
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus not granted")
            return
        }

        try {
            mediaPlayer.reset()

            // 将相对 URL 转换为完整的 HTTP URL
            val fullUrl = convertToFullUrl(url)
            Log.d(TAG, "Original URL: $url")
            Log.d(TAG, "Full URL: $fullUrl")

            mediaPlayer.setDataSource(fullUrl)
            mediaPlayer.prepareAsync()

            Log.d(TAG, "Playing track: $title, PlayMode: $playMode")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing track", e)
            notifyWebViewOfError()
        }
    }

    private fun convertToFullUrl(url: String): String {
        // 如果已经是完整的 HTTP/HTTPS URL,直接返回
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return addTokenToUrl(url)
        }

        // 如果是相对路径,添加服务器 URL
        val serverUrl = preferencesManager.getServerUrl()
        val fullUrl = if (url.startsWith("/")) {
            "$serverUrl$url"
        } else {
            "$serverUrl/$url"
        }

        return addTokenToUrl(fullUrl)
    }

    private fun addTokenToUrl(url: String): String {
        val token = preferencesManager.getStreamToken()
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "No stream token available")
            return url
        }

        // 在 URL 中添加 token 参数
        return if (url.contains("?")) {
            "$url&token=$token"
        } else {
            "$url?token=$token"
        }
    }

    fun pause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            stopProgressUpdates()
            updateMediaSession()
            updateNotification()
            notifyWebViewOfStateChange()
            Log.d(TAG, "Playback paused")
        }
    }

    fun resume() {
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
            startProgressUpdates()
            updateMediaSession()
            updateNotification()
            notifyWebViewOfStateChange()
            Log.d(TAG, "Playback resumed")
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer.seekTo(positionMs.toInt())
        updateMediaSession()
        notifyWebViewOfStateChange()
        Log.d(TAG, "Seeked to position: $positionMs")
    }

    fun playNext() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "playNext: playlist is empty")
            return
        }
        when (playMode) {
            PlayMode.LOOP -> {
                // 单曲循环模式：历史中有后续则前进，否则随机选一首并追加到历史
                if (loopHistPos < loopHistory.size - 1) {
                    loopHistPos++
                    currentIndex = loopHistory[loopHistPos]
                } else {
                    val randomIndex = getRandomIndex()
                    loopHistory.add(randomIndex)
                    loopHistPos++
                    currentIndex = randomIndex
                }
                val track = playlist[currentIndex]
                playTrack(track.url, track.title)
            }
            PlayMode.RANDOM -> playRandomTrack()
            else -> playNextTrack()
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "playPrevious: playlist is empty")
            return
        }
        when (playMode) {
            PlayMode.LOOP -> {
                // 单曲循环模式：有历史则后退，否则随机选一首并加入历史头部
                if (loopHistPos > 0) {
                    loopHistPos--
                    currentIndex = loopHistory[loopHistPos]
                } else {
                    val randomIndex = getRandomIndex()
                    loopHistory.add(0, randomIndex)
                    currentIndex = randomIndex
                }
                val track = playlist[currentIndex]
                playTrack(track.url, track.title)
            }
            PlayMode.RANDOM -> playRandomTrack()
            else -> {
                if (currentIndex > 0 && currentIndex < playlist.size) {
                    currentIndex--
                    val track = playlist[currentIndex]
                    playTrack(track.url, track.title)
                } else if (currentIndex == 0 && playlist.isNotEmpty()) {
                    // 如果在第一首，重新播放当前曲目
                    val track = playlist[0]
                    playTrack(track.url, track.title)
                }
            }
        }
    }

    private fun playNextTrack() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "playNextTrack: playlist is empty")
            return
        }
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            val track = playlist[currentIndex]
            playTrack(track.url, track.title)
        } else {
            // End of playlist
            stop()
        }
    }

    private fun getRandomIndex(): Int {
        if (playlist.size <= 1) return currentIndex
        var randomIndex: Int
        do {
            randomIndex = Random.nextInt(playlist.size)
        } while (randomIndex == currentIndex)
        return randomIndex
    }

    private fun initLoopHistory(index: Int) {
        loopHistory.clear()
        if (index >= 0) loopHistory.add(index)
        loopHistPos = if (loopHistory.isEmpty()) -1 else 0
    }

    private fun playRandomTrack() {
        if (playlist.isNotEmpty()) {
            currentIndex = getRandomIndex()
            val track = playlist[currentIndex]
            playTrack(track.url, track.title)
        } else {
            Log.w(TAG, "playRandomTrack: playlist is empty")
        }
    }

    private fun setPlayMode(mode: String) {
        val oldMode = playMode
        playMode = when (mode.uppercase()) {
            "LOOP" -> PlayMode.LOOP
            "RANDOM" -> PlayMode.RANDOM
            else -> PlayMode.LOOP  // 默认为单曲循环
        }
        // 切换到单曲循环模式时，以当前曲目为起点初始化导航历史
        if (playMode == PlayMode.LOOP) {
            initLoopHistory(currentIndex)
        }
        notifyWebViewOfStateChange()
        Log.d(TAG, "Play mode changed from $oldMode to $playMode (input: $mode)")

        // 显示Toast提示用户
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                applicationContext,
                "播放模式: ${when(playMode) {
                    PlayMode.LOOP -> "单曲循环"
                    PlayMode.RANDOM -> "随机播放"
                }}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stop() {
        stopProgressUpdates()
        mediaPlayer.stop()
        mediaPlayer.reset()
        currentTrack = null
        stopForeground(true)
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        notifyWebViewOfStateChange()
        Log.d(TAG, "Playback stopped")
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressUpdateRunnable)
        progressHandler.post(progressUpdateRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressUpdateRunnable)
    }

    private fun handleTrackCompletion() {
        Log.d(TAG, "====== Track completed, playMode: $playMode ======")

        // 显示Toast提示（调试用）
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                applicationContext,
                "歌曲结束 - 模式: ${when(playMode) {
                    PlayMode.LOOP -> "单曲循环"
                    PlayMode.RANDOM -> "随机"
                }}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        when (playMode) {
            PlayMode.LOOP -> {
                // 单曲循环：重新播放当前曲目
                Log.d(TAG, "Looping current track")
                currentTrack?.let { track ->
                    // 使用重新准备的方式而不是seekTo，更可靠
                    try {
                        mediaPlayer.reset()
                        val fullUrl = convertToFullUrl(track.url)
                        Log.d(TAG, "Reloading URL for loop: $fullUrl")
                        mediaPlayer.setDataSource(fullUrl)
                        mediaPlayer.prepareAsync()
                        Log.d(TAG, "Restarting track in loop mode: ${track.title}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error looping track", e)
                        // 如果重新准备失败，尝试使用seekTo作为备选方案
                        try {
                            mediaPlayer.seekTo(0)
                            mediaPlayer.start()
                        } catch (e2: Exception) {
                            Log.e(TAG, "Error seeking to start", e2)
                            notifyWebViewOfError()
                        }
                    }
                } ?: run {
                    Log.w(TAG, "No current track to loop")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "错误: 没有当前曲目可以循环",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            PlayMode.RANDOM -> {
                Log.d(TAG, "Playing random track")
                playRandomTrack()
            }
        }
    }

    private fun updateMediaSession() {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(
                if (mediaPlayer.isPlaying) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED,
                mediaPlayer.currentPosition.toLong(),
                1.0f
            )
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    private fun updateNotification() {
        val notification = notificationManager.buildNotification(
            currentTrack?.title ?: "Unknown",
            mediaPlayer.isPlaying
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun notifyWebViewOfStateChange() {
        val state = getCurrentState()
        val intent = Intent(ACTION_PLAYBACK_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, state.toJson())
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun notifyWebViewOfError() {
        val intent = Intent(ACTION_PLAYBACK_ERROR)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun getCurrentState(): PlaybackState {
        return PlaybackState(
            isPlaying = mediaPlayer.isPlaying,
            currentPosition = mediaPlayer.currentPosition.toLong(),
            duration = mediaPlayer.duration.toLong(),
            currentTrack = currentTrack,
            playMode = playMode
        )
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

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        mediaPlayer.release()
        mediaSession.release()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        Log.d(TAG, "Service destroyed")
    }

    companion object {
        private const val TAG = "AudioPlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_UPDATE_INTERVAL = 1000L // 每秒更新一次进度

        const val ACTION_PLAY = "com.netdisk.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.netdisk.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.netdisk.app.ACTION_RESUME"
        const val ACTION_NEXT = "com.netdisk.app.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.netdisk.app.ACTION_PREVIOUS"
        const val ACTION_SEEK = "com.netdisk.app.ACTION_SEEK"
        const val ACTION_SET_MODE = "com.netdisk.app.ACTION_SET_MODE"
        const val ACTION_STOP = "com.netdisk.app.ACTION_STOP"

        const val ACTION_PLAYBACK_STATE_CHANGED = "com.netdisk.app.PLAYBACK_STATE_CHANGED"
        const val ACTION_PLAYBACK_ERROR = "com.netdisk.app.PLAYBACK_ERROR"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYLIST = "extra_playlist"
        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_PLAY_MODE = "extra_play_mode"
        const val EXTRA_STATE = "extra_state"
    }
}
