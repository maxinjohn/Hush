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
 * The runtime's `downloadByStrategy` is a *blocking* native call with no timeout of
 * its own, and the sweep around it has one budget for every provider. So a provider
 * that wedges is not merely slow - it spends the whole budget and takes the sweep
 * down with it. Measured on device for one track ("Kaane Kaane"):
 *
 * ```
 * deezer              19.2s  -> quality_unavailable
 * amazon               1.6s  -> quality_unavailable
 * soundcloud           1.6s  -> quality_unavailable
 * ytmusic-spotiflac    1.7s  -> quality_unavailable
 * qobuz-web            26.0s  -> never returned (stuck on resolving_stream)
 * sweep outcome: UNAVAILABLE reason=sweep-unfinished (45000ms)
 * ```
 *
 * qobuz-web's 26s of silence is what pushed the sweep past its ceiling, and because
 * the budget was already gone the providers after it were never asked at all. A
 * watchdog turns that one wedged provider into a bounded cost, and the sweep keeps
 * walking the rest of the chain.
 */
object SpotiFLACProviderStallPolicy {
    /**
     * Silence longer than this means the provider is wedged, not slow.
     *
     * A healthy attempt answers within about 1.5-2s for a catalogue miss and about
     * 10s for a full lossless download, and the runtime emits a progress sample as it
     * moves between stages, so nothing at all for this long is already far outside
     * observed behaviour - while still leaving room for a slow network.
     */
    const val STALL_TIMEOUT_MS = 8_000L

    /**
     * Idle budget while the attempt is still resolving, before any byte has arrived.
     *
     * A provider is allowed to answer "temporarily unavailable, retry in N seconds", and the
     * runtime honours that itself: the extension returns `PROVIDER_UNAVAILABLE`, `retryable`,
     * `retry_after_seconds: 10`, and the runtime then sleeps 10s before its next of three
     * attempts. Measured on device, that path emits nothing at all while it sleeps - no progress
     * event, no byte - so a provider that is asking for time and a provider that has wedged look
     * identical to the meter until the sleep ends.
     *
     * This used to be 14s, sized so one declared retry plus the request after it could finish.
     * Measured end to end, that sizing is what makes a cold playback slow and never actually
     * rescues the provider:
     *
     * ```
     * 21:28:58  attempt source=amazon -> runtime walks amazon (1.5s, not found)
     * 21:29:03  runtime is on deezer; "temporarily unavailable ... retrying in 10s"
     * 21:29:14  abandoned, 14s spent - the retry it waited for produced nothing
     * 21:29:14  attempt source=deezer     -> the same 14s, the same provider, again
     * 21:29:28  attempt source=qobuz-web  -> downloaded 36MB in 1.8s
     * ```
     *
     * 28s of that 44s was the same unavailable provider paid for twice, to reach a provider that
     * answers in under two seconds. A declared retry cannot fit inside any window this app would
     * be willing to hold a listener at 0:00 for (10s of sleep *plus* the request), so the choice
     * is not "wait long enough" but "who waits". The window is therefore short, and a provider
     * abandoned here is not skipped: it is demoted behind the providers this sweep has not reached
     * yet and put the question again (`SpotiFLACProviderStallPolicy.orderRemaining`), which is a
     * real second chance rather than a timeout nobody ever sees the end of.
     *
     * The tighter window is right once data is moving, where silence means a stopped socket, and it
     * is now used before that too - where silence means either a wedge or a declared wait, and
     * neither is worth the listener's minute.
     */
    const val RESOLUTION_STALL_TIMEOUT_MS = 6_000L

    /**
     * Hard ceiling for one provider attempt that is not moving bytes.
     *
     * Without it a provider that reports just enough to look alive could still hold
     * the sweep for as long as it liked.
     *
     * Measured from the last *real* transfer, not from the attempt start - see
     * [ProviderStallMeter.abandonReason]. On device a ceiling measured from the start
     * killed attempts that were downloading normally: the log read "provider amazon
     * timed out without progress for 29ms", i.e. bytes had arrived 29 ms earlier and
     * the attempt was abandoned anyway, then recorded as stalled and demoted for the
     * full cooldown. That is how a slow-but-working provider ends up behind the ones
     * that never answer, and how a track only that provider has ends up skipped.
     */
    const val ATTEMPT_CEILING_MS = 25_000L

