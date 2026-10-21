package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog exists because one wedged provider used to spend the whole sweep budget and starve
 * every provider behind it. These pin the boundaries that decide when a provider is abandoned - and
 * the windows those boundaries are counted in are the user's per-source policy, so each case names
 * the policy it is asserting rather than a constant in the app.
 *
 * The two windows answer two different questions and are asserted separately throughout:
 *
 *  - the connect window bounds silence *before* the first byte ("it never connected");
 *  - the download window bounds silence *after* bytes have started ("the transfer stopped"), and it
 *    is much longer, because cutting a download that is arriving is the failure this file exists to
 *    prevent - measured on device as a provider 70% through a 26 MB FLAC abandoned and the track
 *    falling back to YouTube.
 */
class SpotiFLACProviderStallTest {

    private val policy = SpotiFLACFetchPolicy.DEFAULT
    private val connect = policy.connectTimeoutMs
    private val download = policy.downloadTimeoutMs
    private val ceiling = policy.attemptCeilingMs

    @Test
    fun `the shipped policy is five seconds to connect and a minute of silence to download`() {
        assertEquals(5_000L, connect)
        assertEquals(60_000L, download)
        assertEquals(3, policy.maxAttempts)
        // The attempt may spend the connect window and then its whole download window.
        assertEquals(connect + download, ceiling)
    }

    @Test
    fun `a provider is stalled only once the silence outlasts the window`() {
        val lastProgress = 1_000L
        assertFalse(SpotiFLACProviderStallPolicy.isStalled(lastProgress, lastProgress, connect))
        assertFalse(
            SpotiFLACProviderStallPolicy.isStalled(lastProgress, lastProgress + connect - 1, connect),
        )
        // The boundary itself counts.
        assertTrue(SpotiFLACProviderStallPolicy.isStalled(lastProgress, lastProgress + connect, connect))
        assertTrue(
            SpotiFLACProviderStallPolicy.isStalled(lastProgress, lastProgress + 26_000L, connect),
        )
    }

    @Test
    fun `the ceiling is the attempt's own two windows`() {
        val started = 5_000L
        assertFalse(SpotiFLACProviderStallPolicy.isOverCeiling(started, started + ceiling - 1, ceiling))
        assertTrue(SpotiFLACProviderStallPolicy.isOverCeiling(started, started + ceiling, ceiling))
    }

    @Test
    fun `the stall budget is clamped to what is left of the ceiling`() {
        val started = 0L
        assertEquals(
            download,
            SpotiFLACProviderStallPolicy.effectiveTimeoutMs(started, nowMs = 1_000L, download, ceiling),
        )
        // Near the ceiling the remaining time is what actually applies ...
        assertEquals(
            ceiling - 20_000L,
            SpotiFLACProviderStallPolicy.effectiveTimeoutMs(started, nowMs = 20_000L, download, ceiling),
        )
        // ... and past it the budget is zero rather than negative.
        assertEquals(
            0L,
            SpotiFLACProviderStallPolicy.effectiveTimeoutMs(started, nowMs = ceiling + 5_000L, download, ceiling),
        )
    }

    @Test
    fun `a stalled provider is reported, never demoted, and only for the cooldown`() {
        val stalledAt = 10_000L
        assertTrue(SpotiFLACProviderStallPolicy.isDemoted(stalledAt, nowMs = 10_001L))
        assertTrue(
            SpotiFLACProviderStallPolicy.isDemoted(
                stalledAt,
                nowMs = stalledAt + SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS - 1,
            ),
        )
        // After the cooldown it is an ordinary candidate again - and it never stopped being one.
        assertFalse(
            SpotiFLACProviderStallPolicy.isDemoted(
                stalledAt,
                nowMs = stalledAt + SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS,
            ),
        )
        assertFalse(SpotiFLACProviderStallPolicy.isDemoted(null, nowMs = 10_001L))
    }

