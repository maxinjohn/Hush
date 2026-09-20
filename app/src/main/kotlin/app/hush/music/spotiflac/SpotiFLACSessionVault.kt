package app.hush.music.spotiflac

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Durable home for SpotiFLAC signed-session material.
 *
 * A verified source should cost one Cloudflare challenge and then never again.
 * Three things used to break that promise, and all three are about the *file* a
 * session is looked up through rather than the session itself:
 *
 * 1. **Extension version bumps.** The runtime's record name hashes
 *    `namespace + baseUrl + appVersion + platform`, so the moment a registry
 *    update ships `deezer@1.4.0` the impeccable session minted as `deezer@1.3.5`
 *    becomes invisible: the runtime looks for a new file, finds nothing, and asks
 *    for a challenge while the old session sits on disk.
 * 2. **Expired records.** The Go runtime clears a record once `expires_at`
 *    passes, so an app that stayed closed past the session lifetime loses the
 *    only copy of the secret that could have refreshed it.
 * 3. **Fresh installs.** Records and the `install_id` live in the app's private
 *    storage, which a reinstall wipes - taking the identity the gateway already
 *    trusts with it.
 *
 * The vault is keyed by **extension**, not by the version that extension
 * currently ships, and every record the app observes is mirrored into it. When a
 * live record is missing, expired or blank, [adopt] refills it - first from a
 * sibling record belonging to another *version* of the same extension, then from
 * the vault - so the renewer can refresh it back to life without a human.
 *
 * Identifying the extension matters more than it looks. Every extension of a
 * gateway shares one `namespace`, `baseUrl` and `platform`, so those three fields
 * do not tell extensions apart at all; the only per-extension discriminator a
 * record carries is the prefix of `app_version` (`deezer@1.3.5` -> `deezer`,
 * `amzn@2.3.8` -> `amzn`). Matching on the gateway scope alone would happily
 * adopt another source's session, which looks verified and then 403s.
 */
object SpotiFLACSessionVault {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Where a session's material came from. */
    enum class Origin { SIBLING_RECORD, VAULT_FILE, NONE }

    /** The outcome of trying to refill one extension's live record. */
    data class Adoption(
        val adopted: Boolean,
        val origin: Origin,
        val appVersion: String? = null,
        val expiresAtMillis: Long? = null,
        val detail: String,
    )

    /** A piece of candidate session material found on disk. */
    internal data class Candidate(
        val source: File,
        val extensionKey: String,
        val sessionId: String,
        val sessionSecret: String,
        val installId: String,
        val appVersion: String,
        val expiresAtMillis: Long?,
        val modifiedAtMillis: Long,
        val origin: Origin,
    )

    /**
     * The gateway identity of a session slot, which every extension of one gateway
     * shares - so it is only half of what identifies a session. The extension itself
     * is the other half; see [extensionKeyOf].
     */
    internal fun scopeKey(namespace: String, baseUrl: String, platform: String): String =
        listOf(namespace, baseUrl.lowercase(), platform.lowercase()).joinToString("\n")

    /**
     * The per-extension part of an extension's app version: `deezer` for
     * `deezer@1.3.5`, `amzn` for `amzn@2.3.8`.
     *
     * This is what survives a version bump, so it is what two versions of the same
     * extension have in common - and what two *different* extensions do not.
     */
    internal fun extensionKeyOf(appVersion: String): String =
        appVersion.trim().substringBefore('@').lowercase()

    /**
     * Whether an existing record belongs to the same extension as the one being
     * used now.
     *
     * A versioned app version must share the extension key; an unversioned one
     * (`ext-1.0`) carries no identity at all, so it can only be reused when it is
     * exactly the same string - guessing there would risk another source's session.
     */
    internal fun sameExtension(recordAppVersion: String, currentAppVersion: String): Boolean {
        val record = recordAppVersion.trim()
        val current = currentAppVersion.trim()
        if (record.isEmpty() || current.isEmpty()) return false
        if (!record.contains('@') || !current.contains('@')) {
            return record.equals(current, ignoreCase = true)
        }
        return extensionKeyOf(record) == extensionKeyOf(current)
    }

    /** Vault file name for one extension of one gateway. */
    internal fun vaultFileName(
        extensionId: String,
        namespace: String,
        baseUrl: String,
        platform: String,
    ): String {
        val id = sanitize(extensionId)
        val hash = sha256Hex(scopeKey(namespace, baseUrl, platform)).take(8)
        return "$id-$hash.json"
    }

    /**
     * Picks the best candidate to revive a record with.
     *
     * A record that is still valid beats one that has lapsed (the renewer can
     * refresh either, but a live one needs no network call at all), and among
     * equals the longest remaining validity wins - falling back to the most
     * recently written file when nothing has an expiry.
     */
    internal fun pickCandidate(
        candidates: List<Candidate>,
        nowMillis: Long,
    ): Candidate? = candidates
        .filter { it.sessionId.isNotBlank() && it.sessionSecret.isNotBlank() && it.installId.isNotBlank() }
        .maxWithOrNull(
            compareBy(
                { candidateUsable(it, nowMillis) },
                { it.expiresAtMillis ?: Long.MIN_VALUE },
                { it.modifiedAtMillis },
            ),
        )

