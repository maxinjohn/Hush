/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * Which provider the runtime was actually on, read from the runtime's own log.
 *
 * Hush hands the runtime the whole candidate list and the runtime falls through it by itself, so
 * the provider an abandoned attempt went quiet on is routinely **not** the provider that attempt
 * was requested with. Measured on device:
 *
 * ```
 * Native download start: source=amazon
 *   [DownloadWithExtensionFallback] Trying provider: deezer
 *   [SignedSession] Provider temporarily unavailable for extension deezer; retrying in 10s
 * provider stalled id=amazon ... abandoned, trying next provider
 * ```
 *
 * Recording that stall against `amazon` is wrong twice over: the demotion moves an innocent
 * provider behind the wedged one, and the very next sweep attempt walks into `deezer` again and
 * pays the same timeout. The attempt says nothing about which provider it was on, so the log is
 * the only source for it - and it is deliberately read as *evidence*, never as a verdict: only
 * lines whose whole purpose is to name a provider in the walk are matched, and a line naming a
 * provider some other way (an `[Extension:amazon:INFO]` tag, a track id) is ignored.
 *
 * A wrong answer here is cheap and self-correcting. The worst case is a provider demoted behind
 * the ones this sweep has not reached yet, and a demoted provider is still asked - just later.
 */
internal object SpotiFLACRuntimeWalk {

    /**
     * Lines the fallback walk writes as it moves between providers.
     *
     * Ordered most-specific first: the first pattern to match decides the provider for that line,
     * so a `Trying provider:` line can never be read as anything else.
     */
    private val PROVIDER_LINES = listOf(
        Regex("""Trying provider:\s*([A-Za-z0-9._-]+)"""),
        Regex("""Provider temporarily unavailable for extension\s+([A-Za-z0-9._-]+)"""),
        Regex("""Provider\s+([A-Za-z0-9._-]+)\s+maps requested quality"""),
        Regex("""Extension service '([^']+)' moved to priority front"""),
        Regex("""Source extension\s+([A-Za-z0-9._-]+)\s+failed"""),
        Regex("""Track source is extension '([^']+)' matching selected provider"""),
    )

    /**
     * The last provider the walk named in [messages], or null when it named none.
     *
     * Last rather than first: the lines arrive in walk order, so the most recent one is the
     * provider the attempt stopped on.
     */
    fun providerOf(messages: List<String>): String? {
        var provider: String? = null
        for (message in messages) {
            val named = providerIn(message) ?: continue
            provider = named
        }
        return provider
    }

    /**
     * Lines that name a *lyrics* provider, which is a different chain entirely.
     *
     * The lyrics walk logs the same sentence shape (`[Lyrics] Trying provider: lrclib`), and it
     * runs after a download - where it is then the most recent "Trying provider" line in the
     * buffer. Read as the download walk, it would attribute every stall to a lyrics backend that
     * is not even in the candidate list, and stop demoting the provider that actually refused.
     */
    private val NOT_A_DOWNLOAD_WALK = Regex("(?i)\\blyrics\\b")

    /**
     * The walk's own account of a provider *failing*, as opposed to being reached.
     *
     * Deliberately not folded into [PROVIDER_LINES]: attribution of a stall wants the provider the
     * attempt was last *on*, and a failure line names a provider the walk has already finished
     * with. This pattern exists for the one question only a failure line can answer - whether the
     * provider's reason was a refusal to serve at all.
     */
    private val FAILURE_LINES = Regex("""([A-Za-z0-9._-]+)\s+failed\s*:""")

    /**
     * Reasons providers give for refusing service for a while rather than for this track.
     *
     * Written as the phrases that actually appear, and deliberately narrow: "429" on its own would
     * match a track title, an item id or a byte count in an unrelated line, whereas `HTTP 429` and
     * "too many requests" only ever come from a server saying no. A false positive here costs a
     * working provider its place in the next few sweeps, so the bar is set high.
     */
    private val RATE_LIMIT_REASONS = listOf(
        "http 429",
        "429 too many",
        "rate limit",
        "rate-limit",
        "ratelimit",
        "too many requests",
        "slow_down",
        "temporarily blocked",
    )

    /**
     * Providers the walk reported as *refusing service*, not as lacking the track.
     *
     * Measured on the reporting device: Deezer answered `HTTP 429 for /tickets` to every attempt,
     * and because a fast refusal is not a stall, nothing recorded it - so the same provider was
     * asked first on every later track, kept paying the refusal, and kept the rate limit alive.
     *
     * [knownProviders] is the sweep's own candidate list, and only names from it are returned. The
     * log is evidence, not a verdict: a captured word like "resolution" (from "stream resolution
     * failed") must not be able to enter the candidate set as a provider that is cooling down.
     */
    fun rateLimitRefusals(
        messages: List<String>,
        knownProviders: Collection<String>,
    ): List<String> {
        if (messages.isEmpty() || knownProviders.isEmpty()) return emptyList()
        val known = knownProviders.associateBy { it.lowercase() }
        val refusals = LinkedHashSet<String>()
        for (message in messages) {
            if (NOT_A_DOWNLOAD_WALK.containsMatchIn(message)) continue
            if (RATE_LIMIT_REASONS.none { message.contains(it, ignoreCase = true) }) continue
            val named = providerIn(message) ?: FAILURE_LINES.find(message)?.groupValues?.getOrNull(1)
            val canonical = known[named?.trim()?.lowercase()] ?: continue
            refusals += canonical
        }
        return refusals.toList()
    }

    /** The provider named by a single log line, or null when it names none. */
    fun providerIn(message: String): String? {
        if (NOT_A_DOWNLOAD_WALK.containsMatchIn(message)) return null
        for (pattern in PROVIDER_LINES) {
            val match = pattern.find(message) ?: continue
            val captured = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (captured.isNotEmpty()) return captured
        }
        return null
    }
}
