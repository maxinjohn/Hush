/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * Which queue positions a SpotiFLAC lookahead covers.
 *
 * Kept pure and separate from the player so the number of tracks warmed can be pinned by
 * tests rather than by watching a device: the count comes from the user's "Prefetch
 * upcoming songs" setting (0 = off, up to 4), and getting it wrong is silent - a lookahead
 * that warms one track too few shows up as a slow start, and one too many as bandwidth
 * taken from the track that is playing.
 *
 * Two rules that are easy to get wrong and are therefore stated here:
 *
 * - **The track being played is never in its own lookahead.** A one-item queue would
 *   otherwise resolve the song already on. Wrapping is therefore bounded by the current
 *   position rather than by the queue length.
 * - **A lookahead never repeats a position.** With a short queue and a large count, the
 *   offsets would wrap onto positions already covered.
 *
 * Both engines' lookaheads come from here, for the same reason the count does: Hush warms
 * tracks through two different mechanisms, and two mechanisms that disagree about *which*
 * tracks get warmed produce a playback path that is fast or slow depending on which engine
 * happened to answer. One plan, asked twice, cannot disagree.
 */
object SpotiFLACPrefetchPlan {

    /**
     * The positions to warm, in the order to warm them: up to [count] items after
     * [currentIndex].
     *
     * [wrap] is whether the queue comes back round after its last track - repeat-all, in
     * other words - and it is the only thing that decides what happens at the end:
     *
     * - **Wrapping** continues at the queue's first item, so a looping queue is warm on the
     *   wrap and the last track does not start cold. This is what makes a looped playlist
     *   feel continuous.
     * - **Not wrapping** stops at the last track. Those positions will not be played next, and
     *   warming them is not free: the SpotiFLAC side is whole lossless downloads, so warming
     *   the head of a queue that is about to end would fetch tens of megabytes for tracks
     *   nobody will hear.
     *
     * Returns an empty list for a count of zero or less, an empty queue, or - when wrapping -
     * a queue whose only item is the one playing.
     */
    fun upcomingIndices(
        total: Int,
        currentIndex: Int,
        count: Int,
        wrap: Boolean = true,
    ): List<Int> {
        if (count <= 0 || total <= 0 || currentIndex < 0) return emptyList()
        val indices = ArrayList<Int>(minOf(count, total - 1).coerceAtLeast(0))
        for (offset in 1..count) {
            val index = currentIndex + offset
            if (index >= total) {
                if (!wrap) break
                // Wrapped back onto the track that is playing, or onto one already planned.
                val looped = index % total
                if (looped == currentIndex || looped in indices) break
                indices.add(looped)
            } else {
                indices.add(index)
            }
        }
        return indices
    }
}
