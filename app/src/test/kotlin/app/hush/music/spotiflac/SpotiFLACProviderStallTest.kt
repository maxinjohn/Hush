package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog exists because one wedged provider used to spend the whole sweep budget
 * and starve every provider behind it. These pin the boundaries that decide when a
 * provider is abandoned, and the classification that keeps a stall from being mistaken
 * for "the catalogues do not have this track".
 */
class SpotiFLACProviderStallTest {

    private val timeout = SpotiFLACProviderStallPolicy.STALL_TIMEOUT_MS
    private val ceiling = SpotiFLACProviderStallPolicy.ATTEMPT_CEILING_MS

    @Test
    fun `a provider is stalled only once the silence outlasts the timeout`() {
        val lastProgress = 1_000L
        assertFalse(SpotiFLACProviderStallPolicy.isStalled(lastProgress, lastProgress))
        assertFalse(SpotiFLACProviderStallPolicy.isStalled(lastProgress, lastProgress + timeout - 1))
        // The boundary itself counts: qobuz-web sat silent for 26s on device.
        assertTrue(SpotiFLACProviderStallPolicy.isStalled(lastProgress, lastProgress + timeout))
        assertTrue(
            SpotiFLACProviderStallPolicy.isStalled(lastProgress, lastProgress + 26_000L),
        )
    }

    @Test
    fun `the absolute ceiling catches a provider that keeps dribbling progress`() {
        val started = 5_000L
        assertFalse(SpotiFLACProviderStallPolicy.isOverCeiling(started, started + ceiling - 1))
        assertTrue(SpotiFLACProviderStallPolicy.isOverCeiling(started, started + ceiling))
    }

    @Test
    fun `the stall budget is clamped to what is left of the ceiling`() {
        val started = 0L
        assertEquals(
            timeout,
            SpotiFLACProviderStallPolicy.effectiveTimeoutMs(started, nowMs = 1_000L),
        )
        // Near the ceiling the remaining time is what actually applies ...
        assertEquals(
            ceiling - 20_000L,
            SpotiFLACProviderStallPolicy.effectiveTimeoutMs(started, nowMs = 20_000L),
        )
        // ... and past it the budget is zero rather than negative.
        assertEquals(
            0L,
            SpotiFLACProviderStallPolicy.effectiveTimeoutMs(started, nowMs = ceiling + 5_000L),
        )
    }

    @Test
    fun `a stalled provider is demoted, not dropped, and only for the cooldown`() {
        val stalledAt = 10_000L
        assertTrue(SpotiFLACProviderStallPolicy.isDemoted(stalledAt, nowMs = 10_001L))
        assertTrue(
            SpotiFLACProviderStallPolicy.isDemoted(
                stalledAt,
                nowMs = stalledAt + SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS - 1,
            ),
        )
        // After the cooldown it is an ordinary candidate again.
        assertFalse(
            SpotiFLACProviderStallPolicy.isDemoted(
                stalledAt,
                nowMs = stalledAt + SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS,
            ),
        )
        assertFalse(SpotiFLACProviderStallPolicy.isDemoted(null, nowMs = 10_001L))
    }

    @Test
    fun `ordering keeps the configured priority inside each half`() {
        val enabled = listOf("deezer", "amazon", "soundcloud", "qobuz-web", "tidal-web")

        // Nothing stalled: the user's own order is returned untouched.
        assertEquals(
            enabled,
            SpotiFLACProviderStallPolicy.orderByRecentStalls(enabled, emptyMap(), nowMs = 0L),
        )

        // qobuz-web wedged: it goes last, everything else keeps its relative order.
        val ordered =
            SpotiFLACProviderStallPolicy.orderByRecentStalls(
                candidates = enabled,
                stalledAtMs = mapOf("qobuz-web" to 1_000L),
                nowMs = 2_000L,
            )
        assertEquals(listOf("deezer", "amazon", "soundcloud", "tidal-web", "qobuz-web"), ordered)

        // Two wedged providers both move back, still in their configured order.
        val twoStalled =
            SpotiFLACProviderStallPolicy.orderByRecentStalls(
                candidates = enabled,
                stalledAtMs = mapOf("qobuz-web" to 1_000L, "deezer" to 1_000L),
                nowMs = 2_000L,
            )
        assertEquals(listOf("amazon", "soundcloud", "tidal-web", "deezer", "qobuz-web"), twoStalled)

        // A stale stall entry no longer demotes anything.
        assertEquals(
            enabled,
            SpotiFLACProviderStallPolicy.orderByRecentStalls(
                candidates = enabled,
                stalledAtMs = mapOf("qobuz-web" to 1_000L),
                nowMs = 1_000L + SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS,
            ),
        )
    }

