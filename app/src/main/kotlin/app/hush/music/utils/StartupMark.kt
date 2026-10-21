/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.hush.music.BuildConfig

/**
 * One line per launch milestone, printed with the time since the process started.
 *
 * Hush routes Timber into an in-memory buffer, which is the right place for a log a user can read
 * and the wrong place for a launch timeline: by the time anyone can open the log screen, the launch
 * is over, and the buffer holds whatever ran *after* the feed appeared. This writes to logcat with a
 * tag of its own instead, so `adb logcat -s HushStartup` answers the one question a slow start
 * raises - which part of the launch the waiting was spent in - from a cold start on a real device.
 *
 * Debug only on purpose: the marks are for measuring, and a release build has no logcat to measure
 * into. Nothing here runs unless [BuildConfig.DEBUG], and [mark] is a no-op when it is not.
 */
object StartupMark {
    private const val TAG = "HushStartup"

    private val enabled: Boolean = BuildConfig.DEBUG

    /** Milliseconds since this process was started, which is what every mark is stamped with. */
    private fun sinceProcessStart(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    /** Records that [milestone] has happened, with the time since the process started. */
    fun mark(milestone: String) {
        if (!enabled) return
        runCatching { Log.i(TAG, "$milestone at ${sinceProcessStart()}ms") }
    }
}
