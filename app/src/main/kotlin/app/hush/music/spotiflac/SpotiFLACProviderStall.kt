/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * When to give up on a single SpotiFLAC provider attempt.
 *
 * The runtime's `downloadByStrategy` is a *blocking* native call with no timeout of its own, so a
 * provider that wedges is not merely slow - it spends whatever the sweep allows and takes the walk
 * down with it. The watchdog around it is what turns that into a bounded cost, and the bounds are
 * now the user's: [SpotiFLACFetchPolicy] gives every source a connect window (silence before the
 * first byte), a download window (silence after bytes have started) and a number of attempts.
 *
 * The two windows are deliberately different questions:
 *
 *  - before the first byte the provider may still be answering, so silence means "not connected";
 *  - after the first byte a stopped transfer is a stopped socket, but a *slow* one is the normal
 *    case for a lossless file, so the window is much longer. Measured on device, a 26 MB FLAC at
 *    70% was cut off by a ceiling sized for a metadata call, and the track fell back to YouTube
 *    with the download still arriving.
 *
 * The only rule this file still owns by itself is the cooldown record: a provider that stalled is
 * *reported*, never reordered - the Audio Sources list is the walk, and a source the user ranked
 * first is asked first however recently it went quiet.
 */
object SpotiFLACProviderStallPolicy {
    /** How often the watchdog re-reads the stall meter. */
    const val POLL_MS = 250L

    /**
     * How long a provider that stalled is still *reported* as having done so.
     *
     * This used to size a demotion: a provider that went quiet moved behind the ones below it for
     * two minutes. It no longer moves, because the Audio Sources order is the walk and a source the
     * user ranked first is asked first however recently it stalled - a wedge is often about one
     * track, and being second-guessed on the next one is not a priority list. The record is kept for
     * the log, which is where "why was that fetch slow" is answered.
     */
    const val DEMOTION_COOLDOWN_MS = 2 * 60_000L

    /** True when [lastProgressAtMs] is stale enough to give up on the provider. */
    fun isStalled(
        lastProgressAtMs: Long,
        nowMs: Long,
        timeoutMs: Long,
    ): Boolean = nowMs - lastProgressAtMs >= timeoutMs

    /**
     * True when the attempt has outlived its absolute ceiling.
     *
     * [startedAtMs] is the baseline the ceiling is counted from. The meter passes the later of the
     * attempt start and the last real transfer, so this only fires for an attempt that is not moving
     * bytes - an attempt that is transferring is bounded by the download window instead.
     */
    fun isOverCeiling(
        startedAtMs: Long,
        nowMs: Long,
        ceilingMs: Long,
    ): Boolean = nowMs - startedAtMs >= ceilingMs

    /** True while a provider that stalled at [stalledAtMs] is still reported as recently stalled. */
    fun isDemoted(
        stalledAtMs: Long?,
        nowMs: Long,
        cooldownMs: Long = DEMOTION_COOLDOWN_MS,
    ): Boolean = stalledAtMs != null && nowMs - stalledAtMs < cooldownMs

    /**
     * How long a provider that *refused* service is left out of the chain.
     *
     * Much longer than a stall report, because the two are different statements. Going quiet says
     * nothing about the provider's willingness and is often about one track; a rate limit is the
     * provider saying no to *this client* for a while, and asking again inside that window only
     * extends it.
     */
    const val RATE_LIMIT_COOLDOWN_MS = 10 * 60_000L

    /** True while a provider that refused service at [limitedAtMs] is still cooling down. */
    fun isRateLimited(
        limitedAtMs: Long?,
        nowMs: Long,
        cooldownMs: Long = RATE_LIMIT_COOLDOWN_MS,
    ): Boolean = limitedAtMs != null && nowMs - limitedAtMs < cooldownMs

    /**
     * How long a provider that reported *itself* unavailable is kept at the back of the chain.
     *
     * The strongest statement available: a stall is silence and a refusal is about this client,
     * while this is the provider's own health check saying the service behind it cannot be reached.
     */
    const val UNAVAILABLE_COOLDOWN_MS = 10 * 60_000L

    /** True while a provider that reported itself unavailable at [atMs] stays at the back. */
    fun isUnavailable(
        atMs: Long?,
        nowMs: Long,
        cooldownMs: Long = UNAVAILABLE_COOLDOWN_MS,
    ): Boolean = atMs != null && nowMs - atMs < cooldownMs

