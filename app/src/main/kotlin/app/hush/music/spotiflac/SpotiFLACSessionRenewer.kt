package app.hush.music.spotiflac

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Keeps the native runtime's per-extension gateway sessions alive without a human.
 *
 * Each SpotiFLAC download extension holds its *own* signed session, and the Go
 * runtime only renews one while it is still valid: `signedSessionRefreshDue`
 * requires `now < expires_at`, and once a record is expired the runtime clears it
 * and raises a Cloudflare challenge. So a session that is allowed to lapse always
 * costs the user a manual verification.
 *
 * The gateway's `POST {base}/session/refresh` endpoint renews a session from its
 * `install_id` with no Turnstile, signed with the *record's own* secret exactly
 * like the runtime signs its requests. This class performs that call ahead of
 * expiry — from app start, from background work, and on demand — which is what
 * turns verification into a one-time step instead of a recurring chore.
 */
object SpotiFLACSessionRenewer {

    private const val TAG = "SpotiFLACRenew"

    /**
     * Renew once this much validity is left. The runtime's own window is one hour;
     * renewing earlier leaves room for a few missed background runs (doze, no
     * network) before a session would actually expire.
     */
    const val RENEW_WINDOW_SECONDS = 3 * 3600L

    /**
     * How often the background renewer runs, in minutes.
     *
     * Kept far below [RENEW_WINDOW_SECONDS] rather than merely under it, because the window is the
     * number of *chances* a session gets: a renewal is only accepted while the session is still
     * valid, and WorkManager defers under doze, so a cadence that matched the window left exactly
     * one attempt to be missed. A playback path also renews what is due, so this is the schedule
     * for a device that is not playing.
     */
    const val BACKGROUND_INTERVAL_MINUTES = 60L

    /** WorkManager's flex window in minutes; must stay below the interval above. */
    const val BACKGROUND_FLEX_MINUTES = 15L

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 20_000L
    private const val DEFAULT_REFRESH_PATH = "/session/refresh"
    private const val DEFAULT_PLATFORM = "extension"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The gateway sits behind Cloudflare and rejects non-browser-ish clients: a
     * plain `HttpURLConnection` request is answered with a bare
     * `403 {"error":"Forbidden"}` *before* signature validation. OkHttp is the
     * stack Hush already uses successfully against this same host, so renewal
     * goes through it too.
     *
     * Built per call rather than cached, so the relay address and proxy the user configured are the
     * ones this request uses. These calls are rare - a scheduled renewal, a playback-path one, or one
     * probe after a failed sweep - so a shared connection pool would buy almost nothing and a stale
     * client would silently keep using the route that is not working.
     */
    private fun relayClient(): OkHttpClient =
        SpotiFLACRelayStore.okHttp(
            endpoint = SpotiFLACRelayStore.endpoint(),
            readTimeoutMs = READ_TIMEOUT_MS,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
        )

    /** One extension's signed-session config plus its current record, if any. */
    data class ExtensionSession(
        val extensionId: String,
        val displayName: String?,
        val namespace: String,
        val appVersion: String,
        val expiresAtMillis: Long?,
        val hasSession: Boolean,
        val recordFile: File,
        /** The session id currently on disk, for tying a renewal verdict to the session it judged. */
        val sessionId: String? = null,
    ) {
        val isExpired: Boolean
            get() = expiresAtMillis?.let { it <= System.currentTimeMillis() } ?: false

        val remainingSeconds: Long?
            get() = expiresAtMillis?.let { (it - System.currentTimeMillis()) / 1000 }
    }

    data class RenewResult(
        val extensionId: String,
        val renewed: Boolean,
        val detail: String,
        val expiresAtMillis: Long? = null,
        /** The gateway says this session is gone: only a new verification can fix it. */
        val needsVerification: Boolean = false,
        /** The gateway would not rotate this session, and will likely work later. */
        val refused: Boolean = false,
        /**
         * The session this result is about, so the answer can be attached to it and drop away by
         * itself once a re-verification replaces it. Null when no session was read at all.
         */
        val sessionId: String? = null,
        /**
         * Whether the gateway was actually asked during this run.
         *
         * A run that asked nothing - not due yet, or answered out of the refusal memo - must not
         * replace the record of what the gateway last said. The memo in particular reports itself as
         * a refusal, so letting it write a verdict means the honest "this session is dead" from a
         * real answer is overwritten on the next app start by a "try later" the app invented, and
         * the row goes back to promising a renewal it cannot deliver.
         */
        val contactedGateway: Boolean = false,
    )

    /** A non-2xx refresh answer, carrying the code and the body the gateway explained itself with. */
    internal class SpotiFLACRenewalRefusedException(
        val statusCode: Int,
        val body: String,
    ) : IllegalStateException("HTTP $statusCode")

    /** What a failed renewal means, and whether it should be remembered. */
    internal data class RenewalFailure(
        val needsVerification: Boolean,
        val refused: Boolean,
        val remember: Boolean,
        val detail: String,
        /**
         * How long the gateway asked to be left alone for, when it said so.
         *
         * Only ever set from the gateway's own "temporarily blocked" answer, and only used to
         * decide when the *scheduled* renewer may ask again - never to stop a manual tap.
         */
        val retryAfterMs: Long? = null,
    )

