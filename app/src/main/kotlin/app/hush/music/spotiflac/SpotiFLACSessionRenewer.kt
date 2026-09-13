package app.hush.music.spotiflac

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Keeps the native runtime's per-extension gateway sessions alive without a human.
 *
 * Each SpotiFLAC download extension holds its *own* signed session, and the Go
 * runtime only renews one while it is still valid: `signedSessionRefreshDue`
 * requires `now < expires_at`, and once a record is expired the runtime clears it
 * and raises a Cloudflare challenge. So a session that is allowed to lapse always
 * costs the user a manual verification.
 *
 * The gateway's `POST {base}/session/refresh` endpoint renews a session from its
 * `install_id` with no Turnstile, signed with the *record's own* secret exactly
 * like the runtime signs its requests. This class performs that call ahead of
 * expiry — from app start, from background work, and on demand — which is what
 * turns verification into a one-time step instead of a recurring chore.
 */
object SpotiFLACSessionRenewer {

    private const val TAG = "SpotiFLACRenew"

    /**
     * Renew once this much validity is left. The runtime's own window is one hour;
     * renewing earlier leaves room for a few missed background runs (doze, no
     * network) before a session would actually expire.
     */
    const val RENEW_WINDOW_SECONDS = 3 * 3600L

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 20_000L
    private const val DEFAULT_REFRESH_PATH = "/session/refresh"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The gateway sits behind Cloudflare and rejects non-browser-ish clients: a
     * plain `HttpURLConnection` request is answered with a bare
     * `403 {"error":"Forbidden"}` *before* signature validation. OkHttp is the
     * stack Hush already uses successfully against this same host, so renewal
     * goes through it too.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(READ_TIMEOUT_MS + CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** One extension's signed-session config plus its current record, if any. */
    data class ExtensionSession(
        val extensionId: String,
        val displayName: String?,
        val namespace: String,
        val appVersion: String,
        val expiresAtMillis: Long?,
        val hasSession: Boolean,
        val recordFile: File,
    ) {
        val isExpired: Boolean
            get() = expiresAtMillis?.let { it <= System.currentTimeMillis() } ?: false

        val remainingSeconds: Long?
            get() = expiresAtMillis?.let { (it - System.currentTimeMillis()) / 1000 }
    }

    data class RenewResult(
        val extensionId: String,
        val renewed: Boolean,
        val detail: String,
        val expiresAtMillis: Long? = null,
    )

    /** Every extracted download extension that declares a signed session. */
    fun sessions(context: Context): List<ExtensionSession> {
        val root = extensionsDir(context)
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> sessionFor(context, dir) }.sortedBy { it.extensionId }
    }

    /** Sessions that are already expired, i.e. will demand a manual verification. */
    fun expired(context: Context): List<ExtensionSession> = sessions(context).filter { it.isExpired }

    /** How long a gateway refusal is remembered before trying that source again. */
    private const val REJECTED_RETRY_MS = 6 * 3600_000L

    private fun rejectionPrefs(context: Context) =
        context.getSharedPreferences("spotiflac_session_renew", Context.MODE_PRIVATE)

    private fun rejectionKey(extensionId: String) = "rejected_$extensionId"

    private fun markRenewalRejected(context: Context, extensionId: String, sessionId: String) {
        runCatching {
            rejectionPrefs(context)
                .edit()
                .putString(
                    rejectionKey(extensionId),
                    "$sessionId|${System.currentTimeMillis() + REJECTED_RETRY_MS}",
                )
                .apply()
        }
    }

    /** When renewal was last refused for this exact session generation, else 0. */
    private fun renewalRejectedUntil(context: Context, extensionId: String, sessionId: String): Long =
        runCatching {
            val stored = rejectionPrefs(context).getString(rejectionKey(extensionId), null)
            if (!renewalRejectionActive(stored, sessionId)) return 0L
            parseRenewalRejection(stored)?.second ?: 0L
        }.getOrDefault(0L)

