package app.hush.music.ui.screens.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hush.music.ui.component.HushProgressSpinner
import app.hush.music.ui.component.TextFieldDialog
import app.hush.music.R
import app.hush.music.constants.AudioQuality
import app.hush.music.constants.AudioQualityKey
import app.hush.music.constants.PrefetchCountKey
import app.hush.music.constants.SourcePriorityKey
import app.hush.music.constants.SpotiFLACCacheStreamsKey
import app.hush.music.constants.SpotiFLACEmbedReplayGainKey
import app.hush.music.constants.SpotiFLACEnabledDefault
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.SpotiFLACFallbackToYouTubeKey
import app.hush.music.constants.SpotiFLACFetchPoliciesKey
import app.hush.music.constants.SpotiFLACPrefetchNextKey
import app.hush.music.constants.SpotiFLACQualityKey
import app.hush.music.constants.ParallelSourceFetchKey
import app.hush.music.constants.SpotiFLACTryNextSourceKey
import app.hush.music.constants.SpotiFLACVerifiedOnlyKey
import app.hush.music.constants.YoutubeStreamingEnabledKey
import app.hush.music.spotiflac.ExtensionRepositories
import app.hush.music.spotiflac.ExtensionRepositoryManager
import app.hush.music.spotiflac.RepositoryAddResult
import app.hush.music.spotiflac.SpotiFLACChallengeEngine
import app.hush.music.spotiflac.SpotiFLACChallengeRoute
import app.hush.music.spotiflac.SpotiFLACAvailability
import app.hush.music.spotiflac.SpotiFLACDiag
import app.hush.music.spotiflac.SpotiFLACEngineState
import app.hush.music.spotiflac.SpotiFLACEngineStatus
import app.hush.music.spotiflac.SpotiFLACFetchPolicies
import app.hush.music.spotiflac.SpotiFLACFetchPolicy
import app.hush.music.spotiflac.SpotiFLACExtensionUpgrade
import app.hush.music.spotiflac.SpotiFLACSessionRecovery
import app.hush.music.spotiflac.SpotiFLACSessionRenewer
import app.hush.music.spotiflac.SpotiFLACSessionVerdict
import app.hush.music.spotiflac.SpotiFLACSessionVerdictReport
import app.hush.music.spotiflac.SpotiFLACVerificationChecklist
import app.hush.music.spotiflac.SpotiFLACSourceMark
import app.hush.music.spotiflac.SpotiFLACSourceAuthState
import app.hush.music.spotiflac.SourceTestState
import app.hush.music.spotiflac.SpotiFLACPackageUpdateLog
import app.hush.music.spotiflac.SpotiFLACPackageUpdateReport
import app.hush.music.spotiflac.SourceWithState
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberLowDataModeActive
import app.hush.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SpotiFLACSettings"

/**
 * How old a passing check may be before the row states it again.
 *
 * A check that matched its registry is the state the row's version already proves, so drawing it
 * under every source is noise - twelve rows saying `Checked 55 min ago`. Half a day is long enough
 * that the line means "this is getting stale" rather than "this just happened".
 */
private const val CHECK_LINE_STALE_MS = 12L * 60L * 60L * 1000L

/**
 * How often the renewal countdown is re-derived from the session read the screen is holding.
 *
 * The label is minute-granular, so this only has to be fine enough that a number never reads as
 * stale - and coarse enough that a screen left open is not recomposing on a clock for no gain.
 */
