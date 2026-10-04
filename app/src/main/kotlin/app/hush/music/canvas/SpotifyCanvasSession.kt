/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.canvas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.hush.music.constants.SpotifySpDcKey
import app.hush.music.constants.SpotifySpKeyKey
import app.hush.music.spotify.Spotify
import app.hush.music.spotify.SpotifyAuth
import app.hush.music.utils.dataStore
import java.io.IOException

/**
 * Access-token + client-id session for the Spotify canvas provider.
 *
 * The canvas endpoints need both values together: the canvaz-cache API authenticates with the
 * web-player access token, and the client-token handshake needs the client id that arrives in
 * the same token response. Hush persists only the token, so a fresh one is fetched here (TOTP
 * handshake, three small requests) and kept in memory until shortly before it expires.
 */
object SpotifyCanvasSession {
    private const val TOKEN_MARGIN_MS = 30_000L

    data class Session(
        val accessToken: String,
        val clientId: String,
        val expiresAtMs: Long,
    )

    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: Session? = null

    @Volatile
    private var cachedCredential: String? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** Whether a Spotify account with stored cookies is connected. */
    suspend fun isConnected(): Boolean {
        val context = appContext ?: return false
        return runCatching {
            context.dataStore.data.first()[SpotifySpDcKey].orEmpty().isNotBlank()
        }.getOrDefault(false)
    }

    suspend fun <T> withSession(block: suspend (Session) -> T): T =
        withContext(Dispatchers.IO) {
            val context = appContext ?: throw IOException("Spotify canvas session is not initialized")
            mutex.withLock {
                val prefs = context.dataStore.data.first()
                val spDc = prefs[SpotifySpDcKey].orEmpty()
                if (spDc.isBlank()) throw IOException("Spotify is not connected")
                val spKey = prefs[SpotifySpKeyKey].orEmpty()
                if (cachedCredential != spDc) {
                    cached = null
                    cachedCredential = spDc
                }

                for (attempt in 0..1) {
                    val session =
                        cached
                            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() + TOKEN_MARGIN_MS }
                            ?: fetchSession(spDc = spDc, spKey = spKey).also { cached = it }
                    Spotify.accessToken = session.accessToken
                    try {
                        return@withLock block(session)
                    } catch (error: SpotifyCanvasProvider.RequestException) {
                        // A token that went stale between refreshes gets exactly one retry.
                        if (error.statusCode != 401 || attempt > 0) throw error
                        cached = null
                    }
                }

                throw IOException("Spotify canvas session could not be established")
            }
        }

    private suspend fun fetchSession(
        spDc: String,
        spKey: String,
    ): Session {
        val token = SpotifyAuth.fetchAccessToken(spDc = spDc, spKey = spKey).getOrThrow()
        if (token.accessToken.isBlank() || token.clientId.isBlank()) {
            throw IOException("Spotify token response is missing canvas credentials")
        }
        return Session(
            accessToken = token.accessToken,
            clientId = token.clientId,
            expiresAtMs = token.accessTokenExpirationTimestampMs,
        )
    }
}
