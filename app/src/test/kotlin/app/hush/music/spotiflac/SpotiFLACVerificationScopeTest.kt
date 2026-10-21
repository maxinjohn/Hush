/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A switched-off source is never asked to verify.
 *
 * Measured on the reporting device: two Tido addons on, every native source off, and a playback that
 * could not be served raised challenges for `amazon` and `tidal-web` - browser trips and "Verifying…"
 * rows for sources the user had deliberately turned off. The cause was not the verifier but the list
 * it was handed: the *native* candidate order is empty when the only switched-on sources are addons,
 * and empty was read as "no choice recorded", which lets every installed package through.
 *
 * The install in the first case is that device's own: the native list is empty while the switches
 * name two sources, so the answer has to be "none", not "everything".
 */
class SpotiFLACVerificationScopeTest {

    private val installed =
        listOf("amazon", "deezer", "qobuz-web", "tidal-web", "soundcloud", "ytmusic-spotiflac")

    private val tido =
        setOf(
            "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi",
            "tido-tido-tido-dpdns-org-a48bf403dcfc40ae94f668c562d08201:com.yourname.tidal.hifi",
        )

    @Test
    fun `an addon-only selection verifies none of the installed native sources`() {
        assertEquals(
            emptyList<String>(),
            SpotiFLACVerificationScope.targetable(installed, tido),
        )
    }

    @Test
    fun `a switched-off source is left out while a switched-on one is kept`() {
        assertEquals(
            listOf("deezer"),
            SpotiFLACVerificationScope.targetable(installed, setOf("deezer")),
        )
    }

    /** Nothing recorded is not the same answer as nothing switched on. */
    @Test
    fun `an install that has never recorded a choice keeps every source`() {
        assertEquals(
            installed,
            SpotiFLACVerificationScope.targetable(installed, null),
        )
    }

    /** ... and an install whose every source is off asks about none of them. */
    @Test
    fun `nothing switched on means nothing is verified`() {
        assertEquals(
            emptyList<String>(),
            SpotiFLACVerificationScope.targetable(installed, emptySet()),
        )
    }

    /** The ids come from a package directory on one side and a manifest on the other. */
    @Test
    fun `the match is case insensitive`() {
        assertEquals(
            listOf("Tidal-Web"),
            SpotiFLACVerificationScope.targetable(listOf("Tidal-Web"), setOf("tidal-web")),
        )
    }
}
