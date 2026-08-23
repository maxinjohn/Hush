package app.hush.music.spotiflac

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class BootstrapResponse(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("session_secret") val sessionSecret: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("challenge_url") val challengeUrl: String? = null,
    @SerialName("auth_url") val authUrl: String? = null,
    @SerialName("turnstile_site_key") val turnstileSiteKey: String? = null,
    @SerialName("server_nonce") val serverNonce: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
private data class ExchangeRequest(
    @SerialName("grant") val grant: String,
    @SerialName("install_id") val installId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("platform") val platform: String,
)

@Serializable
private data class ExchangeResponse(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("session_secret") val sessionSecret: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("error") val error: String? = null,
)

data class SpotiFLACSession(
    val sessionId: String,
    val sessionSecret: String,
    val expiresAt: Long,
)

enum class SessionState {
    NONE,
    BOOTSTRAP_REQUIRED,
    CHALLENGE_PENDING,
    ACTIVE,
    EXPIRED,
    ERROR,
}

@Singleton
class SpotiFLACSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
) {
    companion object {
        private const val TAG = "SpotiFLACSession"
        private const val PREFS_NAME = "spotiflac_session"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_SECRET = "session_secret"
        private const val KEY_SESSION_EXPIRES = "session_expires"
        private const val KEY_CHALLENGE_URL = "challenge_url"
        private const val KEY_CHALLENGE_ID = "challenge_id"
        private const val KEY_TURNSTILE_SITE_KEY = "turnstile_site_key"
        private const val KEY_SERVER_NONCE = "server_nonce"
        const val APP_VERSION = "4.8.5"
        private const val PLATFORM = "extension"
        private const val SCHEME_LABEL = "ZARZ-HMAC-V1"
        private const val HEADER_PREFIX = "X-Zarz-"
        private const val TIME_WINDOW_SECONDS = 300L
        const val BASE_URL = "https://api.zarz.moe/v2"

        @Volatile
        private var instance: SpotiFLACSessionManager? = null

        fun getInstance(): SpotiFLACSessionManager {
            return instance
                ?: throw IllegalStateException("SpotiFLACSessionManager not initialized")
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    var currentSession: SpotiFLACSession? = null
        private set

    private val _sessionState = MutableStateFlow(SessionState.NONE)
    val sessionStateFlow: StateFlow<SessionState> = _sessionState.asStateFlow()
    private val sessionOperationMutex = Mutex()

    private var installId: String = ""

    init {
        instance = this
        installId = prefs.getString(KEY_INSTALL_ID, null) ?: generateInstallId()
        prefs.edit().putString(KEY_INSTALL_ID, installId).apply()
        restoreSession()
    }

    val sessionState: SessionState
        get() {
            val session = currentSession ?: return SessionState.NONE
            val now = System.currentTimeMillis()
            if (now >= session.expiresAt) return SessionState.EXPIRED
            return SessionState.ACTIVE
        }

    val challengeUrl: String?
        get() = prefs.getString(KEY_CHALLENGE_URL, null)

    val turnstileSiteKey: String?
        get() = prefs.getString(KEY_TURNSTILE_SITE_KEY, null)

    val challengeId: String?
        get() = prefs.getString(KEY_CHALLENGE_ID, null)

    val serverNonce: String?
        get() = prefs.getString(KEY_SERVER_NONCE, null)

    private fun generateInstallId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun restoreSession() {
        val sessionId = prefs.getString(KEY_SESSION_ID, null)
        val sessionSecret = prefs.getString(KEY_SESSION_SECRET, null)
        val expiresAt = prefs.getLong(KEY_SESSION_EXPIRES, 0)

        // Always clear invalid/expired sessions to allow re-authentication
        if (sessionId != null && sessionSecret != null && System.currentTimeMillis() < expiresAt) {
            currentSession = SpotiFLACSession(sessionId, sessionSecret, expiresAt)
            _sessionState.value = SessionState.ACTIVE
            Timber.tag(TAG).d("Restored session: $sessionId (expires ${java.time.Instant.ofEpochMilli(expiresAt)})")
        } else {
            // Invalid/expired session - clear it to allow fresh bootstrap
            Timber.tag(TAG).d("No valid saved session, clearing to allow re-bootstrap")
            clearSession()
        }
    }

    private fun saveSession(session: SpotiFLACSession) {
        prefs.edit()
            .putString(KEY_SESSION_ID, session.sessionId)
            .putString(KEY_SESSION_SECRET, session.sessionSecret)
            .putLong(KEY_SESSION_EXPIRES, session.expiresAt)
            .apply()
        currentSession = session
        _sessionState.value = SessionState.ACTIVE
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_SECRET)
            .remove(KEY_SESSION_EXPIRES)
            .remove(KEY_CHALLENGE_URL)
            .remove(KEY_CHALLENGE_ID)
            .remove(KEY_TURNSTILE_SITE_KEY)
            .remove(KEY_SERVER_NONCE)
            .apply()
        currentSession = null
        _sessionState.value = SessionState.NONE
    }

    fun hasActiveSession(): Boolean {
        return currentSession != null && System.currentTimeMillis() < (currentSession?.expiresAt ?: 0)
    }

    fun forceRestoreSession(): Boolean {
        restoreSession()
        return currentSession != null
    }



    suspend fun bootstrap(): Result<SessionState> = sessionOperationMutex.withLock {
        runCatching {
        Timber.tag(TAG).d("Bootstrapping session (install_id=$installId)")

        val url = "$BASE_URL/bootstrap?install_id=$installId&app_version=$APP_VERSION"
        val response = httpClient.get(url) {
            header("User-Agent", "SpotiFLAC-Mobile/$APP_VERSION")
            header("Accept", "application/json")
        }
        val body = response.bodyAsText()
        val status = response.status.value
        Timber.tag(TAG).d("Bootstrap response: status=$status, body=${body.take(500)}")

        if (body.contains("<html") || body.contains("Just a moment")) {
            throw SpotiFLACException("Relay returned Cloudflare challenge instead of bootstrap (HTTP $status). Try again later.")
        }

        // 401/403 means the existing session is invalid — clear it so re-bootstrap can start fresh
        if (status == 401 || status == 403) {
            Timber.tag(TAG).w("Bootstrap auth failed ($status), clearing stale session")
            clearSession()
            return@runCatching SessionState.NONE
        }

        if (status == 404 || body.contains("Not found")) {
            Timber.tag(TAG).w("Bootstrap endpoint not available")
            clearSession()
            return@runCatching SessionState.NONE
        }

        val bootstrapResponse = json.decodeFromString<BootstrapResponse>(body)

        if (bootstrapResponse.error != null) {
            Timber.tag(TAG).w("Bootstrap error: ${bootstrapResponse.error}")
            clearSession()
            return@runCatching SessionState.NONE
        }

        if (!bootstrapResponse.sessionId.isNullOrBlank() && !bootstrapResponse.sessionSecret.isNullOrBlank()) {
            val expiresAt = parseExpiresAt(bootstrapResponse.expiresAt)
            val session = SpotiFLACSession(
                sessionId = bootstrapResponse.sessionId,
                sessionSecret = bootstrapResponse.sessionSecret,
                expiresAt = expiresAt,
            )
            saveSession(session)
            Timber.tag(TAG).d("Session obtained directly: ${session.sessionId}")
            return@runCatching SessionState.ACTIVE
        }

        if (!bootstrapResponse.challengeId.isNullOrBlank()) {
            prefs.edit().putString(KEY_CHALLENGE_ID, bootstrapResponse.challengeId).apply()
            if (!bootstrapResponse.serverNonce.isNullOrBlank()) {
                prefs.edit().putString(KEY_SERVER_NONCE, bootstrapResponse.serverNonce).apply()
            }
            if (!bootstrapResponse.turnstileSiteKey.isNullOrBlank()) {
                prefs.edit().putString(KEY_TURNSTILE_SITE_KEY, bootstrapResponse.turnstileSiteKey).apply()
            }

            val callbackUrl = "spotiflac://session-grant?cb_version=v2grant&state=spotiflac"
            val encodedCallback = java.net.URLEncoder.encode(callbackUrl, "UTF-8")
                .replace("+", "%20")
            val challengeUrl = "$BASE_URL/challenge?id=${bootstrapResponse.challengeId}&cb=$encodedCallback"
            prefs.edit().putString(KEY_CHALLENGE_URL, challengeUrl).apply()
            Timber.tag(TAG).d("Turnstile challenge required: $challengeUrl")
            _sessionState.value = SessionState.CHALLENGE_PENDING
            return@runCatching SessionState.CHALLENGE_PENDING
        }

        val challengeUrl = bootstrapResponse.challengeUrl ?: bootstrapResponse.authUrl
        if (!challengeUrl.isNullOrBlank()) {
            prefs.edit().putString(KEY_CHALLENGE_URL, challengeUrl).apply()
            Timber.tag(TAG).d("Challenge required: $challengeUrl")
            _sessionState.value = SessionState.CHALLENGE_PENDING
            return@runCatching SessionState.CHALLENGE_PENDING
        }

        Timber.tag(TAG).w("Bootstrap returned neither session nor challenge")
        clearSession()
        return@runCatching SessionState.NONE
        }
    }

    suspend fun exchangeGrant(grant: String): Result<SessionState> = sessionOperationMutex.withLock {
        runCatching {
        Timber.tag(TAG).d("Exchanging grant (grant_len=${grant.length})")

        val url = "$BASE_URL/session/exchange"
        val requestBody = json.encodeToString(
            ExchangeRequest.serializer(),
            ExchangeRequest(
                grant = grant,
                installId = installId,
                appVersion = APP_VERSION,
                platform = PLATFORM,
            ),
        )
        Timber.tag(TAG).d("Exchange POST $url")

        val response = httpClient.post(url) {
            header("User-Agent", "SpotiFLAC-Mobile/$APP_VERSION")
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val body = response.bodyAsText()
        val status = response.status.value
        Timber.tag(TAG).d("Exchange response: status=$status, body=${body.take(500)}")

        if (body.contains("<html") || body.contains("Just a moment")) {
            throw SpotiFLACException("Relay returned Cloudflare challenge instead of session (HTTP $status). Try again later.")
        }

        if (status < 200 || status >= 300) {
            // Auth failures mean the current session is invalid — clear so next attempt starts fresh
            if (status == 401 || status == 403) {
                Timber.tag(TAG).w("Exchange auth failed ($status), clearing session")
                clearSession()
            }
            throw SpotiFLACException("Exchange failed with HTTP $status: ${body.take(200)}")
        }

        val exchangeResponse = json.decodeFromString<ExchangeResponse>(body)

        if (exchangeResponse.error != null) {
            clearSession()
            throw SpotiFLACException("Exchange error: ${exchangeResponse.error}")
        }

        if (exchangeResponse.sessionId.isNullOrBlank() || exchangeResponse.sessionSecret.isNullOrBlank()) {
            throw SpotiFLACException("Exchange returned no session credentials")
        }

        val expiresAt = parseExpiresAt(exchangeResponse.expiresAt)
        val session = SpotiFLACSession(
            sessionId = exchangeResponse.sessionId,
            sessionSecret = exchangeResponse.sessionSecret,
            expiresAt = expiresAt,
        )
        saveSession(session)
        prefs.edit().remove(KEY_CHALLENGE_URL).apply()
        prefs.edit().remove(KEY_CHALLENGE_ID).apply()
        prefs.edit().remove(KEY_SERVER_NONCE).apply()
        prefs.edit().remove(KEY_TURNSTILE_SITE_KEY).apply()
        val saved = prefs.getString(KEY_SESSION_ID, null)
        Timber.tag(TAG).d("Session obtained via exchange: id=${session.sessionId}, expires=${java.time.Instant.ofEpochMilli(session.expiresAt)}, persisted=$saved")
        if (saved.isNullOrBlank()) {
            Timber.tag(TAG).e("Session save verification FAILED — SharedPreferences did not persist")
        }
        SessionState.ACTIVE
        }
    }

    fun signRequest(
        method: String,
        path: String,
        body: String = "",
    ): Map<String, String> {
        val session = currentSession ?: throw SpotiFLACException("No active session")

        // Go uses Format("2006-01-02T15:04:05.000Z") — exactly 3 decimal places
        // Server parses with time.Parse("2006-01-02T15:04:05.000Z", ts)
        // Instant.now().toString() produces nanoseconds which breaks server parsing
        val timestamp = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())
        val nonce = generateNonce()
        val bodySha256 = sha256Hex(body)

        val window = System.currentTimeMillis() / 1000 / TIME_WINDOW_SECONDS
        val rollingInput = "$window:${session.sessionId}"
        // Go: hmacSHA256Bytes([]byte(record.SessionSecret), []byte(rollingInput))
        // Session secret is used as raw string bytes, NOT base64-decoded
        val sessionSecretRawBytes = session.sessionSecret.toByteArray(Charsets.UTF_8)
        val rollingKeyBytes = hmacSha256(sessionSecretRawBytes, rollingInput.toByteArray())
        // Go: rk = base64.RawURLEncoding.EncodeToString(...)
        // Rolling key is base64url-encoded, then []byte(rk) is used for signature HMAC
        val rollingKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rollingKeyBytes)

        val pathOnly = path.substringBefore("?")
        // Go: strings.Join([]string{..., path, "", bodyHash, ...}, "\n")
        // Empty string in the slice creates an empty line between path and bodyHash
        val signingInput = buildString {
            append(SCHEME_LABEL)
            append("\n")
            append(method.uppercase())
            append("\n")
            append(pathOnly)
            append("\n\n") // Empty line after path (Go "" in strings.Join)
            append(bodySha256)
            append("\n")
            append(timestamp)
            append("\n")
            append(nonce)
            append("\n")
            append(session.sessionId)
            append("\n")
            append(APP_VERSION)
            append("\n")
            append(PLATFORM)
        }

        // Go: hmacSHA256Bytes([]byte(rk), []byte(signingInput))
        // rk is the base64url string, []byte(rk) uses its ASCII bytes
        val signature = hmacSha256(
            rollingKey.toByteArray(Charsets.UTF_8),
            signingInput.toByteArray(),
        )
        val signatureBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature)

        android.util.Log.w(TAG, "=== SIGNING ${method.uppercase()} $path ===")
        android.util.Log.w(TAG, "  session_id=${session.sessionId} secret_len=${session.sessionSecret.length}")
        android.util.Log.w(TAG, "  window=$window pathOnly=$pathOnly")
        android.util.Log.w(TAG, "  timestamp=$timestamp nonce=$nonce")
        android.util.Log.w(TAG, "  signature=${signatureBase64.take(24)}...")

        val headers = mapOf(
            "${HEADER_PREFIX}Session" to session.sessionId,
            "${HEADER_PREFIX}Timestamp" to timestamp,
            "${HEADER_PREFIX}Nonce" to nonce,
            "${HEADER_PREFIX}Body-SHA256" to bodySha256,
            "${HEADER_PREFIX}Signature" to signatureBase64,
            "${HEADER_PREFIX}App-Version" to APP_VERSION,
            "${HEADER_PREFIX}Platform" to PLATFORM,
        )
        return headers
    }

    fun getSignedHeaders(method: String, path: String, body: String = ""): Map<String, String> {
        val session = currentSession
        if (session == null) {
            Timber.tag(TAG).w("getSignedHeaders called with no active session")
            throw SpotiFLACException("No active SpotiFLAC session — authenticate first")
        }
        // Session exists but is expired — clear it so bootstrap can start fresh
        if (System.currentTimeMillis() >= session.expiresAt) {
            Timber.tag(TAG).w("Session expired (expiresAt=${java.time.Instant.ofEpochMilli(session.expiresAt)}), clearing")
            clearSession()
            throw SpotiFLACException("SpotiFLAC session expired — re-bootstrap required")
        }
        return try {
            signRequest(method, path, body)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to sign request")
            throw SpotiFLACException("Request signing failed: ${e.message}")
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun parseExpiresAt(expiresAt: String?): Long {
        if (expiresAt.isNullOrBlank()) {
            Timber.tag(TAG).w("No expires_at provided, defaulting to 1 hour")
            return System.currentTimeMillis() + 3600_000
        }
        return try {
            java.time.Instant.parse(expiresAt).toEpochMilli()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse expires_at: $expiresAt, defaulting to 1 hour")
            System.currentTimeMillis() + 3600_000
        }
    }
}
