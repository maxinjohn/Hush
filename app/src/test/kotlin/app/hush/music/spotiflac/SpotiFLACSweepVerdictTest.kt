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

    /**
     * A source that refused service had no chance to answer about the track, so it is not a verdict.
     *
     * `Tido · Lossless answered HTTP 403 (Unavailable)` is the measured refusal; the addon never
     * named a track, so recording it as a catalogue miss would hide the song for hours.
     */
    @Test
    fun `an addon's refusal is not a catalogue verdict`() {
        assertFalse(
            SpotiFLACSweepVerdict.isCatalogueVerdict(
                "tido-tido-tido-dpdns-org-a48bf403dcfc40ae94f668c562d08201:com.yourname.tidal.hifi" +
                    "=left out (Tido · Lossless answered HTTP 403 (Unavailable))",
            ),
        )
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("answered HTTP 403"))
        assertFalse(SpotiFLACSweepVerdict.isCatalogueVerdict("answered HTTP 401"))
    }

    /**
     * A refusal is read as one, because the player's line depends on the difference.
     *
     * `no audio` was what the walk used to record for an addon that answered `403 (Unavailable)`, and
     * the line built from it - "No source has this track · Tido" - is a claim about Tidal's catalogue
     * that the addon never made. Both shapes the bridge writes are pinned here: the aggregate of a
     * walk that asked, and the sentence thrown when the walk had nothing to ask.
     */
    @Test
    fun `a source's refusal reads as a refusal and a catalogue answer does not`() {
        assertTrue(
            SpotiFLACSweepVerdict.isRefusal(
                "All SpotiFLAC sources failed (tido-tido-tido-dpdns-org-a48bf403dcfc40ae94f668c562d08201" +
                    ":com.yourname.tidal.hifi=Tido · Lossless answered HTTP 403 (Unavailable))",
            ),
        )
        assertTrue(
            SpotiFLACSweepVerdict.isRefusal(
                "Every enabled SpotiFLAC source has refused service: tido..." +
                    "(Tido answered HTTP 403 (Unavailable))",
            ),
        )
        assertFalse(
            SpotiFLACSweepVerdict.isRefusal(
                "All SpotiFLAC sources failed (deezer=no results returned; qobuz-web=no audio)",
            ),
        )
        assertFalse(SpotiFLACSweepVerdict.isRefusal("sweep did not finish"))
        assertFalse(SpotiFLACSweepVerdict.isRefusal(null))
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
