/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rules a track change's movement is built on, because every one of them is a fault
 * somebody reported rather than a preference.
 *
 * They are the parts of the arrival that are decided rather than drawn: what an arrival *is* (a turn
 * or a fade), when a cover is a new arrival at all, and the shape of the turn itself. Each was read
 * out of a screen recording first - a skip that showed the new cover flat, took it away again and
 * turned it back in; a turn that played after the cover had already arrived; a turn whose rotation
 * was spent in its first eighth of a second, which reads as a snap and then nothing - so these
 * assertions are the ones a careless edit breaks in silence.
 */
class TrackChangeMotionTest {
    @Test
    fun `a track whose cover is asked for twice is still one arrival`() {
        // A video thumbnail resolves into the full-resolution image a moment after the track starts.
        assertEquals(
            trackIdentityOf(mediaId = "abc", artworkUri = "video-thumb.jpg"),
            trackIdentityOf(mediaId = "abc", artworkUri = "high-res.jpg"),
        )
    }

    @Test
    fun `a different track is a different arrival`() {
        assertNotEquals(
            trackIdentityOf(mediaId = "abc", artworkUri = "high-res.jpg"),
            trackIdentityOf(mediaId = "def", artworkUri = "high-res.jpg"),
        )
    }

    @Test
    fun `a track with no id falls back to the request it is playing`() {
        assertEquals(
            "song.m4a",
            trackIdentityOf(mediaId = "", artworkUri = "song.m4a"),
        )
    }

    @Test
    fun `the first composition fades rather than turning`() {
        // Opening the player has its own entrance; a turn over it would be one movement too many.
        assertEquals(
            TrackChangeArrival.FADE,
            trackChangeArrival(
                arrivalsStarted = false,
                turn = true,
                animated = true,
                animationsDisabled = false,
                played = false,
            ),
        )
    }

    @Test
    fun `a track brought in by a gesture turns the page`() {
        // A tap on skip and a swipe across the artwork are the same movement to a person, so the
        // caller asks for a turn for both - see `TrackArtwork`'s `turn` in `Thumbnail`. Artwork
        // that only settled after a swipe is what put a cover being replaced next to a page being
        // turned.
        assertEquals(
            TrackChangeArrival.TURN,
            trackChangeArrival(
                arrivalsStarted = true,
                turn = true,
                animated = true,
                animationsDisabled = false,
                played = false,
            ),
        )
    }

    @Test
    fun `a page's memory outlives the item it was drawn in`() {
        // The grid recycles its items, and a swipe is the gesture that makes it do so: the page the
        // change lands on is the one whose composition was just reused. A memory remembered inside
        // the page went with it, so the swipe arrived as a first composition - a fade - and lost the
        // cover it was meant to arrive over. Asking for the same slot twice is what a recycled page
        // does.
        val memories = TrackArtworkMemories()
        val first = memories.forSlot("current")
        first.arrivalsStarted.value = true
        first.turnedFor.value = "abc"
        first.baseModel.value = "previous-cover.jpg"

        val recycled = memories.forSlot("current")
        assertTrue("the slot's second page should be the same memory", first === recycled)
        assertTrue("arrivalsStarted should have survived", recycled.arrivalsStarted.value)
        assertEquals("turnedFor should have survived", "abc", recycled.turnedFor.value)
        assertEquals("the cover to arrive over should have survived", "previous-cover.jpg", recycled.baseModel.value)
    }

    @Test
    fun `one page's memory is not another's`() {
        // The pages either side of the one being read have their own arrival to play.
        val memories = TrackArtworkMemories()
        memories.forSlot("current").turnedFor.value = "abc"
        assertNotEquals(
            memories.forSlot("current").turnedFor.value,
            memories.forSlot("next").turnedFor.value,
        )
    }

    @Test
    fun `a page handed a memory that has arrived before turns`() {
        // The swipe's rule, spelled out: the decision itself is unchanged - it is the memory that
        // has to still be there when the change lands.
        val recycled = TrackArtworkMemories().forSlot("current")
        recycled.arrivalsStarted.value = true
        assertEquals(
            TrackChangeArrival.TURN,
            trackChangeArrival(
                arrivalsStarted = recycled.arrivalsStarted.value,
                turn = true,
                animated = true,
                animationsDisabled = false,
                played = recycled.turnedFor.value == "abc",
            ),
        )
    }

    @Test
    fun `an arrival is played once for one track`() {
        // The second request for the same track's cover is not a second track.
        listOf(true, false).forEach { turn ->
            assertEquals(
                "turn=$turn",
                TrackChangeArrival.SETTLE,
                trackChangeArrival(
                    arrivalsStarted = true,
                    turn = turn,
                    animated = true,
                    animationsDisabled = false,
                    played = true,
                ),
            )
        }
    }

