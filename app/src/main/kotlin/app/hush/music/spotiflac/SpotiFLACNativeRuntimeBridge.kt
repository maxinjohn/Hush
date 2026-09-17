package app.hush.music.spotiflac

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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
    private val sessionManager: SpotiFLACSessionManager,
    private val playbackCache: SpotiFLACPlaybackCache,
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
        private const val RUNTIME_APP_VERSION = SpotiFLACSessionManager.APP_VERSION
    }

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val lock = Any()

    /** Live progress of the download currently feeding the player, if any. */
    private val _downloadProgress = MutableStateFlow<PlaybackDownloadProgress?>(null)
    val downloadProgress: StateFlow<PlaybackDownloadProgress?> = _downloadProgress.asStateFlow()

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
     * Whether the persisted demotions have been read into [recentlyStalledSources].
     *
     * Persisted because the demotion is a statement about a provider, not about this
     * process: a provider that wedged before a restart will wedge again on the first
     * track afterwards, and the sweep would pay its watchdog budget before reaching a
     * source that answers. That is exactly the cold-start delay a listener notices.
     */
    @Volatile private var stallsLoaded = false

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
            }.filter { it.isNotBlank() && !it.equals("spotify-web", ignoreCase = true) }
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
                repositoryManager.sources.value.map { it.source.id }
            }.filter { it.isNotBlank() && !it.equals("spotify-web", ignoreCase = true) }
            for (id in ids) {
                runCatching { ensurePackageLoaded(backend, id, repositoryManager.getSourceForId(id)) }
                    .onFailure { SpotiFLACDiag.log("prewarm skip id=$id msg=${it.message}") }
                // Seed after the package is extracted so the manifest is readable;
                // this is what lets downloads pass the signed-session preflight
                // using Hush's existing gateway session.
                seedSignedSessionFor(id)
            }
            SpotiFLACDiag.log("prewarm done ids=${ids.joinToString(",")}")
            // Sources that cannot download yet get their challenge solved now, in the
            // background, instead of surfacing as a failed track later - but only while
            // SpotiFLAC is actually in use. With it off every source is unused, and an
            // automatic run is not a harmless no-op: on a device whose WebView cannot run
            // Cloudflare's check (a car head unit) it ends with a browser tab opening by
            // itself, asking the user to solve a challenge for playback that is not routed
            // through SpotiFLAC at all. The preference is read here, at the moment the
            // attempt would start, so a source queued while it was on is never verified after
            // it has been switched off either.
            if (PreferenceStore.get(SpotiFLACEnabledKey) == true) {
                sourcesAwaitingVerification().takeIf { it.isNotEmpty() }?.let { blocked ->
                    SpotiFLAutoVerifier.enqueue(blocked, "prewarm")
                }
            } else {
                SpotiFLACDiag.log("prewarm: no automatic verification (SpotiFLAC is disabled)")
            }
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
    private fun sourcesAwaitingVerification(): List<String> {
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
     * Orders the download candidates for a resolve.
     *
     * The registry list is network-backed and can still be empty right after the
     * service starts (or while a toggle rebuilds the timeline), so installed
     * extension packages on disk are used as a fallback. Without this, a resolve
     * that races the registry sync fails with "No SpotiFLAC download sources are
     * enabled" even though the extensions are present and loaded.
     */
    private fun resolveCandidates(sourceIds: List<String>): List<String> {
        val fromRegistry =
            sourceIds.ifEmpty { repositoryManager.sources.value.map { it.source.id } }
                .filter { it.isNotBlank() && !it.equals("spotify-web", ignoreCase = true) }
                .distinct()
        // The registry and the extension packages on disk disagree in both directions,
        // and the sweep should follow what can actually download. See
        // SpotiFLACCandidateOrder for why an unavailable source is demoted, not dropped.
        val installed = installedDownloadExtensionIds()
        if (fromRegistry.isEmpty() && installed.isNotEmpty()) {
            SpotiFLACDiag.log("candidates from installed packages: ${installed.joinToString(",")}")
        }
        val ordered = SpotiFLACCandidateOrder.order(registry = fromRegistry, installed = installed)
        if (ordered.unavailable.isNotEmpty() && ordered.loadable.isNotEmpty()) {
            SpotiFLACDiag.log(
                "candidates demoted (extension package unavailable): ${ordered.unavailable.joinToString(",")}",
            )
        }
        if (ordered.extras.isNotEmpty() && ordered.loadable.isNotEmpty()) {
            SpotiFLACDiag.log(
                "candidates added from installed packages: ${ordered.extras.joinToString(",")}",
            )
        }
        val base = ordered.all
        // The download pipeline pauses at the first provider that needs
        // verification, so an already-usable source must be tried first or it
        // never gets a chance. Stable-partition keeps user ordering within groups.
        // A source with no signed-session contract counts as usable: it signs in
        // with the service itself and has nothing to verify.
        val (verified, pending) = base.partition { sourceAuthState(it).isUsable }
        if (verified.isNotEmpty() && pending.isNotEmpty()) {
            SpotiFLACDiag.log("candidates prioritized verified: ${verified.joinToString(",")}")
        }
        // "Only verified sources" keeps an unverified repo from ever being
        // attempted while a working one exists (it would only burn a timeout).
        val verifiedOnly = PreferenceStore.get(SpotiFLACVerifiedOnlyKey) ?: true
        if (verifiedOnly && verified.isNotEmpty()) {
            SpotiFLACDiag.log("candidates verified-only: ${verified.joinToString(",")}")
            return verified
        }
        return verified + pending
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
    private fun unverifiedDownloadExtensionIds(): List<String> =
        installedDownloadExtensionIds().filter {
            sourceAuthState(it) == SpotiFLACSourceAuthState.NEEDS_VERIFICATION
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
        runCatching {
            val backend = requireBackend()
            initializeRuntime(backend)
            val id = extensionIdFor(sourceId)
            ensurePackageLoaded(backend, id, repositoryManager.getSourceForId(id))
            if (SpotiFLACSourceAuth.declaresNoSignedSession(manifestJsonFor(id))) {
                return@runCatching "$id needs no signed session"
            }
            val probe = runCatching { invokeString(backend, "checkExtensionHealthJSON", id) }
            val probeBody = probe.getOrNull().orEmpty()
            SpotiFLACDiag.log("engine test $id: health=${probeBody.take(200)}")
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
            throw SpotiFLACException("$label is $status".take(MAX_TEST_REASON))
        }
        val status = payload.textOrNull("status") ?: payload.textOrNull("state") ?: "engine answered"
        if (status.lowercase() in HEALTHY_STATUSES) return status.take(MAX_TEST_REASON)
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

    /** Extension ids whose extracted package declares a download provider. */
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
                if (name.equals("spotify-web", ignoreCase = true)) return@mapNotNull null
                val types = obj["type"] as? kotlinx.serialization.json.JsonArray
                val isDownload = types?.any { element ->
                    element.jsonPrimitive.content.equals("download_provider", ignoreCase = true)
                } == true
                name.takeIf { isDownload }
            }.getOrNull()
        }.sorted()
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
     * pressed (and one solved grant is applied to every source anyway). Without
     * this fallback the button answered "no challenge yet" while a usable challenge
     * was sitting in the runtime's global list.
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
                    )
                }
            } finally {
                resolveLocks.remove(trackKey, mutex)
            }
        }
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
    ): ResolvedFile {
        val backend = requireBackend()
        initializeRuntime(backend)

        // A provider that wedged on an earlier track is demoted rather than removed, so
        // the watchdog budget is paid once per cooldown instead of once per track while
        // the provider stays in the chain.
        val enabledCandidates = resolveCandidates(sourceIds)
        // Loaded once per process: without it every restart re-learns the same wedge by
        // paying its watchdog timeout before the first usable source is even reached.
        ensureStallsLoaded()
        val candidates =
            SpotiFLACProviderStallPolicy.orderByRecentStalls(
                candidates = enabledCandidates,
                stalledAtMs = recentlyStalledSources,
                nowMs = System.currentTimeMillis(),
            )
        if (candidates != enabledCandidates) {
            SpotiFLACDiag.log(
                "provider order demoted recently stalled: ${candidates.joinToString(",")}",
            )
        }
        SpotiFLACDiag.log(
            "resolve start title=\"$title\" artist=\"$artist\" isrc=${isrc ?: "-"} " +
                "spotifyId=${spotifyTrackId ?: "-"} quality=$quality candidates=${candidates.joinToString(",")}",
        )
        if (candidates.isEmpty()) {
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
        var verificationError: SpotiFLACVerificationRequiredException? = null
        val failures = mutableListOf<String>()

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
        ): ResolvedFile? {
            val source = repositoryManager.getSourceForId(sourceId)
            if (source != null && !source.supportsDownload) return null
            // The runtime writes to the same deterministic path as any entry it replaces,
            // so a lookup in that window would see a short file and call it truncated.
            // Marking the key keeps lookups away from a file that is mid-write.
            if (cacheEnabled) playbackCache.beginRewrite(trackKey)
            return try {
                val response = downloadViaExtension(
                    backend = backend,
                    sourceId = sourceId,
                    source = source,
                    itemId = itemId,
                    mediaId = mediaId,
                    outputDir = outputDir,
                    outputPath = outputPath,
                    // The *request*, not this attempt's token: it is what the user asked for,
                    // and the useful thing to name when a source answers with something else.
                    requestedQuality = quality,
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
                        itemId = itemId,
                    )
                }
                if (cacheEnabled) {
                    playbackCache.record(
                        trackKey = trackKey,
                        file = response.file,
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
                playbackCache.associateMediaId(mediaId, trackKey)
                SpotiFLACDiag.log("resolve ok source=$sourceId quality=$attemptQuality")
                response.copy(trackKey = trackKey)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                SpotiFLACDiag.log("sweep cancelled id=$sourceId (not treated as a source failure)")
                throw cancellation
            } catch (error: Throwable) {
                lastError = error
                if (error is SpotiFLACVerificationRequiredException && verificationError == null) {
                    verificationError = error
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
                runCatching { if (outputPath.isFile) outputPath.delete() }
                null
            } finally {
                // Always released, including on cancellation: a key left marked as
                // "being written" would keep the finished file unservable for the rest
                // of the session.
                if (cacheEnabled) playbackCache.endRewrite(trackKey)
            }
        }

        for (sourceId in candidates) {
            attemptSource(sourceId, quality)?.let { return it }
            // A source that answered "no compatible lossless quality" was never actually
            // asked: the request itself was impossible for it, so it cannot count as tried.
            // Retrying that same source at a lossy quality is what finally gives the YT
            // Music extension - which serves lossy only - a real chance, and it is why
            // switching one source off no longer looks like the whole chain giving up.
            val lastFailure = failures.lastOrNull()
            val retryQuality = retryTokens[sourceId]
            // Two ways a source was never really asked for this track: it cannot deliver the
            // quality, or it answered with audio this app cannot play (a Dolby track where FLAC was
            // requested). Both are retried at the source's own lossy option, because that is a
            // question it can actually answer.
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
            if (!tryNextSource) break
        }
        SpotiFLACDiag.log("all sources failed: ${failures.joinToString("; ")}")
        // Only ask for verification when nothing could play at all: surfacing
        // it earlier aborted resolves while a usable source was still untried.
        verificationError?.let { throw it }
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
        buildJson: (PreparedPackage) -> String,
    ): ResolvedFile {
        ensurePackageLoaded(backend, sourceId, source)
        seedSignedSessionFor(sourceId)
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
        val stallMeter = ProviderStallMeter()
        val responseText =
            try {
                withProgressTracking(itemId, mediaId, sourceId, stallMeter) {
                    withProviderStallWatchdog(backend, itemId, sourceId, stallMeter) {
                        invokeString(backend, "downloadByStrategy", requestJson)
                    }
                }
            } catch (stalled: SpotiFLACProviderStalledException) {
                recentlyStalledSources[sourceId] = System.currentTimeMillis()
                persistStalls()
                SpotiFLACDiag.log(
                    "provider stalled id=$sourceId reason=${stalled.reason} " +
                        "stage=${stalled.stage ?: "-"} " +
                        "silent=${stalled.stalledMillis}ms - abandoned, trying next provider",
                )
                // The runtime's own account of the attempt is captured at the moment it is given
                // up on, because the stall report says only *that* it stopped and which stage it
                // stopped in - never why. That gap is the whole question for a provider that
                // abandons every track at the same stage (measured: qobuz-web at
                // `resolving_stream`, every track tried), and the runtime log holds the reason.
                dumpRuntimeLogs("stall-$sourceId")
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
        SpotiFLACDiag.log(
            "download ok id=$sourceId file=${file.name} bytes=${file.length()} alreadyExists=$alreadyExists " +
                "container=$container codec=${reportedCodec ?: "-"} bits=$bitDepth rate=$sampleRate",
        )
        _downloadProgress.value =
            PlaybackDownloadProgress(
                mediaId = mediaId.orEmpty(),
                sourceId = sourceId,
                percent = 100,
                bytesReceived = file.length(),
                bytesTotal = file.length(),
                status = "completed",
            )
        Timber.tag(TAG).i(
            "Native download ok: source=$sourceId file=${file.name} bytes=${file.length()} " +
                "codec=${response["audio_codec"]?.jsonPrimitive?.content} bits=$bitDepth rate=$sampleRate",
        )
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
        val existing = sessionManager.installIdForRuntime
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
        val file = packageStore.packageFile(sourceId)
        if (!file.isFile && source != null && source.isRuntimeCompatible) {
            packageStore.downloadAndVerify(source).getOrThrow()
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

    /**
     * Seeds the runtime's signed-session record for one extension from Hush's own
     * already-exchanged gateway session.
     *
     * The runtime stores these records as plain JSON under
     * `<spotiflacDir>/signed_sessions/<namespace>-<sha256(scope)[:16]>.json` and
     * `preflightSignedSession` only checks them locally (non-empty session id and
     * secret, not expired). Reusing Hush's session therefore satisfies the
     * preflight without a second, single-use Turnstile grant - which is what makes
     * SpotiFLAC playback work without a manual per-source verification.
     *
     * An existing usable record is left untouched so a session the runtime
     * exchanged itself (and may refresh) always wins.
     */
    private fun seedSignedSessionFor(sourceId: String) {
        val session = sessionManager.sessionForRuntimeSeeding ?: return
        val installId = sessionManager.installIdForRuntime ?: return
        runCatching {
            val record = signedSessionFileFor(sourceId) ?: return@runCatching
            // Mirror signedSessionConfigWithDefaults: the record stores the
            // trimmed, original-case config values, and the file name hashes the
            // lowercased scope (both handled in signedSessionFileFor).
            val manifestFile = File(
                File(context.filesDir, "spotiflac/extensions"),
                "$sourceId/manifest.json",
            )
            val manifest = json.parseToJsonElement(manifestFile.readText()).jsonObject
            val signed = manifest["signedSession"]?.jsonObject ?: return@runCatching
            val namespace = sanitizeSessionNamespace(
                signed["namespace"]?.jsonPrimitive?.content.orEmpty(),
            )
            val baseUrl = signed["baseUrl"]?.jsonPrimitive?.content?.trim().orEmpty()
            val appVersion = signed["appVersion"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "ext-1.0"
            val platform = signed["platform"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "extension"
            // The gateway binds a session to the app version that minted it. Hush
            // exchanges its own session as SpotiFLACSessionManager.APP_VERSION, so
            // handing it to an extension that signs as "deezer@1.3.5" produces
            // requests the gateway answers with a bare 403 - measured directly:
            // the same session and path return 403 signed as deezer@1.3.5 and 428
            // VERIFY_REQUIRED (i.e. signature accepted) signed as the app version.
            // Seeding a mismatched session therefore does not "save" a challenge,
            // it guarantees every request through that extension fails.
            if (!appVersion.equals(SpotiFLACSessionManager.APP_VERSION, ignoreCase = true)) {
                SpotiFLACDiag.log(
                    "seed skipped for $sourceId: manifest appVersion=$appVersion " +
                        "but Hush's session was minted as ${SpotiFLACSessionManager.APP_VERSION}",
                )
                return@runCatching
            }
            record.parentFile?.mkdirs()
            if (record.isFile) {
                val existing = runCatching { record.readText() }.getOrNull()
                // Re-seed whenever the record cannot actually download: expired,
                // or from a session generation Hush has since replaced. Skipping
                // this left an expired record in place forever, because the id and
                // secret were still non-blank, so the provider stayed unusable no
                // matter how often the user re-authenticated in Hush.
                val existingId = SpotiFLACSourceAuth.recordSessionId(existing)
                val sameGeneration = existingId != null && existingId == session.sessionId
                val usable = SpotiFLACSourceAuth.recordUsable(existing, System.currentTimeMillis())
                if (usable && sameGeneration) return@runCatching
                if (existingId != null && existingId != session.sessionId) {
                    SpotiFLACDiag.log(
                        "re-seeding signed session for $sourceId: record=$existingId live=${session.sessionId}",
                    )
                }
            }
            val payload = buildJsonObject {
                put("install_id", installId)
                put("session_id", session.sessionId)
                put("session_secret", session.sessionSecret)
                put("expires_at", java.time.Instant.ofEpochMilli(session.expiresAt).toString())
                put("namespace", namespace)
                put("base_url", baseUrl)
                put("app_version", appVersion)
                put("platform", platform)
            }.toString()
            record.writeText(payload)
            SpotiFLACDiag.log("seeded signed session for $sourceId -> ${record.name}")
        }.onFailure { SpotiFLACDiag.log("seed signed session failed for $sourceId: ${it.message}") }
    }

    /**
     * Removes signed-session records that hold Hush's own session for an extension
     * that signs with a different app version.
     *
     * Those records were written by an earlier build's seeding and can never work
     * (see the appVersion binding above), but they are indistinguishable from a
     * genuinely verified session by inspection alone - the record carries the
     * extension's app version, not the one the session was minted with. The session
     * id is the tell: a record holding Hush's own session id was seeded from Hush.
     * Clearing them makes the source report "Verification needed" honestly and lets
     * it mint its own correctly bound session instead of 403-ing forever.
     */
    fun purgeForeignSeededSessions(): Int {
        val hushSessionId = sessionManager.sessionForRuntimeSeeding?.sessionId ?: return 0
        if (hushSessionId.isBlank()) return 0
        var purged = 0
        runCatching {
            SpotiFLACSessionRenewer.sessions(context).forEach { session ->
                if (session.appVersion.equals(SpotiFLACSessionManager.APP_VERSION, ignoreCase = true)) {
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
     * Copies the tail of the runtime's own log buffer into the diag log. The
     * runtime reports gateway rejections (HTTP status, error codes) only here,
     * which is otherwise invisible from the app side.
     */
    fun dumpRuntimeLogs(reason: String) {
        val backend = backendClass ?: return
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
                return@runCatching
            }
            entries.takeLast(20).forEach { entry ->
                val obj = entry as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val level = obj["level"]?.jsonPrimitive?.content.orEmpty()
                val tag = obj["tag"]?.jsonPrimitive?.content.orEmpty()
                val message = obj["message"]?.jsonPrimitive?.content.orEmpty()
                SpotiFLACDiag.log("  rt| $level $tag: " + message.take(300))
            }
        }.onFailure { SpotiFLACDiag.log("dumpRuntimeLogs failed: ${it.message}") }
    }

    /** Mirrors the runtime's sanitizeSignedSessionNamespace. */
    private fun sanitizeSessionNamespace(value: String): String {
        val filtered = value.trim().lowercase().filter { ch ->
            (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.'
        }
        return filtered.trim('.', '-', '_')
    }

    /** Seeds signed sessions for every enabled download source. */
    suspend fun seedSignedSessions(sourceIds: List<String>) = withContext(Dispatchers.IO) {
        val ids = sourceIds.ifEmpty {
            repositoryManager.sources.value.map { it.source.id }
        }.filter { it.isNotBlank() && !it.equals("spotify-web", ignoreCase = true) }
        ids.forEach(::seedSignedSessionFor)
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

    /** Reads persisted demotions once, dropping any that have already expired. */
    private fun ensureStallsLoaded() {
        if (stallsLoaded) return
        synchronized(this) {
            if (stallsLoaded) return
            runCatching {
                val file = stalledSourcesFile
                if (!file.isFile) return@synchronized
                val now = System.currentTimeMillis()
                val parsed = json.parseToJsonElement(file.readText())
                    .jsonObject
                    .mapNotNull { (sourceId, value) ->
                        val stalledAt = value.jsonPrimitive.content.toLongOrNull()
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
            stalledSourcesFile.writeText(
                buildJsonObject {
                    live.forEach { (sourceId, stalledAt) -> put(sourceId, stalledAt) }
                }.toString(),
            )
        }.onFailure { Timber.tag(TAG).w(it, "Could not persist provider stalls") }
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
    ): String = buildJsonObject {
        put("contract_version", 1)
        put("isrc", isrc.orEmpty())
        put("service", sourceId)
        put("download_provider", sourceId)
        put("provider_track_id", "")
        put("spotify_id", spotifyTrackId.orEmpty())
        put("track_name", title)
        put("artist_name", artist)
        put("album_name", album.orEmpty())
        put("album_artist", artist)
        put("cover_url", coverUrl.orEmpty())
        put("output_dir", outputDir.absolutePath)
        put("output_path", outputPath.absolutePath)
        put("output_ext", "")
        put("filename_format", "{artist} - {title}")
        put("quality", quality.uppercase())
        put("embed_metadata", false)
        put("embed_lyrics", false)
        put("embed_replaygain", false)
        put("post_processing_enabled", false)
        put("track_number", 0)
        put("disc_number", 0)
        put("total_tracks", 0)
        put("release_date", "")
        put("item_id", itemId)
        put("duration_ms", durationMs)
        put("source", sourceId)
        put("use_extensions", true)
        // Fallbacks across the remaining enabled providers mirror upstream's
        // default download behaviour when the primary source misses a track.
        put("use_fallback", true)
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
        block: suspend () -> T,
    ): T {
        val backend = backendClass
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
                    while (isActive) {
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
                        _downloadProgress.value = published
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
            succeeded = true
            return result
        } finally {
            reporter?.cancel()
            if (!succeeded) {
                // Nothing else ends this signal. A failed or cancelled attempt left the
                // last sample published, so the player kept showing a progress bar frozen
                // at whatever percentage the attempt reached - reading as "still fetching"
                // for a fetch that had already stopped. This is also the only caller of
                // clearDownloadProgress, which is why it never cleared before.
                clearDownloadProgress()
                SpotiFLACDiag.log("progress cleared itemId=$itemId source=$sourceId (attempt did not complete)")
            }
        }
    }

    /** Ends the live progress signal for an attempt that is no longer running. */
    fun clearDownloadProgress() {
        _downloadProgress.value = null
    }

    /** Aborts an in-flight playback download for a queue item. */
    fun cancelDownload(mediaId: String) {
        val backend = backendClass ?: return
        runCatching { invokeVoid(backend, "cancelDownload", "hush-$mediaId") }
            .onFailure { SpotiFLACDiag.log("cancelDownload failed: ${it.message}") }
        _downloadProgress.value = null
    }

    /** Cached playback file for a queue item, when one exists on disk. */
    fun playbackFileForMediaId(mediaId: String): File? = playbackCache.fileForMediaId(mediaId)

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
 * Process-wide handle so non-Hilt singletons (SpotiFLACSessionManager) can
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
