/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.theme.ArchiveTuneDesign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.ui.theme.archiveTunePressable
import moe.rukamori.archivetune.ui.utils.HeaderDownloadProgressIndicator
import moe.rukamori.archivetune.ui.utils.HeaderDownloadState

private val HeaderActionHeight = 48.dp
private val HeaderIconSize = 22.dp
private val CompactWidthThreshold = 360.dp

@Composable
fun PlaylistHeaderActionLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

/**
 * Standard list header actions: Like → Play → Shuffle → Download → More.
 * Extra actions belong in the ⋮ menu.
 */
@Composable
fun StandardPlaylistHeaderActions(
    modifier: Modifier = Modifier,
    liked: Boolean? = null,
    onToggleLike: (() -> Unit)? = null,
    subscribed: Boolean? = null,
    onToggleSubscribe: (() -> Unit)? = null,
    leadingAction: (@Composable () -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    downloadState: HeaderDownloadState? = null,
    onDownloadClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    playEnabled: Boolean = true,
    shuffleEnabled: Boolean = true,
    showDownload: Boolean = downloadState != null && onDownloadClick != null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < CompactWidthThreshold
        PlaylistHeaderActionLayout {
            when {
                leadingAction != null -> leadingAction()
                liked != null && onToggleLike != null -> {
                    HeaderIconAction(
                        onClick = onToggleLike,
                        contentDescription = stringResource(R.string.action_like),
                        active = liked,
                        activeTint = MaterialTheme.colorScheme.error,
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (liked) R.drawable.favorite else R.drawable.favorite_border,
                                ),
                            contentDescription = null,
                            modifier = Modifier.size(HeaderIconSize),
                            tint = if (liked) MaterialTheme.colorScheme.error else LocalIconTint(),
                        )
                    }
                }

                subscribed != null && onToggleSubscribe != null -> {
                    HeaderIconAction(
                        onClick = onToggleSubscribe,
                        contentDescription =
                            stringResource(
                                if (subscribed) R.string.subscribed else R.string.subscribe,
                            ),
                        active = subscribed,
                        activeTint = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (subscribed) R.drawable.subscribed else R.drawable.subscribe,
                                ),
                            contentDescription = null,
                            modifier = Modifier.size(HeaderIconSize),
                            tint = if (subscribed) MaterialTheme.colorScheme.primary else LocalIconTint(),
                        )
                    }
                }
            }

            if (onPlay != null) {
                HeaderPlayAction(
                    onClick = onPlay,
                    enabled = playEnabled,
                    showLabel = !compact,
                )
            }

            if (onShuffle != null) {
                HeaderIconAction(
                    onClick = onShuffle,
                    enabled = shuffleEnabled,
                    contentDescription = stringResource(R.string.shuffle),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription = null,
                        modifier = Modifier.size(HeaderIconSize),
                        tint = LocalIconTint(),
                    )
                }
            }

            if (showDownload) {
                HeaderIconAction(
                    onClick = onDownloadClick!!,
                    contentDescription = stringResource(R.string.download),
                    active = downloadState == HeaderDownloadState.Completed,
                    activeTint = MaterialTheme.colorScheme.primary,
                ) {
                    PlaylistDownloadButtonContent(
                        downloadState = downloadState ?: HeaderDownloadState.None,
                        iconSize = HeaderIconSize,
                    )
                }
            }

            if (onMoreClick != null) {
                HeaderIconAction(
                    onClick = onMoreClick,
                    contentDescription = stringResource(R.string.options),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                        modifier = Modifier.size(HeaderIconSize),
                        tint = LocalIconTint(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalIconTint(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun HeaderPlayAction(
    onClick: () -> Unit,
    enabled: Boolean,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val playLabel = stringResource(R.string.play)
    val haptic = LocalHapticFeedback.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
    val gradient =
        Brush.horizontalGradient(
            colors =
                listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.tertiary,
                ),
        )
    Surface(
        modifier =
            modifier
                .height(HeaderActionHeight)
                .widthIn(min = if (showLabel) 112.dp else HeaderActionHeight)
                .clip(ArchiveTuneDesign.chipShape)
                .archiveTunePressable(
                    onClick = {
                        if (enableHapticFeedback) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onClick()
                    },
                    enabled = enabled,
                    pressScale = ArchiveTuneDesign.ChipPressScale,
                )
                .semantics { contentDescription = playLabel },
        color = Color.Transparent,
        shape = ArchiveTuneDesign.chipShape,
    ) {
        Box(
            modifier =
                Modifier
                    .background(gradient, ArchiveTuneDesign.chipShape)
                    .padding(horizontal = if (showLabel) 18.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    modifier = Modifier.size(HeaderIconSize),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                if (showLabel) {
                    Text(
                        text = playLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderIconAction(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = HeaderActionHeight,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
    val containerColor =
        if (active) {
            activeTint.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Surface(
        modifier =
            modifier
                .size(iconSize)
                .clip(CircleShape)
                .archiveTunePressable(
                    onClick = {
                        if (enableHapticFeedback) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onClick()
                    },
                    enabled = enabled,
                    pressScale = ArchiveTuneDesign.ChipPressScale,
                ).semantics { this.contentDescription = contentDescription },
        color = containerColor,
        shape = CircleShape,
    ) {
        Box(
            modifier =
                Modifier
                    .border(0.5.dp, borderColor, CircleShape)
                    .padding(1.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
fun PlaylistDownloadButtonContent(
    downloadState: HeaderDownloadState,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
) {
    when (downloadState) {
        HeaderDownloadState.Completed -> {
            Icon(
                painter = painterResource(R.drawable.offline),
                contentDescription = null,
                modifier = modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        is HeaderDownloadState.Partial -> {
            HeaderDownloadProgressIndicator(progress = downloadState.progress, modifier = modifier)
        }

        HeaderDownloadState.None -> {
            Icon(
                painter = painterResource(R.drawable.download),
                contentDescription = null,
                modifier = modifier.size(iconSize),
                tint = LocalIconTint(),
            )
        }
    }
}

// Legacy aliases kept for any remaining direct usages during migration.
@Deprecated("Use StandardPlaylistHeaderActions", ReplaceWith("StandardPlaylistHeaderActions"))
@Composable
fun PlaylistPrimaryActionButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconRes: Int,
    label: String,
    shapes: androidx.compose.material3.ToggleButtonShapes,
    colors: androidx.compose.material3.ToggleButtonColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonHeight: Dp = 48.dp,
    iconSize: Dp = 20.dp,
) {
    HeaderPlayAction(onClick = { onCheckedChange(!checked) }, enabled = enabled, showLabel = true, modifier = modifier)
}

@Deprecated("Use StandardPlaylistHeaderActions", ReplaceWith("StandardPlaylistHeaderActions"))
@Composable
fun PlaylistIconActionButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconRes: Int,
    contentDescription: String,
    shapes: androidx.compose.material3.ToggleButtonShapes,
    colors: androidx.compose.material3.ToggleButtonColors,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    content: (@Composable () -> Unit)? = null,
) {
    HeaderIconAction(
        onClick = { onCheckedChange(!checked) },
        contentDescription = contentDescription,
        modifier = modifier,
        active = checked,
    ) {
        if (content != null) {
            content()
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = LocalIconTint(),
            )
        }
    }
}
