package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query("SELECT * FROM media_downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_downloads WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED') ORDER BY createdAt DESC")
    fun getActiveDownloads(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_downloads WHERE status = 'COMPLETED' AND isVault = :isVault ORDER BY completedAt DESC")
    fun getCompletedMedia(isVault: Boolean): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_downloads WHERE id = :id")
    suspend fun getById(id: String): MediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MediaEntity)

    @Update
    suspend fun update(entity: MediaEntity)

    @Delete
    suspend fun delete(entity: MediaEntity)

    @Query("UPDATE media_downloads SET progress = :progress, downloadedBytes = :downloaded, totalBytes = :total, speedBytesPerSec = :speed WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Int, downloaded: Long, total: Long, speed: Long)

    @Query("UPDATE media_downloads SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)

    @Query("UPDATE media_downloads SET isVault = :isVault, filePath = :newPath WHERE id = :id")
    suspend fun updateVaultStatus(id: String, isVault: Boolean, newPath: String)
}