    /** A candidate is usable when it has no expiry, or one still in the future. */
    internal fun candidateUsable(candidate: Candidate, nowMillis: Long): Boolean {
        val expiry = candidate.expiresAtMillis ?: return true
        return expiry > nowMillis
    }

    // ---------------------------------------------------------------- mirroring

    /**
     * Mirrors a live record into the vault.
     *
     * Called for every record the app sees, so the vault is always at least as
     * complete as the runtime's own store - including for sessions the runtime
     * minted itself during an invisible challenge.
     */
    fun remember(context: Context, recordFile: File, extensionId: String) {
        runCatching {
            if (!recordFile.isFile) return@runCatching
            val record = readFlat(recordFile.readText()) ?: return@runCatching
            val namespace = record["namespace"].orEmpty()
            val baseUrl = record["base_url"].orEmpty()
            if (namespace.isBlank() || baseUrl.isBlank()) return@runCatching
            val platform = record["platform"].orEmpty().ifBlank { DEFAULT_PLATFORM }
            writeFlat(
                File(vaultDir(context), vaultFileName(extensionId, namespace, baseUrl, platform)),
                record,
            )
            record["install_id"]?.takeIf { it.isNotBlank() }?.let { rememberInstallId(context, it) }
        }
    }

    /**
     * Remembers the app's gateway identity.
     *
     * A reinstall mints a brand new `install_id` unless the vault still holds the
     * old one, and a brand new identity is exactly what the gateway answers with a
     * challenge.
     */
    fun rememberInstallId(context: Context, installId: String) {
        runCatching {
            if (installId.isBlank()) return@runCatching
            val file = File(vaultDir(context), INSTALL_ID_FILE)
            val existing = runCatching { file.readText().trim() }.getOrNull()
            if (existing == installId) return@runCatching
            file.parentFile?.mkdirs()
            file.writeText(installId)
        }
    }

