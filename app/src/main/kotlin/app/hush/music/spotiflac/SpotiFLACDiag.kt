/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded on-disk diagnostic for the SpotiFLAC playback pipeline.
 *
 * Hush routes Timber to an in-memory buffer, so field debugging of source
 * routing is otherwise blind. This writes a small rolling log to
 * `files/spotiflac/diag.log` (last [MAX_LINES] lines) that developers can pull
 * with `adb shell run-as <pkg> cat files/spotiflac/diag.log`.
 */
object SpotiFLACDiag {
    private const val TAG = "SpotiFLACDiag"
    private const val FILE_NAME = "diag.log"
    private const val MAX_LINES = 400

    @Volatile private var appContext: Context? = null
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val context = appContext ?: return
        synchronized(lock) {
            runCatching {
                val dir = File(context.filesDir, "spotiflac").apply { mkdirs() }
                val file = File(dir, FILE_NAME)
                val line = "${timeFormat.format(Date())} ${message.take(500)}"
                val existing = if (file.isFile) file.readLines().takeLast(MAX_LINES - 1) else emptyList()
                file.writeText((existing + line).joinToString("\n"))
            }
        }
    }
}