    @Test
    fun `the meter ignores repeated samples so a wedge cannot be masked`() {
        val meter = ProviderStallMeter(startedAtMs = 1_000L)
        meter.onProgress(seq = 7L, stage = "resolving_metadata", nowMs = 1_000L)

        // The runtime repeats its last delta while the item sits still. If a repeat reset
        // the clock, a wedged provider would look healthy forever.
        meter.onProgress(seq = 7L, stage = "resolving_stream", nowMs = 4_000L)
        val resolution = SpotiFLACProviderStallPolicy.RESOLUTION_STALL_TIMEOUT_MS
        assertFalse(meter.shouldAbandon(nowMs = 1_000L + resolution - 1))
        assertEquals("resolving_metadata", meter.lastStage)

        // A genuinely new event moves it forward and remembers the stage.
        meter.onProgress(seq = 8L, stage = "resolving_stream", nowMs = 9_000L)
        assertEquals("resolving_stream", meter.lastStage)
        assertEquals(9_000L, meter.lastProgressAt())
        // No byte has arrived, so this attempt is still resolving and the resolution window
        // applies (see `a provider waiting on its own declared retry is abandoned ...`). The
        // meter here starts when the event arrives, which keeps the absolute ceiling out of the
        // way of the boundary being checked - it would otherwise fire first, at 25s from the
        // start.
        val resolving = ProviderStallMeter(startedAtMs = 9_000L)
        resolving.onProgress(seq = 1L, stage = "resolving_stream", nowMs = 9_000L)
        assertFalse(resolving.shouldAbandon(nowMs = 9_000L + resolution - 1))
        assertTrue(resolving.shouldAbandon(nowMs = 9_000L + resolution))
    }

    @Test
    fun `a provider waiting on its own declared retry is abandoned, and put the question again`() {
        // Measured on device: a provider answers PROVIDER_UNAVAILABLE, retryable,
        // retry_after_seconds=10 and the runtime sleeps the declared 10s and calls it again.
        // Waiting that out is what a listener pays for at 0:00, and it does not even rescue the
        // provider: in the same sweep `deezer` declared that retry twice and never delivered,
        // while `qobuz-web` answered in 1.8s. So the attempt is abandoned inside the wait - the
        // provider is neither waited for nor skipped, it is moved behind the sources this sweep
        // has not reached yet and asked again (asserted below).
        val meter = ProviderStallMeter(startedAtMs = 0L)
        meter.onProgress(seq = 1L, stage = "resolving_stream", nowMs = 2_000L)
        assertFalse(meter.hasTransferred())
        val resolution = SpotiFLACProviderStallPolicy.RESOLUTION_STALL_TIMEOUT_MS
        // Silence inside the window is still given the benefit of the doubt...
        assertFalse(meter.shouldAbandon(nowMs = 2_000L + resolution - 1))
        // ...and the declared 10s sleep is not: the retry cannot finish inside any window worth
        // holding the player at 0:00 for.
        assertEquals(
            ProviderAbandonReason.STALLED,
            meter.abandonReason(nowMs = 2_000L + 10_000L),
        )

        // Asked again later in the same sweep, not dropped: the one source already answered
        // keeps its place, and the provider that went quiet moves behind the three the sweep has
        // not reached yet.
        val stalledAt = 100_000L
        assertEquals(
            listOf("amazon", "deezer", "soundcloud", "tidal-web", "qobuz-web"),
            SpotiFLACProviderStallPolicy.orderRemaining(
                candidates = listOf("amazon", "deezer", "qobuz-web", "soundcloud", "tidal-web"),
                attemptedCount = 1,
                stalledAtMs = mapOf("qobuz-web" to stalledAt),
                nowMs = stalledAt + 1_000L,
            ),
        )
    }

