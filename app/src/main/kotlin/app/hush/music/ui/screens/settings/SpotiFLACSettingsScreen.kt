package app.hush.music.ui.screens.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hush.music.ui.component.HushProgressSpinner
import app.hush.music.R
import app.hush.music.constants.AudioQuality
import app.hush.music.constants.AudioQualityKey
import app.hush.music.constants.PrefetchCountKey
import app.hush.music.constants.SourcePriorityKey
import app.hush.music.constants.SpotiFLACCacheStreamsKey
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.SpotiFLACFallbackToYouTubeKey
import app.hush.music.constants.SpotiFLACPrefetchNextKey
import app.hush.music.constants.SpotiFLACQualityKey
import app.hush.music.constants.ParallelSourceFetchKey
import app.hush.music.constants.SpotiFLACTryNextSourceKey
import app.hush.music.constants.SpotiFLACVerifiedOnlyKey
import app.hush.music.constants.YoutubeStreamingEnabledKey
import app.hush.music.spotiflac.ExtensionRepositoryManager
import app.hush.music.spotiflac.SpotiFLACChallengeEngine
import app.hush.music.spotiflac.SpotiFLACChallengeRoute
import app.hush.music.spotiflac.SpotiFLACAvailability
import app.hush.music.spotiflac.SpotiFLACDiag
import app.hush.music.spotiflac.SpotiFLACEngineState
import app.hush.music.spotiflac.SpotiFLACEngineStatus
import app.hush.music.spotiflac.SpotiFLACSessionRenewer
import app.hush.music.spotiflac.SpotiFLACSessionVerdict
import app.hush.music.spotiflac.SpotiFLACSessionVerdictReport
import app.hush.music.spotiflac.SpotiFLACVerificationChecklist
import app.hush.music.spotiflac.SpotiFLACSourceAuthState
import app.hush.music.spotiflac.SourceTestState
import app.hush.music.spotiflac.SpotiFLACPackageUpdateLog
import app.hush.music.spotiflac.SpotiFLACPackageUpdateReport
import app.hush.music.spotiflac.SourceWithState
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val (youtubeQuality, setYoutubeQuality) =
        rememberEnumPreference(AudioQualityKey, defaultValue = AudioQuality.AUTO)

    // Both engines' settings are always on screen. They used to fold, and the fold was the wrong
    // tool: it hid the switch that made a neighbouring switch grey out, and it hid the reason an
    // engine was not playing - which is the one thing this screen exists to explain.

    // Engine priority (highest first) and the fallback / cache controls.
    val (sourcePriorityRaw, setSourcePriority) =
        rememberPreference(SourcePriorityKey, defaultValue = "SPOTIFLAC,YOUTUBE")
    val (fallbackToYouTube, setFallbackToYouTube) =
        rememberPreference(SpotiFLACFallbackToYouTubeKey, defaultValue = true)
    val (tryNextSource, setTryNextSource) =
        rememberPreference(SpotiFLACTryNextSourceKey, defaultValue = true)
    val (parallelFetch, setParallelFetch) =
        rememberPreference(ParallelSourceFetchKey, defaultValue = false)
    val (verifiedOnly, setVerifiedOnly) =
        rememberPreference(SpotiFLACVerifiedOnlyKey, defaultValue = true)
    val (cacheStreams, setCacheStreams) =
        rememberPreference(SpotiFLACCacheStreamsKey, defaultValue = true)
    val (prefetchNext, setPrefetchNext) =
        rememberPreference(SpotiFLACPrefetchNextKey, defaultValue = true)
    // Read-only here: the count is owned by Settings -> Playback -> "Prefetch upcoming songs".
    val (prefetchCount, _) = rememberPreference(PrefetchCountKey, defaultValue = 2)
    val priorityOrder =
        remember(sourcePriorityRaw) {
            sourcePriorityRaw
                .split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf("SPOTIFLAC", "YOUTUBE") }
        }

    val repoManager = remember { ExtensionRepositoryManager.getInstance() }
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

    /** The session id on disk per source, so a renewal verdict can be matched to the session it judged. */
    var sessionIds by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    /** What the gateway last said about each source's session. */
    var sessionVerdicts by remember {
        mutableStateOf<Map<String, SpotiFLACSessionVerdict>>(emptyMap())
    }
    var renewMessage by remember { mutableStateOf<String?>(null) }
    var isRenewingSessions by remember { mutableStateOf(false) }

    /**
     * The gateway's client-wide block, when it is holding one.
     *
     * Shown once for the section rather than on every row, because it is not about any source: the
     * relay is refusing this connection's address, and every row would report the same thing.
     */
    var relayBlock by remember { mutableStateOf(SpotiFLACSessionRenewer.relayBlock(context)) }
    var isRetryingRelay by remember { mutableStateOf(false) }


    // The exit the gateway sees, which is Hush's own proxy setting. Reported and never configured
    // What the runtime has loaded per source, so a row can show the build it is actually running
    // next to the one its registry publishes - the difference an update is for. Read in one pass
    // for the whole list, off the main thread, because each answer is a runtime call plus a file.
    var installedVersions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var updatingSources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var packageMessages by remember { mutableStateOf<Map<String, PackageMessage>>(emptyMap()) }
    var isCheckingPackages by remember { mutableStateOf(false) }
    // When each package was last looked at, and which registry this install follows for it.
    var extensionChecks by remember {
        mutableStateOf<Map<String, app.hush.music.spotiflac.SpotiFLACExtensionCheck>>(emptyMap())
    }

    /**
     * What each source's *own* service says about itself, when it says it cannot serve.
     *
     * Read from the engine's recorded verdicts and never asked for here, so the row can explain a
     * source that is sitting at the back of the chain without the user pressing Test first - the
     * state in which "Verified - 3h left" beside a failed test looks like a contradiction. The
     * record carries the provider's own reason, and it expires with the demotion it describes.
     */
    var providerHealth by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // The same engine question the player overlay asks. A WebView older than Cloudflare's
    // supported range never mints a token, so opening the challenge here would produce a check
    // that can only time out - the browser route has to be offered instead. Resolved once per
    // composition because it cannot change while the app is running.
    val challengeEngine = remember { SpotiFLACChallengeEngine.current() }
    val engineCapable = remember(challengeEngine) {
        SpotiFLACChallengeEngine.canSolveCloudflare(challengeEngine)
    }

    /**
     * Re-reads how long each verified source's session has left.
     *
     * This walks the extensions directory and reads one manifest plus one session record per source,
     * so it is done off the main thread. It is called from composition - on every verification tick,
     * after a renewal, after a check completes - and reading files there is a stall the user sees as
     * jank rather than as a slow function, which is the worst kind on a head unit with slow storage.
     */
    /**
     * Re-reads which build of each source the runtime has loaded.
     *
     * The registry version is already in the row's own source, so only the installed half has to be
     * asked for - and it is asked for once for the whole list rather than once per row.
     */
    suspend fun refreshInstalledVersions() {
        val bridge = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance ?: return
        if (!bridge.isRuntimeAvailable) return
        installedVersions = withContext(Dispatchers.IO) {
            runCatching { bridge.installedExtensionVersions(sources.map { it.source.id }) }
                .getOrDefault(emptyMap())
        }
        extensionChecks = withContext(Dispatchers.IO) {
            runCatching { bridge.extensionChecks() }.getOrDefault(emptyMap())
        }
        // Read with the versions because it is the same kind of fact about the same sources, and
        // because this is already the once-per-list read that happens on every verification tick.
        providerHealth = withContext(Dispatchers.IO) {
            runCatching { bridge.providerHealthNotes() }.getOrDefault(emptyMap())
        }
    }

    suspend fun refreshSessionValidity() {
        val sessions = withContext(Dispatchers.IO) { SpotiFLACSessionRenewer.sessions(context) }
        sessionValidity = sessions.associate { it.extensionId to it.remainingSeconds }
        // The record alone cannot say whether the gateway still honours a session, so whatever it
        // last answered is read back with it. Held per source and matched against the session on
        // disk, so a re-verification clears it without anything having to remember to.
        sessionIds = sessions.associate { it.extensionId to it.sessionId }
        sessionVerdicts = withContext(Dispatchers.IO) {
            runCatching { SpotiFLACSessionRenewer.lastVerdicts(context) }.getOrDefault(emptyMap())
        }
        relayBlock = withContext(Dispatchers.IO) { SpotiFLACSessionRenewer.relayBlock(context) }
    }


    /**
     * The sources one action would have to get through, in the order the list shows them.
     *
     * Deliberately the same predicate the per-row "Verify" button uses, read from the same state:
     * a state that is not yet read is *not* counted (a challenge for a package that has not been
     * loaded only answers "no challenge yet"), a source that signs in with its own service has no
     * check to run, and a healthy session has nothing to solve. A verified source whose lapsed
     * record is still refreshing does count - that is the case where a check is genuinely needed
     * again.
     */
    val sourcesNeedingCheck =
        sources
            .map { it.source }
            .filter { it.supportsDownload }
            .filter { source ->
                when (verifyStatus[source.id]) {
                    SpotiFLACSourceAuthState.NEEDS_VERIFICATION -> true
                    SpotiFLACSourceAuthState.VERIFIED ->
                        sessionValidity[source.id]?.let { it <= 0 } == true ||
                            // A session the gateway has turned down needs a check even while its
                            // record still looks unexpired - that record is exactly why the row used
                            // to say "renews automatically" while every request through it was
                            // refused. Counting it here is what keeps the line above the list from
                            // reading "Everything is verified - nothing to check" over two rows that
                            // say the opposite, and it is what makes "Verify all" cover them.
                            // Matched against the session on disk, so a re-verification retires it.
                            SpotiFLACSessionVerdictReport.applies(
                                verdict = sessionVerdicts[source.id],
                                currentSessionId = sessionIds[source.id],
                            ) != null
                    else -> false
                }
            }

    /**
     * Re-reads every enabled source's auth state from the runtime.
     *
     * The runtime's own signed-session record is the single source of truth, so a verification
     * performed by *any* surface - the automatic run, the player overlay, the notification, the
     * grant deep link, or this screen - shows up here as soon as it lands. Reading it only once,
     * when the screen appeared, is what made a source the app had already verified still read
     * "Verification needed", sending the user back to solve a check whose grant was already spent.
     */
    suspend fun refreshVerifyStatus() {
        // Before reporting a source as needing a check, make sure the session it already earned is
        // where the runtime looks for it. A record's file name is derived from the extension's app
        // version, so a registry bump hides a verified session behind a stale name and the source
        // asks for a challenge it has already passed - the "I verified and it wants another check"
        // loop. The vault is keyed by extension, so restoring from it is what keeps one
        // verification enough for every surface.
        val restored = withContext(Dispatchers.IO) {
            runCatching { SpotiFLACSessionRenewer.restoreFromVault(context) }.getOrDefault(emptyList())
        }
        if (restored.isNotEmpty()) {
            SpotiFLACDiag.log("session restored from vault: ${restored.joinToString(",")}")
        }
        verifyStatus = withContext(Dispatchers.IO) {
            val bridge = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
            if (bridge == null || !bridge.isRuntimeAvailable) return@withContext verifyStatus
            repoManager.getEnabledSourceIds().associateWith { bridge.sourceAuthState(it) }
        }
        refreshSessionValidity()
    }

    // Keyed on the set of enabled sources rather than on the row objects themselves. A row carries
    // its own test state, and warming the runtime can end in the verifier recording a result, which
    // writes that state back into this same list - so keying on the rows re-ran this effect, warmed
    // the runtime again, asked the verifier again, and so on for as long as the screen stayed open.
    // Ids compare structurally, so a result landing no longer re-triggers the warm-up.
    val enabledSourceIds = remember(sources) { sources.filter { it.enabled }.map { it.source.id } }

    /**
     * What the SpotiFLAC engine is doing, which is not what its switch says.
     *
     * An engine whose session-bearing sources have all lost their sessions is *paused*, and playback
     * is running on YouTube until one is verified - so that is what this screen has to say. Recomputed
     * whenever a source's state, a verdict or the gateway's block changes, so the line cannot outlive
     * the thing it describes.
     */
    val spotiflacEngineStatus =
        remember(spotiflacEnabled, enabledSourceIds, verifyStatus, relayBlock) {
            SpotiFLACAvailability.current(
                context = context,
                enabled = spotiflacEnabled,
                sources = enabledSourceIds,
            )
        }

    LaunchedEffect(enabledSourceIds, spotiflacEnabled) {
        if (!spotiflacEnabled) {
            // Nothing is in use, so nothing can be reported as needing a check either.
            verifyStatus = emptyMap()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val bridge = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
                ?: return@withContext
            if (!bridge.isRuntimeAvailable) return@withContext
            bridge.prepareForPlayback(repoManager.getEnabledSourceIds())
        }
        refreshVerifyStatus()
        // `prepareForPlayback` is also what reconciles packages against the registry, so this is
        // the moment the installed versions can differ from the published ones.
        refreshInstalledVersions()
    }

    val verifiedTicker by app.hush.music.spotiflac.SpotiFLAutoVerifier.verifiedTicker.collectAsState()
    val autoVerificationSource by app.hush.music.spotiflac.SpotiFLAutoVerifier.active.collectAsState()
    // Where an all-sources run currently is, so one tap can be followed without guessing.
    val autoVerificationStatus by app.hush.music.spotiflac.SpotiFLAutoVerifier.status.collectAsState()
    // The run, source by source: which one is being solved, which are still queued and which have
    // already landed. Watched here because a run works through several sources in sequence and can
    // take a while over each, so "something is happening" is not an answer a user can act on.
    val verificationSteps by app.hush.music.spotiflac.SpotiFLAutoVerifier.steps.collectAsState()

    // A verification that completes anywhere else has to reach these rows without the user leaving
    // and returning: otherwise the only button on offer is this screen's own "Verify", and the
    // challenge behind it is the one that was just spent - which reads as "I verified and it still
    // wants another check". The ticker is bumped by the verifier for exactly this, and the active
    // slot is watched so a run that gives up also un-sticks the row.
    LaunchedEffect(verifiedTicker, autoVerificationSource) {
        if (!spotiflacEnabled) return@LaunchedEffect
        refreshVerifyStatus()
    }

    // A source test can have failed while its Cloudflare challenge was outstanding, and that verdict
    // is about the session that no longer exists. The failure is cleared when *that* source's own
    // auth state reaches VERIFIED - the transition, read from the state this screen already holds.
    // It used to be cleared when Hush's own gateway session became active, which has nothing to do
    // with a source's session and almost never happened; clearing it on any verification landing
    // would instead wipe a failure the user had just recorded for a source that was already
    // verified, so the transition is what decides.
    val previousVerifyStatus =
        remember { mutableStateOf<Map<String, SpotiFLACSourceAuthState>>(emptyMap()) }
    LaunchedEffect(verifyStatus) {
        val previous = previousVerifyStatus.value
        previousVerifyStatus.value = verifyStatus
        val justVerified = verifyStatus.filter { (id, state) ->
            state == SpotiFLACSourceAuthState.VERIFIED && previous[id] != null && previous[id] != state
        }.keys
        if (justVerified.isEmpty()) return@LaunchedEffect
        sources
            .filter { it.source.id in justVerified && it.testState == SourceTestState.FAILED }
            .forEach { source ->
                repoManager.setSourceTestState(source.source.id, SourceTestState.IDLE)
            }
    }

    // Coming back from a check solved in a browser - the only route on a device whose WebView is
    // older than Cloudflare supports - is a resume, and it is exactly when the state has changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (spotiflacEnabled) scope.launch { refreshVerifyStatus() }
    }

    /**
     * A version check the user asked for: re-read the registries, then check every package.
     *
     * Both halves matter. The registry list is what carries a published version, so sweeping before
     * re-reading it would compare packages against a list that may itself be hours old - the one way
     * a check asked for now could answer "up to date" about a build that has since been published.
     * The sweep is forced for the same reason: the per-version memo answers "already answered", and
     * a button that returns a remembered answer instead of looking is a button that lies.
     */
    fun checkForUpdates() {
        scope.launch {
            isCheckingPackages = true
            verifyMessage = null
            val bridge = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
            if (bridge == null || !bridge.isRuntimeAvailable) {
                verifyMessage = "SpotiFLAC runtime is not available in this build"
                isCheckingPackages = false
                return@launch
            }
            val summary = withContext(Dispatchers.IO) {
                runCatching {
                    repoManager.syncRegistries()
                    bridge.reconcileExtensionPackages(userInitiated = true)
                }.getOrNull()
            }
            if (summary == null) {
                verifyMessage = "Could not check for updates — try again"
            }
            refreshInstalledVersions()
            isCheckingPackages = false
        }
    }

    /**
     * Asks for every source that still needs a check, from one tap.
     *
     * The gateway issues a challenge per source - one `app_version`, one challenge, and a grant
     * belongs to the challenge behind it - so there is no single check that mints every session.
     * What can be removed is the *work*: this hands the whole list to the verifier, which works
     * through them one at a time and reports progress in the line above the list. On a device
     * whose embedded WebView can run Cloudflare the challenges solve themselves, so the tap is
     * the only interaction; on one whose WebView cannot (a car head unit) each challenge is opened
     * in the browser for the user, and the grant comes back on its own.
     */
    fun verifyAllSources(extensionIds: List<String>) {
        if (extensionIds.isEmpty()) return
        verifyMessage = null
        app.hush.music.spotiflac.SpotiFLAutoVerifier.enqueue(
            sourceIds = extensionIds,
            reason = "settings-verify-all",
            force = true,
            browserFallback = true,
        )
    }

    /**
     * Asks for one source's check, on its own.
     *
     * The card's Verify is the batch, and this is the single source in front of the user: a track
     * being held at "verification required", or a session that reads wrong while playback works.
     * It joins the same queue with the same permission to open the browser, because asking for a
     * check *is* the consent that a background prewarm does not have - so a car head unit's
     * challenge opens for the user here exactly as it does from the batch. It names itself in the
     * log ("settings-verify-one"), so a report can tell a deliberate single check from a batch run.
     */
    fun verifySource(extensionId: String) {
        if (extensionId.isBlank()) return
        verifyMessage = null
        app.hush.music.spotiflac.SpotiFLAutoVerifier.enqueue(
            sourceIds = listOf(extensionId),
            reason = "settings-verify-one",
            force = true,
            browserFallback = true,
        )
    }

    /**
     * Renews every session the gateway will still rotate, and names what happened to each.
     *
     * This used to be half of a merged "Verify & renew all" that renewed first and then quietly
     * raised checks for whatever the gateway had turned down. That read as a contradiction - the
     * line above it counted how many sources already need a check, and the same button offered to
     * renew them - and it hid which half had actually run. Renewing and verifying are different
     * jobs with different outcomes: the gateway rotates a live session, but a session it has
     * thrown away can only be replaced by a check, and no amount of renewing will produce one.
     * So they are two buttons, and this one reports the renewals it really performed.
     */
    fun renewSessions() {
        scope.launch {
            isRenewingSessions = true
            renewMessage = null
            val results = withContext(Dispatchers.IO) {
                SpotiFLACSessionRenewer.renewAll(context, force = true, reason = "settings-renew")
            }
            refreshSessionValidity()
            // A source the gateway says is gone has to stop reading "Verified" in the rows above,
            // which is also what puts it in the count the Verify button acts on.
            if (results.any { it.needsVerification }) refreshVerifyStatus()
            // Every source is named with what actually happened to it. A bare "renewed 1 session"
            // left the user unable to tell a dead session (needs a verification) from a refused one
            // (needs time) from one that never needed anything - which is exactly the question the
            // button is pressed to answer.
            renewMessage = SpotiFLACSessionRenewer.summarise(results)
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
            // A source whose package has not been read yet cannot be classified, and on a fresh
            // install that is every source. Preparing it is what turns "Verify" into a real answer
            // instead of a button that either does nothing or offers a check for a source that
            // needs none.
            if (bridge.sourceAuthState(extensionId) == SpotiFLACSourceAuthState.UNKNOWN) {
                withContext(Dispatchers.IO) {
                    runCatching { bridge.prepareForPlayback(listOf(extensionId)) }
                }
                refreshVerifyStatus()
            }
            // A session the gateway has turned down is still perfectly valid to the *runtime*, whose
            // preflight only checks that a record holds an id, a secret and a future expiry. So the
            // check was refused for exactly the source that needed it - pressing Verify on a source
            // the gateway had answered `401 SESSION_INVALID` for replied "already verified". Dropping
            // the refused material first is what lets a challenge be raised at all; see
            // SpotiFLACSessionRenewer.forgetGatewayRejectedSession.
            val forgotten = withContext(Dispatchers.IO) {
                runCatching {
                    SpotiFLACSessionRenewer.forgetGatewayRejectedSession(context, extensionId)
                }.getOrDefault(false)
            }
            if (forgotten) {
                sessionVerdicts = sessionVerdicts - extensionId
                refreshVerifyStatus()
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
                // Still unread after preparing it: there is no challenge to raise, and saying so is
                // better than opening one for a source the runtime may not even have.
                SpotiFLACSourceAuthState.UNKNOWN -> {
                    verifyMessage =
                        "Could not read $extensionId's package — reinstall it from Repositories, " +
                            "then verify"
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
            // The grant belongs to the extension that raised the challenge, and this screen
            // already resolved that owner into `extensionId` (the runtime's pending auth carries
            // its own id). Delivering it anywhere else is answered HTTP 403, so the target is the
            // owner alone - other sources each need their own check, which the auto-verifier
            // queues by itself.
            withContext(Dispatchers.IO) { bridge?.deliverGrant(grant, listOf(extensionId)) }
            val ok = bridge?.isSourceVerified(extensionId) ?: false
            verifyStatus = withContext(Dispatchers.IO) {
                repoManager.getEnabledSourceIds().associateWith {
                    // Unknown, not "nothing to verify": with no runtime there is no answer at all,
                    // and claiming the source needs nothing is what hid a required check.
                    bridge?.sourceAuthState(it) ?: SpotiFLACSourceAuthState.UNKNOWN
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
                "$extensionId verified"
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

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.audio_sources_title),
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
                    EngineSectionTitle(
                        title = "YouTube",
                        subtitle = "Stream from YouTube Music",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchRow(
                        title = "YouTube Enabled",
                        checked = youtubeEnabled,
                        onCheckedChange =
                            if (spotiflacEnabled) {
                                { enabled -> setYoutubeEnabled(enabled) }
                            } else {
                                null
                            },
                        enabled = spotiflacEnabled,
                    )

                    // Stream quality belongs to the thing it configures, so it lives under
                    // the YouTube switch rather than in Player settings - the same way
                    // SpotiFLAC's Audio Quality lives under its own switch below. Two
                    // screens setting one value is how a setting ends up disagreeing with
                    // the engine it names.
                    if (youtubeEnabled) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.stream_quality),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.youtube_quality_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val youtubeQualities =
                                listOf(
                                    AudioQuality.HIGHEST to stringResource(R.string.audio_quality_max),
                                    AudioQuality.HIGH to stringResource(R.string.audio_quality_high),
                                    AudioQuality.AUTO to stringResource(R.string.audio_quality_auto),
                                    AudioQuality.LOW to stringResource(R.string.audio_quality_low),
                                )
                            youtubeQualities.forEach { (quality, label) ->
                                SettingsRadioRow(
                                    title = label,
                                    selected = youtubeQuality == quality,
                                    onClick = { setYoutubeQuality(quality) },
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

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    EngineSectionTitle(
                        title = "SpotiFLAC",
                        subtitle = "Lossless audio from Tidal, Qobuz, Deezer & more via Spotify metadata",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // The enable switch lives here, with the rest of the audio-source
                    // configuration, rather than behind dev mode: it changes which engine
                    // plays the user's music, and hiding that in an experimental menu made a
                    // working source look like a debug toy. It stays locked on while YouTube
                    // is off, because something has to be able to play - the same rule the
                    // YouTube switch above follows from the other side.
                    SettingsSwitchRow(
                        title = stringResource(R.string.spotiflac_enabled),
                        checked = spotiflacEnabled,
                        onCheckedChange =
                            if (youtubeEnabled) {
                                { enabled -> setSpotiflacEnabled(enabled) }
                            } else {
                                null
                            },
                        enabled = youtubeEnabled,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // What the engine is actually doing, which is not the same as what the switch
                    // says: a SpotiFLAC with no usable source is paused, and playback is running on
                    // YouTube in the meantime. Shown here rather than in a separate card because this
                    // is where the switch that would fix it lives.
                    EngineStatusLine(status = spotiflacEngineStatus)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (spotiflacEnabled) {
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
                        // Off by default: racing is faster when a provider is slow, and heavier
                        // everywhere else (several downloads at once, several rate limits).
                        SettingsSwitchRow(
                            title = stringResource(R.string.spotiflac_parallel_fetch),
                            checked = parallelFetch,
                            onCheckedChange = setParallelFetch,
                        )
                        Text(
                            text = stringResource(R.string.spotiflac_parallel_fetch_desc),
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
                        // How many, not just whether: this switch is the on/off for both
                        // engines' lookahead, and the number is the one "Prefetch upcoming
                        // songs" in Player settings holds. Reported here rather than
                        // repeated as a second control, so the two screens can never
                        // disagree about the same value.
                        Text(
                            text = spotiflacPrefetchSummary(prefetchCount),
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
                                HushProgressSpinner(
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
                        // What the startup check did, next to the list it is about. Packages are
                        // kept current without the user asking, so the only thing left to do here
                        // is say so - a silent update is indistinguishable from a stale build, and
                        // the only place it used to be reported was the diagnostic log.
                        val packageSummary by SpotiFLACPackageUpdateLog.summary.collectAsState()
                        val packageUpdateLine =
                            packageSummary?.let { SpotiFLACPackageUpdateReport.summary(it) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = packageUpdateLine
                                    ?: "Each source's build is checked against its registry at startup.",
                                style = MaterialTheme.typography.labelMedium,
                                color =
                                    if (packageUpdateLine != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { checkForUpdates() },
                                enabled = !isCheckingPackages,
                            ) {
                                Text(if (isCheckingPackages) "Checking…" else "Check for updates")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                item {
                    // Said before the rows, because a block explains *all* of them at once: each one
                    // still reads whatever its own session says, while nothing they describe can
                    // actually be reached. Measured on the reporting device, this state was invisible
                    // - the rows showed healthy sessions and the tracks silently fell back to
                    // YouTube, so the only honest account of what was happening was in a log.
                    relayBlock?.let { block ->
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "SpotiFLAC is blocked at the gateway",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${block.reason.replaceFirstChar { it.uppercase() }} - " +
                                    "${SpotiFLACSessionRenewer.formatBlockRemaining(block.remainingMs)} left. " +
                                    "Every source reaches the gateway through the same relay, so none of " +
                                    "them can answer until it lifts. Hush waits it out instead of spending " +
                                    "a sweep on sources that will all be refused.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "The refusal follows this connection's address, so another " +
                                    "network is served normally - a VPN is enough, and a proxy set in " +
                                    "Internet settings is the other exit the app's own requests can use. " +
                                    "Nothing in the app clears the block, and a verification cannot " +
                                    "either: the challenge endpoint answers the same way while it lasts. " +
                                    "Hush asks again by itself as soon as the route changes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            // No exit row and no second way into the proxy setting: the proxy is set
                            // once, in Internet settings, and every SpotiFLAC request already follows
                            // it. Repeating it here - with the address in use, plus a button to go
                            // and change it - made this notice look like a third place to configure a
                            // network that had already been configured.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        isRetryingRelay = true
                                        scope.launch {
                                            relayBlock = withContext(Dispatchers.IO) {
                                                SpotiFLACSessionRenewer.retryRelayNow(context)
                                            }
                                            renewMessage =
                                                relayBlock?.let { still ->
                                                    "Still blocked - " +
                                                        SpotiFLACSessionRenewer
                                                            .formatBlockRemaining(still.remainingMs) +
                                                        " left"
                                                } ?: "The gateway is answering again"
                                            isRetryingRelay = false
                                            refreshSessionValidity()
                                        }
                                    },
                                    enabled = !isRetryingRelay,
                                ) {
                                    Text(if (isRetryingRelay) "Asking…" else "Try again now")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = "Extension verification",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Each source keeps its own gateway session, so a check verifies the source that raised it and is kept from then on - it never has to be solved twice. Sources that sign in with their own service are marked as needing no verification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Every source the registry offers is listed here, with the ones that need
                        // attention first. Filtering this list to download providers left Apple Music
                        // and Spotify Web out of it entirely - so a source the user could see in the
                        // list above read as *missing* here rather than as having nothing to
                        // authorise. Their role is what decides the wording, not their presence.
                        val sessionRows = sources
                            .map { it.source }
                            .map { source ->
                                source to SpotiFLACSessionVerdictReport.row(
                                    authState = verifyStatus[source.id],
                                    remainingSeconds = sessionValidity[source.id],
                                    verdict = sessionVerdicts[source.id],
                                    currentSessionId = sessionIds[source.id],
                                    formatRemaining = ::formatSessionValidity,
                                    declaredTypes = source.declaredRoles,
                                )
                            }
                            .sortedBy { (_, row) ->
                                when {
                                    row.needsCheck -> 0
                                    row.healthy -> 1
                                    else -> 2
                                }
                            }

                        val healthyCount = sessionRows.count { (_, row) -> row.healthy }
                        val attentionCount = sessionRows.count { (_, row) -> row.needsCheck }
                        val runStatus = autoVerificationStatus
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // One line for one job. While a run is in flight this slot carries what the
                            // run is doing ("Verifying amazon…"); idle, it carries what the sessions are
                            // worth. Drawing both would state the same state twice over, and the two can
                            // read as a contradiction - "3 sources need a check" sitting above "1 of 3
                            // checked" is the same fact told two ways, and a reader has to work out that
                            // they agree.
                            Text(
                                text = runStatus ?: SpotiFLACSessionVerdictReport.summary(
                                    healthy = healthyCount,
                                    needsCheck = attentionCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (autoVerificationSource != null) {
                                // A run in progress offers the one thing that applies to it, so the
                                // row never shows three controls for one job.
                                androidx.compose.material3.TextButton(
                                    onClick = { app.hush.music.spotiflac.SpotiFLAutoVerifier.cancel() },
                                ) {
                                    Text("Cancel")
                                }
                            } else {
                                // Exactly two actions, because there are exactly two jobs: Renew
                                // rotates a session the gateway still accepts, Verify mints one it
                                // has turned down. Each is enabled only when it can do something -
                                // Renew with no session to rotate, or Verify with no check to raise,
                                // would be a button that lies about the state it sits next to.
                                androidx.compose.material3.TextButton(
                                    onClick = { renewSessions() },
                                    enabled = !isRenewingSessions && !isExchanging &&
                                        healthyCount + attentionCount > 0,
                                ) {
                                    Text(if (isRenewingSessions) "Working…" else "Renew")
                                }
                                androidx.compose.material3.TextButton(
                                    onClick = { verifyAllSources(sourcesNeedingCheck.map { it.id }) },
                                    enabled = !isRenewingSessions && !isExchanging && attentionCount > 0,
                                ) {
                                    Text("Verify")
                                }
                            }
                        }
                        // How far the run has got. A run works through sources one at a time and a
                        // challenge can take a while, so the count is what tells a user the button
                        // did something - and it is drawn only while a run is in flight, because the
                        // verifier keeps its last marks after finishing.
                        if (SpotiFLACVerificationChecklist.isRunning(autoVerificationSource)) {
                            SpotiFLACVerificationChecklist.progressLine(verificationSteps)?.let { progress ->
                                Text(
                                    text = progress,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // The gateway's own answer about a session outranks the record: a record
                        // that is present and unexpired is exactly how a session the gateway has
                        // already refused used to read "renews automatically". That judgement lives
                        // in the pure rule above, so this loop cannot contradict the line it shows.
                        sessionRows.forEach { (source, sessionRow) ->
                            // While a run is in flight, this row says where *this run* has got to
                            // with the source: solving, waiting, landed, or given up on. That is
                            // the news the user is waiting for, and it is the one thing the session
                            // record cannot express - a challenge being solved and one not yet
                            // started look identical on disk. The session's own text takes over
                            // again the moment the run is over, so nothing here can go stale.
                            val runStep =
                                if (SpotiFLACVerificationChecklist.isRunning(autoVerificationSource)) {
                                    SpotiFLACVerificationChecklist.stepFor(verificationSteps, source.id)
                                } else {
                                    null
                                }
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
                                            text = runStep?.let { SpotiFLACVerificationChecklist.text(it.state) }
                                                ?: sessionRow.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = when (runStep?.let { SpotiFLACVerificationChecklist.tone(it.state) }) {
                                                SpotiFLACVerificationChecklist.Tone.SOLVING,
                                                SpotiFLACVerificationChecklist.Tone.DONE,
                                                -> MaterialTheme.colorScheme.primary
                                                SpotiFLACVerificationChecklist.Tone.ATTENTION ->
                                                    MaterialTheme.colorScheme.error
                                                SpotiFLACVerificationChecklist.Tone.WAITING ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                null ->
                                                    if (sessionRow.healthy) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                            },
                                        )
                                    }
                                    // Still no Verify button per row: the row's job is to say where
                                    // its session stands, and the check a row *needs* is one of the
                                    // ones the batch above raises, from the same state, in the same
                                    // order - so a button here was a third control for one job, and
                                    // as a button it read as "verify" even on a row that was already
                                    // verified. What is here instead is one overflow, which is what
                                    // makes re-checking a single source possible without promising
                                    // anything: it is offered only where a check applies at all, and
                                    // never while a run is in flight, because the run's own Cancel
                                    // is the control then and two ways to start checks would each
                                    // look like the current one.
                                    if (sessionRow.checkable &&
                                        !SpotiFLACVerificationChecklist.isRunning(autoVerificationSource)
                                    ) {
                                        var rowMenuOpen by remember(source.id) { mutableStateOf(false) }
                                        // The button and its menu share one `Box` because a
                                        // `DropdownMenu` is anchored to its parent layout node: as
                                        // siblings of this full-width row the two put the menu against
                                        // the *left* edge of the screen (x=64..564 of 1440), nowhere
                                        // near the button that was tapped (x=1200..1360).
                                        androidx.compose.foundation.layout.Box {
                                            androidx.compose.material3.IconButton(onClick = { rowMenuOpen = true }) {
                                                androidx.compose.material3.Icon(
                                                    imageVector = Icons.Rounded.MoreVert,
                                                    contentDescription = "More options for ${source.displayName}",
                                                )
                                            }
                                            androidx.compose.material3.DropdownMenu(
                                                expanded = rowMenuOpen,
                                                onDismissRequest = { rowMenuOpen = false },
                                            ) {
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = { Text("Verify this source") },
                                                    onClick = {
                                                        rowMenuOpen = false
                                                        verifySource(source.id)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Sessions renew in the background, so a check is a one-time step. " +
                                "Verify turns lossless playback back on; Renew rotates a session that is " +
                                "still live.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        installedVersion = installedVersions[sourceWithState.source.id],
                        check = extensionChecks[sourceWithState.source.id],
                        providerNote = providerHealth[sourceWithState.source.id],
                        isUpdating = sourceWithState.source.id in updatingSources,
                        packageMessage = packageMessages[sourceWithState.source.id],
                        onUpdate = {
                            val source = sourceWithState.source
                            scope.launch {
                                updatingSources = updatingSources + source.id
                                packageMessages = packageMessages - source.id
                                val bridge =
                                    app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
                                val result = withContext(Dispatchers.IO) {
                                    bridge?.updateExtensionPackage(source.id)
                                }
                                val message = result?.getOrNull()
                                    ?: result?.exceptionOrNull()?.message
                                    ?: "SpotiFLAC runtime is not available in this build"
                                packageMessages = packageMessages +
                                    (source.id to PackageMessage(message, isError = result?.isSuccess != true))
                                updatingSources = updatingSources - source.id
                                refreshInstalledVersions()
                                // A new package can carry a new signed-session scope, so the row's
                                // verification state is re-read rather than left on the old answer.
                                refreshVerifyStatus()
                            }
                        },
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
                        // The engine the source actually plays through, not the relay. Asked through the
                        // relay's own `/health?source=` endpoint with Hush's relay session, Test answered
                        // "Cloudflare verification required" for every signed source on a device where all
                        // four were verified and downloading - the credential it questioned is not the one
                        // playback uses (see SpotiFLACNativeRuntimeBridge.testSource). A control whose
                        // verdict is wrong for a working source is worse than no control.
                        onTest = {
                            scope.launch {
                                repoManager.setSourceTestState(sourceWithState.source.id, SourceTestState.TESTING)
                                val bridge =
                                    app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
                                val result = if (bridge == null || !bridge.isRuntimeAvailable) {
                                    Result.failure(
                                        IllegalStateException(
                                            "SpotiFLAC runtime is not available in this build",
                                        ),
                                    )
                                } else {
                                    bridge.testSource(sourceWithState.source.id)
                                }
                                repoManager.setSourceTestState(
                                    sourceWithState.source.id,
                                    if (result.isSuccess) SourceTestState.SUCCESS else SourceTestState.FAILED,
                                    result.exceptionOrNull()?.message,
                                )
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

/** The one-line result of an Update tap, shown under the row that asked for it. */
private data class PackageMessage(val text: String, val isError: Boolean)

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
    installedVersion: String? = null,
    check: app.hush.music.spotiflac.SpotiFLACExtensionCheck? = null,
    providerNote: String? = null,
    isUpdating: Boolean = false,
    packageMessage: PackageMessage? = null,
    onUpdate: () -> Unit = {},
) {
    // Which build is actually running, next to the one the registry publishes. Without this the
    // row can only ever show what the source *is*, so a package left behind by a registry bump is
    // invisible - the state that kept amazon on 2.3.8 for a whole install. Read here rather than
    // inside the text column because the overflow offers the update, and it names the version it
    // would install.
    val registryVersion = sourceWithState.source.version.trim()
    val loadedVersion = installedVersion?.trim()
    val versionLine = when {
        registryVersion.isEmpty() && loadedVersion.isNullOrEmpty() -> null
        loadedVersion.isNullOrEmpty() -> "Registry $registryVersion · not installed"
        registryVersion.isEmpty() -> "Version $loadedVersion"
        loadedVersion.equals(registryVersion, ignoreCase = true) -> "Version $loadedVersion"
        else -> "Installed $loadedVersion · Registry $registryVersion"
    }
    // Only for a build that is genuinely behind the registry the user can see: a source with nothing
    // installed has its own first-install path, and an update offered there would be a control that
    // never does anything.
    val behindRegistry =
        !loadedVersion.isNullOrEmpty() &&
            registryVersion.isNotEmpty() &&
            !loadedVersion.equals(registryVersion, ignoreCase = true)

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
            // The reason a test failed belongs *here*, under the row it is about, not in
            // the narrow trailing slot next to the name. A raw gateway sentence rendered
            // there wrapped to two lines and pushed the switch and the row's controls out of
            // the row, so a failure looked like a broken layout instead of a result. One line,
            // with the rest scrolled off rather than reflowing the row.
            if (sourceWithState.testState == SourceTestState.FAILED) {
                Text(
                    text = sourceWithState.testError?.takeIf { it.isNotBlank() } ?: "Source test failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            } else if (providerNote != null) {
                // The same statement the Test would produce, from the verdict the engine already
                // holds - so the row stops waiting for a tap to explain why a verified source is
                // being passed over. Shown only while no test result is: the two say the same thing
                // about the same source, and a row that repeats itself reads as a second problem.
                Text(
                    text = providerNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (versionLine != null) {
                Text(
                    text = versionLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // When this build was last looked at, and which registry it came from. A version number
            // alone cannot tell "checked a minute ago" from "checked before the last three app
            // starts", and the registry is the part that matters when two publish the same source.
            val checkLine = app.hush.music.spotiflac.SpotiFLACExtensionCheckReport.line(
                check = check,
                registry = app.hush.music.spotiflac.SpotiFLACExtensionCheckReport
                    .registryLabel(sourceWithState.source.repositoryId),
                nowMs = System.currentTimeMillis(),
            )
            if (checkLine != null) {
                Text(
                    text = checkLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            packageMessage?.let { message ->
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (message.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        when (sourceWithState.testState) {
            SourceTestState.TESTING -> {
                HushProgressSpinner(
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
                // Short and always the same width: the reason is on the detail line above.
                Text(
                    text = "FAIL",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            SourceTestState.IDLE -> {}
        }

        // The switch is the row's state - is this source used at all - so it stays out in the open,
        // next to the verdict the test produced. Everything the row can *do* is one overflow: the
        // same single gesture the session rows use, because a line of loose text buttons and arrows
        // put five tap targets in one row, and the switch then read as one of them rather than as
        // the one control that is not about doing something.
        Switch(
            checked = sourceWithState.enabled,
            onCheckedChange = onToggleEnabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )

        var rowMenuOpen by remember(sourceWithState.source.id) { mutableStateOf(false) }
        // One `Box` holds the button and its menu: a `DropdownMenu` is anchored to its parent layout
        // node, so as siblings of this full-width row they would anchor to the row and open the menu
        // against the far edge of the screen instead of under the button.
        androidx.compose.foundation.layout.Box {
            androidx.compose.material3.IconButton(onClick = { rowMenuOpen = true }) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "More options for ${sourceWithState.source.displayName}",
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = rowMenuOpen,
                onDismissRequest = { rowMenuOpen = false },
            ) {
                // Offered first, and only where there is something to install: it is the one action
                // that answers the registry line above it, and it names the version it would take.
                if (behindRegistry) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (isUpdating) "Updating…" else "Update to $registryVersion") },
                        enabled = !isUpdating,
                        onClick = {
                            rowMenuOpen = false
                            onUpdate()
                        },
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Test this source") },
                    onClick = {
                        rowMenuOpen = false
                        onTest()
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Move up") },
                    enabled = canMoveUp,
                    onClick = {
                        rowMenuOpen = false
                        onMoveUp()
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Move down") },
                    enabled = canMoveDown,
                    onClick = {
                        rowMenuOpen = false
                        onMoveDown()
                    },
                )
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

/**
 * A section's title. There is no fold any more, deliberately.
 *
 * Folding hid the two things this screen exists to explain: the switch that greys a neighbouring one
 * out, and the reason an engine is not playing. Both engines' settings are short enough to show, and a
 * collapsed section read as "nothing to see here" over engines that were in fact not playing at all.
 */
@Composable
private fun EngineSectionTitle(
    title: String,
    subtitle: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What an engine is doing right now - status only, deliberately with no action on it.
 *
 * This line used to carry its own "Verify" button, which made the same job reachable from two
 * places on one screen: the button here fired a single source's check, the sessions card below
 * offered "Verify & renew all", and every row had a "Verify" of its own. Three controls, one job,
 * and no way to tell whether they did the same thing. The status is what belongs up here - it sits
 * under the switch that changes it - and the actions live once, together, in the sessions card.
 *
 * A paused engine is not one problem, which is why the line still only *states* the reason: a
 * gateway rate limit is a wait, and a button offering a Cloudflare check for it would be worse than
 * no button at all.
 */
@Composable
private fun EngineStatusLine(status: SpotiFLACEngineStatus) {
    val accent =
        if (status.usable) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
            )
            Text(
                text = status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun spotiflacPrefetchSummary(count: Int): String =
    when (count.coerceIn(0, 4)) {
        0 -> "Off - \"Prefetch upcoming songs\" is set to Off in Playback settings."
        1 -> "Fetches the next song ahead through SpotiFLAC (and resolves the next stream URL). " +
            "The count is owned by \"Prefetch upcoming songs\" in Playback settings."
        else -> "Fetches the next $count songs ahead through SpotiFLAC (and resolves the next " +
            "$count stream URLs). The count is owned by \"Prefetch upcoming songs\" in " +
            "Playback settings."
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

/** One engine row in the playback priority list, with its 1st/2nd badge and one overflow. */
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
        // One overflow rather than a pair of arrows: those were two controls for one job - moving a
        // row in a list of two - and the one that did not apply simply vanished, so the row's width
        // changed as the list was reordered. The badge already says which position the engine holds;
        // this says what can be done about it, the same way every other row on this screen does.
        var menuOpen by remember(name) { mutableStateOf(false) }
        // The button and its menu share one `Box`, because a `DropdownMenu` is anchored to its parent
        // layout node - as siblings of this full-width row they anchor to the row, and the menu then
        // opens against the screen's edge rather than under the button that was tapped.
        androidx.compose.foundation.layout.Box {
            androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "More options for $name",
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Move up") },
                    enabled = canMoveUp,
                    onClick = {
                        menuOpen = false
                        onMoveUp()
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Move down") },
                    enabled = canMoveDown,
                    onClick = {
                        menuOpen = false
                        onMoveDown()
                    },
                )
            }
        }
    }
}