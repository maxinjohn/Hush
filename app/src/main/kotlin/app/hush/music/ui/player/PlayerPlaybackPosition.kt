/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

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
import app.hush.music.models.MediaMetadata
import app.hush.music.playback.PlayerConnection
import app.hush.music.ui.utils.rememberMotionCadence
import kotlin.math.abs

private const val SeekbarSettleToleranceMs = 1_500L

/**
 * How long a released seek bar may keep showing the position it asked for.
 *
 * The bar shows the dragged value until the player gets there, which is what keeps it from
 * snapping back to the old position while a seek is in flight. That rule had no ceiling, and a
 * player that cannot honour a seek - a payload with no index to seek in, so the request is
 * adjusted back to the first sample - never arrives: the bar stayed on the asked-for position
 * and the transport kept the fetching ring, which was reported as "the play button is spinning"
 * and "seeking does not work". The player's own position is the truth after this long, whether
 * the seek was honoured or not, so the bar goes back to it and the ring goes back to play/pause.
 */
private const val SeekbarSettleCeilingMs = 1_500L

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
    // The slider only has to look continuous. Polling it faster than the device can draw
    // simply moves work onto the main thread for frames that are never shown.
    val cadence = rememberMotionCadence()
    val state =
        remember(mediaMetadata?.id) {
            PlayerPlaybackPositionState(
                initialPositionMs = player.currentPosition,
                initialDurationMs = player.duration,
            )
        }

    // The ceiling for a seek that is never going to land. Restarted by every drag (each move
    // sets a new value), and it only starts counting once the gesture has ended, so a held
    // thumb is never taken out from under the finger.
    androidx.compose.runtime.LaunchedEffect(sliderPosition, isUserSeeking) {
        if (sliderPosition == null || isUserSeeking) return@LaunchedEffect
        delay(SeekbarSettleCeilingMs)
        onSliderSettled()
    }

    androidx.compose.runtime.LaunchedEffect(mediaMetadata?.id, playbackState, aodModeEnabled) {
        val startTime = SystemClock.elapsedRealtime()
        if (playbackState == STATE_READY) {
            while (isActive) {
                delay(if (aodModeEnabled) 500L else cadence.playbackPositionTickMs)
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
