/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import app.hush.music.constants.SpotiFLACEnabledDefault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that decides whether a download may be *fetched* by the third-party engine.
 *
 * The failure this exists for is the one nobody sees in a log: low data mode is a promise that no
 * lossless byte is pulled over a metered link, and the download path was the one place that answered
 * only the engine's switch. So on an install with low data mode on the file it produced was a whole
 * FLAC fetched at the user's BEST setting, while the screen they had just looked at drew the
 * third-party switch off and the resolve order refused to route playback to the same engine. Four
 * cases, because "off" has two ways to be off and only one of them is the switch: a rule that forgot
 * either would be silent in exactly the direction that costs the user data.
 *
 * What the rule deliberately does not cover is a file already on disk - a copy costs no link - which
 * is why the caller asks it after its local-copy branch rather than before it. That ordering is not
 * this test's to hold; the predicate it hands over is.
 */
class SpotiFLACDownloadPolicyTest {

    @Test
    fun `a switched-on engine fetches when the link is unmetered`() {
        assertTrue(spotiFLACMayServeDownload(engineEnabled = true, lowDataActive = false))
    }

    @Test
    fun `low data mode refuses the fetch even with the engine switched on`() {
        // The bug: the switch said yes, low data mode said no, and the download asked anyway.
        assertFalse(spotiFLACMayServeDownload(engineEnabled = true, lowDataActive = true))
    }

    @Test
    fun `a switched-off engine fetches nothing, metered or not`() {
        assertFalse(spotiFLACMayServeDownload(engineEnabled = false, lowDataActive = false))
        assertFalse(spotiFLACMayServeDownload(engineEnabled = false, lowDataActive = true))
    }

    @Test
    fun `the unmetered case is the out-of-the-box one`() {
        // The engine ships on, so the first case above is what a fresh install does with a download
        // rather than an install that went looking for the switch; the low-data case is what protects
        // one that has low data mode on.
        assertTrue(SpotiFLACEnabledDefault)
    }
}
