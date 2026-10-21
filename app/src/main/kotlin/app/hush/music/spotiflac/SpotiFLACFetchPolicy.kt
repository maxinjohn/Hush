/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale

/**
 * How long one source is given, and how many times it is asked.
 *
 * These were constants with a lot of reasoning behind each number, and the reasoning was right for
 * the app's own network but wrong as a promise to a listener: a provider that needs 8s on a slow
 * link was abandoned at 6s, while a provider that had wedged held the player at 0:00 for as long as
 * the sweep allowed. Neither outcome is a setting anyone chose. So the two windows are the user's,
 * per source, with the defaults the behaviour this app wants:
 *
 *  - [connectTimeoutSeconds] - time to produce the first byte. Silence longer than this means the
 *    source did not connect, and the walk moves on (or asks it again, see [maxAttempts]).
 *  - [downloadTimeoutSeconds] - silence *after* bytes have started. Once audio is arriving the
 *    question is no longer "will it answer" but "has the transfer stopped", so it gets a much
 *    longer window: a whole lossless file can legitimately take a minute on a slow link, and cutting
 *    that off at 80% was throwing away a download that was about to finish.
 *  - [maxAttempts] - how many times the same source is asked before the walk moves to the next one.
 *    Only transient failures are repeated (a stall, a dropped socket, a timeout); a catalogue miss
 *    or a quality refusal is an answer, and asking it again only spends the listener's time.
 *
 * The absolute bound for one attempt is [attemptCeilingMs] - the two windows added together - so an
 * attempt can always spend its connect window and then its whole download window.
 */
@Serializable
data class SpotiFLACFetchPolicy(
    val connectTimeoutSeconds: Int = DEFAULT_CONNECT_SECONDS,
    val downloadTimeoutSeconds: Int = DEFAULT_DOWNLOAD_SECONDS,
    val maxAttempts: Int = DEFAULT_ATTEMPTS,
) {
    /** How long a source may stay silent before its first byte. */
    val connectTimeoutMs: Long get() = connectTimeoutSeconds.coerceAtLeast(1).toLong() * 1_000L

    /** How long a transfer may stay silent once bytes have started. */
    val downloadTimeoutMs: Long get() = downloadTimeoutSeconds.coerceAtLeast(1).toLong() * 1_000L

    /**
     * The longest one attempt may run.
     *
     * Sum rather than max: an attempt may spend its connect window and then its whole download
     * window, and a bound of only the larger one would cut a slow connect followed by a normal
     * transfer. Measured from the last byte rather than from the start (see [ProviderStallMeter]),
     * so an attempt that is still transferring is never killed by it.
     */
    val attemptCeilingMs: Long get() = connectTimeoutMs + downloadTimeoutMs

    /** The policy with every field inside the range the settings screen offers. */
    fun normalized(): SpotiFLACFetchPolicy =
        SpotiFLACFetchPolicy(
            connectTimeoutSeconds = connectTimeoutSeconds.coerceIn(MIN_CONNECT_SECONDS, MAX_CONNECT_SECONDS),
            downloadTimeoutSeconds =
                downloadTimeoutSeconds.coerceIn(MIN_DOWNLOAD_SECONDS, MAX_DOWNLOAD_SECONDS),
            maxAttempts = maxAttempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS),
        )

    /** True when this is the shipped policy, which is the one thing the settings row can say quietly. */
    fun isDefault(): Boolean = normalized() == DEFAULT

    /**
     * The two lines the Audio Sources row shows for this policy.
     *
     * Two lines rather than one because of what a single line did on a 360dp screen: it ended at
     * `5s connect \u00b7 60s download \u00b7 3...`, which cuts off the retry count - the one number the line
     * exists to state - so the break is placed rather than left to the row's width.
     */
    fun summary(): String =
        normalized().let { policy ->
            buildString {
                append(policy.connectTimeoutSeconds).append("s connect")
                append(" \u00b7 ")
                append(policy.downloadTimeoutSeconds).append("s download")
                append('\n')
                append(policy.maxAttempts)
                append(if (policy.maxAttempts == 1) " try" else " tries")
                append(" before the next source")
            }
        }

    companion object {
        const val DEFAULT_CONNECT_SECONDS = 5
        const val DEFAULT_DOWNLOAD_SECONDS = 60
        const val DEFAULT_ATTEMPTS = 3

        /**
         * Lower bound of the connect window.
         *
         * Two seconds rather than one: a single TLS handshake and a redirect can take that on a
         * mobile link, and a window shorter than that would abandon every source on a bad network
         * before any of them had been given a chance.
         */
        const val MIN_CONNECT_SECONDS = 2
        const val MAX_CONNECT_SECONDS = 120

        /**
         * Lower bound of the download window.
         *
         * Ten seconds, because anything shorter is a "give up mid-file" setting in disguise: a
         * lossless file is tens of megabytes and a pause of a few seconds on a congested cell is
         * normal. The upper bound is a slow but genuinely live transfer, not a wedge.
         */
        const val MIN_DOWNLOAD_SECONDS = 10
        const val MAX_DOWNLOAD_SECONDS = 600

        const val MIN_ATTEMPTS = 1

        /** More than five attempts on one source is a wait no replay is worth. */
        const val MAX_ATTEMPTS = 5

        val DEFAULT = SpotiFLACFetchPolicy()
    }
}