    /**
     * A stall is reported, never applied.
     *
     * The list the user arranged is the order a resolve walks, so a source that went quiet once is
     * still asked first next time.
     */
    @Test
    fun `a recently stalled provider keeps its place, and is only reported`() {
        val enabled = listOf("deezer", "amazon", "soundcloud", "qobuz-web", "tidal-web")
        val stalled = mapOf("qobuz-web" to 1_000L)

        assertEquals(
            listOf("qobuz-web"),
            SpotiFLACProviderStallPolicy.recentlyStalled(enabled, stalled, nowMs = 2_000L),
        )
        assertEquals(
            enabled,
            SpotiFLACProviderStallPolicy.orderAvailable(enabled, emptyMap(), nowMs = 2_000L),
        )
        assertTrue(
            SpotiFLACProviderStallPolicy.recentlyStalled(
                enabled,
                stalled,
                nowMs = 1_000L + SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS,
            ).isEmpty(),
        )
    }

    @Test
    fun `the meter ignores repeated samples so a wedge cannot be masked`() {
        val meter = ProviderStallMeter(startedAtMs = 1_000L, policy = policy)
        meter.onProgress(seq = 7L, stage = "resolving_metadata", nowMs = 1_000L)

        // The runtime repeats its last delta while the item sits still. If a repeat reset
        // the clock, a wedged provider would look healthy forever.
        meter.onProgress(seq = 7L, stage = "resolving_stream", nowMs = 4_000L)
        assertFalse(meter.shouldAbandon(nowMs = 1_000L + connect - 1))
        assertEquals("resolving_metadata", meter.lastStage)

        // A genuinely new event moves it forward and remembers the stage.
        meter.onProgress(seq = 8L, stage = "resolving_stream", nowMs = 9_000L)
        assertEquals("resolving_stream", meter.lastStage)
        assertEquals(9_000L, meter.lastProgressAt())
        // No byte has arrived, so this attempt is still connecting and the connect window applies.
        val resolving = ProviderStallMeter(startedAtMs = 9_000L, policy = policy)
        resolving.onProgress(seq = 1L, stage = "resolving_stream", nowMs = 9_000L)
        assertFalse(resolving.shouldAbandon(nowMs = 9_000L + connect - 1))
        assertTrue(resolving.shouldAbandon(nowMs = 9_000L + connect))
    }

    /**
     * A source that is still connecting and one that has stopped transferring are the same silence
     * to the meter, and the difference is which window it is judged by.
     */
    @Test
    fun `the connect window governs silence before the first byte, the download window after it`() {
        val connecting = ProviderStallMeter(startedAtMs = 0L, policy = policy)
        connecting.onProgress(seq = 1L, stage = "resolving_stream", nowMs = 0L)
        assertEquals(connect, connecting.idleTimeoutMs())
        assertTrue(connecting.shouldAbandon(nowMs = connect))

        val transferring = ProviderStallMeter(startedAtMs = 0L, policy = policy)
        transferring.onProgress(seq = 1L, stage = "resolving_stream", nowMs = 1_000L)
        transferring.onProgress(
            seq = 2L,
            stage = "downloading",
            bytesReceived = 4_000_000L,
            nowMs = 3_000L,
        )
        assertTrue(transferring.hasTransferred())
        assertEquals(download, transferring.idleTimeoutMs())
        // The connect window has long passed - a download that needs a minute must not be killed by it.
        assertFalse(
            transferring.shouldAbandon(nowMs = 3_000L + connect + 1_000L),
        )
        assertEquals(
            ProviderAbandonReason.STALLED,
            transferring.abandonReason(nowMs = 3_000L + download),
        )
    }

    @Test
    fun `a zero-byte progress event is not a transfer`() {
        // The runtime's resolving stages report `bytes=0/0`: a progress event with no data
        // behind it. Counting that as a transfer would put every connection phase on the download
        // window and undo the connect budget entirely.
        val meter = ProviderStallMeter(startedAtMs = 0L, policy = policy)
        meter.onProgress(seq = 1L, stage = "resolving_stream", bytesReceived = 0L, nowMs = 1_000L)
        assertFalse(meter.hasTransferred())
        assertFalse(meter.shouldAbandon(nowMs = 1_000L + connect - 1))
        assertTrue(meter.shouldAbandon(nowMs = 1_000L + connect))
    }

