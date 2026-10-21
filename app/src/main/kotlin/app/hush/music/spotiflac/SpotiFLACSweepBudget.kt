/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * The clock one provider sweep runs against, and the one reading that may move it.
 *
 * A sweep is a walk of the user's sources, and the budget over it exists to bound a wait: past
 * `MAX_SWEEP_BUDGET_MS` the failure is more likely systemic than a chain that merely needed longer.
 * What that budget could not tell apart is a chain that is *stuck* from a chain that is *working* -
 * so on the reporting device, with seventeen sources switched on and a fourteen-candidate walk, the
 * sweep was cut off at its 96s cap with a provider 70% through a 26 MB lossless file, the run was
 * recorded as `sweep unfinished`, and the track fell back to YouTube. Measured in the app's own log:
 *
 * ```
 * 14:04:57 routing spotiflac=true yt=true engines=SPOTIFLAC,YOUTUBE mediaId=dQw4w9WgXcQ
 * 14:04:57 sweep budget mediaId=dQw4w9WgXcQ sources=14 perAttemptMs=25000 budgetMs=96000
 * 14:06:30 progress sample ... stage=downloading pct=70 received=18677580 total=26492923
 * 14:06:33 native path cancelled mediaId=dQw4w9WgXcQ (not treated as a miss)
 * 14:06:33 miss memo record mediaId=dQw4w9WgXcQ reason=sweep-unfinished retentionMs=1800000
 * 14:06:34 playback label mediaId=dQw4w9WgXcQ -> YouTube • Vision
 * ```
 *
 * The bytes were arriving the whole time. So the budget is extended by exactly one signal: **the
 * transfer reading changed**. Reading it as "bytes are present" would not do - every surface keeps
 * the last sample it was given, so a finished or dead transfer looks identical to a live one from a
 * single reading. It is the *change* that proves something is happening, and there are two kinds:
 * bytes growing (a download running) and bytes starting over at zero (the walk moving on to the next
 * provider). Both are the sweep doing work; neither is the sweep waiting.
 *
 * The reset case is not a nicety. A provider that trickles 47MB over two minutes and then goes quiet
 * spends the whole budget on itself: on the reporting device the attempt was abandoned after 168s of
 * wall time with the last thirty of them silent, and because the clock was already gone the sources
 * below it in the user's list were never asked at all - the same "never asked" failure the budget was
 * supposed to prevent, one level down. A new attempt is the walk continuing, so it re-arms the clock.
 *
 * The third reading is the attempt itself changing: [Reading.sourceId] is which source the meter is
 * describing, and a source that is different from the last one is a new entry in the walk starting.
 * It has to count, because most attempts spend their time *before* their first byte - a module runs a
 * search, a runtime provider resolves metadata - and an attempt that has not moved a byte yet looks
 * exactly like a dead sweep from the byte count alone. Measured on the reporting device: the byte
 * clock expired 34s after the walk moved from a module that had stopped transferring to the two
 * behind it, both of which were inside their own 30s budgets when the whole sweep was cancelled -
 * and the entries below them were never asked.
 *
 * Two bounds keep the extension honest:
 *
 * - [TRANSFER_GRACE_MS] is longer than one attempt's ceiling, so an attempt that is downloading
 *   always gets to its own limit rather than being cut by the sweep's clock.
 * - [MAX_TOTAL_MS] is an absolute lid. A chain of providers each trickling a few bytes must not be
 *   able to hold playback open indefinitely; past this, the walk ends as it always did.
 */
internal object SpotiFLACSweepBudget {

    /**
     * How long a sweep may keep running after the last sign of a transfer that was still growing.
     *
     * Longer than one attempt's whole ceiling on purpose: with the shipped policy an attempt may
     * spend 5s connecting and then 60s with no data before its own watchdog gives up, so a grace
     * shorter than that pair would make this clock decide something the attempt already decides -
     * and would cut a slow but live download one level up, which is the exact failure this clock was
     * written for. 90s leaves a full download window plus the connect window and some slack.
     */
    const val TRANSFER_GRACE_MS = 90_000L

    /**
     * The longest a single sweep may run in total, whatever it is transferring.
     *
     * A lid rather than a target: the point of the budget is a bounded wait, and a series of
     * providers that each report a little progress is exactly the pathological case the original
     * budget was written for. It grew with the per-source retry setting: three attempts at a 60s
     * download window on several sources is more work than the old 240s lid could ever pay for, and
     * a lid that cuts the walk means the sources behind the cut were never asked - the one outcome
     * the budget exists to prevent. Eight minutes is still a bounded wait, and a wedged source now
     * spends its own windows rather than the whole sweep's.
     */
    const val MAX_TOTAL_MS = 480_000L

    /** How often the sweep's clock is re-read. Matches the provider watchdog's own poll. */
    const val POLL_MS = 250L

    /**
     * What the progress meter said, as the clock needs it: how much has arrived, and who is sending.
     *
     * [sourceId] is the source the sample belongs to. It is the only reading that says "a new entry
     * in the walk has started", which is activity that produces no bytes for a while - a module's
     * search, a provider's metadata phase - and therefore the reading without which those attempts
     * look like a sweep that is waiting.
     */
    data class Reading(
        val transferredBytes: Long = 0L,
        val sourceId: String? = null,
    )

    /**
     * The clock as the sweep holds it: when it runs out, and what the meter said when it was last
     * moved.
     *
     * [transferredBytes] and [sourceId] are carried rather than passed in again so a change is
     * measured against the reading that extended the clock, not against the last sample of a transfer
     * that has since been replaced by a different one.
     */
    data class Clock(
        val startedAtMs: Long,
        val deadlineMs: Long,
        val transferredBytes: Long = 0L,
        val sourceId: String? = null,
    ) {
        /** Time left before this clock runs out, floored at zero. */
        fun remainingMs(nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L)

        fun expired(nowMs: Long): Boolean = nowMs >= deadlineMs
    }

    fun start(nowMs: Long, budgetMs: Long): Clock =
        Clock(startedAtMs = nowMs, deadlineMs = nowMs + budgetMs.coerceAtLeast(1L))

    /**
     * The clock after a progress reading: moved whenever the reading *changed*, and never past
     * [MAX_TOTAL_MS] from the start.
     *
     * A reading identical to the last one leaves the clock exactly where it was, which is what makes a
     * dead transfer and a slow one different answers: the meter keeps repeating its last sample, so
     * silence reads as silence. A reading that is *lower* is the next attempt starting its own count -
     * the walk continuing - and it re-arms the clock for the same reason a growing reading does. A
     * reading that names a *different source* is the walk continuing too, whatever the byte count
     * says, which is what covers an attempt that spends its first seconds resolving rather than
     * transferring.
     */
    fun afterProgress(
        clock: Clock,
        nowMs: Long,
        transferredBytes: Long,
        sourceId: String? = clock.sourceId,
    ): Clock {
        if (transferredBytes == clock.transferredBytes && sourceId == clock.sourceId) return clock
        val grace = nowMs + TRANSFER_GRACE_MS
        val lid = clock.startedAtMs + MAX_TOTAL_MS
        return clock.copy(
            deadlineMs = minOf(grace, lid),
            transferredBytes = transferredBytes,
            sourceId = sourceId,
        )
    }

    /** Whether the clock has been extended at all - for the log, so a long sweep explains itself. */
    fun extended(clock: Clock): Boolean = clock.transferredBytes > 0L
}