    /** Names in [candidates] that are currently reported unavailable, for the log. */
    fun unavailableNow(
        candidates: List<String>,
        unavailableAtMs: Map<String, Long>,
        nowMs: Long,
    ): List<String> =
        if (unavailableAtMs.isEmpty()) {
            emptyList()
        } else {
            candidates.filter { isUnavailable(unavailableAtMs[it], nowMs) }
        }

    /** Names in [candidates] that are currently cooling down, for the log. */
    fun coolingDown(
        candidates: List<String>,
        rateLimitedAtMs: Map<String, Long>,
        nowMs: Long,
    ): List<String> =
        if (rateLimitedAtMs.isEmpty()) {
            emptyList()
        } else {
            candidates.filter { isRateLimited(rateLimitedAtMs[it], nowMs) }
        }

    /**
     * The candidates to ask, minus the ones that are already refusing service.
     *
     * The order is the user's and is never changed here: a provider is either in this list at the
     * place they gave it, or out of it because it has said no. That is the whole rule of the resolve
     * walk. The two exclusions are statements a source made about *itself*: a rate limit is a
     * refusal this client has to sit out, and an outage is the provider's own health saying it
     * cannot serve. Both are bounded cooldowns, and neither can reorder anything.
     *
     * The safety valve is deliberate: when every candidate is cooling down, the full list is
     * returned unchanged, because a single-source setup whose one provider has rate-limited us is
     * still better off asking than not playing.
     */
    fun orderAvailable(
        candidates: List<String>,
        rateLimitedAtMs: Map<String, Long>,
        nowMs: Long,
        unavailableAtMs: Map<String, Long> = emptyMap(),
    ): List<String> {
        val leftOut =
            (coolingDown(candidates, rateLimitedAtMs, nowMs) + unavailableNow(candidates, unavailableAtMs, nowMs))
                .toSet()
        if (leftOut.isEmpty()) return candidates
        return candidates.filterNot { it in leftOut }.ifEmpty { candidates }
    }

    /**
     * The module/addon sources to ask, minus the ones that have refused service.
     *
     * Unlike a runtime provider, an addon that refused is *left out*, not moved to the back. The two
     * facts it states are different in kind: a runtime provider's failure can be about one track
     * (its catalogue, a missing id, a bad package for that item), which is why the walk keeps such a
     * source and only reorders it; a server addon's refusal arrives before any track is named - a
     * `403` from the service itself, a host that will not answer, a timeout - so the next track gets
     * the same answer for the same one round trip. Measured on the reporting device against
     * `tido.tido.tido.dpdns.org`: every addon call in a sweep returned `403 {"error":"Unavailable"}`
     * in under a second, six sources' worth of that on every refetch, and no track was ever served.
     *
     * There is deliberately no "ask anyway when nothing else is left" valve here, unlike
     * [orderAvailable]. For a runtime provider the valve buys a real chance at playback for a single
     * source the client has just rate-limited; for an addon it only buys the same refusal again, on
     * every track, which is the cost this exists to remove. A walk emptied this way fails at once and
     * the engine falls back to YouTube, while the Audio Sources row says which service refused and
     * how long it is out - and its Test, or the cooldown ending, is the way back.
     */
    fun availableAddons(
        addons: List<String>,
        unavailableAtMs: Map<String, Long>,
        nowMs: Long,
    ): List<String> {
        if (unavailableAtMs.isEmpty()) return addons
        val leftOut = unavailableNow(addons, unavailableAtMs, nowMs).toSet()
        if (leftOut.isEmpty()) return addons
        return addons.filterNot { it in leftOut }
    }

    /** The candidates that stalled in the last [DEMOTION_COOLDOWN_MS], for the log only. */
    fun recentlyStalled(
        candidates: List<String>,
        stalledAtMs: Map<String, Long>,
        nowMs: Long,
    ): List<String> = candidates.filter { isDemoted(stalledAtMs[it], nowMs) }

    /** The smaller of the download window and what is left of the ceiling. */
    fun effectiveTimeoutMs(
        startedAtMs: Long,
        nowMs: Long,
        stallTimeoutMs: Long,
        ceilingMs: Long,
    ): Long {
        val ceilingRemaining = ceilingMs - (nowMs - startedAtMs)
        return min(stallTimeoutMs, ceilingRemaining.coerceAtLeast(0L))
    }
}

