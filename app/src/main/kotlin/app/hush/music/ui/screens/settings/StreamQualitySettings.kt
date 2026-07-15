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
import androidx.compose.foundation.layout.statusBarsPadding
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
import app.hush.music.constants.AudioQuality
import app.hush.music.constants.AudioQualityKey
import app.hush.music.constants.EnableSaavnStreamingKey
import app.hush.music.constants.ParallelSourceFetchKey
import app.hush.music.constants.PrimaryAudioScraper
import app.hush.music.constants.PrimaryAudioScraperKey
import app.hush.music.constants.SaavnAudioQuality
import app.hush.music.constants.SaavnAudioQualityKey
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.ListPreference
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.SwitchPreference
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamQualitySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioQuality, onAudioQualityChange) =
        rememberEnumPreference(AudioQualityKey, defaultValue = AudioQuality.AUTO)
    val (legacySaavnEnabled, onLegacySaavnEnabledChange) =
        rememberPreference(EnableSaavnStreamingKey, defaultValue = false)
    val (primaryScraper, onPrimaryScraperChange) =
        rememberEnumPreference(
            PrimaryAudioScraperKey,
            defaultValue = if (legacySaavnEnabled) PrimaryAudioScraper.JIOSAAVN else PrimaryAudioScraper.YOUTUBE,
        )
    val saavnEnabled = primaryScraper == PrimaryAudioScraper.JIOSAAVN
    val playerConnection = LocalPlayerConnection.current
    val onSaavnToggle: (Boolean) -> Unit = { enabled ->
        // This is now controlled by Primary Scraper, but kept for cache clearing
        if (enabled) {
            playerConnection?.service?.clearSaavnIncompatiblePlaybackCache()
        }
    }
    val (saavnQuality, onSaavnQualityChange) =
        rememberEnumPreference(SaavnAudioQualityKey, defaultValue = SaavnAudioQuality.QUALITY_320)
    val onSaavnQualitySelected: (SaavnAudioQuality) -> Unit = { quality ->
        onSaavnQualityChange(quality)
        playerConnection?.service?.clearSaavnIncompatiblePlaybackCache()
    }

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
            Spacer(modifier = Modifier.height(TopAppBarDefaults.TopAppBarExpandedHeight + 16.dp))

            PreferenceGroup(title = stringResource(R.string.audio_source)) {
                item {
                    ListPreference(
                        title = { Text(stringResource(R.string.primary_audio_scraper)) },
                        description = when (primaryScraper) {
                            PrimaryAudioScraper.YOUTUBE -> stringResource(R.string.primary_scraper_yt_only)
                            PrimaryAudioScraper.JIOSAAVN -> stringResource(R.string.primary_scraper_saavn_fallback)
                        },
                        icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                        selectedValue = primaryScraper,
                        values = PrimaryAudioScraper.entries,
                        onValueSelected = { newScraper ->
                            onPrimaryScraperChange(newScraper)
                            onLegacySaavnEnabledChange(newScraper == PrimaryAudioScraper.JIOSAAVN)
                            playerConnection?.service?.clearSaavnIncompatiblePlaybackCache()
                        },
                        valueText = {
                            when (it) {
                                PrimaryAudioScraper.YOUTUBE -> stringResource(R.string.primary_scraper_youtube)
                                PrimaryAudioScraper.JIOSAAVN -> stringResource(R.string.primary_scraper_jiosaavn)
                            }
                        },
                    )
                }

                item {
                    val (parallelFetch, onParallelFetchChange) =
                        rememberPreference(ParallelSourceFetchKey, defaultValue = false)
                    SwitchPreference(
                        title = { Text(stringResource(R.string.parallel_source_fetch)) },
                        description = stringResource(R.string.parallel_source_fetch_desc),
                        icon = { Icon(painterResource(R.drawable.integration), null) },
                        checked = parallelFetch,
                        onCheckedChange = onParallelFetchChange,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PreferenceGroup(title = stringResource(R.string.youtube_music_quality)) {
                item {
                    ListPreference(
                        title = { Text(stringResource(R.string.youtube_music_quality)) },
                        description = stringResource(R.string.youtube_quality_desc),
                        icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                        selectedValue = audioQuality,
                        values = listOf(
                            AudioQuality.HIGHEST,
                            AudioQuality.HIGH,
                            AudioQuality.AUTO,
                            AudioQuality.LOW,
                        ),
                        onValueSelected = onAudioQualityChange,
                        valueText = {
                            when (it) {
                                AudioQuality.HIGHEST -> stringResource(R.string.audio_quality_max)
                                AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                                AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                                AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PreferenceGroup(title = stringResource(R.string.jiosaavn_quality)) {
                if (!saavnEnabled) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.info),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Set Primary Scraper to JioSaavn in Audio Source to enable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item {
                        ListPreference(
                            title = { Text(stringResource(R.string.jiosaavn_quality)) },
                            description = stringResource(R.string.jiosaavn_quality_desc),
                            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                            selectedValue = saavnQuality,
                            values = SaavnAudioQuality.entries,
                            onValueSelected = onSaavnQualitySelected,
                            valueText = { it.toLabel() },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(SettingsDimensions.ScreenBottomPadding))
        }

        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.stream_quality),
                    color = MaterialTheme.colorScheme.onSurface,
                )
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
