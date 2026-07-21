package com.netdisk.app.models

import com.google.gson.Gson

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,  // Position in milliseconds
    val duration: Long = 0L,         // Duration in milliseconds
    val currentTrack: AudioTrack? = null,
    val playMode: PlayMode = PlayMode.LOOP
) {
    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String): PlaybackState {
            return Gson().fromJson(json, PlaybackState::class.java)
        }
    }
}

enum class PlayMode {
    LOOP,      // Loop current track
    RANDOM     // Random playback
}
