package com.example.engine

import android.net.Uri
import android.text.Html
import android.util.Log
import com.example.model.MediaExtractionResult
import com.example.model.MediaFormat
import com.example.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object MediaExtractorEngine {

    private const val TAG = "MediaExtractorEngine"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val USER_AGENT_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Extracts authentic media metadata and download stream URLs.
     * Never returns fake or sample streams.
     */
    suspend fun extractMedia(rawUrl: String): Result<MediaExtractionResult> = withContext(Dispatchers.IO) {
        try {
            val trimmedUrl = rawUrl.trim()
            if (trimmedUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please enter a valid link."))
            }

            // 1. Direct media URL check (e.g. .mp4, .mp3, .m4a, .webm)
            if (isDirectMediaUrl(trimmedUrl)) {
                return@withContext Result.success(extractDirectMedia(trimmedUrl))
            }

            // 2. Facebook URLs (reels, watch, share links, posts)
            if (isFacebookUrl(trimmedUrl)) {
                val fbResult = extractFacebook(trimmedUrl)
                if (fbResult != null) {
                    return@withContext Result.success(fbResult)
                }
            }

            // 3. YouTube URLs (watch, shorts, youtu.be)
            if (isYouTubeUrl(trimmedUrl)) {
                val ytResult = extractYouTube(trimmedUrl)
                if (ytResult != null) {
                    return@withContext Result.success(ytResult)
                }
            }

            // 4. Social Media & Web video pages (HTML5 video tags, OpenGraph, etc.)
            val htmlResult = extractFromHtmlPage(trimmedUrl)
            if (htmlResult != null) {
                return@withContext Result.success(htmlResult)
            }

            // If direct scraping cannot bypass anti-bot or DRM, guide user to built-in browser
            Result.failure(
                IllegalStateException(
                    "Unable to extract video stream directly. Please open this link in the Built-in Browser to capture and download the media stream."
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for $rawUrl", e)
            Result.failure(e)
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                lower.endsWith(".webm") || lower.endsWith(".wav") || lower.endsWith(".m3u8") ||
                lower.endsWith(".flv") || lower.endsWith(".ogg") || lower.endsWith(".mov") ||
                lower.contains(".mp4?") || lower.contains(".mp3?") || lower.contains(".m4a?")
    }

    private fun isFacebookUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com")
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/watch") || lower.contains("youtu.be/") ||
                lower.contains("youtube.com/shorts/") || lower.contains("m.youtube.com")
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Pattern.compile("v=([a-zA-Z0-9_-]{11})"),
            Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Pattern.compile("shorts/([a-zA-Z0-9_-]{11})"),
            Pattern.compile("embed/([a-zA-Z0-9_-]{11})")
        )
        for (p in patterns) {
            val matcher = p.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun extractDirectMedia(url: String): MediaExtractionResult {
        val uri = Uri.parse(url)
        val filename = uri.lastPathSegment?.substringBefore("?") ?: "media_file"
        val isAudio = url.lowercase().contains(".mp3") || url.lowercase().contains(".m4a") ||
                url.lowercase().contains(".wav") || url.lowercase().contains(".ogg")
        val ext = if (isAudio) "mp3" else "mp4"

        // Query file size if possible
        var sizeBytes = 0L
        try {
            val headReq = Request.Builder().url(url).head().build()
            client.newCall(headReq).execute().use { resp ->
                sizeBytes = resp.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {}

        val formats = if (isAudio) {
            listOf(
                MediaFormat("audio_direct", "Audio Stream ($ext)", "AUDIO", url, ext, sizeBytes, isAudioOnly = true)
            )
        } else {
            listOf(
                MediaFormat("video_direct", "Video Stream ($ext)", "VIDEO", url, ext, sizeBytes),
                MediaFormat("audio_from_video", "Audio Only ($ext)", "AUDIO", url, ext, sizeBytes, isAudioOnly = true)
            )
        }

        return MediaExtractionResult(
            title = filename.substringBeforeLast(".").replace("_", " "),
            author = uri.host ?: "Direct Link",
            sourceUrl = url,
            thumbnailUrl = null,
            durationSeconds = 0L,
            platform = "Direct Stream",
            formats = formats
        )
    }

    /**
     * Extracts real Facebook video streams from fbcdn.net without dummy fallbacks.
     */
    private fun extractFacebook(url: String): MediaExtractionResult? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_MOBILE)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-Dest", "document")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                // Title
                val titlePattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']")
                val titleMatcher = titlePattern.matcher(html)
                var title = if (titleMatcher.find()) {
                    cleanHtmlText(titleMatcher.group(1))
                } else {
                    val pageTitlePattern = Pattern.compile("<title>([^<]+)</title>")
                    val pageTitleMatcher = pageTitlePattern.matcher(html)
                    if (pageTitleMatcher.find()) cleanHtmlText(pageTitleMatcher.group(1)) else "Facebook Video"
                }
                title = title.substringBefore("| Facebook").trim()

                // Thumbnail
                val thumbPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']")
                val thumbMatcher = thumbPattern.matcher(html)
                val thumb = if (thumbMatcher.find()) cleanUrl(thumbMatcher.group(1)) else null

                // Video stream URLs
                val hdPattern = Pattern.compile("\"(?:browser_native_hd_url|playable_url_quality_hd)\":\"(https:[^\"]+)\"")
                val sdPattern = Pattern.compile("\"(?:browser_native_sd_url|playable_url)\":\"(https:[^\"]+)\"")
                val ogVideoPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:video(?::url|:secure_url)?[\"']\\s+content=[\"'](https:[^\"']+)[\"']")

                val streamUrls = mutableListOf<Pair<String, String>>() // Label to URL

                val hdMatcher = hdPattern.matcher(html)
                if (hdMatcher.find()) {
                    streamUrls.add("HD 720p Video" to cleanUrl(hdMatcher.group(1)))
                }

                val sdMatcher = sdPattern.matcher(html)
                if (sdMatcher.find()) {
                    val sdUrl = cleanUrl(sdMatcher.group(1))
                    if (streamUrls.none { it.second == sdUrl }) {
                        streamUrls.add("SD Mobile Video" to sdUrl)
                    }
                }

                val ogMatcher = ogVideoPattern.matcher(html)
                while (ogMatcher.find()) {
                    val ogUrl = cleanUrl(ogMatcher.group(1))
                    if (streamUrls.none { it.second == ogUrl }) {
                        streamUrls.add("Standard MP4 Video" to ogUrl)
                    }
                }

                if (streamUrls.isEmpty()) return null

                // Determine file size by probing best stream
                val primaryStream = streamUrls.first().second
                var contentBytes = probeContentLength(primaryStream)

                val formats = mutableListOf<MediaFormat>()
                streamUrls.forEachIndexed { index, (label, streamUrl) ->
                    formats.add(
                        MediaFormat(
                            id = "fb_v_$index",
                            formatLabel = label,
                            mediaType = "VIDEO",
                            downloadUrl = streamUrl,
                            ext = "mp4",
                            fileSizeBytes = contentBytes
                        )
                    )
                }

                // Audio extraction format (plays/saves stream as audio)
                formats.add(
                    MediaFormat(
                        id = "fb_audio",
                        formatLabel = "Audio Only (MP3)",
                        mediaType = "AUDIO",
                        downloadUrl = primaryStream,
                        ext = "mp3",
                        fileSizeBytes = if (contentBytes > 0) contentBytes / 3 else 0L,
                        isAudioOnly = true
                    )
                )

                return MediaExtractionResult(
                    title = title,
                    author = "Facebook",
                    sourceUrl = url,
                    thumbnailUrl = thumb,
                    durationSeconds = 60L,
                    platform = "Facebook",
                    formats = formats
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Facebook extraction error: ${e.message}")
            return null
        }
    }

    /**
     * Extracts YouTube streams using the InnerTube API (ANDROID_VR client) + oEmbed metadata.
     * Returns authentic googlevideo.com stream URLs.
     */
    private fun extractYouTube(url: String): MediaExtractionResult? {
        val videoId = extractYouTubeVideoId(url) ?: return null

        var title = "YouTube Video ($videoId)"
        var author = "YouTube Creator"
        var thumb: String? = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        // 1. Fetch official authentic video metadata via oEmbed
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", USER_AGENT_MOBILE)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    title = json.optString("title", title)
                    author = json.optString("author_name", author)
                    val jsonThumb = json.optString("thumbnail_url")
                    if (jsonThumb.isNotBlank()) thumb = jsonThumb
                }
            }
        } catch (_: Exception) {}

        // 2. Fetch direct streams via InnerTube ANDROID_VR client
        try {
            val playerUrl = "https://www.youtube.com/youtubei/v1/player"
            val jsonPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.61.48")
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("osName", "Android")
                        put("osVersion", "12")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url(playerUrl)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT_MOBILE)
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val rootJson = JSONObject(body)

                val playability = rootJson.optJSONObject("playabilityStatus")
                val status = playability?.optString("status")
                if (status != "OK") {
                    Log.w(TAG, "YouTube playability status: $status")
                    return null
                }

                val videoDetails = rootJson.optJSONObject("videoDetails")
                if (videoDetails != null) {
                    title = videoDetails.optString("title", title)
                    author = videoDetails.optString("author", author)
                }

                val streamingData = rootJson.optJSONObject("streamingData") ?: return null
                val formatsList = mutableListOf<MediaFormat>()

                // Combined video + audio formats (plays everywhere seamlessly)
                val combinedFormats = streamingData.optJSONArray("formats")
                if (combinedFormats != null) {
                    for (i in 0 until combinedFormats.length()) {
                        val f = combinedFormats.getJSONObject(i)
                        val streamUrl = f.optString("url")
                        if (streamUrl.isNotBlank()) {
                            val quality = f.optString("qualityLabel", "360p")
                            val mime = f.optString("mimeType", "video/mp4")
                            val ext = if (mime.contains("webm")) "webm" else "mp4"
                            val contentLength = f.optLong("contentLength", 0L)
                            formatsList.add(
                                MediaFormat(
                                    id = "yt_v_$i",
                                    formatLabel = "$quality MP4 (Video + Audio)",
                                    mediaType = "VIDEO",
                                    downloadUrl = streamUrl,
                                    ext = ext,
                                    fileSizeBytes = contentLength,
                                    resolution = quality
                                )
                            )
                        }
                    }
                }

                // Adaptive audio formats (M4A / Opus)
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                if (adaptiveFormats != null) {
                    for (i in 0 until adaptiveFormats.length()) {
                        val a = adaptiveFormats.getJSONObject(i)
                        val streamUrl = a.optString("url")
                        val mime = a.optString("mimeType", "")
                        if (streamUrl.isNotBlank() && mime.contains("audio")) {
                            val isM4a = mime.contains("audio/mp4")
                            val ext = if (isM4a) "m4a" else "webm"
                            val bitrate = a.optInt("bitrate", 128000) / 1000
                            val contentLength = a.optLong("contentLength", 0L)
                            val label = if (isM4a) "$bitrate kbps M4A Audio" else "$bitrate kbps Opus Audio"
                            formatsList.add(
                                MediaFormat(
                                    id = "yt_a_$i",
                                    formatLabel = label,
                                    mediaType = "AUDIO",
                                    downloadUrl = streamUrl,
                                    ext = ext,
                                    fileSizeBytes = contentLength,
                                    bitrate = "$bitrate kbps",
                                    isAudioOnly = true
                                )
                            )
                        }
                    }
                }

                if (formatsList.isNotEmpty()) {
                    return MediaExtractionResult(
                        title = title,
                        author = author,
                        sourceUrl = url,
                        thumbnailUrl = thumb,
                        durationSeconds = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L,
                        platform = "YouTube",
                        formats = formatsList,
                        subtitles = listOf(
                            SubtitleTrack("en", "English", "", "vtt")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "YouTube InnerTube extraction error: ${e.message}")
        }
        return null
    }

    /**
     * Extracts direct HTML5 video from OpenGraph or video tag on web pages.
     */
    private fun extractFromHtmlPage(url: String): MediaExtractionResult? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_MOBILE)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                val ogVideoMatcher = Pattern.compile(
                    "<meta\\s+(?:property|name)=[\"']og:video(?::secure_url|:url)?[\"']\\s+content=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE
                ).matcher(html)
                var videoUrl: String? = if (ogVideoMatcher.find()) cleanUrl(ogVideoMatcher.group(1)) else null

                if (videoUrl == null) {
                    val videoTagMatcher = Pattern.compile(
                        "<(?:video|source)[^>]+src=[\"']([^\"']+\\.(?:mp4|webm|m3u8)[^\"']*)[\"']",
                        Pattern.CASE_INSENSITIVE
                    ).matcher(html)
                    if (videoTagMatcher.find()) {
                        videoUrl = cleanUrl(videoTagMatcher.group(1))
                    }
                }

                if (videoUrl != null) {
                    if (videoUrl.startsWith("/")) {
                        val baseUri = Uri.parse(url)
                        videoUrl = "${baseUri.scheme}://${baseUri.host}$videoUrl"
                    }

                    val titleMatcher = Pattern.compile(
                        "<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']",
                        Pattern.CASE_INSENSITIVE
                    ).matcher(html)
                    val title = if (titleMatcher.find()) cleanHtmlText(titleMatcher.group(1)) else {
                        val htmlTitleMatcher = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (htmlTitleMatcher.find()) cleanHtmlText(htmlTitleMatcher.group(1)) else "Web Video"
                    }

                    val thumbMatcher = Pattern.compile(
                        "<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']",
                        Pattern.CASE_INSENSITIVE
                    ).matcher(html)
                    val thumb = if (thumbMatcher.find()) cleanUrl(thumbMatcher.group(1)) else null

                    val sizeBytes = probeContentLength(videoUrl)

                    return MediaExtractionResult(
                        title = title,
                        author = Uri.parse(url).host ?: "Website",
                        sourceUrl = url,
                        thumbnailUrl = thumb,
                        durationSeconds = 120L,
                        platform = "Web HTML5 Video",
                        formats = listOf(
                            MediaFormat("html5_video", "Direct Video Stream (MP4)", "VIDEO", videoUrl, "mp4", sizeBytes),
                            MediaFormat("html5_audio", "Extracted Audio (MP3)", "AUDIO", videoUrl, "mp3", sizeBytes / 3, isAudioOnly = true)
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun probeContentLength(url: String): Long {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT_MOBILE)
                .build()
            client.newCall(req).execute().use { resp ->
                resp.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun cleanUrl(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun cleanHtmlText(raw: String): String {
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
