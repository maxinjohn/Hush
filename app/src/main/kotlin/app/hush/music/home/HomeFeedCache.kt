/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The last home feed the server sent, kept so the next launch can paint it before the network answers.
 *
 * The feed was the one screen in the app with no copy of itself: every launch asked YouTube for
 * `FEmusic_home` and drew nothing from it until the answer arrived. Measured on the reporting
 * device, that request returns in about 2.6 seconds on a good connection, and the local shelves
 * (quick picks, keep listening) paint before it - which is why a launch showed *some* of the screen
 * and then filled in seconds later, and why on a slower network the whole feed was a spinner for
 * long enough to read as "the app is slow".
 *
 * What is kept is the server's own JSON, not a copy of the parsed feed. Restoring therefore runs the
 * same parser the live request runs ([app.hush.music.innertube.YouTube.homePageFromJson]), so the
 * endpoints, thumbnails and continuation tokens are the ones the server actually sent. A
 * re-modelled cache would be a second definition of the feed, and the first field it forgot would be
 * a row that renders and cannot be played.
 *
 * Deliberately not a "is this fresh" cache: a stale shelf is more useful than an empty screen, and
 * the live request replaces it within seconds of the launch. The age only bounds how far back it can
 * reach, so a device that has not opened Hush for a month does not present a month-old feed as
 * today's.
 */
@Singleton
class HomeFeedCache
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private companion object {
            /** How old a kept feed may be and still be painted. */
            val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)

            /**
             * How long a kept feed is trusted *without asking the server again*.
             *
             * Two different questions, and they used to be one. [MAX_AGE_MS] answers "may this be
             * painted at all" - a week-old feed is better than an empty screen. This answers "is a
             * request needed", and every launch used to answer yes: the feed was fetched on every
             * cold start even though the kept copy was minutes old, so the network round trip was
             * spent re-drawing the same shelves and the first seconds after launch were spent
             * competing with a request the user had not asked for. A feed's shelves do not change
             * in a few minutes, and the pull-to-refresh gesture and the home refresh action both
             * force a request when the user actually wants one.
             */
            val FRESH_FOR_MS = TimeUnit.MINUTES.toMillis(20)
        }

        private val file: File
            get() = File(context.filesDir, "home/feed.json")

        /**
         * The kept feed, parsed once.
         *
         * The file is ~400 KB of JSON and parsing it is the expensive half of restoring it - a few
         * hundred milliseconds on a debug build, well under a tenth of that with R8 and a profile. So
         * it is parsed as early as possible ([warm], from the application's own background work)
         * rather than when the home screen looks for it, and handed over from memory after that.
         */
        @Volatile
        private var ready: app.hush.music.innertube.pages.HomePage? = null

        /**
         * One parse at a time.
         *
         * Two callers race here by design - the application warms the feed while the process is
         * still starting and the home view model asks for it a second later - and without this the
         * slower caller would parse the same 400 KB a second time, doubling the very work the warm
         * exists to get out of the launch window.
         */
        private val warmLock = kotlinx.coroutines.sync.Mutex()

        /** Parses the kept feed ahead of the screen asking for it; safe to call more than once. */
        suspend fun warm() {
            if (ready != null) return
            warmLock.withLock {
                if (ready != null) return
                val json = read() ?: return
                ready = app.hush.music.innertube.YouTube.homePageFromJson(json).getOrNull()
            }
        }

        /** The parsed kept feed, when [warm] has finished; null before that or when there is none. */
        fun readyPage(): app.hush.music.innertube.pages.HomePage? = ready

        /**
         * Whether the kept feed is recent enough to be shown *instead of* asking again.
         *
         * Deliberately about the file's own timestamp rather than [ready]: the parsed copy is only
         * the parse, and a feed parsed from a week-old file is still a week old.
         */
        suspend fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
            withContext(Dispatchers.IO) {
                val cached = file
                if (!cached.isFile) return@withContext false
                val age = nowMs - cached.lastModified()
                age in 0..FRESH_FOR_MS
            }

        /** The kept feed's JSON, or null when there is none or it is too old to trust. */
        suspend fun read(): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val cached = file
                    if (!cached.isFile) return@runCatching null
                    val age = System.currentTimeMillis() - cached.lastModified()
                    if (age > MAX_AGE_MS) return@runCatching null
                    cached.readText().takeIf { it.isNotBlank() }
                }.getOrNull()
            }

        /**
         * Keeps [json] as the feed to paint next time.
         *
         * Written to a temporary file and renamed over the real one: a process killed mid-write
         * would otherwise leave half a JSON document behind, and the next launch would read it,
         * fail to parse it and conclude the cache is useless - which is the one launch it was
         * written for. Failures are silent by design: not being able to keep the feed is not a
         * reason to fail the refresh that produced it.
         */
        suspend fun store(
            json: String,
            page: app.hush.music.innertube.pages.HomePage,
        ) =
            withContext(Dispatchers.IO) {
                // The parsed copy the caller just built, not a second parse: the refresh already has
                // the feed and a kept copy is only worth having if keeping it is cheaper than
                // fetching it again.
                ready = page
                runCatching {
                    val target = file
                    target.parentFile?.mkdirs()
                    val temp = File(target.parentFile, "${target.name}.tmp")
                    temp.writeText(json)
                    if (!temp.renameTo(target)) {
                        target.writeText(json)
                        temp.delete()
                    }
                }
            }

        /** Forgets the kept feed, for an account switch where the old one is simply wrong. */
        suspend fun clear() =
            withContext(Dispatchers.IO) {
                runCatching { file.delete() }
            }
    }
