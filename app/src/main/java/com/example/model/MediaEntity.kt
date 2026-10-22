package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_downloads")
data class MediaEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val filePath: String,
    val mediaType: String, // "VIDEO" or "AUDIO"
    val qualityLabel: String, // e.g. "1080p FHD", "720p HD", "320 kbps MP3"
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progress: Int = 0, // 0 - 100
    val speedBytesPerSec: Long = 0L,
    val status: String = "QUEUED", // "QUEUED", "DOWNLOADING", "PAUSED", "COMPLETED", "FAILED"
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
    val subtitlesUrl: String? = null,
    val isVault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null
)