    @Test
    fun `motion switched off places the cover instead of moving it`() {
        assertEquals(
            TrackChangeArrival.SETTLE,
            trackChangeArrival(
                arrivalsStarted = true,
                turn = true,
                animated = true,
                animationsDisabled = true,
                played = false,
            ),
        )
    }

    @Test
    fun `a layer nobody watches is placed, not moved`() {
        // The blurred backdrop, and the pages either side: a full-frame transform per frame spent on
        // something off-screen or re-blurred.
        listOf(true, false).forEach { turn ->
            assertEquals(
                "turn=$turn",
                TrackChangeArrival.SETTLE,
                trackChangeArrival(
                    arrivalsStarted = true,
                    turn = turn,
                    animated = false,
                    animationsDisabled = false,
                    played = false,
                ),
            )
        }
    }

    @Test
    fun `the turn starts turned away and ends flat`() {
        assertEquals(1f, turnProgress(-1L), 0.0001f)
        assertEquals(1f, turnProgress(0L), 0.0001f)
        assertEquals(0f, turnProgress(TRACK_CHANGE_TURN_MS), 0.0001f)
        assertEquals("past the end the page is flat and stays flat", 0f, turnProgress(TRACK_CHANGE_TURN_MS * 2), 0.0001f)
    }

    @Test
    fun `the rotation is still carrying the page at half of the turn`() {
        // The regression this holds: a curve damped as exp(-8t) reached flat inside an eighth of the
        // window and then sat there, so the movement read as a snap followed by nothing - and on a
        // device that drops the frames around a decode, the snap was most of what could be seen.
        val half = turnProgress(TRACK_CHANGE_TURN_MS / 2)
        assertTrue(
            "the page should still be off flat at half of the turn, was $half",
            abs(half) > 0.1f,
        )
    }

    @Test
    fun `the page is still visibly turned away a fifth of the way in`() {
        val fifth = turnProgress(TRACK_CHANGE_TURN_MS / 5)
        assertTrue(
            "the page should still be turned away, was $fifth",
            fifth > 0.15f,
        )
    }

    @Test
    fun `the page comes to rest without swinging back past flat`() {
        // A counter-lean reads as a glitch, not as paper. The turn is allowed to reach flat and stay
        // there; it is not allowed to lean the other way on the way there.
        val samples =
            (0..48).map { step -> turnProgress(TRACK_CHANGE_TURN_MS * step / 48) }
        val deepest = samples.min()
        assertTrue(
            "the page leaned $deepest past flat, which is a swing back",
            deepest >= -0.001f,
        )
        // Most of the way flat, not all the way: a decay steep enough to be flat by this point is one
        // whose last quarter of the movement took half the window, which reads as a turn that crawls
        // once it is past its middle. See the test below, which is the other end of the same rule.
        val settled = abs(turnProgress(TRACK_CHANGE_TURN_MS * 4 / 5))
        assertTrue(
            "the page should be most of the way flat by four fifths of the turn, was $settled",
            settled < 0.12f,
        )
    }

    @Test
    fun `the turn does not crawl through its second half`() {
        // The fault this holds: with the movement decaying as `(1 - t)^2`, the last quarter of the way
        // took *half* the window - smooth for the first half of the turn and slow for the rest, which
        // is how it was reported. A page that comes to rest still travels on its way there.
        val byHalf = 1f - turnProgress(TRACK_CHANGE_TURN_MS / 2)
        val byNineTenths = 1f - turnProgress(TRACK_CHANGE_TURN_MS * 9 / 10)
        assertTrue(
            "the second half of the turn should carry a good part of the movement, was " +
                "${byNineTenths - byHalf} against $byHalf in the first half",
            byNineTenths - byHalf > byHalf * 0.35f,
        )
    }

    @Test
    fun `a finger's page comes over exactly as far as it is dragged`() {
        // Direct, so the motion belongs to the hand that made it: a fifth of a page is a fifth of the
        // turn, not a fraction of some clock that started on its own.
        assertEquals(0.8f, heldTurnProgress(0.2f), 0.0001f)
        assertEquals(HELD_TURN, heldTurnProgress(0f), 0.0001f)
        assertEquals(HELD_FLAT, heldTurnProgress(1f), 0.0001f)
    }

    @Test
    fun `a finger cannot drag a page past its own edges`() {
        assertEquals(HELD_FLAT, heldTurnProgress(1.4f), 0.0001f)
        assertEquals(HELD_TURN, heldTurnProgress(-0.3f), 0.0001f)
    }

    @Test
    fun `a drag has to go over before the track is handed over`() {
        assertTrue(
            "a page dragged a third past the mark should hand the track over",
            swipeHandsOver(
                draggedFraction = SWIPE_HAND_OVER_FRACTION,
                direction = TRACK_CHANGE_NEXT,
                canSkipNext = true,
                canSkipPrevious = true,
            ),
        )
        assertTrue(
            "a nudge should not skip a track",
            !swipeHandsOver(
                draggedFraction = SWIPE_HAND_OVER_FRACTION - 0.05f,
                direction = TRACK_CHANGE_NEXT,
                canSkipNext = true,
                canSkipPrevious = true,
            ),
        )
    }

