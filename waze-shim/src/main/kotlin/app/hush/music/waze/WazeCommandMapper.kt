package app.hush.music.waze

/**
 * Maps Waze App Protocol Messenger messages to Hush command strings.
 *
 * The Waze SDK communicates via the Messenger protocol:
 * - what=0: Init/handshake (handled separately)
 * - what=1: Play/pause (arg1: 0=pause, else=play)
 * - what=2: Next/skip
 * - what=3: Previous
 * - what=99: Heartbeat
 *
 * This mapping is isolated here so it can be unit-tested
 * without requiring Android framework dependencies.
 */
object WazeCommandMapper {

    const val WHAT_INIT = 0
    const val WHAT_PLAY_PAUSE = 1
    const val WHAT_NEXT = 2
    const val WHAT_PREVIOUS = 3
    const val WHAT_HEARTBEAT = 99

    /**
     * Translate a Waze Messenger [what] code and [arg1] to a Hush command string.
     *
     * @return The command string (e.g. "play", "pause", "next", "previous"),
     *         or `null` if the message is not a transport command
     *         (init, heartbeat, unknown).
     */
    fun mapMessageToCommand(what: Int, arg1: Int = 0): String? = when (what) {
        // Messenger messages default arg1 to 0 when not present;
        // arg1=0 → pause, anything else → play
        WHAT_PLAY_PAUSE -> if (arg1 == 0) "pause" else "play"
        WHAT_NEXT -> "next"
        WHAT_PREVIOUS -> "previous"
        else -> null // init, heartbeat, unknown — not transport commands
    }

    /**
     * All valid Hush Waze command strings that [mapMessageToCommand] can produce.
     */
    val TRANSPORT_COMMANDS = setOf("play", "pause", "next", "previous")
}
