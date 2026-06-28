/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.constants.PlayerStreamClientKey
import moe.rukamori.archivetune.constants.StreamSourceAndroidCreatorKey
import moe.rukamori.archivetune.constants.StreamSourceAndroidVRKey
import moe.rukamori.archivetune.constants.StreamSourceIOSKey
import moe.rukamori.archivetune.constants.StreamSourcePreferences
import moe.rukamori.archivetune.constants.StreamSourceTVHTML5Key
import moe.rukamori.archivetune.constants.StreamSourceVisionOSKey
import moe.rukamori.archivetune.constants.StreamSourceWebCreatorKey
import moe.rukamori.archivetune.constants.StreamSourceWebRemixKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.theme.HushAmbientBackground
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamSourcesSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (webRemix, onWebRemixChange) = rememberPreference(StreamSourceWebRemixKey, defaultValue = true)
    val (tvHtml5, onTvHtml5Change) = rememberPreference(StreamSourceTVHTML5Key, defaultValue = true)
    val (visionOS, onVisionOSChange) = rememberPreference(StreamSourceVisionOSKey, defaultValue = true)
    val (androidVR, onAndroidVRChange) = rememberPreference(StreamSourceAndroidVRKey, defaultValue = true)
    val (ios, onIosChange) = rememberPreference(StreamSourceIOSKey, defaultValue = false)
    val (webCreator, onWebCreatorChange) = rememberPreference(StreamSourceWebCreatorKey, defaultValue = true)
    val (androidCreator, onAndroidCreatorChange) =
        rememberPreference(StreamSourceAndroidCreatorKey, defaultValue = false)
    val (preferredStreamClient, _) =
        rememberEnumPreference(PlayerStreamClientKey, defaultValue = PlayerStreamClient.ANDROID_VR)

    val disabledClients =
        remember(webRemix, tvHtml5, visionOS, androidVR, ios, webCreator, androidCreator) {
            StreamSourcePreferences.disabledClientNames(
                webRemix = webRemix,
                tvHtml5 = tvHtml5,
                visionOs = visionOS,
                androidVr = androidVR,
                ios = ios,
                webCreator = webCreator,
                androidCreator = androidCreator,
            )
        }
    val allToggleableDisabled =
        remember(disabledClients) {
            StreamSourcePreferences.toggleableFamilies.all { it in disabledClients }
        }
    val streamOrderFamilies =
        remember(preferredStreamClient, disabledClients) {
            YTPlayerUtils.streamClientPreviewFamilies(preferredStreamClient, disabledClients)
        }

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(
            heightFraction = 0.55f,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            Modifier
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ).nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(bottom = SettingsDimensions.ScreenBottomPadding),
        ) {
            Spacer(
                Modifier.windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
                ),
            )

            Text(
                text = stringResource(R.string.stream_source_order),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        start = SettingsDimensions.ScreenHorizontalPadding,
                        end = SettingsDimensions.ScreenHorizontalPadding,
                        top = 8.dp,
                        bottom = 4.dp,
                    ),
            )
            Text(
                text = stringResource(R.string.stream_source_order_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier =
                    Modifier.padding(
                        start = SettingsDimensions.ScreenHorizontalPadding,
                        end = SettingsDimensions.ScreenHorizontalPadding,
                        bottom = 8.dp,
                    ),
            )

            AnimatedVisibility(
                visible = allToggleableDisabled,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                Text(
                    text = stringResource(R.string.stream_source_none_enabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.padding(
                            start = SettingsDimensions.ScreenHorizontalPadding,
                            end = SettingsDimensions.ScreenHorizontalPadding,
                            bottom = 8.dp,
                        ),
                )
            }

            AnimatedContent(
                targetState = streamOrderFamilies,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                },
                label = "streamSourceOrder",
            ) { families ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                            .horizontalScroll(rememberScrollState()),
                ) {
                    families.forEachIndexed { index, family ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "${index + 1}. ${streamClientFamilyLabel(family)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            PreferenceGroup(title = stringResource(R.string.stream_source_web_clients)) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.stream_source_web_remix)) },
                        description = stringResource(R.string.stream_source_web_remix_desc),
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        checked = webRemix,
                        onCheckedChange = onWebRemixChange,
                    )
                }
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.stream_source_tvhtml5)) },
                        description = stringResource(R.string.stream_source_tvhtml5_desc),
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        checked = tvHtml5,
                        onCheckedChange = onTvHtml5Change,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            PreferenceGroup(title = stringResource(R.string.stream_source_native_clients)) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.stream_source_visionos)) },
                        description = stringResource(R.string.stream_source_visionos_desc),
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        checked = visionOS,
                        onCheckedChange = onVisionOSChange,
                    )
                }
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.stream_source_android_vr)) },
                        description = stringResource(R.string.stream_source_android_vr_desc),
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        checked = androidVR,
                        onCheckedChange = onAndroidVRChange,
                    )
                }
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.stream_source_ios)) },
                        description = stringResource(R.string.stream_source_ios_desc),
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        checked = ios,
                        onCheckedChange = onIosChange,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            PreferenceGroup(title = stringResource(R.string.stream_source_creator_clients)) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.stream_source_web_creator)) },
                        description = stringResource(R.string.stream_source_web_creator_desc),
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        checked = webCreator,
                        onCheckedChange = onWebCreatorChange,
                    )
                }
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.stream_source_android_creator)) },
                        description = stringResource(R.string.stream_source_android_creator_desc),
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        checked = androidCreator,
                        onCheckedChange = onAndroidCreatorChange,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter),
            title = { Text(stringResource(R.string.stream_sources)) },
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
        )
    }
}

@Composable
private fun streamClientFamilyLabel(family: String): String =
    when (family) {
        "WEB_REMIX" -> stringResource(R.string.stream_source_web_remix)
        "TVHTML5" -> stringResource(R.string.stream_source_tvhtml5)
        "VISIONOS" -> stringResource(R.string.stream_source_visionos)
        "ANDROID_VR" -> stringResource(R.string.stream_source_android_vr)
        "IOS" -> stringResource(R.string.stream_source_ios)
        "WEB_CREATOR" -> stringResource(R.string.stream_source_web_creator)
        "ANDROID_CREATOR" -> stringResource(R.string.stream_source_android_creator)
        else -> family
    }
