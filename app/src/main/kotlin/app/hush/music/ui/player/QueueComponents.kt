/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.player

import app.hush.music.ui.theme.HushDesign
import app.hush.music.ui.component.HushProgressSpinner
import androidx.compose.animation.AnimatedContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hush.music.LocalPlayerConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import app.hush.music.ui.component.HushIconButton
import app.hush.music.ui.component.hushBouncyClickable
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import android.content.res.Configuration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import app.hush.music.playback.QueuePrefetchState
import app.hush.music.playback.queuePrefetchState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import app.hush.music.R
import app.hush.music.constants.AuraLandscapeBarVerticalPadding
import app.hush.music.constants.AuraLandscapePillHeight
import app.hush.music.constants.EnableHapticFeedbackKey
import app.hush.music.constants.PrimaryAudioScraper
import app.hush.music.constants.PrimaryAudioScraperKey
import app.hush.music.db.entities.autoRateDisplay
import app.hush.music.db.entities.codecRowDetail
import app.hush.music.db.entities.containerLabel
import app.hush.music.db.entities.formattedBitrate
import app.hush.music.models.ActiveOutputDevice
import app.hush.music.models.MediaMetadata
import app.hush.music.ui.component.ActionPromptDialog
import app.hush.music.ui.component.BottomSheetPageState
import app.hush.music.ui.component.BottomSheetState
import app.hush.music.ui.component.MenuState
import app.hush.music.ui.component.bottomSheetDraggable
import app.hush.music.ui.component.hushMarquee
import app.hush.music.ui.theme.hushPressable
import app.hush.music.ui.component.hushPressMotion
import app.hush.music.ui.component.rememberPressInteractionSource
import app.hush.music.ui.menu.PlayerMenu
import app.hush.music.ui.utils.ShowMediaInfo
import app.hush.music.utils.makeTimeString
import app.hush.music.playback.FormatRow
import app.hush.music.playback.FormatRowSource
import app.hush.music.playback.PlaybackEngine
import app.hush.music.playback.PlaybackSourceLabels
import app.hush.music.utils.PlaybackDownloadProgress
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import kotlin.math.roundToInt

/**
 * Current Song Header shown at the top of the queue
 * Displays album art, song info, and control buttons
 */
@Composable
fun CurrentSongHeader(
    sheetState: BottomSheetState,
    mediaMetadata: MediaMetadata?,
    liked: Boolean,
    isPlaying: Boolean,
    repeatMode: Int,
    shuffleModeEnabled: Boolean,
    locked: Boolean,
    songCount: Int,
    queueDuration: Int,
    infiniteQueueEnabled: Boolean,
    infiniteQueueLoading: Boolean,
    backgroundColor: Color,
    onBackgroundColor: Color,
    onToggleLike: () -> Unit,
    onMenuClick: () -> Unit,
    onClearQueueClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onLockClick: () -> Unit,
    onInfiniteQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp > configuration.screenHeightDp

    val topPadding = if (isLandscape) 6.dp else 20.dp
    val bottomPadding = if (isLandscape) 4.dp else 8.dp
    val horizontalPadding = if (isLandscape) 12.dp else 16.dp
    val artSize = if (isLandscape) 48.dp else 64.dp
    val titleStyle =
        if (isLandscape) {
            MaterialTheme.typography.titleMedium
        } else {
            MaterialTheme.typography.titleLarge
        }
    val sectionGap = if (isLandscape) 6.dp else 14.dp
    val handleBottomGap = if (isLandscape) 6.dp else 16.dp
    val toggleButtonSize = if (isLandscape) 40.dp else 48.dp
    val actionIconButtonSize = if (isLandscape) 36.dp else 40.dp
    val actionIconSize = if (isLandscape) 18.dp else 20.dp
    val likeButtonSize = if (isLandscape) 38.dp else 44.dp
    val likeIconSize = if (isLandscape) 22.dp else 26.dp

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .bottomSheetDraggable(sheetState)
                .padding(horizontal = horizontalPadding)
                .padding(top = topPadding, bottom = bottomPadding),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = handleBottomGap),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(if (isLandscape) 40.dp else 48.dp)
                        .height(if (isLandscape) 4.dp else 5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(onBackgroundColor.copy(alpha = 0.4f)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 10.dp else 14.dp),
        ) {
            AsyncImage(
                model = mediaMetadata?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(artSize)
                        .clip(RoundedCornerShape(if (isLandscape) 10.dp else 12.dp))
                        .background(onBackgroundColor.copy(alpha = 0.06f)),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 1.dp else 2.dp),
            ) {
                Text(
                    text = mediaMetadata?.title ?: "",
                    style = titleStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onBackgroundColor,
                    modifier = Modifier.hushMarquee(),
                )
                Text(
                    text = mediaMetadata?.artists?.joinToString(", ") { it.name } ?: "",
                    style =
                        if (isLandscape) {
                            MaterialTheme.typography.bodySmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onBackgroundColor.copy(alpha = 0.6f),
                    modifier = Modifier.hushMarquee(),
                )
                if (isLandscape) {
                    Text(
                        text =
                            pluralStringResource(R.plurals.n_song, songCount, songCount) +
                                "  •  " + makeTimeString(queueDuration * 1000L),
                        style = MaterialTheme.typography.labelSmall,
                        color = onBackgroundColor.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HushIconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(likeButtonSize),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor =
                            if (liked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                onBackgroundColor
                            },
                    ),
            ) {
                Icon(
                    painter =
                        painterResource(
                            if (liked) {
                                R.drawable.favorite
                            } else {
                                R.drawable.favorite_border
                            },
                        ),
                    contentDescription = stringResource(R.string.action_like),
                    modifier = Modifier.size(likeIconSize),
                )
            }
        }

        Spacer(modifier = Modifier.height(sectionGap))

        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    HushIconButton(
                        onClick = onLockClick,
                        modifier = Modifier.size(actionIconButtonSize),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                contentColor = onBackgroundColor.copy(alpha = 0.7f),
                            ),
                    ) {
                        Icon(
                            painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                            contentDescription = null,
                            modifier = Modifier.size(actionIconSize),
                        )
                    }
                    HushIconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.size(actionIconButtonSize),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                contentColor = onBackgroundColor.copy(alpha = 0.7f),
                            ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                            modifier = Modifier.size(actionIconSize),
                        )
                    }
                    HushIconButton(
                        onClick = onClearQueueClick,
                        modifier = Modifier.size(actionIconButtonSize),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = stringResource(R.string.clear),
                            modifier = Modifier.size(actionIconSize),
                        )
                    }
                }

                QueuePlaybackToggleRow(
                    shuffleModeEnabled = shuffleModeEnabled,
                    repeatMode = repeatMode,
                    infiniteQueueEnabled = infiniteQueueEnabled,
                    infiniteQueueLoading = infiniteQueueLoading,
                    onBackgroundColor = onBackgroundColor,
                    enableHapticFeedback = enableHapticFeedback,
                    view = view,
                    onShuffleClick = onShuffleClick,
                    onRepeatClick = onRepeatClick,
                    onInfiniteQueueClick = onInfiniteQueueClick,
                    buttonSize = toggleButtonSize,
                    iconSize = if (isLandscape) 20.dp else 22.dp,
                    compact = true,
                )
            }
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(onBackgroundColor.copy(alpha = 0.06f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    HushIconButton(
                        onClick = onLockClick,
                        modifier = Modifier.size(actionIconButtonSize),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                contentColor = onBackgroundColor.copy(alpha = 0.7f),
                            ),
                    ) {
                        Icon(
                            painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                            contentDescription = null,
                            modifier = Modifier.size(actionIconSize),
                        )
                    }
                    HushIconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.size(actionIconButtonSize),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                contentColor = onBackgroundColor.copy(alpha = 0.7f),
                            ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                            modifier = Modifier.size(actionIconSize),
                        )
                    }
                    HushIconButton(
                        onClick = onClearQueueClick,
                        modifier = Modifier.size(actionIconButtonSize),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = stringResource(R.string.clear),
                            modifier = Modifier.size(actionIconSize),
                        )
                    }
                }

                Text(
                    text =
                        pluralStringResource(R.plurals.n_song, songCount, songCount) +
                            "  •  " + makeTimeString(queueDuration * 1000L),
                    style = MaterialTheme.typography.labelMedium,
                    color = onBackgroundColor.copy(alpha = 0.55f),
                    modifier = Modifier.padding(end = 14.dp),
                )
            }

            Spacer(modifier = Modifier.height(sectionGap))

            QueuePlaybackToggleRow(
                shuffleModeEnabled = shuffleModeEnabled,
                repeatMode = repeatMode,
                infiniteQueueEnabled = infiniteQueueEnabled,
                infiniteQueueLoading = infiniteQueueLoading,
                onBackgroundColor = onBackgroundColor,
                enableHapticFeedback = enableHapticFeedback,
                view = view,
                onShuffleClick = onShuffleClick,
                onRepeatClick = onRepeatClick,
                onInfiniteQueueClick = onInfiniteQueueClick,
                buttonSize = toggleButtonSize,
                iconSize = 22.dp,
                compact = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(sectionGap))

            Text(
                text = stringResource(R.string.queue_continue_playing),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onBackgroundColor,
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = stringResource(R.string.queue_autoplaying_similar),
                style = MaterialTheme.typography.bodySmall,
                color = onBackgroundColor.copy(alpha = 0.5f),
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        HorizontalDivider(
            color = onBackgroundColor.copy(alpha = 0.08f),
            thickness = 1.dp,
        )
    }
}

