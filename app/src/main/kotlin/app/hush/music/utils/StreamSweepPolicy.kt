/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.utils

/**
 * Why a client sweep ended without a stream, and whether asking again can help.
 *
 * The distinction that matters is *who refused*: a refusal aimed at the address is transient, while a
 * client that simply had no answer for the track is not something a second sweep changes. Without the
 * distinction every dead sweep looked the same to the caller - the track was abandoned and the queue
 * moved on, which is exactly the "songs skip for no reason" symptom when the address is being
 * challenged (measured on the reporting device: one sweep, three families answering `LOGIN_REQUIRED`,
 * four others producing nothing, and an audible skip).
 */
enum class StreamSweepOutcome {
    /** A client produced a playable candidate. */
    RESOLVED,

    /**
     * The sweep was refused by the address (sign-in / "not a bot"), not by the track.
     *
     * The same request frequently works moments later - measured on the reporting device, where the
     * identical probe returned a fully playable response shortly after being challenged.
     */
    ADDRESS_CHALLENGED,

    /** Nobody was refused by the address: asking again would ask the same question. */
    UNANSWERED,
}

/** Thrown when a sweep failed because the address carrying it was refused. */
class StreamAddressChallengedException(
    val videoId: String,
    val clientFamilies: Set<String>,
    /** What would have been reported had the retry not been worth making. */
    val underlying: Throwable,
) : Exception("Stream resolution for $videoId was refused by the address (${clientFamilies.joinToString()})", underlying)

object StreamSweepPolicy {

    /**
     * How many extra sweeps an address-refused track gets.
     *
     * Two, because the addresses that get challenged here are challenged intermittently - the observed
     * failing track failed twice and other tracks served immediately before and after it. More would
     * only lengthen the wait on a track that is genuinely unavailable.
     */
    const val MAX_ADDRESS_RETRIES = 2

    private val retryDelaysMs = longArrayOf(2_000L, 6_000L)

    /**
     * The delay before retry [attempt] (1-based), or `null` once the retries are spent.
     *
     * Deliberately shorter than a sweep: the point is to catch a challenge that lifts quickly, not to
     * wait out a rate limit.
     */
    fun retryDelayMs(attempt: Int): Long? {
        if (attempt < 1 || attempt > MAX_ADDRESS_RETRIES) return null
        return retryDelaysMs[attempt - 1]
    }

    fun outcome(
        gatedFamilies: Set<String>,
        producedCandidate: Boolean = false,
    ): StreamSweepOutcome =
        when {
            producedCandidate -> StreamSweepOutcome.RESOLVED
            gatedFamilies.isNotEmpty() -> StreamSweepOutcome.ADDRESS_CHALLENGED
            else -> StreamSweepOutcome.UNANSWERED
        }
}
