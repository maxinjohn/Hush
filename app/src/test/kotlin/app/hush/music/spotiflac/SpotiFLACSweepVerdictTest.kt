package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retention a failed sweep earns hangs on this classification, and the expensive
 * mistake is one-directional: a blocked sweep read as a verdict hides a playable track
 * from SpotiFLAC for hours.
 */
class SpotiFLACSweepVerdictTest {

    @Test
    fun `a catalogue answer is a verdict`() {
        assertTrue(SpotiFLACSweepVerdict.isCatalogueVerdict("no results returned for source deezer"))
        assertTrue(SpotiFLACSweepVerdict.isCatalogueVerdict("track not found in qobuz-web"))
        assertTrue(SpotiFLACSweepVerdict.isCatalogueVerdict("no match for this ISRC"))
    }

    @Test
    fun `a transport or session failure is not a verdict`() {
        // These are the real strings this device logged while nothing was wrong with the
        // catalogues.
        assertFalse(
            SpotiFLACSweepVerdict.isCatalogueVerdict(
                "Download failed: verification_required: extension 'amazon' needs signed-session verification",
            ),
        )
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("zarz-v2@6: Provider temporarily unavailable"))
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("unable to resolve host spotiflac.example"))
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("read timed out"))
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("HTTP 503"))
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("too many requests"))
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("no active session"))
    }

    @Test
    fun `one blocked source disqualifies the whole sweep`() {
        // The aggregate mixes sources. A single timeout means at least one catalogue was
        // never consulted, so the sweep cannot speak for the providers as a whole.
        val aggregate =
            "deezer=no results returned; qobuz-web=read timed out; tidal-web=HTTP 502"
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict(aggregate))
    }

    @Test
    fun `an unrecognised failure is not a verdict`() {
        // Fails toward re-sweeping: one extra sweep is much cheaper than hiding a track
        // the providers do have.
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("something entirely new went wrong"))
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict(""))
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict(null))
    }

    @Test
    fun `a verification failure is never a verdict even with a catalogue word in it`() {
        val error = SpotiFLACVerificationRequiredException(
            "amazon",
            "Download failed: verification_required: no results until you verify",
        )
        assertEquals(SpotiFLACSweepOutcome.UNAVAILABLE, SpotiFLACSweepVerdict.forFailure(error))
    }

    @Test
    fun `a missing error is unavailable, not a verdict`() {
        assertEquals(SpotiFLACSweepOutcome.UNAVAILABLE, SpotiFLACSweepVerdict.forFailure(null))
    }

    @Test
    fun `the reason mapping only grants the long retention to a catalogue answer`() {
        assertEquals(
            SpotiFLACMissPolicy.REASON_NO_MATCH,
            SpotiFLACMissPolicy.reasonFor(SpotiFLACSweepOutcome.NO_MATCH),
        )
        assertEquals(
            SpotiFLACMissPolicy.REASON_SWEEP_UNFINISHED,
            SpotiFLACMissPolicy.reasonFor(SpotiFLACSweepOutcome.UNAVAILABLE),
        )
        // A resolved sweep clears the memo rather than recording one, so it must never
        // earn the "no match" reason if it is ever passed here.
        assertEquals(
            SpotiFLACMissPolicy.REASON_SWEEP_UNFINISHED,
            SpotiFLACMissPolicy.reasonFor(SpotiFLACSweepOutcome.RESOLVED),
        )
        // And the two retentions really are different, which is the point of the split.
        assertTrue(
            SpotiFLACMissPolicy.retentionFor(SpotiFLACMissPolicy.REASON_NO_MATCH) >
                SpotiFLACMissPolicy.retentionFor(SpotiFLACMissPolicy.REASON_SWEEP_UNFINISHED),
        )
    }
}
