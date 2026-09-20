/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import timber.log.Timber

/**
 * Who this install is to the SpotiFLAC gateway: one install id, and the client version and relay
 * address every call to it is described by.
 *
 * This was a *session* manager, and what it is not any more is the important part of its story. Hush
 * used to hold its own gateway session - `GET /v2/bootstrap`, a Turnstile challenge, `POST
 * /v2/session/exchange` - and sign its own requests with it. That session can serve no source: the
 * gateway binds a session to the app version that minted it, so a session minted as this client's
 * version is answered `403` by an extension that signs as `<id>@<version>` (measured: the same
 * session and path answered `403` signed as `tidal-web@1.2.6` and `428 VERIFY_REQUIRED` signed as the
 * client version). Hush's own resolver needed it, and that resolver is gone with it.
 *
 * The gateway no longer hands one out either. Measured on the reporting device, bootstrap answers a
 * fresh Turnstile challenge on every launch:
 *
 * ```
 * Bootstrap response: status=200, body={"challenge_id":"chl_TEjEjK6Jm3b5RCZuwQjd", ...}
 * Turnstile challenge required: https://api.zarz.moe/v2/challenge?id=chl_...
 * ```
 *
 * and the surface that could complete that challenge is gone too, so the state it left behind
 * (`CHALLENGE_PENDING`, forever) had no repair and only fed a settings card that asked for a check
 * nobody could pass. Playback never needed it: every source is served by its own extension's
 * session, which is what [SpotiFLACSessionRenewer] renews.
 *
 * Two things here do still have a job. The install id is the gateway's key for this install, and the
 * runtime has to present the same one, so it is minted and persisted here. And the *old* session id
 * is still readable, because records an earlier build seeded from it cannot work and have to be
 * recognised ([legacyRelaySessionId] - see `purgeForeignSeededSessions`).
 *
 * Gateway-block detection, the one part of the bootstrap that was doing real work, lives where it
 * belongs now: [SpotiFLACSessionRenewer.probeRelayBlock] asks the relay's unauthenticated health
 * endpoint about this connection's address, the route watch calls it at start and on every route
 * change, and every renewal records a refusal it is handed.
 */
object SpotiFLACInstallIdentity {

    private const val TAG = "SpotiFLACIdentity"

    /** Where the install id lives. The name is the one the relay session used, deliberately. */
    private const val PREFS_NAME = "spotiflac_session"
    private const val KEY_INSTALL_ID = "install_id"

    /**
     * Where Hush's own gateway session used to be stored.
     *
     * Read, never written: its only use is recognising the session records an older build seeded from
     * it ([legacyRelaySessionId]). A value left over from such a build identifies exactly the records
     * that cannot work, and an install that never had one simply has nothing here.
     */
    private const val KEY_LEGACY_SESSION_ID = "session_id"

    /** The version every call to the gateway describes this client as. */
    const val APP_VERSION = "4.9.6"

    /** The relay the app's own gateway calls go to, unless the user names another one. */
    const val BASE_URL = "https://api.zarz.moe/v2"

    /** 32 lowercase hex characters, the only shape the gateway accepts. */
    internal val INSTALL_ID_PATTERN = Regex("^[0-9a-f]{32}$")

    /**
     * The gateway identity this install uses, or null when there is nothing usable to present.
     *
     * Prefs win while they exist; the vault is the fallback, so a build that runs before anything has
     * been minted still signs with the identity the gateway already trusts.
     */
    fun installId(context: Context): String? {
        val stored = runCatching { prefs(context).getString(KEY_INSTALL_ID, null) }.getOrNull()
        if (!stored.isNullOrBlank() && stored.matches(INSTALL_ID_PATTERN)) return stored
        return SpotiFLACSessionVault.storedInstallId(context)
            ?.takeIf { it.matches(INSTALL_ID_PATTERN) }
    }

    /**
     * The identity to keep using, minting one only when there is none to keep.
     *
     * Minting is the expensive branch - the gateway has never seen a fresh id - which is why prefs
     * and then the vault are consulted first. That is what carries the identity through an app
     * upgrade or a restore the vault survived, and it is why the vault is written on every call.
     */
    fun installIdForRuntime(context: Context): String? {
        val existing = installId(context)
        if (existing != null) {
            SpotiFLACSessionVault.rememberInstallId(context, existing)
            return existing
        }
        val minted = generateInstallId()
        runCatching { prefs(context).edit().putString(KEY_INSTALL_ID, minted).apply() }
        SpotiFLACSessionVault.rememberInstallId(context, minted)
        Timber.tag(TAG).i("Minted a new gateway install id")
        return minted
    }

    /**
     * The session Hush's own client was once minted, when an older build left one behind.
     *
     * Not a credential: nothing signs with it, and it is not a valid session any more. It is the only
     * way to recognise a signed-session record that an earlier build seeded from Hush's session for an
     * extension that signs with a different version - a record that looks perfectly usable and can
     * never download, so the source it belongs to would 403 forever.
     */
    fun legacyRelaySessionId(context: Context): String? =
        runCatching { prefs(context).getString(KEY_LEGACY_SESSION_ID, null) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun generateInstallId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
