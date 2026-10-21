/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A switched-off source is not renewed.
 *
 * Measured on the reporting device: one source switched on, twelve extension packages installed,
 * and every launch plus every hourly run asked the gateway about all thirteen. The user sees the
 * other half of it on the Audio Sources screen - rows they had switched off sitting in "Checking
 * session..." - and the gateway sees a client spending its rate limit on sources nobody has asked
 * to play.
 *
 * The rule has one exception, and it is not "renew everything when unsure": an install that has
 * never recorded a choice must keep renewing, because that install's defaults are all on and a
 * cache of "no switches" is indistinguishable from "everything off" if the two are conflated.
 */
class SwitchedOffSourcesAreNotRenewedTest {
    private fun session(id: String) =
        SpotiFLACSessionRenewer.ExtensionSession(
            extensionId = id,
            displayName = id,
            namespace = "ns",
            appVersion = "1.0.0",
            expiresAtMillis = null,
            hasSession = true,
            recordFile = File("/tmp/$id.session"),
        )

    @Test
    fun `a session whose source is switched off is left alone`() {
        val selection =
            SpotiFLACSessionRenewer.renewalsFor(
                sessions = listOf(session("deezer"), session("amazon"), session("tidal-web")),
                switchedOn = setOf("deezer"),
            )

        assertEquals(listOf("deezer"), selection.candidates.map { it.extensionId })
        assertEquals(
            listOf("amazon", "tidal-web"),
            selection.skipped.map { it.extensionId },
        )
    }

    /** The stored switch carries whatever spelling the registry used. */
    @Test
    fun `the match is case insensitive`() {
        val selection =
            SpotiFLACSessionRenewer.renewalsFor(
                sessions = listOf(session("Qobuz-Web")),
                switchedOn = setOf("qobuz-web"),
            )

        assertEquals(1, selection.candidates.size)
        assertTrue(selection.skipped.isEmpty())
    }

    /** Nothing recorded is not the same answer as nothing switched on. */
    @Test
    fun `an install that has never recorded a choice renews everything`() {
        val all = listOf(session("deezer"), session("amazon"))
        val selection = SpotiFLACSessionRenewer.renewalsFor(sessions = all, switchedOn = null)

        assertEquals(all, selection.candidates)
        assertTrue(selection.skipped.isEmpty())
    }

    /** ... and an install whose every source is off asks the gateway nothing at all. */
    @Test
    fun `nothing switched on means nothing is asked`() {
        val selection =
            SpotiFLACSessionRenewer.renewalsFor(
                sessions = listOf(session("deezer"), session("amazon")),
                switchedOn = emptySet(),
            )

        assertTrue(selection.candidates.isEmpty())
        assertEquals(2, selection.skipped.size)
    }
}
