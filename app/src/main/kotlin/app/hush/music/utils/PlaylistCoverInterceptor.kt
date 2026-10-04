/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.CancellationException
import app.hush.music.repository.PlaylistCoverRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/**
 * Refreshes playlist covers whose signed ytimg URL has expired.
 *
 * YouTube Music playlist artwork is served from `i.ytimg.com/pl_c/<playlistId>/...` with
 * URLs that lapse; a stale URL stored in the playlist row keeps failing with 4xx while the
 * cover on YouTube's side is perfectly fine. When a load of such a URL errors, this asks
 * [PlaylistCoverRepository] for the playlist's current cover, persists it over the expired
 * row, and retries the image load against the fresh URL.
 */
class PlaylistCoverInterceptor
    @Inject
    constructor(
        private val repository: PlaylistCoverRepository,
    ) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            val result = chain.proceed()
            if (result !is ErrorResult || !request.networkCachePolicy.readEnabled) return result

            val statusCode = (result.throwable as? HttpException)?.response?.code ?: return result
            val sourceUrl =
                when (val data = request.data) {
                    is String -> data
                    is android.net.Uri -> data.toString()
                    is coil3.Uri -> data.toString()
                    else -> return result
                }
            val playlistId = refreshedPlaylistId(statusCode, sourceUrl) ?: return result

            return try {
                val refreshedUrl = repository.refreshRemoteCover(playlistId, sourceUrl)
                if (refreshedUrl == sourceUrl) return result

                val refreshedRequest =
                    request.newBuilder()
                        .data(refreshedUrl)
                        .memoryCacheKey(refreshedUrl)
                        .diskCacheKey(refreshedUrl)
                        .build()
                chain.withRequest(refreshedRequest).proceed()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                ErrorResult(
                    image = result.image,
                    request = request,
                    throwable = exception,
                )
            }
        }
    }

/**
 * Returns the playlist id to re-fetch when an image load failed with [statusCode], or null
 * when this URL is not a refreshable playlist cover (wrong status, host, or path shape).
 */
internal fun refreshedPlaylistId(
    statusCode: Int,
    sourceUrl: String,
): String? {
    if (statusCode != 400 && statusCode != 401 && statusCode != 403 && statusCode != 404 && statusCode != 410) {
        return null
    }
    val url = sourceUrl.toHttpUrlOrNull() ?: return null
    if (url.host != "ytimg.com" && !url.host.endsWith(".ytimg.com")) return null
    val path = url.pathSegments
    if (path.size != 3 || path[0] != "pl_c" || path[1].isBlank()) return null
    return path[1]
}
