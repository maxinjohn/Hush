/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.spotiflac.SpotiFLACQualityCascade.QualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotiFLACQualityCascadeTest {
    /** The real manifests, so the test pins behaviour against the shipped packages. */
    private val ytMusic =
        listOf(QualityOption(id = "best", label = "Best Audio")) to "low_res"
    private val deezer = listOf(QualityOption(id = "flac", label = "FLAC")) to "lossless"
    private val tidal =
        listOf(
            QualityOption(id = "DOLBY_ATMOS", kind = "spatial", label = "Dolby Atmos"),
            QualityOption(id = "HI_RES_LOSSLESS", kind = "lossless", label = "HiRes FLAC"),
            QualityOption(id = "HIGH", kind = "lossy", label = "High"),
            QualityOption(id = "LOW", kind = "lossy", label = "Low"),
        ) to "lossless"
    private val soundcloud =
        listOf(QualityOption(id = "mp3_128", label = "MP3 128kbps")) to "low_res"

    @Test
    fun `lossless qualities are recognised in any casing`() {
        assertTrue(SpotiFLACQualityCascade.isLossless("LOSSLESS"))
        assertTrue(SpotiFLACQualityCascade.isLossless("lossless"))
        assertTrue(SpotiFLACQualityCascade.isLossless("  Hi_Res_Lossless "))
        assertTrue(SpotiFLACQualityCascade.isLossless("flac"))
        assertFalse(SpotiFLACQualityCascade.isLossless("320"))
        assertFalse(SpotiFLACQualityCascade.isLossless("HIGH"))
    }

    @Test
    fun `only a quality limitation counts, not a catalogue answer`() {
        val actual =
            "ytmusic-spotiflac@LOSSLESS=All providers failed. Last error: provider " +
                "ytmusic-spotiflac has no compatible lossless quality for \"LOSSLESS\""
        assertTrue(SpotiFLACQualityCascade.isQualityLimited(actual))
        // A catalogue miss is a real answer, not an unasked question.
        assertFalse(SpotiFLACQualityCascade.isQualityLimited("deezer@LOSSLESS=quality_unavailable: 404"))
        assertFalse(SpotiFLACQualityCascade.isQualityLimited("no results returned for source deezer"))
        assertFalse(SpotiFLACQualityCascade.isQualityLimited(null))
    }

    @Test
    fun `a declared kind always wins over the id and label`() {
        assertEquals(
            "lossy",
            SpotiFLACQualityCascade.kindOf(
                QualityOption(id = "flac-ish", kind = "lossy", label = "FLAC"),
                downloadFallbackTier = "lossless",
            ),
        )
    }

    @Test
    fun `kind falls back to the id and label heuristics`() {
        assertEquals("lossless", SpotiFLACQualityCascade.kindOf(QualityOption("flac"), null))
        assertEquals("lossless", SpotiFLACQualityCascade.kindOf(QualityOption("HI_RES"), null))
        assertEquals(
            "lossless",
            SpotiFLACQualityCascade.kindOf(QualityOption("x", label = "24-Bit Max"), null),
        )
        assertEquals("spatial", SpotiFLACQualityCascade.kindOf(QualityOption("ac4"), null))
        assertEquals(
            "spatial",
            SpotiFLACQualityCascade.kindOf(QualityOption("x", label = "Dolby Atmos"), null),
        )
        // A label that refuses Atmos is not an Atmos option: Tido's hi-res rung is "Max (Hi-Res
        // 24-bit up to 192 kHz, no Atmos)", and reading the word without its negation classified
        // the one rung a lossless request should ask for as the one kind it can never use.
        assertEquals(
            "lossless",
            SpotiFLACQualityCascade.kindOf(
                QualityOption("HIRES", label = "Max (Hi-Res 24-bit up to 192 kHz, no Atmos)"),
                null,
            ),
        )
        assertEquals(
            "lossless",
            SpotiFLACQualityCascade.kindOf(QualityOption("x", label = "24-Bit, without Dolby"), null),
        )
        assertEquals("lossy", SpotiFLACQualityCascade.kindOf(QualityOption("HIGH"), null))
        assertEquals("lossy", SpotiFLACQualityCascade.kindOf(QualityOption("mp3_128"), null))
        assertEquals(
            "lossy",
            SpotiFLACQualityCascade.kindOf(QualityOption("x", label = "Opus 320kbps"), null),
        )
        assertEquals("", SpotiFLACQualityCascade.kindOf(QualityOption("320"), null))
    }

    @Test
    fun `a generic best token defers to the download fallback tier`() {
        val best = QualityOption(id = "best", label = "Best Audio")
        assertEquals("lossy", SpotiFLACQualityCascade.kindOf(best, "low_res"))
        assertEquals("lossless", SpotiFLACQualityCascade.kindOf(best, "lossless"))
        assertEquals("lossless", SpotiFLACQualityCascade.kindOf(best, "hi_res"))
        // Without a tier a generic token says nothing either way.
        assertEquals("", SpotiFLACQualityCascade.kindOf(best, null))
    }

    @Test
    fun `the description is never used to classify an option`() {
        // YT Music's own option says "Opus/M4A audio direct from YouTube" - reading the
        // description would call a lossy option lossy only by accident, and would
        // misclassify any source whose description mentions a fallback format.
        val option =
            QualityOption(id = "best", label = "Best Audio")
        assertEquals("lossy", SpotiFLACQualityCascade.kindOf(option, "low_res"))
    }

    @Test
    fun `YT Music retries at its own declared token, not a fixed one`() {
        // The bug this pins: retrying "LOSSLESS" as "320" is rejected by the runtime the
        // same way, because "320" is not in this manifest - it maps to the lossless kind
        // and fails with an identical message.
        assertEquals(
            "best",
            SpotiFLACQualityCascade.lossyRetryToken(ytMusic.first, ytMusic.second),
        )
    }

    @Test
    fun `a source with a real lossy option retries at it, best declared first`() {
        assertEquals(
            "HIGH",
            SpotiFLACQualityCascade.lossyRetryToken(tidal.first, tidal.second),
        )
        assertEquals(
            "mp3_128",
            SpotiFLACQualityCascade.lossyRetryToken(soundcloud.first, soundcloud.second),
        )
    }

    @Test
    fun `a lossless-only or undeclared source has no second question to ask`() {
        assertNull(SpotiFLACQualityCascade.lossyRetryToken(deezer.first, deezer.second))
        // A legacy manifest with no options passes the request through untouched, so
        // re-asking a different token is meaningless.
        assertNull(SpotiFLACQualityCascade.lossyRetryToken(emptyList(), null))
    }

    @Test
    fun `a lossless request makes two attempts per source, a lossy one makes one`() {
        assertEquals(2, SpotiFLACQualityCascade.attemptsPerSource("LOSSLESS"))
        assertEquals(1, SpotiFLACQualityCascade.attemptsPerSource("320"))
    }

    @Test
    fun `an unplayable answer is a reason to retry the source, not a catalogue miss`() {
        // Amazon answers a lossless request with Dolby Digital Plus or Atmos on some tracks. That
        // is silent on a device with no AC-3/AC-4 output path, so it must not be remembered as
        // "this source does not have the track" - it is retried at the source's own lossy option,
        // exactly like a source that cannot deliver the requested quality.
        val unplayable =
            "provider 'amazon' ${SpotiFLACQualityCascade.UNPLAYABLE_FORMAT_MARKER} " +
                "(Dolby ac4; requested quality was LOSSLESS)"
        assertTrue(SpotiFLACQualityCascade.isUnplayableFormat(unplayable))
        // The two are distinct questions, and neither may answer for the other: a plain catalogue
        // miss is neither, and a quality limit is not an unplayable answer.
        assertFalse(SpotiFLACQualityCascade.isQualityLimited(unplayable))
        assertFalse(
            SpotiFLACQualityCascade.isUnplayableFormat(
                "provider 'deezer' has no compatible lossless quality for \"LOSSLESS\"",
            ),
        )
        assertFalse(SpotiFLACQualityCascade.isUnplayableFormat("no results"))
        assertFalse(SpotiFLACQualityCascade.isUnplayableFormat(null))
    }

    @Test
    fun `budget covers the extra attempts the lossy retry adds`() {
        // A retry count of one, so what these two numbers differ by is the quality retry alone -
        // which is only asked of a source that could not deliver the selected quality.
        val lossless =
            SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = 8,
                quality = "LOSSLESS",
                perAttemptMs = 8_000L,
                maxAttempts = 1,
            )
        val lossy =
            SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = 8,
                quality = "320",
                perAttemptMs = 8_000L,
                maxAttempts = 1,
            )
        // The quality retry doubles the work, so the same source count needs more time.
        assertTrue("lossless budget $lossless should exceed lossy $lossy", lossless > lossy)
        // 8 sources x 2 attempts x 8s = 128s, inside the bounds.
        assertEquals(128_000L, lossless)
        // 8 sources x 1 attempt x 8s = 64s, inside the bounds.
        assertEquals(64_000L, lossy)
    }

    /**
     * The user's retry setting is budgeted for, and never as the sum of the two kinds of retry.
     *
     * A source may be asked again after a transient failure *or* at its own lossy option, and those
     * are two different questions put to it - budgeting both would pay twice for work a source does
     * only once, while budgetting neither is how a walk's clock ends before a source has used the
     * attempts the user gave it.
     */
    @Test
    fun `budget pays for the retry setting, and takes the larger of the two retry kinds`() {
        val withRetries =
            SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = 2,
                quality = "LOSSLESS",
                perAttemptMs = 10_000L,
                maxAttempts = 3,
            )
        // 2 sources x 3 attempts x 10s - the larger of the quality retry (2) and the setting (3).
        assertEquals(60_000L, withRetries)

        val withoutRetries =
            SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = 2,
                quality = "LOSSLESS",
                perAttemptMs = 10_000L,
                maxAttempts = 1,
            )
        // 2 sources x 2 attempts x 10s = 40s, which the floor raises to the shortest window a cold
        // provider round-trip needs - still the quality retry rather than one attempt.
        assertEquals(45_000L, withoutRetries)
        assertTrue(withRetries > withoutRetries)
    }

    @Test
    fun `a module in the walk costs its search and its stream, not one attempt`() {
        // The half that used to be left out of this sum. A module spends a search budget and then a
        // stream budget per track - `SpotiFLACModuleHost.SEARCH_BUDGET_MS` and `STREAM_BUDGET_MS`, both
        // 30s - so budgeting it as one provider attempt (8s here, 25s on the reporting device) made
        // the sweep's clock run out while the module was still inside its own budget, and the entries
        // the user had ranked below it were never asked.
        val nativesOnly =
            SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = 3,
                quality = "LOSSLESS",
                perAttemptMs = 25_000L,
                maxAttempts = 1,
            )
        val withModules =
            SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = 3,
                quality = "LOSSLESS",
                perAttemptMs = 25_000L,
                addonCount = 1,
                maxAttempts = 1,
            )

        // 3 native sources x 2 attempts x 25s.
        assertEquals(150_000L, nativesOnly)
        // + one module x (30s search + 30s stream).
        assertEquals(210_000L, withModules)
    }

    @Test
    fun `a walk larger than the lid is still bounded by it`() {
        // The lid is the listener's bound, not the walk's: a walk whose own allowances exceed it is
        // cut off there, and the sweep says which entries it never asked (see the bridge's walk
        // cancellation line). Sized honestly rather than pretended away - the number below is the
        // most a listener can be held, and it is the same number a live transfer may already reach.
        assertEquals(
            SpotiFLACSweepBudget.MAX_TOTAL_MS,
            SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = 10,
                quality = "LOSSLESS",
                perAttemptMs = 25_000L,
                addonCount = 3,
                maxAttempts = 2,
            ),
        )
    }

    @Test
    fun `budget and the extended clock share one lid`() {
        // They used to be two different numbers - a 96s plan and a clock allowed to reach 240s - so a
        // large install was always planned for less time than its own entries could legitimately
        // spend, and the sources behind the cut were skipped on every track. One bound, read the same
        // way by both.
        assertEquals(
            SpotiFLACSweepBudget.MAX_TOTAL_MS,
            SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 64, quality = "LOSSLESS"),
        )
        assertEquals(
            SpotiFLACSweepBudget.MAX_TOTAL_MS,
            SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 64, quality = "LOSSLESS", addonCount = 12),
        )
    }

    @Test
    fun `budget never falls below the window a cold provider round-trip needs`() {
        assertEquals(
            45_000L,
            SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 0, quality = "LOSSLESS", perAttemptMs = 8_000L, maxAttempts = 1),
        )
        assertEquals(
            45_000L,
            SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 1, quality = "LOSSLESS", perAttemptMs = 8_000L, maxAttempts = 1),
        )
        assertEquals(
            45_000L,
            SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 2, quality = "320", perAttemptMs = 8_000L, maxAttempts = 1),
        )
        assertEquals(
            45_000L,
            SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 3, quality = "320", perAttemptMs = 8_000L, maxAttempts = 1),
        )
    }

    @Test
    fun `budget grows with the chain and stays inside its ceiling`() {
        assertEquals(
            48_000L,
            SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = 6,
                quality = "320",
                perAttemptMs = 8_000L,
                maxAttempts = 1,
            ),
        )
        // Past the ceiling the listener is not made to wait indefinitely: skipping is
        // always available, and a failure this slow is more likely systemic than a chain
        // that merely needed longer. The ceiling is the clock's own absolute lid, so the two
        // cannot disagree about how long a walk may take.
        assertEquals(
            SpotiFLACSweepBudget.MAX_TOTAL_MS,
            SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 64, quality = "LOSSLESS"),
        )
    }
}
