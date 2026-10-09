/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import app.hush.music.playback.LocalPlaybackLookahead.LocalEvidence
import app.hush.music.playback.LocalPlaybackLookahead.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the guarantee that a queue which is already on this device costs no provider request.
 *
 * Written as a *queue swept for requests*, not as a handful of boolean assertions, because that is
 * the shape of the failure being guarded: the lookahead is the one path that fetches ahead of the
 * user, and a guard that asks one of the local questions and calls the track absent does not fail
 * loudly - it spends a provider sweep, per source, for bytes that were about to be read off disk.
 * Measured on the reporting device, every warm of an already-streamed track was a full six-source
 * sweep, and the halves that were being asked separately are exactly the two cases tested first here:
 * a track whose bytes are in Media3's *streaming cache*, and one that is a *SpotiFLAC* file on disk
 * (or a finished download, which the same term covers).
 *
 * The negative direction carries the same weight as the positive. A rule that answers "local" too
 * eagerly passes every zero-request test in this file and quietly stops fetching anything new, so
 * `a queue with one track that is not local still costs exactly one request` is what makes the rest
 * of it mean something rather than merely agree with the implementation.
 *
 * ### What this file models, and what it does not
 *
 * The caches themselves are Media3's and are not constructed here. Two things are modelled:
 * the *span arithmetic* the service runs to find how much of a track is stored continuously, and the
 * *rule* it turns that evidence into. Both live in [LocalPlaybackLookahead] and both are called by
 * production, so loosening either fails here.
 *
 * One part of the production question is deliberately **not** modelled, and the tests below are
 * written so as not to imply otherwise. The service learns a track's length only by inference - a
 * cache that stores a run from the start is the only thing that can say how long the track is - so
 * "is a stored prefix the whole track?" is answered in production by Media3's `Cache.isCached` on the
 * span, not by this arithmetic. A test here can therefore only make that distinction where the length
 * is *given*, which is what [covers] takes as an argument: every stored-track case below states the
 * length it is a stored track of, rather than leaving it to be inferred.
 */
class LocalPlaybackLookaheadTest {

    private val wholeTrack = 8_000_000L

    /** What a cache holds for a track it stored end to end: one span, from the start. */
    private fun storedWholeTrack(length: Long = wholeTrack): List<Span> =
        listOf(Span(position = 0L, length = length))

    /** What a cache holds for a track it only began: the case that has to keep fetching. */
    private fun storedPrefix(length: Long = wholeTrack): List<Span> =
        listOf(Span(position = 0L, length = length / 2))

    /**
     * Whether a cache holding [spans] covers a whole track of [length] bytes.
     *
     * Read through the production arithmetic, so these tests fail if the coverage rule is loosened -
     * "a run that stops short still counts" is the mutation that would otherwise slip through. The
     * length is required rather than defaulted for the reason in the class comment: this question is
     * only well-posed against a known length.
     */
    private fun covers(
        spans: List<Span>,
        length: Long,
    ): Boolean =
        LocalPlaybackLookahead.continuousLength(
            spans = spans,
            position = 0L,
            requestedLength = length,
        ) >= length

    /** What the service would assemble for a track a single cache holds end to end. */
    private fun streamedCache(
        spans: List<Span>,
        trackLength: Long = wholeTrack,
    ): LocalEvidence = LocalEvidence(streamingCacheComplete = covers(spans, trackLength))

    private fun downloadCache(
        spans: List<Span>,
        trackLength: Long = wholeTrack,
    ): LocalEvidence = LocalEvidence(downloadCacheComplete = covers(spans, trackLength))

    /** The user's own download, or a SpotiFLAC playback file: a file the app owns. */
    private val spotiflacFile = LocalEvidence(ownedFile = true)

    /** The number of provider requests a lookahead makes for [queue]: one per item it must fetch. */
    private fun providerRequests(queue: List<LocalEvidence>): Int =
        queue.count { LocalPlaybackLookahead.needsFetch(it) }

    // ------------------------------------------------------------------
    // The two cases measured on device, plus the third store
    // ------------------------------------------------------------------

    @Test
    fun `a queue held end to end in the streaming cache costs no provider request`() {
        // The case the device measured as a full six-source sweep per track: Media3 had every byte,
        // and the lookahead asked anyway because its guard only knew about files the app owns.
        val queue = List(60) { streamedCache(storedWholeTrack()) }
        assertEquals(0, providerRequests(queue))
    }

    @Test
    fun `a queue of SpotiFLAC files costs no provider request`() {
        // A SpotiFLAC playback file and a finished download are the same answer to this question: the
        // bytes are a file the app owns, and the resolver serves it without touching the network.
        val queue = List(60) { spotiflacFile }
        assertEquals(0, providerRequests(queue))
    }

    @Test
    fun `a queue held end to end in the download store's cache costs no provider request`() {
        val queue = List(60) { downloadCache(storedWholeTrack()) }
        assertEquals(0, providerRequests(queue))
    }

