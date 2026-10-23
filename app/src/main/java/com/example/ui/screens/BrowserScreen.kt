package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.MainViewModel
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.ElectricCyan

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: MainViewModel,
    initialUrl: String? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val browserUrl by viewModel.browserUrl.collectAsState()
    val showSnifferSheet by viewModel.showBrowserSnifferSheet.collectAsState()
    val detectedStreams by viewModel.detectedBrowserStreams.collectAsState()
    val detectedTitle by viewModel.detectedMediaTitle.collectAsState()

    var inputUrlText by remember { mutableStateOf(initialUrl ?: browserUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    val bookmarks = listOf(
        "YouTube" to "https://m.youtube.com",
        "TikTok" to "https://www.tiktok.com",
        "Vimeo" to "https://vimeo.com/watch",
        "SoundCloud" to "https://soundcloud.com",
        "Archive.org" to "https://archive.org/details/movies",
        "Reddit" to "https://www.reddit.com/r/videos"
    )

    Column(modifier = Modifier.fillMaxSize().testTag("browser_screen")) {
        // Address Bar & Controls
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }

                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }

                    IconButton(
                        onClick = { webViewRef?.reload() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }

                    OutlinedTextField(
                        value = inputUrlText,
                        onValueChange = { inputUrlText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("browser_url_input"),
                        singleLine = true,
                        placeholder = { Text("Search or enter web URL", fontSize = 13.sp) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CrimsonRed,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                val url = if (!inputUrlText.startsWith("http://") && !inputUrlText.startsWith("https://")) {
                                    if (inputUrlText.contains(".")) "https://$inputUrlText"
                                    else "https://duckduckgo.com/?q=${Uri.encode(inputUrlText)}"
                                } else inputUrlText
                                inputUrlText = url
                                webViewRef?.loadUrl(url)
                            }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Go",
                                    tint = CrimsonRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                }

                // Bookmarks Row
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(bookmarks) { (name, url) ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.clickable {
                                inputUrlText = url
                                webViewRef?.loadUrl(url)
                            }
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Page Loading Progress
        if (pageProgress in 1..99) {
            LinearProgressIndicator(
                progress = { pageProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = CrimsonRed
            )
        }

        // Web Browser View & Floating Sniffer Pill
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let {
                                    inputUrlText = it
                                    viewModel.onBrowserUrlChanged(it)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true

                                // Inject sniffer script to inspect video tags and performance network entries
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        var videos = document.getElementsByTagName('video');
                                        if (videos.length > 0 && videos[0].src) {
                                            return JSON.stringify({title: document.title, url: videos[0].src, type: 'video'});
                                        }
                                        var meta = document.querySelector('meta[property="og:video"]');
                                        if (meta && meta.content) {
                                            return JSON.stringify({title: document.title, url: meta.content, type: 'video'});
                                        }
                                        return null;
                                    })();
                                    """.trimIndent()
                                ) { result ->
                                    if (result != null && result != "null" && result.isNotBlank()) {
                                        try {
                                            val clean = result.trim('"').replace("\\\"", "\"")
                                            val json = org.json.JSONObject(clean)
                                            val title = json.optString("title", "Web Video")
                                            val streamUrl = json.optString("url")
                                            if (streamUrl.isNotBlank()) {
                                                viewModel.onBrowserMediaDetected(title, streamUrl, "video")
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: ""
                                val lower = reqUrl.lowercase()
                                if (lower.contains(".mp4") || lower.contains(".m4a") ||
                                    lower.contains(".m3u8") || lower.contains("googlevideo.com/videoplayback") ||
                                    lower.contains("v.redd.it") || lower.contains("tiktokcdn.com") ||
                                    lower.contains("fbcdn.net") || lower.contains("video.xx.fbcdn") ||
                                    lower.contains("cdninstagram.com")) {
                                    // Detected playable media stream
                                    view?.post {
                                        viewModel.onBrowserMediaDetected(
                                            title = view.title ?: "Detected Media Stream",
                                            url = reqUrl,
                                            type = if (lower.contains("audio") || lower.endsWith(".m4a")) "audio" else "video"
                                        )
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageProgress = newProgress
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }
                        }

                        loadUrl(initialUrl ?: browserUrl)
                        webViewRef = this
                    }
                },
                update = { webView ->
                    webViewRef = webView
                }
            )

            // Animated Media Sniffer Floating Pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showSnifferSheet && detectedStreams.isNotEmpty(),
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = CrimsonRed),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .clickable {
                            val stream = detectedStreams.firstOrNull()
                            if (stream != null) {
                                viewModel.startDownload(
                                    format = stream,
                                    title = detectedTitle,
                                    sourceUrl = inputUrlText,
                                    thumbnailUrl = null,
                                    subtitlesUrl = null,
                                    saveToVault = false
                                )
                                android.widget.Toast.makeText(
                                    context,
                                    "Download started! Check progress in Downloads tab.",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .testTag("media_sniffer_fab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download Detected Media",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Media Stream Detected!",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap to download $detectedTitle",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }
    }
}
}