    @Test
    fun `a sweep in progress leaves what it answered alone and never re-asks it`() {
        val nowMs = 500_000L
        val candidates = listOf("amazon", "deezer", "qobuz-web", "soundcloud")
        // Nothing has stalled: the order is untouched, so this can be applied unconditionally
        // after every attempt without changing a sweep that is already working.
        assertEquals(
            candidates,
            SpotiFLACProviderStallPolicy.orderRemaining(candidates, 1, emptyMap(), nowMs),
        )
        // A provider ahead of the cursor stays behind it: it has been answered, and re-asking it
        // is exactly the duplicate cost this ordering exists to prevent.
        val reordered =
            SpotiFLACProviderStallPolicy.orderRemaining(
                candidates = candidates,
                attemptedCount = 2,
                stalledAtMs = mapOf("amazon" to nowMs - 500L),
                nowMs = nowMs,
            )
        assertEquals(listOf("amazon", "deezer"), reordered.take(2))
        assertEquals(listOf("qobuz-web", "soundcloud"), reordered.drop(2))
        // Every candidate survives the reorder, in one order or the other.
        assertEquals(candidates.toSet(), reordered.toSet())
        // Nothing left to try, nothing to reorder.
        assertEquals(
            candidates,
            SpotiFLACProviderStallPolicy.orderRemaining(
                candidates,
                attemptedCount = candidates.size,
                stalledAtMs = mapOf("soundcloud" to nowMs),
                nowMs = nowMs,
            ),
        )
    }

    @Test
    fun `a zero-byte progress event is not a transfer`() {
        // The runtime's resolving stages report `bytes=0/0`: a progress event with no data
        // behind it. Counting that as a transfer puts every resolution phase on the tight
        // transfer window, which is exactly how qobuz-web stayed abandoned at 8.1s even after
        // the resolution budget was introduced.
        val meter = ProviderStallMeter(startedAtMs = 0L)
        meter.onProgress(seq = 1L, stage = "resolving_stream", bytesReceived = 0L, nowMs = 1_000L)
        assertFalse(meter.hasTransferred())
        val resolution = SpotiFLACProviderStallPolicy.RESOLUTION_STALL_TIMEOUT_MS
        assertFalse(meter.shouldAbandon(nowMs = 1_000L + resolution - 1))
        assertTrue(meter.shouldAbandon(nowMs = 1_000L + resolution))
    }

    @Test
    fun `the tight window applies again once bytes have moved`() {
        // Silence after data has flowed means a stopped transfer, not a provider asking for
        // time, so the narrow window governs the rest of the attempt.
        val meter = ProviderStallMeter(startedAtMs = 0L)
        meter.onProgress(seq = 1L, stage = "resolving_stream", nowMs = 1_000L)
        meter.onProgress(seq = 2L, stage = "downloading", bytesReceived = 4_000_000L, nowMs = 3_000L)
        assertTrue(meter.hasTransferred())
        assertFalse(meter.shouldAbandon(nowMs = 3_000L + timeout - 1))
        assertEquals(
            ProviderAbandonReason.STALLED,
            meter.abandonReason(nowMs = 3_000L + timeout),
        )
    }

    @Test
    fun `the meter abandons on the ceiling even when events keep arriving`() {
        val meter = ProviderStallMeter(startedAtMs = 0L)
        // Events keep ticking but no byte is ever reported, so the attempt is not moving
        // data - this is the dribble the ceiling exists to bound.
        meter.onProgress(seq = 1L, stage = "resolving_stream", nowMs = ceiling - 1_000L)
        assertFalse(
            SpotiFLACProviderStallPolicy.isStalled(
                meter.lastProgressAt(),
                nowMs = ceiling - 1_000L,
            ),
        )
        assertTrue(meter.shouldAbandon(nowMs = ceiling))
        assertEquals(ProviderAbandonReason.CEILING, meter.abandonReason(nowMs = ceiling))
    }