@Composable
private fun QueuePlaybackToggleRow(
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    infiniteQueueEnabled: Boolean,
    infiniteQueueLoading: Boolean,
    onBackgroundColor: Color,
    enableHapticFeedback: Boolean,
    view: android.view.View,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onInfiniteQueueClick: () -> Unit,
    buttonSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val uncheckedColors =
        ToggleButtonDefaults.toggleButtonColors(
            containerColor = onBackgroundColor.copy(alpha = 0.12f),
            contentColor = onBackgroundColor,
        )
    val checkedColors =
        ToggleButtonDefaults.toggleButtonColors(
            checkedContainerColor = onBackgroundColor.copy(alpha = 0.22f),
            checkedContentColor = onBackgroundColor,
        )
    val infiniteCheckedColors =
        ToggleButtonDefaults.toggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = onBackgroundColor.copy(alpha = 0.12f),
            contentColor = onBackgroundColor.copy(alpha = 0.5f),
        )

    Row(
        modifier = modifier,
        horizontalArrangement =
            if (compact) {
                Arrangement.spacedBy(6.dp)
            } else {
                Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffleModifier =
            if (compact) {
                Modifier.size(buttonSize)
            } else {
                Modifier.weight(1f).size(buttonSize)
            }
        val repeatModifier =
            if (compact) {
                Modifier.size(buttonSize)
            } else {
                Modifier.weight(1f).size(buttonSize)
            }
        val infiniteModifier =
            if (compact) {
                Modifier.size(buttonSize)
            } else {
                Modifier.weight(1f).size(buttonSize)
            }

        ToggleButton(
            checked = shuffleModeEnabled,
            onCheckedChange = {
                if (enableHapticFeedback) {
                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                    )
                }
                onShuffleClick()
            },
            modifier = shuffleModifier,
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            colors = if (shuffleModeEnabled) checkedColors else uncheckedColors,
        ) {
            Icon(
                painter = painterResource(R.drawable.shuffle),
                contentDescription = stringResource(R.string.action_shuffle_on),
                modifier = Modifier.size(iconSize),
            )
        }

        ToggleButton(
            checked = repeatMode != Player.REPEAT_MODE_OFF,
            onCheckedChange = {
                if (enableHapticFeedback) {
                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                    )
                }
                onRepeatClick()
            },
            modifier = repeatModifier,
            shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
            colors = if (repeatMode != Player.REPEAT_MODE_OFF) checkedColors else uncheckedColors,
        ) {
            Icon(
                painter =
                    painterResource(
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> R.drawable.repeat
                        },
                    ),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        }

        ToggleButton(
            checked = infiniteQueueEnabled,
            onCheckedChange = { onInfiniteQueueClick() },
            modifier = infiniteModifier,
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            colors = infiniteCheckedColors,
            enabled = !infiniteQueueLoading,
        ) {
            AnimatedContent(
                targetState = infiniteQueueLoading,
                label = "InfiniteQueueLoading",
            ) { loading ->
                if (loading) {
                    // Turns whether or not the system's animation scale is on; the standard
                    // indeterminate indicator does not, and this button sits over a queue that is
                    // being fetched, which is exactly when a listener presses it again.
                    HushProgressSpinner(
                        modifier = Modifier.size(iconSize),
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.all_inclusive),
                        contentDescription = stringResource(R.string.similar_content),
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

/**
 * Shared Sleep Timer Dialog component used in both Queue and Player.
 */
@Composable
fun SleepTimerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onEndOfSong: () -> Unit,
    initialValue: Float = 30f,
) {
    var sleepTimerValue by remember { mutableFloatStateOf(initialValue) }

    ActionPromptDialog(
        titleBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.sleep_timer),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
        onDismiss = onDismiss,
        onConfirm = {
            onConfirm(sleepTimerValue.roundToInt())
        },
        onCancel = onDismiss,
        onReset = {
            sleepTimerValue = 30f
        },
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.minute,
                            sleepTimerValue.roundToInt(),
                            sleepTimerValue.roundToInt(),
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(Modifier.height(16.dp))

                Slider(
                    value = sleepTimerValue,
                    onValueChange = { sleepTimerValue = it },
                    valueRange = 5f..120f,
                    steps = (120 - 5) / 5 - 1,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                OutlinedButton(onClick = onEndOfSong, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.end_of_song))
                }
            }
        },
    )
}

/**
 * Whether the player draws its provenance line at all.
 *
 * The row says what the audio is and where it is coming from - and, while a track is being fetched,
 * that it *is* being fetched, naming the source and the percentage. It used to be drawn only when
 * Media3 already had a format row for the item, which means only once the audio existed: a track
 * started by "next" or by a queue tap has no format yet, so the one line that could have named the
 * source being tried - and the seconds being spent on it - was absent for exactly the window it
 * exists for. Measured on the device: a track started from the queue sat at 0:00 behind a bare
 * spinner with no row at all, while the same fetch reached through "Refetch song" printed
 * "Downloading 25% • gglos" - the refetch differed only in that the track already had a stored
 * format row from its earlier play.
 *
 * Live progress counts on its own. [LocalPlayerConnection.activeDownloadProgress] only ever carries
 * the item that is playing (a lookahead's transfers go to its own signal), so a non-null value is a
 * statement about *this* track, and the row's own text already prefers the fetch or download state
 * over the stored facts while one is in flight.
 *
 * A pure function rather than the condition written into the composable, so the rule that a fetch
 * with no format row is still drawn is something a test can state; see [CodecRowVisibilityTest].
 */
fun codecRowIsVisible(
    showCodecOnPlayer: Boolean,
    hasFormatRow: Boolean,
    hasProgress: Boolean,
    /**
     * A sweep that ended with nothing, whose verdict is now this row's to print.
     *
     * Without it the row had nothing to say and was not drawn - which is exactly the state a failed
     * fetch leaves: no format row (nothing was served) and no progress (the sweep is over), so the
     * failure had no surface left anywhere once the album-art panel stopped showing it.
     */
    hasFailure: Boolean = false,
): Boolean = showCodecOnPlayer && (hasFormatRow || hasProgress || hasFailure)

/** [codecRowIsVisible] with the progress read from the connection the player is bound to. */
@Composable
fun rememberShowCodecRow(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
): Boolean {
    // Remembered unconditionally, like the flows inside CodecInfoRow: LocalPlayerConnection starts
    // null and becomes bound once the service is up, and a `remember` reached only through `?:` is
    // not wrapped in a group by the Compose compiler - which is a startup crash on the surface that
    // can compose before the service binds.
    val fallbackFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<PlaybackDownloadProgress?>(null) }
    val connection = LocalPlayerConnection.current
    val progress by (
        connection?.activeDownloadProgress ?: fallbackFlow
    ).collectAsStateWithLifecycle(initialValue = null)
    val fallbackFailureFlow = remember {
        kotlinx.coroutines.flow.MutableStateFlow<app.hush.music.playback.FetchFailure?>(null)
    }
    val failure by (
        connection?.activeFetchFailure ?: fallbackFailureFlow
    ).collectAsStateWithLifecycle(initialValue = null)
    return codecRowIsVisible(
        showCodecOnPlayer = showCodecOnPlayer,
        hasFormatRow = formatRow != null,
        hasProgress = progress != null,
        hasFailure = failure != null,
    )
}