    /** The remembered install id, or null when the vault never saw one. */
    fun storedInstallId(context: Context): String? = runCatching {
        File(vaultDir(context), INSTALL_ID_FILE)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ---------------------------------------------------------------- adoption

    /**
     * Refills a live record that can no longer be used, from material the app
     * already earned.
     *
     * Candidates are the records the runtime keeps beside this one that belong to
     * the *same extension* under any version, plus the vault entry for this
     * extension. The winner is written into [liveRecord] under the *current* name,
     * keeping the version it was actually minted with - the gateway binds a session
     * to that version, so rewriting it to the manifest's newer version would be a
     * guaranteed 403.
     */
    fun adopt(
        context: Context,
        extensionId: String,
        liveRecord: File,
        namespace: String,
        baseUrl: String,
        platform: String,
        currentAppVersion: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Adoption {
        if (namespace.isBlank() || baseUrl.isBlank()) {
            return Adoption(false, Origin.NONE, detail = "scope unknown")
        }
        val current = runCatching { liveRecord.takeIf { it.isFile }?.readText() }.getOrNull()
        if (SpotiFLACSourceAuth.recordUsable(current, nowMillis)) {
            return Adoption(false, Origin.NONE, detail = "record already usable")
        }

        val scope = scopeKey(namespace, baseUrl, platform)
        // The app's own identity is the fallback for a record that never stored
        // one; without it there is nothing to sign a revival with.
        val fallbackInstallId = SpotiFLACInstallIdentity.installId(context).orEmpty()
        val candidates = siblingCandidates(context, scope, liveRecord, fallbackInstallId, currentAppVersion) +
            vaultCandidates(
                context = context,
                extensionId = extensionId,
                namespace = namespace,
                baseUrl = baseUrl,
                platform = platform,
                scope = scope,
                fallbackInstallId = fallbackInstallId,
                currentAppVersion = currentAppVersion,
            )
        val picked = pickCandidate(candidates, nowMillis)
            ?: return Adoption(false, Origin.NONE, detail = "no stored material")

        return runCatching {
            val payload = revivalPayload(picked, namespace, baseUrl, platform)
            liveRecord.parentFile?.mkdirs()
            val temp = File(liveRecord.parentFile, liveRecord.name + ".revive.tmp")
            temp.writeText(payload)
            if (!temp.renameTo(liveRecord)) {
                liveRecord.writeText(payload)
                temp.delete()
            }
            SpotiFLACDiag.log(
                "session revived for $extensionId origin=${picked.origin} " +
                    "minted=${picked.appVersion} expires=${picked.expiresAtMillis}",
            )
            Adoption(
                adopted = true,
                origin = picked.origin,
                appVersion = picked.appVersion,
                expiresAtMillis = picked.expiresAtMillis,
                detail = "revived from ${picked.origin}",
            )
        }.getOrElse { error ->
            Adoption(false, Origin.NONE, detail = "write failed: ${error.message}")
        }
    }

    /** Records already on disk for the same extension scope, any version. */
    private fun siblingCandidates(
        context: Context,
        scope: String,
        liveRecord: File,
        fallbackInstallId: String,
        currentAppVersion: String,
    ): List<Candidate> {
        val files = liveRecord.parentFile?.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { file ->
            if (file.name == liveRecord.name) return@mapNotNull null
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            candidateFromText(
                text = text,
                modifiedAtMillis = file.lastModified(),
                origin = Origin.SIBLING_RECORD,
                source = file,
                scope = scope,
                currentAppVersion = currentAppVersion,
                fallbackInstallId = fallbackInstallId,
            )
        }
    }

    /** The vault entry for this extension, whatever version it was minted under. */
    private fun vaultCandidates(
        context: Context,
        extensionId: String,
        namespace: String,
        baseUrl: String,
        platform: String,
        scope: String,
        fallbackInstallId: String,
        currentAppVersion: String,
    ): List<Candidate> {
        val file = File(vaultDir(context), vaultFileName(extensionId, namespace, baseUrl, platform))
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return listOfNotNull(
            candidateFromText(
                text = text,
                modifiedAtMillis = file.lastModified(),
                origin = Origin.VAULT_FILE,
                source = file,
                scope = scope,
                currentAppVersion = currentAppVersion,
                fallbackInstallId = fallbackInstallId,
            ),
        )
    }

    /**
     * Parses one stored record into a candidate, rejecting anything that is not the
     * same extension on the same gateway.
     *
     * Names and scopes cannot tell two sources apart on their own - every extension
     * of a gateway shares `namespace`, `baseUrl` and `platform` - so the record's
     * own `app_version` is what proves which extension it belongs to. Adopting
     * another source's session would leave this source looking verified while every
     * request through it is refused.
     */
    internal fun candidateFromText(
        text: String,
        modifiedAtMillis: Long,
        origin: Origin,
        source: File,
        scope: String,
        currentAppVersion: String,
        fallbackInstallId: String,
    ): Candidate? {
        val record = readFlat(text) ?: return null
        val namespace = record["namespace"].orEmpty()
        val baseUrl = record["base_url"].orEmpty()
        if (namespace.isBlank() || baseUrl.isBlank()) return null
        val platform = record["platform"].orEmpty().ifBlank { DEFAULT_PLATFORM }
        if (scopeKey(namespace, baseUrl, platform) != scope) return null
        val appVersion = record["app_version"].orEmpty()
        if (!sameExtension(appVersion, currentAppVersion)) return null
        val sessionId = record["session_id"].orEmpty()
        val secret = record["session_secret"].orEmpty()
        if (sessionId.isBlank() || secret.isBlank()) return null
        return Candidate(
            source = source,
            extensionKey = extensionKeyOf(appVersion),
            sessionId = sessionId,
            sessionSecret = secret,
            installId = record["install_id"].orEmpty().ifBlank { fallbackInstallId },
            appVersion = appVersion.ifBlank { currentAppVersion },
            expiresAtMillis = record["expires_at"]?.let(::parseExpiryMillis),
            modifiedAtMillis = modifiedAtMillis,
            origin = origin,
        )
    }

    /**
     * The record to write for a revived session.
     *
     * `app_version` is the version the session was *minted* under, never the
     * manifest's current one: the gateway binds a session to its minting version,
     * so rewriting it to a newer version is a guaranteed rejection.
     */
    internal fun revivalPayload(
        candidate: Candidate,
        namespace: String,
        baseUrl: String,
        platform: String,
    ): String = buildJsonObject {
        put("install_id", candidate.installId)
        put("session_id", candidate.sessionId)
        put("session_secret", candidate.sessionSecret)
        candidate.expiresAtMillis?.let {
            put("expires_at", java.time.Instant.ofEpochMilli(it).toString())
        }
        put("namespace", namespace)
        put("base_url", baseUrl)
        put("app_version", candidate.appVersion)
        put("platform", platform)
    }.toString()

    internal fun parseExpiryMillis(value: String): Long? =
        runCatching { java.time.Instant.parse(value.trim()).toEpochMilli() }.getOrNull()

    // ---------------------------------------------------------------- plumbing

    private const val INSTALL_ID_FILE = "install_id.txt"
    private const val DEFAULT_PLATFORM = "extension"

    private fun vaultDir(context: Context): File = File(context.filesDir, "spotiflac/session_vault")

    private fun sanitize(value: String): String =
        value.trim().lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' }

    private fun readFlat(text: String?): Map<String, String>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(text).jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
        }.getOrNull()
    }

    private fun writeFlat(file: File, record: Map<String, String>) {
        val payload = buildJsonObject { record.forEach { (key, value) -> put(key, value) } }.toString()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
    }

    internal fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