    @Test
    fun `an attempt that is moving bytes is not killed by the ceiling`() {
        // On device a ceiling counted from the attempt start abandoned providers that
        // were downloading fine, and demoted them for the full cooldown afterwards.
        // A 60 MB lossless file at ~2 MB/s legitimately needs longer than the ceiling.
        val meter = ProviderStallMeter(startedAtMs = 0L)
        var bytes = 0L
        var now = 1_000L
        while (now < ceiling * 3) {
            bytes += 4_000_000L
            meter.onProgress(seq = now, stage = "downloading", bytesReceived = bytes, nowMs = now)
            now += 1_000L
        }
        assertTrue(meter.transferring(nowMs = now))
        assertFalse(meter.shouldAbandon(nowMs = now))
        assertEquals(null, meter.abandonReason(nowMs = now))
    }

    @Test
    fun `a transfer that stops is still caught by the stall timeout`() {
        val meter = ProviderStallMeter(startedAtMs = 0L)
        meter.onProgress(seq = 1L, stage = "downloading", bytesReceived = 8_000_000L, nowMs = 9_000L)
        // Bytes stopped at 9s: the ceiling is no longer what bounds this attempt.
        assertTrue(meter.transferring(nowMs = 10_000L))
        assertFalse(meter.shouldAbandon(nowMs = 10_000L))
        assertEquals(
            ProviderAbandonReason.STALLED,
            meter.abandonReason(nowMs = 9_000L + timeout),
        )
    }

    @Test
    fun `a repeated byte count is not a transfer`() {
        val meter = ProviderStallMeter(startedAtMs = 0L)
        meter.onProgress(seq = 1L, stage = "downloading", bytesReceived = 5_000L, nowMs = 1_000L)
        // The runtime re-reports its size without moving data; that must not look like
        // progress, or a wedged provider would hold the sweep indefinitely.
        meter.onProgress(seq = 2L, stage = "downloading", bytesReceived = 5_000L, nowMs = 20_000L)
        assertEquals(1_000L, meter.lastTransferAt())
        assertFalse(meter.transferring(nowMs = 20_000L))
    }

    @Test
    fun `the ceiling reports transfer silence rather than the last event gap`() {
        // The misleading case seen on device: "timed out without progress for 29ms" for
        // an attempt abandoned by the ceiling. The reported number must be the silence
        // since the last byte, so an operator is not sent after the wrong cause.
        val meter = ProviderStallMeter(startedAtMs = 0L)
        // One real byte at the start, then events with no data until the ceiling.
        meter.onProgress(seq = 1L, stage = "downloading", bytesReceived = 1_000L, nowMs = 0L)
        meter.onProgress(seq = 2L, stage = "resolving_stream", nowMs = ceiling - 29L)
        assertEquals(ProviderAbandonReason.CEILING, meter.abandonReason(nowMs = ceiling))
        assertEquals(29L, meter.stalledMillis(nowMs = ceiling))
        assertEquals(ceiling, meter.transferSilenceMillis(nowMs = ceiling))
    }

    @Test
    fun `a stall is never mistaken for a catalogue verdict`() {
        val stalled = SpotiFLACProviderStalledException(
            sourceId = "qobuz-web",
            stage = "resolving_stream",
            stalledMillis = 26_000L,
        )
        // Asserted through the verdict, because that is what decides whether the track is
        // hidden from SpotiFLAC for hours.
        assertEquals(SpotiFLACSweepOutcome.UNAVAILABLE, SpotiFLACSweepVerdict.forFailure(stalled))

        // And one stalled provider disqualifies a sweep whose other sources said
        // "no results" - the sweep never got an answer from the whole chain.
        val aggregate =
            "deezer=no results returned; amazon=no results returned; qobuz-web=${stalled.message}"
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict(aggregate))

