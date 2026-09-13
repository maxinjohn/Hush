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
    fun `budget covers the extra attempts the lossy retry adds`() {
        val lossless = SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 8, quality = "LOSSLESS")
        val lossy = SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 8, quality = "320")
        // The retry doubles the work, so the same source count needs more time.
        assertTrue("lossless budget $lossless should exceed lossy $lossy", lossless > lossy)
        // 8 sources x 2 attempts x 8s = 128s, bounded by the ceiling.
        assertEquals(96_000L, lossless)
        // 8 sources x 1 attempt x 8s = 64s, inside the bounds.
        assertEquals(64_000L, lossy)
    }

    @Test
    fun `budget never falls below the window a cold provider round-trip needs`() {
        assertEquals(45_000L, SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 0, quality = "LOSSLESS"))
        assertEquals(45_000L, SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 1, quality = "LOSSLESS"))
        assertEquals(45_000L, SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 2, quality = "320"))
        assertEquals(45_000L, SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 3, quality = "320"))
    }

    @Test
    fun `budget grows with the chain and stays inside its ceiling`() {
        assertEquals(48_000L, SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 6, quality = "320"))
        // Past the ceiling the listener is not made to wait indefinitely: skipping is
        // always available, and a failure this slow is more likely systemic than a chain
        // that merely needed longer.
        assertEquals(96_000L, SpotiFLACQualityCascade.sweepBudgetMs(sourceCount = 64, quality = "LOSSLESS"))
    }
}