    /** `sessionId|untilMillis`, or null when the stored value is unusable. */
    internal fun parseRenewalRejection(stored: String?): Pair<String, Long>? {
        if (stored.isNullOrBlank()) return null
        val parts = stored.split("|", limit = 2)
        if (parts.size != 2) return null
        val until = parts[1].toLongOrNull() ?: return null
        if (parts[0].isBlank()) return null
        return parts[0] to until
    }

    /**
     * True while a refusal still applies. A re-verified source stores a new session
     * id, which clears the refusal so renewal is attempted again immediately.
     */
    internal fun renewalRejectionActive(stored: String?, sessionId: String): Boolean {
        val parsed = parseRenewalRejection(stored) ?: return false
        return parsed.first == sessionId && parsed.second > System.currentTimeMillis()
    }

    /**
     * Renews every session that is inside [RENEW_WINDOW_SECONDS] of expiry.
     *
     * @param force renew regardless of remaining validity (manual "refresh now").
     */
    fun renewAll(
        context: Context,
        force: Boolean = false,
        reason: String = "scheduled",
    ): List<RenewResult> {
        val candidates = sessions(context)
        if (candidates.isEmpty()) {
            SpotiFLACDiag.log("session renew ($reason): no extension sessions found")
            return emptyList()
        }
        val results = candidates.map { renew(context, it, force) }
        val renewed = results.count { it.renewed }
        SpotiFLACDiag.log(
            "session renew ($reason): renewed=$renewed of ${results.size} [" +
                results.joinToString(", ") { "${it.extensionId}:${it.detail}" } + "]",
        )
        return results
    }

    private fun renew(context: Context, session: ExtensionSession, force: Boolean): RenewResult {
        if (!session.hasSession) {
            return RenewResult(session.extensionId, false, "no session to renew")
        }

        val remaining = session.remainingSeconds
        if (!renewalDue(remaining, force)) {
            val minutes = remaining?.let { it / 60 }
            return RenewResult(
                session.extensionId,
                false,
                if (minutes != null) "not due (${minutes}m left)" else "not due",
                session.expiresAtMillis,
            )
        }

        val record = runCatching { readRecord(session.recordFile) }.getOrNull()
            ?: return RenewResult(session.extensionId, false, "unreadable session record")
        val sessionId = record["session_id"].orEmpty()
        val sessionSecret = record["session_secret"].orEmpty()
        val installId = record["install_id"].orEmpty()
        if (sessionId.isEmpty() || sessionSecret.isEmpty() || installId.isEmpty()) {
            return RenewResult(session.extensionId, false, "incomplete session record")
        }

        val rejectedUntil = renewalRejectedUntil(context, session.extensionId, sessionId)
        if (rejectedUntil > System.currentTimeMillis()) {
            val minutes = (rejectedUntil - System.currentTimeMillis()) / 60_000
            return RenewResult(
                session.extensionId,
                false,
                "gateway refuses renewal (retry in ${minutes}m)",
                session.expiresAtMillis,
            )
        }

        val refreshed = runCatching {
            requestRefresh(context, session, record)
        }.getOrElse { error ->
            SpotiFLACDiag.log("session renew failed id=${session.extensionId} ${error.message}")
            // Some sources (amazon, for one) are refused by the gateway outright.
            // Remember the refusal against this session generation so every app
            // start does not re-hammer the endpoint or spam the log; a freshly
            // verified session has a new id and is tried again immediately.
            if (error.message?.contains("403") == true) {
                markRenewalRejected(context, session.extensionId, sessionId)
            }
            return RenewResult(session.extensionId, false, "request failed: ${error.message}")
        }

        val newExpiry = refreshed["expires_at"]?.let(::parseExpiryMillis)
        if (refreshed.isEmpty()) {
            return RenewResult(session.extensionId, false, "gateway returned no session", newExpiry)
        }

        return runCatching {
            val applied = applyRefresh(session, record, refreshed)
            if (applied) {
                SpotiFLACDiag.log(
                    "session renewed id=${session.extensionId} expires=${refreshed["expires_at"]}",
                )
                Timber.tag(TAG).i("Renewed SpotiFLAC session for %s", session.extensionId)
                RenewResult(session.extensionId, true, "renewed", newExpiry)
            } else {
                RenewResult(session.extensionId, false, "superseded by a newer session", newExpiry)
            }
        }.getOrElse { error ->
            RenewResult(session.extensionId, false, "write failed: ${error.message}")
        }
    }