/**
 * The per-source policies as they are stored in one preference value.
 *
 * Kept as JSON in a single string preference rather than one key per source: the source list is
 * dynamic - a repository can add or remove entries - and keys of that shape accumulate entries for
 * sources that no longer exist. Decoding ignores ids the registry no longer knows, and the map is
 * written sorted so the stored value is stable between saves.
 */
object SpotiFLACFetchPolicies {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private fun key(sourceId: String): String = sourceId.trim().lowercase(Locale.US)

    /** Every stored policy, keyed by lowercased source id. A malformed value reads as none. */
    fun decode(raw: String?): Map<String, SpotiFLACFetchPolicy> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        val parsed =
            runCatching { json.decodeFromString<Map<String, SpotiFLACFetchPolicy>>(text) }
                .getOrElse { return emptyMap() }
        return parsed
            .mapKeys { (id, _) -> key(id) }
            .mapValues { (_, policy) -> policy.normalized() }
            .filterKeys { it.isNotBlank() }
    }

    /**
     * The stored map as the value to persist, in a stable order.
     *
     * Typed as a plain `Map` on purpose: the sorter's own type is a `SortedMap`, and the serialiser
     * is chosen from the static type - a `SortedMap` has no serialiser, and asking for one is a
     * failure at the first save rather than at the type.
     */
    fun encode(policies: Map<String, SpotiFLACFetchPolicy>): String {
        val sorted: Map<String, SpotiFLACFetchPolicy> =
            policies
                .filterKeys { it.isNotBlank() }
                .mapKeys { (id, _) -> key(id) }
                .mapValues { (_, policy) -> policy.normalized() }
                .toSortedMap()
        return json.encodeToString(sorted)
    }

    /** The policy for [sourceId], or the shipped default when nothing was saved for it. */
    fun policyFor(
        raw: String?,
        sourceId: String,
    ): SpotiFLACFetchPolicy = decode(raw)[key(sourceId)] ?: SpotiFLACFetchPolicy.DEFAULT

    /**
     * [raw] with [sourceId] set to [policy], or with the entry removed when [policy] is null.
     *
     * A policy equal to the default is removed rather than stored, so the value does not grow a row
     * for every source the user opened and saved unchanged.
     */
    fun updated(
        raw: String?,
        sourceId: String,
        policy: SpotiFLACFetchPolicy?,
    ): String {
        val policies = decode(raw).toMutableMap()
        val id = key(sourceId)
        val normalized = policy?.normalized()
        if (normalized == null || normalized == SpotiFLACFetchPolicy.DEFAULT) {
            policies.remove(id)
        } else {
            policies[id] = normalized
        }
        return encode(policies)
    }

    /** How many sources carry a policy that is not the default - for the settings summary. */
    fun customizedCount(raw: String?): Int = decode(raw).size
}

/**
 * Whether asking a source again could produce a different answer.
 *
 * The distinction is the difference between a retry that helps and one that just makes the wait
 * longer. A stall, a dropped socket and a timeout are all statements about *this attempt*; a
 * catalogue miss, a quality refusal and an unplayable answer are statements about the request, and
 * repeating the same request reaches the same answer. The walk already handles the second kind:
 * the quality refusal is retried at the source's own lossy option, and the rest move on.
 */
object SpotiFLACFetchRetry {
    /**
     * How long to wait before asking the same source again.
     *
     * Short on purpose: the first attempt has just failed on a socket or a silence, and the retry is
     * a fresh request rather than a backoff for a rate limit (those are left out of the chain for
     * minutes by the provider's own record). Enough that the runtime has torn the old download down
     * before the new one claims the same item id.
     */
    fun backoffMs(attemptNumber: Int): Long =
        (attemptNumber.coerceAtLeast(1).toLong() * 400L).coerceAtMost(2_000L)

    /**
     * Failures that mean "the request was put and answered", so a second identical request is
     * pointless. Matched on the message because that is what the runtime gives the sweep: it reports
     * catalogue and quality verdicts as text, not as typed errors.
     */
    private val answeredMarkers =
        listOf(
            "no results",
            "no match",
            "not found",
            "not in",
            "invalid",
            "quality_unavailable",
            "no compatible",
            "unsupported",
            "verification required",
            "needs signed-session",
            "cannot play",
            "unplayable",
            "no such",
            "404",
        )

    fun isTransient(failure: Throwable?): Boolean {
        val error = failure ?: return false
        // The watchdog's own verdict: the source went quiet. Asking it again is exactly what a retry
        // is for - the second attempt starts a fresh socket rather than waiting on the dead one.
        if (error is SpotiFLACProviderStalledException) return true
        if (error is SpotiFLACVerificationRequiredException) return false
        if (error is SpotiFLACRelayBlockedException) return false
        val message = error.message.orEmpty().lowercase(Locale.US)
        if (answeredMarkers.any { message.contains(it) }) return false
        if (SpotiFLACQualityCascade.isQualityLimited(message)) return false
        if (SpotiFLACQualityCascade.isUnplayableFormat(message)) return false
        // A transport failure anywhere in the chain: connect, read, DNS, a dropped socket.
        var current: Throwable? = error
        while (current != null) {
            if (current is IOException || current is InterruptedIOException) return true
            current = current.cause
        }
        // A source that answered with an error the runtime did not classify is treated as transient
        // only when its message names the machinery rather than the catalogue - a bare "download
        // failed" from an extension is most often a flaky upstream.
        return message.contains("timed out") ||
            message.contains("timeout") ||
            message.contains("failed to connect") ||
            message.contains("connection") ||
            message.contains("download failed")
    }
}
