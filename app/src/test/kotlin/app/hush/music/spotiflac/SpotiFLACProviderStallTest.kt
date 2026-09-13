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
        assertFalse(meter.shouldAbandon(nowMs = 8_999L))
        assertEquals("resolving_metadata", meter.lastStage)

        // A genuinely new event moves it forward and remembers the stage.
        meter.onProgress(seq = 8L, stage = "resolving_stream", nowMs = 9_000L)
        assertEquals("resolving_stream", meter.lastStage)
        assertFalse(meter.shouldAbandon(nowMs = 9_000L + timeout - 1))
        assertTrue(meter.shouldAbandon(nowMs = 9_000L + timeout))
    }

    @Test
    fun `the meter abandons on the ceiling even with steady progress`() {
        val meter = ProviderStallMeter(startedAtMs = 0L)
        meter.onProgress(seq = 1L, stage = "downloading", nowMs = ceiling - 1_000L)
        // Not stalled - but the attempt as a whole has run long enough.
        assertFalse(
            SpotiFLACProviderStallPolicy.isStalled(
                meter.lastProgressAt(),
                nowMs = ceiling - 1_000L,
            ),
        )
        assertTrue(meter.shouldAbandon(nowMs = ceiling))
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
}
