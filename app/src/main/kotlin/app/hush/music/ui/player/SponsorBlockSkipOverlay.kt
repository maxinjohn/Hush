package app.hush.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.hush.music.constants.SponsorBlockAutoSkipKey
import app.hush.music.playback.PlayerConnection
import app.hush.music.utils.rememberPreference
import app.hush.music.utils.sponsorblock.SegmentCategory
import app.hush.music.utils.sponsorblock.SponsorBlockManager
import app.hush.music.utils.sponsorblock.SponsorBlockSegment

@Composable
fun SponsorBlockSkipOverlay(
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    val segments by SponsorBlockManager.currentSegments.collectAsState()
    val (autoSkip) = rememberPreference(SponsorBlockAutoSkipKey, true)

    if (segments.isEmpty()) return

    val currentPosition = playerConnection.player.currentPosition
    val activeSegment = SponsorBlockManager.getActiveSegment(currentPosition)

    if (autoSkip && activeSegment != null) {
        LaunchedEffect(activeSegment) {
            val skipToMs = SponsorBlockManager.skipToEnd(activeSegment)
            if (playerConnection.player.currentPosition < skipToMs) {
                playerConnection.player.seekTo(skipToMs)
            }
        }
    }

    AnimatedVisibility(
        visible = !autoSkip && activeSegment != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        activeSegment?.let { segment ->
            Box(
                modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                SponsorBlockSkipButton(
                    segment = segment,
                    onSkip = {
                        val skipToMs = SponsorBlockManager.skipToEnd(segment)
                        playerConnection.player.seekTo(skipToMs)
                    },
                )
            }
        }
    }
}

@Composable
private fun SponsorBlockSkipButton(
    segment: SponsorBlockSegment,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segmentColor = when (segment.category) {
        SegmentCategory.SPONSOR -> Color(0xFF00D1FF)
        SegmentCategory.INTRO -> Color(0xFF00FF88)
        SegmentCategory.OUTRO -> Color(0xFFFF6B6B)
        SegmentCategory.SELF_PROMO -> Color(0xFFFFD700)
        SegmentCategory.INTERACTION -> Color(0xFFFF9500)
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onSkip),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Skip ${SegmentCategory.displayName(segment.category)}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            Spacer(modifier = Modifier.width(4.dp))
            val durationText = formatDuration((segment.end - segment.start).toLong())
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onSkip) {
                Text(
                    text = "Skip",
                    color = segmentColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}
