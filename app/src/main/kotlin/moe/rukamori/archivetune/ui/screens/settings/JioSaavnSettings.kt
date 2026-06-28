/*
 * Hush — GPL-3.0
 * JioSaavn settings UI adapted from Vivi Music (GPL-3.0).
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EnableSaavnStreamingKey
import moe.rukamori.archivetune.constants.SaavnAudioQuality
import moe.rukamori.archivetune.constants.SaavnAudioQualityKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.theme.HushAmbientBackground
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.material3.Switch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JioSaavnSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (saavnEnabled, onSaavnEnabledChange) = rememberPreference(EnableSaavnStreamingKey, defaultValue = false)
    val (saavnQuality, onSaavnQualityChange) =
        rememberEnumPreference(SaavnAudioQualityKey, defaultValue = SaavnAudioQuality.QUALITY_320)

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(
            heightFraction = 0.55f,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(TopAppBarDefaults.TopAppBarExpandedHeight + 8.dp))

            Text(
                text = stringResource(R.string.enable_saavn_streaming_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            val containerColor by animateColorAsState(
                targetValue =
                    if (saavnEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                label = "saavnToggleContainer",
            )
            val contentColor =
                if (saavnEnabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

            Card(
                onClick = { onSaavnEnabledChange(!saavnEnabled) },
                shape = RoundedCornerShape(50),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.enable_saavn_streaming),
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                    )
                    Switch(
                        checked = saavnEnabled,
                        onCheckedChange = onSaavnEnabledChange,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            PreferenceGroup(title = stringResource(R.string.saavn_audio_quality)) {
                SaavnAudioQuality.entries.forEach { quality ->
                    item {
                        PreferenceEntry(
                            title = { Text(quality.toLabel()) },
                            icon = {
                                RadioButton(
                                    selected = saavnQuality == quality,
                                    onClick = null,
                                    enabled = saavnEnabled,
                                )
                            },
                            isEnabled = saavnEnabled,
                            onClick = { onSaavnQualityChange(quality) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 24.dp, bottom = 36.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    painter = painterResource(R.drawable.info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                )
                Text(
                    text = stringResource(R.string.jiosaavn_beta_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TopAppBar(
            title = { Text(stringResource(R.string.jiosaavn_settings)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
            scrollBehavior = scrollBehavior,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ),
        )
    }
}
