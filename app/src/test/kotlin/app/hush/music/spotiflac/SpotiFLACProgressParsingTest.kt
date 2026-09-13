/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

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
}
