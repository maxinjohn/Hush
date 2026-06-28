/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.C
import androidx.media3.common.Player.STATE_READY
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import kotlin.math.abs

private const val SeekbarSettleToleranceMs = 1_500L

@Stable
class PlayerPlaybackPositionState(
    initialPositionMs: Long,
    initialDurationMs: Long,
) {
    var positionMs by mutableLongStateOf(initialPositionMs)
    var durationMs by mutableLongStateOf(initialDurationMs)
}

val LocalPlayerPlaybackPosition =
    staticCompositionLocalOf<PlayerPlaybackPositionState> {
        error("PlayerPlaybackPositionState not provided")
    }

@Composable
fun rememberPlayerPlaybackPositionState(
    playerConnection: PlayerConnection,
    mediaMetadata: MediaMetadata?,
    playbackState: Int,
    aodModeEnabled: Boolean,
    isUserSeeking: Boolean,
    sliderPosition: Long?,
    onSliderSettled: () -> Unit,
): PlayerPlaybackPositionState {
    val player = playerConnection.player
    val state =
        remember(mediaMetadata?.id) {
            PlayerPlaybackPositionState(
                initialPositionMs = player.currentPosition,
                initialDurationMs = player.duration,
            )
        }

    androidx.compose.runtime.LaunchedEffect(mediaMetadata?.id, playbackState, aodModeEnabled) {
        val startTime = SystemClock.elapsedRealtime()
        if (playbackState == STATE_READY) {
            while (isActive) {
                delay(if (aodModeEnabled) 500L else 100L)
                val isTransitioning = player.currentMediaItem?.mediaId != mediaMetadata?.id
                val currentPlayerPosition = player.currentPosition
                val currentPlayerDuration = player.duration

                if (isTransitioning) {
                    val elapsedSinceStart = SystemClock.elapsedRealtime() - startTime
                    state.positionMs = elapsedSinceStart
                    mediaMetadata?.let {
                        val metaDuration = it.duration.toLong() * 1000
                        state.durationMs = if (metaDuration > 0) metaDuration else 0L
                    }
                } else {
                    state.positionMs = currentPlayerPosition
                    state.durationMs = currentPlayerDuration
                    if (!isUserSeeking) {
                        sliderPosition?.let { targetPosition ->
                            val clampedTargetPosition =
                                when {
                                    currentPlayerDuration > 0L && currentPlayerDuration != C.TIME_UNSET -> {
                                        targetPosition.coerceIn(0L, currentPlayerDuration)
                                    }

                                    else -> {
                                        targetPosition.coerceAtLeast(0L)
                                    }
                                }
                            if (abs(currentPlayerPosition - clampedTargetPosition) <= SeekbarSettleToleranceMs) {
                                onSliderSettled()
                            }
                        }
                    }
                }
            }
        } else {
            mediaMetadata?.let {
                val metaDuration = it.duration.toLong() * 1000
                state.durationMs = if (metaDuration > 0) metaDuration else 0L
            }
            val currentPlayerPosition = player.currentPosition
            if (sliderPosition == null && currentPlayerPosition > 0L) {
                state.positionMs = currentPlayerPosition
            }
        }
    }

    return state
}
