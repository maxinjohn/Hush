/*
 * ArchiveTune (2026)
 * (c) Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.moriextractor

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.concurrent.TimeUnit

class StreamingExtractionManager(
    baseUrl: String = BackendBaseUrl,
    private val bearerToken: String,
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    private val httpClient =
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(20, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
        }

    private val _extractorState = MutableStateFlow<ExtractorState>(ExtractorState.Idle)
    val extractorState: StateFlow<ExtractorState> = _extractorState

    suspend fun extractAudioUrl(
        videoUrl: String,
        audioQuality: ExtractorAudioQuality = ExtractorAudioQuality.AUTO,
    ): String =
        withContext(Dispatchers.IO) {
            val normalizedVideoUrl = videoUrl.trim()
            if (normalizedVideoUrl.isBlank()) {
                throw ArchiveTuneExtractorException("Video URL is missing")
            }

            val token = bearerToken.trim()
            if (token.isBlank()) {
                throw ArchiveTuneExtractorException("ArchiveTune Extractor token is missing")
            }

            _extractorState.value = ExtractorState.Processing
            try {
                val raw =
                    httpClient
                        .get("$normalizedBaseUrl/api/extract") {
                            header("Authorization", "Bearer $token")
                            parameter("url", normalizedVideoUrl)
                            parameter("quality", audioQuality.apiValue)
                            parameter("format", "bestaudio")
                        }.bodyAsText()
                val response = json.decodeFromString(BackendExtractorResponse.serializer(), raw)
                val audioUrl = response.playableUrl.orEmpty()
                if (response.success && audioUrl.isNotBlank() && audioUrl.isHttpUrl()) {
                    _extractorState.value =
                        ExtractorState.Success(
                            audioUrl = audioUrl,
                            title = response.title,
                            thumbnail = response.thumbnail,
                        )
                    audioUrl
                } else {
                    val message =
                        response.error
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: "ArchiveTune Extractor returned no playable audio stream"
                    _extractorState.value = ExtractorState.Error(message)
                    throw ArchiveTuneExtractorException(message)
                }
            } catch (cancellation: CancellationException) {
                _extractorState.value = ExtractorState.Idle
                throw cancellation
            } catch (throwable: Throwable) {
                val exception =
                    throwable as? ArchiveTuneExtractorException
                        ?: ArchiveTuneExtractorException("ArchiveTune Extractor request failed", throwable)
                _extractorState.value = ExtractorState.Error(exception.message.orEmpty())
                throw exception
            }
        }

    private fun String.isHttpUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    private companion object {
        const val BackendBaseUrl = "https://extractor.koiiverse.cloud"
    }
}

class ArchiveTuneExtractorException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
