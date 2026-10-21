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

    /**
     * The wording for "this source answered with audio that cannot be played".
     *
     * A source can return a *different question's* answer: Amazon's extension serves Dolby
     * Digital Plus and Atmos beside its lossless FLAC, and a Dolby stream is silent on a device
     * with no AC-3/AC-4 output path - the player advances and nothing is audible. That is not a
     * catalogue miss, so remembering it as one would keep the track failing forever; it is the
     * same kind of answer as "I cannot deliver that quality", and is retried the same way - by
     * asking that source for the lossy option it does declare.
     */
    const val UNPLAYABLE_FORMAT_MARKER = "answered with audio this app cannot play"

    /** True when a failure was "that answer cannot be played", not "not in my catalogue". */
    fun isUnplayableFormat(failure: String?): Boolean =
        failure?.contains(UNPLAYABLE_FORMAT_MARKER) == true

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
        val text = (token + " " + option.label.lowercase(Locale.US)).replace(NEGATED_SPATIAL, " ")
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
     *
     * [addonCount] is the half that used to be left out entirely, and it is what made the walk
     * look shorter than it is: a module or server addon is not one attempt at the runtime's stall
     * window, it is its own search plus its own stream fetch, each with [ADDON_CALL_BUDGET_MS] to
     * spend. Measured on the reporting device, a ten-entry walk with three modules in it was
     * budgeted as `sources=14 perAttemptMs=25000 budgetMs=96000` and cut off at 124s while a module
     * was still inside its own budget - and the two entries behind it in the user's order were
     * never asked at all, which is the failure this function exists to prevent.
     */
    fun sweepBudgetMs(
        sourceCount: Int,
        quality: String,
        perAttemptMs: Long = SpotiFLACFetchPolicy.DEFAULT.attemptCeilingMs,
        addonCount: Int = 0,
        addonBudgetMs: Long = ADDON_BUDGET_MS,
        /**
         * How many times one source may be asked, from the user's per-source retry setting. The
         * budget has to cover the retries or the sweep's clock ends the walk part-way, and the
         * sources behind the cut look exactly like sources that had nothing.
         */
        maxAttempts: Int = SpotiFLACFetchPolicy.DEFAULT.maxAttempts,
    ): Long {
        // The quality retry is a different question put to the same source, so the number of attempts
        // a source may cost is the larger of the two - never their sum, which would budget twice for
        // work a source only does once.
        val perSourceAttempts =
            maxOf(attemptsPerSource(quality), maxAttempts.coerceAtLeast(1))
        val attempts = sourceCount.coerceAtLeast(1) * perSourceAttempts
        // Sized by the window each attempt will actually be given, because a budget sized for a
        // shorter one cuts the chain off part-way - and the sources behind the cut were never
        // asked, which is indistinguishable from a catalogue miss.
        val allowance = perAttemptMs.coerceAtLeast(1L)
        val native = attempts * allowance
        // A module is asked twice per track (search, then stream) and each call may spend its own
        // budget before its own watchdog fires, so one entry costs that pair and not one attempt.
        val addons = addonCount.coerceAtLeast(0) * addonBudgetMs.coerceAtLeast(1L)
        return (native + addons).coerceIn(MIN_SWEEP_BUDGET_MS, MAX_SWEEP_BUDGET_MS)
    }

    private val QUALITY_KINDS = setOf("lossless", "lossy", "spatial")

    /**
     * A *refusal* of spatial audio written into a label, read before the keywords are.
     *
     * The manifests Hush reads advertise their hi-res rung by saying what it is not: Tido's
     * `HIRES` option is "Max (Hi-Res 24-bit up to 192 kHz, no Atmos)". Read literally that is a
     * label containing "Atmos", so it classified as spatial - the one kind the cascade decides it
     * cannot play - and the rung Hush should have been asking for was excluded before the lossy
     * fallback was even considered. Removing the negated phrase first is what lets the rest of the
     * label answer: `24-bit` says lossless.
     */
    private val NEGATED_SPATIAL =
        Regex("\\b(?:no|without|non)[- ]?(?:dolby[- ]?)?(?:atmos|dolby|surround)\\b")

    private val SPATIAL_TOKENS = setOf("ac4", "ac-4", "eac3", "e-ac-3", "ec-3")

    /** Never shorter than the window a cold cache needs for a real provider round-trip. */
    private const val MIN_SWEEP_BUDGET_MS = 45_000L

    /**
     * Upper bound on how long a listener waits before the failure is shown.
     *
     * Completeness is worth some waiting - skipping to the next track is always
     * available - but not an unbounded amount: past here the failure is more likely to
     * be systemic than a fallback chain that merely needed longer.
     *
     * It is deliberately the same lid [SpotiFLACSweepBudget.MAX_TOTAL_MS] puts on a sweep that is
     * being extended by a live transfer, and not a second, smaller one. The two used to disagree - a
     * plan budget of 96s and a clock that could reach 240s - so a walk of fourteen switched-on
     * sources was *always* planned for less time than its own entries could legitimately spend, and
     * the sources behind the cut were skipped on every long install. One bound, read the same way by
     * both, is what makes "every source is asked" something the plan can actually pay for.
     */
    private const val MAX_SWEEP_BUDGET_MS = SpotiFLACSweepBudget.MAX_TOTAL_MS

    /**
     * What one module or server addon may spend in a sweep: its search plus its stream fetch.
     *
     * Mirrors `SpotiFLACModuleHost`'s own per-call budgets (`SEARCH_BUDGET_MS` and
     * `STREAM_BUDGET_MS`), because those are the limits that fire if a module wedges. Budgeting less
     * per entry only means this clock cancels the attempt before the module's own watchdog does - and
     * an attempt cancelled from above never answers, so the walk moves on with the source untried.
     */
    private const val ADDON_BUDGET_MS = 60_000L
}