    @Test
    fun `a swipe backwards a long way still only asks for the previous track`() {
        assertTrue(
            "dragging back should hand over to the previous track",
            swipeHandsOver(0.9f, TRACK_CHANGE_PREVIOUS, canSkipNext = false, canSkipPrevious = true),
        )
        assertTrue(
            "a queue with nothing before this track should take no swipe",
            !swipeHandsOver(0.9f, TRACK_CHANGE_PREVIOUS, canSkipNext = true, canSkipPrevious = false),
        )
        assertTrue(
            "a queue with nothing after this track should take no swipe either",
            !swipeHandsOver(0.9f, TRACK_CHANGE_NEXT, canSkipNext = false, canSkipPrevious = true),
        )
    }

    @Test
    fun `a handed-over turn carries on where the finger left it`() {
        // The page the finger carried a third of the way has also spent the first fifth of the turn's
        // time. Picking up there is what keeps one gesture to one motion: the flip the hand started
        // finishes, instead of starting again once the song has already changed.
        val handedOver = heldTurnProgress(SWIPE_HAND_OVER_FRACTION)
        assertEquals(
            "the turn should resume at the progress the finger left, was ${turnProgress(turnElapsedFor(handedOver))}",
            handedOver,
            turnProgress(turnElapsedFor(handedOver)),
            0.01f,
        )
        assertEquals("a turn nobody has carried starts at its first frame", 0L, turnElapsedFor(HELD_TURN))
        assertEquals(
            "a page already flat has nothing left to run",
            TRACK_CHANGE_TURN_MS,
            turnElapsedFor(HELD_FLAT),
        )
    }

    @Test
    fun `two swipes the same way do not share one turn-in`() {
        // The fault this guards: the turn-in was remembered by direction alone, so a second swipe the
        // same way never read its neighbour again and kept the first swipe's - and since the first
        // swipe had already handed the track over and moved the queue, that neighbour was the track
        // now playing. On screen the flap showed the artwork of the song already playing.
        assertNotEquals(
            "a new gesture has to read its own neighbour, even when the direction is unchanged",
            SwipeTurnInKey(gestureId = 1, direction = TRACK_CHANGE_NEXT),
            SwipeTurnInKey(gestureId = 2, direction = TRACK_CHANGE_NEXT),
        )
    }

    @Test
    fun `one swipe keeps its turn-in while it is under the finger`() {
        // The other half of the rule, and the reason the key is not just the gesture: within one
        // gesture the turn-in is fixed, because the hand-over moves the queue underneath it and
        // re-reading it would swap the layer to the following track's cover mid-turn.
        assertEquals(
            SwipeTurnInKey(gestureId = 7, direction = TRACK_CHANGE_PREVIOUS),
            SwipeTurnInKey(gestureId = 7, direction = TRACK_CHANGE_PREVIOUS),
        )
        assertNotEquals(
            "turning back the other way within one gesture is a different turn-in",
            SwipeTurnInKey(gestureId = 7, direction = TRACK_CHANGE_PREVIOUS),
            SwipeTurnInKey(gestureId = 7, direction = TRACK_CHANGE_NEXT),
        )
    }

    @Test
    fun `a turn starts turned away`() {
        // Reported to the readout, and reported wrong first: the panel drew the progress the page's
        // state held *before* the arrival began, so a skip read as "TURN, 0.00" - a page turning from
        // flat, which is the one thing a turn is not.
        assertEquals(1f, arrivalStartProgress(TrackChangeArrival.TURN, handedOver = null))
    }

    @Test
    fun `a turn a gesture handed over starts where the finger left it`() {
        assertEquals(0.65f, arrivalStartProgress(TrackChangeArrival.TURN, handedOver = 0.65f))
    }

    @Test
    fun `a fade and a page that is only placed start flat`() {
        // Neither is a turn: the cover is not turned away at any point of either.
        assertEquals(0f, arrivalStartProgress(TrackChangeArrival.FADE, handedOver = null))
        assertEquals(0f, arrivalStartProgress(TrackChangeArrival.SETTLE, handedOver = null))
    }

    @Test
    fun `the turn comes to rest rather than stopping dead`() {
        // No step at the end: the last frames of the turn have to move a little less than the ones
        // before them, or the page lands with a jolt.
        val last = turnProgress(TRACK_CHANGE_TURN_MS * 39 / 40)
        val earlier = turnProgress(TRACK_CHANGE_TURN_MS * 38 / 40)
        assertTrue(
            "the page should be decelerating into flat, was $earlier then $last",
            last < earlier && last >= 0f,
        )
    }
}
