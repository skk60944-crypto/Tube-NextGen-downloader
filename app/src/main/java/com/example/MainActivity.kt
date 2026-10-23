package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownloadDone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownloadDone
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.MainViewModel
import com.example.ui.components.FormatPickerSheet
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.PlayerSheet
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.theme.TubeAIOTheme

enum class ScreenTab(val title: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector, val tag: String) {
    EXPLORE("Explore", Icons.Filled.Download, Icons.Outlined.Download, "nav_explore"),
    BROWSER("Browser", Icons.Filled.Public, Icons.Outlined.Public, "nav_browser"),
    DOWNLOADS("Downloads", Icons.Filled.FileDownloadDone, Icons.Outlined.FileDownloadDone, "nav_downloads"),
    VAULT("Vault", Icons.Filled.Lock, Icons.Outlined.Lock, "nav_vault"),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingShareIntent(intent)

        setContent {
            TubeAIOTheme {
                MainAppContainer(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.onUrlInputChanged(sharedText)
                viewModel.analyzeUrl(sharedText)
            }
        }
    }
}

@Composable
fun MainAppContainer(viewModel: MainViewModel) {
    var currentTab by remember { mutableStateOf(ScreenTab.EXPLORE) }
    var showFullPlayer by remember { mutableStateOf(false) }

    val showFormatDialog by viewModel.showFormatDialog.collectAsState()
    val extractionResult by viewModel.extractionResult.collectAsState()
    val currentlyPlaying by viewModel.currentlyPlaying.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()

    // Explore is the default/home tab. Pressing Back on any other tab returns to Explore first.
    BackHandler(enabled = !showFullPlayer && currentTab != ScreenTab.EXPLORE) {
        currentTab = ScreenTab.EXPLORE
    }

    // Request notification permission on Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { /* permission result handled gracefully */ }

        LaunchedEffect(Unit) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!showFullPlayer) {
                Column {
                    // Floating Mini Player Bar if media is playing
                    AnimatedVisibility(
                        visible = currentlyPlaying != null,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        currentlyPlaying?.let { media ->
                            MiniPlayerBar(
                                media = media,
                                viewModel = viewModel,
                                onExpand = { showFullPlayer = true }
                            )
                        }
                    }

                    // Bottom Navigation Bar
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .testTag("bottom_nav_bar")
                    ) {
                        ScreenTab.entries.forEach { tab ->
                            val isSelected = currentTab == tab
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { currentTab = tab },
                                icon = {
                                    if (tab == ScreenTab.DOWNLOADS && activeDownloads.isNotEmpty()) {
                                        BadgedBox(
                                            badge = {
                                                Badge { Text("${activeDownloads.size}") }
                                            }
                                        ) {
                                            Icon(
                                                if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tab.title
                                            )
                                        }
                                    } else {
                                        Icon(
                                            if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.title
                                        )
                                    }
                                },
                                label = { Text(tab.title) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                ScreenTab.EXPLORE -> ExploreScreen(
                    viewModel = viewModel,
                    onNavigateToBrowser = { url ->
                        viewModel.onBrowserUrlChanged(url)
                        currentTab = ScreenTab.BROWSER
                    },
                    onNavigateToDownloads = { currentTab = ScreenTab.DOWNLOADS }
                )
                ScreenTab.BROWSER -> BrowserScreen(
                    viewModel = viewModel
                )
                ScreenTab.DOWNLOADS -> DownloadsScreen(
                    viewModel = viewModel,
                    onNavigateToExplore = { currentTab = ScreenTab.EXPLORE }
                )
                ScreenTab.VAULT -> VaultScreen(
                    viewModel = viewModel
                )
                ScreenTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel
                )
            }
        }

        // Format Picker Bottom Sheet
        if (showFormatDialog && extractionResult != null) {
            FormatPickerSheet(
                extraction = extractionResult!!,
                onDismiss = { viewModel.dismissFormatDialog() },
                onStartDownload = { format, saveToVault ->
                    viewModel.startDownload(
                        format = format,
                        title = extractionResult!!.title,
                        sourceUrl = extractionResult!!.sourceUrl,
                        thumbnailUrl = extractionResult!!.thumbnailUrl,
                        subtitlesUrl = extractionResult!!.subtitles.firstOrNull()?.url,
                        saveToVault = saveToVault
                    )
                    android.widget.Toast.makeText(
                        context,
                        "Download started! Track progress in Downloads tab.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    currentTab = ScreenTab.DOWNLOADS
                }
            )
        }

        // Full In-App Player Overlay (Video with subtitles or Audio with visualizer)
        AnimatedVisibility(
            visible = showFullPlayer && currentlyPlaying != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
        ) {
            currentlyPlaying?.let { media ->
                PlayerSheet(
                    media = media,
                    viewModel = viewModel,
                    onClose = { showFullPlayer = false }
                )
            }
        }
    }
}