        // Sanity: without the stall that same aggregate really would be a verdict, so the
        // assertion above is testing the stall and not a blanket rule.
        val noStall = "deezer=no results returned; amazon=no results returned"
        assertTrue(SpotiFLACSweepVerdict.isCatalogueVerdict(noStall))
        assertNotEquals(SpotiFLACSweepOutcome.NO_MATCH, SpotiFLACSweepVerdict.forFailure(stalled))
        assertEquals("qobuz-web", stalled.sourceId)
    }

    @Test
    fun `a demotion restored after a restart still holds the provider back`() {
        val stalledAt = 1_000_000L
        val persisted = mapOf("amazon" to stalledAt)
        val order =
            SpotiFLACProviderStallPolicy.orderByRecentStalls(
                candidates = listOf("amazon", "deezer", "qobuz-web"),
                stalledAtMs = persisted,
                // One instant after the restart, well inside the cooldown.
                nowMs = stalledAt + 1_000L,
            )
        assertEquals(listOf("deezer", "qobuz-web", "amazon"), order)
    }

    @Test
    fun `a demotion that expired while the app was closed is not applied`() {
        val stalledAt = 1_000_000L
        val nowMs = stalledAt + SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS
        // The restore path drops entries that are no longer in cooldown, so the provider
        // is not pushed behind the answering ones on a stale statement.
        assertFalse(SpotiFLACProviderStallPolicy.isDemoted(stalledAt, nowMs))
        assertEquals(
            listOf("amazon", "deezer", "qobuz-web"),
            SpotiFLACProviderStallPolicy.orderByRecentStalls(
                candidates = listOf("amazon", "deezer", "qobuz-web"),
                stalledAtMs = emptyMap(),
                nowMs = nowMs,
            ),
        )
    }

    //
    // The resolution window. It used to be a constant, and on a network slower than that constant
    // every provider was killed on the line: measured on device, deezer's own extension call took
    // 6036ms and was abandoned at 6009ms of silence, and because every provider measured about the
    // same the whole sweep failed and the track fell back to YouTube.
    //

    /** A provider this install has never timed is unknown, not fast. */
    @Test
    fun `an unmeasured provider gets the generous initial window`() {
        assertEquals(
            SpotiFLACProviderStallPolicy.INITIAL_RESOLUTION_WINDOW_MS,
            SpotiFLACProviderStallPolicy.resolutionWindowMs(null),
        )
        assertEquals(
            SpotiFLACProviderStallPolicy.INITIAL_RESOLUTION_WINDOW_MS,
            SpotiFLACProviderStallPolicy.resolutionWindowMs(0L),
        )
        assertTrue(
            SpotiFLACProviderStallPolicy.INITIAL_RESOLUTION_WINDOW_MS >
                SpotiFLACProviderStallPolicy.RESOLUTION_STALL_TIMEOUT_MS,
        )
    }

    /** The window a slow provider is given grows past the constant that killed it. */
    @Test
    fun `a measured provider is given twice what it needed, within the ceiling`() {
        // The observed device figure: 6036ms was abandoned under a 6000ms window.
        assertEquals(12_072L, SpotiFLACProviderStallPolicy.resolutionWindowMs(6_036L))
        // A fast catalogue miss keeps the tight floor rather than inheriting the generous one.
        assertEquals(
            SpotiFLACProviderStallPolicy.RESOLUTION_STALL_TIMEOUT_MS,
            SpotiFLACProviderStallPolicy.resolutionWindowMs(1_500L),
        )
        // Never past the ceiling that bounds every attempt anyway.
        assertEquals(
            SpotiFLACProviderStallPolicy.ATTEMPT_CEILING_MS,
            SpotiFLACProviderStallPolicy.resolutionWindowMs(60_000L),
        )
    }

    /** The memory of what a provider needs survives tracks and launches. */
    @Test
    fun `measured timings are averaged, not overwritten, and restored by id`() {
        val timings = ProviderTimings()
        assertEquals(
            SpotiFLACProviderStallPolicy.INITIAL_RESOLUTION_WINDOW_MS,
            timings.windowFor("deezer"),
        )
        timings.record("deezer", 4_000L)
        assertEquals(8_000L, timings.windowFor("deezer"))
        // A later, slower attempt moves the average toward it rather than replacing it.
        timings.record("deezer", 8_000L)
        assertEquals(5_000L, timings.snapshot()["deezer"])
        assertEquals(10_000L, timings.windowFor("deezer"))
        // Case and padding are not a second provider.
        timings.record("  DEEZER ", 5_000L)
        assertEquals(1, timings.snapshot().size)
        // A measurement of zero is nothing to learn from.
        timings.record("amazon", 0L)
        assertTrue(timings.snapshot()["amazon"] == null)

        val restored = ProviderTimings()
        restored.restore(timings.snapshot())
        assertEquals(timings.windowFor("deezer"), restored.windowFor("deezer"))
        assertEquals(
            SpotiFLACProviderStallPolicy.INITIAL_RESOLUTION_WINDOW_MS,
            restored.windowFor("qobuz-web"),
        )
    }

    /**
     * A provider that refused service is left out of the chain, not merely moved down it.
     *
     * Measured on the reporting device: Deezer answered `HTTP 429` and was still asked first on
     * every later track, because a refusal is not a stall and nothing demoted it.
     */
    @Test
    fun `a provider that refused service is not asked again while it cools down`() {
        val now = 1_000_000L
        val cooling = mapOf("deezer" to now - 60_000L)
        assertEquals(
            listOf("amazon", "qobuz-web", "tidal-web"),
            SpotiFLACProviderStallPolicy.orderAvailable(
                candidates = listOf("deezer", "amazon", "qobuz-web", "tidal-web"),
                stalledAtMs = emptyMap(),
                rateLimitedAtMs = cooling,
                nowMs = now,
            ),
        )
        assertEquals(
            listOf("deezer"),
            SpotiFLACProviderStallPolicy.coolingDown(
                candidates = listOf("deezer", "amazon"),
                rateLimitedAtMs = cooling,
                nowMs = now,
            ),
        )
    }

    /** The cooldown lapses: this is a pause, not a blacklist. */
    @Test
    fun `a provider comes back once its refusal has expired`() {
        val now = 1_000_000L
        val expired = mapOf("deezer" to now - SpotiFLACProviderStallPolicy.RATE_LIMIT_COOLDOWN_MS - 1L)
        assertTrue(!SpotiFLACProviderStallPolicy.isRateLimited(expired["deezer"], now))
        assertEquals(
            listOf("deezer", "amazon"),
            SpotiFLACProviderStallPolicy.orderAvailable(
                candidates = listOf("deezer", "amazon"),
                stalledAtMs = emptyMap(),
                rateLimitedAtMs = expired,
                nowMs = now,
            ),
        )
        assertTrue(SpotiFLACProviderStallPolicy.RATE_LIMIT_COOLDOWN_MS > SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS)
    }

    /**
     * The safety valve: when every candidate is refusing, asking is still the only way to play.
     *
     * A single-source setup whose one provider has rate-limited us must not end up with an empty
     * chain, which would look exactly like "everything is switched off".
     */
    @Test
    fun `a chain where every provider is cooling still asks them`() {
        val now = 1_000_000L
        val allCooling = mapOf("deezer" to now, "amazon" to now)
        assertEquals(
            listOf("deezer", "amazon"),
            SpotiFLACProviderStallPolicy.orderAvailable(
                candidates = listOf("deezer", "amazon"),
                stalledAtMs = emptyMap(),
                rateLimitedAtMs = allCooling,
                nowMs = now,
            ),
        )
    }

    /** A refusal never resurrects a provider the sweep has already asked. */
    @Test
    fun `the remaining tail drops a refusing provider without re-asking the attempted prefix`() {
        val now = 1_000_000L
        val reordered =
            SpotiFLACProviderStallPolicy.orderRemainingAvailable(
                candidates = listOf("deezer", "amazon", "qobuz-web", "tidal-web"),
                attemptedCount = 1,
                stalledAtMs = emptyMap(),
                rateLimitedAtMs = mapOf("qobuz-web" to now),
                nowMs = now,
            )
        assertEquals(listOf("deezer"), reordered.take(1))
        assertEquals(
            listOf("amazon", "tidal-web"),
            reordered.drop(1),
        )
    }

    // ------------------------------------------------------------------
    // The runtime's own metadata phase
    // ------------------------------------------------------------------

    /**
     * The measured bug: a provider killed on the line inside the runtime's metadata search.
     *
     * On the reporting device qobuz-web was given 6000ms (its own measured window), spent 9.7s and
     * then 13.5s in the runtime's `searchTracks` calls for the *metadata* phase, and was abandoned
     * 6027ms in - "stuck on resolving_metadata". Four providers went the same way and the sweep
     * reported "all sources failed", which became a remembered miss, so the track kept falling back
     * to YouTube although the providers were answering.
     */
    @Test
    fun `the metadata phase gets its own measured budget, not the provider's window`() {
        val meter = ProviderStallMeter(startedAtMs = 0L, resolutionTimeoutMs = 6_000L)
        meter.onProgress(seq = 1L, stage = "resolving_metadata", bytesReceived = 0L, nowMs = 0L)
        // The window that used to decide this would have killed it at 6s.
        assertEquals(null, meter.abandonReason(6_000L))
        assertEquals(null, meter.abandonReason(20_000L))
        // Bounded all the same: the ceiling ends it, so this cannot hold the sweep open.
        assertEquals(ProviderAbandonReason.CEILING, meter.abandonReason(25_000L))
    }

    /**
     * Once bytes are moving, the metadata budget no longer applies - the tight window is the signal.
     *
     * A stopped socket is a stopped socket, and the runtime saying it is downloading while no data
     * arrives is exactly the shape of a wedge this app has already paid for.
     */
    @Test
    fun `a stopped transfer is still abandoned on the tight window`() {
        val meter = ProviderStallMeter(startedAtMs = 0L, resolutionTimeoutMs = 6_000L)
        meter.onProgress(seq = 1L, stage = "resolving_metadata", bytesReceived = 0L, nowMs = 0L)
        meter.onProgress(seq = 2L, stage = "downloading", bytesReceived = 4096L, nowMs = 1_000L)
        assertEquals(null, meter.abandonReason(8_000L))
        assertEquals(ProviderAbandonReason.STALLED, meter.abandonReason(9_500L))
    }

    /** The stage that names the runtime's own work is matched exactly, not by substring. */
    @Test
    fun `only the metadata stage widens the budget`() {
        assertTrue(SpotiFLACProviderStallPolicy.isMetadataStage("resolving_metadata"))
        assertTrue(SpotiFLACProviderStallPolicy.isMetadataStage(" Resolving_Metadata "))
        assertFalse(SpotiFLACProviderStallPolicy.isMetadataStage("resolving_stream"))
        assertFalse(SpotiFLACProviderStallPolicy.isMetadataStage("downloading"))
        assertFalse(SpotiFLACProviderStallPolicy.isMetadataStage(null))
    }

    /** The window a sweep's budget is sized from covers the metadata phase an attempt pays for. */
    @Test
    fun `the try window is never below the metadata phase`() {
        assertEquals(
            SpotiFLACProviderStallPolicy.METADATA_STAGE_IDLE_MS,
            SpotiFLACProviderStallPolicy.tryWindowMs(3_000L),
        )
        // Never above the ceiling that bounds every attempt either.
        assertEquals(
            SpotiFLACProviderStallPolicy.ATTEMPT_CEILING_MS,
            SpotiFLACProviderStallPolicy.tryWindowMs(null),
        )
    }

    /** The meter reports the resolution cost the window is meant to cover. */
    @Test
    fun `the meter measures time to first byte, and nothing when no byte moved`() {
        val meter = ProviderStallMeter(startedAtMs = 1_000L, resolutionTimeoutMs = 12_000L)
        assertEquals(null, meter.timeToFirstByteMs())
        // A resolving-stage event with zero bytes is not a first byte.
        meter.onProgress(seq = 1L, stage = "resolving_stream", bytesReceived = 0L, nowMs = 3_000L)
        assertEquals(null, meter.timeToFirstByteMs())
        meter.onProgress(seq = 2L, stage = "downloading", bytesReceived = 512L, nowMs = 6_000L)
        assertEquals(5_000L, meter.timeToFirstByteMs())
    }
}
