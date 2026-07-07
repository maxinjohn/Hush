/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.hush.music.R

data class SearchableSettingEntry(
    val label: String,
    val sectionTitle: String,
    val route: String,
)

@Composable
fun getAllSearchableSettings(): List<SearchableSettingEntry> =
    listOf(
        SearchableSettingEntry(stringResource(R.string.account), stringResource(R.string.account), "settings/account"),
        SearchableSettingEntry(stringResource(R.string.appearance), stringResource(R.string.appearance), "settings/appearance"),
        SearchableSettingEntry(stringResource(R.string.settings_playback_title), stringResource(R.string.settings_playback_title), "settings/player"),
        SearchableSettingEntry(stringResource(R.string.stream_sources), stringResource(R.string.settings_playback_title), "settings/player/stream_sources"),
        SearchableSettingEntry(stringResource(R.string.jiosaavn_settings), stringResource(R.string.settings_playback_title), "settings/player/jiosaavn"),
        SearchableSettingEntry(stringResource(R.string.stream_source_android_vr), stringResource(R.string.stream_sources), "settings/player/stream_sources"),
        SearchableSettingEntry(stringResource(R.string.stream_source_web_remix), stringResource(R.string.stream_sources), "settings/player/stream_sources"),
        SearchableSettingEntry(stringResource(R.string.stream_source_tvhtml5), stringResource(R.string.stream_sources), "settings/player/stream_sources"),
        SearchableSettingEntry(stringResource(R.string.stream_source_visionos), stringResource(R.string.stream_sources), "settings/player/stream_sources"),
        SearchableSettingEntry(stringResource(R.string.stream_source_ios), stringResource(R.string.stream_sources), "settings/player/stream_sources"),
        SearchableSettingEntry(stringResource(R.string.stream_source_web_creator), stringResource(R.string.stream_sources), "settings/player/stream_sources"),
        SearchableSettingEntry(stringResource(R.string.stream_source_android_creator), stringResource(R.string.stream_sources), "settings/player/stream_sources"),
        SearchableSettingEntry(stringResource(R.string.android_auto), stringResource(R.string.android_auto), "settings/android_auto"),
        SearchableSettingEntry(stringResource(R.string.settings_behavior_title), stringResource(R.string.settings_behavior_title), "settings/privacy"),
        SearchableSettingEntry(stringResource(R.string.lyrics), stringResource(R.string.lyrics), "settings/lyrics"),
        SearchableSettingEntry(stringResource(R.string.integration), stringResource(R.string.integration), "settings/integration"),
        SearchableSettingEntry(stringResource(R.string.ai_integration), stringResource(R.string.ai_integration), "settings/ai_integration"),
        SearchableSettingEntry(stringResource(R.string.backup_restore), stringResource(R.string.backup_restore), "settings/backup_restore"),
        SearchableSettingEntry(stringResource(R.string.content), stringResource(R.string.content), "settings/content"),
        SearchableSettingEntry(stringResource(R.string.internet), stringResource(R.string.internet), "settings/internet"),
        SearchableSettingEntry(stringResource(R.string.storage), stringResource(R.string.storage), "settings/storage"),
        SearchableSettingEntry(stringResource(R.string.about), stringResource(R.string.about), "settings/about"),
        SearchableSettingEntry(stringResource(R.string.audio_normalization), stringResource(R.string.settings_playback_title), "settings/player"),
        SearchableSettingEntry(stringResource(R.string.loudness_level), stringResource(R.string.settings_playback_title), "settings/player"),
        SearchableSettingEntry(stringResource(R.string.audio_offload), stringResource(R.string.settings_playback_title), "settings/player"),
        SearchableSettingEntry(stringResource(R.string.audio_crossfade_title), stringResource(R.string.settings_playback_title), "settings/player"),
        SearchableSettingEntry(stringResource(R.string.audio_quality), stringResource(R.string.settings_playback_title), "settings/player"),
        SearchableSettingEntry(stringResource(R.string.alarm), stringResource(R.string.settings_playback_title), "settings/alarm"),
        SearchableSettingEntry(stringResource(R.string.lyrics_provider_priority), stringResource(R.string.content), "settings/content"),
        SearchableSettingEntry(stringResource(R.string.lyrics_animation_style), stringResource(R.string.lyrics), "settings/lyrics"),
        SearchableSettingEntry(stringResource(R.string.canvas_source), stringResource(R.string.appearance), "settings/appearance/canvas"),
        SearchableSettingEntry(stringResource(R.string.content_country), stringResource(R.string.content), "settings/content"),
        SearchableSettingEntry(stringResource(R.string.network_ip_version), stringResource(R.string.content), "settings/content"),
        SearchableSettingEntry(stringResource(R.string.hide_explicit), stringResource(R.string.content), "settings/content"),
        SearchableSettingEntry(stringResource(R.string.hide_video), stringResource(R.string.content), "settings/content"),
        SearchableSettingEntry(stringResource(R.string.automatic_backup), stringResource(R.string.backup_restore), "settings/backup_restore/autobackup"),
        SearchableSettingEntry(stringResource(R.string.listen_history), stringResource(R.string.settings_behavior_title), "settings/privacy"),
        SearchableSettingEntry(stringResource(R.string.search_history), stringResource(R.string.settings_behavior_title), "settings/privacy"),
        SearchableSettingEntry(stringResource(R.string.po_token_generation), stringResource(R.string.po_token_generation), "settings/po_token"),
        SearchableSettingEntry(stringResource(R.string.updates), stringResource(R.string.updates), "settings/update"),
    )
