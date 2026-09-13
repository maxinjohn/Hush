/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.util.Locale

/**
 * Whether a source that failed a request was ever really asked.
 *
 * A lossless request is impossible for some extensions - the YT Music one serves lossy
 * audio only - and the runtime says so plainly instead of returning a catalogue answer:
 *
 * ```
 * provider ytmusic-spotiflac has no compatible lossless quality for "LOSSLESS"
 * ```
 *
 * That is not the same as "this source does not have the track". It means the question
 * could not be put to it at all, so counting it as tried is what made disabling one
 * source look like the whole chain had given up: with lossless selected, every lossy-only
 * source was structurally excluded and the remaining lossless sources had to do all the
 * work alone.
 *
 * The retry quality is *not* a fixed token. A source only accepts the quality ids its own
 * manifest declares, and asking for anything else is the same dead end as asking for
 * lossless - the runtime maps an unrecognised id to the lossless kind and fails again.
 * YT Music declares exactly one option, `best`, so a retry at `"320"` fails with
 * "no compatible lossless quality for 320", which is how a retry can look like it ran
 * while never having asked a different question. The token therefore comes from the
 * target's manifest, resolved with the runtime's own kind rules so Hush and the runtime
 * agree about which option is the lossy one.
 */
object SpotiFLACQualityCascade {
    /** Qualities that demand a lossless container, which not every source can produce. */
    private val losslessQualities =
        setOf("LOSSLESS", "HI_RES_LOSSLESS", "FLAC", "HIRES", "HI_RES")

    /** The runtime's own wording for "I cannot deliver that quality". */
    private const val QUALITY_LIMIT_MARKER = "no compatible "

    /** A quality option as a source's manifest declares it. */
    data class QualityOption(
        val id: String,
        val kind: String = "",
        val label: String = "",
    )

    /** True when [quality] can only be satisfied by a lossless source. */
    fun isLossless(quality: String): Boolean =
        quality.trim().uppercase(Locale.US) in losslessQualities

    /**
     * Whether a failure is "I cannot deliver that quality" rather than "not in my
     * catalogue".
     *
     * The runtime words this as `no compatible <kind> quality for "<requested>"`. A
     * catalogue miss says something else entirely (`no results`, `quality_unavailable`
     * with a 404, a match failure), which is a real answer and must be remembered as one.
     */
    fun isQualityLimited(failure: String?): Boolean =
        failure?.lowercase(Locale.US)?.contains(QUALITY_LIMIT_MARKER) == true &&
            failure.lowercase(Locale.US).contains("quality for")

    /**
     * Which of the runtime's three quality kinds an option belongs to.
     *
     * Mirrors the runtime's own resolver: a declared [QualityOption.kind] wins, otherwise
     * the id and label are read, and a generic `best`/`default` token defers to the
     * manifest's `downloadFallbackTier`. The description is deliberately ignored - it can
     * mention the formats a source falls back to, which would misclassify the option.
     *
     * Returns "" when the kind cannot be established, which the runtime treats as
     * lossless; kept distinct here so a caller can tell "declared lossless" from
     * "unknown".
     */
    fun kindOf(option: QualityOption, downloadFallbackTier: String?): String {
        val declared = option.kind.trim().lowercase(Locale.US)
        if (declared in QUALITY_KINDS) return declared

        val token = option.id.trim().lowercase(Locale.US)
        val text = token + " " + option.label.lowercase(Locale.US)
        if (text.contains("atmos") || text.contains("dolby") || text.contains("surround") ||
            token in SPATIAL_TOKENS
        ) {
            return "spatial"
        }
        if (text.contains("lossless") || text.contains("flac") || text.contains("alac") ||
            text.contains("24-bit") || text.contains("16-bit") || token == "hi_res"
        ) {
            return "lossless"
        }
        if (token == "high" || token == "low" || text.contains("mp3") ||
            text.contains("aac") || text.contains("opus") || text.contains("vorbis")
        ) {
            return "lossy"
        }
        if (token == "best" || token == "default" || token.isEmpty()) {
            return when (downloadFallbackTier?.trim()?.lowercase(Locale.US)) {
                "hi_res", "lossless" -> "lossless"
                "low_res" -> "lossy"
                else -> ""
            }
        }
        return ""
    }

    /**
     * The quality to retry this source at, or null when it has nothing lossy to offer.
     *
     * Taking the first lossy option respects the manifest's own ordering, which lists its
     * preferred qualities first. Null is a real answer: a source that only declares
     * lossless (deezer) or declares nothing at all (a legacy manifest, which the runtime
     * passes through untouched) has no second question to ask, so a sweep must not spend
     * a timeout pretending otherwise.
     */
    fun lossyRetryToken(options: List<QualityOption>, downloadFallbackTier: String?): String? =
        options
            .firstOrNull { kindOf(it, downloadFallbackTier) == "lossy" }
            ?.id
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** How many attempts one source may make, for sizing the sweep budget. */
    fun attemptsPerSource(quality: String): Int = if (isLossless(quality)) 2 else 1

    /**
     * The wall-clock budget one sweep may spend, given how much work it now contains.
     *
     * The lossy retry doubles the attempts a sweep can make, so a fixed 45s budget that
     * used to cover a full pass no longer does: the chain would be cut off part-way and
     * the sources behind the cut - the ones that were never asked - look exactly like
     * sources that had nothing. Sizing by the actual work lets every enabled source be
     * put the question at least once, which is the whole point of a fallback chain.
     *
     * The per-attempt allowance is the provider stall timeout: budgeting more than that
     * per attempt only buys time for a provider that is already being abandoned.
     */
    fun sweepBudgetMs(sourceCount: Int, quality: String): Long {
        val attempts = sourceCount.coerceAtLeast(1) * attemptsPerSource(quality)
        return (attempts * PER_ATTEMPT_BUDGET_MS).coerceIn(MIN_SWEEP_BUDGET_MS, MAX_SWEEP_BUDGET_MS)
    }

    private val QUALITY_KINDS = setOf("lossless", "lossy", "spatial")

    private val SPATIAL_TOKENS = setOf("ac4", "ac-4", "eac3", "e-ac-3", "ec-3")

    /**
     * Time allowed per attempt.
     *
     * Matches the provider stall timeout, so a sweep's budget is roughly "one wedged
     * provider per attempt before the whole walk is out of time" - a global fault rather
     * than one slow source, which is a different problem from this chain's fallback.
     */
    private const val PER_ATTEMPT_BUDGET_MS = 8_000L

    /** Never shorter than the window a cold cache needs for a real provider round-trip. */
    private const val MIN_SWEEP_BUDGET_MS = 45_000L

    /**
     * Upper bound on how long a listener waits before the failure is shown.
     *
     * Completeness is worth some waiting - skipping to the next track is always
     * available - but not an unbounded amount: past here the failure is more likely to
     * be systemic than a fallback chain that merely needed longer.
     */
    private const val MAX_SWEEP_BUDGET_MS = 96_000L
}
