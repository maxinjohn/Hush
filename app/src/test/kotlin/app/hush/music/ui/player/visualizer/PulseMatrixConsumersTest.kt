package app.hush.music.ui.player.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry exists because the engine used to count consumers, and a count cannot tell
 * whose release it is processing. These tests pin the two cases a screen transition
 * produces.
 */
class PulseMatrixConsumersTest {
    @Test
    fun `the mini player arriving before the full player leaves keeps one live consumer`() {
        val consumers = PulseMatrixConsumers()

        val fullPlayer = consumers.register()
        val miniPlayer = consumers.register()
        assertEquals(2, consumers.count)

        // The departing screen releases its own registration after the arriving screen
        // registered. With a counter this landed on zero and stopped the visualiser.
        fullPlayer.release()
        assertEquals(1, consumers.count)
        assertTrue(consumers.isNotEmpty)

        miniPlayer.release()
        assertTrue(consumers.isEmpty)
    }

    @Test
    fun `a release is spent once, so a repeated one cannot end someone else's registration`() {
        val consumers = PulseMatrixConsumers()

        val stale = consumers.register()
        val live = consumers.register()

        stale.release()
        stale.release() // e.g. a finally block after an explicit release
        stale.release()
        assertEquals(1, consumers.count)

        live.release()
        assertTrue(consumers.isEmpty)
    }

    @Test
    fun `a token that was never handed out changes nothing`() {
        val consumers = PulseMatrixConsumers()
        val live = consumers.register()

        assertFalse(consumers.release(id = 999L))

        assertEquals(1, consumers.count)
        live.release() // the live registration is untouched by the stray release
        assertTrue(consumers.isEmpty)
    }

    @Test
    fun `forceRelease clears every registration at once`() {
        val consumers = PulseMatrixConsumers()
        repeat(3) { consumers.register() }
        assertEquals(3, consumers.count)

        consumers.clear()

        assertTrue(consumers.isEmpty)
    }

    @Test
    fun `every release is reported to the engine, once each`() {
        val remaining = mutableListOf<Int>()
        val consumers = PulseMatrixConsumers { remaining += it }

        val fullPlayer = consumers.register()
        val miniPlayer = consumers.register()
        fullPlayer.release()
        miniPlayer.release()
        miniPlayer.release() // a finally block after an explicit release

        assertEquals(listOf(1, 0), remaining)
    }

    @Test
    fun `tokens are distinct`() {
        val consumers = PulseMatrixConsumers()
        val ids = List(50) { consumers.register().id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
