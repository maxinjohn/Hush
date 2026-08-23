package app.hush.music.audiosource

object AudioSourceQualityRanking {

    fun best(
        candidates: List<AudioCandidate>,
        quality: AudioSourceQuality,
        providerPriority: (String) -> Int,
    ): AudioCandidate? {
        return rank(candidates, quality, providerPriority).firstOrNull()
    }

    fun rank(
        candidates: List<AudioCandidate>,
        quality: AudioSourceQuality,
        providerPriority: (String) -> Int,
    ): List<AudioCandidate> {
        if (candidates.isEmpty()) return emptyList()

        return candidates.sortedWith(compareByDescending<AudioCandidate> { candidate ->
            qualityTierScore(candidate, quality)
        }.thenByDescending {
            it.bitDepth ?: 0
        }.thenByDescending {
            it.sampleRateHz ?: 0
        }.thenByDescending {
            it.bitrateKbps ?: 0
        }.thenByDescending {
            providerPriority(it.providerId)
        })
    }

    private fun qualityTierScore(candidate: AudioCandidate, target: AudioSourceQuality): Int {
        val isLossless = candidate.containerFormat == "flac" || candidate.containerFormat == "alac"
        val isHiRes = isLossless && (candidate.bitDepth ?: 16) > 16

        return when (target) {
            AudioSourceQuality.MAXIMUM -> when {
                isHiRes -> 3
                isLossless -> 2
                else -> 1
            }
            AudioSourceQuality.LOSSLESS -> when {
                isLossless -> 2
                else -> 1
            }
            AudioSourceQuality.NORMAL -> 1
        }
    }
}