    @Test
    fun `the meter abandons on the ceiling even when events keep arriving`() {
        val meter = ProviderStallMeter(startedAtMs = 0L, policy = policy)
        // Events keep ticking but no byte is ever reported, so the attempt is not moving
        // data - this is the dribble the ceiling exists to bound.
        meter.onProgress(seq = 1L, stage = "resolving_stream", nowMs = ceiling - 1_000L)
        assertTrue(meter.shouldAbandon(nowMs = ceiling))
        assertEquals(ProviderAbandonReason.CEILING, meter.abandonReason(nowMs = ceiling))
    }

    /**
     * The bug this whole change is about: a download that is visibly arriving is never cut off.
     *
     * Bytes moving for three ceilings' worth of wall time, with a sample a second: the attempt is
     * still transferring and nothing may abandon it, however long the file takes.
     */
    @Test
    fun `an attempt that is moving bytes is not killed by the ceiling`() {
        val meter = ProviderStallMeter(startedAtMs = 0L, policy = policy)
        var bytes = 0L
        var now = 1_000L
        while (now < ceiling * 3) {
            bytes += 400_000L
            meter.onProgress(seq = now, stage = "downloading", bytesReceived = bytes, nowMs = now)
            now += 1_000L
        }
        assertTrue(meter.transferring(nowMs = now))
        assertFalse(meter.shouldAbandon(nowMs = now))
        assertEquals(null, meter.abandonReason(nowMs = now))
    }

    @Test
    fun `a transfer that stops is caught, and reported with the silence since the last byte`() {
        val meter = ProviderStallMeter(startedAtMs = 0L, policy = policy)
        meter.onProgress(seq = 1L, stage = "downloading", bytesReceived = 8_000_000L, nowMs = 9_000L)
        assertTrue(meter.transferring(nowMs = 10_000L))
        assertFalse(meter.shouldAbandon(nowMs = 9_000L + download - 1))
        assertEquals(
            ProviderAbandonReason.STALLED,
            meter.abandonReason(nowMs = 9_000L + download),
        )
        // The reported number is the silence since the last byte, which is what an operator needs.
        assertEquals(download, meter.transferSilenceMillis(nowMs = 9_000L + download))
    }

    @Test
    fun `a repeated byte count is not a transfer`() {
        val meter = ProviderStallMeter(startedAtMs = 0L, policy = policy)
        meter.onProgress(seq = 1L, stage = "downloading", bytesReceived = 5_000L, nowMs = 1_000L)
        // The runtime re-reports its size without moving data; that must not look like
        // progress, or a wedged provider would hold the sweep indefinitely.
        meter.onProgress(seq = 2L, stage = "downloading", bytesReceived = 5_000L, nowMs = 200_000L)
        assertEquals(1_000L, meter.lastTransferAt())
        assertFalse(meter.transferring(nowMs = 200_000L))
    }

    /**
     * The user's setting is what the meter judges by, not a constant.
     *
     * A source that needs longer on this network gets longer, in both windows at once.
     */
    @Test
    fun `a longer policy widens both windows and the ceiling`() {
        val patient = SpotiFLACFetchPolicy(connectTimeoutSeconds = 20, downloadTimeoutSeconds = 300, maxAttempts = 5)
        assertEquals(20_000L, patient.connectTimeoutMs)
        assertEquals(300_000L, patient.downloadTimeoutMs)
        assertEquals(320_000L, patient.attemptCeilingMs)

        val meter = ProviderStallMeter(startedAtMs = 0L, policy = patient)
        meter.onProgress(seq = 1L, stage = "resolving_stream", nowMs = 0L)
        assertFalse(meter.shouldAbandon(nowMs = 19_000L))
        assertTrue(meter.shouldAbandon(nowMs = 20_000L))

        meter.onProgress(seq = 2L, stage = "downloading", bytesReceived = 1_024L, nowMs = 21_000L)
        assertEquals(300_000L, meter.idleTimeoutMs())
        assertFalse(meter.shouldAbandon(nowMs = 21_000L + 299_000L))
    }

