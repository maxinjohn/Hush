/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.hush.music.network.NetworkBannerUiState

private data class NetworkBannerVisuals(
    val message: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
)

@Composable
fun NetworkStatusBanner(
    state: NetworkBannerUiState,
    modifier: Modifier = Modifier,
) {
    var lastVisibleState by remember { mutableStateOf<NetworkBannerUiState>(NetworkBannerUiState.Offline) }

    if (state != NetworkBannerUiState.Hidden) {
        lastVisibleState = state
    }

    val visuals =
        when (lastVisibleState) {
            NetworkBannerUiState.Hidden,
            NetworkBannerUiState.Offline,
            -> {
                NetworkBannerVisuals(
                    message = "No internet connection",
                    icon = Icons.Default.CloudOff,
                    containerColor = Color(0xFF7F1D1D),
                    contentColor = Color.White,
                )
            }

            NetworkBannerUiState.BackOnline -> {
                NetworkBannerVisuals(
                    message = "Back online",
                    icon = Icons.Default.CloudDone,
                    containerColor = Color(0xFF1E8E3E),
                    contentColor = Color.White,
                )
            }
        }

    AnimatedVisibility(
        visible = state != NetworkBannerUiState.Hidden,
        modifier = modifier,
        enter =
            slideInVertically(animationSpec = tween(durationMillis = 250)) { -it } +
                fadeIn(animationSpec = tween(durationMillis = 180)),
        exit =
            slideOutVertically(animationSpec = tween(durationMillis = 220)) { -it } +
                fadeOut(animationSpec = tween(durationMillis = 180)),
        label = "networkStatusBanner",
    ) {
        HushStatusBanner(
            message = visuals.message,
            icon = visuals.icon,
            containerColor = visuals.containerColor,
            contentColor = visuals.contentColor,
        )
    }
}
