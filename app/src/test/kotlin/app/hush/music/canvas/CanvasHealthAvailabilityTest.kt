package app.hush.music.canvas

import app.hush.music.constants.CanvasSource
import app.hush.music.utils.NetworkConnectivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the canvas health availability ladder: the reason a provider row shows
 * *before* any network probe runs, in the order [canvasHealthAvailability] evaluates them.
 *
 * The order is the contract: a row must report the reason closest to the user's intent
 * (source selection, then the switch) before reasons that are only about the network.
 */
class CanvasHealthAvailabilityTest {

    private val ready = NetworkConnectivity(online = true, wifi = true, metered = false)
    private val cellular = NetworkConnectivity(online = true, wifi = false, metered = true)
    private val offline = NetworkConnectivity(online = false, wifi = false, metered = true)

    private fun inputs(
        enabled: Boolean = true,
        source: CanvasSource = CanvasSource.AUTO,
        wifiOnly: Boolean = false,
        lowDataMode: Boolean = false,
        connectivity: NetworkConnectivity = ready,
        spotifyConnected: Boolean = true,
    ) = CanvasHealthInputs(
        enabled = enabled,
        source = source,
        wifiOnly = wifiOnly,
        lowDataMode = lowDataMode,
        connectivity = connectivity,
        spotifyConnected = spotifyConnected,
    )

    private fun availability(
        provider: CanvasSource,
        build: CanvasHealthInputs = inputs(),
    ): CanvasHealth = canvasHealthAvailability(build, provider)

    // ── the happy path: every row is probeable ──────────────────────────────────

    @Test
    fun `every provider reaches CHECKING when the preconditions hold`() {
        for (provider in CanvasSource.values()) {
            assertEquals(CanvasHealth.CHECKING, availability(provider))
        }
    }

    @Test
    fun `a dedicated source still reaches CHECKING for its own provider`() {
        for (source in CanvasSource.values()) {
            assertEquals(CanvasHealth.CHECKING, availability(source, inputs(source = source)))
        }
    }

    // ── rung 1: source selection ────────────────────────────────────────────────

    @Test
    fun `a source that excludes a provider reports NOT_SELECTED`() {
        val onlyApple = inputs(source = CanvasSource.APPLE_MUSIC)

        assertEquals(CanvasHealth.CHECKING, availability(CanvasSource.APPLE_MUSIC, onlyApple))
        assertEquals(CanvasHealth.NOT_SELECTED, availability(CanvasSource.HUSH_CANVAS, onlyApple))
        assertEquals(CanvasHealth.NOT_SELECTED, availability(CanvasSource.TIDAL, onlyApple))
        assertEquals(CanvasHealth.NOT_SELECTED, availability(CanvasSource.SPOTIFY, onlyApple))
    }

    @Test
    fun `NOT_SELECTED is reported before any other reason`() {
        // excluded by the source, and also disabled, offline and disconnected
        val blocked =
            inputs(
                enabled = false,
                source = CanvasSource.TIDAL,
                spotifyConnected = false,
                connectivity = offline,
            )

        assertEquals(CanvasHealth.NOT_SELECTED, availability(CanvasSource.SPOTIFY, blocked))
        assertEquals(CanvasHealth.NOT_SELECTED, availability(CanvasSource.HUSH_CANVAS, blocked))
        // the selected provider falls through to the next rung
        assertEquals(CanvasHealth.DISABLED, availability(CanvasSource.TIDAL, blocked))
    }

    // ── rung 2: the switch ──────────────────────────────────────────────────────

    @Test
    fun `DISABLED is reported before any connectivity reason`() {
        val disabledOffline =
            inputs(enabled = false, wifiOnly = true, lowDataMode = true, connectivity = offline)

        assertEquals(CanvasHealth.DISABLED, availability(CanvasSource.HUSH_CANVAS, disabledOffline))
        assertEquals(CanvasHealth.DISABLED, availability(CanvasSource.SPOTIFY, disabledOffline))
    }

    // ── rung 3: the Spotify account ─────────────────────────────────────────────

    @Test
    fun `the Spotify row needs a connected account`() {
        val disconnected = inputs(spotifyConnected = false)

        assertEquals(CanvasHealth.NOT_CONNECTED, availability(CanvasSource.SPOTIFY, disconnected))
        // other rows are unaffected
        assertEquals(CanvasHealth.CHECKING, availability(CanvasSource.TIDAL, disconnected))
    }

