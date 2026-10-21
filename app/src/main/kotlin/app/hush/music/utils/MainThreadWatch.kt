/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.hush.music.BuildConfig

/**
 * What the UI thread is doing while a launch is still going on, sampled into logcat.
 *
 * The device says it plainly enough that the app skipped two hundred frames while the home feed was
 * being built, but not *who* was holding the thread that should have drawn them: `Choreographer`
 * counts the frames it lost, and the platform's own thread dump goes to `/data/anr`, which a
 * sideloaded app cannot read. This samples the thread instead - a stack snapshot every [intervalMs]
 * for [durationMs], one logcat line each - so a stall can be read back as the frames the app was
 * running at the time rather than guessed at from whichever log line happened to be near it.
 *
 * Debug only, and never started by the release build: sampling a thread hundreds of times per launch
 * is work in itself, and it exists to answer a question about a build, not to run in one.
 */
object MainThreadWatch {
    private const val TAG = "HushMainSample"

    /** Frames of the stack that are printed per sample; enough to name the caller of the work. */
    private const val STACK_DEPTH = 14

    fun start(durationMs: Long = 12_000, intervalMs: Long = 120) {
        if (!BuildConfig.DEBUG) return
        val main = Looper.getMainLooper().thread
        val thread =
            Thread(
                {
                    val until = SystemClock.elapsedRealtime() + durationMs
                    while (SystemClock.elapsedRealtime() < until) {
                        val stamp = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                        val stack =
                            runCatching { main.stackTrace }.getOrDefault(emptyArray())
                        val rendered =
                            if (stack.isEmpty()) {
                                "(no stack)"
                            } else {
                                stack
                                    .take(STACK_DEPTH)
                                    .joinToString(" < ") {
                                        "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                                    }
                            }
                        runCatching { Log.i(TAG, "@${stamp}ms $rendered") }
                        SystemClock.sleep(intervalMs)
                    }
                },
                "hush-main-watch",
            )
        thread.isDaemon = true
        thread.start()
    }
}
