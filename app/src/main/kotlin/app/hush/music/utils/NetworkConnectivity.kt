/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService

/**
 * Snapshot of the active network, in the shape the canvas policy reasons about.
 *
 * [wifi] is deliberately stricter than "not cellular": a VPN or tethered link without a Wi-Fi
 * transport does not satisfy a Wi-Fi-only setting.
 */
data class NetworkConnectivity(
    val online: Boolean = false,
    val wifi: Boolean = false,
    val metered: Boolean = true,
)

fun Context.currentNetworkConnectivity(): NetworkConnectivity {
    val manager = getSystemService<ConnectivityManager>() ?: return NetworkConnectivity()
    val network = manager.activeNetwork ?: return NetworkConnectivity()
    return manager.getNetworkCapabilities(network)?.toNetworkConnectivity() ?: NetworkConnectivity()
}

private fun NetworkCapabilities.toNetworkConnectivity(): NetworkConnectivity =
    NetworkConnectivity(
        online =
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        wifi =
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        metered =
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
    )

/**
 * Observes the active network while composed, starting from a synchronous read so the first
 * frame already has a correct answer instead of flashing an "offline" state.
 */
@Composable
fun rememberNetworkConnectivity(): NetworkConnectivity {
    val context = LocalContext.current
    var connectivity by remember(context) { mutableStateOf(context.currentNetworkConnectivity()) }

    DisposableEffect(context) {
        val manager = context.getSystemService<ConnectivityManager>()
        if (manager == null) {
            connectivity = NetworkConnectivity()
            return@DisposableEffect onDispose {}
        }

        val handler = Handler(Looper.getMainLooper())
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivity = context.currentNetworkConnectivity()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    connectivity = networkCapabilities.toNetworkConnectivity()
                }

                override fun onLost(network: Network) {
                    connectivity = context.currentNetworkConnectivity()
                    // The system can deliver onLost while the departing network still answers
                    // as active, which would leave the state stuck at the stale value with no
                    // further callback coming (there is no replacement network). Re-read once
                    // after it settles; the answer is always the current truth either way.
                    handler.postDelayed(
                        { connectivity = context.currentNetworkConnectivity() },
                        SettleDelayMillis,
                    )
                }
            }

        connectivity = context.currentNetworkConnectivity()
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { connectivity = context.currentNetworkConnectivity() }

        onDispose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    return connectivity
}

private const val SettleDelayMillis = 500L
