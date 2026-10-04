/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.canvas

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import app.hush.music.constants.CanvasSource
import app.hush.music.utils.NetworkConnectivity

/** Lifecycle of one provider row on the settings health panel. */
enum class CanvasHealth {
    NOT_CHECKED,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    NOT_SELECTED,
    NOT_CONNECTED,
    DISABLED,
    OFFLINE,
    WIFI_REQUIRED,
    LOW_DATA_MODE,
}

data class CanvasHealthStatus(
    val hush: CanvasHealth = CanvasHealth.NOT_CHECKED,
    val appleMusic: CanvasHealth = CanvasHealth.NOT_CHECKED,
    val tidal: CanvasHealth = CanvasHealth.NOT_CHECKED,
    val spotify: CanvasHealth = CanvasHealth.NOT_CHECKED,
) {
    val checking: Boolean
        get() =
            hush == CanvasHealth.CHECKING ||
                appleMusic == CanvasHealth.CHECKING ||
                tidal == CanvasHealth.CHECKING ||
                spotify == CanvasHealth.CHECKING
}

/** Everything the health logic derives its per-row availability from. */
data class CanvasHealthInputs(
    val enabled: Boolean,
    val source: CanvasSource,
    val wifiOnly: Boolean,
    val lowDataMode: Boolean,
    val connectivity: NetworkConnectivity,
    val spotifyConnected: Boolean,
)

/**
 * The state a row has before (or without) a live probe: why it cannot be checked, or CHECKING
 * when every precondition holds and a probe would succeed-or-fail on its own.
 */
fun canvasHealthAvailability(
    inputs: CanvasHealthInputs,
    provider: CanvasSource,
): CanvasHealth =
    when {
        !inputs.source.accepts(provider) -> CanvasHealth.NOT_SELECTED
        !inputs.enabled -> CanvasHealth.DISABLED
        provider == CanvasSource.SPOTIFY && !inputs.spotifyConnected -> CanvasHealth.NOT_CONNECTED
        !inputs.connectivity.online -> CanvasHealth.OFFLINE
        inputs.wifiOnly && !inputs.connectivity.wifi -> CanvasHealth.WIFI_REQUIRED
        inputs.lowDataMode && inputs.connectivity.metered -> CanvasHealth.LOW_DATA_MODE
        else -> CanvasHealth.CHECKING
    }

/** The four rows with their pre-probe availability, used to reset results on config changes. */
fun pendingCanvasHealth(inputs: CanvasHealthInputs): CanvasHealthStatus =
    CanvasHealthStatus(
        hush = canvasHealthAvailability(inputs, CanvasSource.HUSH_CANVAS),
        appleMusic = canvasHealthAvailability(inputs, CanvasSource.APPLE_MUSIC),
        tidal = canvasHealthAvailability(inputs, CanvasSource.TIDAL),
        spotify = canvasHealthAvailability(inputs, CanvasSource.SPOTIFY),
    )

/** Rows whose availability is CHECKING get a live probe; the rest keep their reason. */
suspend fun checkCanvasHealth(inputs: CanvasHealthInputs): CanvasHealthStatus =
    coroutineScope {
        val hush = async { checkProvider(inputs, CanvasSource.HUSH_CANVAS) { HushCanvas.isHealthy() } }
        val appleMusic = async { checkProvider(inputs, CanvasSource.APPLE_MUSIC) { AppleMusicProvider.isHealthy() } }
        val tidal = async { checkProvider(inputs, CanvasSource.TIDAL) { TidalCanvasProvider.isHealthy() } }
        val spotify = async { checkProvider(inputs, CanvasSource.SPOTIFY) { SpotifyCanvasProvider.isHealthy() } }
        CanvasHealthStatus(
            hush = hush.await(),
            appleMusic = appleMusic.await(),
            tidal = tidal.await(),
            spotify = spotify.await(),
        )
    }

private suspend fun checkProvider(
    inputs: CanvasHealthInputs,
    provider: CanvasSource,
    probe: suspend () -> Boolean,
): CanvasHealth {
    val availability = canvasHealthAvailability(inputs, provider)
    if (availability != CanvasHealth.CHECKING) return availability
    return try {
        val timeoutMs = if (provider == CanvasSource.SPOTIFY) 45_000L else 20_000L
        if (withTimeoutOrNull(timeoutMs) { probe() } == true) {
            CanvasHealth.AVAILABLE
        } else {
            CanvasHealth.UNAVAILABLE
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        CanvasHealth.UNAVAILABLE
    }
}
