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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.security.SecureRandom
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
        const val APP_VERSION = "4.9.6"

        /** 32 lowercase hex characters, the only shape the gateway accepts. */
        internal val INSTALL_ID_PATTERN = Regex("^[0-9a-f]{32}$")

        /**
         * The callback the gateway is willing to put in front of a solved challenge.
         *
         * Only the `spotiflac` scheme is reflected back into the page; any other scheme (including
         * the `hush://spotiflac-grant` this used to send) arrives as an empty `callbackUrl`, so the
         * page has no target and a browser-solved challenge can never return on its own.
         */
        internal fun callbackUrlFor(state: String): String =
            "spotiflac://session-grant?cb_version=v2grant&state=$state"

        /**
         * What the relay puts in its callback's `state`.
         *
         * The gateway issues a nonce with a challenge and matches the solved grant back through it,
         * so echoing the nonce is what lets a browser-solved challenge be completed. A literal
         * state (the placeholder this used to send for every challenge) carries nothing for the
         * gateway to match, which is a request it can only reject.
         */
        internal fun relayCallbackState(serverNonce: String?): String =
            serverNonce?.trim()?.takeIf { it.isNotEmpty() } ?: "spotiflac"
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

        /**
         * The gateway identity this install uses, without needing the DI instance.
         *
         * Prefs win while they exist; the vault is the fallback so a build that
         * runs before the manager is constructed (or after a data wipe the vault
         * survived) can still sign with the identity the gateway already trusts.
         */
        fun installIdOrNull(context: Context): String? {
            val stored = runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_INSTALL_ID, null)
            }.getOrNull()
            if (!stored.isNullOrBlank() && stored.matches(INSTALL_ID_PATTERN)) return stored
            return SpotiFLACSessionVault.storedInstallId(context)
                ?.takeIf { it.matches(INSTALL_ID_PATTERN) }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    var currentSession: SpotiFLACSession? = null
        private set

    /** The most recent Turnstile grant, retained only in memory for the native extension runtime. */
    @Volatile
    var currentGrant: String? = null
        private set

    private val _sessionState = MutableStateFlow(SessionState.NONE)
    val sessionStateFlow: StateFlow<SessionState> = _sessionState.asStateFlow()
    private val sessionOperationMutex = Mutex()

    private var installId: String = ""

    /**
     * 32-hex install identity exposed for the native SpotiFLAC runtime so its
     * signed-session files share the gateway install identity Hush uses.
     */
    val installIdForRuntime: String?
        get() {
            ensureInstallId()
            return installId.takeIf { it.matches(INSTALL_ID_PATTERN) }
        }

    private fun ensureInstallId() {
        if (installId.isBlank()) {
            installId = durableInstallId()
            prefs.edit().putString(KEY_INSTALL_ID, installId).apply()
        }
        SpotiFLACSessionVault.rememberInstallId(context, installId)
    }

    /**
     * The identity to keep using: prefs, then the vault, and only then a new one.
     *
     * Minting a fresh id is the expensive branch - the gateway has never seen it,
     * so it answers with a Cloudflare challenge - which is why the vault is
     * consulted first. That is what carries the identity through an app upgrade or
     * a restore the vault survived.
     */
    private fun durableInstallId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.matches(INSTALL_ID_PATTERN) }
            ?.let { return it }
        SpotiFLACSessionVault.storedInstallId(context)?.takeIf { it.matches(INSTALL_ID_PATTERN) }
            ?.let { id ->
                Timber.tag(TAG).i("Restored SpotiFLAC install id from the session vault")
                return id
            }
        return generateInstallId()
    }

    init {
        instance = this
        installId = durableInstallId()
        prefs.edit().putString(KEY_INSTALL_ID, installId).apply()
        SpotiFLACSessionVault.rememberInstallId(context, installId)
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

    /** Reset the install ID to bypass rate limiting from previous failed attempts. */
    fun resetInstallId() {
        val newId = generateInstallId()
        prefs.edit()
            .putString(KEY_INSTALL_ID, newId)
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_SECRET)
            .remove(KEY_SESSION_EXPIRES)
            .remove(KEY_CHALLENGE_URL)
            .remove(KEY_CHALLENGE_ID)
            .remove(KEY_SERVER_NONCE)
            .remove(KEY_TURNSTILE_SITE_KEY)
            .apply()
        installId = newId
        currentSession = null
        _sessionState.value = SessionState.NONE
        Timber.tag(TAG).w("Reset install ID to $newId")
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
        currentGrant = null
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

    /**
     * The live signed session, for seeding the native runtime's signed-session
     * records. The runtime validates these records locally before a download, so
     * an already-exchanged Hush session lets extensions download without running
     * their own Turnstile challenge.
     */
    val sessionForRuntimeSeeding: SpotiFLACSession?
        get() = currentSession?.takeIf { System.currentTimeMillis() < it.expiresAt }

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

        // A relay-level 401/403 is an explicit authentication rejection. Clear the
        // in-memory and persisted credentials so the next playback attempt can start
        // a fresh bootstrap/auth flow. Transient 404/server errors below deliberately
        // keep a valid session because they do not prove that the session is invalid.
        if (status == 401 || status == 403) {
            Timber.tag(TAG).w("Bootstrap auth failed ($status), clearing stale session")
            clearSession()
            return@runCatching SessionState.NONE
        }

        if (status == 404 || body.contains("Not found")) {
            Timber.tag(TAG).w("Bootstrap endpoint not available")
            // Don't clear a valid session just because bootstrap is unavailable
            if (currentSession == null) clearSession()
            return@runCatching SessionState.NONE
        }

        val bootstrapResponse = json.decodeFromString<BootstrapResponse>(body)

        if (bootstrapResponse.error != null) {
            Timber.tag(TAG).w("Bootstrap error: ${bootstrapResponse.error}")
            // Don't clear a valid session just because bootstrap errored
            if (currentSession == null) clearSession()
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

            // Use a Hush-specific callback scheme to avoid the official SpotiFLAC app
            // intercepting the redirect (both apps register for spotiflac://).
            val callbackUrl = callbackUrlFor(relayCallbackState(bootstrapResponse.serverNonce))
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
        currentGrant = grant.trim().takeIf { it.isNotBlank() }
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
            // The grant was refused, not the session. This request carries no session credentials
            // at all - only the grant, the install id and the client identity - so a 401/403 here
            // cannot be evidence that the stored session is invalid, and clearing it destroyed a
            // working session every time a grant belonged to another client.
            //
            // That is the field case this protects: on a device whose WebView cannot run
            // Cloudflare, the only challenge on screen is the extension runtime's, and its grant
            // is answered 403 by this exchange by construction. Clearing on it wiped the relay
            // session, re-raised every source's challenge and left the card showing the 403 no
            // matter how many times the check was solved.
            if (status == 401 || status == 403) {
                Timber.tag(TAG).w(
                    "Grant exchange refused ($status); keeping the stored session: ${body.take(120)}",
                )
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
        // The grant is single-use, so it cannot be replayed to the extension
        // runtime (the gateway answers HTTP 403). Instead, seed the runtime's
        // signed-session records from this freshly exchanged session so extension
        // downloads pass their local preflight without a second Turnstile.
        seedRuntimeSessionsInBackground()
        SessionState.ACTIVE
        }
    }

    private fun seedRuntimeSessionsInBackground() {
        val runtime = runCatching { SpotiFLACNativeRuntimeBridgeHolder.instance }.getOrNull() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                runtime.seedSignedSessions(
                    ExtensionRepositoryManager.getInstance().getEnabledSourceIds(),
                )
            }
        }
    }

    fun signRequest(
        method: String,
        path: String,
        body: String = "",
        appVersionOverride: String? = null,
        platformOverride: String? = null,
    ): Map<String, String> {
        val session = currentSession ?: throw SpotiFLACException("No active session")
        val signingAppVersion = appVersionOverride?.trim().takeUnless { it.isNullOrBlank() } ?: APP_VERSION
        val signingPlatform = platformOverride?.trim().takeUnless { it.isNullOrBlank() } ?: PLATFORM

        // The scheme itself lives in SpotiFLACRequestSigner so the session renewer
        // signs identically; a second copy of this is how signatures drift.
        val headers = SpotiFLACRequestSigner.signedHeaders(
            method = method,
            path = path,
            body = body,
            sessionId = session.sessionId,
            sessionSecret = session.sessionSecret,
            appVersion = signingAppVersion,
            platform = signingPlatform,
            schemeLabel = SCHEME_LABEL,
            headerPrefix = HEADER_PREFIX,
            timeWindowSeconds = TIME_WINDOW_SECONDS,
        )
        android.util.Log.w(TAG, "=== SIGNING ${method.uppercase()} $path ===")
        android.util.Log.w(TAG, "  session_id=${session.sessionId} secret_len=${session.sessionSecret.length}")
        android.util.Log.w(TAG, "  timestamp=${headers["${HEADER_PREFIX}Timestamp"]} nonce=${headers["${HEADER_PREFIX}Nonce"]}")
        android.util.Log.w(TAG, "  signature=${headers["${HEADER_PREFIX}Signature"]?.take(24)}...")
        return headers
    }

    fun getSignedHeaders(
        method: String,
        path: String,
        body: String = "",
        appVersionOverride: String? = null,
        platformOverride: String? = null,
    ): Map<String, String> {
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
            signRequest(method, path, body, appVersionOverride, platformOverride)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to sign request")
            throw SpotiFLACException("Request signing failed: ${e.message}")
        }
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