/** Why an attempt was abandoned, so the reported cause matches the trigger that fired. */
enum class ProviderAbandonReason {
    /** Nothing at all arrived for the attempt's current idle window. */
    STALLED,

    /** Events kept arriving but no byte moved for the whole ceiling. */
    CEILING,
}

/**
 * What each provider has been observed to need before it starts moving bytes.
 *
 * Kept as a diagnostic, not as a decision: the windows are the user's policy now, and letting a
 * measured value widen them would make the setting mean something different from what it says. It
 * is still worth recording - it is the number that explains "why was that fetch slow", and the log
 * prints it next to the policy it was judged by.
 *
 * A decayed average rather than the last sample, because one slow round trip on a bad connection
 * must not describe a provider for a week.
 */
class ProviderTimings(
    private val observedMs: MutableMap<String, Long> = mutableMapOf(),
) {
    /** What this provider has been observed to need before its first byte, or null. */
    fun observedMsFor(sourceId: String): Long? = observedMs[key(sourceId)]

    /** Records how long this provider took to start moving bytes. */
    fun record(sourceId: String, firstByteMs: Long) {
        if (firstByteMs <= 0L) return
        val id = key(sourceId)
        val previous = observedMs[id]
        observedMs[id] = if (previous == null) firstByteMs else (previous * 3 + firstByteMs) / 4
    }

    /** The observed values, for persistence. */
    fun snapshot(): Map<String, Long> = observedMs.toMap()

    /** Restores previously observed values. */
    fun restore(values: Map<String, Long>) {
        values.forEach { (id, ms) -> if (ms > 0L) observedMs[key(id)] = ms }
    }

    private fun key(sourceId: String): String = sourceId.trim().lowercase(Locale.US)
}

/**
 * Live "has this provider said anything lately?" meter for one provider attempt.
 *
 * Fed by the progress reporter that already mirrors the runtime's transfer meter. It
 * deliberately counts only *new* progress events: the runtime repeats the last delta
 * while an item sits still, so a naive "did we get a sample?" test would reset the
 * clock forever and never notice a wedge.
 *
 * It also tracks the last event that actually **moved bytes**, because the two
 * conditions are not interchangeable. Event liveness answers "is the runtime still
 * doing something for this item", while byte movement answers "is the download
 * progressing". A provider in `resolving_stream` may legitimately emit stage updates
 * for a while, so the ceiling exists to bound that; but an attempt that is moving bytes
 * is bounded by the download window, not by the ceiling, because cutting off a transfer
 * that is visibly arriving is the failure this meter exists to prevent.
 *
 * [policy] is read once per attempt, which is what makes the two windows the ones the
 * user chose for this source.
 */