    /** Every field is clamped into the range the settings screen offers. */
    @Test
    fun `a policy outside the offered range is clamped`() {
        val clamped =
            SpotiFLACFetchPolicy(
                connectTimeoutSeconds = 0,
                downloadTimeoutSeconds = 5,
                maxAttempts = 99,
            ).normalized()

        assertEquals(SpotiFLACFetchPolicy.MIN_CONNECT_SECONDS, clamped.connectTimeoutSeconds)
        assertEquals(SpotiFLACFetchPolicy.MIN_DOWNLOAD_SECONDS, clamped.downloadTimeoutSeconds)
        assertEquals(SpotiFLACFetchPolicy.MAX_ATTEMPTS, clamped.maxAttempts)

        val huge =
            SpotiFLACFetchPolicy(
                connectTimeoutSeconds = 100_000,
                downloadTimeoutSeconds = 100_000,
                maxAttempts = 0,
            ).normalized()
        assertEquals(SpotiFLACFetchPolicy.MAX_CONNECT_SECONDS, huge.connectTimeoutSeconds)
        assertEquals(SpotiFLACFetchPolicy.MAX_DOWNLOAD_SECONDS, huge.downloadTimeoutSeconds)
        assertEquals(SpotiFLACFetchPolicy.MIN_ATTEMPTS, huge.maxAttempts)
    }

    @Test
    fun `the summary names both windows and the retry count`() {
        // Two lines, and the break is placed rather than left to the row's width: one line on a
        // 360dp screen ended at `... \u00b7 3`, which is the retry count cut off.
        assertEquals("5s connect \u00b7 60s download\n3 tries before the next source", policy.summary())
        assertEquals(
            "20s connect \u00b7 120s download\n1 try before the next source",
            SpotiFLACFetchPolicy(connectTimeoutSeconds = 20, downloadTimeoutSeconds = 120, maxAttempts = 1)
                .summary(),
        )
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

        val noStall = "deezer=no results returned; amazon=no results returned"
        assertTrue(SpotiFLACSweepVerdict.isCatalogueVerdict(noStall))
        assertNotEquals(SpotiFLACSweepOutcome.NO_MATCH, SpotiFLACSweepVerdict.forFailure(stalled))
        assertEquals("qobuz-web", stalled.sourceId)
    }

    /**
     * The measurement survives tracks and launches, and is only ever reported.
     *
     * It no longer widens anything - the windows are the user's - but it is what the log prints next
     * to the policy, so it still has to be a decayed average rather than the last sample.
     */
    @Test
    fun `measured first-byte times are averaged, not overwritten, and restored by id`() {
        val timings = ProviderTimings()
        assertEquals(null, timings.observedMsFor("deezer"))
        timings.record("deezer", 4_000L)
        assertEquals(4_000L, timings.observedMsFor("deezer"))
        // A later, slower attempt moves the average toward it rather than replacing it.
        timings.record("deezer", 8_000L)
        assertEquals(5_000L, timings.snapshot()["deezer"])
        // Case and padding are not a second provider.
        timings.record("  DEEZER ", 5_000L)
        assertEquals(1, timings.snapshot().size)
        // A measurement of zero is nothing to learn from.
        timings.record("amazon", 0L)
        assertTrue(timings.snapshot()["amazon"] == null)

        val restored = ProviderTimings()
        restored.restore(timings.snapshot())
        assertEquals(timings.observedMsFor("deezer"), restored.observedMsFor("deezer"))
        assertEquals(null, restored.observedMsFor("qobuz-web"))
    }