    /** Every extracted download extension that declares a signed session. */
    fun sessions(context: Context): List<ExtensionSession> {
        val root = extensionsDir(context)
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> sessionFor(context, dir) }.sortedBy { it.extensionId }
    }

    /** Sessions that are already expired, i.e. will demand a manual verification. */
    fun expired(context: Context): List<ExtensionSession> = sessions(context).filter { it.isExpired }

    /** How long a gateway refusal is remembered before trying that source again. */
    private const val REJECTED_RETRY_MS = 6 * 3600_000L

    /** [BLOCK_MIN_MS], exposed so a test can pin the wait a bare 429 is given. */
    internal val BLOCK_MIN_MS_FOR_TEST = BLOCK_MIN_MS

    /**
     * Bounds on a gateway-announced block, so a nonsense `retry_after` cannot take over.
     *
     * The floor is a few minutes because the answer is always "not yet" when it is given at all,
     * and the ceiling is a day because the block is a rate limit rather than a lifecycle: past
     * that, asking once more is cheaper than never asking again.
     */
    private const val BLOCK_MIN_MS = 5 * 60_000L
    private const val BLOCK_MAX_MS = 24 * 3600_000L

    private fun rejectionPrefs(context: Context) =
        context.getSharedPreferences("spotiflac_session_renew", Context.MODE_PRIVATE)

    private fun rejectionKey(extensionId: String) = "rejected_$extensionId"

    private fun markRenewalRejected(
        context: Context,
        extensionId: String,
        sessionId: String,
        untilMs: Long = System.currentTimeMillis() + REJECTED_RETRY_MS,
    ) {
        runCatching {
            rejectionPrefs(context)
                .edit()
                .putString(
                    rejectionKey(extensionId),
                    "$sessionId|$untilMs",
                )
                .apply()
        }
    }

    /** When renewal was last refused for this exact session generation, else 0. */
    private fun renewalRejectedUntil(context: Context, extensionId: String, sessionId: String): Long =
        runCatching {
            val stored = rejectionPrefs(context).getString(rejectionKey(extensionId), null)
            if (!renewalRejectionActive(stored, sessionId)) return 0L
            parseRenewalRejection(stored)?.second ?: 0L
        }.getOrDefault(0L)

    /** `sessionId|untilMillis`, or null when the stored value is unusable. */
    internal fun parseRenewalRejection(stored: String?): Pair<String, Long>? {
        if (stored.isNullOrBlank()) return null
        val parts = stored.split("|", limit = 2)
        if (parts.size != 2) return null
        val until = parts[1].toLongOrNull() ?: return null
        if (parts[0].isBlank()) return null
        return parts[0] to until
    }

    /**
     * True while a refusal still applies. A re-verified source stores a new session
     * id, which clears the refusal so renewal is attempted again immediately.
     */
    internal fun renewalRejectionActive(stored: String?, sessionId: String): Boolean {
        val parsed = parseRenewalRejection(stored) ?: return false
        return parsed.first == sessionId && parsed.second > System.currentTimeMillis()
    }

    /**
     * A refusal of the whole client, rather than of one source's session.
     *
     * The two are genuinely different answers and were being kept in one place. A `401
     * SESSION_INVALID` is about *that* source's session and outlives nothing but a verification; a
     * `429` with `retry_after` is about the client's **address**, and arrives identically whichever
     * source is asked. Measured on the reporting device, one sweep got the same block for amazon,
     * deezer, qobuz-web and tidal-web, and the same install on a VPN was served normally - which is
     * what settles it: the gateway is refusing the connection, not the session.
     *
     * Remembering it per source is why the block did not look like a block. Each source learned the
     * answer for itself, so the app kept asking from the other five and every one of those requests
     * was another contact during a block that the gateway had already said lasts ~21h. Kept once for
     * the client instead, and everything that would ask the gateway asks this first.
     */
    data class RelayBlock(
        val untilMs: Long,
        val reason: String,
        /** How much of the wait is left, never negative. */
        val remainingMs: Long,
    )

    private fun relayBlockUntilKey() = "relay_block_until"

    private fun relayBlockReasonKey() = "relay_block_reason"

    private fun relayBlockRouteKey() = "relay_block_route"

    private fun relayBlockAddressKey() = "relay_block_address"

    /**
     * The client-wide block, or null when there is none, it has expired, or it was earned on a route
     * this device is no longer on.
     *
     * The route check is what stops a block outliving the address it is about. A block is the gateway
     * refusing a *route* - and a VPN coming up, or a proxy being switched on, produces a different one
     * without anything in the app being touched. While the record is about a route that is gone, the
     * app has no evidence about the current one, so it is not blocked: it tries, and if the gateway
     * refuses this route too then a fresh answer re-arms the countdown with the gateway's own timing.
     * [SpotiFLACRouteWatch] asks the gateway properly the moment the change is noticed.
     */
    fun relayBlock(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): RelayBlock? {
        val block = storedRelayBlock(context, nowMs) ?: return null
        val fingerprint = SpotiFLACRouteFingerprint
        val currentRoute = runCatching { fingerprint.current(context) }.getOrNull() ?: return block
        val currentAddress = runCatching { fingerprint.liveAddress(context) }.getOrNull()
        return relayBlockHeldAgainst(
            block = block,
            recordedRoute = storedRelayBlockRoute(context),
            recordedAddress = storedRelayBlockAddress(context),
            currentRoute = currentRoute,
            currentAddress = currentAddress,
        )
    }

    /**
     * Whether a stored block still applies to the route in use. Pure, so the rule is pinnable.
     *
     * Two questions, one answer, because the gateway refuses an *address* and the app can see two things
     * about which address it is on: the coarse route (transports plus proxy) and the network actually
     * carrying the traffic. The coarse half is what survives a restart; the other is what catches the
     * move a coarse fingerprint cannot see, and what a block earned before that move is no longer about.
     *
     * A block whose provenance cannot be read - no current route, no current address, or a record
     * written before either was kept - is held: the app may not be sure it is still blocked, but
     * discarding the gateway's own answer for lack of evidence is how a client ends up asking a blocking
     * gateway on every launch.
     */
    internal fun relayBlockHeldAgainst(
        block: RelayBlock?,
        recordedRoute: String?,
        recordedAddress: String? = null,
        currentRoute: String?,
        currentAddress: String? = null,
    ): RelayBlock? =
        block?.takeIf {
            val routeStillDescribes =
                currentRoute == null || SpotiFLACRouteFingerprint.stillDescribes(recordedRoute, currentRoute)
            val addressStillTheOne =
                currentAddress == null ||
                    !SpotiFLACRouteFingerprint.addressChanged(recordedAddress, currentAddress)
            routeStillDescribes && addressStillTheOne
        }

    /**
     * The block exactly as recorded, without asking whether its route is still the one in use.
     *
     * For the one caller that has to tell "no block" apart from "a block about somewhere else":
     * the watch, whose whole job is to disprove a block the device has moved out from under.
     */
    internal fun storedRelayBlock(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): RelayBlock? {
        val prefs = runCatching { rejectionPrefs(context) }.getOrNull() ?: return null
        return relayBlockAt(
            untilMs = prefs.getLong(relayBlockUntilKey(), 0L),
            storedReason = prefs.getString(relayBlockReasonKey(), null),
            nowMs = nowMs,
        )
    }

    /** The route a stored block was earned on, or null when it was recorded before routes were kept. */
    internal fun storedRelayBlockRoute(context: Context): String? =
        runCatching { rejectionPrefs(context).getString(relayBlockRouteKey(), null) }.getOrNull()

    /**
     * The *address* a stored block was earned on, or null when it was recorded before addresses were kept.
     *
     * Held here even though [SpotiFLACRouteFingerprint.liveAddress] says a network handle must not be
     * trusted across restarts, and the two are not in conflict: what that warning is about is *asking* -
     * a launch that decides the route moved would go and ask a blocking gateway again, every launch, on
     * the strength of a handle the platform reassigned. What is recorded here is only ever used to *stop*
     * trusting the gateway's answer, which costs no request at all and can only be wrong in the direction
     * of trying an engine once too often. It is also self-limiting: the answer that comes back is written
     * with the address in use at the time, so a stable address converges after the first one.
     */
    internal fun storedRelayBlockAddress(context: Context): String? =
        runCatching { rejectionPrefs(context).getString(relayBlockAddressKey(), null) }.getOrNull()

    /**
     * A stored block read against the clock. Pure, so the rule can be pinned without a context.
     *
     * A missing reason is re-derived rather than treated as no block: the deadline is the fact, and
     * the wording is only how it is said.
     */
    internal fun relayBlockAt(
        untilMs: Long,
        storedReason: String?,
        nowMs: Long,
    ): RelayBlock? {
        if (untilMs <= nowMs) return null
        val remaining = untilMs - nowMs
        return RelayBlock(
            untilMs = untilMs,
            reason = storedReason?.takeIf { it.isNotBlank() } ?: describeGatewayBlock(remaining),
            remainingMs = remaining,
        )
    }

    /**
     * Whether a run's answers prove the block is over.
     *
     * Only a session the gateway actually *renewed* counts. The weaker rule it replaces - "any
     * answer that was not a refusal" - looked reasonable and was wrong, and the device showed why: in
     * one run the gateway refused three sources with its own ~20h timing (`rejected_deezer`,
     * `rejected_amazon`, `rejected_qobuz-web` all written with that wait) while a fourth answered out
     * of its own record, so the run was read as "not refused" and the block the other three had just
     * established was cleared. Every source in that run agreed the client was blocked; the one that
     * merely did not fail was enough to throw it away.
     *
     * A run that asked nothing (not due, or answered out of a memo) proves nothing either, and a
     * transport failure says even less. Keeping the block when nothing was renewed costs at most the
     * wait the gateway itself asked for - the record expires on its own - which is why the
     * conservative direction is the honest one here, and the manual "try again now" always clears it.
     */
    internal fun relayBlockClearedBy(results: List<RenewResult>): Boolean =
        results.any { it.renewed }

    /** Records a block the gateway announced, for the client. */
    internal fun rememberRelayBlock(
        context: Context,
        waitMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        reason: String = describeGatewayBlock(waitMs),
    ) {
        val clamped = waitMs.coerceIn(BLOCK_MIN_MS, BLOCK_MAX_MS)
        runCatching {
            // Committed rather than applied, because of what the record is for: it is written at the
            // moment the gateway has just refused the client, which is also a moment the app is
            // likely to be backgrounded or stopped - and the reasoning above walked away from a real
            // run where the per-source rejections survived and this record did not.
            rejectionPrefs(context)
                .edit()
                .putLong(relayBlockUntilKey(), nowMs + clamped)
                .putString(relayBlockReasonKey(), reason)
                // Kept with the block, not checked at the start of it: the address behind a refusal can
                // change while the countdown runs, and the route is the only part of that the app can
                // see. See [SpotiFLACRouteFingerprint].
                .putString(
                    relayBlockRouteKey(),
                    runCatching { SpotiFLACRouteFingerprint.current(context) }.getOrNull(),
                )
                // And the address itself, so a block cannot outlive the network it was earned on. See
                // [storedRelayBlockAddress] for why a handle is acceptable here.
                .putString(
                    relayBlockAddressKey(),
                    runCatching { SpotiFLACRouteFingerprint.liveAddress(context) }.getOrNull(),
                )
                .commit()
        }
    }

    /** The relay's own health endpoint, which answers a client-wide block without a signature. */
    private const val HEALTH_PATH = "/health"

    /** How often the *same* route may be asked again, so a burst of failures on one route asks once. */
    private const val PROBE_MIN_INTERVAL_MS = 60_000L

    /**
     * The shortest gap between two probes whatever the route says.
     *
     * One network change does not produce one event. The platform reports the old network going away,
     * the new one arriving and then its capabilities changing, and the address is still being assigned
     * while the first of those is delivered - so a handful of probes would otherwise leave in the same
     * second, asking a blocking gateway several times about an address that is not settled yet. This
     * collapses them into the single question that was meant.
     */
    internal const val PROBE_SETTLE_MS = 5_000L

    @Volatile
    private var lastProbeAtMs = 0L

    /**
     * The route the last probe was about, so the interval above can bound repeats of *that* route
     * rather than every question the app ever asks.
     *
     * The distinction is the whole point of asking on a route change: what the throttle protects is a
     * blocking gateway from being asked again about an address it has already refused, and a different
     * route is a different address. Keyed on time alone, the second half of "switch the Wi-Fi off and
     * on again" - the half that lands on the blocked network - was suppressed for a whole minute, and
     * the first play after it walked the entire provider chain instead of going straight to YouTube.
     */
    @Volatile
    private var lastProbeRoute: String? = null

    /**
     * Whether a probe about [route] may be made, given when the last one was and what it was about.
     *
     * Pure, so both halves of the rule - a repeat of one route is throttled, a move to another is not
     * - can be pinned without a network or a clock.
     */
    internal fun probeIsDue(
        route: String,
        lastRoute: String?,
        sinceLastMs: Long,
    ): Boolean {
        if (sinceLastMs < PROBE_SETTLE_MS) return false
        if (route == lastRoute && sinceLastMs < PROBE_MIN_INTERVAL_MS) return false
        return true
    }

    /** Takes the probe slot for [route], or reports that a recent question already covers it. */
    private fun takeProbeSlot(route: String): Boolean {
        val now = System.currentTimeMillis()
        if (!probeIsDue(route, lastProbeRoute, now - lastProbeAtMs)) return false
        lastProbeAtMs = now
        lastProbeRoute = route
        return true
    }

    /**
     * The route a probe is about, at the resolution a probe needs.
     *
     * [SpotiFLACRouteFingerprint.live] rather than [SpotiFLACRouteFingerprint.current]: the question
     * here is "has anything about where this traffic leaves from changed", and two Wi-Fi networks are
     * the same answer to the coarse fingerprint while being different addresses to the gateway - which
     * is the difference the gateway is refusing it on.
     */
    private fun probeRouteKey(context: Context): String =
        runCatching { SpotiFLACRouteFingerprint.live(context) }.getOrDefault("unknown")

    /**
     * Asks the relay whether it is blocking this client, and records the answer if it is.
     *
     * Nothing inside a sweep can tell "these catalogues do not have this track" from "the gateway
     * is refusing this address", because what the block actually kills is the *extensions'* own
     * calls. Measured on the blocked device, the sweep failed with a provider 404, an invalid ASIN
     * and a metadata stall and not one `429` - so a client-wide block looked like every catalogue
     * answering "no" at once, and it was remembered as an unfinished sweep that would be retried
     * the same way. The relay's `/health` answers it directly and needs no session, so a sweep that
     * has already come up empty spends one cheap request learning whether the cause was the client.
     *
     * Only ever asked *after* a sweep has failed, and throttled when it is: asking the relay things
     * is what the block is a reaction to, and a probe that ran on its own schedule would be the
     * same mistake in a smaller size.
     *
     * @return the block when the relay is holding one, else null.
     */
    /**
     * What any relay answer means for the client: the wait to observe, or null when the relay is not
     * blocking. Pure, so the rule can be pinned without a network, and shared by every request that
     * can learn the block: `/health` from a failed sweep, from a route change and from SpotiFLAC being
     * switched on, and an extension's renewal answered `429`.
     *
     * A `429` counts as the client's answer even when the gateway does not say for how long - the
     * shortest honest wait is used rather than ignoring it, and the next probe either extends it with
     * real timing or clears it. Anything else is not a block: these answers are about one endpoint,
     * and a `401` or `404` says nothing about whether this connection is being refused.
     */
    internal fun blockWaitFromAnswer(statusCode: Int, body: String): Long? =
        if (statusCode == 429) retryAfterMs(body) ?: BLOCK_MIN_MS else null

    suspend fun probeRelayBlock(
        context: Context,
        reason: String = "sweep-failed",
    ): RelayBlock? {
        relayBlock(context)?.let { return it }
        if (!takeProbeSlot(probeRouteKey(context))) return null
        val endpoint = SpotiFLACRelayStore.endpoint()
        val url = endpoint.baseUrl.trimEnd('/') + HEALTH_PATH
        return withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request.Builder()
                        .url(url)
                        .get()
                        .header("Accept", "application/json")
                        .build()
                relayClient().newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val waitMs = blockWaitFromAnswer(response.code, text)
                    if (waitMs == null) {
                        SpotiFLACDiag.log(
                            "relay probe ($reason): HTTP ${response.code} - the client is not blocked",
                        )
                        return@use null
                    }
                    SpotiFLACDiag.log(
                        "relay probe ($reason): HTTP 429 - ${describeGatewayBlock(waitMs)} " +
                            "body=${text.take(160)}",
                    )
                    rememberRelayBlock(
                        context = context,
                        waitMs = waitMs,
                        reason = "gateway is refusing this connection (HTTP 429)",
                    )
                    relayBlock(context)
                }
            }.getOrNull()
        }
    }

    /**
     * What one cheap question about the route in use established.
     *
     * [asked] is false when the question was not worth making - a probe a moment ago already answered
     * it, or a remembered block already does - and a caller must not read that as "this route is
     * fine". The route watch does read it: its whole job is to tell a route the gateway refuses from
     * one it serves, and those two are indistinguishable in a boolean.
     *
     * [answering] is the third state, and it is not the same as [asked]: a probe whose request never
     * reached the gateway asked, and learned nothing. A route change is often accompanied by the
     * network still coming up, so this is the common case rather than an edge one - and reading it as
     * "served" is what made the watch *clear* everything the previous route had recorded and treat the
     * new one as checked, so the first play afterwards walked the whole provider chain and the block
     * went unrecorded for the whole session.
     */
    data class RouteProbe(
        val block: RelayBlock?,
        val asked: Boolean,
        val answering: Boolean = false,
    ) {
        /** The gateway answered without refusing: the only proof a route is being served. */
        val served: Boolean get() = block == null && answering
    }

    /**
     * Asks the gateway about the route in use, touching no session.
     *
     * The route watch needs this the moment it sees the device move onto a network, because the answer
     * decides what the next play costs: a route the gateway refuses has to be *recorded* as a block,
     * and until it was, the resolver walked the whole provider chain - measured at 96s for a six-source
     * chain - before falling back to YouTube, while every one of those requests was another contact
     * during a block the gateway had asked not to be contacted in.
     *
     * One GET of the relay's health endpoint: no signature, no session state, and no effect on a
     * session that works. A remembered block is *not* cleared first - throwing away what the gateway
     * said is what [reask] is for - because the question here is narrower: is this route served at all?
     */
    suspend fun probeRoute(
        context: Context,
        reason: String = "route-changed",
    ): RouteProbe {
        relayBlock(context)?.let { return RouteProbe(it, asked = false) }
        if (!takeProbeSlot(probeRouteKey(context))) return RouteProbe(null, asked = false)
        val answering = askRelayHealth(context, reason)
        return RouteProbe(block = relayBlock(context), asked = true, answering = answering)
    }

    /**
     * Forgets the block: either the gateway disproved it by answering, or the user asked to retry.
     */
    internal fun clearRelayBlock(context: Context) {
        runCatching {
            rejectionPrefs(context)
                .edit()
                .remove(relayBlockUntilKey())
                .remove(relayBlockReasonKey())
                .remove(relayBlockRouteKey())
                .remove(relayBlockAddressKey())
                .apply()
        }
    }

    /** Drops one source's remembered refusal, so the next run actually asks the gateway. */
    internal fun clearRenewalRejection(context: Context, extensionId: String) {
        runCatching {
            rejectionPrefs(context).edit().remove(rejectionKey(extensionId)).apply()
        }
    }

    /**
     * Asks the gateway again despite a remembered block: the user said to, or the route changed.
     *
     * The block is remembered against the address, and the app cannot see that address - only the
     * route that produces it, which is what [SpotiFLACRouteWatch] watches so a VPN coming up is
     * noticed rather than waited out. The tap remains for everything that cannot be seen at all (a
     * different relay, a captive portal solved in a browser), so this is allowed to ask once - and
     * its answer either clears the block (it had lifted) or re-arms it with the gateway's own
     * timing, which is why this hands back what is left rather than a boolean.
     *
     * The per-source refusals are dropped with it: a block wrote those too, so clearing only the
     * block would leave every source answering out of its own memo and nothing asked at all.
     */
    suspend fun retryRelayNow(
        context: Context,
        reason: String = "manual-retry",
    ): RelayBlock? = reask(context, reason).block

    /**
     * What asking a blocking gateway again established.
     *
     * A caller that is about to act on the answer - the route watch replaying a held track - has to
     * tell three states apart, not two: the gateway refused this route (still blocked), it served a
     * renewal (blocked no longer), or nothing came back at all. The third is the one a boolean loses,
     * and it is common: a route change often happens *because* the network is moving, and a request
     * that died in a tunnel must not be read as "the gateway is fine now".
     */
    data class ReaskOutcome(
        val block: RelayBlock?,
        val renewedAny: Boolean,
        val asked: Boolean,
        val answering: Boolean,
    ) {
        /**
         * Whether the gateway is *proven* to be serving this client again.
         *
         * Proof is something the gateway itself said, and the only thing that counts is a request it
         * answered without refusing: a request that never reached it is evidence about the network,
         * not about the gateway, and replaying a held track into a dead connection is the same wait
         * with extra steps. When this is false and [block] is null the answer is "unknown", and the
         * caller stays put - the next genuine answer (a verification landing, or the next route
         * change) is what moves it.
         */
        val servedAgain: Boolean get() = block == null && answering
    }

    /**
     * Drops everything a block wrote and asks the gateway once, reporting what came back.
     *
     * The per-source refusals are dropped with the block: a block wrote those too, so clearing only the
     * block would leave every source answering out of its own memo and nothing asked at all.
     *
     * Two asks, because they answer different questions. The renewals are the real thing - a route that
     * can mint sessions is a route that works - but they cannot answer at all when a long block has
     * already cost the app its sessions: there is nothing to renew, and "nothing renewed" would read as
     * "no answer" for a route that is in fact fine. Measured on the blocked device, that is exactly the
     * state it was in (`renewed=0 of 4 [amazon:no session to renew - verify this source, ...]`), so the
     * relay's health endpoint is asked too: it needs no signature, and its answer is about the address
     * rather than about a session.
     */
    internal suspend fun reask(
        context: Context,
        reason: String,
    ): ReaskOutcome {
        clearRelayBlock(context)
        runCatching { sessions(context).forEach { clearRenewalRejection(context, it.extensionId) } }
        val results = runCatching { renewAll(context, force = true, reason = reason) }.getOrNull()
        val answering = askRelayHealth(context, reason)
        return ReaskOutcome(
            block = relayBlock(context),
            renewedAny = results?.any { it.renewed } == true,
            asked = results != null,
            answering = answering,
        )
    }

    /**
     * One GET of the relay's health endpoint: did it answer this route, and did it refuse the client?
     *
     * Returns whether the relay served the request. A refusal records the block, so the caller's next
     * read of [relayBlock] re-arms the countdown from the gateway's own timing rather than from a guess.
     */
    private suspend fun askRelayHealth(
        context: Context,
        reason: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = SpotiFLACRelayStore.endpoint()
            val request =
                Request.Builder()
                    .url(endpoint.baseUrl.trimEnd('/') + HEALTH_PATH)
                    .get()
                    .header("Accept", "application/json")
                    .build()
            relayClient().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val waitMs = blockWaitFromAnswer(response.code, body)
                if (waitMs == null) {
                    SpotiFLACDiag.log(
                        "relay health ($reason): HTTP ${response.code} - this route is being served",
                    )
                    true
                } else {
                    SpotiFLACDiag.log(
                        "relay health ($reason): HTTP 429 - ${describeGatewayBlock(waitMs)}",
                    )
                    rememberRelayBlock(
                        context = context,
                        waitMs = waitMs,
                        reason = "gateway is refusing this connection (HTTP 429)",
                    )
                    false
                }
            }
        }.getOrDefault(false)
    }

    /** The remaining wait as a screen counting it down says it. */
    internal fun formatBlockRemaining(remainingMs: Long): String {
        val minutes = (remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L
        return when {
            minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }

    /**
     * Renews every session that is inside [RENEW_WINDOW_SECONDS] of expiry.
     *
     * @param force renew regardless of remaining validity (manual "refresh now").
     */
    fun renewAll(
        context: Context,
        force: Boolean = false,
        reason: String = "scheduled",
    ): List<RenewResult> {
        val candidates = sessions(context)
        if (candidates.isEmpty()) {
            SpotiFLACDiag.log("session renew ($reason): no extension sessions found")
            return emptyList()
        }
        // First make sure every record that the app has ever earned is present and
        // usable: mirror it into the vault, and revive one whose record name moved
        // (extension version bump) or which the runtime cleared after expiry.
        val adoptions = reconcile(context, candidates, reason)
        val results = (if (adoptions > 0) sessions(context) else candidates)
            .map { renew(context, it, force) }
        val renewed = results.count { it.renewed }
        SpotiFLACDiag.log(
            "session renew ($reason): renewed=$renewed of ${results.size} [" +
                results.joinToString(", ") { "${it.extensionId}:${it.detail}" } + "]",
        )
        rememberVerdicts(context, results)
        // Then drop the material of every session the gateway itself called gone.
        //
        // Recording the answer is not enough on its own, and the gap was not cosmetic. A session
        // lives in two places - its record file and the vault's copy - and [reconcile] above put
        // the vault's copy back on *every* run, including the runs after the gateway had answered
        // `401 SESSION_INVALID`. So the loop was: revive an already-dead session, be told it is
        // dead, revive it again on the next start. Measured on device: `session reconcile
        // (background): amazon record expired -> revived from VAULT_FILE`, immediately followed by
        // `session renew attempt failed id=amazon signed=amzn@2.3.10 HTTP 401`, on every route
        // change, indefinitely.
        //
        // Two user-visible consequences, both reported: the runtime's preflight is *local*, so a
        // revived record with a future expiry made the source look verified while every provider
        // fetch was answered 401 - the sweep failed and the track fell back to YouTube - and
        // pressing Verify replied "already verified" instead of raising the check that was the
        // only thing that could have fixed it. Deleting the refused session is what makes the
        // source honestly unverified, which is the state that raises a challenge.
        val forgotten =
            results
                .filter(::shouldForgetSessionAfterRenewal)
                .count { forgetGatewayRejectedSession(context, it.extensionId) }
        if (forgotten > 0) {
            SpotiFLACDiag.log(
                "session renew ($reason): dropped $forgotten session(s) the gateway refused, " +
                    "so their sources can be verified again",
            )
        }
        // Any answer that was not a refusal is proof the gateway is serving this client again, which
        // is the only evidence that a block has lifted. Recorded here rather than in the failure
        // path so it holds however the block was cleared - by time passing, or by the user changing
        // the network under it.
        if (relayBlockClearedBy(results)) clearRelayBlock(context)
        return results
    }

    /**
     * Keeps what the gateway said about each session, for a screen opened after the fact.
     *
     * The source rows are built from the session *record*, and a record the gateway has already
     * thrown away looks exactly like a healthy one. Measured on device: deezer and qobuz each held an
     * unexpired record while the gateway answered `401 SESSION_INVALID`, and the row above still
     * promised "renews automatically". Nothing but the gateway can tell those apart, so its answer is
     * recorded here - keyed by the session it judged, so it lapses by itself when a re-verification
     * replaces that session.
     */
    private fun rememberVerdicts(context: Context, results: List<RenewResult>) {
        if (results.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            val existing = readVerdicts(context).toMutableMap()
            val recorded = results.filter(::verdictIsWorthRecording)
            // Nothing learned means nothing written: a run that only re-read the memo must leave the
            // earlier, real answer alone rather than replace it with its own guess.
            if (recorded.isEmpty()) return@runCatching
            recorded.forEach { result ->
                existing[result.extensionId] = SpotiFLACSessionVerdict(
                    sourceId = result.extensionId,
                    sessionId = result.sessionId,
                    outcome = verdictOutcome(result),
                    checkedAtMs = now,
                    detail = result.detail,
                )
            }
            writeVerdicts(context, existing.values.toList())
        }.onFailure { SpotiFLACDiag.log("session verdict write failed: ${it.message}") }
    }

    /**
     * Whether a result is new information about a session.
     *
     * A result says something about the session when the gateway was asked, or when the app worked
     * out on its own that the session cannot serve (a record minted for a version this source no
     * longer signs with). Everything else - "not due (355m left)", an unreadable record, or the
     * refusal memo repeating itself - is a statement about *this run*, and recording it would erase
     * what the gateway last said.
     */
    internal fun verdictIsWorthRecording(result: RenewResult): Boolean =
        result.contactedGateway || result.needsVerification

    /** What a renewal result means for the row that will show it. */
    internal fun verdictOutcome(result: RenewResult): SpotiFLACSessionVerdict.Outcome = when {
        result.renewed -> SpotiFLACSessionVerdict.Outcome.RENEWED
        result.needsVerification -> SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION
        result.refused -> SpotiFLACSessionVerdict.Outcome.REFUSED
        else -> SpotiFLACSessionVerdict.Outcome.SKIPPED
    }

    /**
     * Drops the session material for a source the gateway has turned down, so a check can be raised.
     *
     * The runtime's preflight only looks *locally* - a record with an id, a secret and a future
     * expiry passes it - so a session the gateway has already refused still counts as verified to the
     * runtime. Measured on device: pressing Verify for a source whose session the gateway had answered
     * `401 SESSION_INVALID` for replied "qobuz-web is already verified", i.e. the one source that
     * needed the check could not be given one.
     *
     * What is dropped is only what the gateway has refused: the records carrying that exact session,
     * plus the vault's copy of it, which would otherwise be restored straight back on the next screen
     * open and re-arm the same dead session. The install id survives, and that is what a fresh
     * verification is minted against.
     *
     * @return true when something was dropped, i.e. the source's state has changed.
     */
    fun forgetGatewayRejectedSession(context: Context, sourceId: String): Boolean {
        val session = runCatching { sessions(context) }.getOrNull()
            ?.firstOrNull { it.extensionId == sourceId } ?: return false
        // Only a session the gateway has actually refused is forgotten - a refusal attached to some
        // *other* session, or to none, says nothing about the one on disk.
        val rejected = SpotiFLACSessionVerdictReport.applies(
            verdict = lastVerdicts(context)[sourceId],
            currentSessionId = session.sessionId,
        ) ?: return false
        val refusedId = rejected.sessionId?.trim().orEmpty()
        if (refusedId.isEmpty()) return false

        var dropped = 0
        // Every record carrying the refused session, under whatever extension version wrote it: the
        // gateway binds a session to its minting version, so a sibling generation cannot serve this
        // source either (refresh answers 403 for a version swap), and leaving one behind would let
        // the vault restore it.
        recordsDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.forEach { file ->
                if (runCatching { readRecord(file) }.getOrNull()?.get("session_id") != refusedId) return@forEach
                if (file.delete()) dropped++
            }
        signedSessionConfigOf(context, sourceId)?.let { config ->
            val vault = File(
                File(context.filesDir, "spotiflac/session_vault"),
                SpotiFLACSessionVault.vaultFileName(
                    sourceId,
                    session.namespace,
                    config.baseUrl,
                    config.platform,
                ),
            )
            val stored = runCatching { readRecord(vault) }.getOrNull()?.get("session_id")
            if (stored == refusedId && vault.delete()) dropped++
        }
        if (dropped > 0) {
            SpotiFLACDiag.log(
                "session forgotten id=$sourceId: gateway rejected ${refusedId.take(14)}..., " +
                    "$dropped record(s) dropped so a check can be raised",
            )
        }
        return dropped > 0
    }

    /** Every source's last renewal verdict, keyed by source id. Empty when nothing has run yet. */
    fun lastVerdicts(context: Context): Map<String, SpotiFLACSessionVerdict> =
        runCatching { readVerdicts(context) }.getOrDefault(emptyMap())

    private fun readVerdicts(context: Context): Map<String, SpotiFLACSessionVerdict> {
        val file = verdictsFile(context)
        if (!file.isFile) return emptyMap()
        return json.decodeFromString<SpotiFLACSessionVerdictIndex>(file.readText())
            .verdicts
            .associateBy { it.sourceId }
    }

    private fun writeVerdicts(context: Context, verdicts: List<SpotiFLACSessionVerdict>) {
        val file = verdictsFile(context)
        file.parentFile?.mkdirs()
        val payload = json.encodeToString(SpotiFLACSessionVerdictIndex(verdicts = verdicts))
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
    }

    private fun verdictsFile(context: Context): File =
        File(context.filesDir, "spotiflac/session_verdicts.json")

    @kotlinx.serialization.Serializable
    private data class SpotiFLACSessionVerdictIndex(
        val version: Int = 1,
        val verdicts: List<SpotiFLACSessionVerdict> = emptyList(),
    )

    /**
     * Puts back any session the runtime cannot currently see, from what the app already earned.
     *
     * A session record's *file name* is derived from the extension's app version, so a registry
     * update that bumps a version leaves a verified session sitting under the old name - and the
     * source then reports as needing a challenge it has already passed, which is what makes a car
     * user solve the same check twice. The vault is keyed by extension rather than by version, so
     * this writes such a session back under the name in use now.
     *
     * Cheap and side-effect free when there is nothing to do: it reads the per-extension records
     * and returns without writing anything unless one is genuinely unusable.
     *
     * @return the extension ids that became usable because of this call.
     */
    fun restoreFromVault(context: Context): List<String> {
        val candidates = runCatching { sessions(context) }.getOrNull() ?: return emptyList()
        val unusable = candidates.filter { !it.hasSession || it.isExpired }.map { it.extensionId }
        if (unusable.isEmpty()) return emptyList()
        val adopted = runCatching { reconcile(context, candidates, "restore") }.getOrDefault(0)
        if (adopted == 0) return emptyList()
        val usableNow = runCatching { sessions(context) }
            .getOrDefault(emptyList())
            .filter { it.hasSession && !it.isExpired }
            .map { it.extensionId }
            .toSet()
        return unusable.filter { it in usableNow }
    }

    /**
     * Mirrors live records into the durable vault and revives the unusable ones.
     *
     * A session is only ever lost through its *file*: the runtime derives the
     * record name from the extension's app version, so a registry bump hides the
     * session behind a new name, and the Go runtime deletes a record once it has
     * expired. Both cases are recoverable from material the app already holds, and
     * the renewer can then refresh the revived session back to valid without a
     * Cloudflare round trip.
     *
     * @return how many records were adopted, so the caller can re-read them.
     */
    private fun reconcile(
        context: Context,
        candidates: List<ExtensionSession>,
        reason: String,
    ): Int {
        var adopted = 0
        candidates.forEach { session ->
            val record = session.recordFile
            val text = runCatching { record.takeIf { it.isFile }?.readText() }.getOrNull()
            if (SpotiFLACSourceAuth.recordUsable(text, System.currentTimeMillis())) {
                SpotiFLACSessionVault.remember(context, record, session.extensionId)
                return@forEach
            }
            val before = if (text.isNullOrBlank()) "record missing" else "record expired"
            val config = signedSessionConfigOf(context, session.extensionId)
            val result = SpotiFLACSessionVault.adopt(
                context = context,
                extensionId = session.extensionId,
                liveRecord = record,
                namespace = session.namespace,
                baseUrl = config?.baseUrl.orEmpty(),
                platform = config?.platform.orEmpty().ifBlank { DEFAULT_PLATFORM },
                currentAppVersion = session.appVersion,
            )
            if (result.adopted) {
                adopted++
                SpotiFLACDiag.log(
                    "session reconcile ($reason): ${session.extensionId} $before -> " +
                        "${result.detail} (minted ${result.appVersion})",
                )
                SpotiFLACSessionVault.remember(context, record, session.extensionId)
            } else if (!text.isNullOrBlank()) {
                // Nothing to revive with, but the material is still worth keeping:
                // a later run may have a working gateway again.
                SpotiFLACSessionVault.remember(context, record, session.extensionId)
            }
        }
        return adopted
    }

    private fun renew(context: Context, session: ExtensionSession, force: Boolean): RenewResult {
        val record = runCatching { readRecord(session.recordFile) }.getOrNull()
            ?: return RenewResult(session.extensionId, false, "unreadable session record")
        val mintedVersion = record["app_version"].orEmpty().ifBlank { session.appVersion }
        // A session is bound to the exact app version that minted it, and the gateway will not
        // migrate one forward: `/v2/session/refresh` answers a bare 403 when the signature carries
        // another version (measured against the live gateway with a session minted as `amzn@2.3.8`
        // and its extension since updated to `amzn@2.3.10`), while the same call signed as the
        // minting version is accepted. Renewing a record the extension can no longer sign with only
        // rotates a secret nothing can use - and reporting that as "renewed" is how a source came to
        // read as verified in this screen while the runtime had already gated it as unverified. There
        // is one thing that fixes it, so that is what gets said.
        //
        // Checked before the "not due yet" shortcut on purpose: a session this source cannot sign
        // with is not a source that is fine until later, it is one that cannot download now - and
        // "not due (355m left)" was exactly how that got reported.
        if (!SpotiFLACSourceAuth.versionsBound(mintedVersion, session.appVersion)) {
            SpotiFLACDiag.log(
                "session renew skipped id=${session.extensionId}: record minted as $mintedVersion, " +
                    "extension now signs as ${session.appVersion}",
            )
            return RenewResult(
                session.extensionId,
                renewed = false,
                detail = "minted as $mintedVersion but this source now signs as " +
                    "${session.appVersion} - verify again",
                expiresAtMillis = session.expiresAtMillis,
                needsVerification = true,
                sessionId = session.sessionId,
            )
        }

        if (!session.hasSession) {
            // Not "nothing to do": a source that declares a signed session and has no usable record
            // cannot download at all, and the only thing that produces one is a verification. Say so,
            // so the line under the button sends the user to the check instead of leaving them to
            // guess whether the button did anything.
            return RenewResult(
                session.extensionId,
                renewed = false,
                detail = "no session to renew - verify this source",
                expiresAtMillis = session.expiresAtMillis,
                needsVerification = true,
                sessionId = session.sessionId,
            )
        }

        val remaining = session.remainingSeconds
        if (!renewalDue(remaining, force)) {
            val minutes = remaining?.let { it / 60 }
            return RenewResult(
                session.extensionId,
                false,
                if (minutes != null) "not due (${minutes}m left)" else "not due",
                session.expiresAtMillis,
                sessionId = session.sessionId,
            )
        }

        val sessionId = record["session_id"].orEmpty()
        val sessionSecret = record["session_secret"].orEmpty()
        val installId = record["install_id"].orEmpty()
        if (sessionId.isEmpty() || sessionSecret.isEmpty() || installId.isEmpty()) {
            return RenewResult(
                session.extensionId,
                false,
                "incomplete session record",
                sessionId = sessionId.takeIf { it.isNotEmpty() },
            )
        }

        val rejectedUntil = renewalRejectedUntil(context, session.extensionId, sessionId)
        // A user pressing renew is asking now. The memo exists so the *background* renewer does not
        // hammer a gateway that already said no - letting it answer a manual tap too meant the
        // button reported "renewed 1 of 4" while three sources were never even asked, up to six
        // hours after a single refusal. The button tries; a fresh refusal re-arms the memo.
        if (renewalRefusalApplies(rejectedUntil, System.currentTimeMillis(), userAsked = force)) {
            val minutes = (rejectedUntil - System.currentTimeMillis()) / 60_000
            return RenewResult(
                session.extensionId,
                false,
                "gateway refuses renewal (retry in ${minutes}m)",
                session.expiresAtMillis,
                refused = true,
                sessionId = sessionId,
            )
        }

        // The gateway binds a session to the app version that minted it, so the
        // signature must carry the *record's* version (a version mismatch was already
        // answered above, because no attempt can succeed for it).
        val signVersions = signVersionsFor(mintedVersion, session.appVersion)

        var lastError: Throwable? = null
        var refreshed: Map<String, String> = emptyMap()
        var usedVersion: String? = null
        for (version in signVersions) {
            val attempt = runCatching { requestRefresh(context, session, record, version) }
            val failure = attempt.exceptionOrNull()
            if (failure == null) {
                refreshed = attempt.getOrDefault(emptyMap())
                usedVersion = version
                break
            }
            lastError = failure
            SpotiFLACDiag.log(
                "session renew attempt failed id=${session.extensionId} signed=$version ${failure.message}",
            )
            // A refusal of the *signature* is worth one more attempt under the extension's current
            // version; a dead session or a transport failure is not version related.
            val refusal = failure as? SpotiFLACRenewalRefusedException
            if (refusal?.statusCode != 403) break
        }

        if (lastError != null && usedVersion == null) {
            val outcome = classifyRenewalFailure(lastError!!)
            // Remembered against this session generation so neither a dead session nor a refused one
            // is re-asked on every app start and every playback - and so the log stays readable. A
            // fresh verification mints a new session id, which clears it immediately.
            if (outcome.remember) {
                // The gateway's own timing is honoured where it gave one: a block is revisited when
                // it said to revisit it, not on a schedule the app picked for it.
                markRenewalRejected(
                    context,
                    session.extensionId,
                    sessionId,
                    untilMs =
                        outcome.retryAfterMs
                            ?.let { System.currentTimeMillis() + it }
                            ?: System.currentTimeMillis() + REJECTED_RETRY_MS,
                )
            }
            if (outcome.retryAfterMs != null) {
                // The gateway refused the client, not this source, so the answer is kept for
                // everyone: the other sources (and every playback sweep) would each spend their own
                // request learning the same thing.
                rememberRelayBlock(context, outcome.retryAfterMs)
            }
            return RenewResult(
                session.extensionId,
                false,
                outcome.detail,
                needsVerification = outcome.needsVerification,
                refused = outcome.refused,
                sessionId = sessionId,
                contactedGateway = true,
            )
        }

        if (refreshed.isEmpty() && usedVersion == mintedVersion) {
            return RenewResult(
                session.extensionId,
                false,
                "gateway returned no session",
                session.expiresAtMillis,
                sessionId = sessionId,
                contactedGateway = true,
            )
        }

        // Re-bind the record to the version the gateway actually renewed under, so
        // the next request signs the way this one just proved works.
        val migrated = usedVersion != null && usedVersion != mintedVersion
        if (migrated) {
            refreshed = refreshed + ("app_version" to usedVersion!!)
            SpotiFLACDiag.log(
                "session migrated id=${session.extensionId} $mintedVersion -> $usedVersion",
            )
        }
        val newExpiry = refreshed["expires_at"]?.let(::parseExpiryMillis) ?: session.expiresAtMillis

        return runCatching {
            val applied = applyRefresh(session, record, refreshed)
            if (applied || migrated) {
                SpotiFLACDiag.log(
                    "session renewed id=${session.extensionId} expires=${refreshed["expires_at"]}",
                )
                Timber.tag(TAG).i("Renewed SpotiFLAC session for %s", session.extensionId)
                SpotiFLACSessionVault.remember(context, session.recordFile, session.extensionId)
                RenewResult(
                    session.extensionId,
                    true,
                    if (migrated) "renewed (migrated to $usedVersion)" else "renewed",
                    newExpiry,
                    // The gateway rotates the secret of the *same* session id, so a renewal leaves the
                    // generation alone - which is why a refusal attached to it stays attached until a
                    // verification replaces the session outright.
                    sessionId = refreshed["session_id"]?.takeIf { it.isNotBlank() } ?: sessionId,
                    contactedGateway = true,
                )
            } else {
                RenewResult(
                    session.extensionId,
                    false,
                    "superseded by a newer session",
                    newExpiry,
                    sessionId = sessionId,
                    contactedGateway = true,
                )
            }
        }.getOrElse { error ->
            RenewResult(
                session.extensionId,
                false,
                "write failed: ${error.message}",
                sessionId = sessionId,
                contactedGateway = true,
            )
        }
    }

    /**
     * The signing config for a renewal, preferring the manifest but falling back to
     * the record itself.
     *
     * A record stores everything the gateway needs to identify the session
     * (`base_url`, `platform`, and the version it was minted under), so a renewal
     * still works when the extension's manifest has been replaced, removed and
     * re-added, or is simply unreadable. That matters because the alternative is
     * asking for a challenge the user has already paid for once.
     */
    private fun configForRefresh(
        context: Context,
        session: ExtensionSession,
        record: Map<String, String>,
        signVersion: String,
    ): SignedSessionConfig? {
        val manifest = signedSessionConfigOf(context, session.extensionId)
        val baseUrl = record["base_url"]
            ?.takeIf { it.isNotBlank() }
            ?: manifest?.baseUrl
            ?: return null
        return SignedSessionConfig(
            baseUrl = baseUrl,
            appVersion = signVersion,
            platform = record["platform"]?.takeIf { it.isNotBlank() }
                ?: manifest?.platform
                ?: SpotiFLACRequestSigner.DEFAULT_PLATFORM,
            schemeLabel = manifest?.schemeLabel ?: SpotiFLACRequestSigner.DEFAULT_SCHEME_LABEL,
            headerPrefix = manifest?.headerPrefix ?: SpotiFLACRequestSigner.DEFAULT_HEADER_PREFIX,
            timeWindowSeconds = manifest?.timeWindowSeconds
                ?: SpotiFLACRequestSigner.DEFAULT_TIME_WINDOW_SECONDS,
            refreshPath = manifest?.refreshPath ?: DEFAULT_REFRESH_PATH,
        )
    }

    /**
     * POSTs the gateway's refresh endpoint signed with the record's own secret.
     * Returns the session fields the gateway sent back (empty when it accepted the
     * call but had nothing to rotate).
     */
    private fun requestRefresh(
        context: Context,
        session: ExtensionSession,
        record: Map<String, String>,
        signVersion: String,
    ): Map<String, String> {
        val config = configForRefresh(context, session, record, signVersion)
            ?: throw IllegalStateException("no signed-session config for ${session.extensionId}")
        val body = buildJsonObject { put("install_id", record["install_id"].orEmpty()) }.toString()
        val url = config.baseUrl.trimEnd('/') + "/" + config.refreshPath.trimStart('/')
        // The signature covers the path of the *resolved* URL. The runtime builds
        // the request with url.ResolveReference and signs parsed.EscapedPath(), so
        // with a base of https://host/v2 the signed path is /v2/session/refresh —
        // signing the relative /session/refresh is rejected as Forbidden.
        val signedPath = runCatching { java.net.URI(url).path }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: config.refreshPath
        val headers = SpotiFLACRequestSigner.signedHeaders(
            method = "POST",
            path = signedPath,
            body = body,
            sessionId = record["session_id"].orEmpty(),
            sessionSecret = record["session_secret"].orEmpty(),
            appVersion = signVersion,
            platform = config.platform,
            schemeLabel = config.schemeLabel,
            headerPrefix = config.headerPrefix,
            timeWindowSeconds = config.timeWindowSeconds,
        )

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("User-Agent", "SpotiFLAC-Mobile/$signVersion")
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        relayClient().newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // The gateway explains itself in the body (error code, origin,
                // action); without it a rejected renewal is indistinguishable from
                // a WAF block.
                SpotiFLACDiag.log(
                    "session renew HTTP ${response.code} id=${session.extensionId} body=" + text.take(240),
                )
                throw SpotiFLACRenewalRefusedException(response.code, text)
            }
            return parseRefreshResponse(text)
        }
    }

    private fun parseRefreshResponse(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        obj["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { result["session_id"] = it }
        obj["session_secret"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { result["session_secret"] = it }
        obj["expires_at"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { result["expires_at"] = it }
        return result
    }

    /**
     * Merges refreshed fields into the record on disk.
     *
     * A rotating secret can be written concurrently by the runtime itself, so the
     * record is re-read first and the write is skipped when another writer already
     * replaced the generation this renewal was based on. That way the renewer can
     * never clobber a fresher session with a stale one.
     */
    private fun applyRefresh(
        session: ExtensionSession,
        record: Map<String, String>,
        refreshed: Map<String, String>,
    ): Boolean {
        val file = session.recordFile
        val latest = runCatching { readRecord(file) }.getOrNull() ?: record
        if (latest["session_id"].orEmpty() != record["session_id"].orEmpty()) return false

        val merged = latest.toMutableMap()
        refreshed.forEach { (key, value) -> if (value.isNotBlank()) merged[key] = value }
        if (merged == latest) return false

        file.parentFile?.mkdirs()
        val payload = buildJsonObject { merged.forEach { (key, value) -> put(key, value) } }.toString()
        val temp = File(file.parentFile, file.name + ".renew.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
        return true
    }

    /** Reads a session record file's flat string fields. */
    private fun readRecord(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val obj = json.parseToJsonElement(file.readText()).jsonObject
        return obj.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
    }

    private data class SignedSessionConfig(
        val baseUrl: String,
        val appVersion: String,
        val platform: String,
        val schemeLabel: String,
        val headerPrefix: String,
        val timeWindowSeconds: Long,
        val refreshPath: String,
    )

    private fun signedSessionConfigOf(context: Context, extensionId: String): SignedSessionConfig? {
        val manifest = File(File(extensionsDir(context), extensionId), "manifest.json")
        if (!manifest.isFile) return null
        return runCatching {
            val signed = json.parseToJsonElement(manifest.readText()).jsonObject["signedSession"]
                ?.jsonObject ?: return null
            val baseUrl = signed["baseUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (baseUrl.isEmpty()) return null
            val endpoints = signed["endpoints"]?.let { element ->
                runCatching { element.jsonObject }.getOrNull()
            }
            SignedSessionConfig(
                baseUrl = baseUrl,
                appVersion = signed["appVersion"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "ext-1.0",
                platform = signed["platform"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: SpotiFLACRequestSigner.DEFAULT_PLATFORM,
                schemeLabel = signed["schemeLabel"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: SpotiFLACRequestSigner.DEFAULT_SCHEME_LABEL,
                headerPrefix = signed["headerPrefix"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: SpotiFLACRequestSigner.DEFAULT_HEADER_PREFIX,
                timeWindowSeconds = signed["timeWindowSeconds"]?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull() ?: SpotiFLACRequestSigner.DEFAULT_TIME_WINDOW_SECONDS,
                refreshPath = endpoints?.get("refresh")?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: DEFAULT_REFRESH_PATH,
            )
        }.getOrNull()
    }

    private fun sessionFor(context: Context, dir: File): ExtensionSession? {
        val extensionId = dir.name
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return null
        val signed = runCatching {
            json.parseToJsonElement(manifestFile.readText()).jsonObject["signedSession"]
                ?.jsonObject
        }.getOrNull() ?: return null
        val namespace = SpotiFLACRequestSigner.sanitizeNamespace(
            signed["namespace"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        val baseUrl = signed["baseUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (namespace.isEmpty() || baseUrl.isEmpty()) return null
        val appVersion = signed["appVersion"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "ext-1.0"
        val platform = signed["platform"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: SpotiFLACRequestSigner.DEFAULT_PLATFORM
        val recordFile = File(
            recordsDir(context),
            SpotiFLACRequestSigner.sessionRecordFileName(namespace, baseUrl, appVersion, platform),
        )
        val record = runCatching { readRecord(recordFile) }.getOrDefault(emptyMap())
        val hasSession = record["session_id"].orEmpty().isNotBlank() &&
            record["session_secret"].orEmpty().isNotBlank()
        return ExtensionSession(
            extensionId = extensionId,
            displayName = runCatching {
                json.parseToJsonElement(manifestFile.readText()).jsonObject["displayName"]
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull(),
            namespace = namespace,
            appVersion = appVersion,
            expiresAtMillis = record["expires_at"]?.let(::parseExpiryMillis),
            hasSession = hasSession,
            recordFile = recordFile,
            sessionId = record["session_id"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The versions to sign a renewal with, in order: the one the session was
     * minted under first, then the extension's current version.
     *
     * The gateway binds a session to its minting version, so the record's own
     * version is the only one that can succeed for an unchanged extension. The
     * manifest's version is the second attempt because an extension that has since
     * been updated mints sessions under its new version - if the gateway accepts
     * the migration, an already-verified source stays verified across the update
     * instead of dropping back to a challenge.
     */
    internal fun signVersionsFor(mintedVersion: String, currentVersion: String): List<String> =
        listOf(mintedVersion, currentVersion).filter { it.isNotBlank() }.distinct()

    /**
     * Whether a session should be renewed now. Unknown expiry is treated as due
     * (better one wasted call than a lapsed session), and an already-expired
     * session is still attempted: the gateway may still accept its install id.
     */
    internal fun renewalDue(remainingSeconds: Long?, force: Boolean): Boolean =
        force || remainingSeconds == null || remainingSeconds <= RENEW_WINDOW_SECONDS

    /**
     * Whether a renewal result says the session it names must be dropped so a check can be raised.
     *
     * Only an answer the gateway actually gave can condemn a session: a run that asked nothing is a
     * statement about this app, not about the session, and a source whose record cannot be signed
     * with (a version bump) is one the vault is supposed to revive, not one to throw away.
     */
    internal fun shouldForgetSessionAfterRenewal(result: RenewResult): Boolean =
        result.contactedGateway && result.needsVerification

    /**
     * Whether a remembered refusal stops this attempt.
     *
     * It stops the scheduled renewer and only the scheduled renewer: [userAsked] is a tap on
     * "Renew now", which has to actually try, or the button silently does nothing for as long as
     * the memo lasts.
     */
    internal fun renewalRefusalApplies(
        rejectedUntilMs: Long,
        nowMs: Long,
        userAsked: Boolean,
    ): Boolean = !userAsked && rejectedUntilMs > nowMs

    /**
     * What a failed renewal means, from the gateway's own answer.
     *
     * The distinction that matters is between a session that is *gone* and one the gateway simply
     * will not rotate: the first can only be fixed by verifying the source again, the second may
     * work later. Both are remembered so neither is re-asked on every start.
     */
    internal fun classifyRenewalFailure(error: Throwable): RenewalFailure {
        val refusal = error as? SpotiFLACRenewalRefusedException
        val body = refusal?.body.orEmpty()
        val status = refusal?.statusCode
        // The gateway names this case itself: the session cannot be resumed, only replaced.
        val dead = body.contains("SESSION_INVALID", ignoreCase = true) ||
            body.contains("bootstrap_session", ignoreCase = true)
        // A 428 the gateway explains as VERIFY_REQUIRED is not a transport failure and not a
        // refusal to rotate: it is the gateway asking for a verification, on a session it otherwise
        // accepts. Measured directly - `POST /v2/tickets` and `/v2/session/refresh` both answer
        // `428 {"error":"VERIFY_REQUIRED","action":"verify"}` for a session whose signature is
        // valid. Falling through to the generic branch reported that as `request failed: HTTP 428`,
        // which names neither the cause nor the only thing that fixes it.
        val verifyRequired = status == 428 || body.contains("VERIFY_REQUIRED", ignoreCase = true)
        return when {
            dead || verifyRequired -> RenewalFailure(
                needsVerification = true,
                refused = false,
                remember = true,
                detail = if (dead) {
                    "session no longer valid - verify this source again"
                } else {
                    "the gateway asks for a verification - verify this source"
                },
            )
            status == 403 -> RenewalFailure(
                needsVerification = false,
                refused = true,
                remember = true,
                detail = "gateway refused the renewal",
            )
            status == 401 -> RenewalFailure(
                needsVerification = false,
                refused = true,
                remember = true,
                detail = "gateway rejected the session signature (HTTP 401)",
            )
            status == 429 -> {
                // The gateway said "not now" for the whole client, and it says for how long:
                // `{"error":"Temporarily blocked. Please try again later.","retry_after":78542}`.
                // Measured on the reporting device, this was ignored - so every scheduled run and
                // every playback-path renewal asked again inside the block. That is the one thing
                // guaranteed to extend it, and it is also why a source could sit "verified" while
                // nothing it asked for ever came back: the block was renewed by the app itself.
                val waitMs = retryAfterMs(body)
                RenewalFailure(
                    // Not a verification matter: the session is intact, the gateway is refusing
                    // everybody for a while. Sending the user to a Cloudflare check for this is a
                    // dead end, so the row is told what to wait for instead.
                    needsVerification = false,
                    refused = true,
                    remember = true,
                    detail = describeGatewayBlock(waitMs),
                    retryAfterMs = waitMs,
                )
            }
            else -> RenewalFailure(
                needsVerification = false,
                refused = false,
                remember = false,
                detail = "request failed: ${error.message}",
            )
        }
    }

    /**
     * The wait the gateway asked for, in milliseconds, or null when it did not say.
     *
     * `retry_after` is seconds in the body; a `retry-after` header is accepted too because the same
     * answer is sometimes given only there. Anything unparseable, or outside
     * [BLOCK_MIN_MS]-[BLOCK_MAX_MS], is clamped rather than discarded - the answer is still "not
     * yet", and the only question was how long.
     */
    internal fun retryAfterMs(body: String): Long? {
        if (body.isBlank()) return null
        val seconds =
            runCatching {
                val obj = json.parseToJsonElement(body).jsonObject
                (obj["retry_after"] ?: obj["retry-after"])
                    ?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.toDoubleOrNull()
            }.getOrNull() ?: return null
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return (seconds * 1000.0).toLong().coerceIn(BLOCK_MIN_MS, BLOCK_MAX_MS)
    }

    /** The block, said in the terms a person reads a setting in. */
    internal fun describeGatewayBlock(waitMs: Long?): String =
        when {
            waitMs == null -> "gateway is refusing renewals (HTTP 429)"
            waitMs >= 3600_000L ->
                "gateway is blocking renewals for ~${(waitMs + 1800_000L) / 3600_000L}h"
            else -> "gateway is blocking renewals for ~${(waitMs + 30_000L) / 60_000L}m"
        }

    /**
     * One line for a renew run, naming what happened to each source.
     *
     * "Renewed 1 session" was the whole story a manual renew told, while three sources had not
     * renewed at all - one dead, two refused. The counts alone cannot say which, and the answer
     * decides whether the user does anything: a source whose session is gone needs a verification,
     * a refused one just needs time.
     */
    fun summarise(results: List<RenewResult>): String {
        if (results.isEmpty()) return "No verified sources to renew"
        val renewed = results.filter { it.renewed }
        val needsVerification = results.filter { !it.renewed && it.needsVerification }
        val refused = results.filter { !it.renewed && !it.needsVerification && it.refused }
        val others = results.filter { !it.renewed && !it.needsVerification && !it.refused }
        if (needsVerification.isEmpty() && refused.isEmpty() && others.isEmpty()) {
            return when (renewed.size) {
                0 -> "Nothing to renew"
                1 -> "Renewed ${renewed.first().extensionId}"
                else -> "Renewed all ${renewed.size} sessions"
            }
        }
        val parts = mutableListOf<String>()
        if (renewed.isNotEmpty()) parts.add("renewed ${names(renewed.map { it.extensionId })}")
        if (needsVerification.isNotEmpty()) {
            val who = names(needsVerification.map { it.extensionId })
            parts.add(if (needsVerification.size == 1) "$who needs a new verification" else "$who need a new verification")
        }
        if (refused.isNotEmpty()) {
            parts.add("${names(refused.map { it.extensionId })} refused by the gateway, try again later")
        }
        if (others.isNotEmpty()) parts.add("${names(others.map { it.extensionId })} could not renew")
        return parts.joinToString(" \u00b7 ").replaceFirstChar { it.uppercase() }
    }

    /** `a`, `a and b`, `a, b and 3 more` - a line, not an inventory. */
    private fun names(ids: List<String>): String {
        if (ids.size <= 2) return ids.joinToString(" and ")
        val head = ids.take(2).joinToString(", ")
        return "$head and ${ids.size - 2} more"
    }

    internal fun parseExpiryMillis(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun extensionsDir(context: Context): File = File(context.filesDir, "spotiflac/extensions")

    private fun recordsDir(context: Context): File =
        File(File(context.filesDir, "spotiflac/extension_data"), "signed_sessions")

    /** Debug helper: the record payload for one extension, for diagnostics only. */
    fun describe(context: Context): List<String> =
        sessions(context).map { session ->
            val remaining = session.remainingSeconds
            val state = when {
                !session.hasSession -> "unverified"
                session.isExpired -> "expired"
                else -> "valid (${remaining?.div(60)}m left)"
            }
            "${session.extensionId}: $state"
        }

    /** Exposed for tests. */
    internal fun recordFileNameForTest(
        namespace: String,
        baseUrl: String,
        appVersion: String,
        platform: String,
    ): String = SpotiFLACRequestSigner.sessionRecordFileName(namespace, baseUrl, appVersion, platform)

    /** Exposed for tests. */
    internal fun mergeForTest(
        latest: JsonObject,
        refreshed: Map<String, String>,
    ): Map<String, String> {
        val merged = latest.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
            .toMutableMap()
        refreshed.forEach { (key, value) -> if (value.isNotBlank()) merged[key] = value }
        return merged
    }
}
