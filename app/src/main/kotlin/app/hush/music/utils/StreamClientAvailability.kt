/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.utils

import app.hush.music.constants.StreamSourcePreferences
import app.hush.music.innertube.models.YouTubeClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Which playback clients this device can actually get a stream out of, and why the others cannot.
 *
 * A sweep walks clients in a composed order and stops at the first one that produces a playable URL.
 * Two failure kinds are not the track's fault and not worth re-asking on every resolve:
 *
 * - **This device cannot decipher the client's signatures.** A client answers with its formats either
 *   as a direct URL or as a `signatureCipher`, and a ciphered one has to be deobfuscated by running
 *   YouTube's own player JavaScript. When YouTube rotates that JavaScript ahead of the bundled
 *   extractor the decipher fails for *every* ciphered candidate - measured on the reporting device, on
 *   every attempt, for every track:
 *   ```
 *   Could not find deobfuscation function with any of the known patterns
 *     Failed to find pattern "...decodeURIComponent..." (YoutubeSignatureUtils)
 *   ```
 *   The sweep is led by WEB_REMIX whenever login and Web PoTokens are available, so every playback
 *   paid for four ciphered candidates before reaching a client whose URLs need no deciphering at all.
 *
 * - **The address is being refused.** A client can answer `LOGIN_REQUIRED` / "Sign in to confirm
 *   you're not a bot" because of where the request comes from - a VPN exit or a shared address - and
 *   that refusal is about the address, not the account or the track. Measured on the reporting device
 *   (egress on a VPN), five of fifteen client families answered that way while others served the same
 *   tracks seconds later, so those families cost a request each on every single resolve.
 *
 * Either way the answer is the same: remember the family, move it to the back of the sweep, and keep
 * it in the sweep. Nothing is dropped - both marks expire, and a client that cannot answer today may
 * be the only one that can tomorrow.
 */
object StreamClientAvailability {

    /** How long a client stays deferred after failing to decipher. */
    const val CANNOT_DECIPHER_TTL_MS = 30 * 60 * 1000L

    /** How long a client stays deferred after refusing the address. */
    const val REFUSED_TTL_MS = 10 * 60 * 1000L

    private val undecipherableUntil = ConcurrentHashMap<String, Long>()
    private val refusedUntil = ConcurrentHashMap<String, Long>()

    /** When the decipher itself was last seen failing, whatever client asked for it. */
    @Volatile
    private var decipherFailedAtMs: Long = 0L

    /**
     * The key a client is remembered under: its *family*, not its exact name.
     *
     * What decides whether a candidate is ciphered, or whether an address is refused, is the family -
     * and the same family is reached through several name shapes. Measured on the reporting device, one
     * sweep answered `LOGIN_REQUIRED` as `IOS_MUSIC@7.27.0` and later tried the very same family again
     * as `IOS@19.29.1` and `IOS@19.22.3`; keyed by exact name, each was a fresh client that had to be
     * refused again before it could be recognised. The version is part of the name and not part of the
     * identity here, so it is dropped first, then [StreamSourcePreferences.normalizeClientFamily]
     * collapses the family's aliases (`ANDROID_VR_1_65_10`, `IOS_MUSIC`, `IPADOS`, `TVHTML5*`).
     */
    fun keyOf(clientName: String): String =
        StreamSourcePreferences
            .normalizeClientFamily(clientName.substringBefore('@').trim())
            .uppercase()

    fun keyOf(client: YouTubeClient): String = keyOf(client.clientName)

    fun markCannotDecipher(
        clientName: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = keyOf(clientName)
        if (key.isNotBlank()) undecipherableUntil[key] = nowMs + CANNOT_DECIPHER_TTL_MS
    }

    /** Records that a client was refused by the address rather than by the track. */
    fun markRefused(
        clientName: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = keyOf(clientName)
        if (key.isNotBlank()) refusedUntil[key] = nowMs + REFUSED_TTL_MS
    }

    fun cannotDecipher(
        clientName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = isMarked(undecipherableUntil, keyOf(clientName), nowMs)

    fun isRefused(
        clientName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = isMarked(refusedUntil, keyOf(clientName), nowMs)

    /** Whether anything is known to stop this client answering right now. */
    fun cannotAnswer(
        clientName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = cannotDecipher(clientName, nowMs) || isRefused(clientName, nowMs)

    /** The families deferred for either reason, dropping marks that have already expired. */
    fun unavailableFamilies(nowMs: Long = System.currentTimeMillis()): Set<String> {
        prune(undecipherableUntil, nowMs)
        prune(refusedUntil, nowMs)
        return undecipherableUntil.keys + refusedUntil.keys
    }

    /** The families currently refused by the address, for a caller that has to explain a failure. */
    fun refusedFamilies(nowMs: Long = System.currentTimeMillis()): Set<String> {
        prune(refusedUntil, nowMs)
        return refusedUntil.keys.toSet()
    }

    /**
     * Records that the decipher failed, which is a fact about *this device and this player
     * JavaScript* rather than about the client that happened to ask.
     *
     * Every ciphered candidate has to go through the same extractor, so once it has failed, asking for
     * each of a response's formats costs a decipher attempt apiece - measured at ~2s each, four to five
     * of them per ciphered response, which on a cold start was over half of a 25s first track.
     */
    fun markDecipherUnavailable(nowMs: Long = System.currentTimeMillis()) {
        decipherFailedAtMs = nowMs
    }

    /** Whether a ciphered candidate is still worth asking for. */
    fun canDecipher(nowMs: Long = System.currentTimeMillis()): Boolean =
        decipherFailedAtMs == 0L || nowMs - decipherFailedAtMs >= CANNOT_DECIPHER_TTL_MS

    fun forgetAll() {
        undecipherableUntil.clear()
        refusedUntil.clear()
        decipherFailedAtMs = 0L
    }

    /**
     * [clients] with the deferred ones moved to the back.
     *
     * Pure and stable: everything still believed to work keeps the order the sweep composed, which is
     * what worked last, the user's preference and the configured fallbacks - and the deferred ones keep
     * their order among themselves. Nothing is removed: a sweep whose only answers are deferred must
     * still ask them.
     */
    internal fun <T> preferAnswering(
        clients: List<T>,
        unavailable: Set<String>,
        key: (T) -> String,
    ): List<T> {
        if (unavailable.isEmpty()) return clients
        val (deferred, usable) = clients.partition { key(it) in unavailable }
        return usable + deferred
    }

    private fun isMarked(
        marks: ConcurrentHashMap<String, Long>,
        key: String,
        nowMs: Long,
    ): Boolean {
        if (key.isBlank()) return false
        val until = marks[key] ?: return false
        if (until <= nowMs) {
            marks.remove(key)
            return false
        }
        return true
    }

    private fun prune(
        marks: ConcurrentHashMap<String, Long>,
        nowMs: Long,
    ) {
        val expired = marks.filterValues { it <= nowMs }.keys
        if (expired.isNotEmpty()) marks.keys.removeAll(expired)
    }
}
