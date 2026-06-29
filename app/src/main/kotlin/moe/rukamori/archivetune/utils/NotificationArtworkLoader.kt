/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.innertube.VersionedOkHttpClient
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.ui.utils.deriveYouTubeThumbnailUrl
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.ui.utils.resolvePlaybackArtworkUrl
import moe.rukamori.archivetune.ui.utils.toValidArtworkUrl
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val NotificationArtworkUserAgent =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

object NotificationArtworkLoader {
    private val httpClientHolder =
        VersionedOkHttpClient(
            versionProvider = YouTube::okHttpNetworkVersion,
            baseBuilder = YouTube::newOkHttpClientBuilder,
        )

    fun resolveArtworkUrl(mediaItem: MediaItem): String? {
        mediaItem.metadata?.resolvePlaybackArtworkUrl()?.let { return it }
        mediaItem.mediaMetadata.artworkUri
            ?.toString()
            ?.toValidArtworkUrl()
            ?.highRes()
            ?.let { return it }
        return mediaItem.mediaId.deriveYouTubeThumbnailUrl()?.highRes()
    }

    fun buildArtworkUrlCandidates(primaryUrl: String): List<String> {
        val candidates = linkedSetOf<String>()
        primaryUrl.trim().takeIf { it.isNotBlank() }?.let { candidates += it }
        extractYouTubeVideoId(primaryUrl)?.let { videoId ->
            listOf(
                "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            ).forEach { candidates += it }
        }
        return candidates.toList()
    }

    fun loadBitmap(
        url: String,
        maxSizePx: Int,
    ): Bitmap? {
        for (candidate in buildArtworkUrlCandidates(url)) {
            loadBitmapFromUrl(candidate, maxSizePx)?.let { return it }
        }
        return null
    }

    fun bitmapToArtworkData(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        return output.toByteArray()
    }

    fun mediaItemWithEmbeddedArtwork(
        mediaItem: MediaItem,
        bitmap: Bitmap,
        artworkUrl: String?,
    ): MediaItem {
        val metadataBuilder = mediaItem.mediaMetadata.buildUpon()
        if (artworkUrl != null) {
            metadataBuilder.setArtworkUri(artworkUrl.toUri())
        }
        metadataBuilder.setArtworkData(
            bitmapToArtworkData(bitmap),
            MediaMetadata.PICTURE_TYPE_FRONT_COVER,
        )
        return mediaItem.buildUpon().setMediaMetadata(metadataBuilder.build()).build()
    }

    private fun loadBitmapFromUrl(
        url: String,
        maxSizePx: Int,
    ): Bitmap? {
        return runCatching {
            val httpUrl =
                Request
                    .Builder()
                    .url(url)
                    .build()
                    .url
            val request =
                ArtworkNetworkUtils
                    .applyRequestHeaders(
                        Request
                            .Builder()
                            .url(url)
                            .header("User-Agent", NotificationArtworkUserAgent)
                            .header("Accept", "image/*,*/*;q=0.8"),
                        httpUrl,
                    ).build()
            httpClientHolder
                .get {
                    connectTimeout(12, TimeUnit.SECONDS)
                    readTimeout(12, TimeUnit.SECONDS)
                    followRedirects(true)
                    followSslRedirects(true)
                }.newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.bytes()?.takeIf { it.isNotEmpty() } ?: return@use null
                    val decoded = BitmapFactory.decodeByteArray(body, 0, body.size) ?: return@use null
                    scaleBitmap(decoded, maxSizePx)?.copy(Bitmap.Config.ARGB_8888, false)
                }
        }.getOrNull()
    }

    private fun scaleBitmap(
        bitmap: Bitmap,
        maxSizePx: Int,
    ): Bitmap? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        if (bitmap.width <= maxSizePx && bitmap.height <= maxSizePx) return bitmap
        val scale =
            minOf(
                maxSizePx.toFloat() / bitmap.width.toFloat(),
                maxSizePx.toFloat() / bitmap.height.toFloat(),
            )
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun extractYouTubeVideoId(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return trimmed
        val uriMatch =
            Regex("""(?:youtube\.com/watch\?.*v=|youtu\.be/|/vi/)([a-zA-Z0-9_-]{11})""")
                .find(trimmed)
        if (uriMatch != null) return uriMatch.groupValues[1]
        return trimmed.deriveYouTubeThumbnailUrl()?.substringAfter("/vi/")?.substringBefore("/")
    }
}