    @Test
    fun `a selected Spotify source without an account is NOT_CONNECTED, not NOT_SELECTED`() {
        val selected =
            inputs(source = CanvasSource.SPOTIFY, spotifyConnected = false)

        assertEquals(CanvasHealth.NOT_CONNECTED, availability(CanvasSource.SPOTIFY, selected))
        assertEquals(CanvasHealth.NOT_SELECTED, availability(CanvasSource.TIDAL, selected))
    }

    // ── rungs 4-6: the network ──────────────────────────────────────────────────

    @Test
    fun `OFFLINE outranks the wifi-only and low-data rules`() {
        val everythingBlocked =
            inputs(wifiOnly = true, lowDataMode = true, connectivity = offline)

        assertEquals(CanvasHealth.OFFLINE, availability(CanvasSource.HUSH_CANVAS, everythingBlocked))
    }

    @Test
    fun `wifi only blocks a link without a Wi-Fi transport`() {
        assertEquals(
            CanvasHealth.WIFI_REQUIRED,
            availability(CanvasSource.TIDAL, inputs(wifiOnly = true, connectivity = cellular)),
        )
        // without the setting the same link is fine
        assertEquals(
            CanvasHealth.CHECKING,
            availability(CanvasSource.TIDAL, inputs(wifiOnly = false, connectivity = cellular)),
        )
    }

    @Test
    fun `WIFI_REQUIRED is reported before LOW_DATA_MODE`() {
        val both =
            inputs(wifiOnly = true, lowDataMode = true, connectivity = cellular)

        assertEquals(CanvasHealth.WIFI_REQUIRED, availability(CanvasSource.TIDAL, both))
    }

    @Test
    fun `low data mode only blocks a metered link`() {
        assertEquals(
            CanvasHealth.LOW_DATA_MODE,
            availability(CanvasSource.TIDAL, inputs(lowDataMode = true, connectivity = cellular)),
        )
        assertEquals(
            CanvasHealth.CHECKING,
            availability(
                CanvasSource.TIDAL,
                inputs(lowDataMode = true, connectivity = NetworkConnectivity(online = true, wifi = false, metered = false)),
            ),
        )
    }

    @Test
    fun `a Wi-Fi link passes the wifi-only rung and falls through to low data`() {
        val wifiButMetered =
            inputs(wifiOnly = true, lowDataMode = true, connectivity = NetworkConnectivity(online = true, wifi = true, metered = true))

        assertEquals(CanvasHealth.LOW_DATA_MODE, availability(CanvasSource.TIDAL, wifiButMetered))
    }

    // ── the four rows together ──────────────────────────────────────────────────

    @Test
    fun `pending health probes all four rows when everything is allowed`() {
        val status = pendingCanvasHealth(inputs())

        assertEquals(
            CanvasHealthStatus(
                hush = CanvasHealth.CHECKING,
                appleMusic = CanvasHealth.CHECKING,
                tidal = CanvasHealth.CHECKING,
                spotify = CanvasHealth.CHECKING,
            ),
            status,
        )
        assertTrue(status.checking)
    }

    @Test
    fun `pending health marks only the rows it can probe as pending`() {
        val status = pendingCanvasHealth(inputs(source = CanvasSource.SPOTIFY, spotifyConnected = false))

        assertEquals(CanvasHealth.NOT_SELECTED, status.hush)
        assertEquals(CanvasHealth.NOT_SELECTED, status.appleMusic)
        assertEquals(CanvasHealth.NOT_SELECTED, status.tidal)
        assertEquals(CanvasHealth.NOT_CONNECTED, status.spotify)
        assertFalse(status.checking) // nothing is probeable: every row already has a reason
    }

    @Test
    fun `a disabled switch parks every row on DISABLED with nothing to probe`() {
        val status = pendingCanvasHealth(inputs(enabled = false))

        assertEquals(CanvasHealth.DISABLED, status.hush)
        assertEquals(CanvasHealth.DISABLED, status.appleMusic)
        assertEquals(CanvasHealth.DISABLED, status.tidal)
        assertEquals(CanvasHealth.DISABLED, status.spotify)
        assertFalse(status.checking)
    }

    @Test
    fun `an offline panel reports OFFLINE for every selected row`() {
        val status = pendingCanvasHealth(inputs(connectivity = offline))

        assertEquals(CanvasHealth.OFFLINE, status.hush)
        assertEquals(CanvasHealth.OFFLINE, status.appleMusic)
        assertEquals(CanvasHealth.OFFLINE, status.tidal)
        assertEquals(CanvasHealth.OFFLINE, status.spotify)
        assertFalse(status.checking)
    }
}