/**
 * The facts under the playback controls: bitrate, size, and where the audio came from.
 *
 * The codec name and its provenance phrase are deliberately absent. Both were on this line for a
 * long time and both were asked to be removed repeatedly: the line is the densest thing on the
 * player, the container is already named by the file the app is playing, and "codec from this
 * session's resolve" was a sentence about the *row* rather than about the track - on the one line a
 * listener reads to see what is playing. What is left is the part a listener cannot get from
 * anywhere else on the screen: the bitrate, the size, and the source it is coming from.
 *
 * The parameters are facts, not a formatted string, because the separator and the ordering are the
 * same on every surface that draws this row and two of them assembling their own would drift.
 */
@Composable
fun CodecInfoRow(
    bitrate: String,
    fileSize: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    playbackClient: String? = null,
    /** Horizontal inset of the label. Portrait draws the full-width strip with 30dp; the compact
     * landscape pill trims it so the label can sit beside the chips. */
    horizontalPadding: Dp = 30.dp,
    topPadding: Dp = 6.dp,
    bottomPadding: Dp = 2.dp,
) {
    val fallbackFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val activeClientLabel by (
        LocalPlayerConnection.current?.activePlaybackClientLabel ?: fallbackFlow
    ).collectAsStateWithLifecycle(initialValue = null)
    // No fallback that names an engine. There used to be a `?: if (isPlaying) "YouTube"` here,
    // which meant that for the whole window while SpotiFLAC resolved a track - a provider
    // sweep can take tens of seconds - the row asserted YouTube even though nothing of the
    // kind was happening. Together with a stored format row left over from an earlier
    // YouTube play, that is how a track could read as a 5 MB WebM live stream while a 30 MB
    // FLAC was being fetched for it. Saying nothing is correct until a source is published.
    val resolvedPlaybackClient = playbackClient ?: activeClientLabel
    // A source sweep publishes download progress naming the provider being tried, but no source
    // label (that only appears once a resolve succeeds) - so this row, the one that names the
    // source, rendered blank for the entire window. Measured on device: an uncached track sat at
    // 0:00 for 18 seconds while `deezer` refused and `qobuz-web` stalled, with this line empty
    // throughout, which is what makes a slow source read as a track that never started.
    // Remembered unconditionally, like the flow above: `LocalPlayerConnection` is a
    // `staticCompositionLocalOf` whose value starts null and becomes the bound connection once
    // the service is up, so this whole subtree recomposes with the other branch taken. A
    // `remember` reached only through `?:` is not wrapped in a group by the Compose compiler, so
    // that recomposition would hand this slot to `collectAsStateWithLifecycle`'s own state - on
    // the surface that can be composed before the service binds, which is a startup crash.
    val fallbackProgressFlow =
        remember { kotlinx.coroutines.flow.MutableStateFlow<PlaybackDownloadProgress?>(null) }
    val downloadProgress by (
        LocalPlayerConnection.current?.activeDownloadProgress ?: fallbackProgressFlow
    ).collectAsStateWithLifecycle(initialValue = null)
    // (The row is drawn for a failed sweep even with nothing else to say - see
    // [codecRowIsVisible].)
    // Whether the track is already being heard. A SpotiFLAC file is complete before Media3 is
    // handed it, so audio that is playing cannot be audio that is still downloading - which means a
    // sample left published past the end of its transfer (measured on device: "Downloading 11%" and
    // "Downloading 14%" over tracks that had finished and started playing) is a sample this row must
    // not believe. See [codecRowState].
    //
    // [PlayerConnection.isAudiblyPlaying], not `isPlaying`: the latter is true while the player is
    // only *waiting* to play, which is the whole window a SpotiFLAC track is resolving and
    // downloading - so this guard was hiding every live transfer. The device log that settled it:
    // `progress routed player=true ... pct=99` beside `row draw state=STORED playing=true`.
    val fallbackPlayingFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val isAudiblyPlaying by (
        LocalPlayerConnection.current?.isAudiblyPlaying ?: fallbackPlayingFlow
    ).collectAsStateWithLifecycle(initialValue = false)
    // Which of the three things this row has to say; see [codecRowState] for the precedence.
    val rowState = downloadProgress.codecRowState(isAudiblyPlaying = isAudiblyPlaying)
    val fetching = if (rowState == CodecRowState.RESOLVING) downloadProgress?.fetchingLabel() else null
    // A sweep that ended with nothing: the sentence the player used to draw in a panel over the
    // artwork. It reads here instead, beside the source names it asked, and only for the track it
    // is about - a verdict for one song must never be printed under another.
    val fallbackFailureFlow = remember {
        kotlinx.coroutines.flow.MutableStateFlow<app.hush.music.playback.FetchFailure?>(null)
    }
    val fetchFailure by (
        LocalPlayerConnection.current?.activeFetchFailure ?: fallbackFailureFlow
    ).collectAsStateWithLifecycle(initialValue = null)
    val fallbackMediaFlow = remember {
        kotlinx.coroutines.flow.MutableStateFlow<app.hush.music.models.MediaMetadata?>(null)
    }
    val currentMetadata by (
        LocalPlayerConnection.current?.mediaMetadata ?: fallbackMediaFlow
    ).collectAsStateWithLifecycle(initialValue = null)
    val failureLine =
        fetchFailure
            ?.takeIf { it.mediaId == currentMetadata?.id }
            ?.let { failure ->
                val names = app.hush.music.playback.FetchFailure.sourceNames(failure.sources)
                if (names.isEmpty()) {
                    // No name to put in the sentence is still not a catalogue answer when the
                    // sources refused - see [app.hush.music.playback.FetchFailure.refused].
                    if (failure.refused) {
                        stringResource(R.string.spotiflac_fetch_refused)
                    } else {
                        stringResource(R.string.spotiflac_fetch_failed)
                    }
                } else {
                    val rest = app.hush.music.playback.FetchFailure.remainingCount(failure.sources)
                    val list =
                        if (rest > 0) {
                            stringResource(R.string.spotiflac_fetch_failed_more, names.joinToString(", "), rest)
                        } else {
                            names.joinToString(", ")
                        }
                    // A refusal is not a catalogue answer: the sources in this list never looked at
                    // the track, so the line says what the service did instead of what it did not
                    // find. See [app.hush.music.playback.FetchFailure.refused].
                    if (failure.refused) {
                        stringResource(R.string.spotiflac_fetch_refused_sources, list)
                    } else {
                        stringResource(R.string.spotiflac_fetch_failed_sources, list)
                    }
                }
            }
    // A fetch that has already started reports bytes, and this row used to fall back to the
    // track's *stored* format line for that whole window - so a refetch spent its second half
    // showing a codec name and a size from the copy that was being replaced, while the transfer
    // that was replacing it named nothing at all. Same sentence as the fetch state, with the
    // number the download actually has: how far along, and which source is serving it.
    val downloadingProgress = downloadProgress?.takeIf { rowState == CodecRowState.DOWNLOADING }
    val downloading = downloadingProgress?.downloadLine()
    // Live progress wins over a verdict: a sweep that is running again after a failure has something
    // newer to say than the failure did, and its own line is the honest one.
    val shownFailure = failureLine?.takeIf { fetching == null && downloading == null }
    val rowColor = if (shownFailure != null) MaterialTheme.colorScheme.error else textColor

    // Resolved before the string is built, because the labelling helpers are composable and
    // cannot be called from inside a buildString lambda. The delivery is appended so this row
    // states whether the audio is coming off the device or the network, like the source row.
    val sourceInfo = remember(resolvedPlaybackClient) { PlaybackSourceLabels.parse(resolvedPlaybackClient) }
    val sourceText =
        resolvedPlaybackClient
            ?.takeIf { it.isNotBlank() && sourceInfo.engine != PlaybackEngine.UNKNOWN }
            ?.let { sourceInfo.displayName() }
    val deliveryText = sourceInfo.delivery.displayName()
    // The fallback for a line whose every fact is missing. Resolved here because it is a
    // composable call and the text below is built in a plain lambda.
    val emptyAudioFallback = stringResource(R.string.player_audio_stereo)

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = topPadding,
                    bottom = bottomPadding,
                ),
    ) {
        Text(
            text =
                // While a source is still being fetched there is no codec/bitrate/size to
                // report for the new track, so the fetching state replaces the line rather
                // than leaving it blank - and once bytes are moving, the download's own
                // progress does the same, because the stored row describes the old copy.
                // Only the row for a track that is *not* being fetched falls through to the
                // stored facts, which is the one case where they are still the truth.
                fetching
                    ?: downloading
                    ?: shownFailure
                    ?: buildString {
                        if (bitrate != "Unknown") {
                            append(bitrate)
                        }
                        if (fileSize.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append(fileSize)
                        }
                        if (sourceText != null) {
                            if (isNotEmpty()) append(" • ")
                            append(sourceText)
                            if (deliveryText != null) {
                                append(" (").append(deliveryText.lowercase()).append(")")
                            }
                        }
                        // Every part may be absent - a stream with no bitrate and no known
                        // source has nothing to say - and an empty line is worse than none.
                        if (isEmpty()) append(emptyAudioFallback)
                    },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = rowColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The row is a single centred line of codec/bitrate/size/source facts that
            // routinely outgrows the width, and every part of it is worth reading - so it
            // ticks rather than trailing off into an ellipsis.
            modifier = Modifier.hushMarquee(),
        )
    }
}

