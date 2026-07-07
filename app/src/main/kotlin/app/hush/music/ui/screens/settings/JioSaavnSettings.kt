/*
 * Hush — GPL-3.0
 * Clean JioSaavn settings with compact UI.
 */

package app.hush.music.ui.screens.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.LocalPlayerConnection
import app.hush.music.R
import app.hush.music.constants.EnableSaavnStreamingKey
import app.hush.music.constants.SaavnAudioQuality
import app.hush.music.constants.SaavnAudioQualityKey
import app.hush.music.ui.component.FeatureBetaBadge
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JioSaavnSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (saavnEnabled, onSaavnEnabledChange) = rememberPreference(EnableSaavnStreamingKey, defaultValue = false)
    val playerConnection = LocalPlayerConnection.current
    val onSaavnToggle: (Boolean) -> Unit = { enabled ->
        onSaavnEnabledChange(enabled)
        if (enabled) {
            playerConnection?.service?.clearSaavnIncompatiblePlaybackCache()
        }
    }
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
                    .statusBarsPadding()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(TopAppBarDefaults.TopAppBarExpandedHeight + 24.dp))

            // Enable toggle card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.enable_saavn_streaming),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.jiosaavn_settings_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(if (saavnEnabled) 0f else 1f),
                        )
                    }
                    Switch(
                        checked = saavnEnabled,
                        onCheckedChange = onSaavnToggle,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quality selector
            PreferenceGroup(title = stringResource(R.string.saavn_audio_quality)) {
                SaavnAudioQuality.entries.forEach { quality ->
                    item {
                        PreferenceEntry(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = quality.toLabel(),
                                        color = if (saavnEnabled) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    )
                                }
                            },
                            description = when (quality) {
                                SaavnAudioQuality.QUALITY_320 -> "Best quality, more data"
                                SaavnAudioQuality.QUALITY_160 -> "Balanced quality and data"
                                SaavnAudioQuality.QUALITY_96 -> "Lowest data usage"
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.graphic_eq),
                                    contentDescription = null,
                                    tint = if (saavnEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                )
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = saavnQuality == quality,
                                    onClick = { onSaavnQualityChange(quality) },
                                    enabled = saavnEnabled,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        disabledSelectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        disabledUnselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    ),
                                )
                            },
                            isEnabled = saavnEnabled,
                            onClick = { if (saavnEnabled) onSaavnQualityChange(quality) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Info card - compact
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.jiosaavn_beta_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(SettingsDimensions.ScreenBottomPadding))
        }

        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.jiosaavn_settings),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FeatureBetaBadge()
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            scrollBehavior = scrollBehavior,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
        )
    }
}
