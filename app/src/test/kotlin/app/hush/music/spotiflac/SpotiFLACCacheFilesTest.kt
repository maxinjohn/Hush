/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names on the right of these assertions are the ones this device's cache folder actually
 * held, read with `ls` after a session of downloads:
 *
 * ```
 * b407be2e2eb7403de1ef8e8f.media          25817207   (indexed)
 * b407be2e2eb7403de1ef8e8f.media.m4a      24542701   (the same song, a second time)
 * 7764bc68a1a750aab44d3a96.media.m4a.partial           3251052
 * 7764bc68a1a750aab44d3a96.media.m4a.partial.checkpoint.json.segments  152
 * ```
 *
 * Reading the last dot - what every sweep used to do - maps the middle two to `*.media.m4a`
 * and `*.media.m4a.partial`, neither of which is a track key. So a partial was never protected
 * while its download was running, and was never recognised as rubbish afterwards either.
 */
class SpotiFLACCacheFilesTest {
    private val key = "7764bc68a1a750aab44d3a96"

    @Test
    fun `the key is everything before the first dot`() {
        assertEquals(key, SpotiFLACCacheFiles.trackKeyOf("$key.media"))
        assertEquals(key, SpotiFLACCacheFiles.trackKeyOf("$key.media.m4a"))
        assertEquals(key, SpotiFLACCacheFiles.trackKeyOf("$key.flac"))
        assertEquals(key, SpotiFLACCacheFiles.trackKeyOf("$key.media.m4a.partial"))
        assertEquals(key, SpotiFLACCacheFiles.trackKeyOf("$key.media.m4a.partial.checkpoint.json.segments"))
    }

    @Test
    fun `every file shape of one track belongs to it`() {
        listOf(
            "$key.media",
            "$key.media.m4a",
            "$key.media.flac",
            "$key.flac",
            "$key.media.m4a.partial",
            "$key.media.m4a.partial.checkpoint.json.segments",
        ).forEach { fileName ->
            assertTrue("$fileName should belong to $key", SpotiFLACCacheFiles.belongsTo(fileName, key))
        }
    }

    @Test
    fun `another track's file never belongs to this one`() {
        val other = "b407be2e2eb7403de1ef8e8f"
        assertFalse(SpotiFLACCacheFiles.belongsTo("$other.media", key))
        // A key is a prefix of no other key, so a shared stem is not ownership.
        assertFalse(SpotiFLACCacheFiles.belongsTo("$key" + "ff.media", key))
        assertFalse(SpotiFLACCacheFiles.belongsTo("$key.media", ""))
    }

    @Test
    fun `an unfinished transfer is recognised in both shapes the runtime writes`() {
        assertTrue(SpotiFLACCacheFiles.isPartial("$key.media.m4a.partial"))
        assertTrue(
            SpotiFLACCacheFiles.isPartial("$key.media.m4a.partial.checkpoint.json.segments"),
        )
        // A finished file is finished, whatever its container.
        assertFalse(SpotiFLACCacheFiles.isPartial("$key.media"))
        assertFalse(SpotiFLACCacheFiles.isPartial("$key.media.m4a"))
        assertFalse(SpotiFLACCacheFiles.isPartial("$key.flac"))
    }

    @Test
    fun `a live write covers its partial as well as its finished file`() {
        val writing = setOf(key)
        assertTrue(SpotiFLACCacheFiles.isBeingWritten("$key.media", writing))
        assertTrue(SpotiFLACCacheFiles.isBeingWritten("$key.media.m4a.partial", writing))
        assertFalse(
            SpotiFLACCacheFiles.isBeingWritten(
                "b407be2e2eb7403de1ef8e8f.media.m4a.partial",
                writing,
            ),
        )
    }
}