/**
 * Shows the active playback source.
 */
@Composable
fun PlaybackSourceRow(
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val (primaryScraper) =
        rememberEnumPreference(
            PrimaryAudioScraperKey,
            defaultValue = PrimaryAudioScraper.YOUTUBE,
        )
    val isYouTube = primaryScraper == PrimaryAudioScraper.YOUTUBE

    val clientLabel by playerConnection.activePlaybackClientLabel.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val downloadProgress by playerConnection.activeDownloadProgress.collectAsStateWithLifecycle()    // One structured reading of the source, shared with the full player, so the mini bar and
    // the player cannot describe the same track differently.
    val sourceInfo = remember(clientLabel) { PlaybackSourceLabels.parse(clientLabel) }
    val sourceLabel =
        when {
            sourceInfo.engine != PlaybackEngine.UNKNOWN -> sourceInfo.displayName()
            isPlaying && isYouTube -> stringResource(R.string.playback_source_youtube)
            else -> null
        }
    // What the audio is coming from right now. Kept as its own line so it reads as a
    // different statement from the media3 "Saved offline" badge.
    val deliveryLabel =
        downloadProgress
            ?.let { progress ->
                when {
                    // One word for both halves of the same copy: a cache replay and a fetch that
                    // just finished are both "the app fetched this for playback". See
                    // `PlaybackDelivery.displayName`.
                    progress.fromCache -> stringResource(R.string.playback_delivery_fetched)
                    progress.percent >= 100 -> stringResource(R.string.playback_delivery_fetched)
                    else -> null
                }
            }
            ?: sourceInfo.delivery.displayName()

    // A SpotiFLAC fetch has to be visible before the source label exists: the label is only
    // published once the resolve succeeds.
    if (sourceLabel == null && deliveryLabel == null && downloadProgress == null) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 2.dp),
    ) {
        if (sourceLabel != null) {
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1,
                modifier =
                    Modifier
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .hushMarquee(),
            )
        }
        if (deliveryLabel != null) {
            Text(
                text = deliveryLabel,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 1,
                modifier =
                    Modifier
                        .padding(horizontal = 10.dp)
                        .hushMarquee(),
            )
        }
        // No progress line here: this row names *where* the audio came from, and the one line that
        // reports a transfer in flight is the codec line under the controls ([downloadLine]). Two
        // surfaces printing the same percentage is how the player ended up saying "Downloading 42%"
        // twice, in two different wordings, a few dp apart.
    }
}

/**
 * True while a SpotiFLAC source sweep is still running for this track.
 *
 * The runtime reports a sweep as progress with no bytes behind it at all - `checking_session`
 * -> `resolving_metadata` -> `resolving_stream` - and emits no fraction, because there is
 * nothing to measure yet. Reading `percent == 0` as "a download in progress" is what left the
 * player looking frozen for that whole window: measured on device, pressing play on an
 * uncached track held the player at 0:00 for 18 seconds while providers were tried and
 * abandoned in turn (`deezer` refused in 3s, then `qobuz-web` sat on `resolving_stream`),
 * which is indistinguishable from a track that never started. The source being tried is
 * already in this payload, so the honest thing to show is which one that is.
 */
val PlaybackDownloadProgress.isResolvingSource: Boolean
    get() = !fromCache && bytesTotal <= 0L && percent <= 0

/**
 * What the codec row has to say about the track on screen, if anything.
 *
 * The row is built from the track's *stored* format facts (bitrate, size, source, delivery), and
 * every one of those facts describes the copy the engine currently hands to the player. While a
 * SpotiFLAC track is being resolved or downloaded there is no such copy for it yet: the stored row
 * belongs to the previous play of that track, or to another track entirely, so drawing it over a
 * transfer is how a 33 MB "Deezer (device cache)" line appeared above a track that was audibly still
 * downloading. The transfer's own state replaces the line instead.
 *
 * The precedence is the point, and it is the reason this is a function rather than a chain of
 * `takeIf`s at the call site:
 *
 * * [RESOLVING] first - a sweep publishes no bytes and no fraction, so it is also `percent < 100`,
 *   and reading it as a download would draw "Downloading 0%" for the tens of seconds a provider
 *   sweep can take.
 * * [DOWNLOADING] for anything under 100 that is not a cache hit, *including the very first
 *   sample*: the total size is published before the first byte is read, and that opening moment is
 *   precisely when the stored line is still the old copy. A transfer that already reported itself
 *   complete is not downloading any more - a progress entry can outlive the last byte.
 * * [STORED] otherwise: nothing in flight, a replay off the device cache, or a finished transfer.
 */
internal enum class CodecRowState { RESOLVING, DOWNLOADING, STORED }

internal fun PlaybackDownloadProgress?.codecRowState(isAudiblyPlaying: Boolean = false): CodecRowState =
    when {
        this == null -> CodecRowState.STORED
        // The audio is playing, so the fetch that produced it is over - whatever the last sample
        // said. A SpotiFLAC file is complete before it is played, so a "Downloading 14%" left
        // behind by a transfer that finished (or by one whose terminal sample lost a race with the
        // meter) describes nothing on screen. This is the row's half of the same rule the
        // transport's ring follows, where a playing track has no fraction at all.
        //
        // Audible, not merely `playWhenReady`: that flag is already set while the track is still
        // being fetched, so reading it here treated every live transfer as a finished one.
        isAudiblyPlaying -> CodecRowState.STORED
        fromCache -> CodecRowState.STORED
        isResolvingSource -> CodecRowState.RESOLVING
        percent < 100 -> CodecRowState.DOWNLOADING
        else -> CodecRowState.STORED
    }


/**
 * "Fetching from Deezer…" while a source is being tried, or null once real bytes are
 * moving - at which point [R.string.spotiflac_downloading_percent] is the true statement.
 *
 * The name is the one the publisher resolved ([PlaybackDownloadProgress.sourceDisplayName]): a
 * listener reading this row is asking which source is serving the track, and the registry id it used
 * to print (`bitchord-streaming-modules:bitchord-tidal-flac`) answered that question in a vocabulary
 * nothing else on screen uses.
 */
@Composable
fun PlaybackDownloadProgress.fetchingLabel(): String? =
    if (!isResolvingSource) {
        null
    } else {
        sourceDisplayName
            ?.takeIf { it.isNotBlank() }
            ?.let { name ->
                // How far into the walk this source is, when the publisher knew: "Fetching from
                // Tidal FLAC… (2 of 3)" tells a listener the app is working through their list,
                // where the name alone is the same sentence for a one-source sweep and a
                // five-source one.
                if (attemptIndex > 0 && attemptTotal >= attemptIndex) {
                    stringResource(R.string.spotiflac_fetch_trying_source, name, attemptIndex, attemptTotal)
                } else {
                    stringResource(R.string.spotiflac_fetching_from_source, name)
                }
            }
            ?: stringResource(R.string.spotiflac_fetching_audio)
    }

/**
 * The one line the player says about a transfer in flight: how far along, how fast, how much of
 * what, and from where.
 *
 * The percentage is only half of it. "Downloading 42%" answers neither question a listener waiting
 * asks - is it moving, and how much is left - so the speed, the bytes received against the total and
 * the source follow it, composed in one place ([PlaybackDownloadProgress.downloadDetailParts]) so the
 * codec line, the landscape caption and the refetch path cannot word the same transfer differently.
 * A source sweep has no numbers yet, so it names the source being tried instead of printing 0%.
 */
@Composable
fun PlaybackDownloadProgress.downloadLine(): String {
    fetchingLabel()?.let { return it }
    val percent = stringResource(R.string.spotiflac_downloading_percent, percent)
    return (listOf(percent) + downloadDetailParts()).joinToString(" • ")
}

