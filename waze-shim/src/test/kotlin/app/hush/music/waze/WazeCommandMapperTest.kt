package app.hush.music.waze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [WazeCommandMapper] — the Messenger message → Hush command translation.
 *
 * The Waze App Protocol sends transport commands via Messenger messages:
 * - what=1 with arg1=0 → pause
 * - what=1 with arg1!=0 → play
 * - what=2 → next
 * - what=3 → previous
 * - what=0 (init), what=99 (heartbeat), etc. → not transport commands
 */
class WazeCommandMapperTest {

    // ── Play/Pause (what=1) ──────────────────────────────────────────

    @Test
    fun `what=1 with arg1=0 maps to pause`() {
        assertEquals("pause", WazeCommandMapper.mapMessageToCommand(what = 1, arg1 = 0))
    }

    @Test
    fun `what=1 with arg1=1 maps to play`() {
        assertEquals("play", WazeCommandMapper.mapMessageToCommand(what = 1, arg1 = 1))
    }

    @Test
    fun `what=1 with arg1=2 maps to play`() {
        assertEquals("play", WazeCommandMapper.mapMessageToCommand(what = 1, arg1 = 2))
    }

    @Test
    fun `what=1 with negative arg1 maps to play`() {
        assertEquals("play", WazeCommandMapper.mapMessageToCommand(what = 1, arg1 = -1))
    }

    @Test
    fun `what=1 with large arg1 maps to play`() {
        assertEquals("play", WazeCommandMapper.mapMessageToCommand(what = 1, arg1 = 999))
    }

    // ── Next (what=2) ────────────────────────────────────────────────

    @Test
    fun `what=2 maps to next regardless of arg1`() {
        assertEquals("next", WazeCommandMapper.mapMessageToCommand(what = 2, arg1 = 0))
        assertEquals("next", WazeCommandMapper.mapMessageToCommand(what = 2, arg1 = 1))
        assertEquals("next", WazeCommandMapper.mapMessageToCommand(what = 2, arg1 = 42))
    }

    // ── Previous (what=3) ────────────────────────────────────────────

    @Test
    fun `what=3 maps to previous`() {
        assertEquals("previous", WazeCommandMapper.mapMessageToCommand(what = 3, arg1 = 0))
        assertEquals("previous", WazeCommandMapper.mapMessageToCommand(what = 3, arg1 = 1))
    }

    // ── Non-transport messages return null ────────────────────────────

    @Test
    fun `what=0 (init) returns null`() {
        assertNull(WazeCommandMapper.mapMessageToCommand(what = 0))
    }

    @Test
    fun `what=99 (heartbeat) returns null`() {
        assertNull(WazeCommandMapper.mapMessageToCommand(what = 99))
    }

    @Test
    fun `unknown what codes return null`() {
        assertNull(WazeCommandMapper.mapMessageToCommand(what = 4))
        assertNull(WazeCommandMapper.mapMessageToCommand(what = 10))
        assertNull(WazeCommandMapper.mapMessageToCommand(what = -1))
        assertNull(WazeCommandMapper.mapMessageToCommand(what = 200))
    }

    // ── Default arg1 ─────────────────────────────────────────────────

    @Test
    fun `what=1 with default arg1 (0) maps to pause`() {
        // Messenger messages default arg1 to 0, which is pause
        assertEquals("pause", WazeCommandMapper.mapMessageToCommand(what = 1))
    }

    // ── Transport command set ─────────────────────────────────────────

    @Test
    fun `TRANSPORT_COMMANDS contains all expected commands`() {
        val expected = setOf("play", "pause", "next", "previous")
        assertEquals(expected, WazeCommandMapper.TRANSPORT_COMMANDS)
    }

    @Test
    fun `all transport commands are produced by mapMessageToCommand`() {
        val produced = mutableSetOf<String>()
        for (what in 0..10) {
            for (arg1 in -1..2) {
                val cmd = WazeCommandMapper.mapMessageToCommand(what, arg1)
                if (cmd != null) produced.add(cmd)
            }
        }
        assertTrue("All TRANSPORT_COMMANDS should be producible", produced.containsAll(WazeCommandMapper.TRANSPORT_COMMANDS))
    }

    // ── What code constants ───────────────────────────────────────────

    @Test
    fun `what code constants match expected values`() {
        assertEquals(0, WazeCommandMapper.WHAT_INIT)
        assertEquals(1, WazeCommandMapper.WHAT_PLAY_PAUSE)
        assertEquals(2, WazeCommandMapper.WHAT_NEXT)
        assertEquals(3, WazeCommandMapper.WHAT_PREVIOUS)
        assertEquals(99, WazeCommandMapper.WHAT_HEARTBEAT)
    }
}
