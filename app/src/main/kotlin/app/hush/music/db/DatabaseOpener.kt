/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.db

import android.content.Context
import android.os.SystemClock
import timber.log.Timber
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Builds the music database on a thread of its own, so a launch never waits for it.
 *
 * `InternalDatabase.newInstance` used to open the database before returning - forcing
 * `writableDatabase` so a corrupt or half-migrated file could be repaired before the app saw it -
 * and that happened on whichever thread asked Hilt for the singleton: `MainActivity`'s injection in
 * `onCreate`, i.e. the main thread, before the content view existed. 39-41 ms measured on the NE2211,
 * and on a fresh install the entire open, ~175 ms, because injection follows `App.onCreate` by only
 * milliseconds.
 *
 * Two things changed. [InternalDatabase.newInstance] now builds the database without opening it, so
 * Room opens the file on its first use - on the query executor, off the main thread - and a launch
 * does not wait for it at all; the repair moved into that open path, in `RepairingOpenHelperFactory`,
 * where it still runs before any query. And this object builds the instance itself, started from
 * `App.onCreate`, so even the construction is off the main thread and the provider's wait is a
 * handful of milliseconds at worst.
 *
 * Cost of prefetching: a process start that never touches the database - a boot receiver, say - now
 * builds one anyway. Building is cheap and touches no disk; the file is still only opened on use.
 */
internal object DatabaseOpener {
    private val lock = Any()

    @Volatile
    private var open: FutureTask<MusicDatabase>? = null

    /**
     * Starts the open if it has not been started yet and returns immediately. Called from
     * `App.onCreate`; safe to call from anywhere, any number of times.
     */
    fun start(context: Context) {
        task(context)
    }

    /**
     * The database, building it first if [start] has not run yet (an entry point that never went
     * through `App.onCreate`, or a second process). Blocks only for the remainder of a build that is
     * already in flight, which is milliseconds and touches no disk.
     */
    fun await(context: Context): MusicDatabase {
        val future = task(context)
        val startedAt = SystemClock.elapsedRealtime()
        val database =
            try {
                future.get()
            } catch (e: ExecutionException) {
                // Unwrap, so a caller sees the failure the build would have thrown on its own thread.
                throw e.cause ?: e
            }
        val waited = SystemClock.elapsedRealtime() - startedAt
        if (waited > 0) {
            Timber.d("Waited ${waited}ms for the database on ${Thread.currentThread().name}")
        }
        return database
    }

    /**
     * The single in-flight (or finished) open, starting it on first call. Publishing the task and
     * starting the thread under one lock is what keeps `start` and `await` from racing each other
     * into two opens or into no task at all.
     */
    private fun task(context: Context): FutureTask<MusicDatabase> =
        synchronized(lock) {
            open?.let { return it }
            val appContext = context.applicationContext
            val created =
                FutureTask {
                val startedAt = SystemClock.elapsedRealtime()
                val database = InternalDatabase.newInstance(appContext)
                Timber.d(
                    "Database built in ${SystemClock.elapsedRealtime() - startedAt}ms on ${Thread.currentThread().name}",
                )
                database
                }
            open = created
            Thread(created, "hush-database-open").apply { isDaemon = true }.start()
            created
        }
}
