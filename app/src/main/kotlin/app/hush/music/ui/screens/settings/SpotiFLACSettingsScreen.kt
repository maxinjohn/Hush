package app.hush.music.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hush.music.R
import app.hush.music.constants.DevModeKey
import app.hush.music.constants.SpotiFLACDevGateKey
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.SpotiFLACQualityKey
import app.hush.music.constants.YoutubeStreamingEnabledKey
import app.hush.music.spotiflac.ExtensionRepositoryManager
import app.hush.music.spotiflac.SessionState
import app.hush.music.spotiflac.SpotiFLACSessionManager
import app.hush.music.spotiflac.SourceTestState
import app.hush.music.spotiflac.SourceWithState
import app.hush.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotiFLACSettingsScreen(
    navController: androidx.navigation.NavController,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val (devMode, setDevMode) = rememberPreference(DevModeKey, defaultValue = false)
    val (spotiflacDevGate, setSpotiflacDevGate) = rememberPreference(SpotiFLACDevGateKey, defaultValue = false)
    val (spotiflacEnabled, setSpotiflacEnabled) = rememberPreference(SpotiFLACEnabledKey, defaultValue = false)
    val (spotiflacQuality, setSpotiflacQuality) = rememberPreference(SpotiFLACQualityKey, defaultValue = "BEST")
    val (youtubeEnabled, setYoutubeEnabled) = rememberPreference(YoutubeStreamingEnabledKey, defaultValue = true)

    val repoManager = remember { ExtensionRepositoryManager.getInstance() }
    val sessionManager = remember { SpotiFLACSessionManager.getInstance() }
    val sources by repoManager.sources.collectAsState()
    val isSyncing by repoManager.isSyncing.collectAsState()

    var sessionState by remember { mutableStateOf(sessionManager.sessionState) }
    var isBootstrapping by remember { mutableStateOf(false) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    var grantInput by remember { mutableStateOf("") }
    var showChallengeWebView by remember { mutableStateOf(false) }
    var isExchanging by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            repoManager.syncRegistries()
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "Audio Sources",
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(app.hush.music.R.drawable.arrow_back),
                            contentDescription = "Back",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(paddingValues),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "Dev Mode",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Unlock experimental audio sources",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchRow(
                        title = "Developer Mode",
                        checked = devMode,
                        onCheckedChange = setDevMode,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "YouTube",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Stream from YouTube Music",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchRow(
                        title = "YouTube Enabled",
                        checked = youtubeEnabled,
                        onCheckedChange = setYoutubeEnabled,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "SpotiFLAC",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Lossless audio from Tidal, Qobuz, Deezer & more via Spotify metadata",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AnimatedVisibility(visible = devMode) {
                        Column {
                            SettingsSwitchRow(
                                title = "Enable Dev Gate",
                                checked = spotiflacDevGate,
                                onCheckedChange = setSpotiflacDevGate,
                            )
                            SettingsSwitchRow(
                                title = "SpotiFLAC Enabled",
                                checked = spotiflacEnabled,
                                onCheckedChange = setSpotiflacEnabled,
                            )
                        }
                    }

                    AnimatedVisibility(visible = spotiflacEnabled && (!spotiflacDevGate || devMode)) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Audio Quality",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Falls back to lower quality if preferred is unavailable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            val qualities = listOf(
                                "BEST" to "Best Available",
                                "FLAC" to "Lossless FLAC",
                                "HIGH" to "High (320 kbps)",
                                "MEDIUM" to "Medium (160 kbps)",
                                "LOW" to "Low (96 kbps)",
                            )
                            qualities.forEach { (key, label) ->
                                SettingsRadioRow(
                                    title = label,
                                    selected = spotiflacQuality == key,
                                    onClick = { setSpotiflacQuality(key) },
                                )
                            }
                        }
                    }

                    if (!devMode) {
                        Text(
                            text = "Enable Developer Mode to access SpotiFLAC settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (spotiflacEnabled && (!spotiflacDevGate || devMode)) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = "Authentication",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Session required for downloading tracks via SpotiFLAC relay",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        when (sessionState) {
                            SessionState.ACTIVE -> {
                                Text(
                                    text = "Session Active",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.material3.TextButton(onClick = {
                                    scope.launch {
                                        sessionManager.clearSession()
                                        sessionState = SessionState.NONE
                                    }
                                }) {
                                    Text("Clear Session")
                                }
                            }
                            SessionState.CHALLENGE_PENDING -> {
                                val challengeUrl = sessionManager.challengeUrl
                                Text(
                                    text = "Verification Required",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = "Complete the Cloudflare verification below. The grant will be captured automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                if (isExchanging) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.height(16.dp).width(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Exchanging grant...",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                } else if (showChallengeWebView && challengeUrl != null) {
                                    var grantCaptured by remember { mutableStateOf(false) }
                                    val capturedGrant = remember { mutableStateOf("") }

                                    androidx.compose.material3.Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(400.dp),
                                    ) {
                                        val webView = remember {
                                            android.webkit.WebView(context).apply {
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true

                                                webViewClient = object : android.webkit.WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        view: android.webkit.WebView?,
                                                        request: android.webkit.WebResourceRequest?,
                                                    ): Boolean {
                                                        val url = request?.url?.toString() ?: return false
                                                        Timber.tag("SpotiFLACSettings").d("WebView redirect: $url")

                                                        if (url.contains("spotiflac://session-grant") || url.contains("grant=")) {
                                                            val uri = android.net.Uri.parse(url)
                                                            val grant = uri.getQueryParameter("grant")
                                                            if (!grant.isNullOrBlank() && !grantCaptured) {
                                                                grantCaptured = true
                                                                Timber.tag("SpotiFLACSettings").d("Auto-captured grant from redirect")
                                                                showChallengeWebView = false
                                                                scope.launch {
                                                                    isExchanging = true
                                                                    val result = sessionManager.exchangeGrant(grant)
                                                                    if (result.isSuccess) {
                                                                        sessionManager.forceRestoreSession()
                                                                    }
                                                                    sessionState = result.getOrElse { SessionState.ERROR }
                                                                    bootstrapError = result.exceptionOrNull()?.message
                                                                    isExchanging = false
                                                                }
                                                                return true
                                                            }
                                                        }
                                                        return false
                                                    }

                                                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                                        super.onPageFinished(view, url)
                                                        Timber.tag("SpotiFLACSettings").d("WebView page finished: $url")

                                                        if (!grantCaptured) {
                                                            view?.evaluateJavascript(
                                                                """
                                                                (function() {
                                                                    try {
                                                                        var grant = new URLSearchParams(window.location.search).get('grant');
                                                                        if (grant) {
                                                                            window.__hushGrant = grant;
                                                                            return 'GRANT_FOUND:' + grant;
                                                                        }
                                                                    } catch(e) {}
                                                                    return 'NO_GRANT';
                                                                })();
                                                                """.trimIndent(),
                                                            ) { result ->
                                                                Timber.tag("SpotiFLACSettings").d("JS grant check: $result")
                                                                if (result.contains("GRANT_FOUND:") && !grantCaptured) {
                                                                    val grant = result.substringAfter("GRANT_FOUND:").removeSurrounding("\"")
                                                                    if (grant.isNotBlank()) {
                                                                        grantCaptured = true
                                                                        Timber.tag("SpotiFLACSettings").d("Auto-captured grant from JS")
                                                                        showChallengeWebView = false
                                                                        scope.launch {
                                                                            isExchanging = true
                                                                            val res = sessionManager.exchangeGrant(grant)
                                                                            if (res.isSuccess) {
                                                                                sessionManager.forceRestoreSession()
                                                                            }
                                                                            sessionState = res.getOrElse { SessionState.ERROR }
                                                                            bootstrapError = res.exceptionOrNull()?.message
                                                                            isExchanging = false
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                webChromeClient = object : android.webkit.WebChromeClient() {}

                                                loadUrl(challengeUrl)
                                            }
                                        }

                                        androidx.compose.ui.viewinterop.AndroidView(
                                            factory = { webView },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    androidx.compose.material3.TextButton(onClick = {
                                        showChallengeWebView = false
                                    }) {
                                        Text("Cancel")
                                    }
                                } else {
                                    androidx.compose.material3.TextButton(onClick = {
                                        showChallengeWebView = true
                                    }) {
                                        Text("Start Verification")
                                    }
                                }
                            }
                            SessionState.EXPIRED -> {
                                Text(
                                    text = "Session Expired",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.material3.TextButton(onClick = {
                                    scope.launch {
                                        isBootstrapping = true
                                        bootstrapError = null
                                        val result = sessionManager.bootstrap()
                                        sessionState = result.getOrElse { SessionState.ERROR }
                                        bootstrapError = result.exceptionOrNull()?.message
                                        isBootstrapping = false
                                    }
                                }) {
                                    Text("Re-authenticate")
                                }
                            }
                            else -> {
                                val errorMsg = bootstrapError
                                if (errorMsg != null) {
                                    Text(
                                        text = errorMsg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    Text(
                                        text = "Not authenticated",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Note: The SpotiFLAC relay requires an extension-based session. Direct relay authentication is not available.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        scope.launch {
                                            isBootstrapping = true
                                            bootstrapError = null
                                            val result = sessionManager.bootstrap()
                                            val newState = result.getOrElse { SessionState.ERROR }
                                            sessionState = newState
                                            bootstrapError = result.exceptionOrNull()?.message
                                                ?: if (newState == SessionState.NONE) "Relay does not support direct authentication" else null
                                            isBootstrapping = false
                                        }
                                    },
                                    enabled = !isBootstrapping,
                                ) {
                                    if (isBootstrapping) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.height(16.dp).width(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text("Try Authenticate")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (spotiflacEnabled && (!spotiflacDevGate || devMode)) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Sources",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (isSyncing) {
                                Spacer(modifier = Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.height(16.dp).width(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        Text(
                            text = "Enable, disable & reorder sources. Higher priority sources tried first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                itemsIndexed(sources) { index, sourceWithState ->
                    SourceRow(
                        sourceWithState = sourceWithState,
                        canMoveUp = index > 0,
                        canMoveDown = index < sources.size - 1,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                repoManager.setSourceEnabled(sourceWithState.source.id, enabled)
                            }
                        },
                        onMoveUp = {
                            scope.launch {
                                repoManager.moveSource(index, index - 1)
                            }
                        },
                        onMoveDown = {
                            scope.launch {
                                repoManager.moveSource(index, index + 1)
                            }
                        },
                        onTest = {
                            scope.launch {
                                val providerKey = sourceWithState.source.providerKey ?: sourceWithState.source.id
                                repoManager.setSourceTestState(sourceWithState.source.id, SourceTestState.TESTING)
                                try {
                                    val client = app.hush.music.spotiflac.SpotiFLACClient.getInstance()
                                    val result = client.testSource(providerKey)
                                    val state = if (result.isSuccess && !result.getOrNull().isNullOrEmpty()) {
                                        SourceTestState.SUCCESS
                                    } else {
                                        SourceTestState.FAILED
                                    }
                                    val errorMsg = result.exceptionOrNull()?.message
                                    repoManager.setSourceTestState(sourceWithState.source.id, state, errorMsg)
                                } catch (e: Exception) {
                                    repoManager.setSourceTestState(sourceWithState.source.id, SourceTestState.FAILED, e.message)
                                }
                            }
                        },
                    )
                }

                if (sources.isEmpty() && !isSyncing) {
                    item {
                        Text(
                            text = "No sources available. Check your internet connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SourceRow(
    sourceWithState: SourceWithState,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sourceWithState.source.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = sourceWithState.source.displayDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (sourceWithState.testState) {
            SourceTestState.TESTING -> {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp).width(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            SourceTestState.SUCCESS -> {
                Text(
                    text = "OK",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            SourceTestState.FAILED -> {
                Text(
                    text = sourceWithState.testError ?: "FAIL",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    maxLines = 2,
                )
            }
            SourceTestState.IDLE -> {}
        }

        androidx.compose.material3.TextButton(onClick = onTest) {
            Text("Test")
        }

        Switch(
            checked = sourceWithState.enabled,
            onCheckedChange = onToggleEnabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )

        androidx.compose.foundation.layout.Column {
            if (canMoveUp) {
                androidx.compose.material3.IconButton(onClick = onMoveUp) {
                    Text("▲", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (canMoveDown) {
                androidx.compose.material3.IconButton(onClick = onMoveDown) {
                    Text("▼", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
