package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

class SpotiFLACExtensionPackageVerifierTest {
    @Test
    fun `accepts matching package digest and manifest`() {
        val bytes = packageBytes("tidal-web", "1.2.6")
        val source = ExtensionSource(
            id = "tidal-web",
            sha256 = sha256(bytes),
            downloadUrl = "https://example.test/tidal.sflx",
        )

        val result = SpotiFLACExtensionPackageVerifier.verify(source, bytes)

        assertTrue(result.isSuccess)
        assertEquals("tidal-web", result.getOrThrow().extensionId)
    }

    @Test
    fun `rejects digest mismatch`() {
        val bytes = packageBytes("tidal-web", "1.2.6")
        val source = ExtensionSource(
            id = "tidal-web",
            sha256 = "0".repeat(64),
        )

        assertFalse(SpotiFLACExtensionPackageVerifier.verify(source, bytes).isSuccess)
    }

    @Test
    fun `rejects manifest id mismatch`() {
        val bytes = packageBytes("other", "1.0.0")
        val source = ExtensionSource(
            id = "tidal-web",
            sha256 = sha256(bytes),
        )

        assertFalse(SpotiFLACExtensionPackageVerifier.verify(source, bytes).isSuccess)
    }

    @Test
    fun `rejects traversal path`() {
        val bytes = zipBytes(
            "manifest.json" to "{\"name\":\"tidal-web\",\"version\":\"1\"}".toByteArray(),
            "index.js" to "".toByteArray(),
            "../escape.js" to "bad".toByteArray(),
        )
        val source = ExtensionSource(id = "tidal-web", sha256 = sha256(bytes))

        assertFalse(SpotiFLACExtensionPackageVerifier.verify(source, bytes).isSuccess)
    }

    private fun packageBytes(id: String, version: String): ByteArray = zipBytes(
        "manifest.json" to "{\"name\":\"$id\",\"version\":\"$version\"}".toByteArray(),
        "index.js" to "registerExtension({});".toByteArray(),
    )

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
