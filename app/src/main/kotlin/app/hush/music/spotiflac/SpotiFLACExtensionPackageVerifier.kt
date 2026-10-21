package app.hush.music.spotiflac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Validates registry packages before Hush ever considers them installable.
 *
 * SpotiFLAC packages contain JavaScript for the SpotiFLAC runtime. Hush does
 * not execute downloaded JavaScript directly, but it can still safely verify
 * package identity and layout for a future sandboxed runtime or for diagnostics.
 */
object SpotiFLACExtensionPackageVerifier {
    private const val MAX_ENTRIES = 2048
    private const val MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class VerifiedPackage(
        val extensionId: String,
        val version: String,
        val sha256: String,
        val manifestJson: String,
        /**
         * Whether the registry pinned this package by digest.
         *
         * False means the archive was checked for its *shape* - a manifest, an entry point, safe
         * paths, sane sizes - and hashed, but nothing published that hash, so nothing can confirm
         * the bytes are the ones its author meant to publish. Carried out of here so the surfaces
         * that describe an install can say it rather than imply a verification that did not happen.
         */
        val pinned: Boolean = true,
    )

    /**
     * Validates a package, against the registry's digest when it published one.
     *
     * The digest used to be required, and that made every registry which omits `sha256` unusable in
     * a way nothing said out loud: the source arrived, was listed, its switch turned on, and every
     * attempt failed with `SpotiFLAC extension package is unavailable`. Measured on the reporting
     * device with `AlexRabbit/SpoitiLists`, whose entry publishes a `download_url` and no checksum -
     * upstream SpotiFLAC installs it, so refusing it was Hush's rule and not the format's. What is
     * still refused is a digest that is present and malformed: a registry that meant to pin the
     * package and got it wrong is not the same thing as one that did not pin it at all.
     */
    fun verify(source: ExtensionSource, packageBytes: ByteArray): Result<VerifiedPackage> = runCatching {
        require(packageBytes.isNotEmpty()) { "Extension package is empty" }
        val declaredHash = source.sha256?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        require(declaredHash == null || declaredHash.matches(Regex("[0-9a-f]{64}"))) {
            "Extension ${source.id} has an invalid SHA-256 digest"
        }

        val actualHash = sha256(packageBytes)
        if (declaredHash != null) {
            require(actualHash == declaredHash) {
                "SHA-256 mismatch for ${source.id}: expected $declaredHash, got $actualHash"
            }
        }

        var manifest: String? = null
        var hasIndex = false
        var entries = 0
        var totalBytes = 0L
        val paths = HashSet<String>()

        ZipInputStream(ByteArrayInputStream(packageBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "Extension archive has too many entries" }

                val normalized = normalizeEntryPath(entry.name)
                require(paths.add(normalized.lowercase(Locale.US))) {
                    "Duplicate extension archive path: ${entry.name}"
                }
                if (entry.isDirectory) continue

                val bytes = zip.readBounded(MAX_MANIFEST_BYTES.takeIf { normalized == "manifest.json" } ?: MAX_UNCOMPRESSED_BYTES)
                totalBytes += bytes.size.toLong()
                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                    "Extension archive exceeds the extracted size limit"
                }
                if (normalized == "manifest.json") {
                    require(bytes.size <= MAX_MANIFEST_BYTES) { "manifest.json is too large" }
                    manifest = bytes.toString(Charsets.UTF_8)
                } else if (normalized == "index.js") {
                    hasIndex = true
                }
            }
        }

        val manifestText = requireNotNull(manifest) { "manifest.json is missing from package root" }
        require(hasIndex) { "index.js is missing from package root" }
        val manifestObject = json.parseToJsonElement(manifestText).jsonObject
        val manifestId = manifestObject["name"]?.jsonPrimitive?.content
            ?: manifestObject["id"]?.jsonPrimitive?.content
        val version = manifestObject["version"]?.jsonPrimitive?.content
        require(!manifestId.isNullOrBlank()) { "manifest.json has no extension name" }
        require(!version.isNullOrBlank()) { "manifest.json has no extension version" }
        require(manifestId == source.id) {
            "Package manifest ID $manifestId does not match registry ID ${source.id}"
        }
        val requiredFeatures =
            runCatching {
                manifestObject["requiredRuntimeFeatures"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrNull().orEmpty()
        val refusal = SpotiFLACRuntimeFeatures.missing(requiredFeatures)
        require(refusal == null) { "Extension $manifestId@$version $refusal" }

        VerifiedPackage(
            extensionId = manifestId,
            version = version,
            sha256 = actualHash,
            manifestJson = manifestText,
            pinned = declaredHash != null,
        )
    }

    private fun normalizeEntryPath(raw: String): String {
        require(raw.isNotBlank() && !raw.startsWith('/') && !raw.startsWith('\\')) {
            "Unsafe extension archive path: $raw"
        }
        require(!raw.contains('\\')) { "Unsafe extension archive path: $raw" }
        val parts = raw.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe extension archive path: $raw"
        }
        return parts.joinToString("/")
    }

    private fun ZipInputStream.readBounded(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Extension archive entry exceeds its size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
