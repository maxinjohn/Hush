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

    /**
     * The source's manifest could not be read, so what it needs is not known yet.
     *
     * This is a distinct answer from [NOT_REQUIRED], and conflating the two is what made a fresh
     * install report nothing to verify: on a first run the extension packages have not been
     * extracted yet, so there is no manifest to read, and every source - including the ones that do
     * demand a Cloudflare check - was classified as needing nothing. The automatic verification
     * queue therefore stayed empty, a download later failed with `verification_required`, and the
     * user was sent to solve a check the app had just said was unnecessary.
     */
    UNKNOWN,
    ;

    /**
     * True when a download through this source can be attempted right now.
     *
     * Unknown counts as usable on purpose: the runtime is the authority on whether a source can
     * serve, and refusing to try a source this app simply has not read yet would turn a missing
     * manifest into a failed track.
     */
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

    /** What a manifest says about signed sessions. */
    private enum class Contract {
        /** Not readable as a manifest, so what the source needs is not known. */
        UNREADABLE,

        /** Readable, and it declares no signed session: there is nothing to verify. */
        NONE,

        /** Readable, and it declares the scope of a signed session. */
        SIGNED,
    }

    private fun contractOf(manifestJson: String?): Contract {
        if (manifestJson.isNullOrBlank()) return Contract.UNREADABLE
        val root = runCatching { json.parseToJsonElement(manifestJson).jsonObject }.getOrNull()
            ?: return Contract.UNREADABLE
        val element = root["signedSession"] ?: return Contract.NONE
        val signed = runCatching { element.jsonObject }.getOrNull() ?: return Contract.UNREADABLE
        val namespace = signed["namespace"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val baseUrl = signed["baseUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        // A signed-session block with no scope is a manifest this app cannot act on, which is an
        // unknown rather than a source that needs nothing.
        return if (namespace.isNotEmpty() && baseUrl.isNotEmpty()) Contract.SIGNED else Contract.UNREADABLE
    }

    /**
     * Whether the manifest positively declares a signed-session contract.
     *
     * Only a *readable* manifest can answer this, so a false result is not the same statement as
     * "needs no verification" - [state] exists to keep those apart, and callers that only need the
     * positive case (this one) must not read a false as "nothing to do".
     */
    fun requiresSignedSession(manifestJson: String?): Boolean =
        contractOf(manifestJson) == Contract.SIGNED

    /**
     * Whether the manifest was readable *and* declares no signed session.
     *
     * The only condition under which a source can be skipped as "nothing to verify": a manifest
     * that could not be read says nothing either way, and skipping on that is what lost a grant for
     * a package that had simply not been extracted yet.
     */
    fun declaresNoSignedSession(manifestJson: String?): Boolean =
        contractOf(manifestJson) == Contract.NONE

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

    /** The app version a signed-session manifest signs its requests with. */
    fun manifestAppVersion(manifestJson: String?): String? {
        if (manifestJson.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(manifestJson).jsonObject["signedSession"]
                ?.jsonObject
                ?.get("appVersion")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** The app version a session record was minted under. */
    fun recordAppVersion(recordJson: String?): String? {
        if (recordJson.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(recordJson).jsonObject["app_version"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * Whether a record can serve the source as it signs today.
     *
     * The gateway binds a session to the exact app version that minted it. Measured against the
     * live gateway: `POST /v2/tickets` with a session minted as `amzn@2.3.8` returns 400 when signed
     * as `amzn@2.3.8` (signature accepted) and a bare `403 {"error":"Forbidden"}` when signed as
     * `amzn@2.3.10`, and `/v2/session/refresh` answers 403 for the same swap - so a session can
     * never be migrated forward, only replaced by a fresh verification.
     *
     * A registry update rewrites `signedSession.appVersion` (amazon moved `2.3.8` -> `2.3.10`), which
     * strands the session that was already paid for: every request the updated extension signs is
     * refused, while the record still looks unexpired and the source was reported as verified. So a
     * mismatch has to be treated as unverified - that is what makes the re-verification the user
     * actually needs visible instead of a track that mysteriously fails.
     *
     * Anything that cannot be compared is left alone: a manifest or record with no version says
     * nothing about binding, and inventing a failure from a missing field would demand a check for a
     * source that is working.
     */
    fun recordBoundToSource(recordJson: String?, manifestJson: String?): Boolean =
        versionsBound(recordAppVersion(recordJson), manifestAppVersion(manifestJson))

    /**
     * The binding rule itself, for a caller that already holds the two versions.
     *
     * Kept separate from [recordBoundToSource] so nothing has to reshape a value it has in hand
     * into the JSON that would carry it - one caller did, and the parse of `amzn@2.3.8` failed
     * silently into "no version known", which read as "fine" for a session that was not.
     */
    fun versionsBound(mintedAppVersion: String?, signsAsAppVersion: String?): Boolean {
        val minted = mintedAppVersion?.trim().orEmpty()
        val signsAs = signsAsAppVersion?.trim().orEmpty()
        if (minted.isEmpty() || signsAs.isEmpty()) return true
        return minted.equals(signsAs, ignoreCase = true)
    }

    /**
     * Whether a record can be used by this source: present, unexpired, and bound to the version the
     * source signs with.
     */
    fun recordUsable(
        recordJson: String?,
        manifestJson: String?,
        nowMillis: Long,
    ): Boolean = recordUsable(recordJson, nowMillis) && recordBoundToSource(recordJson, manifestJson)

    /**
     * The full classification, from the manifest plus its session record.
     *
     * A manifest that cannot be read is [SpotiFLACSourceAuthState.UNKNOWN], never "nothing to
     * verify": the caller can only tell those apart by having read the manifest, and reporting an
     * unread one as a source with no contract is how a source that does need a check came to be
     * presented as ready.
     */
    fun state(manifestJson: String?, recordJson: String?, nowMillis: Long): SpotiFLACSourceAuthState =
        when (contractOf(manifestJson)) {
            Contract.UNREADABLE -> SpotiFLACSourceAuthState.UNKNOWN
            Contract.NONE -> SpotiFLACSourceAuthState.NOT_REQUIRED
            Contract.SIGNED -> if (recordUsable(recordJson, manifestJson, nowMillis)) {
                SpotiFLACSourceAuthState.VERIFIED
            } else {
                SpotiFLACSourceAuthState.NEEDS_VERIFICATION
            }
        }
}