/**
 * What one queue row says about the track the lookahead is warming ahead of the listener.
 *
 * The lookahead's traffic had no surface at all before this: the runtime reports one item's progress
 * at a time, and the player's own signal is claimed by the track that is playing, so a queue being
 * fetched ahead of the user looked exactly like a queue doing nothing - while the only visible sign
 * of the work, when it was visible at all, was the fetch label on the song already playing.
 *
 * Drawn on the *row* rather than in the player for the same reason: the answer to "what is being
 * fetched?" is a track in the list, and a status that names one track while the user listens to
 * another belongs on that track. Rows the lookahead is not touching draw nothing, and the playing
 * track's row is refused by [queuePrefetchState] whatever the lookahead is doing.
 *
 * Every state takes the same footprint, so a row's width does not change as the lookahead advances
 * from queued to fetching to downloading; the app already avoids that in the settings rows, where a
 * control that vanished as the list was reordered made the row jump.
 */
@Composable
fun QueuePrefetchBadge(
    mediaId: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val prefetch by playerConnection.lookaheadPrefetch.collectAsStateWithLifecycle()
    val currentMediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val state =
        queuePrefetchState(
            mediaId = mediaId,
            currentMediaId = currentMediaMetadata?.id,
            prefetch = prefetch,
        ) ?: return

    // Spoken, not drawn: the ring and the spinner carry no words, and a queue that quietly fills
    // itself ahead of the listener is exactly the state a screen reader has to be able to report.
    val description =
        when (state) {
            QueuePrefetchState.Queued -> stringResource(R.string.queue_prefetch_queued)
            QueuePrefetchState.Fetching -> stringResource(R.string.spotiflac_fetching_audio)
            is QueuePrefetchState.Downloading ->
                stringResource(R.string.spotiflac_downloading_percent, state.percent)
        }

    Box(
        modifier =
            modifier
                .size(QueuePrefetchBadgeSize)
                .padding(end = 2.dp)
                .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            QueuePrefetchState.Queued ->
                CircularProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.size(QueuePrefetchBadgeSize),
                    strokeWidth = 2.dp,
                    color = color.copy(alpha = 0.35f),
                    trackColor = color.copy(alpha = 0.35f),
                )

            QueuePrefetchState.Fetching ->
                // Turns whether or not the system's animation scale is on: a provider stage can sit
                // there for seconds, and an indicator that never moved would read as a stuck row.
                HushProgressSpinner(
                    modifier = Modifier.size(QueuePrefetchBadgeSize - 4.dp),
                    color = color,
                    strokeWidth = 2.dp,
                    trackColor = color.copy(alpha = 0.25f),
                )

            is QueuePrefetchState.Downloading ->
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.size(QueuePrefetchBadgeSize),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = color.copy(alpha = 0.25f),
                    )
                    Text(
                        text = "${state.percent}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
        }
    }
}

/** The ring the queue's prefetch badge draws, matching the library's own download indicator. */
private val QueuePrefetchBadgeSize = 20.dp

/**
 * V2 Design Style collapsed queue content.
 */
@Composable
fun QueueCollapsedContentV2(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
    textBackgroundColor: Color,
    textButtonColor: Color,
    iconButtonColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    repeatMode: Int,
    mediaMetadata: MediaMetadata?,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    onRepeatModeClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

    LaunchedEffect(enableHapticFeedback) {
        view.isHapticFeedbackEnabled = enableHapticFeedback
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PlaybackSourceRow(textColor = textBackgroundColor)
        if (rememberShowCodecRow(showCodecOnPlayer, formatRow)) {
            val currentFormat = formatRow?.format
            val bitrate = currentFormat?.formattedBitrate() ?: ""

            val extraText =
                listOfNotNull(
                    currentFormat?.codecRowDetail()?.takeIf { it.isNotBlank() },
                ).joinToString(separator = " • ")

            CodecInfoRow(
                bitrate = bitrate,
                fileSize = extraText,
                textColor = textBackgroundColor.copy(alpha = 0.7f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp, vertical = 10.dp)
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    ),
        ) {
            val buttonSize = 42.dp
            val iconSize = 24.dp
            val borderColor = textBackgroundColor.copy(alpha = 0.35f)

            // Queue button
            Box(
                modifier =
                    Modifier
                        .size(buttonSize)
                        .clip(
                            RoundedCornerShape(
                                topStart = 50.dp,
                                bottomStart = 50.dp,
                                topEnd = 10.dp,
                                bottomEnd = 10.dp,
                            ),
                        ).border(
                            1.dp,
                            borderColor,
                            RoundedCornerShape(
                                topStart = 50.dp,
                                bottomStart = 50.dp,
                                topEnd = 10.dp,
                                bottomEnd = 10.dp,
                            ),
                        ).clickable { onExpandQueue() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.queue_music),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = textBackgroundColor,
                )
            }

            // Sleep timer button
            Box(
                modifier =
                    Modifier
                        .size(buttonSize)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                        .clickable { onSleepTimerClick() },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    label = "sleepTimer",
                    targetState = sleepTimerEnabled,
                ) { enabled ->
                    if (enabled) {
                        Text(
                            text = makeTimeString(sleepTimerTimeLeft),
                            color = textBackgroundColor,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .hushMarquee(),
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.bedtime),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = textBackgroundColor,
                        )
                    }
                }
            }

            // Lyrics button
            Box(
                modifier =
                    Modifier
                        .size(buttonSize)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                        .clickable { onShowLyrics() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.lyrics),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = textBackgroundColor,
                )
            }

            // Repeat mode button
            Box(
                modifier =
                    Modifier
                        .size(buttonSize)
                        .clip(
                            RoundedCornerShape(
                                topStart = 10.dp,
                                bottomStart = 10.dp,
                                topEnd = 50.dp,
                                bottomEnd = 50.dp,
                            ),
                        ).border(
                            1.dp,
                            borderColor,
                            RoundedCornerShape(
                                topStart = 10.dp,
                                bottomStart = 10.dp,
                                topEnd = 50.dp,
                                bottomEnd = 50.dp,
                            ),
                        ).clickable {
                            if (enableHapticFeedback) {
                                view.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                                )
                            }
                            onRepeatModeClick()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter =
                        painterResource(
                            id =
                                when (repeatMode) {
                                    Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL -> R.drawable.repeat
                                    Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                    else -> R.drawable.repeat
                                },
                        ),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(iconSize)
                            .alpha(if (repeatMode == Player.REPEAT_MODE_OFF) 0.5f else 1f),
                    tint = textBackgroundColor,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Menu button
            Box(
                modifier =
                    Modifier
                        .size(buttonSize)
                        // Press motion before the clip: a clip wraps everything after it, so a halo
                        // drawn inside one is cut off at the button's own edge.
                        .hushBouncyClickable(onClick = onMenuClick, haloColor = textBackgroundColor)
                        .clip(CircleShape)
                        .background(textBackgroundColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.more_vert),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = textBackgroundColor,
                )
            }
        }
    }
}

/**
 * V3 Design Style collapsed queue content.
 */
