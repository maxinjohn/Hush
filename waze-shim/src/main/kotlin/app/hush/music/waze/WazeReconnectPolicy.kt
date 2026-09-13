package app.hush.music.waze

/**
 * Framework-free reconnect policy for the Waze SdkService Messenger binding.
 *
 * The service intentionally keeps retrying after the nominal attempt budget:
 * Waze can be stopped for an arbitrary amount of time while the shim remains
 * alive, so a permanent give-up would leave the bridge silently disconnected.
 */
object WazeReconnectPolicy {
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L
    const val MAX_RECONNECT_ATTEMPTS = 10

    /** Exponential backoff for the first attempts, capped at 30 seconds. */
    fun delayMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be positive" }
        val exponent = (attempt - 1).coerceAtMost(5)
        return minOf(BASE_DELAY_MS * (1L shl exponent), MAX_DELAY_MS)
    }

    /** The service should keep scheduling retries even after the normal budget. */
    fun isLongRunningRetry(attempt: Int): Boolean = attempt > MAX_RECONNECT_ATTEMPTS
}
