package app.hush.music.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import app.hush.music.constants.SourcePriorityKey
import app.hush.music.constants.SpotiFLACCacheStreamsKey
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.SpotiFLACFallbackToYouTubeKey
import app.hush.music.constants.SpotiFLACPrefetchNextKey
import app.hush.music.constants.SpotiFLACQualityKey
import app.hush.music.constants.SpotiFLACTryNextSourceKey
import app.hush.music.constants.SpotiFLACVerifiedOnlyKey
import app.hush.music.constants.YoutubeStreamingEnabledKey
import app.hush.music.spotiflac.ExtensionRepositoryManager
import app.hush.music.spotiflac.SessionState
import app.hush.music.spotiflac.SpotiFLACSessionManager
import app.hush.music.spotiflac.SpotiFLACSessionRenewer
import app.hush.music.spotiflac.SpotiFLACSourceAuthState
import app.hush.music.spotiflac.SourceTestState
import app.hush.music.spotiflac.SourceWithState
import app.hush.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val TAG = "SpotiFLACSettings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotiFLACSettingsScreen(
    navController: androidx.navigation.NavController,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val (spotiflacEnabled, setSpotiflacEnabled) = rememberPreference(SpotiFLACEnabledKey, defaultValue = false)
    val (spotiflacQuality, setSpotiflacQuality) = rememberPreference(SpotiFLACQualityKey, defaultValue = "BEST")
    val (youtubeEnabled, setYoutubeEnabled) = rememberPreference(YoutubeStreamingEnabledKey, defaultValue = true)

    // Engine priority (highest first) and the fallback / cache controls.
    val (sourcePriorityRaw, setSourcePriority) =
        rememberPreference(SourcePriorityKey, defaultValue = "SPOTIFLAC,YOUTUBE")
    val (fallbackToYouTube, setFallbackToYouTube) =
        rememberPreference(SpotiFLACFallbackToYouTubeKey, defaultValue = true)
    val (tryNextSource, setTryNextSource) =
        rememberPreference(SpotiFLACTryNextSourceKey, defaultValue = true)
    val (verifiedOnly, setVerifiedOnly) =
        rememberPreference(SpotiFLACVerifiedOnlyKey, defaultValue = true)
    val (cacheStreams, setCacheStreams) =
        rememberPreference(SpotiFLACCacheStreamsKey, defaultValue = true)
    val (prefetchNext, setPrefetchNext) =
        rememberPreference(SpotiFLACPrefetchNextKey, defaultValue = true)
    val priorityOrder =
        remember(sourcePriorityRaw) {
            sourcePriorityRaw
                .split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf("SPOTIFLAC", "YOUTUBE") }
        }

    val repoManager = remember { ExtensionRepositoryManager.getInstance() }
    val sessionManager = remember { SpotiFLACSessionManager.getInstance() }
    val sources by repoManager.sources.collectAsState()
    // 1-based position of each enabled source in the resolve order, so the list
    // shows which repo is actually tried first.
    val sourceChoiceLabels =
        remember(sources) {
            sources
                .filter { it.enabled }
                .mapIndexed { choiceIndex, state -> state.source.id to (choiceIndex + 1) }
                .toMap()
        }
    val isSyncing by repoManager.isSyncing.collectAsState()

    val sessionState by sessionManager.sessionStateFlow.collectAsState()
    var isBootstrapping by remember { mutableStateOf(false) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    var grantInput by remember { mutableStateOf("") }
    var showChallengeWebView by remember { mutableStateOf(false) }
    var isExchanging by remember { mutableStateOf(false) }

    // Per-extension verification state. Extensions own their own Turnstile flow,
    // so a verification opened here completes only for the extension that asked.
    var verifyExtensionId by remember { mutableStateOf<String?>(null) }
    var verifyAuthUrl by remember { mutableStateOf<String?>(null) }
    var verifyExpectedState by remember { mutableStateOf<String?>(null) }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var verifyStatus by remember { mutableStateOf<Map<String, SpotiFLACSourceAuthState>>(emptyMap()) }
    // Remaining validity of each extension's gateway session, in seconds. A
    // session that is about to expire is worth warning about, because once it
    // does the extension demands a fresh Cloudflare check.
    var sessionValidity by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var renewMessage by remember { mutableStateOf<String?>(null) }
    var isRenewingSessions by remember { mutableStateOf(false) }

    fun refreshSessionValidity() {
        sessionValidity = SpotiFLACSessionRenewer.sessions(context)
            .associate { it.extensionId to it.remainingSeconds }
    }

    LaunchedEffect(sources, spotiflacEnabled) {
        if (!spotiflacEnabled) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val bridge = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
                ?: return@withContext
            if (!bridge.isRuntimeAvailable) return@withContext
            bridge.prepareForPlayback(repoManager.getEnabledSourceIds())
            verifyStatus =
                repoManager.getEnabledSourceIds().associateWith { bridge.sourceAuthState(it) }
        }
        refreshSessionValidity()
    }

    fun renewSessionsNow() {
        scope.launch {
            isRenewingSessions = true
            renewMessage = null
            val results = withContext(Dispatchers.IO) {
                SpotiFLACSessionRenewer.renewAll(context, force = true, reason = "settings")
            }
            refreshSessionValidity()
            val renewed = results.count { it.renewed }
            val failed = results.filter { !it.renewed && it.detail.startsWith("request failed") }
            renewMessage = when {
                results.isEmpty() -> "No verified sources to renew"
                renewed > 0 -> "Renewed $renewed session${if (renewed == 1) "" else "s"}"
                failed.isNotEmpty() -> "Could not renew ${failed.size} session(s) — verification may be needed"
                else -> "Sessions are still valid"
            }
            isRenewingSessions = false
        }
    }

    fun startVerification(extensionId: String) {
        scope.launch {
            verifyMessage = null
            val bridge = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
            if (bridge == null || !bridge.isRuntimeAvailable) {
                verifyMessage = "SpotiFLAC runtime is not available in this build"
                return@launch
            }
            when (bridge.sourceAuthState(extensionId)) {
                SpotiFLACSourceAuthState.NOT_REQUIRED -> {
                    // SoundCloud and the YouTube Music provider sign in with the
                    // service itself, so there is no Cloudflare check to open.
                    verifyMessage =
                        "$extensionId does not use a SpotiFLAC session — nothing to verify"
                    verifyStatus = verifyStatus + (extensionId to SpotiFLACSourceAuthState.NOT_REQUIRED)
                    return@launch
                }
                SpotiFLACSourceAuthState.VERIFIED -> {
                    verifyMessage = "$extensionId is already verified"
                    verifyStatus = verifyStatus + (extensionId to SpotiFLACSourceAuthState.VERIFIED)
                    return@launch
                }
                SpotiFLACSourceAuthState.NEEDS_VERIFICATION -> Unit
            }
            // ensureChallenge asks the runtime for a challenge, raising one if the
            // extension had not performed a preflight yet, so Verify is not a
            // dead end that only says "play a track first".
            val pending = withContext(Dispatchers.IO) { bridge.ensureChallenge(extensionId) }
            if (pending == null) {
                verifyMessage =
                    "Could not open a challenge for $extensionId — try playing a SpotiFLAC track once"
                return@launch
            }
            verifyExtensionId = pending.extensionId
            verifyExpectedState = expectedVerificationState(pending.authUrl)
            verifyAuthUrl = pending.authUrl
        }
    }

    // Playback blocked by an extension that needs verification: open that
    // extension's challenge immediately so the user only solves the Cloudflare
    // check instead of hunting through the source list.
    LaunchedEffect(Unit) {
        val requested = app.hush.music.spotiflac.SpotiFLACVerificationRequest.consume()
        if (requested != null) {
            withContext(Dispatchers.IO) { repoManager.syncRegistries() }
            startVerification(requested)
        }
    }

    fun completeVerification(raw: String) {
        val extensionId = verifyExtensionId ?: return
        val grant = verificationGrantFrom(raw) ?: return
        val state = verificationStateFrom(raw)
        val expected = verifyExpectedState
        if (expected != null && state != null && state != expected) {
            verifyMessage = "Verification callback did not match the active challenge"
            verifyAuthUrl = null
            verifyExtensionId = null
            return
        }
        verifyAuthUrl = null
        verifyExtensionId = null
        scope.launch {
            isExchanging = true
            val bridge = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
            // A grant is exchanged per extension, so apply it to every enabled
            // source: one solved challenge then refreshes all of them at once.
            val targets = withContext(Dispatchers.IO) {
                bridge?.grantTargetSourceIds(extensionId) ?: listOf(extensionId)
            }
            withContext(Dispatchers.IO) { bridge?.deliverGrant(grant, targets) }
            val ok = bridge?.isSourceVerified(extensionId) ?: false
            verifyStatus = withContext(Dispatchers.IO) {
                repoManager.getEnabledSourceIds().associateWith {
                    bridge?.sourceAuthState(it) ?: SpotiFLACSourceAuthState.NOT_REQUIRED
                }
            }
            refreshSessionValidity()
            verifyMessage = if (ok) {
                val alsoVerified =
                    targets.count { verifyStatus[it] == SpotiFLACSourceAuthState.VERIFIED } - 1
                if (alsoVerified > 0) {
                    "$extensionId verified · $alsoVerified other source(s) refreshed too"
                } else {
                    "$extensionId verified"
                }
            } else {
                "Verification for $extensionId did not complete — try again"
            }
            isExchanging = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            repoManager.syncRegistries()
        }
    }

    // When the native runtime's download preflight demands verification, surface
    // its challenge URL in the same WebView flow used for the Hush-side challenge.
    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.ACTIVE && !showChallengeWebView) {
            withContext(Dispatchers.IO) {
                val runtimeUrl = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder
                    .instance?.pendingRuntimeAuthUrl()
                if (!runtimeUrl.isNullOrBlank()) {
                    Timber.tag(TAG).i("Runtime pending auth detected: %s", runtimeUrl)
                    showChallengeWebView = true
                }
            }
        }
    }

    // A failed source test can have been recorded while a Cloudflare challenge
    // was pending. Clear that stale error as soon as the grant exchange succeeds.
    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.ACTIVE) {
            bootstrapError = null
            sources
                .filter { it.testState == SourceTestState.FAILED }
                .forEach { source ->
                    repoManager.setSourceTestState(source.source.id, SourceTestState.IDLE)
                }
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
                        onCheckedChange = if (spotiflacEnabled) setYoutubeEnabled else null,
                        enabled = spotiflacEnabled,
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠ Experimental — currently in testing and may not work. Do not enable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AnimatedVisibility(visible = spotiflacEnabled) {
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


                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (spotiflacEnabled) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        // Ordering only means something when both engines are
                        // available. With one switched off there is no choice to make,
                        // so show which engine is in use instead of a list that would
                        // pretend the disabled one still matters.
                        if (youtubeEnabled) {
                            Text(
                                text = stringResource(R.string.spotiflac_playback_priority),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.spotiflac_playback_priority_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            priorityOrder.forEachIndexed { index, engine ->
                                PriorityEngineRow(
                                    position = index + 1,
                                    name = if (engine == "SPOTIFLAC") "SpotiFLAC" else "YouTube",
                                    canMoveUp = index > 0,
                                    canMoveDown = index < priorityOrder.lastIndex,
                                    onMoveUp = {
                                        setSourcePriority(swapPriorityOrder(priorityOrder, index, index - 1))
                                    },
                                    onMoveDown = {
                                        setSourcePriority(swapPriorityOrder(priorityOrder, index, index + 1))
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        } else {
                            Text(
                                text = "Playback source",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "YouTube is disabled, so every track plays through SpotiFLAC. There is no order to choose.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = "Fallback",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SettingsSwitchRow(
                            title = stringResource(R.string.spotiflac_try_next_source),
                            checked = tryNextSource,
                            onCheckedChange = setTryNextSource,
                        )
                        Text(
                            text = stringResource(R.string.spotiflac_try_next_source_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // YouTube can only be a fallback while YouTube itself is an
                        // enabled source, so the switch mirrors that: it greys out and
                        // turns itself off when YouTube is disabled.
                        SettingsSwitchRow(
                            title = stringResource(R.string.spotiflac_fallback_youtube),
                            checked = fallbackToYouTube && youtubeEnabled,
                            enabled = youtubeEnabled,
                            onCheckedChange = setFallbackToYouTube,
                        )
                        Text(
                            text = if (youtubeEnabled) {
                                stringResource(R.string.spotiflac_fallback_youtube_desc)
                            } else {
                                "YouTube is disabled in Audio Sources, so there is nothing to fall back to."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SettingsSwitchRow(
                            title = stringResource(R.string.spotiflac_verified_only),
                            checked = verifiedOnly,
                            onCheckedChange = setVerifiedOnly,
                        )
                        Text(
                            text = stringResource(R.string.spotiflac_verified_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            text = stringResource(R.string.spotiflac_cache_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.spotiflac_cache_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsSwitchRow(
                            title = stringResource(R.string.spotiflac_cache_streams),
                            checked = cacheStreams,
                            onCheckedChange = setCacheStreams,
                        )
                        Text(
                            text = stringResource(R.string.spotiflac_cache_streams_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SettingsSwitchRow(
                            title = stringResource(R.string.spotiflac_prefetch_next),
                            checked = prefetchNext,
                            onCheckedChange = setPrefetchNext,
                        )
                        Text(
                            text = stringResource(R.string.spotiflac_prefetch_next_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The cached-song size cap and its usage live in Storage,
                        // next to the shared player cache. Keeping a second copy here
                        // meant two screens could disagree about the same setting.
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
                            text = "Authentication",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Legacy relay session. Playback uses the built-in SpotiFLAC engine, which keeps its own per-source sessions, so you normally never need this. Only used if the built-in engine fails.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Downloads always run through the bundled upstream runtime
                        // (gobackend AAR); verification unlocks the extension pipeline.
                        Text(
                            text = if (sessionState == SessionState.ACTIVE) {
                                "Downloads run through the bundled SpotiFLAC extension runtime."
                            } else {
                                "Downloads run through the bundled SpotiFLAC extension runtime after verification."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

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
                                    }
                                }) {
                                    Text("Clear Session")
                                }
                            }
                            SessionState.CHALLENGE_PENDING -> {
                                // Prefer the runtime's own pending auth URL (extension
                                // preflight) over the Hush-side challenge when present.
                                var runtimeAuthUrl by remember { mutableStateOf<String?>(null) }
                                LaunchedEffect(Unit) {
                                    runtimeAuthUrl = withContext(Dispatchers.IO) {
                                        app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder
                                            .instance?.pendingRuntimeAuthUrl()
                                    }
                                }
                                val challengeUrl = runtimeAuthUrl ?: sessionManager.challengeUrl
                                // Auto-show the WebView when challenge is pending
                                LaunchedEffect(challengeUrl) {
                                    if (challengeUrl != null && !showChallengeWebView && !isExchanging) {
                                        showChallengeWebView = true
                                    }
                                }
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

                                    fun extractGrant(raw: String): String? {
                                        if (raw.contains("grant=")) {
                                            return android.net.Uri.parse(raw).getQueryParameter("grant")
                                                ?.takeIf { it.isNotBlank() }
                                        }
                                        return raw.trim().takeIf { it.isNotBlank() }
                                    }

                                    fun captureGrant(raw: String) {
                                        if (grantCaptured) return
                                        val grant = extractGrant(raw) ?: return
                                        grantCaptured = true
                                        capturedGrant.value = grant
                                        Timber.tag("SpotiFLACSettings").d("Grant captured (len=${grant.length})")
                                        showChallengeWebView = false
                                        scope.launch {
                                            isExchanging = true
                                            val result = sessionManager.exchangeGrant(grant)
                                            if (result.isSuccess) {
                                                sessionManager.forceRestoreSession()
                                            }
                                            bootstrapError = result.exceptionOrNull()?.message
                                            isExchanging = false
                                        }
                                    }

                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(450.dp),
                                    ) {
                                        val webView = remember {
                                            android.webkit.WebView(context).apply {
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.setSupportMultipleWindows(true)

                                                // The relay challenge page delivers the grant through a
                                                // window.SpotiflacGrant.postMessage(...) JS bridge, because
                                                // Chromium WebView silently drops script-initiated
                                                // custom-scheme navigation without a user gesture. Expose that
                                                // bridge so the grant is captured reliably after Turnstile
                                                // succeeds.
                                                addJavascriptInterface(
                                                    object : Any() {
                                                        @android.webkit.JavascriptInterface
                                                        fun postMessage(message: String) {
                                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                                captureGrant(message)
                                                            }
                                                        }
                                                    },
                                                    "SpotiflacGrant",
                                                )

                                                webViewClient = object : android.webkit.WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        view: android.webkit.WebView?,
                                                        request: android.webkit.WebResourceRequest?,
                                                    ): Boolean {
                                                        val url = request?.url?.toString() ?: return false
                                                        Timber.tag("SpotiFLACSettings").d("WebView redirect: $url")

                                                        if (url.contains("spotiflac://session-grant") || url.contains("grant=")) {
                                                            Timber.tag("SpotiFLACSettings").d("Auto-captured grant from redirect")
                                                            captureGrant(url)
                                                            if (grantCaptured) {
                                                                return true
                                                            }
                                                        }
                                                        return false
                                                    }                                                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                                        super.onPageFinished(view, url)
                                                        Timber.tag("SpotiFLACSettings").d("WebView page finished: $url")

                                                        if (!grantCaptured) {
                                                            view?.evaluateJavascript(
                                                                """
                                                                (function() {
                                                                    try {
                                                                        // Check for relay error responses (expired challenge, etc.)
                                                                        var body = document.body ? document.body.innerText : '';
                                                                        if (body.indexOf('"error"') !== -1) {
                                                                            return 'RELAY_ERROR:' + body.substring(0, 200);
                                                                        }
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
                                                                if (result.contains("RELAY_ERROR:")) {
                                                                    val errorBody = result.substringAfter("RELAY_ERROR:").removeSurrounding("\"")
                                                                    Timber.tag("SpotiFLACSettings").w("Relay error in WebView: $errorBody")
                                                                    // Challenge expired or invalid — close WebView so user can retry
                                                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                                        showChallengeWebView = false
                                                                        bootstrapError = "Challenge expired — tap 'Try Again' to get a fresh verification"
                                                                    }
                                                                } else if (result.contains("GRANT_FOUND:") && !grantCaptured) {
                                                                    val grant = result.substringAfter("GRANT_FOUND:").removeSurrounding("\"")
                                                                    if (grant.isNotBlank()) {
                                                                        Timber.tag("SpotiFLACSettings").d("Auto-captured grant from JS")
                                                                        captureGrant(grant)
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
                                    val retryError = bootstrapError
                                    if (retryError != null) {
                                        Text(
                                            text = retryError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                    androidx.compose.material3.TextButton(onClick = {
                                        scope.launch {
                                            bootstrapError = null
                                            isBootstrapping = true
                                            val result = sessionManager.bootstrap()
                                            val newState = result.getOrElse { SessionState.ERROR }
                                            bootstrapError = result.exceptionOrNull()?.message
                                                ?: if (newState == SessionState.CHALLENGE_PENDING) null
                                                else if (newState == SessionState.ACTIVE) null
                                                else "Failed to start verification"
                                            isBootstrapping = false
                                            if (newState == SessionState.CHALLENGE_PENDING || newState == SessionState.ACTIVE) {
                                                showChallengeWebView = true
                                            }
                                        }
                                    }, enabled = !isBootstrapping) {
                                        if (isBootstrapping) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.height(16.dp).width(16.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            Text("Try Again")
                                        }
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

            if (spotiflacEnabled) {
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

                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = "Extension verification",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Solve the Cloudflare check once and the grant is applied to every enabled source, so one check covers all of them. Sources that sign in with their own service are marked as needing no verification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        sources
                            .map { it.source }
                            .filter { it.supportsDownload }
                            .forEach { source ->
                                val authState = verifyStatus[source.id]
                                val verified = authState == SpotiFLACSourceAuthState.VERIFIED
                                val notRequired = authState == SpotiFLACSourceAuthState.NOT_REQUIRED
                                val remaining = sessionValidity[source.id]
                                val expired = remaining != null && remaining <= 0
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = source.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = when {
                                                // Reported from the manifest: this source
                                                // signs in with the service itself, so
                                                // there is no Cloudflare check to offer.
                                                notRequired -> "No verification needed"
                                                !verified -> "Verification needed"
                                                expired -> "Session expired — verify again"
                                                remaining == null -> "Verified"
                                                else -> "Verified — renews automatically (${
                                                    formatSessionValidity(remaining)
                                                } left)"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (verified && !expired) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    if (!notRequired) {
                                        androidx.compose.material3.TextButton(
                                            onClick = { startVerification(source.id) },
                                            enabled = !isExchanging,
                                        ) {
                                            Text(if (verified) "Re-verify" else "Verify")
                                        }
                                    }
                                }
                            }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Sessions renew in the background so a verification is a one-time step.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { renewSessionsNow() },
                                enabled = !isRenewingSessions,
                            ) {
                                Text(if (isRenewingSessions) "Renewing…" else "Renew now")
                            }
                        }
                        val renewNote = renewMessage
                        if (renewNote != null) {
                            Text(
                                text = renewNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val message = verifyMessage
                        if (message != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                itemsIndexed(sources) { index, sourceWithState ->
                    SourceRow(
                        sourceWithState = sourceWithState,
                        choiceLabel = sourceChoiceLabels[sourceWithState.source.id],
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

        val activeVerifyUrl = verifyAuthUrl
        if (activeVerifyUrl != null) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    Text(
                        text = "Verify ${verifyExtensionId ?: ""}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    var verifyGrantCaptured by remember(activeVerifyUrl) { mutableStateOf(false) }
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = {
                            android.webkit.WebView(context).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                addJavascriptInterface(
                                    object : Any() {
                                        @android.webkit.JavascriptInterface
                                        fun postMessage(message: String) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                if (!verifyGrantCaptured) {
                                                    verifyGrantCaptured = true
                                                    completeVerification(message)
                                                }
                                            }
                                        }
                                    },
                                    "SpotiflacGrant",
                                )
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: android.webkit.WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        if (url.contains("grant=") || url.contains("code=")) {
                                            if (!verifyGrantCaptured) {
                                                verifyGrantCaptured = true
                                                completeVerification(url)
                                            }
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        if (verifyGrantCaptured) return
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                try {
                                                    var grant = new URLSearchParams(window.location.search).get('grant')
                                                        || new URLSearchParams(window.location.search).get('code');
                                                    if (grant) return 'GRANT_FOUND:' + grant;
                                                } catch(e) {}
                                                return 'NO_GRANT';
                                            })();
                                            """.trimIndent(),
                                        ) { result ->
                                            if (result.contains("GRANT_FOUND:") && !verifyGrantCaptured) {
                                                val grant = result.substringAfter("GRANT_FOUND:")
                                                    .removeSurrounding("\"")
                                                if (grant.isNotBlank()) {
                                                    verifyGrantCaptured = true
                                                    completeVerification(grant)
                                                }
                                            }
                                        }
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {}
                                loadUrl(activeVerifyUrl)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = {
                        verifyAuthUrl = null
                        verifyExtensionId = null
                    }) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/** Pulls the one-time grant (or `code`) out of an extension verification callback URL. */
/** Human-readable remaining time for a session that is still valid. */
private fun formatSessionValidity(remainingSeconds: Long): String {
    if (remainingSeconds <= 0) return "expired"
    val hours = remainingSeconds / 3600
    val minutes = (remainingSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${remainingSeconds}s"
    }
}

private fun verificationGrantFrom(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    runCatching {
        val uri = android.net.Uri.parse(trimmed)
        uri.getQueryParameter("grant")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.getQueryParameter("cb")?.let { nested -> verificationGrantFrom(nested) }?.let { return it }
    }
    val match = Regex("(?:^|[?&#\\s])(?:grant|code)=([^&#\\s]+)").find(trimmed) ?: return null
    return runCatching { android.net.Uri.decode(match.groupValues[1]) }.getOrNull()
        ?.takeIf { it.isNotBlank() }
}

/** Pulls the callback `state` used to bind a grant to the challenge that started it. */
private fun verificationStateFrom(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    runCatching {
        val uri = android.net.Uri.parse(trimmed)
        uri.getQueryParameter("state")?.takeIf { it.isNotBlank() }?.let { return it }
        for (key in listOf("cb", "callback", "callback_url", "redirect_uri")) {
            val nested = uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }
            if (nested != null) verificationStateFrom(nested)?.let { return it }
        }
    }
    val match = Regex("(?:^|[?&#\\s])state=([^&#\\s]+)").find(trimmed) ?: return null
    return runCatching { android.net.Uri.decode(match.groupValues[1]) }.getOrNull()
        ?.takeIf { it.isNotBlank() }
}

/**
 * Derives the expected callback state from a runtime verification URL, looking
 * through the same nested callback parameters upstream inspects.
 */
private fun expectedVerificationState(authUrl: String, depth: Int = 0): String? {
    if (depth > 3) return null
    return runCatching {
        val uri = android.net.Uri.parse(authUrl.trim())
        uri.getQueryParameter("state")?.takeIf { it.isNotBlank() }?.let { return it }
        for (key in listOf("cb", "callback", "callback_url", "redirect_uri")) {
            val nested = uri.getQueryParameter(key)?.takeIf { it.isNotBlank() } ?: continue
            expectedVerificationState(nested, depth + 1)?.let { return it }
        }
        null
    }.getOrNull()
}

@Composable
private fun SourceRow(
    sourceWithState: SourceWithState,
    choiceLabel: Int?,
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
            if (choiceLabel != null) {
                Text(
                    text = "Choice #$choiceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
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
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange?.invoke(it) },
            enabled = enabled,
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

/** Moves an engine within the priority list and returns the persisted CSV value. */
private fun swapPriorityOrder(
    order: List<String>,
    from: Int,
    to: Int,
): String {
    if (from !in order.indices || to !in order.indices) return order.joinToString(",")
    val mutable = order.toMutableList()
    val moved = mutable.removeAt(from)
    mutable.add(to, moved)
    return mutable.joinToString(",")
}

/** One engine row in the playback priority list, with its 1st/2nd badge. */
@Composable
private fun PriorityEngineRow(
    position: Int,
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text =
                    when (position) {
                        1 -> stringResource(R.string.spotiflac_priority_first)
                        2 -> stringResource(R.string.spotiflac_priority_second)
                        else -> position.toString()
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
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
