package app.hush.music.spotiflac

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads and stores only registry packages whose HTTPS URL and SHA-256 digest
 * validate. Packages are kept as data; Hush does not execute downloaded JS.
 */
@Singleton
class SpotiFLACExtensionPackageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val MAX_PACKAGE_BYTES = 64L * 1024L * 1024L
        private const val DIRECTORY = "spotiflac/extensions"
    }

    suspend fun downloadAndVerify(source: ExtensionSource): Result<SpotiFLACExtensionPackageVerifier.VerifiedPackage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = requireNotNull(source.downloadUrl?.trim()) { "Extension package URL is missing" }
                require(url.startsWith("https://", ignoreCase = true)) {
                    "Extension package URL must use HTTPS"
                }
                val digest = requireNotNull(source.sha256?.trim()) { "Extension package digest is missing" }
                require(digest.matches(Regex("[0-9a-fA-F]{64}"))) { "Extension package digest is invalid" }

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/zip, application/octet-stream")
                    .header("User-Agent", "Hush/${app.hush.music.BuildConfig.VERSION_NAME}")
                    .build()
                val bytes = httpClient.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "Extension download failed: HTTP ${response.code}" }
                    val body = requireNotNull(response.body) { "Extension download returned no body" }
                    body.byteStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_PACKAGE_BYTES) { "Extension package is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }

                val verified = SpotiFLACExtensionPackageVerifier.verify(source, bytes).getOrThrow()
                val destinationDir = File(context.filesDir, DIRECTORY).apply { mkdirs() }
                val destination = File(destinationDir, "${source.id}.sflx")
                val temporary = File(destinationDir, ".${source.id}.sflx.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(destination)) {
                    destination.delete()
                    require(temporary.renameTo(destination)) { "Could not publish verified extension package" }
                }
                verified
            }
        }

    fun packageFile(extensionId: String): File =
        File(context.filesDir, "$DIRECTORY/$extensionId.sflx")

    fun remove(extensionId: String): Boolean = packageFile(extensionId).delete()
}