    /**
     * Idle budget before the first byte, for a provider this install has never measured.
     *
     * A provider that has never been timed here is *unknown*, not fast, and the fixed 6s window
     * treated unknown as fast. Measured on device, that is what a working provider's own extension
     * call looked like when it was abandoned:
     *
     * ```
     * ExtensionPerf: extension=deezer op=download totalMs=6036.4 items=0 payloadBytes=0
     * provider stalled id=soundcloud (walk had reached deezer) reason=STALLED
     *   stage=resolving_stream silent=6009ms - abandoned, trying next provider
     * all sources failed: amazon=... deezer=... (stuck on resolving_stream)
     * ```
     *
     * The extension had not wedged - it was killed on the line at 6009ms, and because every
     * provider in the walk measured about the same, the whole sweep failed and the track fell back
     * to YouTube. The number of providers is not the variable here; the window is.
     */
    const val INITIAL_RESOLUTION_WINDOW_MS = 20_000L

    /**
     * The idle budget for the resolution phase of a provider, from what it has been seen to need.
     *
     * Adaptive in both directions: a provider measured at 6s gets twice that, and one measured at
     * 1.5s (a catalogue miss) gets the floor rather than the flat window, so a fast provider still
     * fails fast instead of holding the sweep for a wait nobody needs. [ATTEMPT_CEILING_MS] caps
     * it, so widening the window can never exceed the ceiling that bounds every attempt anyway.
     *
     * @param firstByteMs time this provider has been observed to take before bytes move, or null
     *   when it has never completed an attempt here.
     */
    fun resolutionWindowMs(firstByteMs: Long?): Long =
        when {
            firstByteMs == null || firstByteMs <= 0L -> INITIAL_RESOLUTION_WINDOW_MS
            else -> (firstByteMs * 2).coerceIn(RESOLUTION_STALL_TIMEOUT_MS, ATTEMPT_CEILING_MS)
        }

    /** How often the watchdog re-reads the stall meter. */
    const val POLL_MS = 250L

    /**
     * The idle budget while the runtime is resolving *metadata*, which is its own work, not a
     * provider's.
     *
     * Before a provider is asked for audio the runtime works out what the track is, and it does that
     * by calling the extensions itself - so the meter hears nothing for that whole phase while the
     * runtime is demonstrably busy. Measured on the reporting device:
     *
     * ```
     * progress id=qobuz-web pct=0 bytes=0/0 stage=resolving_metadata   <- the only event, then silence
     *   rt| DownloadWithExtensionFallback: Metadata incomplete, searching providers for: ...
     *   rt| ExtensionPerf: extension=qobuz-web op=searchTracks totalMs=9752.8
     *   rt| ExtensionPerf: extension=qobuz-web op=searchTracks totalMs=13465.3
     *   rt| DownloadPipeline: item=hush-... service=qobuz-web totalMs=15988.6
     * provider stalled id=qobuz-web stage=resolving_metadata silent=6027ms
     * ```
     *
     * The runtime searches the metadata providers on every attempt, because Hush sends no ISRC and no
     * release date for these tracks; that search measured 6.0s, 9.7s, 13.5s and 20.2s in one phase.
     * The adaptive window answers a different question - how long this provider needed to *start
     * moving bytes* - and it landed on 6s, so the runtime was killed inside its own metadata search
     * on every attempt and the sweep reported "all sources failed" for tracks the providers were
     * answering. This phase is therefore sized from its own measured worst case and not from a
     * provider timing, and [ATTEMPT_CEILING_MS] still ends it there.
     */
    const val METADATA_STAGE_IDLE_MS = 25_000L

    /** The stage the runtime reports while it is working out what the track is. */
    const val METADATA_STAGE = "resolving_metadata"

    /** True while [stage] means "the runtime is still resolving metadata", not "a provider is quiet". */
    fun isMetadataStage(stage: String?): Boolean =
        stage?.trim()?.lowercase(Locale.US) == METADATA_STAGE

    /**
     * How long a provider attempt may take before a byte arrives.
     *
     * The number a sweep's budget is sized from, because it is the most one attempt can now cost - a
     * budget built from the bare window cuts the chain off, and the providers behind the cut look
     * exactly like a catalogue miss. Never below [METADATA_STAGE_IDLE_MS], because that phase is
     * what an attempt now has to be allowed to finish.
     */
    fun tryWindowMs(firstByteMs: Long?): Long =
        maxOf(resolutionWindowMs(firstByteMs), METADATA_STAGE_IDLE_MS)
            .coerceAtMost(ATTEMPT_CEILING_MS)

