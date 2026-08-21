/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.audiosource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourceQualityRankingTest {
    private fun candidate(
        providerId: String = "p1",
        trackId: String = "1",
        containerFormat: String? = null,
        bitDepth: Int? = null,
        sampleRateHz: Int? = null,
        bitrateKbps: Int? = null,
        streamUrl: String? = null,
    ): AudioCandidate =
        AudioCandidate(
            providerId = providerId,
            providerLabel = providerId,
            trackId = trackId,
            title = "Song",
            artists = listOf("Artist"),
            containerFormat = containerFormat,
            bitDepth = bitDepth,
            sampleRateHz = sampleRateHz,
            bitrateKbps = bitrateKbps,
            streamUrl = streamUrl,
        )

    @Test
    fun prefersHiResOver16BitLosslessForMaximum() {
        val hires = candidate(trackId = "hires", containerFormat = "flac", bitDepth = 24, sampleRateHz = 96000)
        val cd = candidate(trackId = "cd", containerFormat = "flac", bitDepth = 16)

        val best = AudioSourceQualityRanking.best(listOf(cd, hires), AudioSourceQuality.MAXIMUM) { 0 }

        assertEquals("hires", best?.trackId)
    }

    @Test
    fun prefersLosslessOverLossyForLossless() {
        val flac = candidate(trackId = "flac", containerFormat = "flac", bitDepth = 16)
        val mp3 = candidate(trackId = "mp3", containerFormat = "mpeg", bitrateKbps = 320)

        val best = AudioSourceQualityRanking.best(listOf(mp3, flac), AudioSourceQuality.LOSSLESS) { 0 }

        assertEquals("flac", best?.trackId)
    }

    @Test
    fun prefersHigherBitrateLossyForNormalWhenNoLosslessAvailable() {
        val high = candidate(trackId = "high", containerFormat = "mpeg", bitrateKbps = 320)
        val low = candidate(trackId = "low", containerFormat = "mpeg", bitrateKbps = 128)

        val best = AudioSourceQualityRanking.best(listOf(low, high), AudioSourceQuality.NORMAL) { 0 }

        assertEquals("high", best?.trackId)
    }

    @Test
    fun providerPriorityBreaksTiesBetweenEqualQualityCandidates() {
        val first = candidate(providerId = "qobuz", trackId = "a", containerFormat = "flac", bitDepth = 16)
        val second = candidate(providerId = "tidal", trackId = "b", containerFormat = "flac", bitDepth = 16)

        val priority: (String) -> Int = { id -> if (id == "qobuz") 3 else 2 }

        val best = AudioSourceQualityRanking.best(listOf(second, first), AudioSourceQuality.LOSSLESS, priority)

        assertEquals("qobuz", best?.providerId)
    }

    @Test
    fun returnsNullWhenNoCandidates() {
        val best = AudioSourceQualityRanking.best(emptyList(), AudioSourceQuality.LOSSLESS) { 0 }

        assertNull(best)
    }

    @Test
    fun existingStreamUrlSurvivesRankingOrder() {
        val direct = candidate(trackId = "direct", streamUrl = "https://cdn.example/stream", containerFormat = "flac", bitDepth = 24, sampleRateHz = 96000)
        val ranked = AudioSourceQualityRanking.rank(listOf(direct), AudioSourceQuality.MAXIMUM) { 0 }

        assertTrue(ranked.isNotEmpty())
        assertEquals("https://cdn.example/stream", ranked.first().streamUrl)
    }
}