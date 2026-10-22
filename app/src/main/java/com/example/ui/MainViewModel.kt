package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.AppDatabase
import com.example.engine.MediaExtractorEngine
import com.example.model.MediaEntity
import com.example.model.MediaExtractionResult
import com.example.model.MediaFormat
import com.example.service.AudioPlaybackService
import com.example.service.DownloadEngine
import com.example.vault.VaultManager
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val mediaDao = database.mediaDao()
    private val downloadEngine = DownloadEngine.getInstance(application)
    val vaultManager = VaultManager.getInstance(application)

    // Room database flows
    val activeDownloads: StateFlow<List<MediaEntity>> = mediaDao.getActiveDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedMedia: StateFlow<List<MediaEntity>> = mediaDao.getCompletedMedia(isVault = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vaultMedia: StateFlow<List<MediaEntity>> = mediaDao.getCompletedMedia(isVault = true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isVaultUnlocked = vaultManager.isUnlocked

    // Media Extraction State
    private val _inputUrl = MutableStateFlow("")
    val inputUrl: StateFlow<String> = _inputUrl.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _extractionResult = MutableStateFlow<MediaExtractionResult?>(null)
    val extractionResult: StateFlow<MediaExtractionResult?> = _extractionResult.asStateFlow()

    private val _analyzeError = MutableStateFlow<String?>(null)
    val analyzeError: StateFlow<String?> = _analyzeError.asStateFlow()

    private val _showFormatDialog = MutableStateFlow(false)
    val showFormatDialog: StateFlow<Boolean> = _showFormatDialog.asStateFlow()

    // Browser Media Sniffer State
    private val _browserUrl = MutableStateFlow("https://m.youtube.com")
    val browserUrl: StateFlow<String> = _browserUrl.asStateFlow()

    private val _detectedBrowserStreams = MutableStateFlow<List<MediaFormat>>(emptyList())
    val detectedBrowserStreams: StateFlow<List<MediaFormat>> = _detectedBrowserStreams.asStateFlow()

    private val _detectedMediaTitle = MutableStateFlow("Web Stream")
    val detectedMediaTitle: StateFlow<String> = _detectedMediaTitle.asStateFlow()

    private val _showBrowserSnifferSheet = MutableStateFlow(false)
    val showBrowserSnifferSheet: StateFlow<Boolean> = _showBrowserSnifferSheet.asStateFlow()

    // Player State
    private val _currentlyPlaying = MutableStateFlow<MediaEntity?>(null)
    val currentlyPlaying: StateFlow<MediaEntity?> = _currentlyPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isSubtitlesEnabled = MutableStateFlow(false)
    val isSubtitlesEnabled: StateFlow<Boolean> = _isSubtitlesEnabled.asStateFlow()

    private val _isPlayerFullScreen = MutableStateFlow(false)
    val isPlayerFullScreen: StateFlow<Boolean> = _isPlayerFullScreen.asStateFlow()

    // MediaController connected to AudioPlaybackService
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var mediaController: MediaController? = null
        private set

    private var playerProgressJob: Job? = null

    init {
        initializeMediaController()
    }

    private fun initializeMediaController() {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), AudioPlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupPlayerListener()
            } catch (_: Exception) {}
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) startPositionTracking() else stopPositionTracking()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = mediaController?.duration?.coerceAtLeast(0L) ?: 0L
                }
            }
        })
    }

    private fun startPositionTracking() {
        playerProgressJob?.cancel()
        playerProgressJob = viewModelScope.launch {
            while (isActive) {
                mediaController?.let {
                    _currentPositionMs.value = it.currentPosition.coerceAtLeast(0L)
                    _durationMs.value = it.duration.coerceAtLeast(0L)
                }
                delay(300)
            }
        }
    }

    private fun stopPositionTracking() {
        playerProgressJob?.cancel()
    }

    fun onUrlInputChanged(url: String) {
        _inputUrl.value = url
        _analyzeError.value = null
    }

    fun analyzeUrl(url: String? = null) {
        val target = url ?: _inputUrl.value
        if (target.isBlank()) {
            _analyzeError.value = "Please enter or paste a valid video/audio link."
            return
        }

        viewModelScope.launch {
            _isAnalyzing.value = true
            _analyzeError.value = null
            _extractionResult.value = null

            val result = MediaExtractorEngine.extractMedia(target)
            result.onSuccess {
                _extractionResult.value = it
                _showFormatDialog.value = true
            }.onFailure {
                _analyzeError.value = it.localizedMessage ?: "Failed to extract media. Check the link and your connection."
            }
            _isAnalyzing.value = false
        }
    }

    fun dismissFormatDialog() {
        _showFormatDialog.value = false
    }

    fun startDownload(
        format: MediaFormat,
        title: String,
        sourceUrl: String,
        thumbnailUrl: String?,
        subtitlesUrl: String?,
        saveToVault: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
            val id = UUID.randomUUID().toString()
            val extension = format.ext.ifBlank { if (format.isAudioOnly) "mp3" else "mp4" }

            val targetDir = if (saveToVault) {
                File(getApplication<Application>().filesDir, "vault_storage")
            } else {
                File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir, "downloads")
            }
            targetDir.mkdirs()

            val fileName = if (saveToVault) "vlt_${id}.${extension}" else "${safeTitle}_${id.take(6)}.${extension}"
            val filePath = File(targetDir, fileName).absolutePath

            val entity = MediaEntity(
                id = id,
                title = title,
                sourceUrl = sourceUrl,
                downloadUrl = format.downloadUrl,
                filePath = filePath,
                mediaType = if (format.isAudioOnly) "AUDIO" else "VIDEO",
                qualityLabel = format.formatLabel,
                totalBytes = format.fileSizeBytes,
                downloadedBytes = 0L,
                progress = 0,
                speedBytesPerSec = 0L,
                status = "QUEUED",
                thumbnailUrl = thumbnailUrl,
                durationSeconds = 0L,
                subtitlesUrl = subtitlesUrl,
                isVault = saveToVault
            )

            downloadEngine.enqueueDownload(entity)
            withContext(Dispatchers.Main) {
                _showFormatDialog.value = false
                _showBrowserSnifferSheet.value = false
            }
        }
    }

    fun pauseDownload(id: String) {
        downloadEngine.pauseDownload(id)
    }

    fun resumeDownload(id: String) {
        downloadEngine.resumeDownload(id)
    }

    fun cancelDownload(id: String) {
        downloadEngine.cancelDownload(id)
    }

    fun deleteMedia(entity: MediaEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(entity.filePath)
                if (file.exists()) file.delete()
                val part = File("${entity.filePath}.part")
                if (part.exists()) part.delete()
            } catch (_: Exception) {}
            mediaDao.delete(entity)
            if (_currentlyPlaying.value?.id == entity.id) {
                closePlayer()
            }
        }
    }

    fun moveToVault(entity: MediaEntity) {
        viewModelScope.launch {
            vaultManager.moveToVault(entity)
        }
    }

    fun restoreFromVault(entity: MediaEntity) {
        viewModelScope.launch {
            vaultManager.restoreFromVault(entity)
        }
    }

    // Browser Media Sniffer
    fun onBrowserUrlChanged(url: String) {
        _browserUrl.value = url
    }

    fun onBrowserMediaDetected(title: String, url: String, type: String) {
        val isAudio = type.contains("audio") || url.endsWith(".mp3") || url.endsWith(".m4a")
        val ext = if (isAudio) "mp3" else "mp4"

        val formats = listOf(
            MediaFormat(
                id = "detected_stream_high",
                formatLabel = if (isAudio) "Audio Stream (HQ)" else "Video Stream (Detected)",
                mediaType = if (isAudio) "AUDIO" else "VIDEO",
                downloadUrl = url,
                ext = ext,
                isAudioOnly = isAudio
            ),
            MediaFormat(
                id = "detected_audio_extract",
                formatLabel = "Audio Only (MP3)",
                mediaType = "AUDIO",
                downloadUrl = url,
                ext = "mp3",
                isAudioOnly = true
            )
        )

        _detectedMediaTitle.value = title.ifBlank { "Detected Stream" }
        _detectedBrowserStreams.value = formats
        _showBrowserSnifferSheet.value = true
    }

    fun dismissBrowserSniffer() {
        _showBrowserSnifferSheet.value = false
    }

    // Playback Controls
    fun playMedia(media: MediaEntity) {
        _currentlyPlaying.value = media
        val uri = if (File(media.filePath).exists()) {
            Uri.fromFile(File(media.filePath))
        } else {
            Uri.parse(media.downloadUrl)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.qualityLabel)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .apply {
                if (!media.subtitlesUrl.isNullOrBlank()) {
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(media.subtitlesUrl))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    setSubtitleConfigurations(listOf(subtitleConfig))
                }
            }
            .build()

        mediaController?.run {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        mediaController?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    fun seekRelative(offsetMs: Long) {
        mediaController?.let {
            val target = (it.currentPosition + offsetMs).coerceIn(0L, it.duration)
            it.seekTo(target)
            _currentPositionMs.value = target
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        mediaController?.setPlaybackSpeed(speed)
    }

    fun toggleSubtitles() {
        _isSubtitlesEnabled.value = !_isSubtitlesEnabled.value
    }

    fun toggleFullScreen() {
        _isPlayerFullScreen.value = !_isPlayerFullScreen.value
    }

    fun closePlayer() {
        mediaController?.stop()
        _currentlyPlaying.value = null
        _isPlaying.value = false
        _isPlayerFullScreen.value = false
        stopPositionTracking()
    }

    override fun onCleared() {
        mediaController?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
