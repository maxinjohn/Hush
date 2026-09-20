package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The runtime walks the candidate list itself, so an attempt requested for one source is
 * routinely abandoned on another. These pin the reading of its own log for that provider,
 * against lines taken verbatim from a device trace where an `amazon` attempt was abandoned
 * while the runtime was waiting on `deezer`.
 */
class SpotiFLACRuntimeWalkTest {

    @Test
    fun `the provider the walk stopped on is the last one it named`() {
        // Verbatim from the diag log of an abandoned attempt: the runtime was asked for
        // `amazon`, walked past it, and went quiet while waiting on `deezer`.
        val messages =
            listOf(
                "DownloadWithExtensionFallback: Track source is extension 'amazon' matching selected provider, trying it first",
                "DownloadWithExtensionFallback: Provider amazon maps requested quality \"LOSSLESS\" to \"best\"",
                "DownloadWithExtensionFallback: Source extension amazon failed: Invalid track ID / ASIN:",
                "DownloadWithExtensionFallback: Trying provider: amazon",
                "DownloadWithExtensionFallback: amazon: not available (reason: not_found_on_amazon)",
                "DownloadWithExtensionFallback: Trying provider: deezer",
                "DownloadWithExtensionFallback: Provider deezer maps requested quality \"LOSSLESS\" to \"flac\"",
                "SignedSession: Provider temporarily unavailable for extension deezer; retrying in 10s (attempt 2/3)",
            )
        assertEquals("deezer", SpotiFLACRuntimeWalk.providerOf(messages))
    }

    @Test
    fun `a declared retry names the provider that is refusing`() {
        assertEquals(
            "qobuz-web",
            SpotiFLACRuntimeWalk.providerIn(
                "SignedSession: Provider temporarily unavailable for extension qobuz-web; retrying in 10s (attempt 2/3)",
            ),
        )
    }

    @Test
    fun `moving a source to the front is the walk naming it`() {
        assertEquals(
            "qobuz-web",
            SpotiFLACRuntimeWalk.providerIn(
                "DownloadWithExtensionFallback: Extension service 'qobuz-web' moved to priority front",
            ),
        )
    }

    @Test
    fun `lines that name something other than a provider are ignored`() {
        // The tag carries an extension name and the track id is a number - neither is the
        // provider the walk is on, and reading either would demote at random.
        assertNull(
            SpotiFLACRuntimeWalk.providerIn(
                "Extension:amazon:INFO: [Amazon] SongLink returned status: 401",
            ),
        )
        assertNull(
            SpotiFLACRuntimeWalk.providerIn(
                "DownloadWithExtensionFallback: Downloading from source extension with trackID: 236718839 (stopProviderFallback: false)",
            ),
        )
        assertNull(SpotiFLACRuntimeWalk.providerIn("Lyrics: Trying provider: lrclib"))
        assertNull(SpotiFLACRuntimeWalk.providerIn(""))
    }

    /**
     * A refusal is read from the walk's own account of a provider *failing*.
     *
     * Verbatim from the reporting device: Deezer answered this to every attempt while Hush kept
     * asking it first on every track, because a fast refusal is not a stall and so nothing was
     * recording it.
     */
    @Test
    fun `a provider that answered with a rate limit is reported as refusing service`() {
        val messages =
            listOf(
                "DownloadWithExtensionFallback: deezer failed: Failed to resolve Deezer download: HTTP 429 for /tickets",
                "DownloadWithExtensionFallback: Trying provider: tidal-web",
                "DownloadWithExtensionFallback: tidal-web failed: stream resolution timeout",
            )
        assertEquals(
            listOf("deezer"),
            SpotiFLACRuntimeWalk.rateLimitRefusals(
                messages,
                knownProviders = listOf("deezer", "tidal-web", "qobuz-web"),
            ),
        )
    }

    /** A timeout is not a refusal: it is the provider saying nothing, which the watchdog handles. */
    @Test
    fun `a plain failure is not a refusal`() {
        assertEquals(
            emptyList<String>(),
            SpotiFLACRuntimeWalk.rateLimitRefusals(
                listOf(
                    "DownloadWithExtensionFallback: tidal-web failed: stream resolution timeout",
                    "DownloadWithExtensionFallback: soundcloud failed: No confident playable match",
                    "DownloadWithExtensionFallback: amazon failed: not_found_on_amazon",
                ),
                knownProviders = listOf("tidal-web", "soundcloud", "amazon"),
            ),
        )
    }

    /**
     * Only names from the chain can be recorded.
     *
     * The log is evidence, not a verdict: "stream resolution failed: HTTP 429" would otherwise put
     * a provider called `resolution` into the candidate set and take a working provider's place out
     * of the chain.
     */
    @Test
    fun `a name that is not a provider in the chain is ignored`() {
        assertEquals(
            emptyList<String>(),
            SpotiFLACRuntimeWalk.rateLimitRefusals(
                listOf(
                    "DownloadWithExtensionFallback: resolution failed: HTTP 429 too many requests",
                    "Lyrics: Provider lrclib failed: rate limit reached",
                ),
                knownProviders = listOf("deezer", "tidal-web"),
            ),
        )
    }

    /** Several providers can refuse in one walk, and each is named once. */
    @Test
    fun `every refusing provider is reported once, in the order the walk met them`() {
        assertEquals(
            listOf("deezer", "qobuz-web"),
            SpotiFLACRuntimeWalk.rateLimitRefusals(
                listOf(
                    "DownloadWithExtensionFallback: deezer failed: HTTP 429 for /tickets",
                    "DownloadWithExtensionFallback: qobuz-web failed: 429 Too Many Requests",
                    "DownloadWithExtensionFallback: deezer failed: HTTP 429 for /tickets",
                ),
                knownProviders = listOf("deezer", "qobuz-web", "amazon"),
            ),
        )
    }

    @Test
    fun `a buffer that never names a provider answers nothing rather than guessing`() {
        assertNull(
            SpotiFLACRuntimeWalk.providerOf(
                listOf(
                    "ExtensionPerf: extension=deezer op=searchTracks totalMs=160.5 items=0",
                    "Lyrics: Provider lrclib failed: no lyrics found",
                ),
            ),
        )
    }
}
