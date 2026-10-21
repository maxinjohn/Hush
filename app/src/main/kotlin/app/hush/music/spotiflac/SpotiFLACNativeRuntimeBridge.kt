package app.hush.music.spotiflac

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import app.hush.music.constants.ParallelSourceFetchKey
import app.hush.music.constants.SpotiFLACEmbedReplayGainKey
import app.hush.music.constants.SpotiFLACFetchPoliciesKey
import app.hush.music.constants.SpotiFLACTryNextSourceKey
import app.hush.music.constants.SpotiFLACVerifiedOnlyKey
import app.hush.music.utils.PlaybackDownloadProgress
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.utils.PreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge to the upstream SpotiFLAC Go runtime (gobackend.aar, built from
 * SpotiFLAC-Mobile's go_backend via gomobile).
 *
 * The runtime executes the signed extension packages (Goja VM) with the exact
 * contracts upstream uses:
 *  - `InitExtensionSystem(extensionsDir, dataDir)` bootstraps the engine.
 *  - `SetRuntimeState(...)` seeds install IDs so runtime-created signed
 *    sessions belong to the same gateway install identity Hush uses.
 *  - `LoadExtensionFromPath(<verified .sflx>)` loads registry packages.
 *  - `SetExtensionEnabledByID(id, true)` + `SetProviderPriorityJSON(...)`
 *    reproduce the upstream provider ordering.
 *  - `DownloadByStrategy(requestJson)` runs the signed-session preflight and
 *    the extension download pipeline, returning a local audio file that Media3
 *    plays directly. A `verification_required` result means the user must
 *    re-authenticate in SpotiFLAC settings.
 *
 * Reflection is intentional: it keeps Hush compiling in checkouts that do not
 * yet bundle the AAR, while bundled builds activate the real runtime.
 */
@Singleton
class SpotiFLACNativeRuntimeBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageStore: SpotiFLACExtensionPackageStore,
    private val repositoryManager: ExtensionRepositoryManager,
    private val playbackCache: SpotiFLACPlaybackCache,
    private val checkStore: SpotiFLACExtensionCheckStore,
    private val moduleHost: SpotiFLACModuleHost,
    private val serverAddonHost: ServerAddonHost,
) {
    companion object {
        private const val TAG = "SpotiFLACNative"
        private const val GOBACKEND_CLASS = "gobackend.Gobackend"

        /** Longest reason a failed source test carries into the row that shows it. */
        private const val MAX_TEST_REASON = 160

        /** The statuses an extension's health payload reports when a source is usable. */
        private val HEALTHY_STATUSES = setOf("online", "ok", "healthy", "pass", "available")

        /** What the runtime calls a version it has already loaded, i.e. not a failure. */
        private const val ALREADY_INSTALLED = "already installed"

        /** How much of an unrecognised load report survives into the diagnostic log. */
        private const val MAX_LOAD_REPORT = 200

        /** Hush's media app version reported to the gateway and extension gates. */
        private const val RUNTIME_APP_VERSION = SpotiFLACInstallIdentity.APP_VERSION

        /**
         * Rows a module is asked for when it is the last candidate in a sweep.
         *
         * A module's rows are its own service's answer and are matched here afterwards, so asking
         * for more than the queue needs only widens the pool the matcher chooses from - and each row
         * of the Archive's answer costs a metadata read inside the module, which is why this is not
         * the module's own maximum.
         */
        private const val MODULE_SEARCH_LIMIT = 20

        /** The quality bucket a module's own rows are in, and the one claim they all make. */
        private const val LOSSLESS_BUCKET = "lossless"
        private const val LOSSLESS_QUALITY = "LOSSLESS"

        /** A module's stream is a FLAC file: the modules themselves probe for the signature. */
        private const val MODULE_CODEC = "flac"
    }

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val lock = Any()

    /** Live progress of the download currently feeding the player, if any. */
    private val _downloadProgress = MutableStateFlow<PlaybackDownloadProgress?>(null)
    val downloadProgress: StateFlow<PlaybackDownloadProgress?> = _downloadProgress.asStateFlow()

    /**
     * Publishes a synthetic transfer sample, so a shell can put the player's codec row into a state
     * that otherwise needs a live provider.
     *
     * Every surface that describes a fetch reads this flow through the service's mirror, so the
     * resolving and downloading states of the row - the two that were reported as a stale size and
     * source from a copy that was being replaced - could only be checked by finding an uncached
     * track and hoping the download was slow enough to photograph. A debug build can say what it is
     * asked to instead, and the rows are then read on a screen showing exactly the sample they were
     * given. Debug-only, like the receiver that calls it.
     */
    fun debugPublishDownloadProgress(progress: PlaybackDownloadProgress?) {
        if (!app.hush.music.BuildConfig.DEBUG) return
        _downloadProgress.value = progress
    }

    /**
     * One lock per cached track. A prefetch and the playback resolve can ask for the
     * same track at the same moment; without this they would both miss the index and
     * download the file twice.
     */
    private val resolveLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Sources abandoned by the stall watchdog, by when they stalled.
     *
     * Only demoted, never removed: a wedged provider is still tried, just after the
     * ones that are answering, and only for [SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS].
     */
    private val recentlyStalledSources = ConcurrentHashMap<String, Long>()

    /**
     * Sources that answered with a refusal to serve, by when they refused.
     *
     * Kept apart from [recentlyStalledSources] because the two are different statements and want
     * different responses: a stall is silence and is demoted, a refusal is the provider saying no
     * to this client and is left out of the chain until the cooldown passes. In memory only - a
     * restart is as good a moment as any to find out whether the limit has lapsed.
     */
    private val rateLimitedSources = ConcurrentHashMap<String, Long>()

    /**
     * Sources that reported *themselves* unavailable, by when they said so.
     *
     * A third statement, distinct from the two above: a stall is silence about one track and a
     * refusal is about this client, while this is the provider's own health check saying the service
     * behind it cannot be reached at all. Measured on the reporting device while Deezer was down on
     * the gateway's side (`services.deezer.ok=false, error="fetch failed"`), every sweep still spent
     * its slot on it and reported `Invalid Deezer track ID` - a message that reads as a broken app.
     * Demoted, never dropped: a sweep whose only sources are unreachable still asks them.
     *
     * Persisted with [recentlyStalledSources] and for the same reason: the statement is about the
     * provider, not about this process, so a restart inside the cooldown must not send the first
     * sweep back to a provider already known to be down - which is precisely the cold-start delay a
     * listener notices. The reason is held too, because in memory this is also the only place that
     * knows *why* a source is sitting at the back, and that is what the Audio Sources row reads.
     */
    private val unavailableSources = ConcurrentHashMap<String, Unavailable>()

    /**
     * A source's own verdict about itself: when it said so, what it said, and how the walk treats it.
     *
     * [skipped] is the half a module or addon has and a runtime provider does not. A provider's
     * verdict moves it behind the others; an addon's refusal takes it out of the walk entirely,
     * because the refusal arrives before any track is named (see
     * [SpotiFLACProviderStallPolicy.availableAddons]). Kept on the record rather than looked up, so
     * the row can say which of the two the user is reading even before a sweep has run.
     */
    private data class Unavailable(
        val atMs: Long,
        val reason: String,
        val skipped: Boolean = false,
    )

    /** The verdicts as timestamps only, which is the shape the ordering policy takes. */
    private val unavailableAtMsBySource: Map<String, Long>
        get() = unavailableSources.mapValues { (_, entry) -> entry.atMs }

    /**
     * Sources a recovery watch is already running for, so one refusal schedules one watch.
     *
     * A source that refuses again while its watch is still going is the same statement, and a second
     * watch would double the probes without learning anything sooner.
     */
    private val recoveringSources: ConcurrentHashMap.KeySetView<String, Boolean> =
        ConcurrentHashMap.newKeySet()

    /**
     * Carries the recovery watches.
     *
     * Process-wide rather than a caller's, because the refusal that starts one is recorded deep in a
     * sweep - whose coroutine is gone the moment the track gives up - while the answer being waited
     * for arrives minutes later, with nothing on screen to hold it.
     */
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * When each source's health was last asked, so the answer is not re-paid per track.
     *
     * The check is a runtime call (measured ~1.8s), so it is only worth making where it changes a
     * decision: right after that source failed a sweep, and at most once per
     * [SpotiFLACProviderStallPolicy.UNAVAILABLE_COOLDOWN_MS] per source.
     */
    private val healthCheckedAt = ConcurrentHashMap<String, Long>()

    /**
     * `id@registryVersion` for every extension package already reconciled in this process.
     *
     * Keyed by the registry's own version, so a registry that starts publishing a different
     * build is noticed on the next resolve instead of waiting for a restart, while the ordinary
     * case - the same package, asked about once per track - costs nothing after the first answer.
     */
    private val reconciledPackages = ConcurrentHashMap.newKeySet<String>()

    /** One lock per source, so two resolves cannot refresh the same package at the same time. */
    private val packageRefreshLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Whether the persisted demotions have been read into [recentlyStalledSources].
     *
     * Persisted because the demotion is a statement about a provider, not about this
     * process: a provider that wedged before a restart will wedge again on the first
     * track afterwards, and the sweep would pay its watchdog budget before reaching a
     * source that answers. That is exactly the cold-start delay a listener notices.
     */
    @Volatile private var stallsLoaded = false

    /**
     * What each provider has been observed to need before it starts moving bytes, so the
     * resolution window fits this network instead of a constant. See [ProviderTimings].
     */
    private val providerTimings = ProviderTimings()

    @Volatile private var timingsLoaded = false

    @Volatile private var initialized = false
    @Volatile private var unavailableReason: String? = null
    private var backendClass: Class<*>? = null

    init {
        SpotiFLACNativeRuntimeBridgeHolder.instance = this
        SpotiFLACNativeRuntimeBridgeHolder.repositoryManager = repositoryManager
    }

    /**
     * The extension a pending runtime challenge belongs to, or null when there is none.
     *
     * A pending challenge is raised by one extension's own preflight, and only that extension
     * can exchange the grant it publishes, so every surface that shows a runtime challenge has
     * to deliver the grant back to this id rather than to whichever source the user tapped.
     */
    fun pendingRuntimeAuth(): PendingExtensionAuth? {
        val backend = backendClass ?: return null
        return runCatching {
            val raw = invokeString(backend, "getAllPendingAuthRequestsJSON")
            if (raw.isBlank() || raw == "[]") return null
            val arr = json.parseToJsonElement(raw)
            (arr as? kotlinx.serialization.json.JsonArray)
                ?.firstNotNullOfOrNull { entry ->
                    val obj = entry as? kotlinx.serialization.json.JsonObject ?: return@firstNotNullOfOrNull null
                    val authUrl = obj["auth_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: return@firstNotNullOfOrNull null
                    PendingExtensionAuth(
                        extensionId = obj["extension_id"]?.jsonPrimitive?.content
                            ?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null,
                        authUrl = authUrl,
                        callbackUrl = obj["callback_url"]?.jsonPrimitive?.content,
                    )
                }
        }.getOrNull()
    }

    /**
     * The owner of the runtime challenge for [extensionId], or null when the runtime holds none.
     *
     * Prefers the challenge raised for this extension; the runtime's list is walked in order, so
     * a challenge raised while a *different* provider was being tried is still returned - with
     * its own id, which is the id the grant must go back to.
     */
    suspend fun challengeOwnerFor(extensionId: String): String? =
        withContext(Dispatchers.IO) { pendingAuthFor(extensionId)?.extensionId?.takeIf { it.isNotBlank() } }

    /** The raw manifest of an extracted extension, or null when unavailable. */
    private fun manifestJsonFor(sourceId: String): String? {
        val manifest = File(
            File(context.filesDir, "spotiflac/extensions"),
            "$sourceId/manifest.json",
        )
        if (!manifest.isFile) return null
        return runCatching { manifest.readText() }.getOrNull()
    }

    /**
     * Delivers a Turnstile grant to the extension whose challenge raised it, using the exact
     * upstream handshake: SetExtensionSessionGrantByID → completeGrant action.
     * The runtime exchanges the grant against the gateway itself, so its signed
     * sessions are independent of Hush's but share the same install identity.
     *
     * The delivery target is the challenge's *owner* and nothing else. A grant is bound to the
     * challenge behind it, and a challenge is raised per extension - `/bootstrap` answers each
     * extension's own client signature with its own challenge, and the exchange payload carries
     * that extension's app version. Handing the same grant to any other extension is answered
     * `session exchange failed: HTTP 403`: measured on device, one grant delivered to four
     * sources verified exactly one and was refused three times. Those refusals were not cosmetic
     * - each one marks the source as still needing verification, so the next play raised the
     * same challenge again, which is the loop a car user sees as "I verify and it still says
     * 403". An empty list (the owner could not be determined, e.g. a bare deep-link callback) is
     * the one case where more than one source is tried.
     *
     * @see pendingRuntimeAuth the owner of a pending runtime challenge.
     */
    suspend fun deliverGrant(grant: String, sourceIds: List<String>) = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = grant.trim()
            require(trimmed.isNotEmpty()) { "Grant is empty" }
            val backend = requireBackend()
            initializeRuntime(backend)
            val ids = sourceIds.ifEmpty {
                repositoryManager.sources.value.map { it.source.id }
            }
                .filter { it.isNotBlank() }
                // A grant redeems only in an extension that holds a signed session, and a metadata
                // provider never does. Asking one anyway costs a package install and a gateway call
                // for a source whose only possible answer is "no session", which is exactly the
                // unwanted work this list must not start - so it is excluded by declaration rather
                // than by name. The name check stays for the window before the registry has been
                // read, where there is no declaration to read yet.
                .filter { id ->
                    repositoryManager.getSourceForId(id)?.declaresMetadataOnly != true &&
                        !id.equals("spotify-web", ignoreCase = true)
                }
            for (id in ids) {
                try {
                    // A missing manifest means "unknown", not "not required", so only a
                    // manifest that was readable *and* declares no signed session is skipped -
                    // otherwise an unextracted package, or one this app could not parse, would
                    // silently lose the grant.
                    val manifest = manifestJsonFor(id)
                    if (manifest != null && SpotiFLACSourceAuth.declaresNoSignedSession(manifest)) {
                        continue
                    }
                    ensurePackageLoaded(backend, id, repositoryManager.getSourceForId(id))
                    invokeVoid(backend, "setExtensionSessionGrantByID", id, trimmed)
                    val result = invokeString(backend, "invokeExtensionActionJSON", id, "completeGrant")
                    SpotiFLACDiag.log("completeGrant id=$id result=${result.take(160)}")
                    Timber.tag(TAG).i("completeGrant for %s: %s", id, result.take(200))
                } catch (error: Throwable) {
                    SpotiFLACDiag.log("grant delivery failed id=$id msg=${error.message}")
                    Timber.tag(TAG).w(error, "Grant delivery failed for extension %s", id)
                }
            }
        }.onFailure { Timber.tag(TAG).w(it, "deliverGrant failed") }
    }

    /**
     * Warms the runtime and the enabled extensions off the playback path: loads
     * the engine, downloads/verifies missing extension packages and registers
     * them so the first track does not pay the package-fetch cost mid-playback.
     */
    suspend fun prepareForPlayback(sourceIds: List<String>) = withContext(Dispatchers.IO) {
        runCatching {
            // Warm the playback index and drop orphaned files (older builds leaked
            // one file per resolve) before the first resolve, so cache lookups are
            // O(1) and storage cannot drift.
            runCatching {
                playbackCache.warmUp()
                playbackCache.reconcile()
                playbackCache.evictIfNeeded()
            }.onFailure { SpotiFLACDiag.log("cache prepare failed: ${it.message}") }
            val backend = requireBackend()
            initializeRuntime(backend)
            // Drop records an older build seeded with Hush's own session before
            // anything reads them, so a source whose session can never be accepted
            // reports as needing verification instead of looking ready.
            purgeForeignSeededSessions()
            val ids = sourceIds.ifEmpty {
                repositoryManager.enabledLiveSourceIds()
            }.filter { it.isNotBlank() && !it.equals("spotify-web", ignoreCase = true) }
            for (id in ids) {
                runCatching { ensurePackageLoaded(backend, id, repositoryManager.getSourceForId(id)) }
                    .onFailure { SpotiFLACDiag.log("prewarm skip id=$id msg=${it.message}") }
            }
            SpotiFLACDiag.log("prewarm done ids=${ids.joinToString(",")}")
            // Last, and only once the switched-on sources have been registered: the runtime's own
            // switches are made to agree with the screen's. See [applySourceSwitchesToRuntime].
            applySourceSwitchesToRuntime(backend)
            // Sources that cannot download yet get their challenge solved now, in the
            // background, instead of surfacing as a failed track later - but only while
            // SpotiFLAC is actually in use. With it off every source is unused, and an
            // automatic run is not a harmless no-op: on a device whose WebView cannot run
            // Cloudflare's check (a car head unit) it ends with a browser tab opening by
            // itself, asking the user to solve a challenge for playback that is not routed
            // through SpotiFLAC at all. The preference is read here, at the moment the
            // attempt would start, so a source queued while it was on is never verified after
            // it has been switched off either.
            // Launch-time verification is deliberately not started here any more. Skipping an
            // unverified source at launch is what this used to compensate for, but the compensation
            // cost more than it bought: the run was invisible, so a challenge that could not finish
            // unattended burned its timeout and then raised the "SpotiFLAC needs verification" notice
            // - a dialog asking for a check nobody had asked for, on every app open, for sources the
            // user was not using. Verification now happens where it is a response to something:
            // playback holding a track ("playback"), or the user's own check in Audio Sources. Both
            // store the session the same way; only the launch prompt is gone.
            SpotiFLACDiag.log(
                "prewarm: no launch-time verification - a source is verified when it is needed " +
                    "or when the user asks",
            )
        }.onFailure { SpotiFLACDiag.log("prewarm failed msg=${it.message}") }
    }

    /**
     * Refreshes whichever sessions are near enough to expiry to be renewed, and nothing else.
     *
     * The background worker is the scheduled path, but WorkManager defers work freely under doze
     * and an hourly run can be pushed past a session's expiry - at which point the gateway refuses
     * the refresh, the runtime clears the record, and a user who did nothing wrong is asked to solve
     * a Cloudflare check. A source is only ever renewed while it is still valid, so the fix is to
     * take the chances that actually occur: this runs on the playback path, which is exactly when
     * the app is being used and therefore when a session must not lapse.
     *
     * Cheap when nothing is due: records are small files and a session inside its window is the
     * only thing that costs a request.
     */
    suspend fun renewDueSessions(reason: String) = withContext(Dispatchers.IO) {
        runCatching { SpotiFLACSessionRenewer.renewAll(context, reason = reason) }
            .onSuccess { results ->
                val renewed = results.filter { it.renewed }.map { it.extensionId }
                if (renewed.isNotEmpty()) {
                    SpotiFLACDiag.log(
                        "session renew ($reason): refreshed ${renewed.joinToString(",")}",
                    )
                }
            }
            .onFailure { SpotiFLACDiag.log("session renew ($reason) failed: ${it.message}") }
    }

    /**
     * The installed sources that genuinely need the user, after the app has given itself
     * every chance to satisfy them.
     *
     * A Cloudflare check is the one step that cannot be automated and cannot be undone, so
     * it has to be the last resort rather than the first guess. Two things make a healthy
     * session look absent, and neither is the user's problem: the runtime deletes a record
     * once `expires_at` passes, and a registry update changes the record's *file name*,
     * which hashes the extension's app version. Both are recoverable by the app itself -
     * the vault holds the material, and the gateway will refresh a lapsed session without a
     * challenge - so this restores, then renews, and only reports what is still missing.
     * Without it, every launch raised the same verification dialog for a source whose
     * session the app was already holding.
     */
    private suspend fun sourcesAwaitingVerification(): List<String> {
        val restored = runCatching { SpotiFLACSessionRenewer.restoreFromVault(context) }
            .getOrDefault(emptyList())
        if (restored.isNotEmpty()) {
            SpotiFLACDiag.log(
                "prewarm: sessions restored from the vault for ${restored.joinToString(",")}",
            )
        }
        val blocked = unverifiedDownloadExtensionIds()
        if (blocked.isEmpty()) return blocked
        // A record that merely lapsed is refreshed against the gateway, which needs no
        // challenge. Only a refresh the gateway refuses leaves the source to the user.
        val renewed = runCatching { SpotiFLACSessionRenewer.renewAll(context, reason = "prewarm-blocked") }
            .getOrDefault(emptyList())
            .filter { it.renewed }
            .map { it.extensionId }
        if (renewed.isNotEmpty()) {
            SpotiFLACDiag.log(
                "prewarm: renewed sessions for ${renewed.joinToString(",")} before asking",
            )
        }
        val stillBlocked = unverifiedDownloadExtensionIds()
        if (stillBlocked.isEmpty()) {
            SpotiFLACDiag.log(
                "prewarm: ${blocked.joinToString(",")} no longer need a verification",
            )
        }
        return stillBlocked
    }

    data class ResolvedFile(
        val file: File,
        val sourceId: String,
        val title: String? = null,
        val artist: String? = null,
        val quality: String? = null,
        val codec: String? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        /** True when the file came from the local playback cache, not the network. */
        val fromCache: Boolean = false,
        /** Cache key this file is stored under, when caching is enabled. */
        val trackKey: String? = null,
    )

    /** True when the gobackend AAR is present in this build. */
    val isRuntimeAvailable: Boolean
        get() = try {
            requireBackend(); true
        } catch (_: Throwable) {
            false
        }

    val lastUnavailableReason: String?
        get() = unavailableReason

    /** A verification challenge an extension raised for itself. */
    data class PendingExtensionAuth(
        val extensionId: String,
        val authUrl: String,
        val callbackUrl: String? = null,
    )

    /**
     * What one resolve will walk, in the order it will walk it.
     *
     * [walk] is the sequence the sweep follows and [candidates] is the same list narrowed to the
     * runtime's own extensions - the runtime is never handed a module id, because an id it has never
     * heard of is a candidate slot spent on a source that can only fail. [addons] says which walked
     * ids are answered by a module or a server addon rather than by the runtime.
     */
    private data class ResolvePlan(
        val candidates: List<String>,
        val walk: List<String>,
        val addons: Map<String, ExtensionSource>,
        /**
         * Module/addon sources left out of this walk because they refused service, with the reason.
         *
         * Carried so the sweep's failure can say why a source the user switched on was never asked.
         * An addon that is skipped and one that the user never enabled are the same absence in the
         * aggregate otherwise, and "All SpotiFLAC sources failed ()" was reachable the moment a
         * walk could be emptied this way.
         */
        val skippedAddons: Map<String, String> = emptyMap(),
    )

    /**
     * Orders the sources one resolve will try, and says which of them are modules.
     *
     * The rule is the user's, and it is one rule: **the Audio Sources order is the walk**. The
     * registry list is network-backed and can still be empty right after the service starts (or
     * while a toggle rebuilds the timeline), so installed extension packages on disk are used as a
     * fallback - but only for an install that has never recorded a choice, exactly as before.
     *
     * Nothing here may reorder what the user arranged. A source that needs a Cloudflare check can be
     * *skipped* (while "only use verified sources" is on, and only while a usable source remains), a
     * source that has refused service can be left out until its cooldown ends, and a source with no
     * extension package can be reported - but none of those may move a lower-ranked source ahead of
     * a higher-ranked one. That was the inversion: an unverified first choice was reordered behind a
     * verified fifth one, so reordering the list changed nothing the player could hear.
     */
    private suspend fun resolvePlan(
        sourceIds: List<String>,
        quality: String,
    ): ResolvePlan {
        // What the screen has switched on, read even when the caller named sources itself: the
        // *absence* of a choice is what decides the fallback below, and that cannot be read off a
        // list that is empty for either reason.
        val switches =
            runCatching { repositoryManager.sourceSwitches() }
                .getOrElse { SourceSwitches(enabledInOrder = emptyList(), known = false) }
        // A caller that names a strict subset of the switched-on native sources is asking for those
        // alone - a probe, a verification run. A caller that names exactly the switched-on set (which
        // is what playback, downloads and prefetch do) is asking for "whatever is on", and that list
        // is the whole Audio Sources order, modules included.
        val nativeEnabled = runCatching { repositoryManager.getEnabledSourceIds() }.getOrDefault(emptyList())
        val narrowed = sourceIds.isNotEmpty() && sourceIds.toSet() != nativeEnabled.toSet()
        val sweep =
            SpotiFLACCandidateOrder.sweep(
                requested = if (narrowed) sourceIds else emptyList(),
                liveEnabled = repositoryManager.enabledLiveSourceIds(),
                switches = switches,
                savedOrder = repositoryManager.savedSourceOrder(),
                installed = installedDownloadExtensionIds(),
                // Excluded by declaration rather than by name: this is a *download* sweep, so a source
                // whose manifest declares no download provider cannot be a candidate however it is
                // listed. Naming `spotify-web` here left Apple Music in the sweep (reported as an
                // "extension package unavailable" while its package was installed and working) and
                // would have needed the same special case added again for the next metadata-only
                // extension. See SpotiFLACExtensionRole.
                cannotDownload = ::cannotSweepNatively,
            )
        // Every decision this rule makes is said out loud, because a sweep that stays off a source
        // and one that never considered it look identical from the player.
        if (sweep.fromInstalledFallback) {
            SpotiFLACDiag.log(
                "candidates from installed packages (this install has never switched a source on or " +
                    "off): " + sweep.candidates.joinToString(","),
            )
        } else if (sweep.chosen.isNotEmpty()) {
            SpotiFLACDiag.log(
                "candidates from the Audio Sources switches: ${sweep.chosen.joinToString(",")}",
            )
        }
        if (sweep.candidates.isEmpty() && sweep.chosen.isNotEmpty()) {
            SpotiFLACDiag.log(
                "no native source the switches name can download (chosen=${sweep.chosen.joinToString(",")}); " +
                    "every switch is still walked in its own place",
            )
        }
        if (sweep.candidates.isEmpty() && sweep.notEnabled.isNotEmpty()) {
            SpotiFLACDiag.log(
                "no native source is switched on; ${sweep.notEnabled.size} installed package(s) left alone: " +
                    sweep.notEnabled.joinToString(","),
            )
        }
        if (sweep.unavailable.isNotEmpty() && sweep.candidates.size > sweep.unavailable.size) {
            SpotiFLACDiag.log(
                "candidates demoted (extension package unavailable): ${sweep.unavailable.joinToString(",")}",
            )
        }
        if (sweep.notEnabled.isNotEmpty()) {
            SpotiFLACDiag.log(
                "candidates skipped (not enabled in Audio Sources): ${sweep.notEnabled.joinToString(",")}",
            )
        }
        val base = sweep.candidates
        // "Only use verified sources" is an exclusion and never a reorder: it names the sources that
        // would only burn a timeout, and the order of what is left is still the user's. It is also
        // only applied while a usable source remains - a list holding nothing but unverified sources
        // is still swept, because asking is how one of them gets verified.
        val usable = base.filter { sourceAuthState(it).isUsable }
        val verifiedOnly = PreferenceStore.get(SpotiFLACVerifiedOnlyKey) ?: true
        val natives =
            if (verifiedOnly && usable.isNotEmpty() && usable.size < base.size) {
                SpotiFLACDiag.log(
                    "candidates verified-only: ${usable.joinToString(",")} " +
                        "(skipped: ${base.filterNot { sourceAuthState(it).isUsable }.joinToString(",")})",
                )
                usable
            } else {
                base
            }
        // Loaded once per process: without it every restart re-learns the same wedge by paying its
        // watchdog timeout before the first usable source is even reached. These maps are read, not
        // applied as reorderings - a source that is refusing or down is left out for its cooldown,
        // and everything else keeps the place the user gave it. The safety valve is the same one the
        // policy has always had: an empty list falls back to the full order, because asking a hurting
        // provider beats not playing at all.
        ensureStallsLoaded()
        val orderingNow = System.currentTimeMillis()
        val cooling = SpotiFLACProviderStallPolicy.coolingDown(
            candidates = natives,
            rateLimitedAtMs = rateLimitedSources,
            nowMs = orderingNow,
        )
        val reportedDown = SpotiFLACProviderStallPolicy.unavailableNow(
            natives,
            unavailableAtMsBySource,
            orderingNow,
        )
        if (reportedDown.isNotEmpty()) {
            SpotiFLACDiag.log(
                "providers left out while their own health says unavailable: " +
                    reportedDown.joinToString(","),
            )
        }
        if (cooling.isNotEmpty() && reportedDown.isEmpty() && cooling.size == natives.size) {
            // Said out loud because silence here looks like a source the user switched off.
            SpotiFLACDiag.log(
                "provider refusal ignored for now: every candidate is cooling down " +
                    "(${cooling.joinToString(",")}) - asking anyway",
            )
        } else if (cooling.isNotEmpty()) {
            SpotiFLACDiag.log(
                "providers left out while they cool down: ${cooling.joinToString(",")} " +
                    "of ${natives.joinToString(",")}",
            )
        }
        // A source that went quiet recently keeps its place - the record is said here, and only
        // said: the walk is the user's order, so being slow once cannot demote a source in it.
        val recentlyStalled =
            SpotiFLACProviderStallPolicy.recentlyStalled(natives, recentlyStalledSources, orderingNow)
        if (recentlyStalled.isNotEmpty()) {
            SpotiFLACDiag.log(
                "providers that stalled recently keep their place in the walk: " +
                    recentlyStalled.joinToString(","),
            )
        }
        val candidates =
            SpotiFLACProviderStallPolicy.orderAvailable(
                candidates = natives,
                rateLimitedAtMs = rateLimitedSources,
                nowMs = orderingNow,
                unavailableAtMs = unavailableAtMsBySource,
            )
        // Modules and server addons are candidates at their own position in the list, not a second
        // phase after it. Only a lossless request can be answered: their rows declare LOSSLESS, and a
        // lossless file recorded under a hi-res key is one the cache refuses to serve back.
        val addonsEnabled =
            if (SpotiFLACPlaybackCache.qualityBucket(quality) == LOSSLESS_BUCKET) {
                runCatching { repositoryManager.getEnabledAddonSources() }
                    .getOrDefault(emptyList())
                    .filter { source -> !narrowed || sourceIds.any { it.equals(source.id, ignoreCase = true) } }
            } else {
                emptyList()
            }
        // An addon that has just refused is left out of this walk, not moved down it: its refusal
        // named no track, so the next track would pay the same round trip for the same answer (see
        // [SpotiFLACProviderStallPolicy.availableAddons]). Even when it empties the walk - which is
        // the honest outcome of a service that is down - the sweep fails at once instead of spending
        // a round trip per song on the same refusal.
        val addonsById = addonsEnabled.associateBy { it.id }
        val liveAddonIds =
            SpotiFLACProviderStallPolicy.availableAddons(
                addons = addonsEnabled.map { it.id },
                unavailableAtMs = unavailableAtMsBySource,
                nowMs = orderingNow,
            )
        val addons = liveAddonIds.mapNotNull { addonsById[it] }
        val addonsLeftOut = addonsEnabled.filterNot { source -> source.id in liveAddonIds }
        val skippedAddons = addonsLeftOut.associate { source -> source.id to (unavailableSources[source.id]?.reason ?: "refused") }
        if (addonsLeftOut.isNotEmpty()) {
            SpotiFLACDiag.log(
                "module/addon sources left out while they cool down: " +
                    skippedAddons.entries.joinToString(", ") { (id, reason) -> "$id ($reason)" },
            )
        }
        val walk =
            SpotiFLACCandidateOrder.walk(
                chosen = sweep.chosen,
                native = candidates,
                addons = addons.map { it.id },
            )
        if (addons.isNotEmpty()) {
            SpotiFLACDiag.log(
                "module/addon sources in the walk: " +
                    addons.filter { source -> walk.any { it.equals(source.id, ignoreCase = true) } }
                        .joinToString(",") { it.id },
            )
        }
        return ResolvePlan(
            candidates = candidates,
            walk = walk,
            addons = addons.associateBy { it.id.trim().lowercase(java.util.Locale.US) },
            skippedAddons = skippedAddons,
        )
    }

    /** Compares two candidates by verification state, for UI ordering. */
    fun isSourceVerified(sourceId: String): Boolean =
        sourceAuthState(sourceId) == SpotiFLACSourceAuthState.VERIFIED

    /**
     * What this source actually needs before it can download: a valid signed
     * session, a fresh challenge, or nothing at all.
     */
    fun sourceAuthState(sourceId: String): SpotiFLACSourceAuthState {
        val manifestFile = File(
            File(context.filesDir, "spotiflac/extensions"),
            "$sourceId/manifest.json",
        )
        if (!manifestFile.isFile) return SpotiFLACSourceAuthState.NOT_REQUIRED
        val manifest = runCatching { manifestFile.readText() }.getOrNull()
        val record = signedSessionFileFor(sourceId)?.let { file ->
            runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        }
        return SpotiFLACSourceAuth.state(manifest, record, System.currentTimeMillis())
    }

    /** The signed-session file the runtime uses for an extension, if derivable. */
    private fun signedSessionFileFor(sourceId: String): File? {
        val manifestFile = File(
            File(context.filesDir, "spotiflac/extensions"),
            "$sourceId/manifest.json",
        )
        if (!manifestFile.isFile) return null
        val signed = runCatching {
            json.parseToJsonElement(manifestFile.readText()).jsonObject["signedSession"]?.jsonObject
        }.getOrNull() ?: return null
        val namespace = sanitizeSessionNamespace(
            signed["namespace"]?.jsonPrimitive?.content.orEmpty(),
        )
        val baseUrl = signed["baseUrl"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (namespace.isEmpty() || baseUrl.isEmpty()) return null
        val appVersion = signed["appVersion"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "ext-1.0"
        val platform = signed["platform"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "extension"
        val scope = listOf(namespace, baseUrl.lowercase(), appVersion.lowercase(), platform.lowercase())
            .joinToString("\n")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        // The runtime stores records beside the per-extension data dirs, i.e.
        // filepath.Dir(extension_data/<id>) -> extension_data/signed_sessions.
        val dir = File(File(context.filesDir, "spotiflac/extension_data"), "signed_sessions")
        return File(dir, "$namespace-$hash.json")
    }

    /** True when the runtime holds a usable (unexpired) signed session. */
    private fun hasSignedSession(sourceId: String): Boolean =
        sourceAuthState(sourceId) == SpotiFLACSourceAuthState.VERIFIED

    /**
     * Every installed download extension that cannot download right now because
     * its signed session is missing or expired.
     */
    private fun unverifiedDownloadExtensionIds(): List<String> {
        // A source the user switched off in Audio Sources must not raise a verification
        // prompt: nothing in the sweep will use it, so the check is not blocking anything
        // they can hear. Read from the switches themselves rather than from the native candidate
        // order, which is empty for an install whose Audio Sources list holds only a module or a
        // server addon - and an empty list used to read as "nothing is known yet", so every
        // installed package that needed a check was let through. Null is the only answer that
        // means "no choice on record" (see [SpotiFLACVerificationScope]).
        val switchedOn = runCatching { repositoryManager.enabledExtensionIdsSnapshot() }.getOrNull()
        return SpotiFLACVerificationScope
            .targetable(installedDownloadExtensionIds(), switchedOn)
            .filter { sourceAuthState(it) == SpotiFLACSourceAuthState.NEEDS_VERIFICATION }
    }

    /**
     * Download sources that cannot be used until they are verified.
     *
     * Exposed so playback can ask for a verification when it has to park a track,
     * rather than waiting for the next prewarm. This is the raw reading; prefer
     * [sourcesNeedingUserVerification] where the answer decides whether to interrupt the
     * user, because a lapsed record shows up here as unverified even when the gateway
     * would renew it without any challenge.
     */
    fun unverifiedDownloadSourceIds(): List<String> = unverifiedDownloadExtensionIds()

    /**
     * The sources a user actually has to act on, suspension-safe for playback.
     *
     * A source that answered `verification_required` is not proof that the user has
     * anything to do: the runtime refuses a record whose `expires_at` has passed even
     * though the same record can be refreshed against the gateway without any Cloudflare
     * check. So the same order as the app-open path applies - restore what the app already
     * holds, renew what the gateway will renew - and only the remainder is reported.
     */
    suspend fun sourcesNeedingUserVerification(): List<String> =
        withContext(Dispatchers.IO) { sourcesAwaitingVerification() }

    /**
     * Asks the built-in engine whether one source can serve a track, the way playback would.
     *
     * The Audio Sources Test button used to answer this through the relay's `/health?source=`
     * endpoint using Hush's relay session - a credential playback does not use. Measured on the
     * device: all four signed sources were `VERIFIED` and downloading, while Test answered
     * "Cloudflare verification required - open SpotiFLAC settings to authenticate" for every one of
     * them, because the relay session was (correctly) not active. A test that fails for a source
     * that works is worse than no test, and it was asking about the wrong credential entirely.
     *
     * This runs the engine's own path instead: the extension package must load, the source must not
     * be holding a pending challenge, and the extension's own health probe must answer. Its payload
     * is the source's real verdict - `{"extension_id":"deezer","status":"online","checks":[...]}`
     * measured on the device - and a failing check is reported as the reason.
     *
     * The runtime's `isExtensionAuthenticatedByID` is deliberately *not* consulted: it answers false
     * for sources that are verified, playing and downloading on this device (the runtime marks one
     * authenticated inside its download preflight, not before it), so gating on it would reproduce
     * the very failure this replaces - a test that fails for a source that works.
     *
     * @param sourceId an extension id, or the registry's `providerKey` for the same source.
     * @return the probe's own short verdict, or a failure whose message is the reason.
     */
    suspend fun testSource(sourceId: String): Result<String> = withContext(Dispatchers.IO) {
        // A module is not the runtime's to test. It has no package, no session and no health
        // endpoint, and asking the runtime about an id it has never heard of is exactly the shape of
        // the wrong answer this file already carries a warning about. What can be asked of a module
        // is what its Test would mean: the script arrives, evaluates, and exports the two functions
        // Hush calls - the same question the runtime's health probe answers for an extension.
        repositoryManager
            .getSourceForId(sourceId)
            ?.takeIf { it.isHostedModule }
            ?.let { module ->
                return@withContext moduleHost.probe(module).map { verdict -> "${module.displayName}: $verdict" }
            }
        // A server addon is not the runtime's to test either: there is no package, no session and
        // no health endpoint, and the question a Test asks is whether its service answers now.
        repositoryManager
            .getSourceForId(sourceId)
            ?.takeIf { it.isServerAddon }
            ?.let { addon ->
                // Test is the way back: it asks the service on purpose, records a failure and
                // forgets one on success, so a source the sweep has left out can be checked - and
                // brought back - without waiting the cooldown out.
                val probed = serverAddonHost.probe(addon)
                probed
                    .onSuccess { clearProviderUnavailable(addon.id) }
                    .onFailure { failure -> refuseAddon(addon, failure) }
                return@withContext probed.map { verdict -> "${addon.displayName}: $verdict" }
            }
        runCatching {
            // A blocked client fails the test for a reason that is not the source's, so it is
            // reported as the block rather than as a source error. The row then says what is wrong
            // and for how long, instead of sending the user to fix a source that is fine.
            SpotiFLACSessionRenewer.relayBlock(context)?.let { block ->
                throw SpotiFLACException(
                    "blocked (429) - " +
                        "${SpotiFLACSessionRenewer.formatBlockRemaining(block.remainingMs)} left",
                )
            }
            val backend = requireBackend()
            initializeRuntime(backend)
            val id = extensionIdFor(sourceId)
            ensurePackageLoaded(backend, id, repositoryManager.getSourceForId(id))
            if (SpotiFLACSourceAuth.declaresNoSignedSession(manifestJsonFor(id))) {
                return@runCatching "$id needs no signed session"
            }
            val probe = runCatching { invokeString(backend, "checkExtensionHealthJSON", id) }
            val probeBody = probe.getOrNull().orEmpty()
            SpotiFLACDiag.log("engine test $id: health=${probeBody.take(600)}")
            // A challenge for *this* extension is the one thing a health probe cannot resolve on
            // its own, so it is reported as the reason rather than as a generic failure.
            ownChallenge(backend, id)?.let { pending ->
                throw SpotiFLACException(
                    "Needs verification - the engine raised a challenge for $id" +
                        pending.authUrl?.takeIf { it.isNotBlank() }?.let { url -> " ($url)" }.orEmpty(),
                )
            }
            probe.exceptionOrNull()?.let { failure ->
                throw SpotiFLACException(
                    failure.message?.take(MAX_TEST_REASON) ?: "health probe failed for $id",
                )
            }
            healthVerdict(probeBody, id)
        }
    }

    /**
     * The extension id behind whatever a caller named a source.
     *
     * The Audio Sources rows hand over the registry's `providerKey` when there is one, and that is
     * not always the extension id - the registry's `tidal` is the extension `tidal-web`. Both name
     * the same source, so both are accepted; an unknown name is passed through unchanged, so a
     * failure still names what the user tapped.
     */
    private fun extensionIdFor(requested: String): String {
        val wanted = requested.trim()
        val sources = repositoryManager.sources.value
        sources.firstOrNull { it.source.id.equals(wanted, ignoreCase = true) }
            ?.let { return it.source.id }
        sources.firstOrNull { it.source.providerKey.equals(wanted, ignoreCase = true) }
            ?.let { return it.source.id }
        return wanted
    }

    /** A JSON field's text, treating a JSON null as absent. */
    private fun JsonObject.textOrNull(key: String): String? =
        this[key]
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    /**
     * The extension's own verdict, in one line, or a failure naming the check that failed.
     *
     * The payload is a report rather than a boolean: a top-level status plus one entry per check, so
     * a source that answers with a status this does not recognise is still reported *as it answered*
     * instead of being forced into pass/fail by a vocabulary that could drift.
     */
    private fun healthVerdict(
        body: String,
        id: String,
    ): String {
        val payload = runCatching { json.parseToJsonElement(body.trim()).jsonObject }.getOrNull()
            ?: return "engine answered for $id"
        payload.textOrNull("error")?.let { throw SpotiFLACException(it.take(MAX_TEST_REASON)) }
        val failedCheck =
            (payload["checks"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { check ->
                    val status = check.textOrNull("status")?.lowercase()
                    status != null && status !in HEALTHY_STATUSES && check["required"]?.jsonPrimitive?.content != "false"
                }
        if (failedCheck != null) {
            val label = failedCheck.textOrNull("label") ?: failedCheck.textOrNull("id") ?: id
            val status = failedCheck.textOrNull("status").orEmpty()
            // The check's own words are preferred over its status word: "offline" is the word the
            // row's session line appears to contradict, while "Deezer: fetch failed" names the half
            // that is broken. `message` is the runtime's sentence and already says which service it
            // is about, so the label would only double up in front of it; `error`/`detail` are raw
            // values and do need it.
            val message = failedCheck.textOrNull("message")
            val machine = failedCheck.textOrNull("error") ?: failedCheck.textOrNull("detail")
            val what = when {
                !message.isNullOrBlank() -> message
                !machine.isNullOrBlank() -> "$label: $machine"
                else -> "$label is $status"
            }
            // Scoped on the way out, so a provider-side outage cannot read as a verdict on the
            // user's session. Recorded as well, because this answer came from the provider itself
            // and a failed Test is exactly as good a moment to stop asking it as a failed sweep is.
            noteProviderUnavailable(id, what)
            throw SpotiFLACException(SpotiFLACProviderNote.headline(what).take(MAX_TEST_REASON))
        }
        val status = payload.textOrNull("status") ?: payload.textOrNull("state") ?: "engine answered"
        if (status.lowercase() in HEALTHY_STATUSES) {
            // It just answered healthy, so an earlier demotion is stale by definition.
            clearProviderUnavailable(id)
            return status.take(MAX_TEST_REASON)
        }
        // A top-level status that is neither healthy nor one of the two states above is still the
        // source's own answer, and reporting it verbatim is more useful than a generic failure.
        return status.take(MAX_TEST_REASON)
    }

    /**
     * Stops the runtime from routing fallback downloads through extensions whose
     * signed session is expired.
     *
     * The runtime pauses its own provider fallback the moment a provider answers
     * `verification_required`, and it walks *every* installed download provider by
     * default - not just the ones this resolve asked for. One stale provider
     * therefore failed every source in the chain with that provider's error, which
     * is why playback reported "extension 'amazon' needs signed-session
     * verification" for tracks attempted through Deezer and Qobuz. Disabling the
     * unusable providers mirrors the candidate list Hush already computes.
     */
    private fun gateUnverifiedProviders(backend: Class<*>, candidates: List<String>) {
        runCatching {
            val unusable = (unverifiedDownloadExtensionIds() + candidates.filter {
                sourceAuthState(it) == SpotiFLACSourceAuthState.NEEDS_VERIFICATION
            }).distinct()
            if (unusable.isEmpty()) return@runCatching
            unusable.forEach { id ->
                runCatching { invokeVoid(backend, "setExtensionEnabledByID", id, false) }
            }
            SpotiFLACDiag.log("runtime gating: disabled unverified=${unusable.joinToString(",")}")
        }.onFailure { SpotiFLACDiag.log("runtime gating failed: ${it.message}") }
    }

    /**
     * Whether the runtime's own sweep can never use this source, so it is not asked on the runtime's
     * behalf at all.
     *
     * Two ways to be unusable there, and they are different questions asked of the same id: the
     * source is a JavaScript module, which the *runtime* has never heard of - [SpotiFLACModuleHost]
     * runs it, and it gets its turn when this chain comes up empty (see [resolveViaModuleSource]) - or
     * its installed package declares that it does not download (see [SpotiFLACExtensionRole]), which
     * makes it the last, most expensive slot of a sweep for a provider that was never able to answer.
     *
     * A source this install cannot classify is kept: before the first registry sync there is no live
     * entry to read, and an id wrongly dropped is a source that never gets asked, which is the worse
     * of the two mistakes. The same rule the manifest question already follows.
     */
    private fun cannotSweepNatively(sourceId: String): Boolean {
        val source = repositoryManager.getSourceForId(sourceId)
        if (source != null && (source.isHostedModule || source.isServerAddon)) return true
        // The installed package is consulted first whenever it can be read, because it is the code
        // the runtime will actually run and it carries the roles the runtime's own sweep reads. A
        // source whose package is missing has no manifest to ask, and only then is the registry's
        // declaration used - a source published as a metadata provider cannot be the one that serves
        // this track, and asking it costs the sweep its last, most valuable slot for an answer that
        // was never possible. An entry Hush cannot classify is kept either way; see
        // [ExtensionSource.declaresMetadataOnly].
        val manifest = manifestJsonFor(sourceId)
        if (manifest != null) return SpotiFLACExtensionRole.declaresNoDownloadProvider(manifest)
        return source?.declaresMetadataOnly == true
    }

    /**
     * How many providers the installed packages alone could offer a sweep.
     *
     * Used to size a sweep's budget *before* it starts, together with the enabled list - an upper
     * bound, not a prediction, and deliberately the package directory's count rather than the switch
     * list's: the budget has to cover whatever actually gets walked, and guessing low is the mistake
     * that matters. The enabled list is empty during a cold start - the registry sync has not landed -
     * while the sweep is still deciding, and a budget sized for one provider cut a six-provider chain
     * off part-way, which is indistinguishable from a catalogue miss. Measured on the reporting
     * device: `sweep budget ... sources=0 perAttemptMs=25000 budgetMs=50000`.
     *
     * The sweep itself is no longer the directory: it walks the switches, and only an install that has
     * never recorded one still falls back to what is installed (see [SpotiFLACCandidateOrder.sweep]).
     * An over-sized budget costs nothing but the patience of a sweep that has nothing to walk, which
     * ends as soon as its last candidate has answered.
     */
    fun installedSweepSourceCount(): Int = installedDownloadExtensionIds().size

    /**
     * How many modules or server addons a sweep of [quality] will walk, for sizing its budget.
     *
     * Zero unless the request is bucketed as lossless, because that is the only bucket a module can
     * answer - [resolvePlan] only puts them in the walk for one, since their rows declare `LOSSLESS`.
     * Read here rather than in the caller for the same reason the bucket rule lives here: a count that
     * disagreed with the walk would size a budget for work the sweep does not do, or - the mistake
     * that matters - leave out work it does.
     *
     * The count is of *entries*, not of attempts: each module is asked twice (search, then stream) and
     * the budget's own rule charges it for both.
     */
    fun addonSweepSourceCount(quality: String): Int {
        if (SpotiFLACPlaybackCache.qualityBucket(quality) != LOSSLESS_BUCKET) return 0
        return runCatching { repositoryManager.getEnabledAddonSources() }
            .getOrDefault(emptyList())
            .size
    }

    private fun installedDownloadExtensionIds(): List<String> {
        val root = File(context.filesDir, "spotiflac/extensions")
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) return@mapNotNull null
            runCatching {
                val obj = json.parseToJsonElement(manifest.readText()).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: dir.name
                // The type check below already excludes a metadata-only extension, so the extra
                // name check that used to sit here (for `spotify-web`) only risked disagreeing with
                // the declaration - spotify-web carries `type: ["metadata_provider"]` today.
                val types = obj["type"] as? kotlinx.serialization.json.JsonArray
                val isDownload = types?.any { element ->
                    element.jsonPrimitive.content.equals("download_provider", ignoreCase = true)
                } == true
                name.takeIf { isDownload }
            }.getOrNull()
        }.sorted()
    }

    /** Every extension id with an extracted package, whether or not it can download. */
    private fun installedExtensionIds(): List<String> =
        File(context.filesDir, "spotiflac/extensions")
            .listFiles { file -> file.isDirectory }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    /**
     * Makes the runtime's own switches agree with the ones in Audio Sources.
     *
     * Hush's list is not the runtime's. Startup hands it the whole extensions directory
     * ([initializeRuntime] -> `loadExtensionsFromDir`), and an extension the runtime has loaded is
     * an extension it will use: its provider walk, its metadata enrichment and its quality probing
     * all read that state and never ask Hush. Measured on the reporting device: `apple-music` was
     * switched off in Audio Sources and still ran the metadata enrichment for a resolve, and the
     * YouTube Music source was switched off and still served the track. A source the user switched
     * off is switched off in the runtime too, so nothing behind the app's back can spend it.
     *
     * Asked only when a choice is on record ([SourceSwitches.known]): with nothing recorded there is
     * nothing to apply, and disabling extensions on the strength of an empty list would be the same
     * mistake in the other direction - a fresh install whose runtime walk had been emptied.
     *
     * The switched-on sources are deliberately not enabled here: [ensurePackageLoaded] turns each one
     * on as it registers it, and it is also what turns a source back on for the one user request that
     * outranks the switch - a verification the user asked for by name.
     */
    private suspend fun applySourceSwitchesToRuntime(backend: Class<*>) {
        val switches = runCatching { repositoryManager.sourceSwitches() }.getOrNull() ?: return
        if (!switches.known) return
        val on = switches.enabledInOrder.map { it.lowercase(java.util.Locale.US) }.toSet()
        val off =
            installedExtensionIds().filterNot { it.lowercase(java.util.Locale.US) in on }
        for (id in off) {
            runCatching { invokeVoid(backend, "setExtensionEnabledByID", id, false) }
        }
        if (off.isNotEmpty()) {
            SpotiFLACDiag.log(
                "runtime switches applied: ${off.size} switched-off source(s) disabled in the " +
                    "runtime: ${off.joinToString(",")}",
            )
        }
    }

    /**
     * Asks the runtime for a specific extension's verification challenge.
     *
     * Extensions own their own Turnstile flow: when the signed-session preflight
     * fails, the extension registers a PendingAuthRequest whose `auth_url` the
     * host must open. The grant returned to that URL's callback is then completed
     * through [deliverGrant] for this extension only — exchanging it in Hush's own
     * session flow would consume the single-use grant and the runtime's exchange
     * would fail with HTTP 403 (exactly the failure observed before this split).
     */
    suspend fun pendingAuthFor(extensionId: String): PendingExtensionAuth? = withContext(Dispatchers.IO) {
        runCatching {
            val backend = requireBackend()
            initializeRuntime(backend)
            ensurePackageLoaded(backend, extensionId, repositoryManager.getSourceForId(extensionId))
            // Verify is an explicit request, so enable the extension even when
            // resolve-time gating disabled it for being unverified.
            invokeVoid(backend, "setExtensionEnabledByID", extensionId, true)
            perExtensionPendingAuth(backend, extensionId)
                ?: runtimePendingAuthFor(backend, extensionId)
        }.onFailure { SpotiFLACDiag.log("pendingAuthFor $extensionId failed: ${it.message}") }.getOrNull()
    }

    private fun perExtensionPendingAuth(
        backend: Class<*>,
        extensionId: String,
    ): PendingExtensionAuth? {
        val raw = invokeString(backend, "getExtensionPendingAuthJSON", extensionId)
        if (raw.isBlank() || raw == "null") return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val authUrl = obj["auth_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        return PendingExtensionAuth(
            extensionId = obj["extension_id"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: extensionId,
            authUrl = authUrl,
            callbackUrl = obj["callback_url"]?.jsonPrimitive?.content,
        )
    }

    /**
     * A challenge the runtime raised while trying a *different* provider.
     *
     * The runtime's download pipeline walks several providers per attempt, so the
     * pending request can belong to a provider other than the one whose button was
     * pressed. Without this fallback the button answered "no challenge yet" while a
     * usable challenge was sitting in the runtime's global list.
     *
     * It is a *fallback* and not an answer: one grant redeems for exactly one
     * extension (see [deliverGrant]), so a challenge raised for another provider does
     * not verify the one the user tapped. The per-extension lookup above is tried first
     * for that reason, and its owner is what the grant is delivered to.
     */
    private fun runtimePendingAuthFor(
        backend: Class<*>,
        extensionId: String,
    ): PendingExtensionAuth? {
        val raw = runCatching { invokeString(backend, "getAllPendingAuthRequestsJSON") }
            .getOrNull().orEmpty()
        if (raw.isBlank() || raw == "[]") return null
        val arr = runCatching {
            json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return null
        val entries = arr.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        val exact = entries.firstOrNull { entry ->
            entry["extension_id"]?.jsonPrimitive?.content?.equals(extensionId, ignoreCase = true) == true
        }
        val chosen = exact ?: entries.firstOrNull()
        val authUrl = chosen?.get("auth_url")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        SpotiFLACDiag.log(
            "pending auth for $extensionId resolved from runtime list " +
                "(owner=${chosen["extension_id"]?.jsonPrimitive?.content ?: "?"})",
        )
        return PendingExtensionAuth(
            extensionId = chosen["extension_id"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: extensionId,
            authUrl = authUrl,
            callbackUrl = chosen["callback_url"]?.jsonPrimitive?.content,
        )
    }

    /** A challenge [extensionId] raised itself. Loading and enabling it is part of asking. */
    private suspend fun ownChallenge(
        backend: Class<*>,
        extensionId: String,
    ): PendingExtensionAuth? {
        ensurePackageLoaded(backend, extensionId, repositoryManager.getSourceForId(extensionId))
        invokeVoid(backend, "setExtensionEnabledByID", extensionId, true)
        return perExtensionPendingAuth(backend, extensionId)
    }

    /**
     * The challenge to show for a source, raising one if the runtime has none yet.
     *
     * A challenge is only registered once the runtime performs a signed-session
     * preflight for that extension, which normally happens during playback. Asking
     * for one directly means the "Verify" button works instead of answering "play a
     * track first" - the health check runs the extension's own availability path,
     * which is where an expired session raises its PendingAuthRequest.
     *
     * This extension's *own* challenge is what is looked for first, because the runtime's global
     * list is the wrong answer for a per-source request: it holds challenges other providers raised,
     * and returning one of those skipped the health probe below entirely - so the source the user
     * asked about never raised a challenge of its own, and the check they solved was for a different
     * source. The global list stays as the last resort it was meant to be.
     */
    suspend fun ensureChallenge(extensionId: String): PendingExtensionAuth? = withContext(Dispatchers.IO) {
        runCatching {
            val backend = requireBackend()
            initializeRuntime(backend)
            ownChallenge(backend, extensionId)?.let { return@runCatching it }
            runCatching { invokeString(backend, "checkExtensionHealthJSON", extensionId) }
                .onFailure { SpotiFLACDiag.log("health probe for $extensionId failed: ${it.message}") }
            ownChallenge(backend, extensionId) ?: runtimePendingAuthFor(backend, extensionId)
        }.onFailure { SpotiFLACDiag.log("ensureChallenge $extensionId failed: ${it.message}") }.getOrNull()
    }

    /** True when the runtime already holds a valid signed session for this extension. */
    fun isExtensionAuthenticated(extensionId: String): Boolean {
        val backend = backendClass ?: return false
        return runCatching {
            val method = backend.methods.firstOrNull {
                it.name == "isExtensionAuthenticatedByID" && it.parameterTypes.size == 1
            } ?: return@runCatching false
            method.invoke(null, extensionId) as? Boolean ?: false
        }.getOrDefault(false)
    }

    /**
     * Pending runtime auth URL, if a download preflight demanded verification.
     * Mirrors upstream: the runtime registers a PendingAuthRequest when it needs
     * a fresh challenge, and the host app opens that URL in a WebView.
     */
    fun pendingRuntimeAuthUrl(): String? = pendingRuntimeAuth()?.authUrl

    suspend fun resolve(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        isrc: String?,
        spotifyTrackId: String?,
        quality: String,
        sourceIds: List<String>,
        coverUrl: String? = null,
        mediaId: String? = null,
    ): Result<ResolvedFile> = withContext(Dispatchers.IO) {
        runCatching {
            require(title.isNotBlank()) { "SpotiFLAC track title is missing" }

            val cacheEnabled = playbackCache.cacheStreamingEnabled()
            val requestedBucket = SpotiFLACPlaybackCache.qualityBucket(quality)
            val trackKey =
                playbackCache.trackKey(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    isrc = isrc,
                    spotifyTrackId = spotifyTrackId,
                    quality = quality,
                )

            // Cache first: a previously resolved track is served straight off disk
            // without touching the runtime, so replays and seeks never re-download.
            cachedResolution(mediaId, trackKey, title)?.let { return@runCatching it }

            // Second chance by media id. The identity inputs are collected from whatever
            // metadata the caller has, and that legitimately differs for the same track:
            // a queue item restored after a restart carries no duration, while the resolve
            // that downloaded the file did. Measured on device for one song, the file was
            // written under `Africa|Toto||0` and the next lookup computed
            // `Africa|Toto||272000`, missed, and re-downloaded a track already on disk.
            // The index is keyed on the queue item, so it still finds that file.
            val indexedKey = playbackCache.trackKeyForMediaId(mediaId)
            if (indexedKey != null && indexedKey != trackKey) {
                cachedResolution(
                    mediaId = mediaId,
                    trackKey = indexedKey,
                    title = title,
                    requiredBucket = requestedBucket,
                )?.let {
                    SpotiFLACDiag.log(
                        "cache hit by mediaId index key=$indexedKey " +
                            "(identity key=$trackKey had no entry)",
                    )
                    return@runCatching it
                }
            }

            // Serialize concurrent resolves of the same track: a prefetch racing the
            // playback resolve used to download the same file twice.
            val mutex = resolveLocks.computeIfAbsent(trackKey) { Mutex() }
            try {
                mutex.withLock {
                    // Another resolve may have completed while this one waited.
                    cachedResolution(mediaId, trackKey, title)?.let { return@runCatching it }
                    // Modules and addons are walked at their own place in the Audio Sources order,
                    // so the fallback below must not ask one twice. Names what the walk already
                    // asked, by id.
                    val attemptedAddons = mutableSetOf<String>()
                    try {
                        resolveUncached(
                            mediaId = mediaId,
                            trackKey = trackKey,
                            cacheEnabled = cacheEnabled,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = durationMs,
                            isrc = isrc,
                            spotifyTrackId = spotifyTrackId,
                            quality = quality,
                            sourceIds = sourceIds,
                            coverUrl = coverUrl,
                            attemptedAddons = attemptedAddons,
                        )
                    } catch (nativeFailure: Throwable) {
                        if (nativeFailure is CancellationException) throw nativeFailure
                        // The fallback covers the failures that arrive *before* the walk starts - a
                        // gateway block, a runtime this build does not have, a plan that could not be
                        // built. A module needs no package, no session and no gateway, so a source the
                        // user switched on can still serve the track there. What it must not do is
                        // re-ask a module the walk has already put this track to: that source is not
                        // a better candidate for having been tried twice.
                        resolveViaModuleSource(
                            mediaId = mediaId,
                            trackKey = trackKey,
                            cacheEnabled = cacheEnabled,
                            title = title,
                            artist = artist,
                            durationMs = durationMs,
                            quality = quality,
                            nativeFailure = nativeFailure,
                            alreadyAsked = attemptedAddons,
                        ) ?: throw nativeFailure
                    }
                }
            } finally {
                resolveLocks.remove(trackKey, mutex)
            }
        }
    }

    /**
     * The modules and addons a resolve that failed *before its walk* can still be served by.
     *
     * The walk in [resolveUncached] already asks every module at the place the user gave it, so this
     * is not the ordinary module path any more - it is the seam for the failures that arrive before
     * the walk starts: a gateway block, a build without the native runtime, a plan that could not be
     * built. A module needs no package, no session and no gateway, so an enabled one can still serve
     * the track there.
     *
     * [alreadyAsked] is what keeps it from asking twice: the walk records every module it put the
     * track to, and a source is not a better candidate for having been tried a second time.
     *
     * Only a lossless request is answered. A module's rows declare `LOSSLESS`, so it cannot serve a
     * hi-res one - and a lossless file recorded under a hi-res key is a file the cache then refuses to
     * serve back (see `bucketSatisfies`), which would mean downloading it again on every play of that
     * track.
     */
    @Suppress("LongParameterList")
    private suspend fun resolveViaModuleSource(
        mediaId: String?,
        trackKey: String,
        cacheEnabled: Boolean,
        title: String,
        artist: String,
        durationMs: Long,
        quality: String,
        nativeFailure: Throwable,
        alreadyAsked: Set<String> = emptySet(),
    ): ResolvedFile? {
        if (SpotiFLACPlaybackCache.qualityBucket(quality) != LOSSLESS_BUCKET) return null
        // A module index publishes JavaScript Hush runs; a server addon publishes an HTTP service.
        // Both answer the same two questions - search, then a stream URL - and both are one list
        // here, in the order the user arranged.
        ensureStallsLoaded()
        val addons =
            runCatching { repositoryManager.getEnabledAddonSources() }
                .getOrDefault(emptyList())
                .filterNot { source -> alreadyAsked.any { it.equals(source.id, ignoreCase = true) } }
                // A refused addon is not asked again here either. This seam only runs when the walk
                // itself could not start, and re-asking a service that just refused is the cost per
                // track this whole change exists to stop. Kept whole when nothing would be left:
                // asking a hurting service beats not playing.
                .let { sources ->
                    val live =
                        SpotiFLACProviderStallPolicy.availableAddons(
                            addons = sources.map { it.id },
                            unavailableAtMs = unavailableAtMsBySource,
                            nowMs = System.currentTimeMillis(),
                        ).toSet()
                    sources.filter { it.id in live }
                }
        if (addons.isEmpty()) return null

        val query = listOf(title, artist).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        SpotiFLACDiag.log(
            "module fallback after \"${nativeFailure.message}\": asking ${addons.size} module/addon source(s) " +
                "for \"$query\"",
        )
        for (source in addons) {
            resolveViaAddon(
                source = source,
                mediaId = mediaId,
                trackKey = trackKey,
                cacheEnabled = cacheEnabled,
                title = title,
                artist = artist,
                durationMs = durationMs,
                quality = quality,
            )?.let { return it }
        }
        return null
    }

    /**
     * One module or server addon, asked for one track at one place in the walk.
     *
     * A module source is a different kind of candidate rather than a worse one. It needs no session,
     * no verification and no package, and what it knows is its own service - for the Archive module
     * that is live tapes, transfers and bootlegs a commercial catalogue does not have at all. So it
     * is asked where the user put it, between the runtime sources they ranked around it, and the
     * result is delivered through the playback cache like every other SpotiFLAC track.
     */
    @Suppress("LongParameterList")
    private suspend fun resolveViaAddon(
        source: ExtensionSource,
        mediaId: String?,
        trackKey: String,
        cacheEnabled: Boolean,
        title: String,
        artist: String,
        durationMs: Long,
        quality: String,
        /** Where this module sits in the walk, for the row's "(2 of 3)"; 0 when unknown. */
        attemptIndex: Int = 0,
        attemptTotal: Int = 0,
    ): ResolvedFile? {
        val wanted = SpotiFLACModuleMatch.Wanted(title = title, artist = artist, durationMs = durationMs)
        val query = listOf(title, artist).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

        // The sweep is announced from its first search, not from its first byte.
        //
        // The runtime's own walk publishes a stage per provider (`resolving_metadata` ->
        // `resolving_stream`), which is what makes "Fetching from deezer…" appear while a track is
        // being tried rather than only once its file is moving. This path published nothing until a
        // module's download was already under way, so a refetch that spent half a minute across two
        // services showed a stopped player and named no source at all - measured on the reporting
        // device as `refetch requested` at 11:13:45 and the first label at 11:14:42, with gglos
        // dropping six manifest rows and one download failing in between. The stages reuse the
        // runtime's vocabulary because every surface that reads them already understands it.
        // What the last announcement was, so the log carries the stages of a sweep rather than one
        // line per 64 KB chunk: a 40 MB file is six hundred of those, and they would push every
        // other line out of the rolling diagnostic within a track.
        var announced: String? = null
        fun announce(
            stage: String,
            percent: Int = 0,
            received: Long = 0L,
            total: Long = 0L,
        ) {
            _downloadProgress.value =
                PlaybackDownloadProgress(
                    mediaId = mediaId.orEmpty(),
                    sourceId = source.id,
                    sourceLabel = repositoryManager.sourceLabel(source.id),
                    percent = percent,
                    bytesReceived = received,
                    bytesTotal = total,
                    stage = stage,
                    attemptIndex = attemptIndex,
                    attemptTotal = attemptTotal,
                )
            // Said out loud because the label it drives only shows while the track is current, and
            // "which source is the sweep on" is the first question a refetch that looks stuck
            // raises. The runtime's own walk logs its stages the same way.
            val key = "${source.id}|$stage"
            if (key != announced) {
                announced = key
                SpotiFLACDiag.log(
                    "module progress source=${source.id} stage=$stage pct=$percent",
                )
            }
        }

        try {
            announce("resolving_metadata")
            val searched =
                if (source.isServerAddon) {
                    // The tier and the Atmos preference travel with the search as well as the stream:
                    // a server addon is told what the client wants on every call, and the search is
                    // what decides which rows come back at all (see [AddonRequestSettings]).
                    serverAddonHost.search(source, query, MODULE_SEARCH_LIMIT, quality)
                } else {
                    moduleHost.search(source, query, MODULE_SEARCH_LIMIT)
                }
            if (searched.isFailure) {
                SpotiFLACDiag.log("module search source=${source.id} failed: ${searched.exceptionOrNull()?.message}")
                refuseAddon(source, searched.exceptionOrNull())
                return null
            }
            // The service answered, so whatever it said last is stale by definition.
            if (source.isServerAddon) noteAddonAnswering(source.id, source.displayName)
            val offered = searched.getOrThrow()
            // A server addon says which rows it can deliver as a *file*. A DASH/HLS row is a
            // manifest - measured on gglos, a 30-second preview playlist - so it is dropped before
            // matching: choosing it would download a description of a song and play it as one.
            // Rows with no declared format are kept: that is every module's answer, and a module
            // that names no format is a module whose stream call is the thing to try.
            val rows =
                if (source.isServerAddon) offered.filter { addonFormatIsPlayable(it.format) } else offered
            if (rows.size < offered.size) {
                SpotiFLACDiag.log(
                    "module source=${source.id} dropped ${offered.size - rows.size} row(s) that only " +
                        "offer a manifest (dash/hls), not a file",
                )
            }
            if (rows.isEmpty()) {
                SpotiFLACDiag.log("module source=${source.id} has no playable row for \"$query\"")
                return null
            }
            val candidates =
                rows.map {
                    SpotiFLACModuleMatch.Row(
                        id = it.id,
                        title = it.title,
                        artist = it.artist,
                        durationMs = it.durationMs,
                    )
                }
            val matched = SpotiFLACModuleMatch.best(wanted, candidates)
            if (matched == null) {
                // Said out loud with the count and the closest row, because "the module had nothing",
                // "the module had twelve rows and none of them was this song" and "the matcher is too
                // strict for what this source actually offers" are three different answers, and the
                // user asking why a module will not play a song needs the third one named.
                val closest = candidates.maxByOrNull { SpotiFLACModuleMatch.score(wanted, it) }
                SpotiFLACDiag.log(
                    "module source=${source.id} matched nothing for \"$query\" (${rows.size} row(s) " +
                        "offered, best \"${closest?.title}\" by \"${closest?.artist}\" at " +
                        "${closest?.let { "%.2f".format(SpotiFLACModuleMatch.score(wanted, it)) }})",
                )
                return null
            }

            announce("resolving_stream")
            val answered =
                if (source.isServerAddon) {
                    serverAddonHost.stream(source, matched.id, quality)
                } else {
                    moduleHost.stream(source, matched.id, quality)
                }
            if (answered.isFailure) {
                SpotiFLACDiag.log("module stream source=${source.id} failed: ${answered.exceptionOrNull()?.message}")
                refuseAddon(source, answered.exceptionOrNull())
                return null
            }
            if (source.isServerAddon) noteAddonAnswering(source.id, source.displayName)
            val stream = answered.getOrNull()
            if (stream == null) {
                // The module ran its own check and refused its own row: a real answer, not a failure.
                SpotiFLACDiag.log("module source=${source.id} refused its own row \"${matched.title}\"")
                return null
            }

            val destination =
                if (cacheEnabled) {
                    playbackCache.outputFileFor(trackKey)
                } else {
                    File(playbackCache.playbackDir(), "play-${UUID.randomUUID()}.flac")
                }
            // The file is being written over the very path a lookup would read, so the key is marked
            // for the same reason the runtime's own downloads mark it.
            if (cacheEnabled) playbackCache.beginRewrite(trackKey)
            val downloaded =
                try {
                    moduleHost.download(stream.url, destination) { received, total ->
                        announce(
                            stage = "downloading",
                            percent =
                                if (total > 0L) {
                                    ((received * 100) / total).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                },
                            received = received,
                            total = total,
                        )
                    }
                } finally {
                    if (cacheEnabled) playbackCache.endRewrite(trackKey)
                }
            if (downloaded.isFailure) {
                SpotiFLACDiag.log(
                    "module download source=${source.id} failed: ${downloaded.exceptionOrNull()?.message}",
                )
                return null
            }
            val bytes = downloaded.getOrDefault(0L)
            if (cacheEnabled) {
                playbackCache.record(
                    trackKey = trackKey,
                    file = destination,
                    sourceId = source.id,
                    codec = MODULE_CODEC,
                    bitDepth = null,
                    sampleRate = null,
                    mediaId = mediaId,
                    qualityBucket = SpotiFLACPlaybackCache.qualityBucket(quality),
                )
            }
            announce(
                stage = "downloading",
                percent = 100,
                received = bytes,
                total = bytes,
            )
            SpotiFLACDiag.log(
                "module resolved source=${source.id} bytes=$bytes quality=" +
                    "${stream.quality ?: LOSSLESS_QUALITY} url=${stream.url.take(140)}",
            )
            return ResolvedFile(
                file = destination,
                sourceId = source.id,
                title = matched.title,
                artist = matched.artist,
                quality = stream.quality ?: LOSSLESS_QUALITY,
                codec = MODULE_CODEC,
                trackKey = trackKey.takeIf { cacheEnabled },
            )
        } finally {
            // Deliberately nothing: the signal ends once for the whole sweep (see the sweep's own
            // end) rather than per source, so a module that answers "not mine" in 300ms does not
            // blink the player's line off while the next source is being asked.
        }
    }

    /**
     * Records that a server addon refused, so the rest of the session stops paying for it per track.
     *
     * The failure is the addon's own: [ServerAddonHost] only fails a call for a reason that is about
     * the *service* - a non-2xx status with the addon's own word for it, a host that did not answer
     * inside its budget, a connection that never opened - while "no rows" and "no file for this
     * track" are successful answers that reach here as empty results and record nothing.
     *
     * A refusal is not a catalogue miss, and the difference is the whole point: measured against
     * `tido.tido.tido.dpdns.org`, every call answered `403 {"error":"Unavailable"}` in under a
     * second, so a walk with two Tido profiles in it spent two round trips per track to learn what
     * the first one had already said. After this, the second track pays nothing: the addons are left
     * out of the walk for the cooldown, and the Audio Sources row shows the addon's own sentence.
     *
     * Modules are deliberately not recorded this way. A module's failure is a script error against
     * one query, and it is the module's own code deciding whether a track is its business - the same
     * record would take a working module's other tracks away for ten minutes.
     */
    private fun refuseAddon(
        source: ExtensionSource,
        failure: Throwable?,
    ) {
        if (!source.isServerAddon) return
        val reason =
            failure?.message?.trim()?.take(MAX_TEST_REASON)?.takeIf { it.isNotEmpty() }
                ?: "did not answer"
        val previous = unavailableSources[source.id]
        noteProviderUnavailable(source.id, reason, skipped = true)
        // A "that source is answering again" notice cannot stand while the same source refuses.
        SpotiFLACAddonRecoveryNotifier.clear(context, source.id)
        // Said once per refusal, not once per track: after this the source is not asked at all until
        // its cooldown ends, so a line here is a line the reader can act on.
        if (previous?.skipped == true && previous.reason == reason) {
            watchAddonRecovery(source)
            return
        }
        SpotiFLACDiag.log(
            "addon source=${source.id} refused ($reason) - left out of the chain for " +
                "${SpotiFLACProviderStallPolicy.UNAVAILABLE_COOLDOWN_MS / 60_000}min",
        )
        watchAddonRecovery(source)
    }

    /**
     * Asks a source that just refused again, in the background, until it answers.
     *
     * The refusal record is the whole reason this exists: a refused addon is left out of the walk so
     * it cannot cost a round trip per song, and what that costs the user is silence - the source sits
     * out ten minutes and nobody learns whether it came back in the second. So it is asked on its own
     * schedule ([SpotiFLACAddonRecoveryPolicy]), with a one-row search, off any user-visible path.
     *
     * The watch ends without a word when the source stops being refused: its cooldown lapsed and a
     * sweep asked it, the user switched it off, or its own Test cleared it. In all three the answer
     * is already on its way somewhere the user can see, and a notice on top of that would be a
     * second report of the first.
     */
    private fun watchAddonRecovery(source: ExtensionSource) {
        if (!recoveringSources.add(source.id)) return
        recoveryScope.launch {
            try {
                SpotiFLACAddonRecovery.watch(
                    stillRefused = { isSkippedNow(source.id) },
                    probe = { serverAddonHost.probe(source).isSuccess },
                    onProbe = { attempt, answered ->
                        // The first probe is said out loud whatever it answers, because the whole
                        // point of the watch is that it is doing something while nothing else runs:
                        // a field log that goes silent for ten minutes cannot tell "still refused"
                        // from "no watch was ever started". Later failures are not logged - one line
                        // per probe would be a log nobody reads.
                        if (answered || attempt == 1) {
                            SpotiFLACDiag.log(
                                "source recovery source=${source.id} attempt=$attempt " +
                                    "answered=$answered",
                            )
                        }
                    },
                    onRecovered = { noteAddonAnswering(source.id, source.displayName) },
                )
            } finally {
                recoveringSources.remove(source.id)
            }
        }
    }

    /**
     * Restarts the recovery watches for addons that were refused before this process started.
     *
     * Only sources that are still enabled are watched: a source the user switched off is not in the
     * walk, so a probe for it would be work spent on something nobody can hear, and its own row's
     * Test is the way to ask it anything.
     */
    private fun resumeAddonRecovery(sourceIds: Set<String>) {
        if (sourceIds.isEmpty()) return
        recoveryScope.launch {
            val sources =
                runCatching { repositoryManager.getEnabledAddonSources() }
                    .getOrDefault(emptyList())
                    .filter { it.id in sourceIds }
            if (sources.isEmpty()) return@launch
            SpotiFLACDiag.log(
                "resuming source recovery for " + sources.joinToString(",") { it.id },
            )
            sources.forEach { watchAddonRecovery(it) }
        }
    }

    /** True while [sourceId] is recorded as refused and still inside that cooldown. */
    private fun isSkippedNow(sourceId: String): Boolean {
        val record = unavailableSources[sourceId] ?: return false
        return record.skipped &&
            SpotiFLACProviderStallPolicy.isUnavailable(record.atMs, System.currentTimeMillis())
    }

    /**
     * A source that had refused has just answered: it is back, and the user is told.
     *
     * One entry point for both routes that can discover it - the background probe, and the first
     * track that happens to ask it after the cooldown lapsed - so the notice cannot be raised twice
     * for one recovery and cannot be missed on either. Only a source that was *skipped* is announced:
     * a runtime provider that reported itself unavailable was never out of the walk, so its recovery
     * is not news, and saying it would train the user to ignore the one that is.
     */
    private fun noteAddonAnswering(sourceId: String, displayName: String) {
        val previous = unavailableSources[sourceId]
        clearProviderUnavailable(sourceId)
        if (previous?.skipped != true) return
        SpotiFLACDiag.log("source $sourceId is answering again - back in the chain")
        SpotiFLACAddonRecovery.noteRecovered()
        SpotiFLACVerifiedBanner.showAnswering(sourceId, displayName)
        SpotiFLACAddonRecoveryNotifier.notify(context, sourceId, displayName)
    }

    /** Serves a track straight from the playback cache when it is already on disk. */
    private fun cachedResolution(
        mediaId: String?,
        trackKey: String,
        title: String,
        /**
         * Required quality group, for the media-id lookup that has no quality in its
         * key. The identity-keyed lookup passes null: its key already encodes the
         * bucket, so a mismatch is impossible there.
         */
        requiredBucket: String? = null,
    ): ResolvedFile? {
        if (!playbackCache.cacheStreamingEnabled()) return null
        val cached = playbackCache.cachedFile(trackKey)
        if (cached == null) {
            SpotiFLACDiag.log("cache miss key=$trackKey title=\"$title\"")
            return null
        }
        if (requiredBucket != null) {
            val storedBucket = playbackCache.entry(trackKey)?.qualityBucket
            if (!SpotiFLACPlaybackCache.bucketSatisfies(storedBucket, requiredBucket)) {
                SpotiFLACDiag.log(
                    "cache index key=$trackKey skipped: stored bucket=${storedBucket ?: "unknown"} " +
                        "for ${requiredBucket} request",
                )
                return null
            }
        }
        playbackCache.associateMediaId(mediaId, trackKey)
        val entry = playbackCache.entry(trackKey)
        _downloadProgress.value =
            PlaybackDownloadProgress(
                mediaId = mediaId.orEmpty(),
                sourceId = entry?.sourceId,
                percent = 100,
                bytesReceived = cached.length(),
                bytesTotal = cached.length(),
                status = "completed",
                fromCache = true,
            )
        SpotiFLACDiag.log(
            "cache hit key=$trackKey file=${cached.name} bytes=${cached.length()}",
        )
        return ResolvedFile(
            file = cached,
            sourceId = entry?.sourceId ?: "cache",
            quality = entry?.codec,
            codec = entry?.codec,
            sampleRate = entry?.sampleRate,
            bitDepth = entry?.bitDepth,
            fromCache = true,
            trackKey = trackKey,
        )
    }

    /** Runtime init, candidate chain, download and cache recording for one track. */
    @Suppress("LongParameterList")
    private suspend fun resolveUncached(
        mediaId: String?,
        trackKey: String,
        cacheEnabled: Boolean,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        isrc: String?,
        spotifyTrackId: String?,
        quality: String,
        sourceIds: List<String>,
        coverUrl: String?,
        /** Modules the walk put this track to, so the pre-walk fallback cannot ask one twice. */
        attemptedAddons: MutableSet<String> = mutableSetOf(),
    ): ResolvedFile {
        // A blocked client cannot be answered by any provider: every extension reaches the same
        // relay, so a sweep can only collect refusals - at the price of the whole provider budget,
        // measured at 96s for a six-source chain - and each of those requests is one more contact
        // during a block the gateway asked not to be contacted in. So it is skipped outright, with
        // the reason carried out, so playback can say what to wait for instead of showing a source
        // error the user has no way to act on.
        relayBlockFailure(SpotiFLACSessionRenewer.relayBlock(context))?.let { blocked ->
            SpotiFLACDiag.log("sweep skipped: ${blocked.message}")
            throw blocked
        }
        val backend = requireBackend()
        initializeRuntime(backend)

        // The plan is the user's list: what to walk, in what order, and which entries are modules.
        // Nothing below may reorder it - see [resolvePlan].
        val plan = resolvePlan(sourceIds, quality)
        val candidates = plan.candidates
        SpotiFLACDiag.log(
            "resolve start title=\"$title\" artist=\"$artist\" isrc=${isrc ?: "-"} " +
                "spotifyId=${spotifyTrackId ?: "-"} quality=$quality candidates=${candidates.joinToString(",")} " +
                "walk=${plan.walk.joinToString(",")}",
        )
        if (plan.walk.isEmpty()) {
            // Distinguish "nothing is enabled" from "everything enabled is waiting
            // for a Cloudflare check". The second case is actionable, so it raises
            // the verification signal (which shows the Verify notice) instead of a
            // generic failure the user cannot act on.
            val blocked = unverifiedDownloadExtensionIds().firstOrNull()
            if (blocked != null) {
                throw SpotiFLACVerificationRequiredException(
                    blocked,
                    "Every enabled SpotiFLAC source needs verification ($blocked first)",
                )
            }
            // A walk emptied by a refusal is not a walk with nothing in it, and saying so is the
            // difference between "switch a source on" and "this service is down": the sources the
            // user chose are enabled, and the row above already names the reason.
            if (plan.skippedAddons.isNotEmpty()) {
                error(
                    "Every enabled SpotiFLAC source has refused service: " +
                        plan.skippedAddons.entries.joinToString("; ") { (id, reason) -> "$id ($reason)" },
                )
            }
            error("No SpotiFLAC download sources are enabled")
        }

        // Upstream provider priority: Hush's enabled order first, then the rest.
        runCatching {
            invokeString(
                backend,
                "setProviderPriorityJSON",
                json.encodeToString(
                    kotlinx.serialization.serializer<List<String>>(),
                    candidates,
                ),
            )
        }.onFailure { Timber.tag(TAG).w(it, "setProviderPriorityJSON failed (non-fatal)") }

        // Keep an expired provider out of the runtime's fallback walk, so it cannot
        // abort every attempt in the chain with its own verification error.
        gateUnverifiedProviders(backend, candidates)

        val tryNextSource = PreferenceStore.get(SpotiFLACTryNextSourceKey) ?: true
        val itemId = "hush-" + (mediaId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
        // The same folder the cached files live in, so an uncached fetch is written beside
        // them and cleaned up by the same reconciliation rather than accumulating in a
        // second location nothing looks at any more.
        val outputDir = playbackCache.playbackDir()
        // A deterministic path lets the runtime reuse an existing file instead
        // of downloading it again (already_exists) and gives Hush a stable
        // cache location per track.
        val outputPath =
            if (cacheEnabled) {
                playbackCache.outputFileFor(trackKey)
            } else {
                File(outputDir, "play-${UUID.randomUUID()}.media")
            }

        var lastError: Throwable? = null
        // The failure of the attempt that just ran, which is what decides whether asking the same
        // source again could produce a different answer. Separate from [lastError], which is the last
        // failure of the *sweep* and is what the aggregate reports.
        var lastAttemptError: Throwable? = null
        var verificationError: SpotiFLACVerificationRequiredException? = null
        val failures = mutableListOf<String>()
        // A source the plan left out is part of the answer, not a gap in it: the sweep reports what
        // happened to every source the user switched on, and a service that refused says so in its
        // own words rather than being named as a source that had nothing.
        plan.skippedAddons.forEach { (id, reason) -> failures += "$id=left out ($reason)" }

        // A source that cannot serve the selected quality was never really asked, so a
        // second attempt at that source is what stops one disabled source from looking
        // like the whole chain giving up. The retry token is per source because it has to
        // come from that source's own manifest. Read once per sweep - the manifests do
        // not change mid-sweep - and only when the request was lossless, since otherwise
        // there is no second question to put.
        val retryTokens =
            if (SpotiFLACQualityCascade.isLossless(quality)) {
                val qualities = installedQualities(backend, candidates)
                candidates.mapNotNull { sourceId ->
                    val declared = qualities[sourceId.lowercase(java.util.Locale.US)]
                        ?: return@mapNotNull null
                    SpotiFLACQualityCascade
                        .lossyRetryToken(declared.options, declared.downloadFallbackTier)
                        ?.let { token -> sourceId to token }
                }.toMap()
            } else {
                emptyMap()
            }

        /**
         * Moves a downloaded file onto the one path its track key owns.
         *
         * The runtime replaces the `.media` name it was handed with the container it actually
         * delivered (`.media.m4a`, `.flac`), and both names were being recorded over time - so a
         * single key could hold two complete copies of the same song, measured on device as a
         * 25 MB FLAC beside a 24 MB M4A of the same track. Only one of them is indexed, which is
         * why nothing counted the other: it was storage spent twice on one song, and a second
         * `/data` path for the same audio. One key, one file - in the serial walk and in the race
         * alike, because two landing rules are what produced the second copy.
         *
         * A rename inside the same directory is free; a different volume (a cache folder on an SD
         * card) cannot be renamed across, so the bytes are copied and the source dropped.
         */
        fun landOnCanonicalPath(downloaded: java.io.File): java.io.File {
            val canonical = playbackCache.outputFileFor(trackKey)
            if (downloaded.absolutePath == canonical.absolutePath) return canonical
            canonical.parentFile?.mkdirs()
            val moved = runCatching { downloaded.renameTo(canonical) }.getOrDefault(false)
            if (!moved) {
                runCatching {
                    downloaded.copyTo(canonical, overwrite = true)
                    downloaded.delete()
                }
            }
            return canonical
        }

        /**
         * One attempt at one source.
         *
         * Returns the resolved file, or null after recording why it failed. Cancellation
         * is re-thrown: the sweep was superseded (a skip, a seek, a duplicate in-flight
         * resolve) and continuing to walk the remaining sources would run downloads for a
         * request nobody is waiting for, and the last one's error would be what the caller
         * classifies the sweep by.
         */
        suspend fun attemptSource(
            sourceId: String,
            attemptQuality: String,
            // The three things a racing attempt must not share with its siblings: the runtime's
            // per-item identity (which the progress meter and `cancelDownload` are keyed by), the
            // file it writes to, and the cache entry it would claim. The defaults are the serial
            // walk's own values, so a race is the only caller that overrides them.
            attemptItemId: String = itemId,
            attemptOutputPath: File = outputPath,
            recordInCache: Boolean = true,
            // Where this attempt sits in the user's walk, so the row can say "1 of 3" while it runs.
            // Defaults for the calls that are not the serial walk (a race, a quality retry).
            attemptIndex: Int = 0,
            attemptTotal: Int = 0,
        ): ResolvedFile? {
            val source = repositoryManager.getSourceForId(sourceId)
            // A metadata provider describes a track; it does not serve one. Skipped here as well as
            // in the candidate list because a caller can name a source on purpose, and a resolve
            // that only ever asked for `spotify-web` would otherwise install a package, load it and
            // spend an attempt to be told the source has no audio. Only a positive declaration skips
            // it: a source the registry says nothing about is still tried, which is the direction a
            // wrong guess is allowed to fail in.
            if (source != null && source.declaresMetadataOnly) return null
            // A racing attempt publishes nothing: several sources are asked at once, so the row
            // would name whichever started last while the winner is what matters. The serial walk
            // is the one a listener waits on source by source, and it is the one announced.
            val announced = recordInCache
            if (announced) publishAttempt(mediaId, sourceId, attemptIndex, attemptTotal)
            // The runtime writes to the same deterministic path as any entry it replaces,
            // so a lookup in that window would see a short file and call it truncated.
            // Marking the key keeps lookups away from a file that is mid-write. Only the
            // attempt that owns the cache entry may claim it: a racer that marked the shared
            // key would hide the winner's file from lookups for as long as it ran.
            if (cacheEnabled && recordInCache) playbackCache.beginRewrite(trackKey)
            return try {
                val response = downloadViaExtension(
                    backend = backend,
                    sourceId = sourceId,
                    source = source,
                    itemId = attemptItemId,
                    mediaId = mediaId,
                    outputDir = outputDir,
                    outputPath = attemptOutputPath,
                    // The *request*, not this attempt's token: it is what the user asked for,
                    // and the useful thing to name when a source answers with something else.
                    requestedQuality = quality,
                    knownProviders = candidates,
                    attemptIndex = attemptIndex,
                    attemptTotal = attemptTotal,
                ) { pkg ->
                    buildRequest(
                        sourceId = sourceId,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        isrc = isrc,
                        spotifyTrackId = spotifyTrackId,
                        quality = attemptQuality,
                        coverUrl = coverUrl,
                        outputDir = pkg.outputDir,
                        outputPath = pkg.outputPath,
                        itemId = attemptItemId,
                        // The sweep below is the walker, unless the user asked for a single source.
                        runtimeWalksProviders = !tryNextSource,
                    )
                }
                // Landed before it is recorded, exactly as a racing winner is: the runtime
                // hands back the file under the container it discovered, so recording that
                // name is what let one track exist twice on disk under one key.
                val landed = if (cacheEnabled && recordInCache) landOnCanonicalPath(response.file) else response.file
                if (cacheEnabled && recordInCache) {
                    playbackCache.record(
                        trackKey = trackKey,
                        file = landed,
                        sourceId = response.sourceId,
                        codec = response.codec,
                        bitDepth = response.bitDepth,
                        sampleRate = response.sampleRate,
                        mediaId = mediaId,
                        // The requested quality, not the per-attempt one: that is what the
                        // cache key was computed from, so the two must agree.
                        qualityBucket = SpotiFLACPlaybackCache.qualityBucket(quality),
                    )
                }
                if (recordInCache) playbackCache.associateMediaId(mediaId, trackKey)
                SpotiFLACDiag.log("resolve ok source=$sourceId quality=$attemptQuality")
                // The audio exists now, so the attempt's own line has nothing left to say. Left
                // published, it would sit on the row as "Fetching from …" over a track that is
                // about to play.
                if (announced) clearDownloadProgress(mediaId)
                response.copy(file = landed, trackKey = trackKey)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                if (announced) clearDownloadProgress(mediaId)
                SpotiFLACDiag.log("sweep cancelled id=$sourceId (not treated as a source failure)")
                throw cancellation
            } catch (error: Throwable) {
                lastAttemptError = error
                // Deliberately no clear here: the sweep continues with the next source, whose own
                // announcement replaces this one. Clearing between attempts is what made the line
                // blink out for every source that answered quickly - measured on device, the row
                // was empty for most of a fresh track's resolve and only ever flickered.
                lastError = error
                if (error is SpotiFLACVerificationRequiredException && verificationError == null) {
                    verificationError = error
                }
                // A provider that answers "HTTP 429" fails *fast*, so it never reaches the stall
                // watchdog - and nothing recorded it. Read here too, because a refusal that arrives
                // as the attempt's own error is just as much a statement about the provider.
                error.message?.let { reason ->
                    recordServiceRefusals(listOf("$sourceId failed: $reason"), candidates)
                }
                failures +=
                    "$sourceId@$attemptQuality=" +
                    (error.message?.take(140) ?: error::class.simpleName)
                SpotiFLACDiag.log(
                    "source failed id=$sourceId quality=$attemptQuality " +
                        "type=${error::class.simpleName} msg=${error.message}",
                )
                Timber.tag(TAG).w(error, "Native SpotiFLAC source failed: $sourceId")
                // A failed attempt must not leave a partial file behind for the
                // next source, or for the runtime's already-exists check, to trust.
                runCatching { if (attemptOutputPath.isFile) attemptOutputPath.delete() }
                null
            } finally {
                // Always released, including on cancellation: a key left marked as
                // "being written" would keep the finished file unservable for the rest
                // of the session.
                if (cacheEnabled && recordInCache) playbackCache.endRewrite(trackKey)
            }
        }

        //
        // Parallel mode. Off by default: racing asks every enabled source at once, which is more
        // concurrent work on the runtime, the network and each provider's own rate limits - a trade
        // worth making knowingly, and the reason it is a toggle rather than the only behaviour.
        //
        val parallelFetch = PreferenceStore.get(ParallelSourceFetchKey) ?: false

        /**
         * Moves a winning racer's file onto the canonical cache path and records it there.
         *
         * A racer deliberately writes to its own path and claims nothing, because one cache key is
         * one file: five attempts recording the same key would leave whichever finished last as the
         * track. The winner lands on the canonical path (a rename inside the same directory, so no
         * second copy of the track is left behind) and only then is it recorded.
         */
        fun commitRaceWinner(winner: ResolvedFile): ResolvedFile {
            if (!cacheEnabled) return winner
            val landed = landOnCanonicalPath(winner.file)
            playbackCache.record(
                trackKey = trackKey,
                file = landed,
                sourceId = winner.sourceId,
                codec = winner.codec,
                bitDepth = winner.bitDepth,
                sampleRate = winner.sampleRate,
                mediaId = mediaId,
                qualityBucket = SpotiFLACPlaybackCache.qualityBucket(quality),
            )
            playbackCache.associateMediaId(mediaId, trackKey)
            return winner.copy(file = landed, trackKey = trackKey)
        }

        /**
         * Every candidate asked at once; the first one that answers wins.
         *
         * The wait becomes the fastest provider instead of the sum of the ones ahead of it, which
         * is the whole point: on a sweep whose providers each need several seconds, serial order
         * makes the listener pay for every provider before the one that can serve the track.
         *
         * Returns null when every source failed, having recorded each failure exactly as the serial
         * walk does, so the retry-at-a-lossy-quality pass below still sees them and still runs.
         */
        suspend fun raceSources(
            sourceIds: List<String>,
            raceQuality: String,
        ): ResolvedFile? = coroutineScope {
            val races =
                sourceIds.map { sourceId ->
                    val racedId = "$itemId-${sourceId.lowercase(java.util.Locale.US)}"
                    val racedPath = File(outputDir, "${outputPath.name}.$racedId")
                    Triple(
                        sourceId,
                        racedPath,
                        async(Dispatchers.IO) {
                            runCatching {
                                attemptSource(
                                    sourceId = sourceId,
                                    attemptQuality = raceQuality,
                                    attemptItemId = racedId,
                                    attemptOutputPath = racedPath,
                                    recordInCache = false,
                                )
                            }
                        },
                    )
                }
            var winner: ResolvedFile? = null
            var winnerId: String? = null
            val pending = races.toMutableList()
            // Awaited in completion order: the first source to produce a playable file ends the
            // race without waiting for the providers still behind it.
            while (pending.isNotEmpty() && winner == null) {
                val settled =
                    select<Pair<String, Result<ResolvedFile?>>> {
                        pending.forEach { (sourceId, _, attempt) ->
                            attempt.onAwait { sourceId to it }
                        }
                    }
                pending.removeAll { it.first == settled.first }
                winner = settled.second.getOrNull()
                if (winner != null) winnerId = settled.first
            }

            // The losers are stopped on both sides. Cancelling the coroutine unwinds the
            // watchdog, which is what tells the runtime to drop the item; the explicit cancel and
            // the partial-file cleanup cover an attempt that had already finished failing.
            races.forEach { (sourceId, racedPath, attempt) ->
                if (winnerId != null && sourceId != winnerId) {
                    attempt.cancel()
                    runCatching {
                        invokeVoid(
                            requireBackend(),
                            "cancelDownload",
                            "$itemId-${sourceId.lowercase(java.util.Locale.US)}",
                        )
                    }
                    runCatching { if (racedPath.isFile) racedPath.delete() }
                }
            }

            val file = winner ?: return@coroutineScope null
            SpotiFLACDiag.log("parallel race won by $winnerId of ${sourceIds.size} sources")
            winnerId?.let { clearProviderUnavailable(it) }
            commitRaceWinner(file)
        }

        // A race has no order - the fastest source wins - so it is confined to the part of the walk
        // that is all runtime sources: up to the first module the user put in the list. A module at
        // the top of their list is still asked first, whatever this switch says. The rule itself is
        // SpotiFLACCandidateOrder.raceable, where its two boundaries - never a module or addon, never
        // anything outside the runtime's own candidates, and therefore never the other engine - are
        // read and pinned rather than living inside this call.
        val raceable =
            if (parallelFetch) {
                SpotiFLACCandidateOrder.raceable(
                    walk = plan.walk,
                    addonIds = plan.addons.keys,
                    nativeIds = candidates,
                )
            } else {
                emptyList()
            }
        if (raceable.size > 1) {
            SpotiFLACDiag.log(
                "parallel fetch is on: ${raceable.size} source(s) race, so the fastest wins rather " +
                    "than the first in the Audio Sources list",
            )
            raceSources(raceable, quality)?.let {
                // The race winner is the audio that will play, so the sweep's line has no more to
                // say about this track.
                clearDownloadProgress(mediaId)
                return it
            }
        }

        // The walk is exactly [ResolvePlan.walk] - the user's Audio Sources order, with each module
        // and addon at the place they put it. It is walked by index and never reordered: an earlier
        // build moved a source this sweep had watched go quiet behind the ones it had not reached
        // yet, which meant the next track started from a source the user had ranked lower. The list
        // is the only thing that decides order; a source that cannot answer is left out of the plan
        // rather than moved around in it.
        var next = 0
        try {
            while (next < plan.walk.size) {
                val sourceId = plan.walk[next]
                val addon = plan.addons[sourceId.trim().lowercase(java.util.Locale.US)]
                if (addon != null) {
                    // A module or server addon at this position. It is a source like any other: the
                    // user's ordering decides when it is asked, and only its own answer decides
                    // whether the walk goes on.
                    attemptedAddons += addon.id
                    // What this addon's record said before the attempt, so the reason below is this
                    // attempt's answer and not a refusal from a track ago.
                    val refusalBefore = unavailableSources[addon.id]
                    resolveViaAddon(
                        source = addon,
                        mediaId = mediaId,
                        trackKey = trackKey,
                        cacheEnabled = cacheEnabled,
                        title = title,
                        artist = artist,
                        durationMs = durationMs,
                        quality = quality,
                        attemptIndex = next + 1,
                        attemptTotal = plan.walk.size,
                    )?.let { return it }
                    // The addon's own words when it refused, instead of "no audio": a service that
                    // answered `403 (Unavailable)` never looked at this track, and this aggregate is
                    // what the player's line and the sweep's verdict are read from - so calling a
                    // refusal a failure to produce audio is how the player came to say "No source has
                    // this track · Tido" about a source that had refused every request.
                    val refusal = unavailableSources[addon.id]?.takeIf { it !== refusalBefore }
                    failures +=
                        if (refusal != null) "${addon.id}=${refusal.reason}" else "${addon.id}=no audio"
                    SpotiFLACDiag.log("module source=${addon.id} could not serve this track")
                    next++
                    if (!tryNextSource) break
                    continue
                }
                // A source that just produced a file is not unavailable, whatever it said about
                // itself earlier - so the demotion (and the row line that reads it) ends here
                // instead of waiting its cooldown out.
                // Ask the source, and ask it again while the failure is the kind a retry can fix.
                // This is the user's per-source setting (three attempts by default): a stall or a
                // dropped socket is a statement about *this* attempt, so a fresh request is worth
                // making - while a catalogue miss or a quality refusal is an answer, and repeating
                // it would only spend the listener's time. Measured before this existed: a source
                // that stalled at 10% or 80% of a download was abandoned on the first try and the
                // walk moved on, although the bytes had been arriving.
                val sourcePolicy = fetchPolicyFor(sourceId)
                var attemptNumber = 1
                var served: ResolvedFile? = null
                while (served == null) {
                    lastAttemptError = null
                    served =
                        attemptSource(
                            sourceId = sourceId,
                            attemptQuality = quality,
                            attemptIndex = next + 1,
                            attemptTotal = plan.walk.size,
                        )
                    if (served != null) break
                    if (attemptNumber >= sourcePolicy.maxAttempts) break
                    if (!SpotiFLACFetchRetry.isTransient(lastAttemptError)) {
                        SpotiFLACDiag.log(
                            "source $sourceId answered for this track; not retrying " +
                                "(attempts=$attemptNumber/${sourcePolicy.maxAttempts})",
                        )
                        break
                    }
                    attemptNumber++
                    val waitMs = SpotiFLACFetchRetry.backoffMs(attemptNumber)
                    SpotiFLACDiag.log(
                        "retrying source $sourceId in ${waitMs}ms " +
                            "(attempt $attemptNumber/${sourcePolicy.maxAttempts}, " +
                            "last=${lastAttemptError?.message?.take(120) ?: "-"})",
                    )
                    delay(waitMs)
                }
                served?.let {
                    clearProviderUnavailable(sourceId)
                    return it
                }
                // A source that answered "no compatible lossless quality" was never actually
                // asked: the request itself was impossible for it, so it cannot count as tried.
                // Retrying that same source at a lossy quality is what finally gives the YT
                // Music extension - which serves lossy only - a real chance, and it is why
                // switching one source off no longer looks like the whole chain giving up.
                val lastFailure = failures.lastOrNull()
                // The provider just failed this track. Before treating that as the track's problem,
                // ask the provider itself: an extension whose health says it cannot serve explains
                // the failure in a way the sweep could never learn from its own errors, and the
                // answer is what keeps the next track from paying for it again - by leaving that
                // source out of the plan while its own health says so, never by moving it around in
                // the user's order.
                noteProviderHealth(sourceId)
                val retryQuality = retryTokens[sourceId]
                // Two ways a source was never really asked for this track: it cannot deliver the
                // quality, or it answered with audio this app cannot play (a Dolby track where FLAC
                // was requested). Both are retried at the source's own lossy option, because that is
                // a question it can actually answer.
                val retryable = lastFailure != null &&
                    (SpotiFLACQualityCascade.isQualityLimited(lastFailure) ||
                        SpotiFLACQualityCascade.isUnplayableFormat(lastFailure))
                if (retryQuality != null &&
                    lastFailure != null &&
                    lastFailure.startsWith("$sourceId@$quality=") &&
                    retryable
                ) {
                    SpotiFLACDiag.log(
                        "source $sourceId cannot serve $quality; retrying it at its own " +
                            "lossy option $retryQuality",
                    )
                    attemptSource(sourceId, retryQuality)?.let { return it }
                }
                next++
                if (!tryNextSource) break
            }
        } catch (cancelled: CancellationException) {
            // The sweep's clock ran out with the walk unfinished. Naming what was *not* asked is
            // the whole difference between the two readings of this line: sources left in the list
            // look exactly like sources that had nothing, and the entries here are the ones the
            // user's own order never reached. See SpotiFLACSweepBudget, which is the clock that
            // decides this. Cancellation is always re-thrown - it is what keeps a superseded or
            // timed-out sweep from arriving at its caller as a finished one.
            val cutOffOn = plan.walk.getOrNull(next)
            SpotiFLACDiag.log(
                if (cutOffOn == null) {
                    "walk cancelled after ${plan.walk.size}/${plan.walk.size}: every source was asked"
                } else {
                    "walk cancelled at ${next + 1}/${plan.walk.size} on $cutOffOn: never asked=" +
                        plan.walk.drop(next + 1).joinToString(",").ifEmpty { "none" }
                },
            )
            throw cancelled
        }
        SpotiFLACDiag.log("all sources failed: ${failures.joinToString("; ")}")
        // The sweep is over. Every source's attempt is announced as a resolving sample (see
        // [publishAttempt]) and nothing replaces it between attempts, so the line would otherwise
        // stay on "Fetching from <the last source>…" over a track nothing could serve.
        clearDownloadProgress(mediaId)
        // Only ask for verification when nothing could play at all: surfacing
        // it earlier aborted resolves while a usable source was still untried.
        verificationError?.let { throw it }
        // A sweep that came up empty may have been refused by the gateway for the whole client, and
        // nothing in the sweep can say so: measured on a blocked device, the providers failed with
        // their own errors - a 404, an invalid ASIN, a metadata stall - because what the block kills
        // is the extensions' own calls, and not one of them reported a 429. So one cheap probe, asked
        // only now that the sweep has already paid for itself, says whether the cause was the client.
        // When it was, this is the failure that is reported, and no further sweep starts until the
        // wait is over.
        SpotiFLACSessionRenewer.probeRelayBlock(context, reason = "sweep-failed")?.let { block ->
            throw SpotiFLACRelayBlockedException(block.remainingMs, block.reason)
        }
        // The aggregate is what the caller classifies a sweep by - whether every source
        // answered "not in my catalogue" (a verdict worth remembering) or the sweep was
        // blocked (transport, session, rate limit). The last source's error alone cannot
        // say that, and throwing it alone is what let one flaky provider be read as a
        // catalogue answer. The cause chain is preserved for error classification.
        throw SpotiFLACException(
            "All SpotiFLAC sources failed (${failures.joinToString("; ")})",
            lastError,
        )
    }

    private data class PreparedPackage(
        val outputDir: File,
        val outputPath: File,
    )

    private suspend fun downloadViaExtension(
        backend: Class<*>,
        sourceId: String,
        source: ExtensionSource?,
        itemId: String,
        mediaId: String?,
        outputDir: File,
        outputPath: File,
        requestedQuality: String,
        /** The sweep's candidates, so a refusal can only ever name a provider in the chain. */
        knownProviders: Collection<String>,
        /** Where this attempt sits in the walk, carried onto every sample the meter publishes. */
        attemptIndex: Int = 0,
        attemptTotal: Int = 0,
        buildJson: (PreparedPackage) -> String,
    ): ResolvedFile {
        ensurePackageLoaded(backend, sourceId, source)
        // Go writes the container it downloads (e.g. .flac) at outputPath; the
        // exact extension is reported in the response and plays as-is.
        val requestJson = buildJson(PreparedPackage(outputDir, outputPath))
        Timber.tag(TAG).i("Native download start: source=$sourceId")
        SpotiFLACDiag.log("download start id=$sourceId out=${outputPath.name} itemId=$itemId")
        // downloadByStrategy is a blocking native call with no timeout of its own, so
        // live progress is mirrored from the runtime's own per-item transfer meter and
        // the watchdog abandons this provider if that meter goes quiet. Without it one
        // wedged provider spends the sweep's whole budget (8 providers share one
        // timeout), and the providers after it are never asked.
        // The window this attempt gets before its first byte is the one this device has measured
        // this provider needing - not a constant. The fixed 6s window killed working providers
        // here (measured: deezer's own extension call at 6036ms, abandoned at 6009ms of silence),
        // and because every provider measured about the same the whole sweep failed.
        ensureTimingsLoaded()
        // The windows this source is judged by are the ones the user set for it, not constants in
        // this file: 5s to connect and 60s without data to finish a download by default. Read once
        // per attempt, so a change lands on the next track rather than at the next launch.
        val fetchPolicy = fetchPolicyFor(sourceId)
        SpotiFLACDiag.log(
            "attempt windows id=$sourceId connect=${fetchPolicy.connectTimeoutMs}ms " +
                "download=${fetchPolicy.downloadTimeoutMs}ms attempts=${fetchPolicy.maxAttempts}",
        )
        val stallMeter = ProviderStallMeter(policy = fetchPolicy)
        val responseText =
            try {
                withProgressTracking(
                    itemId = itemId,
                    mediaId = mediaId,
                    sourceId = sourceId,
                    stallMeter = stallMeter,
                    attemptIndex = attemptIndex,
                    attemptTotal = attemptTotal,
                ) {
                    withProviderStallWatchdog(backend, itemId, sourceId, stallMeter) {
                        invokeString(backend, "downloadByStrategy", requestJson)
                    }
                }
            } catch (stalled: SpotiFLACProviderStalledException) {
                // The runtime's own account of the attempt is captured at the moment it is given
                // up on, because the stall report says only *that* it stopped and which stage it
                // stopped in - never why. That gap is the whole question for a provider that
                // abandons every track at the same stage (measured: qobuz-web at
                // `resolving_stream`, every track tried), and the runtime log holds the reason.
                // It is also where the *provider* comes from: the runtime walks the whole
                // candidate list itself, so the source this attempt was requested with is
                // routinely not the one that went quiet (see [SpotiFLACRuntimeWalk]).
                val runtimeMessages = dumpRuntimeLogs("stall-$sourceId")
                recordServiceRefusals(runtimeMessages, knownProviders)
                val walkedTo = SpotiFLACRuntimeWalk.providerOf(runtimeMessages)
                val stalledSource = walkedTo ?: sourceId
                recentlyStalledSources[stalledSource] = System.currentTimeMillis()
                persistStalls()
                val walkedElsewhere =
                    if (walkedTo != null && walkedTo != sourceId) " (walk had reached $walkedTo)" else ""
                SpotiFLACDiag.log(
                    "provider stalled id=$sourceId$walkedElsewhere reason=${stalled.reason} " +
                        "stage=${stalled.stage ?: "-"} " +
                        "silent=${stalled.stalledMillis}ms - abandoned, trying next provider",
                )
                throw stalled
            }
        val response = json.parseToJsonElement(responseText).jsonObject
        val success = response["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val alreadyExists = response["already_exists"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val error = response["error"]?.jsonPrimitive?.content.orEmpty()
        val errorType = response["error_type"]?.jsonPrimitive?.content.orEmpty()
        if (!success) {
            SpotiFLACDiag.log("download failed id=$sourceId errorType=$errorType error=$error")
            if (errorType.equals("verification_required", ignoreCase = true)) {
                dumpRuntimeLogs("verify-$sourceId")
            }
            if (errorType.equals("verification_required", ignoreCase = true) ||
                error.contains("Verification required", ignoreCase = true)
            ) {
                // The fallback pipeline reports which provider actually needs the
                // signed session ("extension 'amazon' needs ..."), which can differ
                // from the source being attempted. Verifying the reported extension
                // is what actually unblocks the download.
                throw SpotiFLACVerificationRequiredException(
                    verificationBlockedExtension(error) ?: sourceId,
                    error,
                )
            }
            throw SpotiFLACException(
                error.ifBlank { "Native SpotiFLAC download failed for $sourceId" },
            )
        }
        val path = response["file_path"]?.jsonPrimitive?.content.orEmpty()
        var file = File(path.ifBlank { outputPath.absolutePath })
        require(file.isFile && file.length() > 0L) {
            "SpotiFLAC runtime returned no playable file for $sourceId"
        }
        // A provider that delivers an encrypted stream delivers the key to it as well (Amazon's
        // `ffmpeg.mov_key` contract). The runtime forwards both fields and decrypts nothing, so
        // the decryption the provider asked for happens here - before anything judges the bytes,
        // and before the file is recorded as this track's copy.
        SpotiFLACDecryptionContract.parse(response)?.let { contract ->
            file = applyDownloadDecryption(file, contract, sourceId)
        }
        val bitDepth = response["actual_bit_depth"]?.jsonPrimitive?.content?.toIntOrNull()
        val sampleRate = response["actual_sample_rate"]?.jsonPrimitive?.content?.toIntOrNull()
        val reportedCodec = response["audio_codec"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
        // A download of the right size can still be audio nothing can play. Amazon's extension
        // offers Dolby Digital Plus and Atmos beside its lossless FLAC, and a Dolby track is silent
        // on a device with no AC-3/AC-4 output path: playback starts, the position advances, and no
        // sound comes out. Every line Hush used to write called that a success, because nothing
        // looked at the answer - so the answer is checked here, and an unplayable one is treated as
        // this source failing instead of as the track playing.
        val probe = SpotiFLACFileIntegrity.readProbe(file)

        val container = probe?.let { SpotiFLACFileIntegrity.containerOf(it) }
        val dolby = probe?.let { SpotiFLACFileIntegrity.dolbyFormatOf(it) }
            ?: SpotiFLACFileIntegrity.dolbyFormatOfCodecName(reportedCodec)
        // Amazon hands the runtime an encrypted stream and a key; if that decryption did not
        // happen the payload is the right size and silently has no audio, which is the exact
        // failure the listener reported. It is named here rather than played.
        val encrypted = probe?.let { SpotiFLACFileIntegrity.isEncryptedStream(it) } == true
        if (dolby != null || encrypted || container == null) {
            val detail = when {
                encrypted -> "the stream is still encrypted, and no usable key accompanied it " +
                    "(Amazon delivers it encrypted; the decryption is the host's step)"
                dolby != null -> "Dolby $dolby, which has no output path on this device"
                else -> "no recognisable audio container (head=" +
                    (probe?.take(8)?.joinToString("") { "%02x".format(it) } ?: "unreadable") + ")"
            }
            SpotiFLACDiag.log(
                "download unusable id=$sourceId $detail requested=$requestedQuality " +
                    "codec=${reportedCodec ?: "-"} file=${file.name} bytes=${file.length()}",
            )
            throw SpotiFLACException(
                "provider '$sourceId' ${SpotiFLACQualityCascade.UNPLAYABLE_FORMAT_MARKER} " +
                    "($detail; requested quality was $requestedQuality)",
            )
        }
        // A lossless payload from a DASH provider arrives as FLAC inside a fragmented MP4:
        // a `moov` with an empty sample table and a run of `moof`/`mdat` pairs. Nothing in it
        // is an index, so the player can decode it but cannot seek - a seek is snapped back to
        // the first sample and the song starts again. Splicing the frames into a native `.flac`
        // fixes that at the only place it can be fixed, and costs one pass over bytes that
        // were just written. It is a no-op for every payload that is not that shape.
        file = SpotiFLACMovKeyDecryptor.spliceNativeFlac(file, SpotiFLACDiag::log) ?: file
        SpotiFLACDiag.log(
            "download ok id=$sourceId file=${file.name} bytes=${file.length()} alreadyExists=$alreadyExists " +
                "container=$container codec=${reportedCodec ?: "-"} bits=$bitDepth rate=$sampleRate",
        )
        _downloadProgress.value =
            PlaybackDownloadProgress(
                mediaId = mediaId.orEmpty(),
                sourceId = sourceId,
                sourceLabel = repositoryManager.sourceLabel(sourceId),
                percent = 100,
                bytesReceived = file.length(),
                bytesTotal = file.length(),
                status = "completed",
            )
        Timber.tag(TAG).i(
            "Native download ok: source=$sourceId file=${file.name} bytes=${file.length()} " +
                "codec=${response["audio_codec"]?.jsonPrimitive?.content} bits=$bitDepth rate=$sampleRate",
        )
        // Only a finished, playable download teaches the window anything: this is the resolution
        // cost that the next attempt for this provider has to be allowed to spend. Recorded against
        // the *requested* source, because that is the id whose window is looked up next time it is
        // asked - the runtime walks the candidate list itself, so the serving source can be another
        // one (see [SpotiFLACRuntimeWalk]).
        stallMeter.timeToFirstByteMs()?.let { firstByteMs ->
            providerTimings.record(sourceId, firstByteMs)
            persistTimings()
            SpotiFLACDiag.log(
                "provider timing id=$sourceId firstByte=${firstByteMs}ms " +
                    "connect=${fetchPolicy.connectTimeoutMs}ms download=${fetchPolicy.downloadTimeoutMs}ms",
            )
        }
        return ResolvedFile(
            file = file,
            sourceId = response["service"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: sourceId,
            title = response["title"]?.jsonPrimitive?.content,
            artist = response["artist"]?.jsonPrimitive?.content,
            quality = response["quality"]?.jsonPrimitive?.content,
            codec = response["audio_codec"]?.jsonPrimitive?.content
                ?: response["actual_extension"]?.jsonPrimitive?.content,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
        )
    }

    /**
     * Carries out a provider's decryption contract on a finished download.
     *
     * A contract this app cannot act on is *reported*, not guessed at: the bytes are then judged
     * by the integrity check below, which names an unusable stream rather than playing silence.
     * A contract that is acted on and fails is this source failing, so the sweep moves to the next
     * provider instead of handing the listener a track that cannot play.
     *
     * The file that comes back may have a different name and extension than the one the runtime
     * wrote: the lossless FLAC case is a container that has to become a real `.flac`, and the
     * provider's own `output_extension` governs the rest.
     */
    private fun applyDownloadDecryption(
        file: File,
        contract: SpotiFLACDecryptionContract,
        sourceId: String,
    ): File {
        if (!contract.isSupported) {
            SpotiFLACDiag.log(
                "decryption not usable id=$sourceId strategy=${contract.strategy.ifBlank { "-" }} " +
                    "format=${contract.inputFormat.ifBlank { "-" }}",
            )
            return file
        }
        return try {
            val decrypted = SpotiFLACMovKeyDecryptor.decrypt(
                source = file,
                keyHex = contract.keyHex,
                outputExtension = contract.outputExtension,
                log = { line -> SpotiFLACDiag.log(line) },
            )
            SpotiFLACDiag.log(
                "decryption ok id=$sourceId strategy=${contract.strategy} output=${decrypted.output} " +
                    "format=${decrypted.originalFormat} fragments=${decrypted.fragmentsDecrypted} " +
                    "samples=${decrypted.samplesDecrypted} bytes=${decrypted.bytesDecrypted} " +
                    "file=${decrypted.file.name}",
            )
            decrypted.file
        } catch (error: SpotiFLACMovKeyDecryptor.DecryptionFailedException) {
            SpotiFLACDiag.log("decryption failed id=$sourceId reason=${error.message}")
            Timber.tag(TAG).w(error, "SpotiFLAC decryption failed for $sourceId")
            throw SpotiFLACException(
                "provider '$sourceId' ${SpotiFLACQualityCascade.UNPLAYABLE_FORMAT_MARKER} " +
                    "(the stream is encrypted and could not be decrypted: ${error.message})",
            )
        }
    }

    /**
     * Scope for provider attempts the watchdog may have to abandon without joining.
     *
     * Deliberately detached from the caller. The work inside is blocking native code
     * that cannot be cancelled, so joining it - as `coroutineScope`/`async` would -
     * makes the watchdog wait for the very call it is trying to give up on.
     */
    private val providerAttemptScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Runs one provider attempt under a stall watchdog.
     *
     * Abandoning a wedged provider means two things: stop waiting for it here, and ask
     * the runtime to cancel the item (`cancelDownload`) so its own download loop unwinds
     * instead of holding a thread and a socket for the rest of the session.
     *
     * Deliberately not `withTimeout`: that throws `TimeoutCancellationException`, and the
     * per-source loop re-throws cancellation to abort a superseded sweep - so a slow
     * provider would cancel every provider behind it and the sweep would end with no
     * answer at all. This raises an ordinary failure instead, which is recorded against
     * the one provider and lets the loop continue.
     */
    private suspend fun <T> withProviderStallWatchdog(
        backend: Class<*>,
        itemId: String,
        sourceId: String,
        meter: ProviderStallMeter,
        block: suspend () -> T,
    ): T {
        val attempt = providerAttemptScope.async { block() }
        // The reason is captured at the moment it fires rather than re-derived after the
        // loop: the progress reporter is still running, so a late sample could otherwise
        // turn an already-decided ceiling abort into a stall with a 0 ms silence.
        var abandoned: ProviderAbandonReason? = null
        try {
            while (!attempt.isCompleted && abandoned == null) {
                abandoned = meter.abandonReason()
                if (abandoned == null) delay(SpotiFLACProviderStallPolicy.POLL_MS)
            }
        } catch (cancellation: CancellationException) {
            // The sweep was superseded (a skip, a seek, a duplicate in-flight resolve).
            // The attempt is still running native code, so tell the runtime to drop it
            // rather than leaving a download alive for a request nobody is waiting for.
            abortAttempt(backend, itemId, sourceId, "superseded")
            attempt.cancel()
            throw cancellation
        }
        if (attempt.isCompleted) return attempt.await()

        val reason = abandoned ?: ProviderAbandonReason.STALLED
        // The reported number has to match the trigger: the ceiling means "no bytes moved
        // for the whole ceiling", which is far longer than the event silence the stall
        // branch measures. Reporting the short one produced lines like "timed out without
        // progress for 29ms" for an attempt that was downloading normally.
        val silentFor =
            if (reason == ProviderAbandonReason.CEILING) {
                meter.transferSilenceMillis()
            } else {
                meter.stalledMillis()
            }
        abortAttempt(backend, itemId, sourceId, "no progress for ${silentFor}ms")
        attempt.cancel()
        throw SpotiFLACProviderStalledException(
            sourceId = sourceId,
            stage = meter.lastStage,
            stalledMillis = silentFor,
            reason = reason,
        )
    }

    /** Best-effort request for the runtime to unwind an attempt we have stopped waiting on. */
    private fun abortAttempt(
        backend: Class<*>,
        itemId: String,
        sourceId: String,
        reason: String,
    ) {
        SpotiFLACDiag.log("provider attempt aborted id=$sourceId itemId=$itemId ($reason)")
        runCatching { invokeVoid(backend, "cancelDownload", itemId) }
            .onFailure { Timber.tag(TAG).w(it, "cancelDownload failed for $sourceId") }
    }

    private fun requireBackend(): Class<*> {
        backendClass?.let { return it }
        val loaded = runCatching { Class.forName(GOBACKEND_CLASS) }.getOrElse { error ->
            val reason = "SpotiFLAC runtime AAR is not bundled: ${error.message}"
            unavailableReason = reason
            throw SpotiFLACException(reason)
        }
        backendClass = loaded
        return loaded
    }

    private fun initializeRuntime(backend: Class<*>) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val extensionsDir = File(context.filesDir, "spotiflac/extensions").apply { mkdirs() }
            val dataDir = File(context.filesDir, "spotiflac/extension_data").apply { mkdirs() }
            invokeVoid(backend, "setAppVersion", RUNTIME_APP_VERSION)
            invokeVoid(backend, "setLoggingEnabled", true)
            // The extension runtime refuses to load or download anything until its
            // storage master key is installed (upstream passes the keystore-backed
            // key before InitExtensionSystem). Without this, every resolve fails
            // with "extension storage master key is not configured".
            runCatching {
                invokeVoid(
                    backend,
                    "setExtensionStorageMasterKey",
                    SpotiFLACExtensionKeyStore.masterKeyBase64(context),
                )
            }.onFailure {
                SpotiFLACDiag.log("setExtensionStorageMasterKey failed: ${it.message}")
                Timber.tag(TAG).w(it, "setExtensionStorageMasterKey failed (non-fatal)")
            }
            // Seed install IDs BEFORE InitExtensionSystem so any signed session the
            // runtime provisions belongs to Hush's gateway install identity, matching
            // MainActivity.prepareRuntimeState() upstream (payload {v,d,s}).
            runCatching { invokeVoid(backend, "setRuntimeState", buildRuntimeStatePayload(dataDir)) }
                .onFailure { Timber.tag(TAG).w(it, "setRuntimeState failed (non-fatal)") }
            invokeString(backend, "initExtensionSystem", extensionsDir.absolutePath, dataDir.absolutePath)
            // Load extensions extracted by earlier runs before any playback path
            // asks for them, mirroring upstream's startup sequence.
            runCatching { invokeString(backend, "loadExtensionsFromDir", extensionsDir.absolutePath) }
                .onSuccess { report ->
                    SpotiFLACDiag.log("loadExtensionsFromDir: ${summariseExtensionLoad(report)}")
                }
                .onFailure { SpotiFLACDiag.log("loadExtensionsFromDir failed: ${it.message}") }
            initialized = true
        }
    }

    /**
     * One line for what the runtime reported when it loaded the extensions directory.
     *
     * The runtime lists an extension it *skipped* because that exact version was already loaded in
     * the same `errors` array it uses for real failures. Logged raw, every start wrote a blob whose
     * first entries were "already installed" - truncated mid-word at 200 characters - and the one
     * place a head-unit user can look for a failure therefore opened with something that looked like
     * one. Skips are counted; anything else is still shown, and never truncated away.
     *
     * Falls back to the raw text for a payload this does not recognise, so an unfamiliar shape is
     * still visible rather than summarised into silence.
     */
    internal fun summariseExtensionLoad(report: String): String {
        val trimmed = report.trim()
        if (trimmed.isEmpty()) return "ok"
        val payload = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return trimmed.take(MAX_LOAD_REPORT)
        val errors = (payload["errors"] as? JsonArray)
            ?.map { element -> element.jsonPrimitive.contentOrNull ?: element.toString() }
            .orEmpty()
        val skipped = errors.filter { it.contains(ALREADY_INSTALLED, ignoreCase = true) }
        val failures = errors.filterNot { it.contains(ALREADY_INSTALLED, ignoreCase = true) }
        val loaded = (payload["loaded"] as? JsonArray)?.size
        return buildString {
            if (loaded != null) append("loaded=").append(loaded).append(' ')
            append("skipped=").append(skipped.size)
            if (failures.isEmpty()) {
                append(" errors=0")
            } else {
                append(" errors=").append(failures.size).append(": ")
                    .append(failures.joinToString("; ").take(MAX_LOAD_REPORT))
            }
        }
    }

    /**
     * Mirrors upstream's prepareRuntimeState: v1 payload with a default 32-hex
     * install id. Hush's own install id is used when present so the runtime and
     * the app share one gateway identity; otherwise a stable device-derived id.
     */
    private fun buildRuntimeStatePayload(dataDir: File): String {
        val existing = SpotiFLACInstallIdentity.installIdForRuntime(context)
        val defaultId = existing ?: run {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            )?.trim().orEmpty()
            val source = if (androidId.isNotEmpty()) "hush/runtime/v1:$androidId" else {
                val fallbackFile = File(context.noBackupFilesDir, "spotiflac_rs_v1")
                val stored = fallbackFile.takeIf(File::isFile)?.readText()?.trim()
                    ?.takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }
                stored ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it) }
                    .also { value ->
                        runCatching {
                            fallbackFile.parentFile?.mkdirs()
                            fallbackFile.writeText(value)
                        }
                    }
            }
            sha256Hex(source)
        }
        return buildJsonObject {
            put("v", 1)
            put("d", defaultId)
            put("s", buildJsonObject {})
        }.toString()
    }

    private suspend fun ensurePackageLoaded(
        backend: Class<*>,
        sourceId: String,
        source: ExtensionSource?,
    ) {
        // A package used to be downloaded only when it was *missing*, so a source whose registry
        // published a newer build kept the old one for the life of the install. The registry
        // version is the only thing that can say a package on disk is stale, so it is consulted
        // here - before the "package is present" shortcut below can skip it entirely. A refresh
        // that fails never fails the source: the package already on disk still works.
        val upgraded = runCatching { refreshExtensionPackageIfStale(backend, sourceId, source) }
            .onFailure { error ->
                SpotiFLACDiag.log("extension refresh skipped id=$sourceId msg=${error.message}")
            }
            .getOrNull()
            ?.upgraded == true
        val file = packageStore.packageFile(sourceId)
        if (!file.isFile && source != null && source.isRuntimeCompatible) {
            val installed = packageStore.downloadAndVerify(source).getOrThrow()
            // Which registry published a digest and which did not, said where an install happens:
            // the difference is not visible in the file that lands, and it is the one thing a user
            // cannot check for themselves.
            SpotiFLACDiag.log(
                "extension package installed id=$sourceId version=${installed.version} " +
                    "digest=" +
                    if (installed.pinned) "matched the one its registry published" else {
                        "not published by its registry (${installed.sha256.take(12)}…); structure verified"
                    },
            )
        }
        require(file.isFile) {
            "SpotiFLAC extension package is unavailable: $sourceId"
        }
        // The runtime refuses to re-install an extension whose directory already
        // exists ("extension directory already exists"), and errors when the same
        // version is loaded twice. Upstream loads already-extracted extensions at
        // startup and only installs by path for genuinely new packages, so skip
        // the load whenever the runtime already reports the extension.
        if (!isExtensionInstalled(backend, sourceId)) {
            val result = invokeString(backend, "loadExtensionFromPath", file.absolutePath)
            SpotiFLACDiag.log("loaded extension $sourceId: ${result.take(160)}")
            Timber.tag(TAG).d("Loaded SpotiFLAC extension $sourceId: ${result.take(200)}")
        }
        runCatching { invokeVoid(backend, "setExtensionEnabledByID", sourceId, true) }
    }

    /** What one package refresh did, so callers can say it rather than guess it. */
    private data class PackageRefresh(
        val action: SpotiFLACExtensionUpgrade.Action,
        val previousVersion: String?,
        val currentVersion: String?,
        val upgraded: Boolean,
    ) {
        /** True when anything about this install changed, on disk or in the runtime. */
        val changed: Boolean get() = upgraded || previousVersion != currentVersion
    }

    /**
     * One tap from Audio Sources: bring this source's extension package up to its registry build.
     *
     * The same path playback uses, only forced past the once-per-version memo - a user who taps
     * Update is asking again on purpose, and a memo that answered for them would make the button
     * look broken. Returns a one-line result for the row that asked.
     */
    suspend fun updateExtensionPackage(sourceId: String): Result<String> = withContext(Dispatchers.IO) {
        SpotiFLACDiag.log("extension update requested id=$sourceId")
        runCatching {
            val backend = requireBackend()
            initializeRuntime(backend)
            val source = repositoryManager.getSourceForId(sourceId)
            if (source == null || !source.isRuntimeCompatible) {
                return@runCatching "$sourceId has no installable package in the registry"
            }
            val refresh = refreshExtensionPackageIfStale(backend, sourceId, source, force = true)
                ?: return@runCatching "$sourceId is already on the registry build"
            // An upgrade can re-derive the name of a session record, because the name hashes the
            // scope a manifest declares and that includes the extension's own app version. What makes
            // the verification a source already passed reachable under the new name is the vault
            // ([SpotiFLACSessionRenewer.restoreFromVault]), which is where a verified session is kept
            // for exactly this; nothing here has to know the record's name.
            val before = refresh.previousVersion ?: "not installed"
            val after = refresh.currentVersion ?: "not installed"
            checkStore.record(
                listOf(
                    SpotiFLACExtensionCheck(
                        sourceId = sourceId,
                        registry = SpotiFLACExtensionCheckReport.registryLabel(source.repositoryId),
                        checkedAtMs = System.currentTimeMillis(),
                        outcome =
                            if (refresh.previousVersion != refresh.currentVersion) {
                                SpotiFLACExtensionCheck.Outcome.UPDATED
                            } else {
                                SpotiFLACExtensionCheck.Outcome.CURRENT
                            },
                        fromVersion = refresh.previousVersion,
                        toVersion = refresh.currentVersion,
                    ),
                ),
            )
            when {
                refresh.previousVersion != refresh.currentVersion -> "Updated $before → $after"
                refresh.upgraded -> "Reloaded $after"
                else -> "Already on $after"
            }
        }.onFailure { error ->
            SpotiFLACDiag.log("extension update failed id=$sourceId msg=${error.message}")
            // A check that failed is still a check: the row says when it was last looked at and why
            // it did not complete, instead of pretending it was never asked.
            checkStore.record(
                listOf(
                    SpotiFLACExtensionCheck(
                        sourceId = sourceId,
                        registry = SpotiFLACExtensionCheckReport.registryLabel(
                            repositoryManager.getSourceForId(sourceId)?.repositoryId,
                        ),
                        checkedAtMs = System.currentTimeMillis(),
                        outcome = SpotiFLACExtensionCheck.Outcome.FAILED,
                        detail = error.message?.take(MAX_TEST_REASON),
                    ),
                ),
            )
        }
    }

    /** The last check recorded for each source, for the Audio Sources rows. */
    suspend fun extensionChecks(): Map<String, SpotiFLACExtensionCheck> =
        withContext(Dispatchers.IO) { checkStore.all() }

    /**
     * Checks every installed extension package against the registry its source came from.
     *
     * `ensurePackageLoaded` only ever looks at the sources a resolve is about, so a package that is
     * behind while nothing plays - a source the user has switched off, or one whose turn simply did
     * not come - would stay behind until its first track. This runs the same refresh across the whole
     * list at startup and reports what moved, so an update is something the user is told about rather
     * than something that only appears in the diagnostic log.
     *
     * Sources with nothing on disk and nothing enabled are left alone: they have never been
     * installed, and downloading for a source that is not in use is not a startup's decision.
     */
    suspend fun reconcileExtensionPackages(
        userInitiated: Boolean = false,
    ): SpotiFLACPackageUpdateSummary =
        withContext(Dispatchers.IO) {
            val installable =
                repositoryManager.sources.value.filter { it.source.isRuntimeCompatible }
            if (installable.isEmpty()) return@withContext SpotiFLACPackageUpdateSummary(0, emptyList())
            val backend = runCatching { requireBackend() }.getOrNull()
                ?: return@withContext SpotiFLACPackageUpdateSummary(0, emptyList())
            runCatching { initializeRuntime(backend) }
                .onFailure { error ->
                    SpotiFLACDiag.log("package sweep skipped (runtime): ${error.message}")
                    return@withContext SpotiFLACPackageUpdateSummary(0, emptyList())
                }
            val updates = mutableListOf<SpotiFLACPackageUpdateSummary.Entry>()
            val failed = mutableListOf<String>()
            val checkRecords = mutableListOf<SpotiFLACExtensionCheck>()
            var checked = 0
            for (state in installable) {
                val source = state.source
                val onDisk = packageStore.packageFile(source.id).isFile
                if (!onDisk && !state.enabled) continue
                // A source that is switched off is left alone by this automatic sweep for the same
                // reason its session is: nothing plays through it, so keeping its package current
                // is a registry request made for a source the user turned off. The per-source
                // Update in Audio Sources still covers every installed package, switched off or
                // not, because that is the one moment the user is asking about that source.
                if (!userInitiated && !state.enabled) continue
                checked++
                val checkedAtMs = System.currentTimeMillis()
                // Recorded from the row's own source, so the line says which registry this install
                // actually follows rather than which one happens to be listed first today.
                val registry = SpotiFLACExtensionCheckReport.registryLabel(source.repositoryId)
                val refresh = runCatching {
                    // A check the user asked for is not answered from memory: the memo says "already
                    // answered" for the same registry version, and a button that returns a
                    // remembered answer instead of looking is a button that lies.
                    refreshExtensionPackageIfStale(
                        backend,
                        source.id,
                        source,
                        force = userInitiated,
                    )
                }.onFailure { error ->
                    // A refused download is not a failed sweep: the package on disk still works, so
                    // the rest of the list is still worth checking. It is recorded, though - "could
                    // not be reached" and "already current" must not come out as the same answer.
                    SpotiFLACDiag.log("package sweep skipped id=${source.id} msg=${error.message}")
                    failed.add(source.id)
                    checkRecords.add(
                        SpotiFLACExtensionCheck(
                            sourceId = source.id,
                            registry = registry,
                            checkedAtMs = checkedAtMs,
                            outcome = SpotiFLACExtensionCheck.Outcome.FAILED,
                            detail = error.message?.take(MAX_TEST_REASON),
                        ),
                    )
                }.getOrNull()
                if (refresh == null) continue
                val changed = refresh.previousVersion != refresh.currentVersion
                if (changed) {
                    updates.add(
                        SpotiFLACPackageUpdateSummary.Entry(
                            sourceId = source.id,
                            displayName = source.displayName,
                            from = refresh.previousVersion,
                            to = refresh.currentVersion,
                        ),
                    )
                }
                checkRecords.add(
                    SpotiFLACExtensionCheck(
                        sourceId = source.id,
                        registry = registry,
                        checkedAtMs = checkedAtMs,
                        outcome =
                            if (changed) {
                                SpotiFLACExtensionCheck.Outcome.UPDATED
                            } else {
                                SpotiFLACExtensionCheck.Outcome.CURRENT
                            },
                        fromVersion = refresh.previousVersion,
                        toVersion = refresh.currentVersion,
                    ),
                )
            }
            checkStore.record(checkRecords)
            val summary = SpotiFLACPackageUpdateSummary(
                checked = checked,
                updates = updates,
                failed = failed,
            )
            SpotiFLACDiag.log(
                buildString {
                    append("package sweep: checked=").append(checked)
                        .append(" updated=").append(updates.size)
                        .append(" failed=").append(failed.size)
                    if (updates.isNotEmpty()) {
                        append(": ")
                        append(
                            updates.joinToString(", ") { entry ->
                                "${entry.sourceId} ${entry.from ?: "none"}->${entry.to ?: "none"}"
                            },
                        )
                    }
                },
            )
            SpotiFLACPackageUpdateLog.record(summary, userInitiated = userInitiated)
            summary
        }

    /**
     * The runtime-loaded version of each source named, for the Audio Sources rows.
     *
     * Read in one pass for the whole list: the runtime's installed list is a single call, and a row
     * that asked per source would pay for it once per row on every refresh of the screen.
     * Returns only the sources it has an answer for, so "unknown" stays distinguishable from "none".
     */
    suspend fun installedExtensionVersions(sourceIds: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val ids = sourceIds.filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return@withContext emptyMap()
            val backend = runCatching { requireBackend() }.getOrNull()
                ?: return@withContext emptyMap()
            val loaded = runCatching { runtimeInstalledVersions(backend) }
                .getOrDefault(emptyMap())
            ids.mapNotNull { id ->
                val version = loaded[id.lowercase(java.util.Locale.US)]
                    ?: extractedManifestVersion(id)
                version?.let { id to it }
            }.toMap()
        }

    /**
     * Bring an installed extension package up to the version its registry publishes.
     *
     * Returns null when there is nothing to decide (no registry entry, no version, or an answer the
     * memo already holds). The swap itself is the runtime's decision: `checkExtensionUpgradeFromPath`
     * compares the package on disk against the version it has installed, so Hush never replaces a
     * loaded extension on its own version arithmetic - it only makes sure the package being compared
     * is the one the registry publishes.
     */
    private suspend fun refreshExtensionPackageIfStale(
        backend: Class<*>,
        sourceId: String,
        source: ExtensionSource?,
        force: Boolean = false,
    ): PackageRefresh? {
        val registryEntry = source?.takeIf { it.isRuntimeCompatible } ?: return null
        val registryVersion = registryEntry.version.trim()
        if (registryVersion.isEmpty()) return null
        val key = sourceId.lowercase(java.util.Locale.US)
        val lock = packageRefreshLocks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            val memo = "$key@${registryVersion.lowercase(java.util.Locale.US)}"
            if (!force && memo in reconciledPackages) return@withLock null
            val file = packageStore.packageFile(sourceId)
            val storedVersion = packageStore.storedPackageVersion(sourceId)
            // Only ask the runtime what it loaded when there is a package to compare it to; the
            // answer costs a runtime round trip and is meaningless without one.
            val installedVersion = if (file.isFile) installedExtensionVersion(backend, sourceId) else null
            val action = SpotiFLACExtensionUpgrade.plan(registryVersion, storedVersion, installedVersion)
            if (action == SpotiFLACExtensionUpgrade.Action.UP_TO_DATE) {
                reconciledPackages.add(memo)
                return@withLock PackageRefresh(
                    action = action,
                    previousVersion = storedVersion,
                    currentVersion = storedVersion,
                    upgraded = false,
                )
            }
            if (action == SpotiFLACExtensionUpgrade.Action.DOWNLOAD) {
                val verified = packageStore.downloadAndVerify(registryEntry).getOrThrow()
                SpotiFLACDiag.log(
                    "extension package updated id=$sourceId " +
                        "version=${storedVersion ?: "none"} -> ${verified.version}",
                )
            } else {
                SpotiFLACDiag.log(
                    "extension package already current on disk id=$sourceId " +
                        "version=${storedVersion.orEmpty()} installed=${installedVersion.orEmpty()}",
                )
            }
            val upgraded = upgradeLoadedExtension(backend, sourceId, file)
            reconciledPackages.add(memo)
            PackageRefresh(
                action = action,
                previousVersion = storedVersion,
                currentVersion = packageStore.storedPackageVersion(sourceId) ?: storedVersion,
                upgraded = upgraded,
            )
        }
    }

    /**
     * Asks the runtime to swap a loaded extension for the package now on disk.
     *
     * The runtime refuses to load the same version twice and owns the comparison, so the upgrade
     * entry point is only called when its own check says an upgrade is available. A package that
     * the runtime has not loaded at all reports `can_upgrade=false`, and the ordinary load path
     * installs it a moment later.
     */
    private fun upgradeLoadedExtension(backend: Class<*>, sourceId: String, file: File): Boolean {
        if (!file.isFile) return false
        val info = runCatching {
            json.parseToJsonElement(
                invokeString(backend, "checkExtensionUpgradeFromPath", file.absolutePath),
            ).jsonObject
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "checkExtensionUpgradeFromPath failed for $sourceId")
        }.getOrNull() ?: return false
        val canUpgrade = info["can_upgrade"]?.jsonPrimitive?.content
            ?.equals("true", ignoreCase = true) == true
        if (!canUpgrade) return false
        val currentVersion = info["current_version"]?.jsonPrimitive?.content.orEmpty()
        val newVersion = info["new_version"]?.jsonPrimitive?.content.orEmpty()
        return runCatching {
            val result = invokeString(backend, "upgradeExtensionFromPath", file.absolutePath)
            SpotiFLACDiag.log(
                "extension upgraded id=$sourceId " +
                    "$currentVersion -> $newVersion result=${result.take(120)}",
            )
            Timber.tag(TAG).d("Upgraded SpotiFLAC extension $sourceId $currentVersion -> $newVersion")
            true
        }.onFailure { error ->
            SpotiFLACDiag.log("extension upgrade failed id=$sourceId msg=${error.message}")
        }.getOrDefault(false)
    }

    /**
     * The version of an extension the runtime has actually loaded, or null if it has none.
     *
     * Read from the runtime's own installed list first, because that is what it will keep serving
     * until it is told to upgrade; the extracted manifest on disk is the fallback, and describes
     * the same build. Neither is the package file itself: a package can be newer than what is
     * loaded, and that difference is precisely the case an upgrade has to catch.
     */
    private fun installedExtensionVersion(backend: Class<*>, sourceId: String): String? =
        runtimeInstalledVersions(backend)[sourceId.lowercase(java.util.Locale.US)]
            ?: extractedManifestVersion(sourceId)

    /** Every version the runtime reports as installed, keyed by lowercased extension id. */
    private fun runtimeInstalledVersions(backend: Class<*>): Map<String, String> {
        val array = runCatching {
            json.parseToJsonElement(invokeString(backend, "getInstalledExtensions")) as? JsonArray
        }.getOrNull() ?: return emptyMap()
        val versions = mutableMapOf<String, String>()
        array.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val id = listOf("id", "name").firstNotNullOfOrNull { field ->
                obj[field]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            } ?: return@forEach
            val version = obj["version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@forEach
            versions[id.lowercase(java.util.Locale.US)] = version
        }
        return versions
    }

    /** The version in an extension's extracted manifest, i.e. what the runtime unpacked last. */
    private fun extractedManifestVersion(sourceId: String): String? = runCatching {
        val manifest = File(
            File(context.filesDir, "spotiflac/extensions"),
            "$sourceId/manifest.json",
        )
        if (!manifest.isFile) return@runCatching null
        json.parseToJsonElement(manifest.readText()).jsonObject["version"]
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Removes signed-session records that hold Hush's own session for an extension
     * that signs with a different app version.
     *
     * Those records were written by an earlier build's seeding (which handed Hush's own session to
     * extensions, and is gone - the gateway binds a session to the app version that minted it, so such
     * a record can never download), but they are indistinguishable from a genuinely verified session
     * by inspection alone - the record carries the extension's app version, not the one the session was
     * minted with. The session id is the tell: a record holding Hush's own session id was seeded from
     * Hush. Clearing them makes the source report "Verification needed" honestly and lets it mint its
     * own correctly bound session instead of 403-ing forever.
     *
     * The id is read from where the old session was stored rather than from a live session, because
     * there is no live session any more - only the value on the devices that were seeded.
     */
    fun purgeForeignSeededSessions(): Int {
        val hushSessionId = SpotiFLACInstallIdentity.legacyRelaySessionId(context) ?: return 0
        if (hushSessionId.isBlank()) return 0
        var purged = 0
        runCatching {
            SpotiFLACSessionRenewer.sessions(context).forEach { session ->
                if (session.appVersion.equals(SpotiFLACInstallIdentity.APP_VERSION, ignoreCase = true)) {
                    return@forEach
                }
                val record = session.recordFile
                if (!record.isFile) return@forEach
                val stored = runCatching { record.readText() }.getOrNull()
                if (SpotiFLACSourceAuth.recordSessionId(stored) != hushSessionId) return@forEach
                if (record.delete()) {
                    purged++
                    SpotiFLACDiag.log(
                        "purged mismatched seeded session for ${session.extensionId} " +
                            "(appVersion=${session.appVersion} holds Hush's session)",
                    )
                }
            }
        }.onFailure { SpotiFLACDiag.log("purge seeded sessions failed: ${it.message}") }
        return purged
    }

    /**
     * Copies the tail of the runtime's own log buffer into the diag log and returns the messages
     * it read.
     *
     * Only useful *after* an attempt: measured on the reporting device, reading the same buffer while
     * an attempt was in flight returned the identical text for its whole duration, and the lines the
     * attempt generated appeared only once it had ended. So the runtime narrates in hindsight, and
     * mid-attempt liveness cannot be read out of it - which is why an attempt is judged by its own
     * stage instead (see [SpotiFLACFetchPolicy]). The runtime reports gateway rejections (HTTP status, error codes) only here, which
     * is otherwise invisible from the app side - and so is the provider its fallback walk was on
     * when an attempt was abandoned, which the caller reads out of the returned messages. See
     * [SpotiFLACRuntimeWalk].
     */
    fun dumpRuntimeLogs(reason: String): List<String> {
        val backend = backendClass ?: return emptyList()
        val messages = mutableListOf<String>()
        runCatching {
            val raw = invokeString(backend, "getLogsSince", 0L)
            if (raw.isBlank()) return@runCatching
            // The runtime answers {"logs":[{level,tag,message,...}]}.
            val entries = runCatching {
                json.parseToJsonElement(raw).jsonObject["logs"] as? kotlinx.serialization.json.JsonArray
            }.getOrNull()
            SpotiFLACDiag.log("runtime logs ($reason):")
            if (entries == null) {
                SpotiFLACDiag.log("  rt| " + raw.take(300))
                messages += raw
                return@runCatching
            }
            entries.takeLast(20).forEach { entry ->
                val obj = entry as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val level = obj["level"]?.jsonPrimitive?.content.orEmpty()
                val tag = obj["tag"]?.jsonPrimitive?.content.orEmpty()
                val message = obj["message"]?.jsonPrimitive?.content.orEmpty()
                SpotiFLACDiag.log("  rt| $level $tag: " + message.take(300))
                messages += "$tag: $message"
            }
        }.onFailure { SpotiFLACDiag.log("dumpRuntimeLogs failed: ${it.message}") }
        return messages
    }

    /** Mirrors the runtime's sanitizeSignedSessionNamespace. */
    private fun sanitizeSessionNamespace(value: String): String {
        val filtered = value.trim().lowercase().filter { ch ->
            (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.'
        }
        return filtered.trim('.', '-', '_')
    }

    /** Reads "extension 'x' needs signed-session verification" out of an error. */
    private fun verificationBlockedExtension(error: String): String? =
        Regex("extension '([^']+)'").find(error)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /**
     * The file holding demoted providers across restarts.
     *
     * Wall-clock times on purpose: the demotion has to mean the same thing after a
     * restart, and [android.os.SystemClock] resets with the process.
     */
    private val stalledSourcesFile: File
        get() = File(File(context.filesDir, "spotiflac").apply { mkdirs() }, "provider_stalls.json")

    /** The file holding measured provider resolution times across restarts. */
    private val providerTimingsFile: File
        get() = File(File(context.filesDir, "spotiflac").apply { mkdirs() }, "provider_timings.json")

    /** Reads the measured resolution times once. */
    private fun ensureTimingsLoaded() {
        if (timingsLoaded) return
        synchronized(this) {
            if (timingsLoaded) return
            runCatching {
                val file = providerTimingsFile
                if (file.isFile) {
                    val parsed =
                        json.parseToJsonElement(file.readText())
                            .jsonObject
                            .mapNotNull { (sourceId, value) ->
                                value.jsonPrimitive.content.toLongOrNull()?.let { sourceId to it }
                            }
                            .toMap()
                    providerTimings.restore(parsed)
                    if (parsed.isNotEmpty()) {
                        SpotiFLACDiag.log(
                            "provider timings restored: " +
                                parsed.entries.joinToString(", ") { "${it.key}=${it.value}ms" },
                        )
                    }
                }
            }.onFailure { Timber.tag(TAG).w(it, "Could not read persisted provider timings") }
            timingsLoaded = true
        }
    }

    /** Writes the measured resolution times. */
    private fun persistTimings() {
        runCatching {
            providerTimingsFile.writeText(
                buildJsonObject {
                    providerTimings.snapshot().forEach { (sourceId, ms) -> put(sourceId, ms) }
                }.toString(),
            )
        }.onFailure { Timber.tag(TAG).w(it, "Could not persist provider timings") }
    }

    /**
     * The windows and retry count this install uses for one source.
     *
     * Read from the one preference the Audio Sources rows write, so the watchdog, the sweep's budget
     * and the row that shows the numbers cannot disagree about them. A source with no stored policy
     * gets the shipped one - 5s to connect, 60s without data to finish a download, three attempts.
     */
    fun fetchPolicyFor(sourceId: String): SpotiFLACFetchPolicy =
        SpotiFLACFetchPolicies.policyFor(
            PreferenceStore.get(SpotiFLACFetchPoliciesKey),
            sourceId,
        )

    /**
     * The longest one attempt of this sweep may cost, so its budget covers the slowest source asked.
     *
     * The sum of the source's two windows (connect + download): an attempt may spend both before its
     * own watchdog has anything to say, and a budget sized for less cuts the chain off part-way - at
     * which point the sources behind the cut look exactly like sources that had nothing.
     */
    fun sweepAttemptWindowMs(sourceIds: List<String>): Long {
        if (sourceIds.isEmpty()) return SpotiFLACFetchPolicy.DEFAULT.attemptCeilingMs
        return sourceIds.maxOf { sourceId -> fetchPolicyFor(sourceId).attemptCeilingMs }
    }

    /**
     * How many times one source may be asked in this sweep, at most.
     *
     * The budget is sized from the largest retry count in the walk rather than per source, because a
     * single number decides the sweep's deadline. It is an upper bound: a retry only happens after a
     * transient failure (see [SpotiFLACFetchRetry]).
     */
    fun sweepAttemptsPerSource(sourceIds: List<String>): Int {
        if (sourceIds.isEmpty()) return SpotiFLACFetchPolicy.DEFAULT.maxAttempts
        return sourceIds.maxOf { sourceId -> fetchPolicyFor(sourceId).maxAttempts }
    }

    /**
     * Records providers the runtime reported as refusing service, so the chain stops asking them.
     *
     * [knownProviders] is the sweep's candidate list and nothing outside it can be recorded: the
     * runtime log is read as evidence, and a word captured from an unrelated sentence must not be
     * able to take a working provider's place out of the chain.
     */
    private fun recordServiceRefusals(
        lines: List<String>,
        knownProviders: Collection<String>,
    ) {
        val refusing = SpotiFLACRuntimeWalk.rateLimitRefusals(lines, knownProviders)
        if (refusing.isEmpty()) return
        val now = System.currentTimeMillis()
        val newly = refusing.filter { !SpotiFLACProviderStallPolicy.isRateLimited(rateLimitedSources[it], now) }
        refusing.forEach { rateLimitedSources[it] = now }
        SpotiFLACDiag.log(
            "provider refused service (" +
                (newly.joinToString(",").ifEmpty { refusing.joinToString(",") }) +
                ") - left out of the chain for " +
                "${SpotiFLACProviderStallPolicy.RATE_LIMIT_COOLDOWN_MS / 60_000}min",
        )
    }

    /** Reads persisted demotions once, dropping any that have already expired. */
    private fun ensureStallsLoaded() {
        if (stallsLoaded) return
        synchronized(this) {
            if (stallsLoaded) return
            runCatching {
                val file = stalledSourcesFile
                if (!file.isFile) return@synchronized
                val now = System.currentTimeMillis()
                val root = json.parseToJsonElement(file.readText()).jsonObject
                // Two shapes live in this file: the original flat `{sourceId: stalledAtMs}` and the
                // current `{"stalled": {...}, "unavailable": {...}}`. Sniffed rather than versioned,
                // so a file written by an older build still restores instead of being discarded -
                // which would defeat the point of persisting it.
                val stalledRoot = root["stalled"] as? JsonObject ?: root
                val parsed = stalledRoot
                    .mapNotNull { (sourceId, value) ->
                        val stalledAt = (value as? JsonPrimitive)?.content?.toLongOrNull()
                            ?: return@mapNotNull null
                        sourceId.takeIf {
                            SpotiFLACProviderStallPolicy.isDemoted(stalledAt, now)
                        }?.let { it to stalledAt }
                    }.toMap()
                recentlyStalledSources.putAll(parsed)
                if (parsed.isNotEmpty()) {
                    SpotiFLACDiag.log(
                        "stall demotions restored: ${parsed.keys.joinToString(",")}",
                    )
                }
                val restoredUnavailable = (root["unavailable"] as? JsonObject)
                    ?.mapNotNull { (sourceId, value) ->
                        val entry = value as? JsonObject ?: return@mapNotNull null
                        val atMs = (entry["at"] as? JsonPrimitive)?.content?.toLongOrNull()
                            ?: return@mapNotNull null
                        if (!SpotiFLACProviderStallPolicy.isUnavailable(atMs, now)) {
                            return@mapNotNull null
                        }
                        val reason = (entry["reason"] as? JsonPrimitive)?.content.orEmpty()
                        // Absent in a file written before addons were skipped, which is exactly the
                        // runtime-provider shape: demoted, not left out.
                        val skipped = (entry["skipped"] as? JsonPrimitive)?.content?.toBoolean() == true
                        sourceId to Unavailable(atMs, reason, skipped)
                    }
                    ?.toMap()
                    .orEmpty()
                unavailableSources.putAll(restoredUnavailable)
                if (restoredUnavailable.isNotEmpty()) {
                    SpotiFLACDiag.log(
                        "provider unavailability restored: " +
                            restoredUnavailable.keys.joinToString(","),
                    )
                }
                // A source that was refused before this launch is still refused now, and the watch
                // that was re-asking it died with the process. Restarting them here is what makes
                // the recovery survive a restart: without it a user who relaunches the app with a
                // dead addon gets no probes until some track fails against it again, which is the
                // one moment nobody wants to spend on a service already known to be down.
                resumeAddonRecovery(restoredUnavailable.filterValues { it.skipped }.keys)
            }.onFailure { Timber.tag(TAG).w(it, "Could not read persisted provider stalls") }
            stallsLoaded = true
        }
    }

    /** Writes the current demotions, keeping only the ones still in cooldown. */
    private fun persistStalls() {
        runCatching {
            val now = System.currentTimeMillis()
            val live = recentlyStalledSources
                .filterValues { SpotiFLACProviderStallPolicy.isDemoted(it, now) }
            // Drop expired entries from memory too, so a long session does not keep
            // re-reading providers that are no longer demoted.
            recentlyStalledSources.keys.retainAll(live.keys)
            val liveUnavailable = unavailableSources
                .filterValues { SpotiFLACProviderStallPolicy.isUnavailable(it.atMs, now) }
            unavailableSources.keys.retainAll(liveUnavailable.keys)
            stalledSourcesFile.writeText(
                buildJsonObject {
                    putJsonObject("stalled") {
                        live.forEach { (sourceId, stalledAt) -> put(sourceId, stalledAt) }
                    }
                    // The reason is persisted with the moment, so a restored demotion can still say
                    // what the provider reported instead of only that it is sitting at the back.
                    putJsonObject("unavailable") {
                        liveUnavailable.forEach { (sourceId, entry) ->
                            putJsonObject(sourceId) {
                                put("at", entry.atMs)
                                put("reason", entry.reason)
                                put("skipped", entry.skipped)
                            }
                        }
                    }
                }.toString(),
            )
        }.onFailure { Timber.tag(TAG).w(it, "Could not persist provider stalls") }
    }

    /**
     * Records a provider's own verdict that it cannot serve, and persists it.
     *
     * The reason is kept verbatim, because it is the provider's own words and it is what the Audio
     * Sources row shows: nothing here paraphrases an answer that came from the source itself.
     */
    private fun noteProviderUnavailable(
        sourceId: String,
        reason: String,
        skipped: Boolean = false,
    ) {
        unavailableSources[sourceId] = Unavailable(System.currentTimeMillis(), reason, skipped)
        // Written on every record, not only when the words change. A source that refuses again after
        // its cooldown is making a fresh statement, and the moment on disk is what a restart reads:
        // keeping the first one let a restore hand back an already-expired cooldown, which would put
        // a still-dead addon back in the walk on the first track after every restart.
        persistStalls()
    }

    /** Forgets a provider's unavailability, because it has just served or answered healthy. */
    private fun clearProviderUnavailable(sourceId: String) {
        if (unavailableSources.remove(sourceId) != null) persistStalls()
    }

    /**
     * Sources whose own health says they cannot serve right now, with the line to show for each.
     *
     * Read by the Audio Sources screen so a provider-side outage is visible *before* the Test button
     * is pressed - which is the state the user is actually in when they press it and ask why the
     * session looks healthy. It never asks the runtime anything: the answer is the one
     * [noteProviderHealth] (or a failed Test) already paid for, and expired entries are dropped here
     * so a row cannot outlive the demotion it describes.
     *
     * The persisted records are read first. They otherwise arrive with the first sweep, which is the
     * wrong order for the case they exist to serve: straight after a restart the rows are the first
     * thing shown and no sweep has run yet, so a provider recorded as down would be invisible until
     * after it had already been paid for once. Measured on the reporting device - Deezer recorded
     * unavailable on disk, and the freshly opened screen showed an ordinary verified row.
     */
    fun providerHealthNotes(): Map<String, String> {
        ensureStallsLoaded()
        if (unavailableSources.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        return unavailableSources
            .mapNotNull { (sourceId, entry) ->
                if (!SpotiFLACProviderStallPolicy.isUnavailable(entry.atMs, now)) {
                    return@mapNotNull null
                }
                val remaining = SpotiFLACProviderStallPolicy.UNAVAILABLE_COOLDOWN_MS - (now - entry.atMs)
                sourceId to SpotiFLACProviderNote.row(entry.reason, remaining, skipped = entry.skipped)
            }
            .toMap()
    }

    /**
     * Asks a source's own health after it failed a sweep, and records the answer.
     *
     * The sweep can only ever see a provider's *errors*, and those errors cannot tell a track the
     * provider does not have from a service the provider cannot reach. Its own health check can, and
     * the two point at different actions: the first is the track's problem, the second is the
     * provider's. Cost is bounded on purpose - a runtime call is ~1.8s, so the answer is asked once
     * per [SpotiFLACProviderStallPolicy.UNAVAILABLE_COOLDOWN_MS] per source and only where it changes
     * a decision.
     */
    private suspend fun noteProviderHealth(sourceId: String) {
        val now = System.currentTimeMillis()
        val lastAsked = healthCheckedAt[sourceId]
        if (lastAsked != null && now - lastAsked < SpotiFLACProviderStallPolicy.UNAVAILABLE_COOLDOWN_MS) {
            return
        }
        healthCheckedAt[sourceId] = now
        val backend = backendClass ?: return
        val payload = withContext(Dispatchers.IO) {
            runCatching { invokeString(backend, "checkExtensionHealthJSON", sourceId) }.getOrNull()
        }
        when (val verdict = SpotiFLACProviderHealth.verdict(sourceId, payload)) {
            is SpotiFLACProviderHealth.Verdict.Unavailable -> {
                noteProviderUnavailable(sourceId, verdict.reason)
                SpotiFLACDiag.log(
                    "provider reports itself unavailable: $sourceId (${verdict.reason}) - " +
                        "moved last for ${SpotiFLACProviderStallPolicy.UNAVAILABLE_COOLDOWN_MS / 60_000}m",
                )
            }
            // Serving again: forget the demotion immediately rather than waiting the cooldown out.
            SpotiFLACProviderHealth.Verdict.Available -> clearProviderUnavailable(sourceId)
            // No usable answer: demote nobody. An unreadable health line is exactly when guessing
            // costs a source that works.
            null -> Unit
        }
    }

    /**
     * A source's declared quality options, read from the runtime's own manifest.
     *
     * The retry quality has to be an id the source actually accepts. A fixed token does
     * not work: the runtime maps an unrecognised id to the lossless kind, so retrying
     * "LOSSLESS" as "320" fails with the same "no compatible lossless quality" the first
     * attempt produced - a retry that looks like it ran while never asking anything new.
     */
    private data class ExtensionQualities(
        val options: List<SpotiFLACQualityCascade.QualityOption>,
        val downloadFallbackTier: String?,
    )

    /**
     * Reads each installed extension's quality options, keyed by extension id.
     *
     * The runtime is the authority here, not the registry: a registry entry describes
     * what can be installed, while these are the options of the package actually loaded.
     */
    private fun installedQualities(
        backend: Class<*>,
        sourceIds: List<String>,
    ): Map<String, ExtensionQualities> {
        if (sourceIds.isEmpty()) return emptyMap()
        val wanted = sourceIds.map { it.lowercase(java.util.Locale.US) }.toSet()
        return runCatching {
            val raw = invokeString(backend, "getInstalledExtensions")
            val array = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
                ?: return@runCatching emptyMap()
            val byId = mutableMapOf<String, ExtensionQualities>()
            array.forEach { entry ->
                val obj = entry as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val id = listOf("id", "name")
                    .firstNotNullOfOrNull { key ->
                        obj[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    } ?: return@forEach
                if (id.lowercase(java.util.Locale.US) !in wanted) return@forEach
                val options =
                    (obj["quality_options"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { option ->
                            val optionObj = option as? kotlinx.serialization.json.JsonObject
                                ?: return@mapNotNull null
                            val optionId = optionObj["id"]?.jsonPrimitive?.content
                                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            SpotiFLACQualityCascade.QualityOption(
                                id = optionId,
                                kind = optionObj["kind"]?.jsonPrimitive?.content.orEmpty(),
                                label = optionObj["label"]?.jsonPrimitive?.content.orEmpty(),
                            )
                        }.orEmpty()
                val tier =
                    (obj["capabilities"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("downloadFallbackTier")
                        ?.jsonPrimitive
                        ?.content
                byId[id.lowercase(java.util.Locale.US)] = ExtensionQualities(options, tier)
            }
            byId
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Could not read extension quality options; no lossy retry available")
            emptyMap()
        }
    }

    /** True when the runtime already has this extension loaded in this session. */
    private fun isExtensionInstalled(backend: Class<*>, sourceId: String): Boolean =
        runCatching {
            val raw = invokeString(backend, "getInstalledExtensions")
            if (raw.isBlank()) return@runCatching false
            val array = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
                ?: return@runCatching false
            array.any { entry ->
                val obj = entry as? kotlinx.serialization.json.JsonObject ?: return@any false
                listOf("id", "name").any { key ->
                    obj[key]?.jsonPrimitive?.content?.equals(sourceId, ignoreCase = true) == true
                }
            }
        }.getOrDefault(false)

    /**
     * @param runtimeWalksProviders whether the runtime may fall through to other providers after
     *   [sourceId]. It may only do so when it is the *only* walker: the runtime's fallback walks the
     *   rest of the candidate list itself (it logs `New priority order: [soundcloud pandora ...]` and
     *   keeps going) while Hush's sweep asks each enabled provider in turn, so leaving it on meant
     *   every attempt re-paid the runtime's metadata resolution for providers the runtime had already
     *   reached - and the stall recorded against one provider was really a statement about another
     *   (measured: one attempt spent 20.2s in `resolving_metadata` before any provider was asked for
     *   audio, and each of the sweep's later attempts repeated it). With the "try next source"
     *   setting off, Hush asks exactly one provider and this is what lets the runtime reach the rest.
     */
    private fun buildRequest(
        sourceId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        isrc: String?,
        spotifyTrackId: String?,
        quality: String,
        coverUrl: String?,
        outputDir: File,
        outputPath: File,
        itemId: String,
        runtimeWalksProviders: Boolean,
    ): String = buildJsonObject {
        put("contract_version", 1)
        put("isrc", isrc.orEmpty())
        put("service", sourceId)
        put("download_provider", sourceId)
        put("provider_track_id", "")
        put("spotify_id", spotifyTrackId.orEmpty())
        // What a catalogue sees, not what YouTube said: a video title is full of cast, film and
        // format, and its artist is usually a channel, so a provider asked to match the raw text
        // answers `Invalid <service> track ID` for any song whose video title is not already a
        // clean name. Cleaned here rather than upstream of the sweep so nothing else - the miss
        // memo, the prefetch plan, the cache key - changes identity because of a display rule.
        val lookupTitle = SpotiFLACLookupText.title(title)
        val lookupArtist = SpotiFLACLookupText.artist(artist)
        put("track_name", lookupTitle)
        put("artist_name", lookupArtist)
        put("album_name", album.orEmpty())
        put("album_artist", lookupArtist)
        put("cover_url", coverUrl.orEmpty())
        put("output_dir", outputDir.absolutePath)
        put("output_path", outputPath.absolutePath)
        put("output_ext", "")
        put("filename_format", "{artist} - {title}")
        put("quality", quality.uppercase())
        put("embed_metadata", false)
        put("embed_lyrics", false)
        put("embed_replaygain", PreferenceStore.get(SpotiFLACEmbedReplayGainKey) ?: false)
        put("post_processing_enabled", false)
        put("track_number", 0)
        put("disc_number", 0)
        put("total_tracks", 0)
        put("release_date", "")
        put("item_id", itemId)
        put("duration_ms", durationMs)
        put("source", sourceId)
        put("use_extensions", true)
        // Whoever walks the providers, walks once - see [runtimeWalksProviders].
        put("use_fallback", runtimeWalksProviders)
        put("storage_mode", "app")
        put("output_fd", 0)
    }.toString()

    /**
     * Runs [block] while mirroring the runtime's per-item transfer progress into
     * [downloadProgress]. The runtime exposes a non-polling delta wait, so the
     * reporter blocks on it in its own coroutine and is cancelled with the call.
     */
    private suspend fun <T> withProgressTracking(
        itemId: String,
        mediaId: String?,
        sourceId: String,
        stallMeter: ProviderStallMeter? = null,
        attemptIndex: Int = 0,
        attemptTotal: Int = 0,
        block: suspend () -> T,
    ): T {
        val backend = backendClass
        // Set the moment the attempt returns, because the reporter polls in 400ms slices: a delta
        // read *before* the call ended can be published *after* the terminal `completed` sample and
        // overwrite it with whatever percentage the transfer happened to be at. That is how a track
        // could sit on "Downloading 14%" after it had finished and started playing.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val reporter =
            if (backend == null || itemId.isBlank()) {
                null
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    var seq = 0L
                    var lastLoggedPercent = -1
                    var lastLoggedStage: String? = null
                    var lastSampleBytes = 0L
                    var lastSampleAtMs = 0L
                    var lastMeasuredSpeed = 0.0
                    while (isActive && !finished.get()) {
                        val raw =
                            runCatching {
                                invokeString(backend, "waitForMultiProgressDelta", seq, 400L)
                            }.getOrNull().orEmpty()
                        if (raw.isBlank()) continue
                        val delta =
                            parseSpotiFLACProgressDelta(
                                raw = raw,
                                itemId = itemId,
                                mediaId = mediaId.orEmpty(),
                                sourceId = sourceId,
                            ) ?: continue
                        seq = delta.seq
                        // Only a delta that names *this* item counts as liveness. The
                        // stream is multi-item, so feeding the meter on any delta at all
                        // held wedged providers open until the 25s ceiling instead of the
                        // 8s stall timeout, because other tracks' samples kept resetting
                        // the clock.
                        val progress = delta.progress
                        if (!delta.isItemScoped || progress == null) continue
                        // Advances only on a genuinely new event for this item, so the
                        // runtime repeating its last sample cannot mask a wedge - which
                        // is exactly what the stall watchdog needs to see.
                        stallMeter?.onProgress(
                            seq = delta.seq,
                            stage = progress.stage,
                            bytesReceived = progress.bytesReceived,
                        )
                        // The runtime often reports bytes without a speed, so derive
                        // one from consecutive samples when it does not.
                        val nowMs = SystemClock.elapsedRealtime()
                        if (lastSampleAtMs == 0L) {
                            lastSampleBytes = progress.bytesReceived
                            lastSampleAtMs = nowMs
                        } else {
                            val elapsedMs = nowMs - lastSampleAtMs
                            // Rebase on every sample at least 250 ms apart: keeping a
                            // stale baseline made the window grow past any usable
                            // range, so the speed stayed at zero.
                            if (elapsedMs >= 250L) {
                                if (progress.bytesReceived > lastSampleBytes) {
                                    lastMeasuredSpeed =
                                        (progress.bytesReceived - lastSampleBytes).toDouble() /
                                            1_048_576.0 /
                                            (elapsedMs / 1000.0)
                                }
                                lastSampleBytes = progress.bytesReceived
                                lastSampleAtMs = nowMs
                            }
                        }
                        val published =
                            if (progress.speedMbps <= 0.01 && lastMeasuredSpeed > 0.01) {
                                progress.copy(speedMbps = lastMeasuredSpeed)
                            } else {
                                progress
                            }
                        // The attempt may have ended while this delta was being read; publishing it
                        // then would replace the finished state with a stale percentage.
                        if (finished.get()) break
                        // The meter's own samples replace the attempt's announcement within
                        // milliseconds, so the walk's position has to ride along here or the row
                        // loses "(2 of 3)" the moment any progress arrives.
                        _downloadProgress.value =
                            published.copy(
                                sourceLabel = repositoryManager.sourceLabel(sourceId),
                                attemptIndex = attemptIndex,
                                attemptTotal = attemptTotal,
                            )
                        // Log sparsely so the diag feed stays readable while still
                        // proving the transfer meter is live.
                        if (published.percent >= lastLoggedPercent + 10 ||
                            (published.stage != null && published.stage != lastLoggedStage)
                        ) {
                            lastLoggedPercent = published.percent
                            lastLoggedStage = published.stage
                            SpotiFLACDiag.log(
                                "progress id=$sourceId item=$itemId pct=${published.percent} " +
                                    "bytes=${published.bytesReceived}/${published.bytesTotal} " +
                                    "speed=${published.speedMbps} " +
                                    "stage=${published.stage ?: "-"} status=${published.status ?: "-"}",
                            )
                        }
                    }
                }
            }
        var succeeded = false
        try {
            val result = withContext(Dispatchers.IO) { block() }
            // A successful attempt publishes its own terminal state (downloadViaExtension
            // records `completed` with the finished size), which is worth keeping.
            finished.set(true)
            succeeded = true
            return result
        } finally {
            finished.set(true)
            reporter?.cancel()
            if (!succeeded) {
                // Nothing else ends this signal. A failed or cancelled attempt left the
                // last sample published, so the player kept showing a progress bar frozen
                // at whatever percentage the attempt reached - reading as "still fetching"
                // for a fetch that had already stopped. This is also the only caller of
                // clearDownloadProgress, which is why it never cleared before.
                clearDownloadProgress(mediaId)
                SpotiFLACDiag.log("progress cleared itemId=$itemId source=$sourceId (attempt did not complete)")
            }
        }
    }

    /**
     * Announces that [sourceId] is about to be tried for [mediaId], before any byte moves.
     *
     * The runtime's meter answers "how fast", not "who is being asked" - and a source that answers
     * "not mine", or refuses outright, can spend several seconds doing it without publishing a
     * single byte. Measured on device: the player sat at 0:00 with no provenance line at all for a
     * whole sweep (`deezer` spent 5.4s failing on one track), while the only fetches that ever named
     * a source on that row were ones that reached the download meter. So the attempt itself is
     * published as a resolving sample: the row then reads "Fetching from Deezer…" for exactly as
     * long as the source is being tried.
     */
    private fun publishAttempt(
        mediaId: String?,
        sourceId: String,
        attemptIndex: Int = 0,
        attemptTotal: Int = 0,
    ) {
        _downloadProgress.value =
            attemptProgress(
                mediaId = mediaId,
                sourceId = sourceId,
                sourceLabel = runCatching { repositoryManager.sourceLabel(sourceId) }.getOrNull(),
                attemptIndex = attemptIndex,
                attemptTotal = attemptTotal,
            )
    }

    /**
     * Ends the live progress signal for an attempt that is no longer running.
     *
     * [mediaId] is the track the attempt belonged to, and it is what keeps one attempt's ending from
     * blanking another track's line: the flow is one shared signal, so the lookahead warming the
     * *next* track used to clear the *playing* track's progress whenever its own attempt ended, and
     * the player's row went empty in the middle of a live fetch. A null [mediaId] is an
     * unconditional clear, for the callers that really do end the whole signal (a stop, a cancel).
     */
    fun clearDownloadProgress(mediaId: String? = null) {
        if (!clearMayEnd(_downloadProgress.value, mediaId)) return
        _downloadProgress.value = null
    }

    /** Aborts an in-flight playback download for a queue item. */
    fun cancelDownload(mediaId: String) {
        val backend = backendClass ?: return
        runCatching { invokeVoid(backend, "cancelDownload", "hush-$mediaId") }
            .onFailure { SpotiFLACDiag.log("cancelDownload failed: ${it.message}") }
        _downloadProgress.value = null
    }

    /**
     * Cached playback file for a queue item, when one exists on disk.
     *
     * This is also where a copy cached before the FLAC splice existed is made seekable: a
     * fragmented-MP4 FLAC has no index, so the player restarts it from zero on every seek. The
     * splice is idempotent and costs one small read when the file is already a native FLAC, so
     * it is asked here - on the hot lookup every open and every seek re-open uses - rather than
     * leaving every already-downloaded copy of those tracks permanently unseekable.
     */
    fun playbackFileForMediaId(mediaId: String): File? {
        val file = playbackCache.fileForMediaId(mediaId) ?: return null
        val seekable =
            runCatching { SpotiFLACMovKeyDecryptor.spliceNativeFlac(file, SpotiFLACDiag::log) }.getOrNull()
                ?: return file
        val trackKey = playbackCache.entryForMediaId(mediaId)?.trackKey
        if (trackKey.isNullOrBlank()) return seekable
        playbackCache.replaceFile(trackKey, seekable)
        return seekable
    }

    /**
     * Drops the stored playback file for a media id and reports the freed bytes.
     *
     * Used when playback itself reports the bytes are unreadable. The file has to go so
     * the next resolve downloads it again: the runtime reuses an existing `output_path`
     * (`already_exists`), so leaving a truncated file in place would make the re-resolve
     * hand back the same unusable copy and the track would fail identically every time.
     * Returns 0 for a pinned entry - a user download is theirs to remove.
     */
    fun discardPlaybackFile(mediaId: String): Long = playbackCache.removeForMediaId(mediaId)

    /**
     * Which SpotiFLAC source a cached playback file came from. Serving a file
     * straight off disk bypasses the runtime, so this is the only way the player
     * can still label the track with its real source.
     */
    fun cachedPlaybackSourceForMediaId(mediaId: String): String? =
        playbackCache.entryForMediaId(mediaId)?.sourceId?.takeIf { it.isNotBlank() }

    /**
     * Full cache entry for a track, including the codec details of the stored file.
     *
     * Serving a cached file bypasses the resolver, so the player cannot learn the
     * format from resolution - it has to read it back from the cache, otherwise the
     * codec row keeps describing whatever engine last resolved the track.
     */
    fun cachedPlaybackEntryForMediaId(mediaId: String): SpotiFLACCacheEntry? =
        playbackCache.entryForMediaId(mediaId)

    /**
     * Media ids whose audio is already a complete file on this device.
     *
     * SpotiFLAC playback files are read directly instead of being copied into
     * Media3's song cache, so this is the only way for a screen or a browse node to
     * know which tracks are playable offline. See [playbackFileForMediaId].
     */
    fun cachedPlaybackMediaIds(includePinned: Boolean = true): Set<String> =
        playbackCache.cachedMediaIds(includePinned = includePinned)

    /** As [playbackFileForMediaId], dropping the file and its index entry. */
    fun removeCachedPlaybackForMediaId(mediaId: String): Long = playbackCache.removeForMediaId(mediaId)

    /**
     * The same, for a song whose download was just removed: the cached copy goes with it.
     *
     * A removed download must not come back as cached bytes - the next download of that song
     * would then complete instantly from the file the user just deleted, and a resolve would
     * keep serving the discarded quality. Pinned status does not protect it here; the user's
     * request to remove the download outranks it.
     */
    fun discardCachedPlaybackForMediaId(mediaId: String): Long = playbackCache.discardForMediaId(mediaId)

    fun cacheStats(): SpotiFLACCacheStats = playbackCache.stats()

    fun clearPlaybackCache(): Long = playbackCache.clear()

    fun reconcilePlaybackCache() = playbackCache.reconcile()

    private fun invokeString(backend: Class<*>, name: String, vararg args: Any): String {
        val method = backend.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: throw SpotiFLACException("SpotiFLAC runtime method missing: $name")
        return try {
            method.invoke(null, *args)?.toString().orEmpty()
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun invokeVoid(backend: Class<*>, name: String, vararg args: Any) {
        val method = backend.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: throw SpotiFLACException("SpotiFLAC runtime method missing: $name")
        try {
            method.invoke(null, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** The gateway requires a fresh Turnstile verification before this download. */
class SpotiFLACVerificationRequiredException(
    val sourceId: String,
    message: String,
) : Exception(message)

/**
 * The gateway is blocking this client's address, so no provider was asked at all.
 *
 * Carried as its own type rather than folded into a verification notice, because the two need
 * opposite things from the user. A verification is something they can do and it fixes the source; a
 * rate-limit block is something they can only wait out - or route around, since it follows the
 * address and the same install on a VPN is served normally. Sending them to a Cloudflare check for
 * this is a dead end: measured on the reporting device the challenge endpoint was itself answering
 * `429`, so the check could not be completed while the block lasted.
 *
 * The `429` wording is deliberate. [SpotiFLACSweepVerdict] keys off it to record an unfinished sweep
 * rather than a catalogue miss, so a blocked track is retried soon instead of being hidden from
 * SpotiFLAC for hours.
 */
class SpotiFLACRelayBlockedException(
    val remainingMs: Long,
    reason: String,
) : Exception(
    "SpotiFLAC is temporarily blocked (HTTP 429) for this connection - " +
        "${SpotiFLACSessionRenewer.formatBlockRemaining(remainingMs)} left ($reason)",
)

/**
 * The exception a sweep must fail with for [block], or null when the client is not blocked.
 *
 * A function rather than an `if` inside the resolver so the rule can be tested without the runtime:
 * "blocked means asked nothing" is the whole behaviour, and the sweep is what pays for getting it
 * wrong.
 */
internal fun relayBlockFailure(
    block: SpotiFLACSessionRenewer.RelayBlock?,
): SpotiFLACRelayBlockedException? =
    block?.let { SpotiFLACRelayBlockedException(it.remainingMs, it.reason) }

/**
 * Process-wide handle so non-Hilt callers can
 * reach the Hilt-provided bridge. Assigned from the bridge's init block.
 */
object SpotiFLACNativeRuntimeBridgeHolder {
    @Volatile
    var instance: SpotiFLACNativeRuntimeBridge? = null

    /**
     * The repository manager the bridge was built with.
     *
     * Held for one reason: the debug receiver drives what a source row renders (`op=source-row`),
     * because that screen's content is not exposed to the accessibility tree and so cannot be
     * tapped or read back by a shell on a device nobody is holding. Nothing in the app reads
     * this, and it is null in any build that never constructs a bridge.
     */
    @Volatile
    var repositoryManager: ExtensionRepositoryManager? = null
}
