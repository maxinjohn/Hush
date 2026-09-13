package app.hush.music.waze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeReconnectPolicyTest {
    @Test
    fun `backoff starts at one second and doubles`() {
        assertEquals(1_000L, WazeReconnectPolicy.delayMs(1))
        assertEquals(2_000L, WazeReconnectPolicy.delayMs(2))
        assertEquals(4_000L, WazeReconnectPolicy.delayMs(3))
        assertEquals(8_000L, WazeReconnectPolicy.delayMs(4))
        assertEquals(16_000L, WazeReconnectPolicy.delayMs(5))
    }

    @Test
    fun `backoff is capped at thirty seconds`() {
        assertEquals(30_000L, WazeReconnectPolicy.delayMs(6))
        assertEquals(30_000L, WazeReconnectPolicy.delayMs(10))
        assertEquals(30_000L, WazeReconnectPolicy.delayMs(11))
    }

    @Test
    fun `normal attempts are not long running retries`() {
        assertFalse(WazeReconnectPolicy.isLongRunningRetry(1))
        assertFalse(WazeReconnectPolicy.isLongRunningRetry(WazeReconnectPolicy.MAX_RECONNECT_ATTEMPTS))
    }

    @Test
    fun `retries continue after the nominal attempt budget`() {
        assertTrue(WazeReconnectPolicy.isLongRunningRetry(WazeReconnectPolicy.MAX_RECONNECT_ATTEMPTS + 1))
        assertTrue(WazeReconnectPolicy.isLongRunningRetry(100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero is not a valid reconnect attempt`() {
        WazeReconnectPolicy.delayMs(0)
    }
}
