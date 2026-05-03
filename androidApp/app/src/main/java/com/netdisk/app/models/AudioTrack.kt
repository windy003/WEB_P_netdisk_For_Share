package com.netdisk.app.models

data class AudioTrack(
    val url: String,
    val title: String,
    val duration: Long = 0L  // Duration in milliseconds
)
