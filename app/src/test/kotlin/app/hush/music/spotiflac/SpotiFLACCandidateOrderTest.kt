/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These pin down the difference between demoting a source and dropping it, and the
 * promise that user order survives the reconciliation. Both were real: a device with
 * neither an `apple-music` nor a `pandora` extension was handing both to every sweep
 * first, while `ytmusic-spotiflac` - installed, and able to download - never appeared
 * in a sweep built from the registry.
 */
class SpotiFLACCandidateOrderTest {

    private val registry = listOf("tidal-web", "deezer", "qobuz-web", "amazon", "apple-music", "pandora")
    private val installed = listOf("amazon", "deezer", "qobuz-web", "tidal-web", "ytmusic-spotiflac")

    @Test
    fun `a source whose package is missing is tried last, not removed`() {
        val ordered = SpotiFLACCandidateOrder.order(registry, installed)

        assertEquals(listOf("tidal-web", "deezer", "qobuz-web", "amazon"), ordered.loadable)
        assertEquals(listOf("apple-music", "pandora"), ordered.unavailable)
        // Still in the list: the sweep may reach them once nothing loadable worked.
        assertTrue(ordered.all.containsAll(listOf("apple-music", "pandora")))
        assertEquals(
            listOf("tidal-web", "deezer", "qobuz-web", "amazon", "ytmusic-spotiflac", "apple-music", "pandora"),
            ordered.all,
        )
    }

    @Test
    fun `an installed source the registry omits is swept`() {
        val ordered = SpotiFLACCandidateOrder.order(registry, installed)

        assertEquals(listOf("ytmusic-spotiflac"), ordered.extras)
        assertTrue(ordered.all.contains("ytmusic-spotiflac"))
    }

    @Test
    fun `user priority is preserved inside each group`() {
        val reversed = registry.reversed()
        val ordered = SpotiFLACCandidateOrder.order(reversed, installed)

        assertEquals(listOf("amazon", "qobuz-web", "deezer", "tidal-web"), ordered.loadable)
        assertEquals(listOf("pandora", "apple-music"), ordered.unavailable)
    }

    @Test
    fun `nothing is extracted yet, so the registry is taken at face value`() {
        val ordered = SpotiFLACCandidateOrder.order(registry, installed = emptyList())

        assertEquals(registry, ordered.loadable)
        assertTrue(ordered.unavailable.isEmpty())
        assertTrue(ordered.extras.isEmpty())
        assertEquals(registry, ordered.all)
    }

    @Test
    fun `an empty registry falls back to what is installed`() {
        val ordered = SpotiFLACCandidateOrder.order(registry = emptyList(), installed = installed)

        assertTrue(ordered.loadable.isEmpty())
        assertEquals(installed, ordered.extras)
        assertEquals(installed, ordered.all)
    }

    @Test
    fun `a source id is matched regardless of case`() {
        val ordered = SpotiFLACCandidateOrder.order(registry, installed = listOf("AMAZON", "Deezer"))

        assertEquals(listOf("deezer", "amazon"), ordered.loadable)
        assertTrue(ordered.extras.isEmpty())
    }

    @Test
    fun `neither list yields no candidates rather than a duplicate`() {
        val ordered = SpotiFLACCandidateOrder.order(registry = emptyList(), installed = emptyList())

        assertTrue(ordered.all.isEmpty())
    }

    @Test
    fun `an installed source already in the registry is not added twice`() {
        val ordered = SpotiFLACCandidateOrder.order(listOf("amazon"), installed = listOf("amazon"))

        assertEquals(listOf("amazon"), ordered.all)
    }
}
