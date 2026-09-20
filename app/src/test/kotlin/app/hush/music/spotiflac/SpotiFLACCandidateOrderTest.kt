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
 * These pin down two rules. The sweep order is the order the user arranged in Audio
 * Sources - nothing may be reordered around it - and a source the user switched off there
 * is not tried at all. Both were broken: a device with neither an `apple-music` nor a
 * `pandora` extension handed both to every sweep first, and an earlier version swept every
 * installed package, which meant switching a source off in Audio Sources did not stop it
 * being tried.
 */
class SpotiFLACCandidateOrderTest {

    private val enabled = listOf("tidal-web", "deezer", "qobuz-web", "amazon", "apple-music", "pandora")
    private val installed = listOf("amazon", "deezer", "qobuz-web", "tidal-web", "ytmusic-spotiflac")

    @Test
    fun `a source whose package is missing is tried last, not removed`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled, installed)

        assertEquals(listOf("tidal-web", "deezer", "qobuz-web", "amazon"), ordered.loadable)
        assertEquals(listOf("apple-music", "pandora"), ordered.unavailable)
        // Still in the list: the sweep may reach them once nothing loadable worked.
        assertTrue(ordered.all.containsAll(listOf("apple-music", "pandora")))
        assertEquals(
            listOf("tidal-web", "deezer", "qobuz-web", "amazon", "apple-music", "pandora"),
            ordered.all,
        )
    }

    @Test
    fun `an installed source the user has not enabled is not swept`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled, installed)

        assertEquals(listOf("ytmusic-spotiflac"), ordered.notEnabled)
        assertTrue(ordered.all.none { it == "ytmusic-spotiflac" })
    }

    @Test
    fun `user priority is preserved inside each group`() {
        val reversed = enabled.reversed()
        val ordered = SpotiFLACCandidateOrder.order(reversed, installed)

        assertEquals(listOf("amazon", "qobuz-web", "deezer", "tidal-web"), ordered.loadable)
        assertEquals(listOf("pandora", "apple-music"), ordered.unavailable)
    }

    @Test
    fun `nothing is extracted yet, so the enabled list is taken at face value`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled, installed = emptyList())

        assertEquals(enabled, ordered.loadable)
        assertTrue(ordered.unavailable.isEmpty())
        assertTrue(ordered.notEnabled.isEmpty())
        assertEquals(enabled, ordered.all)
    }

    @Test
    fun `an empty enabled list yields no candidates rather than everything installed`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled = emptyList(), installed = installed)

        assertTrue(ordered.all.isEmpty())
        assertEquals(installed, ordered.notEnabled)
    }

    @Test
    fun `a source id is matched regardless of case`() {
        val ordered = SpotiFLACCandidateOrder.order(enabled, installed = listOf("AMAZON", "Deezer"))

        assertEquals(listOf("deezer", "amazon"), ordered.loadable)
    }

    @Test
    fun `an enabled source is not added twice`() {
        val ordered = SpotiFLACCandidateOrder.order(listOf("amazon"), installed = listOf("amazon"))

        assertEquals(listOf("amazon"), ordered.all)
        assertTrue(ordered.notEnabled.isEmpty())
    }

    /**
     * The fallback order, for the window before the registry sync has filled the live
     * source list in. Enumerating the package directory there is alphabetical, which is
     * how a user whose order was `deezer,amazon,qobuz-web,tidal-web` ended up swept as
     * `amazon,deezer,qobuz-web,tidal-web` - measured in the app's own diag log.
     */
    @Test
    fun `installed packages are ordered by the user's priority, not by their names`() {
        val alphabetical = listOf("amazon", "deezer", "qobuz-web", "tidal-web")
        val userOrder = listOf("deezer", "amazon", "qobuz-web", "tidal-web")

        assertEquals(userOrder, SpotiFLACCandidateOrder.bySavedOrder(alphabetical, userOrder))
    }

    @Test
    fun `a source the user has not ranked is ordered after the ones they have`() {
        val installed = listOf("amazon", "deezer", "tidal-web", "ytmusic-spotiflac")
        val savedOrder = listOf("tidal-web", "deezer")

        assertEquals(
            listOf("tidal-web", "deezer", "amazon", "ytmusic-spotiflac"),
            SpotiFLACCandidateOrder.bySavedOrder(installed, savedOrder),
        )
    }

    @Test
    fun `an id is matched regardless of case when ordering`() {
        val installed = listOf("AMAZON", "Deezer")

        assertEquals(
            listOf("Deezer", "AMAZON"),
            SpotiFLACCandidateOrder.bySavedOrder(installed, listOf("deezer", "amazon")),
        )
    }

    @Test
    fun `with no recorded order the installed order is left alone`() {
        val installed = listOf("amazon", "deezer")

        assertEquals(installed, SpotiFLACCandidateOrder.bySavedOrder(installed, emptyList()))
    }
}
