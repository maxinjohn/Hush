/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package app.hush.music.ui.screens.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.*
import app.hush.music.innertube.YouTube
import app.hush.music.ui.component.EditTextPreference
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.ListPreference
import app.hush.music.ui.component.LyricsProviderEnableState
import app.hush.music.ui.component.LyricsProviderPriorityDialog
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.component.SwitchPreference
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import app.hush.music.utils.setAppLocale
import java.util.Locale

@Composable
fun ContentSettings(navController: NavController) {
    val context = LocalContext.current

    // Used only before Android 13
    val (appLanguage, onAppLanguageChange) = rememberPreference(key = AppLanguageKey, defaultValue = SYSTEM_DEFAULT)

    val (contentLanguage, onContentLanguageChange) = rememberPreference(key = ContentLanguageKey, defaultValue = "system")
    val (contentCountry, onContentCountryChange) = rememberPreference(key = ContentCountryKey, defaultValue = "system")
    val (ipVersion, onIpVersionChange) =
        rememberEnumPreference(
            key = IpVersionKey,
            defaultValue = app.hush.music.innertube.models.IpVersion.AUTO,
        )
    val (playlistSuggestionSource, onPlaylistSuggestionSourceChange) =
        rememberEnumPreference(
            key = PlaylistSuggestionSourceKey,
            defaultValue = PlaylistSuggestionSource.BOTH,
        )
    val (hideExplicit, onHideExplicitChange) = rememberPreference(key = HideExplicitKey, defaultValue = false)
    val (hideVideo, onHideVideoChange) = rememberPreference(key = HideVideoKey, defaultValue = false)
    val (lengthTop, onLengthTopChange) = rememberPreference(key = TopSize, defaultValue = "50")
    val (quickPicks, onQuickPicksChange) = rememberEnumPreference(key = QuickPicksKey, defaultValue = QuickPicks.QUICK_PICKS)

    val (enableBetterLyrics, _) = rememberPreference(key = EnableBetterLyricsKey, defaultValue = true)
    val (enableYouLyPlus, _) = rememberPreference(key = EnableYouLyPlusLyricsKey, defaultValue = true)
    val (enableLrcLib, _) = rememberPreference(key = EnableLrcLibKey, defaultValue = true)
    val (enableKugou, _) = rememberPreference(key = EnableKugouKey, defaultValue = true)
    val (enableSimpMusic, _) = rememberPreference(key = EnableSimpMusicLyricsKey, defaultValue = true)
    val (enableUnison, _) = rememberPreference(key = EnableUnisonLyricsKey, defaultValue = true)
    val (enablePaxsenix, _) = rememberPreference(key = EnablePaxsenixLyricsKey, defaultValue = true)
    val (enablePaxsenixAppleMusic, _) = rememberPreference(key = EnablePaxsenixAppleMusicLyricsKey, defaultValue = true)
    val (enablePaxsenixNetease, _) = rememberPreference(key = EnablePaxsenixNeteaseLyricsKey, defaultValue = true)
    val (enablePaxsenixSpotify, _) = rememberPreference(key = EnablePaxsenixSpotifyLyricsKey, defaultValue = true)
    val (enablePaxsenixMusixmatch, _) = rememberPreference(key = EnablePaxsenixMusixmatchLyricsKey, defaultValue = true)
    val (enablePaxsenixYouTube, _) = rememberPreference(key = EnablePaxsenixYouTubeLyricsKey, defaultValue = true)
    val (providerOrderStr, onProviderOrderStrChange) =
        rememberPreference(key = LyricsProviderOrderKey, defaultValue = "")

    var showProviderPriorityDialog by rememberSaveable { mutableStateOf(false) }

    if (showProviderPriorityDialog) {
        LyricsProviderPriorityDialog(
            providerOrderStr = providerOrderStr,
            enableState =
                LyricsProviderEnableState(
                    enableBetterLyrics = enableBetterLyrics,
                    enableYouLyPlus = enableYouLyPlus,
                    enableLrcLib = enableLrcLib,
                    enableKugou = enableKugou,
                    enableSimpMusic = enableSimpMusic,
                    enableUnison = enableUnison,
                    enablePaxsenix = enablePaxsenix,
                    enablePaxsenixAppleMusic = enablePaxsenixAppleMusic,
                    enablePaxsenixNetease = enablePaxsenixNetease,
                    enablePaxsenixSpotify = enablePaxsenixSpotify,
                    enablePaxsenixMusixmatch = enablePaxsenixMusixmatch,
                    enablePaxsenixYouTube = enablePaxsenixYouTube,
                ),
            onDismiss = { showProviderPriorityDialog = false },
            onOrderChange = onProviderOrderStrChange,
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(bottom = SettingsDimensions.ScreenBottomPadding),
    ) {
        PreferenceGroup(title = stringResource(R.string.general)) {
            item {
                ListPreference(
                    title = { Text(stringResource(R.string.content_language)) },
                    icon = { Icon(painterResource(R.drawable.language), null) },
                    selectedValue = contentLanguage,
                    values = listOf(SYSTEM_DEFAULT) + LanguageCodeToName.keys.toList(),
                    valueText = {
                        LanguageCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
                    },
                    onValueSelected = { newValue ->
                        val locale = Locale.getDefault()
                        val languageTag = locale.toLanguageTag().replace("-Hant", "")

                        YouTube.locale =
                            YouTube.locale.copy(
                                hl =
                                    newValue.takeIf { it != SYSTEM_DEFAULT }
                                        ?: locale.language.takeIf { it in LanguageCodeToName }
                                        ?: languageTag.takeIf { it in LanguageCodeToName }
                                        ?: "en",
                            )

                        onContentLanguageChange(newValue)
                    },
                )
            }

            item {
                ListPreference(
                    title = { Text(stringResource(R.string.content_country)) },
                    icon = { Icon(painterResource(R.drawable.location_on), null) },
                    selectedValue = contentCountry,
                    values = listOf(SYSTEM_DEFAULT) + CountryCodeToName.keys.toList(),
                    valueText = {
                        CountryCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
                    },
                    onValueSelected = { newValue ->
                        val locale = Locale.getDefault()

                        YouTube.locale =
                            YouTube.locale.copy(
                                gl =
                                    newValue.takeIf { it != SYSTEM_DEFAULT }
                                        ?: locale.country.takeIf { it in CountryCodeToName }
                                        ?: "US",
                            )

                        onContentCountryChange(newValue)
                    },
                )
            }

            item {
                ListPreference(
                    title = { Text(stringResource(R.string.network_ip_version)) },
                    icon = { Icon(painterResource(R.drawable.wifi_proxy), null) },
                    selectedValue = ipVersion,
                    values = app.hush.music.innertube.models.IpVersion.entries,
                    valueText = {
                        when (it) {
                            app.hush.music.innertube.models.IpVersion.AUTO ->
                                stringResource(R.string.ip_version_auto)
                            app.hush.music.innertube.models.IpVersion.IPV4 ->
                                stringResource(R.string.ip_version_ipv4)
                            app.hush.music.innertube.models.IpVersion.IPV6 ->
                                stringResource(R.string.ip_version_ipv6)
                        }
                    },
                    onValueSelected = { newValue ->
                        YouTube.ipVersion = newValue
                        onIpVersionChange(newValue)
                    },
                )
            }

            item {
                ListPreference(
                    title = { Text(stringResource(R.string.you_might_like_source)) },
                    icon = { Icon(painterResource(R.drawable.playlist_play), null) },
                    selectedValue = playlistSuggestionSource,
                    values =
                        listOf(
                            PlaylistSuggestionSource.PLAYLIST_TITLE,
                            PlaylistSuggestionSource.PLAYLIST_CONTENT,
                            PlaylistSuggestionSource.BOTH,
                        ),
                    valueText = {
                        when (it) {
                            PlaylistSuggestionSource.PLAYLIST_TITLE -> stringResource(R.string.playlist_suggestion_source_title)
                            PlaylistSuggestionSource.PLAYLIST_CONTENT -> stringResource(R.string.playlist_suggestion_source_content)
                            PlaylistSuggestionSource.BOTH -> stringResource(R.string.playlist_suggestion_source_both)
                        }
                    },
                    onValueSelected = onPlaylistSuggestionSourceChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.hide_explicit)) },
                    icon = { Icon(painterResource(R.drawable.explicit), null) },
                    checked = hideExplicit,
                    onCheckedChange = onHideExplicitChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.hide_video)) },
                    icon = { Icon(painterResource(R.drawable.slow_motion_video), null) },
                    checked = hideVideo,
                    onCheckedChange = onHideVideoChange,
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.app_language)) {
            item {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.app_language)) },
                        icon = { Icon(painterResource(R.drawable.language), null) },
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APP_LOCALE_SETTINGS,
                                    "package:${context.packageName}".toUri(),
                                ),
                            )
                        },
                    )
                } else {
                    ListPreference(
                        title = { Text(stringResource(R.string.app_language)) },
                        icon = { Icon(painterResource(R.drawable.language), null) },
                        selectedValue = appLanguage,
                        values = listOf(SYSTEM_DEFAULT) + LanguageCodeToName.keys.toList(),
                        valueText = {
                            LanguageCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
                        },
                        onValueSelected = { langTag ->
                            val newLocale =
                                langTag
                                    .takeUnless { it == SYSTEM_DEFAULT }
                                    ?.let { Locale.forLanguageTag(it) }
                                    ?: Locale.getDefault()

                            onAppLanguageChange(langTag)
                            setAppLocale(context, newLocale)
                        },
                    )
                }
            }
        }

        PreferenceGroup(title = stringResource(R.string.lyrics)) {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.lyrics_provider_priority)) },
                    description = stringResource(R.string.lyrics_provider_priority_desc),
                    icon = { Icon(painterResource(R.drawable.lyrics), null) },
                    onClick = { showProviderPriorityDialog = true },
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.misc)) {
            item {
                EditTextPreference(
                    title = { Text(stringResource(R.string.top_length)) },
                    icon = { Icon(painterResource(R.drawable.trending_up), null) },
                    value = lengthTop,
                    isInputValid = { it.toIntOrNull()?.let { num -> num > 0 } == true },
                    onValueChange = onLengthTopChange,
                )
            }

            item {
                ListPreference(
                    title = { Text(stringResource(R.string.set_quick_picks)) },
                    icon = { Icon(painterResource(R.drawable.home_outlined), null) },
                    selectedValue = quickPicks,
                    values = listOf(QuickPicks.QUICK_PICKS, QuickPicks.LAST_LISTEN, QuickPicks.DONT_SHOW),
                    valueText = {
                        when (it) {
                            QuickPicks.QUICK_PICKS -> stringResource(R.string.quick_picks)
                            QuickPicks.LAST_LISTEN -> stringResource(R.string.last_song_listened)
                            QuickPicks.DONT_SHOW -> stringResource(R.string.dont_show)
                        }
                    },
                    onValueSelected = onQuickPicksChange,
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.content)) },
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
    )
}
