/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.BitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import androidx.core.net.toUri
import moe.rukamori.archivetune.ui.utils.deriveYouTubeThumbnailUrl
import kotlin.math.roundToInt

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                if (data.isEmpty()) {
                    throw IllegalArgumentException("Empty image data")
                }

                BitmapFactory.decodeByteArray(data, 0, data.size)?.also { bitmap ->
                    return@future bitmap
                }

                throw IllegalStateException("Could not decode image data")
            } catch (e: Exception) {
                reportException(e)
                return@future createBitmap(64, 64)
            }
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            val density = context.resources.displayMetrics.density
            val maxIconSizePx = (density * 256f).roundToInt().coerceIn(256, 1024)
            val candidateUrls =
                buildArtworkLoadCandidates(uri).mapNotNull { candidate ->
                    candidate.toString().trim().takeIf { it.isNotBlank() }
                }
            val attempts = 3
            for (candidateUrl in candidateUrls) {
                NotificationArtworkLoader.loadBitmap(candidateUrl, maxIconSizePx)?.let { bitmap ->
                    return@future bitmap
                }

                for (attempt in 1..attempts) {
                    try {
                        val request =
                            ImageRequest
                                .Builder(context)
                                .data(candidateUrl)
                                .allowHardware(false)
                                .size(maxIconSizePx, maxIconSizePx)
                                .build()

                        val result = context.imageLoader.execute(request)

                        when (result) {
                            is SuccessResult -> {
                                try {
                                    val bitmap = result.image.toBitmap()
                                    val scaled = scaleBitmap(bitmap, maxIconSizePx)
                                    if (scaled != null) {
                                        return@future scaled.copy(Bitmap.Config.ARGB_8888, false)
                                    }
                                } catch (e: Exception) {
                                    reportException(e)
                                }
                            }

                            is ErrorResult -> {
                                result.throwable?.let { reportException(it) }
                            }
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }

                    if (attempt < attempts) {
                        delay(250L * attempt)
                    }
                }
            }
            createBitmap(64, 64)
        }

    private fun scaleBitmap(
        bitmap: Bitmap,
        maxIconSizePx: Int,
    ): Bitmap? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        if (bitmap.width <= maxIconSizePx && bitmap.height <= maxIconSizePx) return bitmap
        val scale =
            minOf(
                maxIconSizePx.toFloat() / bitmap.width.toFloat(),
                maxIconSizePx.toFloat() / bitmap.height.toFloat(),
            )
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun buildArtworkLoadCandidates(uri: Uri): List<Uri> {
        val candidates = linkedSetOf<Uri>()
        candidates += uri
        val uriString = uri.toString().trim()
        if (uriString.isNotBlank()) {
            candidates += uriString.toUri()
        }
        extractYouTubeVideoId(uriString)?.let { videoId ->
            listOf(
                "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            ).forEach { fallbackUrl ->
                candidates += fallbackUrl.toUri()
            }
        }
        return candidates.toList()
    }

    private fun extractYouTubeVideoId(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return trimmed
        val uriMatch = Regex("""(?:youtube\.com/watch\?.*v=|youtu\.be/|/vi/)([a-zA-Z0-9_-]{11})""").find(trimmed)
        if (uriMatch != null) return uriMatch.groupValues[1]
        return trimmed.deriveYouTubeThumbnailUrl()?.substringAfter("/vi/")?.substringBefore("/")
    }
}
