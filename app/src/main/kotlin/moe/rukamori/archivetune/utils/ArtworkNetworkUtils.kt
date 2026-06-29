/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.innertube.models.YouTubeClient
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request

object ArtworkNetworkUtils {
    private val youtubeArtworkHostSuffixes =
        listOf(
            "googleusercontent.com",
            "ggpht.com",
            "ytimg.com",
            "youtube.com",
            "youtube-nocookie.com",
        )

    fun isYouTubeArtworkHost(host: String): Boolean =
        youtubeArtworkHostSuffixes.any { host.endsWith(it) }

    fun applyRequestHeaders(
        builder: Request.Builder,
        url: HttpUrl,
    ): Request.Builder {
        if (!isYouTubeArtworkHost(url.host)) {
            return builder
        }

        val requestProfile = StreamClientUtils.resolveRequestProfile(url)
        if (requestProfile.referer != null) {
            return StreamClientUtils.applyRequestProfile(builder, requestProfile)
        }

        return builder
            .header("User-Agent", YouTubeClient.USER_AGENT_WEB_REMIX)
            .header("Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
            .header("Referer", YouTubeClient.REFERER_YOUTUBE_MUSIC)
    }

    fun artworkInterceptor(): Interceptor =
        Interceptor { chain ->
            val request = chain.request()
            chain.proceed(
                applyRequestHeaders(
                    request.newBuilder(),
                    request.url,
                ).build(),
            )
        }
}