    @Test
    fun `a queue that is local in every different way costs no provider request`() {
        // The union is the rule, and a restored session is the shape most likely to catch a guard
        // written term by term: some tracks downloaded, some streamed, some warmed through SpotiFLAC.
        val shorterTrack = 3_000_000L
        val queue =
            listOf(
                streamedCache(storedWholeTrack()),
                spotiflacFile,
                downloadCache(storedWholeTrack()),
                spotiflacFile,
                streamedCache(storedWholeTrack(shorterTrack), trackLength = shorterTrack),
            )
        assertEquals(0, providerRequests(queue))
    }

    @Test
    fun `any one way of being local is enough`() {
        // Each term on its own, because "both halves are required" is how a guard written as an `&&`
        // would read - and it would fetch for every track local in only one of them.
        assertFalse(LocalPlaybackLookahead.needsFetch(LocalEvidence(ownedFile = true)))
        assertFalse(LocalPlaybackLookahead.needsFetch(LocalEvidence(downloadCacheComplete = true)))
        assertFalse(LocalPlaybackLookahead.needsFetch(LocalEvidence(streamingCacheComplete = true)))
    }

    // ------------------------------------------------------------------
    // The direction that has to keep fetching
    // ------------------------------------------------------------------

    @Test
    fun `a queue with one track that is not local still costs exactly one request`() {
        val queue =
            listOf(
                streamedCache(storedWholeTrack()),
                spotiflacFile,
                LocalEvidence(),
                streamedCache(storedWholeTrack()),
            )
        assertEquals(1, providerRequests(queue))
    }

    @Test
    fun `an empty queue asks for nothing, and an untouched track asks for one`() {
        assertEquals(0, providerRequests(emptyList()))
        assertTrue(LocalPlaybackLookahead.needsFetch(LocalEvidence()))
    }

    @Test
    fun `a cache that has stored nothing is not a local copy`() {
        // What an entry looks like before any byte of it has been read: no spans at all, which must
        // not be read as "stored" merely because it is not a prefix with a hole in it.
        assertTrue(LocalPlaybackLookahead.needsFetch(streamedCache(emptyList())))
        assertTrue(LocalPlaybackLookahead.needsFetch(downloadCache(emptyList())))
        assertFalse(covers(emptyList(), wholeTrack))
        assertFalse(covers(storedPrefix(), wholeTrack))
        assertTrue(covers(storedWholeTrack(), wholeTrack))
    }

    @Test
    fun `bytes split across the two caches are not a stored track`() {
        // Neither store holds the whole thing, so there is no single cache that can serve it. The two
        // halves come from the *merged* run the service infers its length from - which is exactly the
        // trap: the merged run reaches the end, so a rule that treated the merged run as the copy
        // would report a track as local that neither data source can answer for. Each cache is judged
        // on its own, and so is each half here.
        val firstHalf = downloadCache(storedPrefix(), trackLength = wholeTrack)
        val secondHalf =
            streamedCache(
                listOf(Span(position = wholeTrack / 2, length = wholeTrack / 2)),
                trackLength = wholeTrack,
            )
        assertTrue(LocalPlaybackLookahead.needsFetch(firstHalf))
        assertTrue(LocalPlaybackLookahead.needsFetch(secondHalf))
        assertEquals(2, providerRequests(listOf(firstHalf, secondHalf)))
    }

    // ------------------------------------------------------------------
    // The span arithmetic the coverage question is answered with
    // ------------------------------------------------------------------

    @Test
    fun `a contiguous run is measured to its end`() {
        assertEquals(
            100L,
            LocalPlaybackLookahead.continuousLength(listOf(Span(0L, 100L)), 0L, Long.MAX_VALUE),
        )
    }

    @Test
    fun `a hole ends the run`() {
        // Two spans either side of a gap are not one cached track, and reading them as one turns a
        // seek into a silent hole.
        val withHole = listOf(Span(0L, 100L), Span(200L, 100L))
        assertEquals(100L, LocalPlaybackLookahead.continuousLength(withHole, 0L, Long.MAX_VALUE))
    }

    @Test
    fun `overlapping spans are not counted twice`() {
        val overlapping = listOf(Span(0L, 100L), Span(50L, 100L))
        assertEquals(150L, LocalPlaybackLookahead.continuousLength(overlapping, 0L, Long.MAX_VALUE))
    }

    @Test
    fun `the run stops at the length that was asked for`() {
        val spans = listOf(Span(0L, 1_000L))
        assertEquals(100L, LocalPlaybackLookahead.continuousLength(spans, 0L, 100L))
    }

    @Test
    fun `a run is measured from the position asked about`() {
        val spans = listOf(Span(0L, 1_000L))
        assertEquals(100L, LocalPlaybackLookahead.continuousLength(spans, 400L, 100L))
    }

    @Test
    fun `spans that end before the position say nothing`() {
        assertEquals(0L, LocalPlaybackLookahead.continuousLength(listOf(Span(0L, 100L)), 500L, 100L))
        assertEquals(0L, LocalPlaybackLookahead.continuousLength(emptyList(), 0L, 100L))
    }
}
