/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.os.SystemClock
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

    /** How often the watchdog re-reads the stall meter. */
    const val POLL_MS = 250L

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
     * Why this attempt should be abandoned, or null while it still deserves waiting.
     *
     * The ceiling is counted from the last real transfer rather than the attempt start:
     * an attempt that is moving bytes is bounded by the stall timeout (which is what
     * "the download stopped" actually looks like), while an attempt that only ticks
     * events without data is still capped at the ceiling.
     */
    fun abandonReason(nowMs: Long = SystemClock.elapsedRealtime()): ProviderAbandonReason? =
        when {
            SpotiFLACProviderStallPolicy.isStalled(lastProgressAtMs.get(), nowMs) ->
                ProviderAbandonReason.STALLED
            SpotiFLACProviderStallPolicy.isOverCeiling(ceilingBaselineMs(), nowMs) ->
                ProviderAbandonReason.CEILING
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
