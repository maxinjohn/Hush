/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_800_000_000_000L
private const val HOUR = 60L * 60L * 1000L

class SpotiFLACMissMemoPolicyTest {
    private fun context(
        sources: List<String> = listOf("deezer", "tidal-web"),
        quality: String = "lossless",
        runtime: Boolean = true,
        usable: List<String> = sources,
    ) = SpotiFLACMissPolicy.contextFingerprint(
        enabledSourceIds = sources,
        qualityBucket = quality,
        runtimeAvailable = runtime,
        usableSourceIds = usable,
    )

    /**
     * The regression behind "clicking a song just plays/pauses the current one": a miss
     * recorded while the gateway sources were waiting for verification kept being trusted
     * after they were verified, so the sweep was skipped and the track could never resolve.
     * Verifying a source must therefore change the fingerprint.
     */
    @Test
    fun `verifying a source invalidates a miss recorded before it`() {
        val beforeVerification = context(usable = emptyList())
        val afterVerification = context(usable = listOf("deezer", "tidal-web"))
        assertNotEquals(beforeVerification, afterVerification)
        assertFalse(
            SpotiFLACMissPolicy.isStillValid(
                recordedAtMs = NOW,
                recordedContext = beforeVerification,
                attemptContext = afterVerification,
                nowMs = NOW + 1000L,
                recordedRetentionMs = SpotiFLACMissPolicy.NO_MATCH_RETENTION_MS,
            ),
        )
    }

    @Test
    fun `the usable set is order independent`() {
        assertEquals(
            context(usable = listOf("tidal-web", "deezer")),
            context(usable = listOf("deezer", "tidal-web")),
        )
    }

    // ------------------------------------------------------------ validity

    @Test
    fun `a fresh miss under the same context is reused`() {
        assertTrue(
            SpotiFLACMissPolicy.isStillValid(
                recordedAtMs = NOW,
                recordedContext = context(),
                attemptContext = context(),
                nowMs = NOW + 5L * 60L * 1000L,
                recordedRetentionMs = SpotiFLACMissPolicy.NO_MATCH_RETENTION_MS,
            ),
        )
    }

    @Test
    fun `a definitive miss stops being reused after its retention`() {
        val retention = SpotiFLACMissPolicy.NO_MATCH_RETENTION_MS
        assertTrue(validAt(NOW + retention, retention))
        assertFalse(validAt(NOW + retention + 1, retention))
    }

    /**
     * The reason the two retentions exist: a sweep that timed out proves nothing about
     * the providers, so it must not remove a track from SpotiFLAC for six hours.
     */
    @Test
    fun `an unfinished sweep is forgotten far sooner than a definitive miss`() {
        val unfinished = SpotiFLACMissPolicy.retentionFor(SpotiFLACMissPolicy.REASON_SWEEP_UNFINISHED)
        val noMatch = SpotiFLACMissPolicy.retentionFor(SpotiFLACMissPolicy.REASON_NO_MATCH)
        assertTrue(unfinished < noMatch)
        assertFalse(validAt(NOW + HOUR, unfinished))
        assertTrue(validAt(NOW + HOUR, noMatch))
    }

    @Test
    fun `an unknown reason is treated as an unfinished sweep`() {
        assertEquals(
            SpotiFLACMissPolicy.UNFINISHED_SWEEP_RETENTION_MS,
            SpotiFLACMissPolicy.retentionFor("something-new"),
        )
    }

    @Test
    fun `a miss recorded by a clock that went backwards is not trusted`() {
        assertFalse(
            SpotiFLACMissPolicy.isStillValid(
                recordedAtMs = NOW + HOUR,
                recordedContext = context(),
                attemptContext = context(),
                nowMs = NOW,
                recordedRetentionMs = SpotiFLACMissPolicy.NO_MATCH_RETENTION_MS,
            ),
        )
    }

    // ------------------------------------------------------------- context