    /**
     * How long a provider that stalled is tried last.
     *
     * The stall itself costs the watchdog budget, so without this every later track
     * would pay it again on the same wedged provider. Demoting rather than disabling
     * matters: the provider is left in the chain, so it is still tried - just after
     * the providers that are answering.
     *
     * Kept short on purpose. A wedge can be specific to one track (deezer served a 38MB
     * FLAC in 10s minutes before it wedged on a different track), so the demotion is a
     * bet that could be wrong. Both outcomes cost about the same - skipping a wedge saves
     * one watchdog budget, and being wrong costs one extra fast-failing provider before
     * the demoted one - so the window is sized to help with a run of tracks rather than
     * to punish a provider for one bad track.
     */
    const val DEMOTION_COOLDOWN_MS = 2 * 60_000L

    /** True when [lastProgressAtMs] is stale enough to give up on the provider. */
    fun isStalled(
        lastProgressAtMs: Long,
        nowMs: Long,
        timeoutMs: Long = STALL_TIMEOUT_MS,
    ): Boolean = nowMs - lastProgressAtMs >= timeoutMs

    /**
     * True when the attempt has outlived its absolute ceiling.
     *
     * [startedAtMs] is the baseline the ceiling is counted from. The meter passes the
     * later of the attempt start and the last real transfer, so this only fires for an
     * attempt that is not moving bytes.
     */
    fun isOverCeiling(
        startedAtMs: Long,
        nowMs: Long,
        ceilingMs: Long = ATTEMPT_CEILING_MS,
    ): Boolean = nowMs - startedAtMs >= ceilingMs

    /** True while a provider that stalled at [stalledAtMs] is still being demoted. */
    fun isDemoted(
        stalledAtMs: Long?,
        nowMs: Long,
        cooldownMs: Long = DEMOTION_COOLDOWN_MS,
    ): Boolean = stalledAtMs != null && nowMs - stalledAtMs < cooldownMs

    /**
     * How long a provider that *refused* service is left out of the chain.
     *
     * Much longer than a stall demotion, because the two are different statements. Going quiet
     * says nothing about the provider's willingness and is often about one track; a rate limit is
     * the provider saying no to *this client* for a while, and asking again inside that window only
     * extends it. Measured next to each other on the reporting device: a stall was paid once per
     * cooldown, while an answering-but-refusing provider was retried on every single track.
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
     * The longest of the three cooldowns, because it is the strongest statement available: a stall
     * is silence and a refusal is about this client, while this is the provider's own health check
     * saying the service behind it cannot be reached at all. Measured while Deezer was down on the
     * gateway's side, every sweep still paid it in full and reported `Invalid Deezer track ID`.
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
     * The candidates to actually ask, leaving out the ones that are refusing service.
     *
     * This is the one place a provider is *dropped* rather than moved, and the reason is that the
     * other orderings answer a different question. Demotion decides which of two providers to ask
     * first; a provider that has refused will not answer at all, so asking it at all is the cost.
     *
     * The safety valve is deliberate: when every candidate is cooling down, the full list is
     * returned unchanged, because a single-source setup whose one provider has rate-limited us is
     * still better off asking than not playing.
     */
    fun orderAvailable(
        candidates: List<String>,
        stalledAtMs: Map<String, Long>,
        rateLimitedAtMs: Map<String, Long>,
        nowMs: Long,
        unavailableAtMs: Map<String, Long> = emptyMap(),
    ): List<String> {
        val cooling = coolingDown(candidates, rateLimitedAtMs, nowMs)
        val kept = if (cooling.isEmpty()) {
            candidates
        } else {
            candidates.filterNot { it in cooling }.ifEmpty { candidates }
        }
        return orderByRecentStallsAndHealth(kept, stalledAtMs, unavailableAtMs, nowMs)
    }

    /**
     * [orderByRecentStalls], with providers that reported themselves unavailable moved to the end.
     *
     * Two demotions, in the order that decides what is asked last: a provider that cannot serve at
     * all is behind one that merely went quiet, and both are behind everything that is answering.
     * Nothing is dropped - a sweep with only unreachable providers still asks them, because asking a
     * broken provider is better than not playing - and the user's own priority still decides the
     * order inside each group.
     */
    fun orderByRecentStallsAndHealth(
        candidates: List<String>,
        stalledAtMs: Map<String, Long>,
        unavailableAtMs: Map<String, Long>,
        nowMs: Long,
    ): List<String> {
        if (unavailableAtMs.isEmpty()) return orderByRecentStalls(candidates, stalledAtMs, nowMs)
        val (reachable, down) = candidates.partition { !isUnavailable(unavailableAtMs[it], nowMs) }
        if (down.isEmpty()) return orderByRecentStalls(candidates, stalledAtMs, nowMs)
        return orderByRecentStalls(reachable, stalledAtMs, nowMs) +
            orderByRecentStalls(down, stalledAtMs, nowMs)
    }

