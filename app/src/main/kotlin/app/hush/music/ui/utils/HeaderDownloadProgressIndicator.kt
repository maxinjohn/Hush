/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.hush.music.R

@Composable
fun HeaderDownloadProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val boundedProgress =
        remember(progress) {
            progress.coerceIn(0f, 1f)
        }

    Box(
        modifier = modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (boundedProgress < 0.01f) {
            Icon(
                painter = painterResource(R.drawable.download),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = LocalContentColor.current,
            )
        } else {
            CircularProgressIndicator(
                progress = { boundedProgress },
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeWidth = 3.dp,
            )
        }
    }
}