    @Test
    fun `adding a provider invalidates a remembered miss`() {
        assertNotEquals(context(listOf("deezer")), context(listOf("deezer", "qobuz-web")))
        assertFalse(
            SpotiFLACMissPolicy.isStillValid(
                recordedAtMs = NOW,
                recordedContext = context(listOf("deezer")),
                attemptContext = context(listOf("deezer", "qobuz-web")),
                nowMs = NOW,
                recordedRetentionMs = SpotiFLACMissPolicy.NO_MATCH_RETENTION_MS,
            ),
        )
    }

    @Test
    fun `the order providers happen to be listed in does not matter`() {
        assertEquals(context(listOf("deezer", "tidal-web")), context(listOf("tidal-web", "deezer")))
        assertEquals(
            context(listOf("Deezer ", "TIDAL-WEB", "deezer")),
            context(listOf("deezer", "tidal-web")),
        )
    }

    @Test
    fun `changing the quality bucket invalidates a remembered miss`() {
        assertNotEquals(context(quality = "lossless"), context(quality = "hires"))
    }

    @Test
    fun `gaining a runtime invalidates a remembered miss`() {
        // Gaining the engine is exactly the event that can make a previously failed resolve
        // succeed, so an answer recorded without it must not survive one. The relay session used to
        // be an input here too; it is gone, because it can serve no source (see
        // SpotiFLACInstallIdentity) and a value that can never change cannot distinguish anything.
        assertNotEquals(context(runtime = false), context(runtime = true))
    }

    @Test
    fun `blank source ids do not change the fingerprint`() {
        assertEquals(context(listOf("deezer")), context(listOf("deezer", "", "   ")))
    }

    // ------------------------------------------------------------ eviction

    private fun entry(
        mediaId: String,
        recordedAtMs: Long = NOW,
        fingerprint: String = context(),
        retentionMs: Long = SpotiFLACMissPolicy.NO_MATCH_RETENTION_MS,
    ) = SpotiFLACMissEntry(
        mediaId = mediaId,
        recordedAtMs = recordedAtMs,
        context = fingerprint,
        retentionMs = retentionMs,
        reason = SpotiFLACMissPolicy.REASON_NO_MATCH,
    )

    @Test
    fun `nothing is dropped while the memo is within its bound`() {
        val entries = (1..10).map { entry("id$it") }
        assertTrue(
            SpotiFLACMissPolicy
                .entriesToDrop(entries, attemptContext = context(), nowMs = NOW, maxEntries = 10)
                .isEmpty(),
        )
    }

    @Test
    fun `entries that can no longer be consulted go before usable ones`() {
        // One entry was recorded under a context that no longer applies; the rest are
        // still doing their job, so only the spent one may be sacrificed.
        val spent = entry("stale", fingerprint = context(sources = listOf("deezer")))
        val kept = (1..3).map { entry("id$it", recordedAtMs = NOW - it * HOUR) }
        val drop =
            SpotiFLACMissPolicy.entriesToDrop(
                entries = listOf(spent) + kept,
                attemptContext = context(),
                nowMs = NOW,
                maxEntries = 3,
            )
        assertEquals(listOf("stale"), drop)
    }

    @Test
    fun `when everything is usable the oldest entries are dropped`() {
        val oldest = entry("oldest", recordedAtMs = NOW - 3 * HOUR)
        val middle = entry("middle", recordedAtMs = NOW - 2 * HOUR)
        val newest = entry("newest", recordedAtMs = NOW - HOUR)
        val drop =
            SpotiFLACMissPolicy.entriesToDrop(
                entries = listOf(newest, oldest, middle),
                attemptContext = context(),
                nowMs = NOW,
                maxEntries = 2,
            )
        assertEquals(listOf("oldest"), drop)
    }

    private fun validAt(
        nowMs: Long,
        retentionMs: Long,
    ): Boolean =
        SpotiFLACMissPolicy.isStillValid(
            recordedAtMs = NOW,
            recordedContext = context(),
            attemptContext = context(),
            nowMs = nowMs,
            recordedRetentionMs = retentionMs,
        )
}
