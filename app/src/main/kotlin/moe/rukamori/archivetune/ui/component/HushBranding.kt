/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R

private val DefaultLogoSize = 56.dp
private val DefaultLogoBoxSize = 68.dp
private val CompactLogoSize = 48.dp
private val CompactLogoBoxSize = 56.dp

@Composable
fun HushBrandHeader(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showTagline: Boolean = true,
    horizontalPadding: Dp = 12.dp,
) {
    val logoSize = if (compact) CompactLogoSize else DefaultLogoSize
    val logoBoxSize = if (compact) CompactLogoBoxSize else DefaultLogoBoxSize
    val sectionGap = if (compact) 8.dp else 12.dp

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(sectionGap),
    ) {
        Box(
            modifier = Modifier.size(logoBoxSize),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.hush_logo_mark),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(logoSize),
            )
        }

        Text(
            text = stringResource(R.string.app_name),
            style =
                if (compact) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.headlineMedium
                },
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        if (showTagline) {
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