    /**
     * [orderAvailable] for the tail of a sweep in progress.
     *
     * The already-attempted prefix is untouched for the same reason [orderRemaining] leaves it
     * alone: it must never be re-asked. Only the providers still ahead are filtered, and if that
     * would leave none of them, the tail is kept as it was.
     */
    fun orderRemainingAvailable(
        candidates: List<String>,
        attemptedCount: Int,
        stalledAtMs: Map<String, Long>,
        rateLimitedAtMs: Map<String, Long>,
        nowMs: Long,
        unavailableAtMs: Map<String, Long> = emptyMap(),
    ): List<String> {
        if (attemptedCount <= 0 || attemptedCount >= candidates.size) return candidates
        val attempted = candidates.take(attemptedCount)
        val remaining = candidates.drop(attemptedCount)
        val usable = orderAvailable(remaining, stalledAtMs, rateLimitedAtMs, nowMs, unavailableAtMs)
        return if (usable == remaining) candidates else attempted + usable
    }

    /**
     * The candidate order to try, with recently stalled providers moved to the back.
     *
     * A stable partition rather than a sort: the user's configured provider priority is
     * preserved inside each half, so this only decides which half goes first.
     */
    fun orderByRecentStalls(
        candidates: List<String>,
        stalledAtMs: Map<String, Long>,
        nowMs: Long,
    ): List<String> {
        if (stalledAtMs.isEmpty()) return candidates
        val (demoted, healthy) =
            candidates.partition { isDemoted(stalledAtMs[it], nowMs) }
        return healthy + demoted
    }

    /**
     * The order for the rest of a sweep in progress: [attemptedCount] sources are already
     * answered, and a provider the sweep has just watched go quiet moves behind the ones it has
     * not reached yet.
     *
     * This exists because the runtime's walk and this sweep are not the same walk. The runtime is
     * handed the whole candidate list and falls through it by itself, so by the time an attempt is
     * abandoned the runtime has usually already paid for a provider further down our list - and
     * then our own loop arrives at that same provider and pays for it a second time. Measured: an
     * attempt for `amazon` was abandoned while the runtime had reached `deezer`, and the very next
     * attempt was `deezer`, costing the same stall budget twice.
     *
     * The already-attempted prefix is left exactly where it is - it must never be re-asked - and
     * the remaining tail is stable-partitioned, so the user's priority still decides the order of
     * everything that is not demoted. Nothing is dropped: a provider moved here is put the question
     * later in the same sweep, not skipped.
     */
    fun orderRemaining(
        candidates: List<String>,
        attemptedCount: Int,
        stalledAtMs: Map<String, Long>,
        nowMs: Long,
    ): List<String> {
        if (stalledAtMs.isEmpty()) return candidates
        if (attemptedCount <= 0 || attemptedCount >= candidates.size) return candidates
        val attempted = candidates.take(attemptedCount)
        val remaining = candidates.drop(attemptedCount)
        val reordered = attempted + orderByRecentStalls(remaining, stalledAtMs, nowMs)
        return if (reordered == candidates) candidates else reordered
    }

    /** The smaller of the stall budget and what is left of the ceiling. */
    fun effectiveTimeoutMs(
        startedAtMs: Long,
        nowMs: Long,
        stallTimeoutMs: Long = STALL_TIMEOUT_MS,
        ceilingMs: Long = ATTEMPT_CEILING_MS,
    ): Long {
        val ceilingRemaining = ceilingMs - (nowMs - startedAtMs)
        return min(stallTimeoutMs, ceilingRemaining.coerceAtLeast(0L))
    }
}

/** Why an attempt was abandoned, so the reported cause matches the trigger that fired. */
enum class ProviderAbandonReason {
    /** Nothing at all arrived for [ProviderStallMeter.stalledMillis]. */
    STALLED,

    /** Events kept arriving but no byte moved for the whole ceiling. */
    CEILING,
}

/**
 * What each provider has been observed to need before it starts moving bytes.
 *
 * This is the memory that makes [SpotiFLACProviderStallPolicy.resolutionWindowMs] adaptive across
 * tracks and across launches: without it every sweep re-learns that this network is slower than
 * 6s by being killed at 6s, and a track only that provider has is skipped every time.
 *
 * A decayed average rather than the last sample, because one slow round trip on a bad connection
 * must not widen a provider's window for a week - and one fast miss must not narrow it back to a
 * window that kills the next real download.
 */