    /**
     * A provider that refused service is left out of the chain, not merely moved down it.
     */
    @Test
    fun `a provider that refused service is not asked again while it cools down`() {
        val now = 1_000_000L
        val cooling = mapOf("deezer" to now - 60_000L)
        assertEquals(
            listOf("amazon", "qobuz-web", "tidal-web"),
            SpotiFLACProviderStallPolicy.orderAvailable(
                candidates = listOf("deezer", "amazon", "qobuz-web", "tidal-web"),
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

    /**
     * An addon that refused is taken out of the walk, so a dead source costs nothing per track.
     *
     * The two Tido profiles are the measured case: every call answered `403 {"error":"Unavailable"}`
     * in under a second, so a walk that kept them spent two round trips on every track to learn what
     * the first one had already said.
     */
    @Test
    fun `an addon that refused is left out of the walk while it cools down`() {
        val now = 1_000_000L
        val tidoTop = "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi"
        val tidoLossless = "tido-tido-tido-dpdns-org-a48bf403dcfc40ae94f668c562d08201:com.yourname.tidal.hifi"
        val qobuz = "qobuz-addon-vercel-app:com.bitchord.qobuz"
        val refused = mapOf(tidoTop to now - 60_000L)

        assertEquals(
            listOf(tidoLossless, qobuz),
            SpotiFLACProviderStallPolicy.availableAddons(
                addons = listOf(tidoTop, tidoLossless, qobuz),
                unavailableAtMs = refused,
                nowMs = now,
            ),
        )
        // Nothing recorded: the list is the user's, untouched.
        assertEquals(
            listOf(tidoTop, tidoLossless, qobuz),
            SpotiFLACProviderStallPolicy.availableAddons(
                addons = listOf(tidoTop, tidoLossless, qobuz),
                unavailableAtMs = emptyMap(),
                nowMs = now,
            ),
        )
    }

    /**
     * No valve: when every addon has refused, none is asked.
     *
     * This is the difference from [SpotiFLACProviderStallPolicy.orderAvailable], and it is the point
     * of the rule. Asking anyway bought a second chance for a runtime provider that had been
     * rate-limited; for an addon it buys the same refusal again on every track, which is exactly the
     * per-song cost this removes. The walk fails fast instead, and the engine falls back.
     */
    @Test
    fun `a walk of refused addons asks none of them`() {
        val now = 1_000_000L
        val refused = mapOf("a" to now - 60_000L, "b" to now - 60_000L)

        assertEquals(
            emptyList<String>(),
            SpotiFLACProviderStallPolicy.availableAddons(
                addons = listOf("a", "b"),
                unavailableAtMs = refused,
                nowMs = now,
            ),
        )
    }

    /** The cooldown lapses: an addon is paused, not blacklisted, and comes back on its own. */
    @Test
    fun `a refused addon comes back once its cooldown has expired`() {
        val now = 1_000_000L
        val expired =
            mapOf("addon" to now - SpotiFLACProviderStallPolicy.UNAVAILABLE_COOLDOWN_MS - 1L)

        assertEquals(
            listOf("addon", "other"),
            SpotiFLACProviderStallPolicy.availableAddons(
                addons = listOf("addon", "other"),
                unavailableAtMs = expired,
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
                rateLimitedAtMs = expired,
                nowMs = now,
            ),
        )
        assertTrue(
            SpotiFLACProviderStallPolicy.RATE_LIMIT_COOLDOWN_MS >
                SpotiFLACProviderStallPolicy.DEMOTION_COOLDOWN_MS,
        )
    }

    /**
     * The safety valve: when every candidate is refusing, asking is still the only way to play.
     */
    @Test
    fun `a chain where every provider is cooling still asks them`() {
        val now = 1_000_000L
        val allCooling = mapOf("deezer" to now, "amazon" to now)
        assertEquals(
            listOf("deezer", "amazon"),
            SpotiFLACProviderStallPolicy.orderAvailable(
                candidates = listOf("deezer", "amazon"),
                rateLimitedAtMs = allCooling,
                nowMs = now,
            ),
        )
    }

    /** The meter reports the resolution cost the log prints, and nothing when no byte moved. */
    @Test
    fun `the meter measures time to first byte, and nothing when no byte moved`() {
        val meter = ProviderStallMeter(startedAtMs = 1_000L, policy = policy)
        assertEquals(null, meter.timeToFirstByteMs())
        // A resolving-stage event with zero bytes is not a first byte.
        meter.onProgress(seq = 1L, stage = "resolving_stream", bytesReceived = 0L, nowMs = 3_000L)
        assertEquals(null, meter.timeToFirstByteMs())
        meter.onProgress(seq = 2L, stage = "downloading", bytesReceived = 512L, nowMs = 6_000L)
        assertEquals(5_000L, meter.timeToFirstByteMs())
    }
}