@Composable
fun QueueCollapsedContentV3(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
    textBackgroundColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

    Column(modifier = modifier.fillMaxWidth()) {
        PlaybackSourceRow(textColor = textBackgroundColor)
        if (rememberShowCodecRow(showCodecOnPlayer, formatRow)) {
            val currentFormat = formatRow?.format
            val bitrate = currentFormat?.formattedBitrate() ?: ""

            CodecInfoRow(
                bitrate = bitrate,
                fileSize = "",
                textColor = textBackgroundColor.copy(alpha = 0.5f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 10.dp)
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    ),
        ) {
            // Queue button
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onExpandQueue() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.queue_music),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = textBackgroundColor.copy(alpha = 0.7f),
                    )
                    Text(
                        text = stringResource(id = R.string.queue),
                        color = textBackgroundColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        // One line, and an ellipsis when the chip cannot give it a full one. These
                        // labels sit in a weighted chip beside an icon, so at a large font scale in
                        // a narrow window there is less room than the word needs - and a one-line
                        // label with the default clip loses characters with nothing to show it:
                        // measured on device, "Lyrics" came out as "Lyri" at a 600x360dp window and
                        // font scale 1.5, and the sweep's text-fit check is what caught it.
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Sleep timer button
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSleepTimerClick() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    label = "sleepTimer",
                    targetState = sleepTimerEnabled,
                ) { enabled ->
                    if (enabled) {
                        Text(
                            text = makeTimeString(sleepTimerTimeLeft),
                            color = textBackgroundColor.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            // The clock in the row beside the two labelled chips, and it starves
                            // the same way they did: the leftovers this row hands its last child
                            // are less than the word needs once the font scale is up. A clock that
                            // loses digits is the failure the text-fit check exists for, so it says
                            // it was shortened instead.
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.bedtime),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = textBackgroundColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // Lyrics button
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onShowLyrics() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.lyrics),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = textBackgroundColor.copy(alpha = 0.7f),
                    )
                    Text(
                        text = stringResource(id = R.string.lyrics),
                        color = textBackgroundColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Menu button
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onMenuClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.more_vert),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = textBackgroundColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * V1 Design Style collapsed queue content (text buttons).
 */
@Composable
fun QueueCollapsedContentV1(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
    textBackgroundColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PlaybackSourceRow(textColor = textBackgroundColor)
        if (rememberShowCodecRow(showCodecOnPlayer, formatRow)) {
            val currentFormat = formatRow?.format
            val bitrate = currentFormat?.formattedBitrate() ?: ""
            val fileSize = currentFormat?.codecRowDetail() ?: ""

            CodecInfoRow(
                bitrate = bitrate,
                fileSize = fileSize,
                textColor = textBackgroundColor.copy(alpha = 0.7f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp, vertical = 12.dp)
                    .windowInsetsPadding(
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
        ) {
            TextButton(
                onClick = onExpandQueue,
                modifier = Modifier.weight(1f),
                shapes = ButtonDefaults.shapes(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.queue_music),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = textBackgroundColor,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(id = R.string.queue),
                        color = textBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.hushMarquee(),
                    )
                }
            }

            TextButton(
                onClick = onSleepTimerClick,
                modifier = Modifier.weight(1.2f),
                shapes = ButtonDefaults.shapes(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.bedtime),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = textBackgroundColor,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    AnimatedContent(
                        label = "sleepTimer",
                        targetState = sleepTimerEnabled,
                    ) { enabled ->
                        if (enabled) {
                            Text(
                                text = makeTimeString(sleepTimerTimeLeft),
                                color = textBackgroundColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.hushMarquee(),
                            )
                        } else {
                            Text(
                                text = stringResource(id = R.string.sleep_timer),
                                color = textBackgroundColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.hushMarquee(),
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = onShowLyrics,
                modifier = Modifier.weight(1f),
                shapes = ButtonDefaults.shapes(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.lyrics),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = textBackgroundColor,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(id = R.string.lyrics),
                        color = textBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.hushMarquee(),
                    )
                }
            }
        }
    }
}

/**
 * V6 Expressive collapsed queue — slim codec row + Queue / Sleep / Lyrics bar.
 */
@Composable
fun V6QueueCollapsedContent(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
    textBackgroundColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    applyBottomInsets: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (rememberShowCodecRow(showCodecOnPlayer, formatRow)) {
            val currentFormat = formatRow?.format
            val bitrate = currentFormat?.formattedBitrate() ?: ""
            val fileSize = currentFormat?.codecRowDetail() ?: ""

            CodecInfoRow(
                bitrate = bitrate,
                fileSize = fileSize,
                textColor = textBackgroundColor.copy(alpha = 0.6f),
                modifier =
                    Modifier.padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 2.dp,
                        bottom = 0.dp,
                    ),
            )
        }

        V6QueueBottomBar(
            textBackgroundColor = textBackgroundColor,
            sleepTimerEnabled = sleepTimerEnabled,
            sleepTimerTimeLeft = sleepTimerTimeLeft,
            onExpandQueue = onExpandQueue,
            onSleepTimerClick = onSleepTimerClick,
            onShowLyrics = onShowLyrics,
            applyBottomInsets = applyBottomInsets,
        )
    }
}

@Composable
fun V6QueueBottomBar(
    textBackgroundColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    applyBottomInsets: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .then(
                    if (applyBottomInsets) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                            ),
                        )
                    } else {
                        Modifier
                    },
                ),
    ) {
        val buttonSize = 48.dp
        val iconSize = 22.dp

        Box(
            modifier =
                Modifier
                    .height(buttonSize)
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(textBackgroundColor.copy(alpha = 0.1f))
                    .hushPressable(onClick = onExpandQueue, pressScale = HushDesign.RowPressScale),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.queue_music),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = textBackgroundColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.queue),
                    color = textBackgroundColor,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier =
                Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .background(
                        if (sleepTimerEnabled) {
                            textBackgroundColor.copy(alpha = 0.2f)
                        } else {
                            textBackgroundColor.copy(alpha = 0.1f)
                        },
                    ).clickable(onClick = onSleepTimerClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                label = "v6SleepTimer",
                targetState = sleepTimerEnabled,
            ) { enabled ->
                if (enabled) {
                    Text(
                        text = makeTimeString(sleepTimerTimeLeft),
                        color = textBackgroundColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .hushMarquee(),
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.bedtime),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = textBackgroundColor,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier =
                Modifier
                    .height(buttonSize)
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(textBackgroundColor.copy(alpha = 0.1f))
                    .clickable(onClick = onShowLyrics),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.lyrics),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = textBackgroundColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.lyrics),
                    color = textBackgroundColor,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * V4 Design Style collapsed queue content (pill buttons).
 */