class ProviderTimings(
    private val observedMs: MutableMap<String, Long> = mutableMapOf(),
) {
    /** The idle budget to give this provider's resolution phase. */
    fun windowFor(sourceId: String): Long =
        SpotiFLACProviderStallPolicy.resolutionWindowMs(observedMs[key(sourceId)])

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

    private fun key(sourceId: String): String = sourceId.trim().lowercase(java.util.Locale.US)
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
 * for a while, so the ceiling exists to bound that; but a provider streaming a 60 MB
 * FLAC emits a sample per percent and can need longer than the ceiling to finish, and
 * killing that attempt throws away a download that was about to succeed.
 */
class ProviderStallMeter(
    private val startedAtMs: Long = SystemClock.elapsedRealtime(),
    /**
     * The idle budget for this provider while it is still resolving, from
     * [ProviderTimings.windowFor] - adaptive, because a flat window either kills a slow network or
     * holds a fast one for a wait it does not need.
     */
    private val resolutionTimeoutMs: Long = SpotiFLACProviderStallPolicy.RESOLUTION_STALL_TIMEOUT_MS,
) {
    private val lastProgressAtMs = AtomicLong(startedAtMs)
    private val lastTransferAtMs = AtomicLong(startedAtMs)
    private val lastSeq = AtomicLong(Long.MIN_VALUE)

    /** No bytes have moved yet, so any first byte counts as a transfer. */
    private val transferredBytes = AtomicLong(-1L)

    @Volatile
    var lastStage: String? = null
        private set

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

    /** True while real bytes have arrived inside the stall window. */
    fun transferring(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        transferSilenceMillis(nowMs) < SpotiFLACProviderStallPolicy.STALL_TIMEOUT_MS

    /**
     * True once any real byte has arrived, so the attempt is transferring, not resolving.
     *
     * Strictly *more than zero*: the runtime's resolving stages report `bytes=0/0`, which is a
     * progress event with no data behind it. Counting that as a transfer would put every
     * resolution phase on the tight transfer window and undo the resolution budget entirely -
     * measured on device as qobuz-web still being abandoned at 8.1s after that budget was added.
     */
    fun hasTransferred(): Boolean = transferredBytes.get() > 0L

    /**
     * The idle budget for what this attempt is currently doing.
     *
     * Before a byte moves the provider is resolving, and it may be waiting on a retry it declared
     * itself - which is not a wedge, and must not be abandoned as one
     * ([SpotiFLACProviderStallPolicy.RESOLUTION_STALL_TIMEOUT_MS]). Once bytes are arriving,
     * silence means the transfer stopped, and the tight window is the right one.
     */
    private fun idleTimeoutMs(): Long =
        when {
            hasTransferred() -> SpotiFLACProviderStallPolicy.STALL_TIMEOUT_MS
            // The runtime's own metadata phase, which is not a provider hanging on a socket and whose
            // cost is measured in tens of seconds: see
            // [SpotiFLACProviderStallPolicy.METADATA_STAGE_IDLE_MS]. Bounded by the same ceiling that
            // bounds every attempt, so this cannot hold a sweep open indefinitely.
            SpotiFLACProviderStallPolicy.isMetadataStage(lastStage) ->
                maxOf(resolutionTimeoutMs, SpotiFLACProviderStallPolicy.METADATA_STAGE_IDLE_MS)
            else -> resolutionTimeoutMs
        }

    /**
     * How long this attempt took to start moving bytes, or null if it never did.
     *
     * This is the measurement [ProviderTimings] learns from: the *resolution* cost, which is what
     * the resolution window has to cover. An attempt that reported `already_exists` or replayed a
     * cached file moved no bytes and is not a measurement of anything.
     */
    fun timeToFirstByteMs(): Long? =
        if (hasTransferred()) lastTransferAtMs.get() - startedAtMs else null

    /**
     * Why this attempt should be abandoned, or null while it still deserves waiting.
     *
     * The ceiling is counted from the last real transfer rather than the attempt start:
     * an attempt that is moving bytes is bounded by the stall timeout (which is what
     * "the download stopped" actually looks like), while an attempt that only ticks
     * events without data is still capped at the ceiling.
     */
    fun abandonReason(nowMs: Long = SystemClock.elapsedRealtime()): ProviderAbandonReason? =
        when {
            // The absolute bound is asked first because it is the one that cannot be widened: the
            // metadata phase's idle budget is as long as the ceiling, so both can be true at once and
            // the attempt ended for the reason it cannot outlive.
            SpotiFLACProviderStallPolicy.isOverCeiling(ceilingBaselineMs(), nowMs) ->
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
