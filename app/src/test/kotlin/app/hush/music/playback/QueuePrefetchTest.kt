/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import app.hush.music.utils.PlaybackDownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins which queue row may describe a prefetch, and which row never may.
 *
 * The failure this guards is one the user reported from the phone: a song playing off the device
 * while the row beside it said it was being fetched and downloaded. Two mechanisms produce it, and
 * the tests are written to catch each one on its own:
 *
 *  - the lookahead's progress being read as the *playing* track's progress (the runtime reports one
 *    item at a time, so an id is the only thing telling them apart), and
 *  - a lookahead that keeps claiming a track after it has landed, so a row says "Queued" over bytes
 *    that are already on the device.
 *
 * The negative direction carries as much weight as the positive, as in [LocalPlaybackLookaheadTest]:
 * a rule that answered "this row is being prefetched" eagerly would draw a ring on every row of a
 * queue nobody is fetching, which is worse than the silence it replaced.
 */
class QueuePrefetchTest {

    private val PLAYING = "playing-song"
    private val NEXT = "next-song"
    private val LATER = "later-song"

    private fun progress(
        mediaId: String,
        percent: Int = 0,
        fromCache: Boolean = false,
        stage: String? = null,
    ) = PlaybackDownloadProgress(
        mediaId = mediaId,
        sourceId = "deezer",
        percent = percent,
        stage = stage,
        fromCache = fromCache,
    )

    private fun stateOf(
        mediaId: String?,
        prefetch: LookaheadPrefetch?,
        currentMediaId: String? = PLAYING,
    ): QueuePrefetchState? =
        queuePrefetchState(
            mediaId = mediaId,
            currentMediaId = currentMediaId,
            prefetch = prefetch,
        )

    // ------------------------------------------------------------------
    // The row that must stay silent
    // ------------------------------------------------------------------

    @Test
    fun `the playing track's row says nothing while the lookahead fetches it`() {
        // The reported bug, at the level it can be pinned: one signal, two tracks. The row for the
        // song in the user's ears must not describe the fetch of that song - it has the transport,
        // the fraction ring and the codec row for that, and a prefetch label over it is what made a
        // song playing perfectly well look like it was still downloading.
        val prefetch = LookaheadPrefetch(planned = listOf(PLAYING, NEXT), active = progress(PLAYING, percent = 40))
        assertNull(stateOf(PLAYING, prefetch))
        assertNull(stateOf(PLAYING, LookaheadPrefetch(planned = listOf(PLAYING))))
    }

    @Test
    fun `a queue with nothing running says nothing, however the rows are asked`() {
        assertNull(stateOf(NEXT, LookaheadPrefetch.None))
        assertNull(stateOf(NEXT, null))
        assertNull(stateOf(null, LookaheadPrefetch(planned = listOf(NEXT))))
        assertNull(stateOf("   ", LookaheadPrefetch(planned = listOf(NEXT))))
    }

    // ------------------------------------------------------------------
    // The rows that do say something
    // ------------------------------------------------------------------

    @Test
    fun `the track being fetched shows the fraction the runtime measured`() {
        val prefetch = LookaheadPrefetch(planned = listOf(NEXT, LATER), active = progress(NEXT, percent = 42))
        assertEquals(QueuePrefetchState.Downloading(42), stateOf(NEXT, prefetch))
        // And only that row: the ones behind it are waiting, not downloading.
        assertEquals(QueuePrefetchState.Queued, stateOf(LATER, prefetch))
    }

    @Test
    fun `a provider stage with no bytes yet reads as fetching, not as nothing`() {
        // `checking_session` and `resolving_metadata` carry no fraction at all, which is the same
        // window in which the player says "Fetching from <source>…". A row that drew an empty ring
        // for it would look like a download that had stalled at zero.
        val prefetch =
            LookaheadPrefetch(
                planned = listOf(NEXT),
                active = progress(NEXT, percent = 0, stage = "resolving_metadata"),
            )
        assertEquals(QueuePrefetchState.Fetching, stateOf(NEXT, prefetch))
    }

    @Test
    fun `a planned track that is not being fetched says it is waiting`() {
        val prefetch = LookaheadPrefetch(planned = listOf(NEXT, LATER))
        assertEquals(QueuePrefetchState.Queued, stateOf(NEXT, prefetch))
        assertEquals(QueuePrefetchState.Queued, stateOf(LATER, prefetch))
    }

    @Test
    fun `a track the lookahead has finished says nothing`() {
        // Percent 100 arrives before the lookahead has recorded the track as dealt with, and a cache
        // hit publishes the same completed entry - neither is a download in progress, and neither
        // should leave a badge over a track that is already on the device.
        assertEquals(
            null,
            stateOf(NEXT, LookaheadPrefetch(planned = listOf(NEXT), active = progress(NEXT, percent = 100))),
        )
        assertEquals(
            null,
            stateOf(
                NEXT,
                LookaheadPrefetch(
                    planned = listOf(NEXT),
                    active = progress(NEXT, percent = 100, fromCache = true),
                ),
            ),
        )
    }

    @Test
    fun `a track is not claiming anything for another track's progress`() {
        // The runtime is one item wide, so the only thing tying a progress entry to a row is its id.
        val prefetch = LookaheadPrefetch(planned = listOf(NEXT), active = progress(LATER, percent = 73))
        assertEquals(QueuePrefetchState.Queued, stateOf(NEXT, prefetch))
        assertEquals(QueuePrefetchState.Downloading(73), stateOf(LATER, prefetch))
    }

    @Test
    fun `a track outside the lookahead says nothing`() {
        val prefetch = LookaheadPrefetch(planned = listOf(NEXT), active = progress(NEXT, percent = 5))
        assertNull(stateOf("some-other-song", prefetch))
    }
}
