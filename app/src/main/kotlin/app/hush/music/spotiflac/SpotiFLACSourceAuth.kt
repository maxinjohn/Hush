/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * What a SpotiFLAC download source needs before it can download anything.
 *
 * The distinction matters because only some sources use the zarz gateway's signed
 * sessions. SoundCloud and the YouTube Music provider declare no `signedSession`
 * block at all — they sign in with the service itself — so reporting them as
 * "verification needed" invents a Cloudflare check that can never be completed,
 * which is exactly what a dead "Verify" button looks like from the outside.
 */
enum class SpotiFLACSourceAuthState {
    /** The runtime holds an unexpired signed session; downloads may proceed. */
    VERIFIED,

    /** The source declares a signed session and the one on disk is unusable. */
    NEEDS_VERIFICATION,

    /** The source has no signed-session contract; there is nothing to verify. */
    NOT_REQUIRED,
    ;

    /** True when a download through this source can be attempted right now. */
    val isUsable: Boolean get() = this != NEEDS_VERIFICATION
}

/**
 * Pure classification of a source's auth state, kept out of the bridge so it can
 * be unit tested without the native runtime, a Context or a network.
 *
 * Expiry is deliberately part of the decision: a record whose `expires_at` has
 * passed is refused by the gateway preflight, and treating it as "verified"
 * (id and secret present) is what let an expired provider sit at the end of the
 * fallback chain and abort every download attempt with `verification_required`.
 */
object SpotiFLACSourceAuth {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Whether the manifest declares a signed-session contract. Unreadable or
     * missing manifests are reported as "no contract", because the caller cannot
     * ask the user to solve a challenge for a source it cannot even describe.
     */
    fun requiresSignedSession(manifestJson: String?): Boolean {
        if (manifestJson.isNullOrBlank()) return false
        val signed = runCatching {
            json.parseToJsonElement(manifestJson).jsonObject["signedSession"]?.jsonObject
        }.getOrNull() ?: return false
        val namespace = signed["namespace"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val baseUrl = signed["baseUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return namespace.isNotEmpty() && baseUrl.isNotEmpty()
    }

    /** The signed-session record's id and secret, when both are non-blank. */
    fun recordSessionId(recordJson: String?): String? {
        if (recordJson.isNullOrBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(recordJson).jsonObject
            // contentOrNull, not content: a JSON null reads as the literal string
            // "null" through content, which would count as a real session.
            val id = obj["session_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val secret = obj["session_secret"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            id.takeIf { it.isNotEmpty() && secret.isNotEmpty() }
        }.getOrNull()
    }

    /** `expires_at` in epoch millis, or null when absent/unparseable. */
    fun recordExpiryMillis(recordJson: String?): Long? {
        if (recordJson.isNullOrBlank()) return null
        val raw = runCatching {
            json.parseToJsonElement(recordJson).jsonObject["expires_at"]
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return null
        return runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()
    }

    /**
     * True when a record can satisfy the runtime's preflight.
     *
     * A record with no readable expiry is accepted: the runtime itself only
     * checks that id and secret are present, so refusing it here would fight the
     * engine over records it considers fine. A record with an expiry that has
     * passed is not: the gateway refuses those requests, and that is a verifiable
     * fact rather than a guess.
     */
    fun recordUsable(recordJson: String?, nowMillis: Long): Boolean {
        if (recordSessionId(recordJson) == null) return false
        val expiry = recordExpiryMillis(recordJson) ?: return true
        return expiry > nowMillis
    }

    /** The full classification, from the manifest plus its session record. */
    fun state(manifestJson: String?, recordJson: String?, nowMillis: Long): SpotiFLACSourceAuthState {
        if (!requiresSignedSession(manifestJson)) return SpotiFLACSourceAuthState.NOT_REQUIRED
        return if (recordUsable(recordJson, nowMillis)) {
            SpotiFLACSourceAuthState.VERIFIED
        } else {
            SpotiFLACSourceAuthState.NEEDS_VERIFICATION
        }
    }
}
