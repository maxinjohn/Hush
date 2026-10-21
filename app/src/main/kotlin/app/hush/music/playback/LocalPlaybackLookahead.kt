/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

/**
 * Whether a queue item that is already on this device still costs a provider request.
 *
 * The lookahead is the one path that fetches *ahead* of the user, so a wrong answer here is not a
 * slow start - it is a provider sweep, per source, for bytes that were about to be read off disk.
 * That is not hypothetical: measured on the reporting device, every warm of a track the user had
 * already streamed was a full six-source sweep, because the guard asked only about files the app
 * owns and a streamed track keeps its bytes in Media3's cache instead. The guard that fixed it
 * asked a second question, and this object is where the two questions now live together, so the
 * next reader cannot answer one of them and call the track absent.
 *
 * The rule is deliberately written as the union of every way a track can be local rather than as a
 * list of checks: "not local" has to mean *nothing* is local, and a rule that has to be extended
 * whenever another store appears is a rule that is eventually extended in only the place it was
 * noticed. Every term here is pinned by `LocalPlaybackLookaheadTest`, including the direction that
 * has to stay fetchable - an all-local queue is the case the user reports, but a queue with one
 * gap has to keep fetching, or the guarantee would be satisfied by fetching nothing ever again.
 *
 * Pure and free of Android on purpose: this is the part of the decision that can be wrong silently,
 * so it is the part that has to be testable without a device, a cache or a network.
 */
object LocalPlaybackLookahead {

    /** One stored run of bytes, in the units Media3's `Cache.getCachedSpans` reports them. */
    data class Span(
        val position: Long,
        val length: Long,
    )

    /**
     * How many bytes are stored continuously from [position], capped at [requestedLength].
     *
     * "Continuously" is the whole point and the reason this is not a byte count: a cache holding
     * megabytes either side of a hole is not a cache of the track, and reading it as one turns a
     * seek into a silent gap. Overlapping spans are walked in order and the cursor only ever moves
     * forward, so a duplicated or nested span cannot double-count.
     *
     * Moved here from the service unchanged, because it is the arithmetic both "is this track
     * local?" questions are built on - [needsFetch] through the evidence its caller assembles, and
     * the resolve path through `inferCachedContentLength`.
     */
    fun continuousLength(
        spans: List<Span>,
        position: Long,
        requestedLength: Long,
    ): Long {
        val targetEnd = position.saturatingAdd(requestedLength)
        var cursor = position
        val ordered =
            spans
                .filter { span -> span.position.saturatingAdd(span.length) > position }
                .sortedBy { span -> span.position }
        for (span in ordered) {
            if (span.position > cursor) break
            val spanEnd = span.position.saturatingAdd(span.length)
            if (spanEnd > cursor) {
                cursor = minOf(spanEnd, targetEnd)
                if (cursor >= targetEnd) break
            }
        }
        return (cursor - position).coerceAtLeast(0L)
    }

    /**
     * Everything already known to be local for one queue item.
     *
     * Three facts rather than one, because they come from three stores that fail independently: the
     * user's download or a SpotiFLAC playback file ([ownedFile]), the download store's own cache,
     * and Media3's streaming cache. Keeping them apart is what lets a test name the regression it is
     * guarding: dropping a term from the rule leaves the case that term covered fetchable again, and
     * only a model that still distinguishes the cases can say so.
     */
    data class LocalEvidence(
        /** A file this app owns for the item: the user's download, or a SpotiFLAC playback file. */
        val ownedFile: Boolean = false,
        /** The download store's cache covers the whole track end to end. */
        val downloadCacheComplete: Boolean = false,
        /** Media3's streaming cache covers the whole track end to end. */
        val streamingCacheComplete: Boolean = false,
    )

    /**
     * Whether a lookahead has to ask a provider for an item with this evidence.
     *
     * Written as "none of the ways it could already be here" rather than "any of them is missing",
     * so a fourth store added later has to be *excluded* here to keep a request alive - the safe
     * direction for a correctness bug in a cache is to fetch, and the safe direction for a
     * completeness bug is to skip.
     */
    fun needsFetch(evidence: LocalEvidence): Boolean =
        !evidence.ownedFile && !evidence.downloadCacheComplete && !evidence.streamingCacheComplete

    /**
     * Whether a *lookahead* has to ask a provider for an item, given everything local about it and
     * whether YouTube can serve bytes this device has cached.
     *
     * This is the entry point both lookaheads - the stream-URL one and the SpotiFLAC one - are meant
     * to go through, because the question they ask is the same one the *play* will answer a moment
     * later: will these bytes come off the device? [needsFetch] is that question minus one fact, and
     * the fact is which stores can still be served.
     *
     * The streaming cache is the one store whose vote depends on a switch. It is Media3's song cache,
     * which only ever receives bytes from a *stream*, and this app streams from YouTube alone - so the
     * play path serves those bytes only while YouTube is an enabled engine. That is not a preference
     * invented here: `resolveLocalPlaybackDataSpecIfAvailable` computes its `includePlayerCache` from
     * exactly that switch. A lookahead that counted the cache with YouTube switched off would skip the
     * one track it can never be served, and the sweep it skipped would then run at the moment of play
     * instead of ahead of it - the stall the lookahead exists to remove.
     *
     * A file the app owns, and the download store's own cache, are unconditional: a complete file on
     * the device plays with or without YouTube, which is what downloading it was for.
     */
    fun needsFetchForLookahead(
        evidence: LocalEvidence,
        youtubeEnabled: Boolean,
    ): Boolean =
        needsFetch(
            if (youtubeEnabled) evidence else evidence.copy(streamingCacheComplete = false),
        )

    private fun Long.saturatingAdd(value: Long): Long {
        if (value <= 0L) return this
        val result = this + value
        return if (result < this) Long.MAX_VALUE else result
    }
}
