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

    /**
     * A registry that publishes no checksum is installed, and the package says so.
     *
     * This is the case that made "add a source and it never works" true: `AlexRabbit/SpoitiLists`
     * publishes a `download_url` and no `sha256`, upstream SpotiFLAC installs it, and Hush refused
     * the download outright - so the source arrived, listed, switched on, and failed every attempt
     * with `SpotiFLAC extension package is unavailable`. What the verification still covers is the
     * archive's shape, which is what the mismatched-manifest and traversal cases below hold.
     */
    @Test
    fun `accepts an undeclared digest, and records that nothing pinned the package`() {
        val bytes = packageBytes("spoiti-lists", "1.4.0")
        val source = ExtensionSource(
            id = "spoiti-lists",
            downloadUrl = "https://example.test/spoiti-lists.spotiflac-ext",
        )

        val verified = SpotiFLACExtensionPackageVerifier.verify(source, bytes).getOrThrow()

        assertEquals("spoiti-lists", verified.extensionId)
        assertEquals("1.4.0", verified.version)
        assertFalse(verified.pinned)
        // The hash of what arrived is still recorded, so an install is not anonymous.
        assertEquals(sha256(bytes), verified.sha256)
    }

    /** A digest that is *declared* is always enforced, and an undeclared one is not the same thing. */
    @Test
    fun `accepts a declared digest and reports it as pinned`() {
        val bytes = packageBytes("tidal-web", "1.2.6")
        val source = ExtensionSource(
            id = "tidal-web",
            sha256 = sha256(bytes),
            downloadUrl = "https://example.test/tidal.sflx",
        )

        assertTrue(SpotiFLACExtensionPackageVerifier.verify(source, bytes).getOrThrow().pinned)
    }

    /**
     * A malformed digest is still a refusal, because that registry meant to pin the package.
     *
     * The distinction the whole change turns on: nothing published is not the same as something
     * published that cannot be used.
     */
    @Test
    fun `rejects a digest that is present but not a digest`() {
        val bytes = packageBytes("tidal-web", "1.2.6")
        for (bad in listOf("deadbeef", "z".repeat(64), "c".repeat(63))) {
            val source = ExtensionSource(id = "tidal-web", sha256 = bad)
            assertFalse(bad, SpotiFLACExtensionPackageVerifier.verify(source, bytes).isSuccess)
        }
        // Whitespace is not a digest either - it is an empty field - and the repository read is what
        // refuses an entry that spells one that way (`isSafeRegistryEntry`), so a blank value can
        // only reach here as "nothing was declared".
        assertFalse(
            SpotiFLACExtensionPackageVerifier.verify(
                ExtensionSource(id = "tidal-web", sha256 = "   "),
                bytes,
            ).getOrThrow().pinned,
        )
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

    /**
     * A package that names a runtime capability this build lacks is refused at install.
     *
     * The bytes would verify otherwise - right id, right digest, sane archive - and the failure
     * would only appear when the runtime tried to load it, as a source that switched on and never
     * answered. Upstream added `accountForms`/`directMedia` in 5.1; this is the gate that keeps such
     * a package from becoming a row nothing can play.
     */
    @Test
    fun `refuses a package that needs a newer runtime feature`() {
        val bytes = zipBytes(
            "manifest.json" to
                ("{\"name\":\"tidal-web\",\"version\":\"9.0.0\"," +
                    "\"requiredRuntimeFeatures\":[\"accountForms@1\"]}").toByteArray(),
            "index.js" to "registerExtension({});".toByteArray(),
        )
        val source = ExtensionSource(id = "tidal-web", sha256 = sha256(bytes))

        val failure = SpotiFLACExtensionPackageVerifier.verify(source, bytes).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("requires runtime feature \"accountForms\""))
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
