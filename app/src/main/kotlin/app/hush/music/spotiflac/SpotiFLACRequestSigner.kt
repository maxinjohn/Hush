package app.hush.music.spotiflac

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ZARZ-HMAC-V1 request signer.
 *
 * This mirrors the Go implementation in the SpotiFLAC runtime
 * (`extension_signed_session.go` → `doSignedSessionRequest`) byte for byte, so a
 * request signed here is accepted by the same gateway the native runtime talks
 * to. It is shared by [SpotiFLACSessionRenewer] (an extension's own session) and
 * [SpotiFLACSessionRenewer] (per-extension session renewal), which keeps a single
 * implementation of a scheme that is easy to get subtly wrong.
 */
object SpotiFLACRequestSigner {

    const val DEFAULT_SCHEME_LABEL = "ZARZ-HMAC-V1"
    const val DEFAULT_HEADER_PREFIX = "X-Zarz-"
    const val DEFAULT_TIME_WINDOW_SECONDS = 300L
    const val DEFAULT_PLATFORM = "extension"

    /** Go's `Format("2006-01-02T15:04:05.000Z")` — exactly three decimal places. */
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /**
     * Builds the signed headers for one request.
     *
     * @param sessionSecret the raw session secret string (NOT base64-decoded),
     *   matching Go's `[]byte(record.SessionSecret)`.
     */
    fun signedHeaders(
        method: String,
        path: String,
        body: String,
        sessionId: String,
        sessionSecret: String,
        appVersion: String,
        platform: String = DEFAULT_PLATFORM,
        schemeLabel: String = DEFAULT_SCHEME_LABEL,
        headerPrefix: String = DEFAULT_HEADER_PREFIX,
        timeWindowSeconds: Long = DEFAULT_TIME_WINDOW_SECONDS,
        instant: Instant = Instant.now(),
        nonce: String = generateNonce(),
    ): Map<String, String> {
        val bodySha256 = sha256Hex(body)
        // Both the timestamp and the rolling window come from the same instant so
        // the signature can never straddle a window boundary.
        val timestamp = TIMESTAMP_FORMAT.format(instant)

        // Rolling key: the session secret keys a per-window value bound to the
        // session id, then the base64url text of that MAC (its ASCII bytes) keys
        // the signature itself.
        val window = rollingWindow(instant, timeWindowSeconds)
        val rollingInput = "$window:$sessionId"
        val rollingKeyBytes = hmacSha256(sessionSecret.toByteArray(Charsets.UTF_8), rollingInput.toByteArray())
        val rollingKey = Base64.getUrlEncoder().withoutPadding().encodeToString(rollingKeyBytes)

        val pathOnly = path.substringBefore("?")
        val signingInput = buildString {
            append(schemeLabel)
            append("\n")
            append(method.uppercase())
            append("\n")
            append(pathOnly)
            append("\n\n") // Go's strings.Join inserts an empty field after the path
            append(bodySha256)
            append("\n")
            append(timestamp)
            append("\n")
            append(nonce)
            append("\n")
            append(sessionId)
            append("\n")
            append(appVersion)
            append("\n")
            append(platform)
        }

        val signature = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                hmacSha256(rollingKey.toByteArray(Charsets.UTF_8), signingInput.toByteArray()),
            )

        return mapOf(
            "${headerPrefix}Session" to sessionId,
            "${headerPrefix}Timestamp" to timestamp,
            "${headerPrefix}Nonce" to nonce,
            "${headerPrefix}Body-SHA256" to bodySha256,
            "${headerPrefix}Signature" to signature,
            "${headerPrefix}App-Version" to appVersion,
            "${headerPrefix}Platform" to platform,
        )
    }

    /** The rotating signing window, matching Go's `ts.Unix() / TimeWindowSeconds`. */
    fun rollingWindow(instant: Instant, timeWindowSeconds: Long): Long {
        val window = if (timeWindowSeconds > 0) timeWindowSeconds else DEFAULT_TIME_WINDOW_SECONDS
        return Math.floorDiv(instant.epochSecond, window)
    }

    fun generateNonce(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * The runtime's session-record file name for a signed-session config:
     * `<namespace>-<sha256(scope)[:16]>.json`, where the hashed scope lowercases
     * the URL/app-version/platform fields.
     */
    fun sessionRecordFileName(
        namespace: String,
        baseUrl: String,
        appVersion: String,
        platform: String,
    ): String {
        val scope = listOf(namespace, baseUrl.lowercase(), appVersion.lowercase(), platform.lowercase())
            .joinToString("\n")
        val hash = sha256Hex(scope).take(16)
        return "$namespace-$hash.json"
    }

    /** Mirrors the runtime's `sanitizeSignedSessionNamespace`. */
    fun sanitizeNamespace(value: String): String {
        val filtered = value.trim().lowercase().filter { ch ->
            (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.'
        }
        return filtered.trim('.', '-', '_')
    }
}
