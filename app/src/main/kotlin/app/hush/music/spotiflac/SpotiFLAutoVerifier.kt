/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs SpotiFLAC source verification on its own, one source at a time.
 *
 * A source's gateway session cannot be shared between sources: `/bootstrap`
 * answers every app version with its own challenge, and the grant a challenge
 * returns is spent by the first exchange (a second exchange against the same grant
 * is answered with HTTP 403). So there is no "one verification covers everything"
 * shortcut - but there is also no reason the user should drive it. Cloudflare's
 * managed challenge solves itself in the WebView, which is why the manual screen
 * used to disappear on its own after a second or two.
 *
 * This owns the queue and the ordering; the WebView that actually solves a
 * challenge lives in [app.hush.music.ui.component.SpotiFLACVerificationOverlay],
 * which observes [active] and reports back through [finish]. Nothing here touches
 * playback: the player keeps its own source order, and a source that cannot be
 * verified yet is simply skipped until it can be.
 */
object SpotiFLAutoVerifier {

    /** How long one source's challenge may take before the queue moves on. */
    const val CHALLENGE_TIMEOUT_MS = 75_000L

    /**
     * A source that just failed verification is not retried immediately: an
     * interactive challenge would otherwise loop, and each attempt is a network
     * round trip against Cloudflare.
     */
    private const val RETRY_COOLDOWN_MS = 10 * 60_000L

    private val _active = MutableStateFlow<String?>(null)

    /** The source whose challenge should be solved right now, or null. */
    val active: StateFlow<String?> = _active.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Human-readable progress of the automatic run, for the UI and diagnostics. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _verifiedTicker = MutableStateFlow(0)

    /**
     * Increments every time a source becomes usable.
     *
     * Playback watches this so a track that stopped because every source was
     * waiting for verification can start again by itself, instead of the queue
     * skipping past it.
     */
    val verifiedTicker: StateFlow<Int> = _verifiedTicker.asStateFlow()

    /**
     * Called whenever a source becomes usable.
     *
     * Playback registers this to resume a track it parked waiting for exactly this.
     * A direct callback rather than only a flow: the resume must not depend on a
     * collector's lifecycle, because a missed emission leaves the track parked with
     * nothing to wake it.
     */
    @Volatile var onSourceVerified: (() -> Unit)? = null

    private val queue = LinkedHashSet<String>()
    private val lastFailedAt = HashMap<String, Long>()
    private val lastAttemptAt = HashMap<String, Long>()

    /**
     * Asks for these sources to be verified in the background.
     *
     * @param reason short label for diagnostics ("playback", "prewarm", ...).
     * @param force ignore the failure cooldown. Used when a track is being held
     *   waiting for exactly this verification: the cooldown exists to stop a failed
     *   attempt from spinning, but a parked track is a concrete reason to try again
     *   now, and making the user wait out ten minutes is worse than one extra call.
     */
    fun enqueue(sourceIds: List<String>, reason: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        var added = 0
        sourceIds.forEach { id ->
            if (id.isBlank() || id == _active.value) return@forEach
            val cooled = !force && lastFailedAt[id]?.let { now - it < RETRY_COOLDOWN_MS } ?: false
            if (cooled) return@forEach
            if (queue.add(id)) added++
        }
        if (added == 0) return
        SpotiFLACDiag.log(
            "auto-verify enqueue ($reason): ${sourceIds.joinToString(",")} queued=${queue.size}",
        )
        startNext()
    }

    /** The sources waiting for a challenge, in order. */
    fun queued(): List<String> = queue.toList()

    /**
     * Marks the active source's attempt finished and moves to the next.
     *
     * Called by the challenge host once a grant has been delivered (or the attempt
     * gave up). A failure starts a cooldown so the queue cannot spin.
     */
    fun finish(sourceId: String, verified: Boolean) {
        if (_active.value == sourceId) _active.value = null
        lastAttemptAt[sourceId] = System.currentTimeMillis()
        if (verified) {
            queue.remove(sourceId)
            lastFailedAt.remove(sourceId)
            _verifiedTicker.value += 1
            SpotiFLACDiag.log("auto-verify done: $sourceId verified")
            runCatching { onSourceVerified?.invoke() }
                .onFailure { SpotiFLACDiag.log("verified listener failed: ${it.message}") }
        } else {
            queue.remove(sourceId)
            lastFailedAt[sourceId] = System.currentTimeMillis()
            SpotiFLACDiag.log("auto-verify gave up: $sourceId (cooldown ${RETRY_COOLDOWN_MS / 60_000}m)")
        }
        startNext()
        // Nothing left to try automatically: hand the one source the user can still
        // act on to the manual notice, rather than leaving playback parked with no
        // explanation. An automatic run that succeeded never reaches here.
        if (queue.isEmpty() && _active.value == null && !verified) {
            _status.value = "Verification for $sourceId needs a manual check"
            SpotiFLACVerificationRequest.request(sourceId)
        }
    }

    /** Drops everything: used when the user cancels or SpotiFLAC is turned off. */
    fun cancel() {
        if (queue.isNotEmpty() || _active.value != null) {
            SpotiFLACDiag.log("auto-verify cancelled (queued=${queue.size})")
        }
        queue.clear()
        _active.value = null
        _status.value = null
    }

    /** True while a source is being verified right now. */
    val isRunning: Boolean get() = _active.value != null

    private fun startNext() {
        if (_active.value != null) return
        val next = queue.firstOrNull()
        if (next == null) {
            _status.value = null
            return
        }
        _active.value = next
        _status.value = "Verifying $next…"
        SpotiFLACDiag.log(
            "auto-verify start: $next (queued=${queue.size} attempted=${lastAttemptAt.size})",
        )
    }

    /** Exposed for tests: whether a source is inside its retry cooldown. */
    internal fun inCooldown(sourceId: String, nowMillis: Long): Boolean =
        lastFailedAt[sourceId]?.let { nowMillis - it < RETRY_COOLDOWN_MS } ?: false

    internal fun recordFailureForTest(sourceId: String, atMillis: Long) {
        lastFailedAt[sourceId] = atMillis
    }
}
