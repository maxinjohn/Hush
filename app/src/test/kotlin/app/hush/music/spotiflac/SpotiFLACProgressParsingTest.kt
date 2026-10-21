/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.ui.player.CodecRowState
import app.hush.music.ui.player.codecRowState
import app.hush.music.utils.PlaybackDownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payloads below are the exact shape the bundled Go runtime emits from
 * `waitForMultiProgressDelta` (see go_backend/progress.go: MultiProgressDelta).
 */
class SpotiFLACProgressParsingTest {
    private fun parse(raw: String) =
        parseSpotiFLACProgressDelta(
            raw = raw,
            itemId = "hush-abc123",
            mediaId = "abc123",
            sourceId = "deezer",
        )

    @Test
    fun `empty payload means nothing changed`() {
        assertNull(parse(""))
        assertNull(parse("   "))
    }

    @Test
    fun `download progress is mapped from the runtime item`() {
        val raw =
            """
            {"seq":42,"reset":true,"items":{"hush-abc123":{"item_id":"hush-abc123",
            "bytes_total":25006347,"bytes_received":12503173,"progress":0.5,
            "speed_mbps":8.24,"is_downloading":true,"status":"downloading"}}}
            """.trimIndent()

        val delta = parse(raw)
        assertNotNull(delta)
        assertEquals(42L, delta!!.seq)

        val progress = delta.progress
        assertNotNull(progress)
        assertEquals(50, progress!!.percent)
        assertEquals(12_503_173L, progress.bytesReceived)
        assertEquals(25_006_347L, progress.bytesTotal)
        assertEquals("deezer", progress.sourceId)
        assertEquals("abc123", progress.mediaId)
        assertEquals("downloading", progress.status)
    }

    @Test
    fun `stage and missing numbers degrade gracefully`() {
        val delta = parse("""{"seq":7,"items":{"hush-abc123":{"stage":"resolving_stream"}}}""")
        assertEquals(7L, delta!!.seq)
        assertEquals(0, delta.progress!!.percent)
        assertEquals(0L, delta.progress!!.bytesTotal)
        assertEquals(0.0, delta.progress!!.speedMbps, 0.0001)
        assertEquals("resolving_stream", delta.progress!!.stage)
    }

    @Test
    fun `percent is clamped even if the runtime overshoots`() {
        assertEquals(100, parse("""{"seq":1,"items":{"hush-abc123":{"progress":1.4}}}""")!!.progress!!.percent)
        assertEquals(0, parse("""{"seq":1,"items":{"hush-abc123":{"progress":-3}}}""")!!.progress!!.percent)
    }

    @Test
    fun `other items never move the player progress`() {
        val raw = """{"seq":9,"items":{"hush-other":{"progress":0.8,"bytes_total":10}}}"""
        val delta = parse(raw)
        assertEquals(9L, delta!!.seq)
        assertNull(delta.progress)
    }

    @Test
    fun `a delta about another item is not liveness for this one`() {
        // The bug this pins: the sequence advances for other transfers too, so feeding
        // the stall meter on any delta held a wedged provider open until the 25s ceiling
        // rather than the 8s stall timeout. Observed on device with amazon and soundcloud
        // on a track neither ever progressed on.
        val foreign =
            parse(
                """{"seq":42,"items":{"hush-other":{"progress":1.0,"bytes_total":99}}}""",
            )
        assertEquals(42L, foreign!!.seq)
        assertFalse(foreign.isItemScoped)

        // A delta that names this item, including a bytes-free stage transition, is
        // liveness - otherwise a provider would be abandoned while it was answering.
        val ours = parse("""{"seq":43,"items":{"hush-abc123":{"stage":"resolving_stream"}}}""")
        assertTrue(ours!!.isItemScoped)
        assertEquals(43L, ours.seq)
        val downloading =
            parse(
                """{"seq":44,"items":{"hush-abc123":{"progress":0.5,"bytes_received":5}}}""",
            )
        assertTrue(downloading!!.isItemScoped)
    }

    @Test
    fun `a delta carrying no items at all is not liveness`() {
        val reset = parse("""{"seq":50,"reset":true}""")
        assertEquals(50L, reset!!.seq)
        assertFalse(reset.isItemScoped)
        assertNull(reset.progress)
    }

    @Test
    fun `malformed payloads are ignored instead of throwing`() {
        assertNull(parse("not json"))
        assertNull(parse("[]"))
    }

    //
    // What a source attempt says before its first byte. Measured on device: a sweep spent 5.4s in
    // `deezer` alone and published nothing, so the player's provenance row had nothing to draw for
    // the whole window and the track looked stuck at 0:00 with no explanation.
    //

    /** The attempt names the source, for the track it belongs to, with no bytes behind it. */
    @Test
    fun `an attempt names the source before any byte arrives`() {
        val attempt = attemptProgress(mediaId = "abc123", sourceId = "deezer", sourceLabel = "Deezer")
        assertEquals("abc123", attempt.mediaId)
        assertEquals("Deezer", attempt.sourceDisplayName)
        assertEquals(0, attempt.percent)
        assertEquals(0L, attempt.bytesTotal)
        assertEquals("resolving_stream", attempt.stage)
        // Which is what the row reads as "Fetching from Deezer…" rather than "Downloading 0%".
        assertEquals(CodecRowState.RESOLVING, attempt.codecRowState())
    }

    /** A source whose display name is unknown still gets named by its id, never left blank. */
    @Test
    fun `an attempt with no label falls back to the source id`() {
        val attempt = attemptProgress(mediaId = "abc123", sourceId = "qobuz-web", sourceLabel = null)
        assertEquals("qobuz-web", attempt.sourceDisplayName)
    }

    //
    // Which clear may wipe the shared progress signal. The lookahead's attempt for the *next*
    // track ends many times while the playing track is being fetched, and every one of those
    // endings used to blank the player's row.
    //

    /** An attempt for another track must not clear this one's line. */
    @Test
    fun `a clear for another track keeps the line`() {
        val playing = PlaybackDownloadProgress(mediaId = "playing", percent = 40, bytesTotal = 30_000_000L)
        assertFalse(clearMayEnd(playing, "next-track"))
        assertTrue(clearMayEnd(playing, "playing"))
    }

    /** The runtime's own unnamed transfer belongs to whatever is running, so it may clear. */
    @Test
    fun `an unnamed transfer may be cleared by anything`() {
        val unnamed = PlaybackDownloadProgress(mediaId = "", percent = 40, bytesTotal = 30_000_000L)
        assertTrue(clearMayEnd(unnamed, "whatever"))
    }

    /** A stop or a cancel ends the whole signal, and nothing is held back. */
    @Test
    fun `an unconditional clear always applies`() {
        val playing = PlaybackDownloadProgress(mediaId = "playing", percent = 40)
        assertTrue(clearMayEnd(playing, null))
        assertTrue(clearMayEnd(null, "playing"))
    }
}