    /**
     * POSTs the gateway's refresh endpoint signed with the record's own secret.
     * Returns the session fields the gateway sent back (empty when it accepted the
     * call but had nothing to rotate).
     */
    private fun requestRefresh(
        context: Context,
        session: ExtensionSession,
        record: Map<String, String>,
    ): Map<String, String> {
        val config = signedSessionConfigOf(context, session.extensionId) ?: return emptyMap()
        val body = buildJsonObject { put("install_id", record["install_id"].orEmpty()) }.toString()
        val url = config.baseUrl.trimEnd('/') + "/" + config.refreshPath.trimStart('/')
        // The signature covers the path of the *resolved* URL. The runtime builds
        // the request with url.ResolveReference and signs parsed.EscapedPath(), so
        // with a base of https://host/v2 the signed path is /v2/session/refresh —
        // signing the relative /session/refresh is rejected as Forbidden.
        val signedPath = runCatching { java.net.URI(url).path }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: config.refreshPath
        val headers = SpotiFLACRequestSigner.signedHeaders(
            method = "POST",
            path = signedPath,
            body = body,
            sessionId = record["session_id"].orEmpty(),
            sessionSecret = record["session_secret"].orEmpty(),
            appVersion = config.appVersion,
            platform = config.platform,
            schemeLabel = config.schemeLabel,
            headerPrefix = config.headerPrefix,
            timeWindowSeconds = config.timeWindowSeconds,
        )

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("User-Agent", "SpotiFLAC-Mobile/${config.appVersion}")
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // The gateway explains itself in the body (error code, origin,
                // action); without it a rejected renewal is indistinguishable from
                // a WAF block.
                SpotiFLACDiag.log(
                    "session renew HTTP ${response.code} id=${session.extensionId} body=" + text.take(240),
                )
                throw IllegalStateException("HTTP ${response.code}")
            }
            return parseRefreshResponse(text)
        }
    }

    private fun parseRefreshResponse(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        obj["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { result["session_id"] = it }
        obj["session_secret"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { result["session_secret"] = it }
        obj["expires_at"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { result["expires_at"] = it }
        return result
    }

    /**
     * Merges refreshed fields into the record on disk.
     *
     * A rotating secret can be written concurrently by the runtime itself, so the
     * record is re-read first and the write is skipped when another writer already
     * replaced the generation this renewal was based on. That way the renewer can
     * never clobber a fresher session with a stale one.
     */
    private fun applyRefresh(
        session: ExtensionSession,
        record: Map<String, String>,
        refreshed: Map<String, String>,
    ): Boolean {
        val file = session.recordFile
        val latest = runCatching { readRecord(file) }.getOrNull() ?: record
        if (latest["session_id"].orEmpty() != record["session_id"].orEmpty()) return false

        val merged = latest.toMutableMap()
        refreshed.forEach { (key, value) -> if (value.isNotBlank()) merged[key] = value }
        if (merged == latest) return false

        file.parentFile?.mkdirs()
        val payload = buildJsonObject { merged.forEach { (key, value) -> put(key, value) } }.toString()
        val temp = File(file.parentFile, file.name + ".renew.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
        return true
    }

    /** Reads a session record file's flat string fields. */
    private fun readRecord(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val obj = json.parseToJsonElement(file.readText()).jsonObject
        return obj.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
    }

    private data class SignedSessionConfig(
        val baseUrl: String,
        val appVersion: String,
        val platform: String,
        val schemeLabel: String,
        val headerPrefix: String,
        val timeWindowSeconds: Long,
        val refreshPath: String,
    )

    private fun signedSessionConfigOf(context: Context, extensionId: String): SignedSessionConfig? {
        val manifest = File(File(extensionsDir(context), extensionId), "manifest.json")
        if (!manifest.isFile) return null
        return runCatching {
            val signed = json.parseToJsonElement(manifest.readText()).jsonObject["signedSession"]
                ?.jsonObject ?: return null
            val baseUrl = signed["baseUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (baseUrl.isEmpty()) return null
            val endpoints = signed["endpoints"]?.let { element ->
                runCatching { element.jsonObject }.getOrNull()
            }
            SignedSessionConfig(
                baseUrl = baseUrl,
                appVersion = signed["appVersion"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "ext-1.0",
                platform = signed["platform"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: SpotiFLACRequestSigner.DEFAULT_PLATFORM,
                schemeLabel = signed["schemeLabel"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: SpotiFLACRequestSigner.DEFAULT_SCHEME_LABEL,
                headerPrefix = signed["headerPrefix"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: SpotiFLACRequestSigner.DEFAULT_HEADER_PREFIX,
                timeWindowSeconds = signed["timeWindowSeconds"]?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull() ?: SpotiFLACRequestSigner.DEFAULT_TIME_WINDOW_SECONDS,
                refreshPath = endpoints?.get("refresh")?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: DEFAULT_REFRESH_PATH,
            )
        }.getOrNull()
    }

    private fun sessionFor(context: Context, dir: File): ExtensionSession? {
        val extensionId = dir.name
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return null
        val signed = runCatching {
            json.parseToJsonElement(manifestFile.readText()).jsonObject["signedSession"]
                ?.jsonObject
        }.getOrNull() ?: return null
        val namespace = SpotiFLACRequestSigner.sanitizeNamespace(
            signed["namespace"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        val baseUrl = signed["baseUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (namespace.isEmpty() || baseUrl.isEmpty()) return null
        val appVersion = signed["appVersion"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "ext-1.0"
        val platform = signed["platform"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: SpotiFLACRequestSigner.DEFAULT_PLATFORM
        val recordFile = File(
            recordsDir(context),
            SpotiFLACRequestSigner.sessionRecordFileName(namespace, baseUrl, appVersion, platform),
        )
        val record = runCatching { readRecord(recordFile) }.getOrDefault(emptyMap())
        val hasSession = record["session_id"].orEmpty().isNotBlank() &&
            record["session_secret"].orEmpty().isNotBlank()
        return ExtensionSession(
            extensionId = extensionId,
            displayName = runCatching {
                json.parseToJsonElement(manifestFile.readText()).jsonObject["displayName"]
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull(),
            namespace = namespace,
            appVersion = appVersion,
            expiresAtMillis = record["expires_at"]?.let(::parseExpiryMillis),
            hasSession = hasSession,
            recordFile = recordFile,
        )
    }

    /**
     * Whether a session should be renewed now. Unknown expiry is treated as due
     * (better one wasted call than a lapsed session), and an already-expired
     * session is still attempted: the gateway may still accept its install id.
     */
    internal fun renewalDue(remainingSeconds: Long?, force: Boolean): Boolean =
        force || remainingSeconds == null || remainingSeconds <= RENEW_WINDOW_SECONDS

    internal fun parseExpiryMillis(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun extensionsDir(context: Context): File = File(context.filesDir, "spotiflac/extensions")

    private fun recordsDir(context: Context): File =
        File(File(context.filesDir, "spotiflac/extension_data"), "signed_sessions")

    /** Debug helper: the record payload for one extension, for diagnostics only. */
    fun describe(context: Context): List<String> =
        sessions(context).map { session ->
            val remaining = session.remainingSeconds
            val state = when {
                !session.hasSession -> "unverified"
                session.isExpired -> "expired"
                else -> "valid (${remaining?.div(60)}m left)"
            }
            "${session.extensionId}: $state"
        }

    /** Exposed for tests. */
    internal fun recordFileNameForTest(
        namespace: String,
        baseUrl: String,
        appVersion: String,
        platform: String,
    ): String = SpotiFLACRequestSigner.sessionRecordFileName(namespace, baseUrl, appVersion, platform)

    /** Exposed for tests. */
    internal fun mergeForTest(
        latest: JsonObject,
        refreshed: Map<String, String>,
    ): Map<String, String> {
        val merged = latest.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
            .toMutableMap()
        refreshed.forEach { (key, value) -> if (value.isNotBlank()) merged[key] = value }
        return merged
    }
}
