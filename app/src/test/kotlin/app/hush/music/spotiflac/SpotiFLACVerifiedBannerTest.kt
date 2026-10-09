/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what a verified-source banner says.
 *
 * The wording is the whole payload: a banner that named no source would leave a user who solved one
 * of four checks unable to tell which one landed, and this is the only place any of it is decided.
 */
class SpotiFLACVerifiedBannerTest {

    @Test
    fun `the banner names the source as the registry calls it`() {
        assertEquals("Qobuz verified", SpotiFLACVerifiedBanner.verifiedText("qobuz-web", "Qobuz"))
    }

    @Test
    fun `an unknown source falls back to its id`() {
        // The registry is fetched at app start; a source it has not listed yet must still be reported
        // as something rather than as nothing.
        assertEquals("qobuz-web verified", SpotiFLACVerifiedBanner.verifiedText("qobuz-web", null))
    }

    @Test
    fun `a blank name falls back to the id rather than drawing nothing`() {
        assertEquals("deezer verified", SpotiFLACVerifiedBanner.verifiedText("deezer", "   "))
    }

    @Test
    fun `the registry name is trimmed`() {
        assertEquals("Amazon Music verified", SpotiFLACVerifiedBanner.verifiedText("amazon", " Amazon Music "))
    }
}
