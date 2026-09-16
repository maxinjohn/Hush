package app.hush.music.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import app.hush.music.spotiflac.SpotiFLACChallengeEngine
import app.hush.music.spotiflac.SpotiFLACChallengeRoute
import app.hush.music.spotiflac.SpotiFLACDiag
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
    val lifecycleOwner = LocalLifecycleOwner.current

    // The same engine question the player overlay asks. A WebView older than Cloudflare's
    // supported range never mints a token, so opening the challenge here would produce a check
    // that can only time out - the browser route has to be offered instead. Resolved once per
    // composition because it cannot change while the app is running.
    val challengeEngine = remember { SpotiFLACChallengeEngine.current() }
    val engineCapable = remember(challengeEngine) {
        SpotiFLACChallengeEngine.canSolveCloudflare(challengeEngine)
    }

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
            if (ok) {
                // The same report the overlay and the browser route make. Without it a successful
                // verification from this screen woke nothing: a track parked on this source stayed
                // parked, and the "needs verification" notice stayed up over a source that was
                // already usable.
                app.hush.music.spotiflac.SpotiFLAutoVerifier.notifyVerified(extensionId)
            }
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
                                // A relay challenge that was raised for *this* visit. The stored one
                                // from an earlier bootstrap is deliberately not used: a challenge is
                                // single-use, so a saved URL is usually already spent, and opening
                                // it anywhere can only end in the page's "Invalid request".
                                var freshRelayUrl by remember { mutableStateOf<String?>(null) }
                                // Once the browser owns verification, the in-app WebView must not load
                                // the same page: a challenge is single-use, so whichever consumer gets
                                // there first spends it and the other can only be told "Invalid
                                // request". The WebView auto-opens within seconds of a challenge being
                                // raised, which is exactly how the browser route kept failing.
                                var browserMode by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) {
                                    runtimeAuthUrl = withContext(Dispatchers.IO) {
                                        app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder
                                            .instance?.pendingRuntimeAuthUrl()
                                    }
                                    if (runtimeAuthUrl == null) {
                                        freshRelayUrl = withContext(Dispatchers.IO) {
                                            sessionManager.bootstrap()
                                            sessionManager.challengeUrl
                                        }
                                    }
                                }
                                val challengeUrl = runtimeAuthUrl ?: freshRelayUrl
                                // Record which challenge is in play, and who owns it, so a
                                // verification that fails can be told apart from one that never got a
                                // challenge at all - the two look identical on screen.
                                LaunchedEffect(challengeUrl, browserMode) {
                                    if (challengeUrl == null) return@LaunchedEffect
                                    val id = challengeUrl.substringAfter("id=", "").substringBefore("&")
                                    val state = challengeUrl.substringAfter("state=", "").substringBefore("&")
                                    SpotiFLACDiag.log(
                                        "relay challenge in play id=$id state=$state " +
                                            "owner=${if (browserMode) "browser" else "app"} " +
                                            "source=${if (runtimeAuthUrl != null) "runtime" else "relay"}",
                                    )
                                }
                                // Auto-show the WebView when a challenge is pending - but only on
                                // an engine that can actually solve it. Opening a page that can
                                // never finish would hold the user in front of a dead check.
                                LaunchedEffect(challengeUrl, engineCapable) {
                                    if (engineCapable && challengeUrl != null &&
                                        !showChallengeWebView && !isExchanging && !browserMode
                                    ) {
                                        showChallengeWebView = true
                                    }
                                }

                                // Declared here rather than inside the WebView branch: the same
                                // grant is now also accepted from the browser route, which does
                                // not have a WebView to capture it from.
                                var grantCaptured by remember { mutableStateOf(false) }
                                // Bumped when the challenge goes to the browser, so a retry can
                                // wait for the grant again instead of the first attempt having
                                // spent the watch.
                                var browserRouteAttempt by remember { mutableStateOf(0) }
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
                                    showChallengeWebView = false
                                    Timber.tag(TAG).d("Grant captured (len=${grant.length})")
                                    // The relay's own record of the flow. Its Timber lines never reach
                                    // logcat in this build, so a verification that did not stick used to
                                    // leave no trace at all beyond "it did not work".
                                    SpotiFLACDiag.log(
                                        "relay grant captured via ${if (browserMode) "browser" else "app"} " +
                                            "(len=${grant.length})",
                                    )
                                    scope.launch {
                                        isExchanging = true
                                        val result = sessionManager.exchangeGrant(grant)
                                        SpotiFLACDiag.log(
                                            "relay grant exchange: success=${result.isSuccess} " +
                                                "err=${result.exceptionOrNull()?.message ?: "none"} " +
                                                "state=${result.getOrNull()}",
                                        )
                                        if (result.isSuccess) {
                                            sessionManager.forceRestoreSession()
                                        }
                                        bootstrapError = result.exceptionOrNull()?.message
                                        isExchanging = false
                                    }
                                }

                                // The browser route is tap-only: solving the check in the browser
                                // publishes the grant on the challenge page, which Hush reads back
                                // for itself. The page's copied callback is still watched, for
                                // browsers that offer one.
                                LaunchedEffect(challengeUrl, engineCapable, browserRouteAttempt) {
                                    // Follow the *route*, not the engine: once the challenge has
                                    // been handed to a browser, that browser's grant is what Hush
                                    // has to collect, whether or not the WebView could have done
                                    // the job itself.
                                    if (challengeUrl == null || browserRouteAttempt == 0) return@LaunchedEffect
                                    val grant = SpotiFLACChallengeRoute.awaitBrowserGrant(
                                        owner = lifecycleOwner,
                                        context = context,
                                        challengeUrl = challengeUrl,
                                        // Shorter than the default wait: the relay gateway binds a
                                        // challenge to the request that raised it, so a browser that
                                        // cannot complete one should hand the job back to the app
                                        // quickly rather than leaving the user waiting.
                                        timeoutMs = BROWSER_ROUTE_TIMEOUT_MS,
                                    )
                                    if (grant != null) {
                                        captureGrant(grant)
                                        return@LaunchedEffect
                                    }
                                    // Nothing came back. The relay check has to be done by the app
                                    // itself - it is raised for this install and carries a JS bridge
                                    // only Hush owns - so give the in-app route a fresh challenge
                                    // instead of leaving verification in a dead end. On a device
                                    // whose WebView cannot run Cloudflare at all, say so, because
                                    // then the per-source verification is the way in.
                                    browserMode = false
                                    if (engineCapable) {
                                        withContext(Dispatchers.IO) { sessionManager.bootstrap() }
                                        freshRelayUrl = sessionManager.challengeUrl
                                        bootstrapError =
                                            "Your browser could not finish this check - Hush is doing it in the app instead"
                                        showChallengeWebView = true
                                        SpotiFLACDiag.log(
                                            "relay browser route produced no grant; in-app route retried " +
                                                "with a fresh challenge",
                                        )
                                    } else {
                                        bootstrapError =
                                            "This device's WebView cannot run Cloudflare's check - verify " +
                                                "a source instead, which can use your browser"
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
                                } else if (showChallengeWebView && challengeUrl != null && engineCapable) {

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
                                    ChallengeBrowserFallback(
                                        url = challengeUrl,
                                        onBrowserOpened = { browserRouteAttempt++ },
                                        onStale = {
                                            scope.launch {
                                                bootstrapError =
                                                    "That challenge was already used - getting a fresh one"
                                                withContext(Dispatchers.IO) { sessionManager.bootstrap() }
                                                freshRelayUrl = sessionManager.challengeUrl
                                                bootstrapError = null
                                            }
                                        },
                                        // Hand the browser a challenge of its own: the WebView is
                                        // closed first so it cannot solve (and spend) the one the
                                        // browser is about to open.
                                        beforeOpen = {
                                            browserMode = true
                                            showChallengeWebView = false
                                            withContext(Dispatchers.IO) { sessionManager.bootstrap() }
                                            freshRelayUrl = sessionManager.challengeUrl
                                            SpotiFLACDiag.log(
                                                "browser route for the relay challenge: fresh " +
                                                    "challenge ${freshRelayUrl ?: "(none)"}",
                                            )
                                            freshRelayUrl
                                        },
                                    ) { raw ->
                                        captureGrant(raw)
                                    }
                                    androidx.compose.material3.TextButton(onClick = {
                                        showChallengeWebView = false
                                    }) {
                                        Text("Cancel")
                                    }
                                } else {
                                    // A challenge that this device's WebView cannot solve: say so
                                    // and offer the route that works instead of the retry loop.
                                    if (challengeUrl != null && !engineCapable) {
                                        Text(
                                            text = SpotiFLACChallengeRoute.unsupportedNotice(challengeEngine),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        ChallengeBrowserFallback(
                                        url = challengeUrl,
                                        onBrowserOpened = { browserRouteAttempt++ },
                                        onStale = {
                                            scope.launch {
                                                bootstrapError =
                                                    "That challenge was already used - getting a fresh one"
                                                withContext(Dispatchers.IO) { sessionManager.bootstrap() }
                                                freshRelayUrl = sessionManager.challengeUrl
                                                bootstrapError = null
                                            }
                                        },
                                    ) { raw ->
                                            captureGrant(raw)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
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
                                            if (engineCapable &&
                                                (
                                                    newState == SessionState.CHALLENGE_PENDING ||
                                                        newState == SessionState.ACTIVE
                                                )
                                            ) {
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
                    var verifyBrowserAttempt by remember(activeVerifyUrl) { mutableStateOf(0) }
                    // A challenge that was already passed - in a browser, or before Hush's
                    // process was killed - still publishes its unspent grant. Reading it here
                    // finishes a repeat Verify without opening a WebView or a browser at all,
                    // which is the only way verification can complete on a device whose engine
                    // cannot run Cloudflare's check.
                    LaunchedEffect(activeVerifyUrl) {
                        if (verifyGrantCaptured) return@LaunchedEffect
                        val recovered = SpotiFLACChallengeRoute.grantFromChallengePage(
                            SpotiFLACChallengeRoute.readChallengePage(activeVerifyUrl),
                        ) ?: return@LaunchedEffect
                        if (verifyGrantCaptured) return@LaunchedEffect
                        SpotiFLACDiag.log(
                            "challenge for ${verifyExtensionId ?: "?"} was already solved; " +
                                "recovering its grant",
                        )
                        verifyGrantCaptured = true
                        completeVerification("grant=$recovered")
                    }
                    // An engine Cloudflare will not challenge never mints a token, so the page is
                    // not opened at all on one: the browser route below does the work instead.
                    if (engineCapable) {
                        ExtensionChallengeWebView(
                            url = activeVerifyUrl,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) { raw ->
                            if (!verifyGrantCaptured) {
                                verifyGrantCaptured = true
                                completeVerification(raw)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = SpotiFLACChallengeRoute.unsupportedNotice(challengeEngine),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    // The browser route is tap-only here too: the grant is read back from the
                    // challenge page rather than copied by hand. Only watched once the challenge
                    // has actually been handed to a browser, and only when the WebView is not the
                    // one doing the work.
                    LaunchedEffect(activeVerifyUrl, engineCapable, verifyBrowserAttempt) {
                        // The browser route is collected wherever it was chosen, not only on
                        // devices whose WebView is too old to run the check.
                        if (verifyBrowserAttempt == 0) return@LaunchedEffect
                        val grant = SpotiFLACChallengeRoute.awaitBrowserGrant(
                            owner = lifecycleOwner,
                            context = context,
                            challengeUrl = activeVerifyUrl,
                        ) ?: return@LaunchedEffect
                        if (!verifyGrantCaptured) {
                            verifyGrantCaptured = true
                            completeVerification(grant)
                        }
                    }
                    ChallengeBrowserFallback(
                        url = activeVerifyUrl,
                        onBrowserOpened = { verifyBrowserAttempt++ },
                        // A spent runtime challenge means this URL is finished: ask the runtime for
                        // a new one rather than sending the user to a page that cannot deliver.
                        onStale = {
                            val id = verifyExtensionId
                            verifyAuthUrl = null
                            if (id != null) startVerification(id)
                        },
                    ) { raw ->
                        if (!verifyGrantCaptured) {
                            verifyGrantCaptured = true
                            completeVerification(raw)
                        }
                    }
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

/**
 * The extension verification page in an embedded WebView.
 *
 * Extracted because the same page now has two hosts: this WebView, and the browser route that
 * replaces it entirely on a device whose WebView is too old for Cloudflare's check. Both report
 * a captured grant through the same callback, so the capture plumbing lives in one place.
 */
@Composable
private fun ExtensionChallengeWebView(
    url: String,
    modifier: Modifier = Modifier,
    onGrant: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            android.webkit.WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The challenge page delivers its grant through a
                // window.SpotiflacGrant.postMessage(...) bridge, because Chromium drops
                // script-initiated custom-scheme navigation without a user gesture.
                addJavascriptInterface(
                    object : Any() {
                        @android.webkit.JavascriptInterface
                        fun postMessage(message: String) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onGrant(message)
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
                        val target = request?.url?.toString() ?: return false
                        if (target.contains("grant=") || target.contains("code=")) {
                            onGrant(target)
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        view?.evaluateJavascript(QUERY_GRANT_JS) { result ->
                            if (result.contains("GRANT_FOUND:")) {
                                val grant = result.substringAfter("GRANT_FOUND:")
                                    .removeSurrounding("\"")
                                if (grant.isNotBlank()) onGrant(grant)
                            }
                        }
                    }
                }
                // Cloudflare names an engine it will not challenge through the page console,
                // which is otherwise invisible on a device we cannot inspect directly.
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(
                        message: android.webkit.ConsoleMessage?,
                    ): Boolean {
                        val text = message?.message() ?: return false
                        SpotiFLACDiag.log(
                            "challenge console[$url] ${message.lineNumber()}: ${text.take(200)}",
                        )
                        return false
                    }
                }
                loadUrl(url)
            }
        },
        modifier = modifier,
    )
}

/** Reads a `grant`/`code` parameter the challenge page may have left in its own URL. */
private const val QUERY_GRANT_JS = """
(function() {
    try {
        var params = new URLSearchParams(window.location.search);
        var grant = params.get('grant') || params.get('code');
        if (grant) return 'GRANT_FOUND:' + grant;
    } catch (e) {}
    return 'NO_GRANT';
})();
"""

/**
 * The route that works when the embedded WebView cannot: solve the check in the device's real
 * browser, and Hush reads the grant back from the challenge page itself.
 *
 * Shared by both verification surfaces, so the fallback does not exist on only one of them - and
 * on a device whose WebView is too old, it is the only way verification completes at all.
 *
 * @param onBrowserOpened the challenge has been handed to a browser, so the host can start
 *   watching for the grant the page publishes once the check is solved.
 * @param onStale the challenge is finished (already verified and spent), so there is nothing a
 *   browser could complete. The host refreshes it instead of opening a page that can only answer
 *   "Invalid request" - which is what a stored challenge URL from an earlier attempt produced.
 * @param beforeOpen mints the URL the browser should actually open, or null when there is nothing
 *   to open. A challenge is single-use and the in-app WebView solves it within seconds of it being
 *   raised, so a browser handed *that* URL can only ever reach a spent one - the page answers
 *   "Invalid request" no matter how often the user retries. The host therefore retires the WebView
 *   and hands the browser a challenge of its own.
 * @param onApply a pasted code or link, for the browsers that hand one over by hand.
 */
@Composable
private fun ChallengeBrowserFallback(
    url: String,
    onBrowserOpened: () -> Unit = {},
    onStale: () -> Unit = {},
    beforeOpen: (suspend () -> String?)? = null,
    onApply: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pasted by remember(url) { mutableStateOf("") }
    Text(
        text = "Solve it in your browser and Hush finishes on its own - there is nothing to " +
            "copy back.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        androidx.compose.material3.TextButton(onClick = {
            scope.launch {
                val target = if (beforeOpen != null) {
                    beforeOpen()
                } else if (
                    SpotiFLACChallengeRoute.readPageState(url) ==
                    SpotiFLACChallengeRoute.PageState.Spent
                ) {
                    // Never hand a finished challenge to a browser: the page would solve and then
                    // reject itself, which reads as "verification is broken" rather than "this link
                    // is used up". Refresh instead.
                    SpotiFLACDiag.log("challenge handed to the browser was already spent; refreshing")
                    onStale()
                    null
                } else {
                    url
                }
                if (target != null) {
                    onBrowserOpened()
                    SpotiFLACChallengeRoute.openInBrowser(context, target)
                }
            }
        }) {
            Text("Open in browser")
        }
        androidx.compose.material3.TextButton(onClick = {
            SpotiFLACChallengeRoute.clipboardText(context)?.let { pasted = it }
        }) {
            Text("Paste code")
        }
    }
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        label = { Text("Verification code or link") },
        textStyle = MaterialTheme.typography.bodySmall,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        androidx.compose.material3.TextButton(
            onClick = {
                val callback = pasted.trim()
                if (callback.isNotEmpty()) onApply(callback)
            },
            enabled = pasted.isNotBlank(),
        ) {
            Text("Apply")
        }
    }
}

/**
 * How long the relay verification waits on a browser before taking the check back into the app.
 *
 * The relay gateway raises its challenge for this install and delivers the grant through a bridge
 * only Hush owns, so a browser is the fallback route - not the one to wait on. Long enough for a
 * real browser to load the page and solve the widget, short enough that a route that cannot finish
 * hands back to the working one while the user is still looking at the screen.
 */
private const val BROWSER_ROUTE_TIMEOUT_MS = 45_000L

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
    val match = Regex("(?:^|[?&#\\s])(?:grant|code)=([^&#\\s]+)").find(trimmed)
    if (match != null) {
        return runCatching { android.net.Uri.decode(match.groupValues[1]) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
    // A bare token - what the page's JavaScript channel posts, and what a user pasting from the
    // browser route may copy - carries no parameters to read. Accepted only when it cannot be
    // anything else, so a pasted sentence or URL is never exchanged as a grant. Matches the
    // player overlay's parser, because both accept the same code.
    return trimmed.takeIf { BARE_GRANT_CODE.matches(it) }
}

/** Length and shape of a bare verification token: unreserved URL characters, nothing else. */
private val BARE_GRANT_CODE = Regex("^[A-Za-z0-9._~-]{20,2048}$")

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
