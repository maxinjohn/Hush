/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.innertube

import okhttp3.OkHttpClient

/**
 * Rebuilds [OkHttpClient] instances when DNS, IP version, or related network settings change.
 */
class VersionedOkHttpClient(
    private val versionProvider: () -> Int,
    private val baseBuilder: () -> OkHttpClient.Builder,
) {
    @Volatile
    private var cached: Pair<Int, OkHttpClient>? = null

    fun get(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient {
        val version = versionProvider()
        cached?.let { (cachedVersion, client) ->
            if (cachedVersion == version) return client
        }
        return synchronized(this) {
            cached?.let { (cachedVersion, client) ->
                if (cachedVersion == version) return client
            }
            val client =
                baseBuilder()
                    .apply(configure)
                    .build()
            cached = version to client
            client
        }
    }

    fun invalidate() {
        cached = null
    }
}