@Composable
fun QueueCollapsedContentV4(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
    textBackgroundColor: Color,
    iconButtonColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    mediaMetadata: MediaMetadata?,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PlaybackSourceRow(textColor = textBackgroundColor)
        if (rememberShowCodecRow(showCodecOnPlayer, formatRow)) {
            val currentFormat = formatRow?.format
            val bitrate = currentFormat?.formattedBitrate() ?: ""
            val fileSize = currentFormat?.codecRowDetail() ?: ""

            CodecInfoRow(
                bitrate = bitrate,
                fileSize = fileSize,
                textColor = textBackgroundColor.copy(alpha = 0.6f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    ),
        ) {
            val buttonSize = 48.dp
            val iconSize = 22.dp

            // Queue button (pill)
            Box(
                modifier =
                    Modifier
                        .height(buttonSize)
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(textBackgroundColor.copy(alpha = 0.1f))
                        .clickable { onExpandQueue() },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.queue_music),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = textBackgroundColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.queue),
                        color = textBackgroundColor,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Sleep timer button (circle)
            Box(
                modifier =
                    Modifier
                        .size(buttonSize)
                        .clip(CircleShape)
                        .background(
                            if (sleepTimerEnabled) {
                                textBackgroundColor.copy(alpha = 0.2f)
                            } else {
                                textBackgroundColor.copy(alpha = 0.1f)
                            },
                        ).clickable { onSleepTimerClick() },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    label = "sleepTimer",
                    targetState = sleepTimerEnabled,
                ) { enabled ->
                    if (enabled) {
                        Text(
                            text = makeTimeString(sleepTimerTimeLeft),
                            color = textBackgroundColor,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .hushMarquee(),
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.bedtime),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = textBackgroundColor,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Lyrics button (pill)
            Box(
                modifier =
                    Modifier
                        .height(buttonSize)
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(textBackgroundColor.copy(alpha = 0.1f))
                        .clickable { onShowLyrics() },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.lyrics),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = textBackgroundColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.lyrics),
                        color = textBackgroundColor,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun QueueCollapsedContentV7(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
    textBackgroundColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onShowLyrics: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onDeviceClick: () -> Unit,
    device: ActiveOutputDevice,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PlaybackSourceRow(textColor = textBackgroundColor)
        if (rememberShowCodecRow(showCodecOnPlayer, formatRow)) {
            val currentFormat = formatRow?.format
            val bitrate = currentFormat?.autoRateDisplay() ?: ""
            val fileSize = currentFormat?.codecRowDetail() ?: ""

            CodecInfoRow(
                bitrate = bitrate,
                fileSize = fileSize,
                textColor = textBackgroundColor.copy(alpha = 0.6f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    ),
        ) {
            val iconSize = 22.dp

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onExpandQueue,
                    shape = CircleShape,
                    color = textBackgroundColor.copy(alpha = 0.08f),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.queue_music),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = textBackgroundColor,
                        )
                    }
                }

                Surface(
                    onClick = onShowLyrics,
                    shape = CircleShape,
                    color = textBackgroundColor.copy(alpha = 0.08f),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.lyrics),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = textBackgroundColor,
                        )
                    }
                }

                Surface(
                    onClick = onSleepTimerClick,
                    shape = if (sleepTimerEnabled) RoundedCornerShape(20.dp) else CircleShape,
                    color = textBackgroundColor.copy(alpha = if (sleepTimerEnabled) 0.16f else 0.08f),
                    modifier =
                        if (sleepTimerEnabled) {
                            Modifier.height(42.dp)
                        } else {
                            Modifier.size(42.dp)
                        },
                ) {
                    AnimatedContent(
                        label = "v7SleepTimer",
                        targetState = sleepTimerEnabled,
                    ) { enabled ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier =
                                Modifier.padding(
                                    start = 10.dp,
                                    end = if (enabled) 12.dp else 10.dp,
                                ),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.bedtime),
                                contentDescription = stringResource(id = R.string.sleep_timer),
                                modifier = Modifier.size(iconSize),
                                tint = textBackgroundColor,
                            )
                            if (enabled) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = makeTimeString(sleepTimerTimeLeft.coerceAtLeast(0L)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = textBackgroundColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                onClick = onDeviceClick,
                shape = RoundedCornerShape(20.dp),
                color = textBackgroundColor.copy(alpha = 0.08f),
                modifier = Modifier.height(36.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = device.type.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = textBackgroundColor,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = textBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun QueueCollapsedContentV9(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
    textBackgroundColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    onShuffleClick: () -> Unit,
    onRepeatModeClick: () -> Unit,
    onMenuClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    compactLandscape: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
    val railContainerColor = textBackgroundColor.copy(alpha = 0.14f)
    val buttonContainerColor = textBackgroundColor.copy(alpha = 0.08f)
    val selectedButtonContainerColor = textBackgroundColor.copy(alpha = 0.18f)
    val uncheckedColors =
        ToggleButtonDefaults.toggleButtonColors(
            containerColor = buttonContainerColor,
            contentColor = textBackgroundColor.copy(alpha = 0.76f),
        )
    val checkedColors =
        ToggleButtonDefaults.toggleButtonColors(
            checkedContainerColor = selectedButtonContainerColor,
            checkedContentColor = textBackgroundColor,
            containerColor = buttonContainerColor,
            contentColor = textBackgroundColor.copy(alpha = 0.76f),
        )

    val railHeight = if (compactLandscape) 56.dp else 72.dp
    val toggleHeight = if (compactLandscape) 44.dp else 56.dp
    val toggleIconSize = if (compactLandscape) 22.dp else 26.dp
    val horizontalRailPadding = if (compactLandscape) 28.dp else 52.dp
    val bottomPadding = if (compactLandscape) 4.dp else 8.dp

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                ).padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!compactLandscape && rememberShowCodecRow(showCodecOnPlayer, formatRow)) {
            val currentFormat = formatRow?.format
            val bitrate = currentFormat?.formattedBitrate() ?: ""
            val fileSize = currentFormat?.codecRowDetail() ?: ""

            CodecInfoRow(
                bitrate = bitrate,
                fileSize = fileSize,
                textColor = textBackgroundColor.copy(alpha = 0.6f),
            )
        }

        if (sleepTimerEnabled) {
            Surface(
                onClick = onSleepTimerClick,
                shape = RoundedCornerShape(18.dp),
                color = textBackgroundColor.copy(alpha = 0.08f),
                modifier =
                    Modifier
                        .padding(bottom = if (compactLandscape) 4.dp else 8.dp)
                        .height(if (compactLandscape) 30.dp else 34.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.bedtime),
                        contentDescription = stringResource(R.string.sleep_timer),
                        tint = textBackgroundColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = makeTimeString(sleepTimerTimeLeft.coerceAtLeast(0L)),
                        style = MaterialTheme.typography.labelMedium,
                        color = textBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(42.dp),
            color = railContainerColor,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalRailPadding)
                    .height(railHeight),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(if (compactLandscape) 6.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                ToggleButton(
                    checked = shuffleModeEnabled,
                    onCheckedChange = {
                        if (enableHapticFeedback) {
                            view.performHapticFeedback(
                                android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                            )
                        }
                        onShuffleClick()
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(toggleHeight),
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    colors = if (shuffleModeEnabled) checkedColors else uncheckedColors,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription =
                            stringResource(
                                if (shuffleModeEnabled) R.string.action_shuffle_on else R.string.action_shuffle_off,
                            ),
                        modifier = Modifier.size(toggleIconSize),
                    )
                }

                ToggleButton(
                    checked = repeatMode != Player.REPEAT_MODE_OFF,
                    onCheckedChange = {
                        if (enableHapticFeedback) {
                            view.performHapticFeedback(
                                android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                            )
                        }
                        onRepeatModeClick()
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(toggleHeight),
                    shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
                    colors = if (repeatMode != Player.REPEAT_MODE_OFF) checkedColors else uncheckedColors,
                ) {
                    Icon(
                        painter =
                            painterResource(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                    else -> R.drawable.repeat
                                },
                            ),
                        contentDescription =
                            stringResource(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                    Player.REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                    else -> R.string.repeat_mode_off
                                },
                            ),
                        modifier = Modifier.size(toggleIconSize),
                    )
                }

                Surface(
                    onClick = {
                        if (enableHapticFeedback) {
                            view.performHapticFeedback(
                                android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                            )
                        }
                        onMenuClick()
                    },
                    shape =
                        RoundedCornerShape(
                            topStart = 12.dp,
                            bottomStart = 12.dp,
                            topEnd = 34.dp,
                            bottomEnd = 34.dp,
                        ),
                    color = buttonContainerColor,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(toggleHeight),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_horiz),
                            contentDescription = stringResource(R.string.more_options),
                            tint = textBackgroundColor,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LandscapeQueueChromeRow(
    textBackgroundColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onShowLyrics: () -> Unit,
    onSleepTimerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                ).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val buttonSize = 44.dp
        val iconSize = 20.dp

        val expandPress = rememberPressInteractionSource()
        Surface(
            onClick = onExpandQueue,
            interactionSource = expandPress,
            shape = RoundedCornerShape(16.dp),
            color = textBackgroundColor.copy(alpha = 0.1f),
            modifier =
                Modifier
                    .weight(1f)
                    .height(buttonSize)
                    .hushPressMotion(
                        interactionSource = expandPress,
                        pressScale = HushDesign.RowPressScale,
                        haloColor = textBackgroundColor,
                    ),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.queue_music),
                    contentDescription = stringResource(R.string.queue),
                    tint = textBackgroundColor,
                    modifier = Modifier.size(iconSize),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.queue),
                    style = MaterialTheme.typography.labelLarge,
                    color = textBackgroundColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Surface(
            onClick = onSleepTimerClick,
            shape = if (sleepTimerEnabled) RoundedCornerShape(16.dp) else CircleShape,
            color = textBackgroundColor.copy(alpha = if (sleepTimerEnabled) 0.18f else 0.1f),
            modifier =
                if (sleepTimerEnabled) {
                    Modifier.height(buttonSize)
                } else {
                    Modifier.size(buttonSize)
                },
        ) {
            AnimatedContent(
                label = "landscapeSleepTimer",
                targetState = sleepTimerEnabled,
            ) { enabled ->
                Row(
                    modifier =
                        Modifier.padding(
                            horizontal = if (enabled) 12.dp else 0.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.bedtime),
                        contentDescription = stringResource(R.string.sleep_timer),
                        tint = textBackgroundColor,
                        modifier = Modifier.size(iconSize),
                    )
                    if (enabled) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = makeTimeString(sleepTimerTimeLeft.coerceAtLeast(0L)),
                            style = MaterialTheme.typography.labelMedium,
                            color = textBackgroundColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Surface(
            onClick = onShowLyrics,
            shape = RoundedCornerShape(16.dp),
            color = textBackgroundColor.copy(alpha = 0.1f),
            modifier =
                Modifier
                    .weight(1f)
                    .height(buttonSize),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.lyrics),
                    contentDescription = stringResource(R.string.lyrics),
                    tint = textBackgroundColor,
                    modifier = Modifier.size(iconSize),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.lyrics),
                    style = MaterialTheme.typography.labelLarge,
                    color = textBackgroundColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun WideLandscapeUpNextBar(
    nextMetadata: app.hush.music.models.MediaMetadata?,
    onExpandQueue: () -> Unit,
    modifier: Modifier = Modifier,
    applyBottomInsets: Boolean = true,
) {
    val surfaceColor = Color.White.copy(alpha = 0.08f)
    val textColor = Color.White
    val secondaryText = textColor.copy(alpha = 0.68f)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (applyBottomInsets) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                            ),
                        )
                    } else {
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Horizontal,
                            ),
                        )
                    },
                ),
    ) {
        Text(
            text = stringResource(R.string.up_next),
            style = MaterialTheme.typography.labelMedium,
            color = secondaryText,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        val expandPress = rememberPressInteractionSource()
        Surface(
            onClick = onExpandQueue,
            interactionSource = expandPress,
            shape = RoundedCornerShape(20.dp),
            color = surfaceColor,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .hushPressMotion(
                        interactionSource = expandPress,
                        pressScale = HushDesign.RowPressScale,
                        haloColor = MaterialTheme.colorScheme.primary,
                    ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = nextMetadata?.thumbnailUrl,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = nextMetadata?.title ?: stringResource(R.string.queue),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.hushMarquee(),
                    )
                    if (!nextMetadata?.artists.isNullOrEmpty()) {
                        Text(
                            text =
                                nextMetadata
                                    ?.artists
                                    ?.joinToString { it.name }
                                    .orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.hushMarquee(),
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = textColor.copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.queue_music),
                            contentDescription = stringResource(R.string.queue),
                            tint = textColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Aura collapsed queue: the codec provenance label and the glass Queue / sleep / Lyrics pills.
 * Portrait stacks the strip over the pills; landscape folds the label into the pill row so the
 * artwork above can reach the bottom of the screen. The pills sit inside the aura glow while the
 * player is expanded, so they are translucent white rather than V6's opaque theme-colored boxes.
 */
@Composable
fun AuraQueueCollapsedContent(
    showCodecOnPlayer: Boolean,
    formatRow: FormatRow?,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    applyBottomInsets: Boolean = true,
    compact: Boolean = false,
    /**
     * Where the player's controls column starts: the artwork column plus the offset the centred
     * landscape composition leaves to its left. The compact chip row centres itself in the space
     * that is left, so the chips land between the first and the last action button instead of
     * hanging off the right edge of the screen.
     */
    chromeStartGap: Dp = 0.dp,
    /**
     * The same offset on the right. Needed, not decorative: the chip row centres in the space
     * between the two gaps, so closing it on the left only would put them half an offset too far
     * right - 57dp off the action row at a 1280dp window, measured on device.
     */
    chromeEndGap: Dp = 0.dp,
) {
    // Landscape passes compact = true: the sheet keeps only the chip row, sitting under the
    // player's action row. The provenance line moves up into the player column on a line of its
    // own, directly below those actions, so this sheet no longer reserves its height.
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (compact) Alignment.End else Alignment.Start,
    ) {
        if (!compact && rememberShowCodecRow(showCodecOnPlayer, formatRow)) {
            val currentFormat = formatRow?.format

            // The provenance line sits where the bottom glow is brightest, so plain white text
            // sank into the artwork. A slim glass strip keeps it legible in portrait.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.34f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CodecInfoRow(
                    bitrate = currentFormat?.formattedBitrate() ?: "",
                    // The sample rate and the size, not the size alone: a device file's bitrate is
                    // the one fact Media3 cannot report (`bitrate=0` for every FLAC it decodes), so
                    // this strip drew `24 MB` and nothing else on the layout Aura actually ships -
                    // while the row in every other layout printed `44.1 kHz • 24 MB`. See
                    // [codecRowDetail].
                    fileSize = currentFormat?.codecRowDetail() ?: "",
                    textColor = Color.White.copy(alpha = 0.88f),
                    modifier =
                        Modifier.padding(
                            top = 3.dp,
                            bottom = 3.dp,
                        ),
                )
            }
        }

        AuraQueueBottomBar(
            sleepTimerEnabled = sleepTimerEnabled,
            sleepTimerTimeLeft = sleepTimerTimeLeft,
            onExpandQueue = onExpandQueue,
            onSleepTimerClick = onSleepTimerClick,
            onShowLyrics = onShowLyrics,
            applyBottomInsets = applyBottomInsets,
            compact = compact,
            chromeStartGap = chromeStartGap,
            chromeEndGap = chromeEndGap,
        )
    }
}

/** Glass bottom pills for the Aura style: Queue, sleep timer and Lyrics. */
@Composable
fun AuraQueueBottomBar(
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    applyBottomInsets: Boolean = true,
    compact: Boolean = false,
    /** See [AuraQueueCollapsedContent.chromeStartGap]: the chrome the chips must centre after. */
    chromeStartGap: Dp = 0.dp,
    /** See [AuraQueueCollapsedContent.chromeEndGap]: the mirrored offset on the right. */
    chromeEndGap: Dp = 0.dp,
) {
    val accent = MaterialTheme.colorScheme.primary
    val pillText = Color.White.copy(alpha = 0.92f)
    // Compact chips (landscape) hug their label so Queue / Lyrics stop stretching half the
    // screen width; the portrait pills keep the full-width shape they were designed for.
    val pillHeight = if (compact) AuraLandscapePillHeight else 48.dp
    val chipShape = RoundedCornerShape(pillHeight / 2)
    val iconSize = if (compact) 18.dp else 20.dp
    val chipGap = if (compact) 8.dp else 10.dp
    val chipLabelStyle =
        if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    // Landscape matches the player column's insets (12dp start / 20dp end) so the
                    // chip row's right edge lines up with the action row and the provenance line.
                    start = if (compact) 12.dp else 16.dp,
                    end = if (compact) 20.dp else 16.dp,
                    top = if (compact) AuraLandscapeBarVerticalPadding else 4.dp,
                    bottom = if (compact) AuraLandscapeBarVerticalPadding else 4.dp,
                ).then(
                    if (applyBottomInsets) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                            ),
                        )
                    } else {
                        Modifier
                    },
                ),
    ) {
        // Compact (landscape): the short inset mirrors the player column's own start padding, and
        // the chip row then centres inside the space that is left. Both ends of that space come
        // from the player, so what the chips centre in is exactly the controls column - the chips
        // end up between Share and the overflow menu, under the action row, while the artwork
        // reaches the bottom of the screen on its left.
        Row(
            horizontalArrangement = if (compact) Arrangement.Center else Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (compact && chromeStartGap > 0.dp) Modifier.padding(start = chromeStartGap) else Modifier)
                    .then(if (compact && chromeEndGap > 0.dp) Modifier.padding(end = chromeEndGap) else Modifier),
        ) {
            Box(
                modifier =
                    Modifier
                        .height(pillHeight)
                        .then(if (compact) Modifier else Modifier.weight(1f))
                        .clip(chipShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .hushPressable(onClick = onExpandQueue, pressScale = HushDesign.RowPressScale)
                        .then(if (compact) Modifier.padding(horizontal = 16.dp) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (compact) Modifier else Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.queue_music),
                        contentDescription = stringResource(id = R.string.queue),
                        modifier = Modifier.size(iconSize),
                        tint = pillText,
                    )
                    Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
                    Text(
                        text = stringResource(id = R.string.queue),
                        color = pillText,
                        style = chipLabelStyle,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(chipGap))

            Box(
                modifier =
                    Modifier
                        .size(pillHeight)
                        .clip(CircleShape)
                        .background(
                            if (sleepTimerEnabled) {
                                accent.copy(alpha = 0.32f)
                            } else {
                                Color.White.copy(alpha = 0.10f)
                            },
                        ).hushPressable(onClick = onSleepTimerClick, pressScale = HushDesign.ChipPressScale),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = sleepTimerEnabled,
                    label = "auraSleep",
                ) { enabled ->
                    if (enabled) {
                        Text(
                            text = makeTimeString(sleepTimerTimeLeft),
                            color = accent,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                                    .hushMarquee(),
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.bedtime),
                            contentDescription = stringResource(id = R.string.sleep_timer),
                            modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                            tint = pillText,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(chipGap))

            Box(
                modifier =
                    Modifier
                        .height(pillHeight)
                        .then(if (compact) Modifier else Modifier.weight(1f))
                        .clip(chipShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .hushPressable(onClick = onShowLyrics, pressScale = HushDesign.RowPressScale)
                        .then(if (compact) Modifier.padding(horizontal = 16.dp) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (compact) Modifier else Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.lyrics),
                        contentDescription = stringResource(id = R.string.lyrics),
                        modifier = Modifier.size(iconSize),
                        tint = pillText,
                    )
                    Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
                    Text(
                        text = stringResource(id = R.string.lyrics),
                        color = pillText,
                        style = chipLabelStyle,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
