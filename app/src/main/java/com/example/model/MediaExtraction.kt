package com.example.model

data class MediaFormat(
    val id: String,
    val formatLabel: String,
    val mediaType: String, // "VIDEO" or "AUDIO"
    val downloadUrl: String,
    val ext: String, // "mp4", "mp3", "m4a", "webm"
    val fileSizeBytes: Long = 0L,
    val resolution: String = "",
    val bitrate: String = "",
    val isAudioOnly: Boolean = false
)

data class SubtitleTrack(
    val language: String,
    val label: String,
    val url: String,
    val format: String = "vtt" // "vtt" or "srt"
)

data class MediaExtractionResult(
    val title: String,
    val author: String,
    val sourceUrl: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val platform: String,
    val formats: List<MediaFormat>,
    val subtitles: List<SubtitleTrack> = emptyList()
)