private const val RENEWAL_COUNTDOWN_TICK_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotiFLACSettingsScreen(
    navController: androidx.navigation.NavController,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val (spotiflacEnabled, setSpotiflacEnabled) =
        rememberPreference(SpotiFLACEnabledKey, defaultValue = SpotiFLACEnabledDefault)
    // Low data mode owns this switch: while it is on, SpotiFLAC is off and cannot be turned on,
    // because every SpotiFLAC play is a whole lossless file pulled over a metered link.
    val lowDataActive = rememberLowDataModeActive()
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
    // Per-source fetch windows and retry counts, as the one JSON value they are stored in. The rows
    // read the policy for their own source out of it, so a source with no entry shows the shipped
    // defaults and the dialog it opens starts from exactly what the walk will use.
    val (fetchPoliciesRaw, setFetchPoliciesRaw) =
        rememberPreference(SpotiFLACFetchPoliciesKey, defaultValue = "")
    val (embedReplayGain, setEmbedReplayGain) =
        rememberPreference(SpotiFLACEmbedReplayGainKey, defaultValue = false)
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
    // 1-based position of each source that is actually in the resolve order, so the list shows which
    // repository is tried first. A source that is switched off, or that needs a runtime this build
    // does not have, is not in that order and must not be numbered as if it were.
    val sourceChoiceLabels =
        remember(sources) {
            sources
                .filter { it.enabled && it.source.isPlayable }
                .mapIndexed { choiceIndex, state -> state.source.id to (choiceIndex + 1) }
                .toMap()
        }
    val isSyncing by repoManager.isSyncing.collectAsState()
    // Every repository Hush reads from - Hush's own two, the shipped module index and whatever the
    // user added - with what it declares, whether Hush reads it, and what the user calls it. One
    // list, because a repository that is read but not drawn is a URL nobody can take back out, and
    // one drawn without its switch is a source list nobody can explain.
    val repositories by repoManager.repositories.collectAsState()
    var showRepositoryDialog by remember { mutableStateOf(false) }
    var repositoryDialogMessage by remember { mutableStateOf<String?>(null) }
    var isAddingRepository by remember { mutableStateOf(false) }
    // The repository being renamed, if any. Held as the row rather than as a URL so the dialog can
    // offer what the row currently shows as its starting point instead of an empty field.
    var renamingRepository by remember { mutableStateOf<app.hush.music.spotiflac.RepositoryRow?>(null) }
    // The repository a Remove tap is about, if any. Removing a row is the one control here that
    // cannot be undone from the phone: the address has to be pasted again, and its sources leave the
    // list in the meantime. So it asks first.
    var removingRepository by remember { mutableStateOf<app.hush.music.spotiflac.RepositoryRow?>(null) }

    var isExchanging by remember { mutableStateOf(false) }

    /**
     * The source whose fetch windows are open in the dialog, if any.
     *
     * Held as the source rather than as an id so the dialog can name it and start from the policy the
     * walk will read; the store is keyed by id, and an id kept here would have to be looked up again
     * to say what it names.
     */
    var editingFetchPolicy by remember {
        mutableStateOf<app.hush.music.spotiflac.ExtensionSource?>(null)
    }

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

    /**
     * The monotonic clock behind [sessionValidity], so the renewal countdown can age with it.
     *
     * [sessionValidity] is a snapshot of remaining seconds, and this screen can stay open for far
     * longer than the read that produced it. Kept as an elapsed-time reading rather than a wall-clock
     * one because it exists only to measure how long ago the read happened, and a clock the user can
     * change must not be able to move the countdown with it.
     */
    var sessionSnapshotElapsedMs by remember { mutableStateOf(0L) }

    /** The session id on disk per source, so a renewal verdict can be matched to the session it judged. */
    var sessionIds by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    /** What the gateway last said about each source's session. */
    var sessionVerdicts by remember {
        mutableStateOf<Map<String, SpotiFLACSessionVerdict>>(emptyMap())
    }
    var renewMessage by remember { mutableStateOf<String?>(null) }

    /**
     * The shared recovery run's own state, observed rather than owned.
     *
     * Renew is one action with one implementation - this screen's Renew and the launch notice's are
     * both [SpotiFLACSessionRecovery.renewAndCheck] - so the two also have one progress and one
     * outcome, and neither can report something the other did not do. Owning the coroutine here
     * instead meant leaving this screen mid-run cancelled the reads that report the result, and the
     * launch notice's own copy of the work was cancelled outright the moment its card was dismissed.
     */
    val isRenewingSessions by SpotiFLACSessionRecovery.running.collectAsState()
    val recoveryOutcome by SpotiFLACSessionRecovery.outcome.collectAsState()

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
        sessionSnapshotElapsedMs = SystemClock.elapsedRealtime()
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
            // Only a source that is actually in use can be blocked by its own session. A
            // switched-off source is not swept, renewed or asked anything, so counting it here put
            // it in front of "Verify all" - a batch whose whole job is the sources the user chose,
            // and which would then open a challenge and spend a request for a source that is not
            // being played.
            .filter { it.enabled }
            .map { it.source }
            // A check applies to the sources that go through Hush's signed gateway session, which
            // is a property of the source: a module index's plugins carry no credentials at all, a
            // lyrics provider has no session to mint, and a service that signs in with its own
            // account is verified by that service. Asking whether a source downloads counted the
            // first and the last of those as needing a check they can never be given.
            .filter { it.requiresSpotiFLACSession }
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
     * Where every source's session stands, for the whole screen.
     *
     * Computed once here rather than inside the card that used to draw it, because the state belongs
     * to the *source* and not to the surface that happens to report it: the summary line, the batch
     * buttons and the line under each source in the list below all read the same rows. Splitting them
     * is what produced two lists of the same sources - one to verify and one to enable - on a screen
     * whose whole job is to show one list of sources with everything about them on the row.
     */
    val sessionRows =
        sources.map { state ->
            val source = state.source
            source to
                SpotiFLACSessionVerdictReport.row(
                    authState = verifyStatus[source.id],
                    remainingSeconds = sessionValidity[source.id],
                    verdict = sessionVerdicts[source.id],
                    currentSessionId = sessionIds[source.id],
                    formatRemaining = ::formatSessionValidity,
                    // The switch is part of the verdict: an off source is not checked, and the row
                    // says so instead of claiming a check that is not running.
                    enabled = state.enabled,
                )
        }
    val sessionRowsBySource = sessionRows.associate { (source, row) -> source.id to row }
    val healthySessionCount = sessionRows.count { (_, row) -> row.healthy }
    val attentionSessionCount = sessionRows.count { (_, row) -> row.needsCheck }


    /**
     * Re-reads every listed source's auth state, switched on or not.
     *
     * The runtime's own signed-session record is the single source of truth, so a verification
     * performed by *any* surface - the automatic run, the player overlay, the notification, the
     * grant deep link, or this screen - shows up here as soon as it lands. Reading it only once,
     * when the screen appeared, is what made a source the app had already verified still read
     * "Verification needed", sending the user back to solve a check whose grant was already spent.
     *
     * Deliberately keyed on the whole list rather than on the enabled sources: the read is a manifest
     * and a session record off disk - no request, no runtime call - so a switched-off source can be
     * described as accurately as a switched-on one. Reading only the enabled ones is what made every
     * switched-off row fall through to "Checking session…": the state map had no entry for it and a
     * miss was drawn as "not read yet", a check that is never going to happen because the source is
     * off. The rows decide what to do with the answer; this only supplies it.
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
            // Read from the manager rather than from the list this composition captured: a refresh
            // that runs before the first registry sync landed would otherwise describe an empty
            // list and leave every row on "Checking session…" until something else re-ran it.
            repoManager.sources.value.map { it.source.id }.associateWith { bridge.sourceAuthState(it) }
        }
        refreshSessionValidity()
    }

    // Keyed on the set of enabled sources rather than on the row objects themselves. A row carries
    // its own test state, and warming the runtime can end in the verifier recording a result, which
    // writes that state back into this same list - so keying on the rows re-ran this effect, warmed
    // the runtime again, asked the verifier again, and so on for as long as the screen stayed open.
    // Ids compare structurally, so a result landing no longer re-triggers the warm-up.
    // Playable as well as enabled, the same predicate the runtime is asked with: a module index's
    // plugin is listed with its switch off and this build cannot play it, so warming the runtime for
    // it would be a request for a source the runtime has never heard of.
    val enabledSourceIds =
        remember(sources) {
            sources.filter { it.enabled && it.source.isPlayable }.map { it.source.id }
        }

    /**
     * What the SpotiFLAC engine is doing, which is not what its switch says.
     *
     * An engine whose session-bearing sources have all lost their sessions is *paused*, and playback
     * is running on YouTube until one is verified - so that is what this screen has to say. Recomputed
     * whenever a source's state, a verdict or the gateway's block changes, so the line cannot outlive
     * the thing it describes.
     */
    val spotiflacEngineStatus =
        remember(spotiflacEnabled, enabledSourceIds, verifyStatus, relayBlock, lowDataActive) {
            SpotiFLACAvailability.current(
                context = context,
                enabled = spotiflacEnabled,
                sources = enabledSourceIds,
                lowDataActive = lowDataActive,
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

    /**
     * The line under each source in the list, including what a run in flight is doing with it.
     *
     * A challenge being solved and one not yet started look identical in the session record on disk,
     * so while a run is in flight the run's own step is what the row says - "solving", "waiting",
     * "landed" - and the record's sentence takes over again the moment the run is over. Built here
     * rather than inside the row so the row stays a description of a source and the run stays one
     * fact about the whole list.
     */
    val sessionLines =
        sessionRowsBySource.mapValues { (sourceId, row) ->
            val runStep =
                if (SpotiFLACVerificationChecklist.isRunning(autoVerificationSource)) {
                    SpotiFLACVerificationChecklist.stepFor(verificationSteps, sourceId)
                } else {
                    null
                }
            SourceSessionLine(
                text = runStep?.let { SpotiFLACVerificationChecklist.text(it.state) } ?: row.text,
                tone = runStep?.let { SpotiFLACVerificationChecklist.tone(it.state) },
                healthy = row.healthy,
                checkable = row.checkable && !SpotiFLACVerificationChecklist.isRunning(autoVerificationSource),
                // A run in flight has its own words and no countdown: the record's remaining time is
                // about the session the run is replacing.
                remaining = row.remaining.takeIf { runStep == null },
            )
        }

    // A verification that completes anywhere else has to reach these rows without the user leaving
    // and returning: otherwise the only button on offer is this screen's own "Verify", and the
    // challenge behind it is the one that was just spent - which reads as "I verified and it still
    // wants another check". The ticker is bumped by the verifier for exactly this, and the active
    // slot is watched so a run that gives up also un-sticks the row.
    LaunchedEffect(verifiedTicker, autoVerificationSource) {
        if (!spotiflacEnabled) return@LaunchedEffect
        refreshVerifyStatus()
    }

    // A source that refused and was re-asked in the background has just come back. Its row still
    // says "Skipped for 8min" until the notes are re-read, which is the one moment that line is
    // actively wrong - playback is already using the source again.
    val recoveredTicker by app.hush.music.spotiflac.SpotiFLACAddonRecovery.ticker.collectAsState()
    LaunchedEffect(recoveredTicker) {
        if (!spotiflacEnabled || recoveredTicker == 0) return@LaunchedEffect
        refreshInstalledVersions()
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
        renewMessage = null
        // The same action the launch notice's Renew runs, so the two cannot drift about what "Renew"
        // means. It renews every session the gateway will still rotate and then raises a check for
        // whatever that could not fix - a source the gateway has thrown away cannot be renewed at
        // all, so renewing alone would leave it exactly as unusable while the button looked pressed.
        SpotiFLACSessionRecovery.renewAndCheck(context, reason = "settings-renew")
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

    // Everything a finished run can change is read back here, at the one place that knows a run
    // ended. Reporting it from inside the coroutine that ran it is how the launch notice lost its
    // result: that coroutine does not survive the card that started it, so the rows above and the
    // message under the buttons were never refreshed at all.
    LaunchedEffect(recoveryOutcome) {
        val report = recoveryOutcome ?: return@LaunchedEffect
        renewMessage = report.line
        refreshSessionValidity()
        // A source the gateway says is gone has to stop reading "Verified" in the rows above, which
        // is also what puts it in the count the Verify button acts on.
        refreshVerifyStatus()
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
                    // Named for what the section lists, not for one of the repositories in it:
                    // Hush reads SpotiFLAC's own registries, the BitChord module index and anything
                    // the user adds, and calling all of that "SpotiFLAC" made a BitChord module look
                    // like part of a repository it has nothing to do with.
                    EngineSectionTitle(
                        title = "Third-party sources",
                        subtitle = "Lossless audio from the sources below. Each row names the " +
                            "repository it came from.",
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
                        // The switch reads off while low data mode holds, and cannot be thrown:
                        // the stored preference is left alone, so the engine returns the moment
                        // low data mode does instead of having to be switched back on by hand.
                        checked = spotiflacEnabled && !lowDataActive,
                        onCheckedChange =
                            if (youtubeEnabled && !lowDataActive) {
                                { enabled -> setSpotiflacEnabled(enabled) }
                            } else {
                                null
                            },
                        enabled = youtubeEnabled && !lowDataActive,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // What the engine is actually doing, which is not the same as what the switch
                    // says: a SpotiFLAC with no usable source is paused, and playback is running on
                    // YouTube in the meantime. Shown here rather than in a separate card because this
                    // is where the switch that would fix it lives.
                    EngineStatusLine(status = spotiflacEngineStatus)
                    // When the sessions behind that status are next topped up. The launch notice can
                    // only speak at launch and only when something is already wrong; this is the same
                    // fact on the screen the user comes to when they want to know, at any time.
                    if (spotiflacEnabled) {
                        SessionRenewalLine(
                            remainingSeconds = sessionValidity.values.toList(),
                            snapshotElapsedMs = sessionSnapshotElapsedMs,
                        )
                    }
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
                                    name = if (engine == "SPOTIFLAC") "3rd-party" else "YouTube",
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
                                text = "YouTube is disabled, so every track plays through the third-party " +
                                    "sources below. There is no order to choose.",
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
                        // Off by default: the loudness tags only matter to players that honour
                        // them, and they change what a downloaded file carries.
                        SettingsSwitchRow(
                            title = stringResource(R.string.spotiflac_embed_replaygain),
                            checked = embedReplayGain,
                            onCheckedChange = setEmbedReplayGain,
                        )
                        Text(
                            text = stringResource(R.string.spotiflac_embed_replaygain_desc),
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
                            text = "Enable, disable & reorder sources. Higher priority sources tried first. " +
                                "Every row names the repository it came from, so the order here is also the " +
                                "answer to \"which repository is being tried\" - and switching a repository off " +
                                "takes its sources out of this list rather than leaving them to be tried anyway.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The order above only matters at all while SpotiFLAC is an engine the player
                        // reaches. Said here, next to the list it applies to, because a user who has
                        // just added a repository and sees nothing change is looking at this list -
                        // and the reason is one row further down in Playback priority.
                        if (youtubeEnabled && priorityOrder.firstOrNull() != "SPOTIFLAC") {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "YouTube is tried before SpotiFLAC in Playback priority, so this order " +
                                    "is only consulted when YouTube cannot answer. Move SpotiFLAC to the top " +
                                    "there to have these sources decide first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // What the startup check did, next to the list it is about. Packages are
                        // kept current without the user asking, so the only thing left to do here
                        // is say so - a silent update is indistinguishable from a stale build, and
                        // the only place it used to be reported was the diagnostic log.
                        //
                        // There is no "Check for updates" button. A source that is behind its
                        // registry carries its own Update action under its version line, and that
                        // action is the same forced refresh this button used to run for the whole
                        // list - so the update is always next to the version it would install, and
                        // the screen has one answer about packages instead of a sweep's summary
                        // competing with the rows the sweep produced.
                        val packageSummary by SpotiFLACPackageUpdateLog.summary.collectAsState()
                        val packageUpdateLine =
                            packageSummary?.let { SpotiFLACPackageUpdateReport.summary(it) }
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
                        )
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
                            text = "Each source keeps its own gateway session, so a check covers that source " +
                                "from then on. A source that needs no session says so on its own row.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // The counts and the per-source states are read from the one list of sources
                        // this screen keeps: every source in it is counted here, whether or not it
                        // has a session to keep, because a source left out of the summary is a source
                        // a reader has to go and find to work out why the totals do not add up.
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
                                    healthy = healthySessionCount,
                                    needsCheck = attentionSessionCount,
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
                                        healthySessionCount + attentionSessionCount > 0,
                                ) {
                                    Text(if (isRenewingSessions) "Working…" else "Renew")
                                }
                                androidx.compose.material3.TextButton(
                                    onClick = { verifyAllSources(sourcesNeedingCheck.map { it.id }) },
                                    enabled = !isRenewingSessions && !isExchanging && attentionSessionCount > 0,
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
                        // The list below *is* this list. Each source's session used to be drawn a
                        // second time here, under the same mark and the same name, and the two lists
                        // disagreed the moment one of them was stale - a source could sit at "needs a
                        // check" here while its own row below said "verified", with no way to tell
                        // which was current. The state moved to the row (see `SourceRow`'s session
                        // line): one source, one row, carrying its switch, its role, its repository
                        // and where its session stands. What stays on this card is what is about the
                        // *set* - the summary and the two batch actions - which is also the only place
                        // they can live, since a batch is not a property of any one row.
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Sessions renew in the background. Verify fixes a source the gateway " +
                                "refused; Renew rotates one it still accepts.",
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

                // Keyed by source id, not by position. The row's own state (an open overflow, a
                // marquee position, its animation) lives in the item's composition; with index keys
                // every row after a moved or newly landed source is a *different* item, so one
                // reorder rebuilt the whole list's state and a sync that inserted a source
                // recomposed every row below it. The id is stable across all of that, which is what
                // keys are for.
                itemsIndexed(sources, key = { _, sourceWithState -> sourceWithState.source.id }) { index, sourceWithState ->
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
                        // Which repository this entry came from, in brackets. Answered by one rule
                        // rather than by a lookup here: a lookup that misses draws no line at all,
                        // which is how a source Hush ships itself and no registry declares - Pandora
                        // - became the one row in the list naming no repository.
                        repositoryLine =
                            app.hush.music.spotiflac.sourceProvenanceLine(
                                sourceWithState.source,
                                repositories,
                            ),
                        // A module keeps no session - it is credential-less by construction, which is
                        // the whole point of one - so a session line here would be a statement about
                        // a check that can never apply to it. It read "Checking session…" on the
                        // reporting device, which is exactly the kind of line that makes a source
                        // look broken while it is working.
                        // The windows and retry count this source is fetched with, on the row itself:
                        // one line that answers "why is this source slow" without opening anything, and
                        // the same numbers the overflow's dialog edits.
                        fetchPolicy =
                            SpotiFLACFetchPolicies.policyFor(fetchPoliciesRaw, sourceWithState.source.id),
                        onEditFetchPolicy = { editingFetchPolicy = sourceWithState.source },
                        sessionLine =
                            sessionLines[sourceWithState.source.id]
                                // Never for a source this build does not run through the runtime, and
                                // never while the engine itself is switched off: with SpotiFLAC off
                                // nothing is checked at all, and the line above the list already says
                                // so - a row claiming otherwise would be inventing a check.
                                .takeIf { spotiflacEnabled && !sourceWithState.source.isPlayedOutsideRuntime },
                        onVerify = {
                            startVerification(sourceWithState.source.id)
                            // The shared run owns the challenge; this only re-reads where the source
                            // stands once it has been raised, so the row cannot keep showing a stale
                            // verdict while the check is solved in the overlay above it.
                            scope.launch { refreshVerifyStatus() }
                        },
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

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    // Where the list above comes from, and the switch that takes a repository's
                    // sources back out of it. Reading the registries was invisible on purpose while
                    // the two were hardcoded - there was nothing to decide - and the moment one can
                    // be added and switched off the rows have to name their own provenance: a source
                    // is only as good as the repository that declared it, and a repository that
                    // stops answering or is switched off has to be visible where the user can act.
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = "Repositories",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Each repository publishes sources. Switching one off stops it being " +
                                "fetched: its sources leave the list above, the playback order and the checks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        repositories.forEach { repository ->
                            RepositoryRowView(
                                repository = repository,
                                isBusy = isAddingRepository,
                                onToggleEnabled = { enabled ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            repoManager.setRepositoryEnabled(repository.url, enabled)
                                        }
                                    }
                                },
                                onRename = { renamingRepository = repository },
                                onRemove = { removingRepository = repository },
                            )
                        }

                        androidx.compose.material3.TextButton(
                            onClick = {
                                repositoryDialogMessage = null
                                showRepositoryDialog = true
                            },
                            enabled = !isAddingRepository,
                        ) {
                            Text("Add repository")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (showRepositoryDialog) {
            TextFieldDialog(
                title = { Text("Add a source repository") },
                placeholder = { Text("https://example.com/registry.json") },
                // A URL field is not prose: an IME that capitalises the host or autocorrects the
                // owner to a word with a space in it produces an address Hush must refuse, and the
                // refusal looks like the field being wrong rather than the keyboard being clever.
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                invalidInputMessage =
                    "That does not look like a link Hush can fetch yet. It needs https:// and a host, " +
                        "with no spaces.",
                // The address has to look like one Hush can turn into a file before the button
                // lights up, and nothing is stored until that file has answered with a repository
                // Hush can read - so a typo cannot leave a dead row behind. A GitHub file *page* is
                // accepted here: the page address is what a browser offers, and the fetch follows
                // it to the raw file it names.
                isInputValid = { !isAddingRepository && ExtensionRepositories.addCandidates(it).isNotEmpty() },
                // The dialog stays while the address is fetched. Closing on Done is what made a
                // refused link look like a tap that did nothing at all: the reason arrived after
                // the dialog was gone.
                dismissOnDone = false,
                onDone = { rawUrl ->
                    scope.launch {
                        isAddingRepository = true
                        repositoryDialogMessage = null
                        val result =
                            withContext(Dispatchers.IO) { repoManager.addRepository(rawUrl) }
                        isAddingRepository = false
                        when (result) {
                            is RepositoryAddResult.Added -> {
                                repositoryDialogMessage = null
                                showRepositoryDialog = false
                            }

                            is RepositoryAddResult.Rejected -> repositoryDialogMessage = result.reason
                        }
                    }
                },
                onDismiss = {
                    if (!isAddingRepository) {
                        showRepositoryDialog = false
                        repositoryDialogMessage = null
                    }
                },
                extraContent = {
                    val message = repositoryDialogMessage
                    Text(
                        text = when {
                            message != null -> message
                            isAddingRepository -> "Reading the repository\u2026"
                            else ->
                                "Paste a repository link or its registry.json - a GitHub file page works " +
                                    "too. Nothing is kept until it answers with sources Hush can read."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (message != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                },
            )
        }

        // Renaming a repository changes only what it is called. The URL is still what is fetched and
        // what a source's provenance is matched on, so a name can be changed as often as a user likes
        // without a single source moving.
        renamingRepository?.let { repository ->
            TextFieldDialog(
                title = { Text("Name this repository") },
                initialTextFieldValue = androidx.compose.ui.text.input.TextFieldValue(repository.nickname.orEmpty()),
                placeholder = { Text("e.g. Lossless modules") },
                isInputValid = { true },
                onDone = { name ->
                    scope.launch {
                        repoManager.setRepositoryNickname(repository.url, name)
                    }
                    renamingRepository = null
                },
                onDismiss = { renamingRepository = null },
                extraContent = {
                    Text(
                        text = "Leave it empty to go back to " + ExtensionRepositories.label(repository.url) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        // Removing a repository takes its sources out of the list, the playback order and the
        // checks, and the address has to be pasted again to bring it back. One tap was enough to do
        // that from a menu, so it asks first - and it says what goes with it.
        removingRepository?.let { repository ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { removingRepository = null },
                title = { Text("Remove ${repository.label}?") },
                text = {
                    Text(
                        text = "Its sources leave the list and the playback order, and Hush stops " +
                            "checking them. Packages already installed stay installed, and you can add " +
                            "the address again at any time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            removingRepository = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    repoManager.removeRepository(repository.url)
                                }
                            }
                        },
                    ) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { removingRepository = null }) {
                        Text("Keep")
                    }
                },
            )
        }

        // The per-source fetch windows. One dialog for one source, opened from that source's overflow:
        // the values are about a single provider's behaviour on this network, so a screen-wide editor
        // would have to say which source each number belonged to and would let one save change every
        // source at once.
        editingFetchPolicy?.let { source ->
            FetchPolicyDialog(
                // The same name its row above carries, so the dialog that edits one source's windows
                // names the source the way the row and the player do.
                sourceName =
                    app.hush.music.spotiflac.SpotiFLACSourceName.name(
                        sourceId = source.id,
                        name = source.displayName,
                    ),
                initial = SpotiFLACFetchPolicies.policyFor(fetchPoliciesRaw, source.id),
                onSave = { policy ->
                    setFetchPoliciesRaw(
                        SpotiFLACFetchPolicies.updated(fetchPoliciesRaw, source.id, policy),
                    )
                    editingFetchPolicy = null
                },
                onReset = {
                    setFetchPoliciesRaw(
                        SpotiFLACFetchPolicies.updated(fetchPoliciesRaw, source.id, null),
                    )
                    editingFetchPolicy = null
                },
                onDismiss = { editingFetchPolicy = null },
            )
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

/**
 * One repository, with the two things a user can do to it: switch it off, or rename it.
 *
 * The switch is in the open because it is the row's state, and it is the same control every source
 * row below uses for the same question one level down. Renaming is behind the row's overflow, next to
 * removal, because both are statements about the whole repository rather than about using it.
 */
@Composable
private fun RepositoryRowView(
    repository: app.hush.music.spotiflac.RepositoryRow,
    isBusy: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = repository.label,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (repository.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
            )
            Text(
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                text = buildString {
                    append(
                        when {
                            repository.isBundled -> "Built-in"
                            // `removable` is true for everything Hush does not own, so it used to
                            // label a URL the user pasted "Shipped with Hush". The shipped
                            // defaults are named by the list they come from, not by the fact that
                            // they can be removed.
                            repository.isShippedDefault -> "Shipped"
                            else -> "Added by you"
                        },
                    )
                    // What it answered with, which is the one thing that tells a registry and a
                    // module index apart on screen - they are both a JSON file at an address - and
                    // how many entries it publishes.
                    // Short enough to keep the whole line inside the width a switch and an
                    // overflow leave: `Built-in \u00b7 registry \u00b7 12 sources` instead of
                    // `Built-in \u00b7 SpotiFLAC registry \u00b7 12 sources`, whose last word wrapped
                    // onto a line of its own on every row.
                    repository.kind?.let { kind ->
                        append(" \u00b7 ")
                        append(
                            when (kind) {
                                app.hush.music.spotiflac.RepositoryKind.SPOTIFLAC_REGISTRY -> "registry"
                                app.hush.music.spotiflac.RepositoryKind.MODULE_INDEX -> "module index"
                                app.hush.music.spotiflac.RepositoryKind.SERVER_ADDON -> "addon"
                            },
                        )
                    }
                    append(" \u00b7 ")
                    append(
                        when (val declared = repository.declaredSources) {
                            null -> "not read"
                            0 -> "no sources"
                            1 -> "1 source"
                            else -> "$declared sources"
                        },
                    )
                    // What a check means for what this repository publishes used to be a third
                    // clause here (`sources verify one by one`, `credential-less modules: nothing to
                    // verify`). It is the source rows' own business - every one of them says where
                    // its session stands - and repeated on the repository it made two lines of
                    // explanation out of a switch.
                    //
                    // A switched-off repository is not fetched at all, so its sources are not merely
                    // unchecked - they are absent from the list above, from the resolve order and
                    // from the checks. Said here so the missing rows are explained where the switch
                    // that removed them is.
                    if (!repository.enabled) append(" \u00b7 off")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            repository.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // The whole address, for the rows whose address is not part of the build: a name is
            // not enough to recognise a repository by once a user has two from the same host, and a
            // personal URL is the one thing only they can check. A built-in or shipped row is
            // addressed by the app itself - its label already names it, and five addresses under
            // five labels was a page of URLs to scroll past - so it draws none unless reading it
            // failed, which is the case the address explains.
            if ((!repository.isBundled && !repository.isShippedDefault) || repository.error != null) {
                Text(
                    text = repository.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        Switch(
            checked = repository.enabled,
            onCheckedChange = { if (!isBusy) onToggleEnabled(it) },
            enabled = !isBusy,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )

        var menuOpen by remember(repository.url) { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "More options for ${repository.label}",
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Rename this repository") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                // Not offered for Hush's own registries: the sources they address are the ones the
                // runtime and the signed-session contracts are written against, so a build without
                // them would list sources it cannot install. Switching them off is the reversible
                // half, and it is the switch on the row.
                if (repository.removable) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Remove this repository") },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/** The one-line result of an Update tap, shown under the row that asked for it. */
private data class PackageMessage(val text: String, val isError: Boolean)

/**
 * Where one source's session stands, and how the row that draws it should say so.
 *
 * [tone] is the run's own verdict when a run is in flight and null otherwise - the session record's
 * sentence does not need one, because [healthy] already decides whether it reads as good news - and
 * [checkable] is whether a single-source check applies at all, which is the whole condition for
 * offering the row's overflow action.
 */
private data class SourceSessionLine(
    val text: String,
    val tone: SpotiFLACVerificationChecklist.Tone?,
    val healthy: Boolean,
    val checkable: Boolean,
    /**
     * The countdown for a session that is still live, drawn as its own value - see
     * `SpotiFLACSessionVerdictReport.Row.remaining` for why it is not part of [text].
     */
    val remaining: String? = null,
)

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
    /**
     * Which repository this source came from, in brackets, or null when the row's own name already
     * says it. Resolved by `sourceProvenanceLine`, so every row that needs one has one.
     */
    repositoryLine: String? = null,
    /** Where its session stands, or null for a source that has no session to keep. */
    sessionLine: SourceSessionLine? = null,
    /** A single-source check, offered only where one applies. */
    onVerify: () -> Unit = {},
    /**
     * How long this source is given and how many times it is asked, drawn on the row and edited from
     * its overflow. Shown for every source rather than only for the ones that differ: the line is the
     * only place the walk's windows exist on this screen, and a user who has never opened the dialog
     * should still be able to read what their player will do.
     */
    fetchPolicy: SpotiFLACFetchPolicy = SpotiFLACFetchPolicy.DEFAULT,
    onEditFetchPolicy: () -> Unit = {},
) {
    // Which build is actually running, next to the one the registry publishes. Without this the
    // row can only ever show what the source *is*, so a package left behind by a registry bump is
    // invisible - the state that kept amazon on 2.3.8 for a whole install. Read here rather than
    // inside the text column because the row's own update control sits under it, and it names the
    // version it would install.
    val registryVersion = sourceWithState.source.version.trim()
    // What the source is called on this row, from the one function the player's own label is built
    // from: a server addon that named itself after its host reads `Tidal · gglos` here as it does on
    // the codec bar, because `gglos` among twelve providers answers nothing about what it serves.
    // The registry's own wording is kept wherever it wrote one, which is what the name is for.
    val rowName =
        app.hush.music.spotiflac.SpotiFLACSourceName.name(
            sourceId = sourceWithState.source.id,
            name = sourceWithState.source.displayName,
        )
    val loadedVersion = installedVersion?.trim()
    // The version, short enough to share a line with the role: `v1.0.10` rather than
    // `Version 1.0.10`, and no second copy of a registry version the Update button already names.
    // What is *installed* is the fact; what the registry publishes only matters when nothing is
    // installed yet, which is the one case that says so.
    val versionLine = when {
        // A module is never installed and never will be: its script is fetched and run when the
        // source is used, so "not installed" describes a state this row is never in and reads as a
        // fault. What can be said about it is the version its index publishes - which is also the
        // thing that changes when the module does, since the script's cache key carries it.
        sourceWithState.source.isHostedModule ->
            registryVersion.takeIf { it.isNotEmpty() }?.let { "module $it" }

        // A server addon is not installed either: its version is what its manifest says, and a
        // row claiming "not installed" would describe an install that is never meant to happen.
        sourceWithState.source.isServerAddon ->
            registryVersion.takeIf { it.isNotEmpty() }?.let { "addon $it" }

        registryVersion.isEmpty() && loadedVersion.isNullOrEmpty() -> null
        loadedVersion.isNullOrEmpty() -> "v$registryVersion (not installed)"
        else -> "v$loadedVersion"
    }
    // One line for what the source is: its role and the build it is running. Two lines said the same
    // thing twice as tall, and on a 360dp screen the pair was the difference between a list a user
    // can scan and one they have to read.
    // A source whose manifest states no role at all (the built-in Pandora entry) used to print
    // `Unknown role` ahead of its version, which is a sentence about the *registry* rather than about
    // the source: the row said nothing the user could act on, in the line whose job is to say what
    // the source does. It says nothing there instead.
    val role =
        sourceWithState.source.primaryRole
            .takeIf { it.isNotBlank() && !it.equals("Unknown role", ignoreCase = true) }
    val roleVersionLine =
        if (loadedVersion.isNullOrEmpty() && versionLine != null && !sourceWithState.source.isPlayedOutsideRuntime) {
            versionLine
        } else {
            listOfNotNull(role, versionLine).joinToString(" \u00b7 ")
        }
    // Only for a build that is genuinely behind the registry the user can see: a source with nothing
    // installed has its own first-install path, and an update offered there would be a control that
    // never does anything. A module has no installed build at all, so there is never one behind.
    //
    // "Behind" is the engine's own comparison (`isNewer`), not string inequality. The two disagree
    // exactly where it matters: a registry that rolls back publishes an *older* version than the one
    // this install runs, and the string test offered "Update to 2.3.8" over a working 2.3.10 - a
    // control whose only outcome was a downgrade the runtime would refuse. The same rule refuses to
    // compare a version it cannot read (`latest`), which is why nothing is offered for those.
    val behindRegistry =
        !sourceWithState.source.isPlayedOutsideRuntime &&
            SpotiFLACExtensionUpgrade.isNewer(registryVersion, loadedVersion)

    /**
     * What this source is and where it came from, in one line.
     *
     * The repository is named on the source because a source list is a list of *other people's*
     * entries: a user who added two repositories and finds a source broken needs to know which of
     * them to switch off, and the source id alone cannot say it - ids are chosen by whoever wrote
     * the entry. The role follows, because "Amber" and "Qobuz" and "Silence" are not obviously the
     * same kind of thing, and a list where every row says what it does is a list a user can arrange
     * without opening anything.
     *
     * The repository is written in brackets, on a line of its own, and the role below it - which is
     * a measurement rather than a preference. One line carrying both does not fit: on this device's
     * 360dp width the text column is about 210dp, so `[zarzet/spotiflac-extension] · Streams audio`
     * clipped to `[zarzet/spotiflac-extension] · …` - the role gone - and the same line written the
     * other way round (`From zarzet/spotiflac-extensio… · Metadata`, which is what shipped first)
     * lost the repository instead, leaving the row naming a registry it could not finish saying.
     * The bracket is 28-33 characters on either bundled registry, so it needs its own line to be
     * read at all; the role is 13-15 and never fights it.
     *
     * It arrives already bracketed, and already decided: whether this row has one at all is a
     * question about the source and the repository list together, and it is answered in one place
     * (`sourceProvenanceLine`) rather than here - a row that decided for itself would be a second
     * answer, and the promise is that every row names an origin.
     */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The source's own mark, on the plate every other settings row uses. A list of twelve
        // near-identical vendor names is read one by one; the mark is what makes the source being
        // looked for findable without reading. Sized from SettingsDimensions rather than by hand, so
        // it lines up with the rows above and below it on every density.
        val markColor =
            app.hush.music.spotiflac.SpotiFLACSourceMark.brandArgb(sourceWithState.source.id)
                ?.let { Color(it) }
                ?: MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier =
                Modifier
                    // Pinned to the top of the entry rather than centred in it: these rows are as
                    // tall as their registry description, which runs to seven lines, and a mark
                    // floating in the middle of that puts it under a sentence instead of beside the
                    // source's name - the one line it belongs to. The offset is what centres the
                    // plate on that first line (36dp plate around a 24dp line of text).
                    .align(Alignment.Top)
                    .offset(y = 6.dp)
                    .size(SettingsDimensions.RowIconSize)
                    .clip(CircleShape)
                    .background(markColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter =
                    painterResource(
                        app.hush.music.spotiflac.SpotiFLACSourceMark.iconRes(sourceWithState.source.id),
                    ),
                contentDescription = null,
                tint = markColor,
                modifier = Modifier.size(SettingsDimensions.RowIconInnerSize),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (choiceLabel != null) {
                Text(
                    text = "Choice #$choiceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Two lines at most, which is enough for every name a registry publishes today - the
            // longest is `Tidal FLAC (tracks.monochrome.st)` at two - and the cap is what keeps a
            // name that grows later from wrapping to three and pushing the row's facts out of sight.
            //
            // The registry's own description is not drawn at all. Every provider writes a paragraph
            // about its own capabilities, and the row already answers the two questions a reader has
            // - what this is (role and version) and where it came from (repository and check) - in
            // facts they can act on. A description cut off mid-sentence at two lines adds height and
            // nothing else.
            Text(
                text = rowName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
            if (repositoryLine != null) {
                Text(
                    text = repositoryLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (roleVersionLine.isNotEmpty()) {
                Text(
                    text = roleVersionLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // What the walk gives this source, in the order it uses it: how long it may stay silent
            // before the first byte (connect), how long a transfer may go without data before it is
            // abandoned (download), and how many times it is asked before the next source is tried.
            // The one line a user needs to answer "why was that source slow", which used to require
            // reading a log. Two lines rather than one: measured on a 360dp screen the single line
            // ended at "60s download · 3...", which is the retry count cut off - the one number the
            // line exists to state - and this row already stacks short facts.
            Text(
                text = fetchPolicy.summary(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // A source Hush cannot play says so on its own row, and says why. It is in the registry,
            // so hiding it would leave the user hunting for a source they read about somewhere worse;
            // what it cannot be is counted in the order or switched on as if it worked, which is
            // what `isPlayable` and the choice label above already refuse to do.
            if (!sourceWithState.source.isPlayable) {
                // The whole reason, over two lines and with no ellipsis. This is the row's answer to
                // the only question a dead switch raises - "why can I not turn this one on?" - and
                // at one line it was clipped to `JavaScript module - this build …`, which reads as
                // a control that is broken rather than as a source this build cannot play. The
                // predicate is the switch's own (`isPlayable`), so the note cannot disagree with
                // the switch beside it.
                Text(
                    text =
                        if (sourceWithState.source.needsModuleRuntime) {
                            "No script to run"
                        } else {
                            "No package to play"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else if (sourceWithState.source.isServerAddon) {
                // A server addon answers with a URL and runs on its own service, so it has the same
                // two facts a module row states: nothing to verify, and a switch that works.
                Text(
                    text = "Server addon \u00b7 no session",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else if (sourceWithState.source.isHostedModule) {
                // A module that *can* run says so, because what it is decides how it behaves: it is
                // run by Hush in its own sandbox rather than by the SpotiFLAC engine, it needs no
                // session and can therefore never be verified, and it is asked only after the
                // engine's own sources have failed. Without this line the row is a source whose
                // switch works for no stated reason, and its absence from every "N verified" count
                // reads as a source that is being passed over.
                Text(
                    text = "Module \u00b7 no session",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else if (
                sourceWithState.source.isRuntimeCompatible &&
                !sourceWithState.source.hasVerifiedPackageDigest
            ) {
                // A package its registry published without a checksum is installed anyway - refusing
                // it made the source look like every other one and fail every time (see
                // `isRuntimeCompatible`) - and the difference is said here, because the install is
                // the one moment it matters and nothing in the file that lands records it. The
                // predicate is the verifier's own: the digest this build would enforce.
                Text(
                    text = "No checksum published",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Where the session stands, on the row it belongs to: this used to be a second list of
            // the same sources above, and the two could disagree about the same source at the same
            // moment. A source with no session to keep says nothing here rather than claiming one.
            sessionLine?.let { line ->
                val color =
                    when (line.tone) {
                        SpotiFLACVerificationChecklist.Tone.SOLVING,
                        SpotiFLACVerificationChecklist.Tone.DONE,
                        -> MaterialTheme.colorScheme.primary
                        SpotiFLACVerificationChecklist.Tone.ATTENTION -> MaterialTheme.colorScheme.error
                        SpotiFLACVerificationChecklist.Tone.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
                        null ->
                            if (line.healthy) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                    }
                // The sentence and its countdown are two texts on one line rather than one
                // sentence with the number inside it. Two things went wrong with the single
                // string: the row ellipsizes from the right, so the part that got cut was the
                // countdown the line exists to report (`Verified — renews automatically (2h 1…`),
                // and the number itself is redrawn every tick, so in a proportional font the whole
                // label shifted sideways whenever a digit changed width. Drawn separately, the
                // number survives the clip and its digits keep their places (an equal-width font
                // and no wrapping), and the sentence next to it stays put.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    line.remaining?.let { remaining ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = remaining,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            // The update lives under the version it would install, rather than in a section-wide
            // "Check for updates" that swept every source at once. Three things follow from that:
            // the action is where the user is already reading the gap between Installed and
            // Registry, it names the build it will take, and pressing it cannot act on a source
            // other than the one it sits under - which is what the row-level wording promised and
            // what the per-version memo used to make ambiguous for the source that was not tapped.
            if (behindRegistry) {
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    onClick = onUpdate,
                    enabled = !isUpdating,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    color =
                        if (isUpdating) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = if (isUpdating) "Updating…" else "Update to $registryVersion",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            // When this build was last looked at. A version number alone cannot tell "checked a
            // minute ago" from "checked before the last three app starts".
            val checkLine = app.hush.music.spotiflac.SpotiFLACExtensionCheckReport.line(
                // A module has no package to check and no check to have been made, so the line would
                // only ever say "Not checked yet" - a statement about a check that is not part of
                // how a module is served.
                check = check.takeIf { !sourceWithState.source.isPlayedOutsideRuntime },
                registry = app.hush.music.spotiflac.SpotiFLACExtensionCheckReport
                    .registryLabel(sourceWithState.source.repositoryId),
                // Which repository this source came from is the provenance line's job, three lines up,
                // and it is the copy that can be acted on: it follows the merge the repository rows
                // control, where this line's origin is the registry the package was last *checked*
                // against. Both named it, so one row printed two names - and after a repository was
                // switched off, two different ones (measured on the device: `From
                // spotiflacapp/spotiflac-ext…` above `Checked 8 min ago · from zarzet`), which
                // leaves a user unable to tell which repository the row means, and that is the one
                // question the name is there to answer.
                //
                // Never here, then, and the row above always: the provenance line is drawn for every
                // source now, and the only rows without one are the modules whose own name already
                // carries the index it came from.
                namesRepository = false,
                nowMs = System.currentTimeMillis(),
                // Only when it has something to add. `Checked 55 min ago` under every source said
                // the same thing twelve times and pushed the facts that differ apart; a check that
                // failed, that replaced the package, or that is old enough to be worth re-running is
                // what a reader needs. A current check from the last few hours is implied by the
                // version line above it - the row would only be repeating itself.
            )?.takeIf { line ->
                // A module and an addon are never checked - there is no package to compare with a
                // registry - so `Not checked yet` under them would describe a step that never
                // happens. The row says `Module · no session` instead, which is the whole truth.
                // The same goes for a source with no version on either side (the built-in Pandora
                // entry): there is no package to compare, so there is no check to have happened.
                !sourceWithState.source.isPlayedOutsideRuntime &&
                    (registryVersion.isNotEmpty() || !loadedVersion.isNullOrEmpty()) &&
                    line.isNotBlank() &&
                    (
                        check?.outcome != app.hush.music.spotiflac.SpotiFLACExtensionCheck.Outcome.CURRENT ||
                            check.checkedAtMs < System.currentTimeMillis() - CHECK_LINE_STALE_MS
                    )
            }
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
        // Off the table for a source this build cannot play: the row is drawn so the user can see
        // what the repository publishes and switch the repository off if they do not want it, and a
        // switch that could be turned on for a source that can never resolve is a switch that lies.
        Switch(
            checked = sourceWithState.enabled && sourceWithState.source.isPlayable,
            onCheckedChange = onToggleEnabled,
            enabled = sourceWithState.source.isPlayable,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )

        var rowMenuOpen by remember(sourceWithState.source.id) { mutableStateOf(false) }
        // One `Box` holds the button and its menu: a `DropdownMenu` is anchored to its parent layout
        // node, so as siblings of this full-width row they would anchor to the row and open the menu
        // against the far edge of the screen instead of under the button.
        androidx.compose.foundation.layout.Box {
            androidx.compose.material3.IconButton(onClick = { rowMenuOpen = true }) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "More options for $rowName",
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = rowMenuOpen,
                onDismissRequest = { rowMenuOpen = false },
            ) {
                // The single-source check, offered only where a check applies - a source with no
                // gateway session has nothing to verify, and a menu item that raised a challenge for
                // it would be a control that lies about what it does. Never while a run is in flight
                // either, because the run's own Cancel is the control then (see `SourceSessionLine`).
                if (sessionLine?.checkable == true) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Verify this source") },
                        onClick = {
                            rowMenuOpen = false
                            onVerify()
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
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Fetch timeouts & retries") },
                    onClick = {
                        rowMenuOpen = false
                        onEditFetchPolicy()
                    },
                )
            }
        }
    }
}

/**
 * The two windows and the retry count for one source.
 *
 * Deliberately about one source: a slow provider and a fast one are different problems on the same
 * network - one needs a longer connect window, the other finishes a file in seconds - and a
 * screen-wide editor would either apply one number to all of them or force the user to pick which
 * source each field meant. The row that opened it says which source it is, and the walk reads back
 * exactly what is saved here (see [SpotiFLACFetchPolicies]).
 *
 * The fields are raw seconds and a count rather than sliders: a slider for 2..120 and another for
 * 10..600 gives no way to type a number, and the range is wide enough that the ends are what a user
 * arrives with ("give it two minutes"). A value outside the range is clamped on save rather than
 * refused, so a typo cannot leave a source unfetchable; the valid range is stated under each field.
 */
@Composable
private fun FetchPolicyDialog(
    sourceName: String,
    initial: SpotiFLACFetchPolicy,
    onSave: (SpotiFLACFetchPolicy) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val normalized = remember(initial) { initial.normalized() }
    var connect by remember(normalized) { mutableStateOf(normalized.connectTimeoutSeconds.toString()) }
    var download by remember(normalized) { mutableStateOf(normalized.downloadTimeoutSeconds.toString()) }
    var attempts by remember(normalized) { mutableStateOf(normalized.maxAttempts.toString()) }

    fun parsed(value: String, fallback: Int): Int = value.trim().toIntOrNull() ?: fallback
    val parsedConnect = parsed(connect, normalized.connectTimeoutSeconds)
    val parsedDownload = parsed(download, normalized.downloadTimeoutSeconds)
    val parsedAttempts = parsed(attempts, normalized.maxAttempts)
    val edited =
        SpotiFLACFetchPolicy(
            connectTimeoutSeconds = parsedConnect,
            downloadTimeoutSeconds = parsedDownload,
            maxAttempts = parsedAttempts,
        ).normalized()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fetch settings for $sourceName") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "How long this source is given, and how many times it is asked, before " +
                        "the walk moves on. A source that did not answer inside its connect window is " +
                        "asked again up to the retry count, then the next source in the list is tried.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = connect,
                    onValueChange = { connect = it.filter(Char::isDigit).take(3) },
                    label = { Text("Connect timeout (seconds)") },
                    supportingText = {
                        Text(
                            "${SpotiFLACFetchPolicy.MIN_CONNECT_SECONDS} to " +
                                "${SpotiFLACFetchPolicy.MAX_CONNECT_SECONDS}. Time to the first byte.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = download,
                    onValueChange = { download = it.filter(Char::isDigit).take(3) },
                    label = { Text("Download timeout (seconds)") },
                    supportingText = {
                        Text(
                            "${SpotiFLACFetchPolicy.MIN_DOWNLOAD_SECONDS} to " +
                                "${SpotiFLACFetchPolicy.MAX_DOWNLOAD_SECONDS}. How long a download may " +
                                "go without data before it is given up on.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = attempts,
                    onValueChange = { attempts = it.filter(Char::isDigit).take(1) },
                    label = { Text("Attempts per source") },
                    supportingText = {
                        Text(
                            "${SpotiFLACFetchPolicy.MIN_ATTEMPTS} to " +
                                "${SpotiFLACFetchPolicy.MAX_ATTEMPTS}. Only a failed attempt is " +
                                "repeated; a source that answered \"not found\" is not asked again.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(edited) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onReset) {
                    Text("Defaults")
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
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
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
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

/**
 * When the next automatic renewal falls due, counted down from the session read the screen holds.
 *
 * The other half of what [EngineStatusLine] says, and it reads next to it for that reason: the status
 * answers "can the engine fetch right now", and this answers "when are the sessions behind it topped
 * up" - the same two things the launch notice reports, but readable whenever the user asks rather
 * than only at a launch where something is already wrong. Silent when there is nothing to renew,
 * because the honest answer there is nothing rather than "never".
 *
 * The clock lives in here, not in the screen. Derived in the screen's body it would move the whole
 * screen - a lazy list of source rows - on every tick; here the same tick invalidates this one line.
 * It is a clock and not a re-read on purpose: the countdown is linear, and re-reading sessions means
 * touching the extensions directory and the gateway's block record for an answer that has not moved.
 *
 * Nothing to count down is the common case, and the only one a fresh install has: no session record
 * has been made yet, the label is null, and the line is not drawn. The ticker used to start anyway,
 * waking once a second and recomposing this row for a line that was never on screen - an install that
 * shows this section from its first launch (the third-party sources are on by default) paid it for
 * nothing. It is now started by the case that draws something: a label that cannot age into one is
 * not drawn at all, and subtracting the elapsed seconds can only move a due moment closer.
 */
@Composable
private fun SessionRenewalLine(
    remainingSeconds: List<Long?>,
    snapshotElapsedMs: Long,
) {
    val anythingToCountDown =
        remember(remainingSeconds) {
            SpotiFLACSessionRenewer.nextRenewalLabel(remainingSeconds) != null
        }
    if (!anythingToCountDown) return
    var tickElapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(RENEWAL_COUNTDOWN_TICK_MS)
            tickElapsedMs = SystemClock.elapsedRealtime()
        }
    }
    val label =
        remember(remainingSeconds, snapshotElapsedMs, tickElapsedMs) {
            // Before the first tick, and before the screen has read any session at all, nothing has
            // aged: the snapshot is the answer as it was taken. Only a tick that lands *after* the
            // read can date it, which is what the subtraction is guarded for.
            val elapsedSeconds =
                if (snapshotElapsedMs <= 0L) {
                    0L
                } else {
                    ((tickElapsedMs - snapshotElapsedMs).coerceAtLeast(0L)) / 1000L
                }
            SpotiFLACSessionRenewer.nextRenewalLabel(
                remainingSeconds.map { remaining -> remaining?.minus(elapsedSeconds) },
            )
        } ?: return
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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