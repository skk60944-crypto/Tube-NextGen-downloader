package com.example.service

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.model.MediaEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = AppDatabase.getInstance(context)
    private val mediaDao = database.mediaDao()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Active downloading coroutine jobs keyed by MediaEntity.id
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Current active download stats for notification & UI
    data class ActiveDownloadStats(
        val id: String,
        val title: String,
        val progress: Int,
        val speedBytesPerSec: Long,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    private val _activeDownloadsStats = MutableStateFlow<Map<String, ActiveDownloadStats>>(emptyMap())
    val activeDownloadsStats: StateFlow<Map<String, ActiveDownloadStats>> = _activeDownloadsStats.asStateFlow()

    fun enqueueDownload(entity: MediaEntity) {
        scope.launch {
            mediaDao.insert(entity.copy(status = "QUEUED"))
            startDownloadJob(entity.id)
        }
    }

    fun pauseDownload(id: String) {
        val job = activeJobs.remove(id)
        job?.cancel()
        scope.launch {
            mediaDao.updateStatus(id, "PAUSED")
            removeStats(id)
            try {
                DownloadService.checkServiceState(context)
            } catch (_: Exception) {}
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            val entity = mediaDao.getById(id) ?: return@launch
            if (entity.status == "PAUSED" || entity.status == "FAILED") {
                startDownloadJob(id)
            }
        }
    }

    fun cancelDownload(id: String) {
        val job = activeJobs.remove(id)
        job?.cancel()
        scope.launch {
            val entity = mediaDao.getById(id)
            if (entity != null) {
                // Remove partial file
                val partFile = File("${entity.filePath}.part")
                if (partFile.exists()) partFile.delete()
                val targetFile = File(entity.filePath)
                if (targetFile.exists()) targetFile.delete()
                mediaDao.delete(entity)
            }
            removeStats(id)
            try {
                DownloadService.checkServiceState(context)
            } catch (_: Exception) {}
        }
    }

    private fun startDownloadJob(id: String) {
        if (activeJobs.containsKey(id)) return

        val job = scope.launch {
            try {
                // Safely start ForegroundService without crashing if system denies it
                try {
                    DownloadService.start(context)
                } catch (e: Exception) {
                    Log.w("DownloadEngine", "Could not start ForegroundService: ${e.message}")
                }

                val entity = mediaDao.getById(id) ?: return@launch
                mediaDao.updateStatus(id, "DOWNLOADING")

                val targetFile = File(entity.filePath)
                targetFile.parentFile?.mkdirs()
                val partFile = File("${entity.filePath}.part")

                var existingBytes = if (partFile.exists()) partFile.length() else 0L

                val requestBuilder = Request.Builder()
                    .url(entity.downloadUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")

                if (existingBytes > 0) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                var response = client.newCall(requestBuilder.build()).execute()

                // If Range not satisfiable (416), delete partial file and retry fresh
                if (response.code == 416 && existingBytes > 0) {
                    response.close()
                    partFile.delete()
                    existingBytes = 0L
                    val freshRequest = Request.Builder()
                        .url(entity.downloadUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                        .header("Accept", "*/*")
                        .build()
                    response = client.newCall(freshRequest).execute()
                }

                response.use { resp ->
                    val isRange = resp.code == 206
                    val isSuccessful = resp.isSuccessful

                    if (!isSuccessful && !isRange) {
                        mediaDao.updateStatus(id, "FAILED", "Server returned HTTP ${resp.code}")
                        return@use
                    }

                    if (!isRange) {
                        // Server does not support Range header; restart fresh
                        existingBytes = 0L
                    }

                    val body = resp.body ?: throw IllegalStateException("Empty response body from media server")
                    val contentLength = body.contentLength()
                    val totalBytes = if (contentLength > 0) existingBytes + contentLength else entity.totalBytes

                    val outputStream = if (existingBytes > 0 && isRange) {
                        RandomAccessFile(partFile, "rw").apply { seek(existingBytes) }
                    } else {
                        RandomAccessFile(partFile, "rw").apply { setLength(0) }
                    }

                    val inputStream = body.byteStream()
                    val buffer = ByteArray(32 * 1024)
                    var totalDownloaded = existingBytes
                    var lastUpdate = System.currentTimeMillis()
                    var bytesSinceLastUpdate = 0L
                    var currentSpeed = 0L

                    outputStream.use { raf ->
                        var read = inputStream.read(buffer)
                        while (isActive && read != -1) {
                            raf.write(buffer, 0, read)
                            totalDownloaded += read
                            bytesSinceLastUpdate += read

                            val now = System.currentTimeMillis()
                            val timeDiff = now - lastUpdate
                            if (timeDiff >= 400) {
                                currentSpeed = (bytesSinceLastUpdate * 1000) / timeDiff.coerceAtLeast(1)
                                val progress = if (totalBytes > 0) {
                                    ((totalDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else 0

                                mediaDao.updateProgress(id, progress, totalDownloaded, totalBytes, currentSpeed)
                                updateStats(id, entity.title, progress, currentSpeed, totalDownloaded, totalBytes)

                                lastUpdate = now
                                bytesSinceLastUpdate = 0L
                            }
                            read = inputStream.read(buffer)
                        }
                    }

                    if (!isActive) {
                        return@launch
                    }

                    // Download complete: atomically move .part to final target file
                    if (partFile.exists()) {
                        if (targetFile.exists()) targetFile.delete()
                        partFile.renameTo(targetFile)
                    }

                    mediaDao.update(
                        entity.copy(
                            status = "COMPLETED",
                            progress = 100,
                            downloadedBytes = totalDownloaded,
                            totalBytes = totalDownloaded,
                            speedBytesPerSec = 0L,
                            completedAt = System.currentTimeMillis()
                        )
                    )
                    removeStats(id)
                }
            } catch (e: CancellationException) {
                // Handled in pause or cancel
            } catch (e: Exception) {
                Log.e("DownloadEngine", "Download failed for $id", e)
                mediaDao.updateStatus(id, "FAILED", e.localizedMessage ?: "Network connection error")
                removeStats(id)
            } finally {
                activeJobs.remove(id)
                try {
                    DownloadService.checkServiceState(context)
                } catch (_: Exception) {}
            }
        }

        activeJobs[id] = job
    }

    private fun updateStats(id: String, title: String, progress: Int, speed: Long, downloaded: Long, total: Long) {
        val current = _activeDownloadsStats.value.toMutableMap()
        current[id] = ActiveDownloadStats(id, title, progress, speed, downloaded, total)
        _activeDownloadsStats.value = current
    }

    private fun removeStats(id: String) {
        val current = _activeDownloadsStats.value.toMutableMap()
        current.remove(id)
        _activeDownloadsStats.value = current
    }

    fun hasActiveDownloads(): Boolean = activeJobs.isNotEmpty()

    companion object {
        @Volatile
        private var INSTANCE: DownloadEngine? = null

        fun getInstance(context: Context): DownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