class ProviderStallMeter(
    private val startedAtMs: Long = SystemClock.elapsedRealtime(),
    private val policy: SpotiFLACFetchPolicy = SpotiFLACFetchPolicy.DEFAULT,
) {
    private val lastProgressAtMs = AtomicLong(startedAtMs)
    private val lastTransferAtMs = AtomicLong(startedAtMs)
    private val lastSeq = AtomicLong(Long.MIN_VALUE)

    /** No bytes have moved yet, so any first byte counts as a transfer. */
    private val transferredBytes = AtomicLong(-1L)

    @Volatile
    var lastStage: String? = null
        private set

    /** The policy this attempt is judged by, for the log. */
    val fetchPolicy: SpotiFLACFetchPolicy get() = policy

    /**
     * Records a progress event. [seq] identifies it, so repeats are ignored.
     *
     * [bytesReceived] is the runtime's cumulative count for this item: only a *growing*
     * value counts as a transfer, so a provider that re-emits its size without moving
     * data cannot look like one.
     */
    fun onProgress(
        seq: Long,
        stage: String?,
        bytesReceived: Long? = null,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (seq == lastSeq.get()) return
        lastSeq.set(seq)
        if (!stage.isNullOrBlank()) lastStage = stage
        lastProgressAtMs.set(nowMs)
        if (bytesReceived != null && bytesReceived > transferredBytes.get()) {
            transferredBytes.set(bytesReceived)
            lastTransferAtMs.set(nowMs)
        }
    }

    fun lastProgressAt(): Long = lastProgressAtMs.get()

    /** When real bytes last arrived. */
    fun lastTransferAt(): Long = lastTransferAtMs.get()

    /** Silence since the last progress *event*, for the stall branch. */
    fun stalledMillis(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        nowMs - lastProgressAtMs.get()

    /** Silence since the last *byte*, for the ceiling branch. */
    fun transferSilenceMillis(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        nowMs - lastTransferAtMs.get()

    fun elapsedMillis(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        nowMs - startedAtMs

    /** True while real bytes have arrived inside the download window. */
    fun transferring(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        transferSilenceMillis(nowMs) < policy.downloadTimeoutMs

    /**
     * True once any real byte has arrived, so the attempt is transferring, not resolving.
     *
     * Strictly *more than zero*: the runtime's resolving stages report `bytes=0/0`, which is a
     * progress event with no data behind it. Counting that as a transfer would put every
     * resolution phase on the download window and undo the connect budget entirely.
     */
    fun hasTransferred(): Boolean = transferredBytes.get() > 0L

    /**
     * The idle budget for what this attempt is currently doing.
     *
     * Before a byte moves the provider is connecting, and the question the user's connect timeout
     * answers is "how long may it stay silent". Once bytes are arriving, silence means the transfer
     * stopped, and the longer download window is the right one.
     */
    fun idleTimeoutMs(): Long =
        if (hasTransferred()) policy.downloadTimeoutMs else policy.connectTimeoutMs

    /** The absolute bound for this attempt. */
    fun ceilingMs(): Long = policy.attemptCeilingMs

    /**
     * How long this attempt took to start moving bytes, or null if it never did.
     *
     * This is the measurement [ProviderTimings] records: the *resolution* cost, which is the number
     * that explains a slow fetch. An attempt that reported `already_exists` or replayed a cached
     * file moved no bytes and is not a measurement of anything.
     */
    fun timeToFirstByteMs(): Long? =
        if (hasTransferred()) lastTransferAtMs.get() - startedAtMs else null

    /**
     * Why this attempt should be abandoned, or null while it still deserves waiting.
     *
     * The ceiling is counted from the last real transfer rather than the attempt start: an attempt
     * that is moving bytes is bounded by the download window (which is what "the download stopped"
     * actually looks like), while an attempt that only ticks events without data is still capped.
     * [SpotiFLACFetchPolicy.attemptCeilingMs] is connect + download, so an attempt can always spend
     * both windows before the ceiling has anything to say.
     */
    fun abandonReason(nowMs: Long = SystemClock.elapsedRealtime()): ProviderAbandonReason? =
        when {
            SpotiFLACProviderStallPolicy.isOverCeiling(ceilingBaselineMs(), nowMs, ceilingMs()) ->
                ProviderAbandonReason.CEILING
            SpotiFLACProviderStallPolicy.isStalled(lastProgressAtMs.get(), nowMs, idleTimeoutMs()) ->
                ProviderAbandonReason.STALLED
            else -> null
        }

    /** True when the attempt should be abandoned and the next provider tried. */
    fun shouldAbandon(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        abandonReason(nowMs) != null

    private fun ceilingBaselineMs(): Long = maxOf(startedAtMs, lastTransferAtMs.get())
}

/**
 * One provider was abandoned mid-attempt because it stopped making progress.
 *
 * A plain [Exception] on purpose - never a [kotlinx.coroutines.CancellationException].
 * The per-source loop re-throws cancellation to abort a superseded sweep, so a stall
 * arriving as cancellation would take the remaining providers down with it; as an
 * ordinary failure it is recorded against this one provider and the loop moves on.
 *
 * The message carries blocked-sweep wording so [SpotiFLACSweepVerdict] treats a sweep
 * that ended on a stall as [SpotiFLACSweepOutcome.UNAVAILABLE] - "we never got an
 * answer from this provider" - rather than a catalogue verdict.
 */
class SpotiFLACProviderStalledException(
    val sourceId: String,
    val stage: String?,
    /** Silence that caused the abort: since the last event, or since the last byte. */
    val stalledMillis: Long,
    val reason: ProviderAbandonReason = ProviderAbandonReason.STALLED,
) : Exception(
    // The wording stays "timed out without progress" for both reasons: the sweep verdict
    // classifier keys off it to avoid recording a blocked sweep as a catalogue miss.
    "provider $sourceId timed out without progress for ${stalledMillis}ms" +
        (stage?.let { " (stuck on $it)" } ?: ""),
)
