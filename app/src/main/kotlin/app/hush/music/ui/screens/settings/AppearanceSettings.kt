/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.AppFontPreference
import app.hush.music.constants.PulseMatrixEnabledKey
import app.hush.music.constants.PulseMatrixThemeKey
import app.hush.music.constants.PulseMatrixMiniPlayerKey
import app.hush.music.constants.PulseMatrixIntensityKey
import app.hush.music.constants.PulseMatrixPeakHoldKey
import app.hush.music.constants.HushCanvasKey
import app.hush.music.constants.CanvasSource
import app.hush.music.constants.CanvasSourceKey
import app.hush.music.constants.BackdropBlurAmountKey
import app.hush.music.constants.BackdropEnabledKey
import app.hush.music.constants.BlurRadiusKey
import app.hush.music.constants.ChipSortTypeKey
import app.hush.music.constants.CropThumbnailToSquareKey
import app.hush.music.constants.CustomFontNameKey
import app.hush.music.constants.CustomFontUriKey
import app.hush.music.constants.DarkModeKey
import app.hush.music.constants.DefaultOpenTabKey
import app.hush.music.constants.DisableAnimationsKey
import app.hush.music.constants.DisableBlurKey
import app.hush.music.constants.DynamicThemeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.hush.music.constants.EnableDynamicIconKey
import app.hush.music.constants.FontPreferenceKey
import app.hush.music.constants.GridItemSize
import app.hush.music.constants.GridItemsSizeKey
import app.hush.music.constants.HidePlayerThumbnailKey
import app.hush.music.constants.LandscapePlayerLayoutKey
import app.hush.music.constants.CarExpressiveAutoHideTitleKey
import app.hush.music.constants.CarExpressiveTitleHideDelayKey
import app.hush.music.constants.LibraryFilter
import app.hush.music.constants.MiniPlayerBackgroundStyle
import app.hush.music.constants.MiniPlayerBackgroundStyleKey
import app.hush.music.constants.PlayerBackgroundStyle
import app.hush.music.constants.PlayerBackgroundStyleKey
import app.hush.music.constants.PlayerButtonsStyle
import app.hush.music.constants.PlayerButtonsStyleKey
import app.hush.music.constants.PlayerDesignStyle
import app.hush.music.constants.rememberPlayerDesignStylePreference
import app.hush.music.constants.PureBlackKey
import app.hush.music.constants.QuickPicksDisplayMode
import app.hush.music.constants.QuickPicksDisplayModeKey
import app.hush.music.constants.RandomThemeOnStartupKey
import app.hush.music.constants.ShowHomeCategoryChipsKey
import app.hush.music.constants.ShowTagsInLibraryKey
import app.hush.music.constants.SliderStyle
import app.hush.music.constants.SliderStyleKey
import app.hush.music.constants.SwipeSensitivityKey
import app.hush.music.constants.SwipeThumbnailKey
import app.hush.music.constants.SwipeToSongKey
import app.hush.music.constants.ThumbnailCornerRadiusKey
import app.hush.music.ui.component.DefaultDialog
import app.hush.music.ui.component.EnumListPreference
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.ListPreference
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.component.SwitchPreference
import app.hush.music.ui.component.ThumbnailCornerRadiusSelectorButton
import app.hush.music.ui.player.StyledPlaybackSlider
import app.hush.music.ui.player.visualizer.PulseMatrixSettings
import app.hush.music.ui.player.visualizer.PulseMatrixTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.hush.music.ui.theme.CustomFontLoader
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.isLowRamDevice
import app.hush.music.utils.IconUtils
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val iconScope = rememberCoroutineScope()
    val defaultDisableAnimations = remember(context) { context.isLowRamDevice() }
    val (dynamicTheme, onDynamicThemeChange) =
        rememberPreference(
            DynamicThemeKey,
            defaultValue = true,
        )
    val (enableDynamicIcon, onEnableDynamicIconPrefChange) =
        rememberPreference(
            EnableDynamicIconKey,
            defaultValue = true,
        )
    val onEnableDynamicIconChange: (Boolean) -> Unit = { enabled ->
        onEnableDynamicIconPrefChange(enabled)
        iconScope.launch(Dispatchers.IO) {
            IconUtils.setIcon(context, enabled)
        }
    }
    LaunchedEffect(enableDynamicIcon) {
        val actualEnabled =
            withContext(Dispatchers.IO) {
                IconUtils.isDynamicIconEnabled(context)
            }
        if (enableDynamicIcon != actualEnabled) {
            withContext(Dispatchers.IO) {
                IconUtils.setIcon(context, enableDynamicIcon)
            }
        }
    }
    val (randomThemeOnStartup, onRandomThemeOnStartupChange) =
        rememberPreference(
            RandomThemeOnStartupKey,
            defaultValue = false,
        )
    val (darkMode, onDarkModeChange) =
        rememberEnumPreference(
            DarkModeKey,
            defaultValue = DarkMode.AUTO,
        )
    val (playerDesignStyle, onPlayerDesignStyleChange) = rememberPlayerDesignStylePreference()
    val (carExpressiveLayout, onCarExpressiveLayoutChange) =
        rememberPreference(
            LandscapePlayerLayoutKey,
            defaultValue = false,
        )
    val (carExpressiveAutoHideTitle, onCarExpressiveAutoHideTitleChange) =
        rememberPreference(
            CarExpressiveAutoHideTitleKey,
            defaultValue = false,
        )
    val (carExpressiveTitleHideDelay, onCarExpressiveTitleHideDelayChange) =
        rememberPreference(
            CarExpressiveTitleHideDelayKey,
            defaultValue = 5,
        )
    val (hidePlayerThumbnail, onHidePlayerThumbnailChange) =
        rememberPreference(
            HidePlayerThumbnailKey,
            defaultValue = false,
        )
    val (pulseMatrixEnabled, onPulseMatrixEnabledChange) =
        rememberPreference(
            PulseMatrixEnabledKey,
            defaultValue = false,
        )
    val (pulseMatrixTheme, onPulseMatrixThemeChange) =
        rememberEnumPreference(
            PulseMatrixThemeKey,
            defaultValue = PulseMatrixTheme.AURORA,
        )
    val (pulseMatrixMiniPlayer, onPulseMatrixMiniPlayerChange) =
        rememberPreference(
            PulseMatrixMiniPlayerKey,
            defaultValue = true,
        )
    val (pulseMatrixIntensity, onPulseMatrixIntensityChange) =
        rememberEnumPreference(
            PulseMatrixIntensityKey,
            defaultValue = PulseMatrixSettings.IntensityLevel.NORMAL,
        )
    val (pulseMatrixPeakHold, onPulseMatrixPeakHoldChange) =
        rememberPreference(
            PulseMatrixPeakHoldKey,
            defaultValue = true,
        )
    // Sync PulseMatrixSettings with preferences
    LaunchedEffect(pulseMatrixEnabled) {
        PulseMatrixSettings.setEnabled(pulseMatrixEnabled)
    }
    LaunchedEffect(pulseMatrixTheme) {
        PulseMatrixSettings.setTheme(pulseMatrixTheme)
    }
    LaunchedEffect(pulseMatrixIntensity) {
        PulseMatrixSettings.setIntensityLevel(pulseMatrixIntensity)
    }
    LaunchedEffect(pulseMatrixPeakHold) {
        PulseMatrixSettings.setPeakHoldEnabled(pulseMatrixPeakHold)
    }
    val (hushCanvasEnabled, onHushCanvasEnabledChange) =
        rememberPreference(
            HushCanvasKey,
            defaultValue = false,
        )
    val (canvasSource) =
        rememberEnumPreference(
            CanvasSourceKey,
            defaultValue = CanvasSource.AUTO,
        )
    val (thumbnailCornerRadius, onThumbnailCornerRadiusChange) =
        rememberPreference(
            key = ThumbnailCornerRadiusKey,
            defaultValue = 16f, // default dp
        )
    val (cropThumbnailToSquare, onCropThumbnailToSquareChange) =
        rememberPreference(
            CropThumbnailToSquareKey,
            defaultValue = false,
        )
    val (playerBackground, onPlayerBackgroundChange) =
        rememberEnumPreference(
            PlayerBackgroundStyleKey,
            defaultValue = PlayerBackgroundStyle.DEFAULT,
        )
    val (miniPlayerBackground, onMiniPlayerBackgroundChange) =
        rememberEnumPreference(
            MiniPlayerBackgroundStyleKey,
            defaultValue = MiniPlayerBackgroundStyle.THEME,
        )
    val (pureBlack, onPureBlackChange) = rememberPreference(PureBlackKey, defaultValue = false)
    val (disableBlur, onDisableBlurChange) = rememberPreference(DisableBlurKey, defaultValue = false)
    val (disableAnimations, onDisableAnimationsChange) =
        rememberPreference(
            DisableAnimationsKey,
            defaultValue = defaultDisableAnimations,
        )
    val (blurRadius, onBlurRadiusChange) = rememberPreference(BlurRadiusKey, defaultValue = 48f)
    val (backdropEnabled, onBackdropEnabledChange) = rememberPreference(BackdropEnabledKey, defaultValue = true)
    val (backdropBlurAmount, onBackdropBlurAmountChange) = rememberPreference(BackdropBlurAmountKey, defaultValue = 60)
    val (fontPreference, onFontPreferenceChange) =
        rememberEnumPreference(
            FontPreferenceKey,
            defaultValue = AppFontPreference.DEFAULT,
        )
    val (customFontUri, onCustomFontUriChange) = rememberPreference(CustomFontUriKey, defaultValue = "")
    val (customFontName, onCustomFontNameChange) = rememberPreference(CustomFontNameKey, defaultValue = "")
    val (defaultOpenTab, onDefaultOpenTabChange) =
        rememberEnumPreference(
            DefaultOpenTabKey,
            defaultValue = NavigationTab.HOME,
        )
    val (playerButtonsStyle, onPlayerButtonsStyleChange) =
        rememberEnumPreference(
            PlayerButtonsStyleKey,
            defaultValue = PlayerButtonsStyle.DEFAULT,
        )
    val (sliderStyle, onSliderStyleChange) =
        rememberEnumPreference(
            SliderStyleKey,
            defaultValue = SliderStyle.Standard,
        )
    val (swipeThumbnail, onSwipeThumbnailChange) =
        rememberPreference(
            SwipeThumbnailKey,
            defaultValue = true,
        )
    val (swipeSensitivity, onSwipeSensitivityChange) =
        rememberPreference(
            SwipeSensitivityKey,
            defaultValue = 0.73f,
        )
    val (gridItemSize, onGridItemSizeChange) =
        rememberEnumPreference(
            GridItemsSizeKey,
            defaultValue = GridItemSize.SMALL,
        )

    val (swipeToSong, onSwipeToSongChange) =
        rememberPreference(
            SwipeToSongKey,
            defaultValue = false,
        )

    val (showTagsInLibrary, onShowTagsInLibraryChange) =
        rememberPreference(
            ShowTagsInLibraryKey,
            defaultValue = true,
        )
    val (showHomeCategoryChips, onShowHomeCategoryChipsChange) =
        rememberPreference(
            ShowHomeCategoryChipsKey,
            defaultValue = true,
        )
    val (quickPicksDisplayMode, onQuickPicksDisplayModeChange) =
        rememberEnumPreference(
            QuickPicksDisplayModeKey,
            defaultValue = QuickPicksDisplayMode.CARD,
        )

    val customFontPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (!CustomFontLoader.isSupportedTtf(context, uri)) {
                Toast.makeText(context, context.getString(R.string.custom_font_invalid), Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            if (customFontUri.isNotBlank() && customFontUri != uri.toString()) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(customFontUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }

            onCustomFontUriChange(uri.toString())
            onCustomFontNameChange(CustomFontLoader.displayName(context, uri))
            onFontPreferenceChange(AppFontPreference.CUSTOM)
        }
    val pickCustomFont =
        remember(customFontPickerLauncher) {
            {
                customFontPickerLauncher.launch(CustomFontLoader.supportedMimeTypes)
            }
        }

    val availableBackgroundStyles =
        PlayerBackgroundStyle.entries.filter {
            it != PlayerBackgroundStyle.BLUR || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }
    val isPlayerStyleCustomizationEnabled = true
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme =
        remember(darkMode, isSystemInDarkTheme) {
            if (darkMode == DarkMode.AUTO) isSystemInDarkTheme else darkMode == DarkMode.ON
        }

    val (defaultChip, onDefaultChipChange) =
        rememberEnumPreference(
            key = ChipSortTypeKey,
            defaultValue = LibraryFilter.LIBRARY,
        )

    var showSliderOptionDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // Customization is always enabled (V7/V8/V9 are no longer selectable)

    if (showSliderOptionDialog && isPlayerStyleCustomizationEnabled) {
        val sliderStyles =
            remember {
                listOf(
                    SliderStyle.Standard,
                    SliderStyle.Wavy,
                    SliderStyle.Thick,
                    SliderStyle.Circular,
                    SliderStyle.Simple,
                )
            }
        DefaultDialog(
            buttons = {
                TextButton(
                    onClick = { showSliderOptionDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
            onDismiss = {
                showSliderOptionDialog = false
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sliderStyles.chunked(3).forEach { styleRow ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        styleRow.forEach { style ->
                            SliderStyleOptionCard(
                                sliderStyle = style,
                                selected = sliderStyle == style,
                                onClick = {
                                    onSliderStyleChange(style)
                                    showSliderOptionDialog = false
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - styleRow.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(
            heightFraction = 0.55f,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(bottom = SettingsDimensions.ScreenBottomPadding),
    ) {
        PreferenceGroup(title = stringResource(R.string.theme)) {
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.enable_dynamic_theme)) },
                    icon = { Icon(painterResource(R.drawable.palette), null) },
                    checked = dynamicTheme,
                    onCheckedChange = onDynamicThemeChange,
                )
            }

            item(visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.enable_dynamic_icon)) },
                    description = stringResource(R.string.enable_dynamic_icon_desc),
                    icon = { Icon(painterResource(R.drawable.small_icon), null) },
                    checked = enableDynamicIcon,
                    onCheckedChange = onEnableDynamicIconChange,
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.app_icon)) },
                    description = stringResource(R.string.app_icon_description),
                    icon = { Icon(painterResource(R.drawable.hush_app_icon), null) },
                    onClick = { navController.navigate("settings/appearance/icon") },
                )
            }

            item(visible = !dynamicTheme || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.random_theme_on_startup)) },
                    description = stringResource(R.string.random_theme_on_startup_desc),
                    icon = { Icon(painterResource(R.drawable.shuffle), null) },
                    checked = randomThemeOnStartup,
                    onCheckedChange = onRandomThemeOnStartupChange,
                )
            }

            item(visible = !dynamicTheme || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.color_palette)) },
                    description = stringResource(R.string.customize_theme_colors),
                    icon = { Icon(painterResource(R.drawable.format_paint), null) },
                    onClick = { navController.navigate("settings/appearance/palette_picker") },
                )
            }

            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.dark_theme)) },
                    icon = { Icon(painterResource(R.drawable.dark_mode), null) },
                    selectedValue = darkMode,
                    onValueSelected = onDarkModeChange,
                    valueText = {
                        when (it) {
                            DarkMode.ON -> stringResource(R.string.dark_theme_on)
                            DarkMode.OFF -> stringResource(R.string.dark_theme_off)
                            DarkMode.AUTO -> stringResource(R.string.dark_theme_follow_system)
                        }
                    },
                )
            }

            item(visible = useDarkTheme) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.pure_black)) },
                    icon = { Icon(painterResource(R.drawable.contrast), null) },
                    checked = pureBlack,
                    onCheckedChange = onPureBlackChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.disable_blur)) },
                    description = stringResource(R.string.disable_blur_desc),
                    icon = { Icon(painterResource(R.drawable.blur_off), null) },
                    checked = disableBlur,
                    onCheckedChange = onDisableBlurChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.disable_animations)) },
                    description = stringResource(R.string.disable_animations_desc),
                    icon = { Icon(painterResource(R.drawable.animation), null) },
                    checked = disableAnimations,
                    onCheckedChange = onDisableAnimationsChange,
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.blur_intensity)) },
                    description = stringResource(R.string.blur_intensity_value, blurRadius.roundToInt()),
                    icon = { Icon(painterResource(R.drawable.blur_on), null) },
                    isEnabled = !disableBlur,
                    content = {
                        Spacer(modifier = Modifier.height(10.dp))
                        Slider(
                            value = blurRadius,
                            onValueChange = onBlurRadiusChange,
                            valueRange = 0f..64f,
                            steps = 63,
                            enabled = !disableBlur,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.album_backdrop)) },
                    description = stringResource(R.string.album_backdrop_desc),
                    icon = { Icon(painterResource(R.drawable.blur_on), null) },
                    checked = backdropEnabled,
                    onCheckedChange = onBackdropEnabledChange,
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.backdrop_blur_amount)) },
                    description = stringResource(R.string.backdrop_blur_amount_value, backdropBlurAmount),
                    icon = { Icon(painterResource(R.drawable.blur_on), null) },
                    isEnabled = backdropEnabled,
                    content = {
                        Spacer(modifier = Modifier.height(10.dp))
                        Slider(
                            value = backdropBlurAmount.toFloat(),
                            onValueChange = { onBackdropBlurAmountChange(it.roundToInt()) },
                            valueRange = 0f..100f,
                            steps = 19,
                            enabled = backdropEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.font_preference)) },
                    description = stringResource(R.string.font_preference_desc),
                    icon = { Icon(painterResource(R.drawable.text_fields), null) },
                    onClick = { navController.navigate("settings/appearance/font_selection") },
                    trailingContent = {
                        Text(
                            text =
                                when (fontPreference) {
                                    AppFontPreference.DEFAULT -> stringResource(R.string.font_preference_default)
                                    AppFontPreference.SYSTEM -> stringResource(R.string.font_preference_system)
                                    AppFontPreference.OUTFIT -> stringResource(R.string.font_option_outfit)
                                    AppFontPreference.PLUS_JAKARTA -> stringResource(R.string.font_option_plus_jakarta)
                                    AppFontPreference.CUSTOM -> stringResource(R.string.font_preference_custom)
                                    else -> stringResource(R.string.font_preference_default)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            item(visible = fontPreference == AppFontPreference.CUSTOM) {
                val customFontDescription =
                    if (customFontName.isNotBlank()) {
                        customFontName
                    } else if (customFontUri.isBlank()) {
                        stringResource(R.string.custom_font_desc)
                    } else {
                        customFontUri
                    }
                PreferenceEntry(
                    title = { Text(stringResource(R.string.custom_font)) },
                    description = customFontDescription,
                    icon = { Icon(painterResource(R.drawable.text_fields), null) },
                    onClick = pickCustomFont,
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.player)) {
            if (PlayerDesignStyle.selectableValues.size > 1) {
                item {
                    EnumListPreference(
                        title = { Text(stringResource(R.string.player_design_style)) },
                        icon = { Icon(painterResource(R.drawable.palette), null) },
                        selectedValue = playerDesignStyle,
                        onValueSelected = onPlayerDesignStyleChange,
                        values = PlayerDesignStyle.selectableValues,
                        valueText = {
                            when (it) {
                                PlayerDesignStyle.V2 -> stringResource(R.string.player_design_v2)
                                PlayerDesignStyle.V4 -> stringResource(R.string.player_design_v4)
                                PlayerDesignStyle.V6 -> stringResource(R.string.player_design_v6)
                                else -> stringResource(R.string.player_design_v6)
                            }
                        },
                    )
                }
            }

            item(visible = playerDesignStyle == PlayerDesignStyle.V6) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.car_expressive_player_layout)) },
                    description = stringResource(R.string.car_expressive_player_layout_desc),
                    icon = { Icon(painterResource(R.drawable.grid_view), null) },
                    checked = carExpressiveLayout,
                    onCheckedChange = onCarExpressiveLayoutChange,
                )
            }

            item(visible = playerDesignStyle == PlayerDesignStyle.V6 && carExpressiveLayout) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.car_expressive_auto_hide_title)) },
                    description = stringResource(R.string.car_expressive_auto_hide_title_desc),
                    icon = { Icon(painterResource(R.drawable.visibility_off), null) },
                    checked = carExpressiveAutoHideTitle,
                    onCheckedChange = onCarExpressiveAutoHideTitleChange,
                )
            }

            item(
                visible =
                    playerDesignStyle == PlayerDesignStyle.V6 &&
                        carExpressiveLayout &&
                        carExpressiveAutoHideTitle,
            ) {
                ListPreference(
                    title = { Text(stringResource(R.string.car_expressive_title_hide_delay)) },
                    icon = { Icon(painterResource(R.drawable.timer), null) },
                    selectedValue = carExpressiveTitleHideDelay.toString(),
                    onValueSelected = { value ->
                        value.toIntOrNull()?.let(onCarExpressiveTitleHideDelayChange)
                    },
                    values = listOf("3", "5", "8", "10"),
                    valueText = { seconds -> stringResource(R.string.car_expressive_title_hide_delay_value, seconds) },
                )
            }

            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.player_background_style)) },
                    icon = { Icon(painterResource(R.drawable.gradient), null) },
                    selectedValue = playerBackground,
                    onValueSelected = onPlayerBackgroundChange,
                    valueText = {
                        when (it) {
                            PlayerBackgroundStyle.DEFAULT -> stringResource(R.string.follow_theme)
                            PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                            PlayerBackgroundStyle.CUSTOM -> stringResource(R.string.custom)
                            PlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                            PlayerBackgroundStyle.COLORING -> stringResource(R.string.coloring)
                            PlayerBackgroundStyle.BLUR_GRADIENT -> stringResource(R.string.blur_gradient)
                            PlayerBackgroundStyle.GLOW -> stringResource(R.string.glow)
                            PlayerBackgroundStyle.GLOW_ANIMATED -> "Glow Animated"
                        }
                    },
                )
            }

            item(visible = playerBackground == PlayerBackgroundStyle.CUSTOM) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.customized_background)) },
                    icon = { Icon(painterResource(R.drawable.image), null) },
                    onClick = { navController.navigate("customize_background") },
                )
            }

            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.mini_player_background_style)) },
                    icon = { Icon(painterResource(R.drawable.gradient), null) },
                    selectedValue = miniPlayerBackground,
                    onValueSelected = onMiniPlayerBackgroundChange,
                    valueText = {
                        when (it) {
                            MiniPlayerBackgroundStyle.THEME -> stringResource(R.string.follow_theme)
                            MiniPlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                            MiniPlayerBackgroundStyle.GLOW -> stringResource(R.string.glow)
                        }
                    },
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.hide_player_thumbnail)) },
                    description = stringResource(R.string.hide_player_thumbnail_desc),
                    icon = { Icon(painterResource(R.drawable.hide_image), null) },
                    checked = hidePlayerThumbnail,
                    onCheckedChange = onHidePlayerThumbnailChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.pulse_matrix)) },
                    description = stringResource(R.string.pulse_matrix_desc),
                    icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                    checked = pulseMatrixEnabled,
                    onCheckedChange = onPulseMatrixEnabledChange,
                )
            }

            item(visible = pulseMatrixEnabled) {
                EnumListPreference(
                    title = { Text(stringResource(R.string.pulse_matrix_theme)) },
                    icon = { Icon(painterResource(R.drawable.palette), null) },
                    selectedValue = pulseMatrixTheme,
                    onValueSelected = onPulseMatrixThemeChange,
                    valueText = {
                        when (it) {
                            PulseMatrixTheme.NEON -> "Neon"
                            PulseMatrixTheme.AMBER -> "Amber"
                            PulseMatrixTheme.CYAN -> "Cyan"
                            PulseMatrixTheme.EMERALD -> "Emerald"
                            PulseMatrixTheme.CRIMSON -> "Crimson"
                            PulseMatrixTheme.VIOLET -> "Violet"
                            PulseMatrixTheme.ICE -> "Ice"
                            PulseMatrixTheme.AURORA -> "Aurora"
                        }
                    },
                )
            }

            item(visible = pulseMatrixEnabled) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.pulse_matrix_mini_player)) },
                    description = stringResource(R.string.pulse_matrix_mini_player_desc),
                    icon = { Icon(painterResource(R.drawable.vibration), null) },
                    checked = pulseMatrixMiniPlayer,
                    onCheckedChange = onPulseMatrixMiniPlayerChange,
                )
            }

            item(visible = pulseMatrixEnabled) {
                ListPreference(
                    title = { Text(stringResource(R.string.pulse_matrix_intensity)) },
                    icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                    selectedValue = pulseMatrixIntensity.name,
                    onValueSelected = { name ->
                        onPulseMatrixIntensityChange(PulseMatrixSettings.IntensityLevel.valueOf(name))
                    },
                    valueText = {
                        when (it) {
                            "LOW" -> stringResource(R.string.pulse_matrix_intensity_low)
                            "NORMAL" -> stringResource(R.string.pulse_matrix_intensity_normal)
                            "HIGH" -> stringResource(R.string.pulse_matrix_intensity_high)
                            else -> stringResource(R.string.pulse_matrix_intensity_normal)
                        }
                    },
                    values = listOf("LOW", "NORMAL", "HIGH"),
                )
            }

            item(visible = pulseMatrixEnabled) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.pulse_matrix_peak_hold)) },
                    description = stringResource(R.string.pulse_matrix_peak_hold_desc),
                    icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                    checked = pulseMatrixPeakHold,
                    onCheckedChange = onPulseMatrixPeakHoldChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.hush_canvas)) },
                    description = stringResource(R.string.hush_canvas_desc),
                    icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
                    checked = hushCanvasEnabled,
                    onCheckedChange = onHushCanvasEnabledChange,
                )
            }

            item(visible = hushCanvasEnabled) {
                val canvasSourceLabel =
                    when (canvasSource) {
                        CanvasSource.AUTO -> stringResource(R.string.canvas_source_auto)
                        CanvasSource.APPLE_MUSIC -> stringResource(R.string.canvas_source_apple_music)
                        CanvasSource.HUSH_CANVAS -> stringResource(R.string.canvas_source_hush_canvas)
                        CanvasSource.TIDAL -> stringResource(R.string.canvas_source_tidal)
                    }
                PreferenceEntry(
                    title = { Text(stringResource(R.string.canvas_source)) },
                    description = canvasSourceLabel,
                    icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
                    onClick = { navController.navigate("settings/appearance/canvas") },
                )
            }

            item {
                ThumbnailCornerRadiusSelectorButton(
                    onRadiusSelected = {},
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.crop_thumbnail_to_square)) },
                    description = stringResource(R.string.crop_thumbnail_to_square_desc),
                    icon = { Icon(painterResource(R.drawable.image), null) },
                    checked = cropThumbnailToSquare,
                    onCheckedChange = onCropThumbnailToSquareChange,
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.aod_customize_title)) },
                    description = stringResource(R.string.aod_customize_entry_desc),
                    icon = { Icon(painterResource(R.drawable.bedtime), null) },
                    onClick = { navController.navigate("settings/appearance/aod_customized") },
                )
            }

            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.player_buttons_style)) },
                    icon = { Icon(painterResource(R.drawable.palette), null) },
                    selectedValue = playerButtonsStyle,
                    onValueSelected = onPlayerButtonsStyleChange,
                    valueText = {
                        when (it) {
                            PlayerButtonsStyle.DEFAULT -> stringResource(R.string.default_style)
                            PlayerButtonsStyle.SECONDARY -> stringResource(R.string.secondary_color_style)
                        }
                    },
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.player_slider_style)) },
                    description = sliderStyleLabel(sliderStyle),
                    icon = { Icon(painterResource(R.drawable.sliders), null) },
                    onClick = {
                        showSliderOptionDialog = true
                    },
                    isEnabled = isPlayerStyleCustomizationEnabled,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.enable_swipe_thumbnail)) },
                    icon = { Icon(painterResource(R.drawable.swipe), null) },
                    checked = swipeThumbnail,
                    onCheckedChange = onSwipeThumbnailChange,
                )
            }

            item(visible = swipeThumbnail) {
                var showSensitivityDialog by rememberSaveable { mutableStateOf(false) }

                if (showSensitivityDialog) {
                    var tempSensitivity by remember { mutableFloatStateOf(swipeSensitivity) }

                    DefaultDialog(
                        onDismiss = {
                            tempSensitivity = swipeSensitivity
                            showSensitivityDialog = false
                        },
                        buttons = {
                            TextButton(
                                onClick = {
                                    tempSensitivity = 0.73f
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.reset))
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            TextButton(
                                onClick = {
                                    tempSensitivity = swipeSensitivity
                                    showSensitivityDialog = false
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(android.R.string.cancel))
                            }
                            TextButton(
                                onClick = {
                                    onSwipeSensitivityChange(tempSensitivity)
                                    showSensitivityDialog = false
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(android.R.string.ok))
                            }
                        },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.swipe_sensitivity),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )

                            Text(
                                text = stringResource(R.string.sensitivity_percentage, (tempSensitivity * 100).roundToInt()),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )

                            Slider(
                                value = tempSensitivity,
                                onValueChange = { tempSensitivity = it },
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                PreferenceEntry(
                    title = { Text(stringResource(R.string.swipe_sensitivity)) },
                    description = stringResource(R.string.sensitivity_percentage, (swipeSensitivity * 100).roundToInt()),
                    icon = { Icon(painterResource(R.drawable.tune), null) },
                    onClick = { showSensitivityDialog = true },
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.misc)) {
            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.quick_picks_display_mode)) },
                    icon = { Icon(painterResource(R.drawable.grid_view), null) },
                    selectedValue = quickPicksDisplayMode,
                    onValueSelected = onQuickPicksDisplayModeChange,
                    valueText = {
                        when (it) {
                            QuickPicksDisplayMode.CARD -> stringResource(R.string.quick_picks_display_mode_card)
                            QuickPicksDisplayMode.LIST -> stringResource(R.string.quick_picks_display_mode_list)
                        }
                    },
                )
            }

            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.default_open_tab)) },
                    icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                    selectedValue = defaultOpenTab,
                    onValueSelected = onDefaultOpenTabChange,
                    valueText = {
                        when (it) {
                            NavigationTab.HOME -> stringResource(R.string.home)
                            NavigationTab.SEARCH -> stringResource(R.string.search)
                            NavigationTab.MOODANDGENRES -> stringResource(R.string.mood_and_genres)
                            NavigationTab.LIBRARY -> stringResource(R.string.filter_library)
                        }
                    },
                )
            }

            item {
                ListPreference(
                    title = { Text(stringResource(R.string.default_lib_chips)) },
                    icon = { Icon(painterResource(R.drawable.tab), null) },
                    selectedValue = defaultChip,
                    values =
                        listOf(
                            LibraryFilter.LIBRARY,
                            LibraryFilter.PLAYLISTS,
                            LibraryFilter.SONGS,
                            LibraryFilter.ALBUMS,
                            LibraryFilter.ARTISTS,
                            LibraryFilter.PODCASTS,
                        ),
                    valueText = {
                        when (it) {
                            LibraryFilter.SONGS -> stringResource(R.string.songs)
                            LibraryFilter.ARTISTS -> stringResource(R.string.artists)
                            LibraryFilter.ALBUMS -> stringResource(R.string.albums)
                            LibraryFilter.PLAYLISTS -> stringResource(R.string.playlists)
                            LibraryFilter.PODCASTS -> stringResource(R.string.filter_podcasts)
                            LibraryFilter.SPOTIFY -> stringResource(R.string.spotify_playlists)
                            LibraryFilter.LIBRARY -> stringResource(R.string.filter_library)
                        }
                    },
                    onValueSelected = onDefaultChipChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.show_home_category_chips)) },
                    description = stringResource(R.string.show_home_category_chips_desc),
                    icon = { Icon(painterResource(R.drawable.home_outlined), null) },
                    checked = showHomeCategoryChips,
                    onCheckedChange = onShowHomeCategoryChipsChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.show_tags_in_library)) },
                    description = stringResource(R.string.show_tags_in_library_desc),
                    icon = { Icon(painterResource(R.drawable.filter_alt), null) },
                    checked = showTagsInLibrary,
                    onCheckedChange = onShowTagsInLibraryChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.swipe_song_to_add)) },
                    icon = { Icon(painterResource(R.drawable.swipe), null) },
                    checked = swipeToSong,
                    onCheckedChange = onSwipeToSongChange,
                )
            }
        }
    }

        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter),
            title = { Text(stringResource(R.string.appearance)) },
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
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
        )
    }
}

@Composable
private fun SliderStyleOptionCard(
    sliderStyle: SliderStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember {
        mutableFloatStateOf(0.5f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(16.dp),
                ).clickable(onClick = onClick)
                .padding(16.dp),
    ) {
        StyledPlaybackSlider(
            sliderStyle = sliderStyle,
            value = sliderValue,
            valueRange = 0f..1f,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {},
            activeColor = MaterialTheme.colorScheme.primary,
            isPlaying = true,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        )

        Text(
            text = sliderStyleLabel(sliderStyle),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun sliderStyleLabel(sliderStyle: SliderStyle): String =
    when (sliderStyle) {
        SliderStyle.Standard -> stringResource(R.string.slider_style_standard)
        SliderStyle.Wavy -> stringResource(R.string.slider_style_wavy)
        SliderStyle.Thick -> stringResource(R.string.slider_style_thick)
        SliderStyle.Circular -> stringResource(R.string.slider_style_circular)
        SliderStyle.Simple -> stringResource(R.string.slider_style_simple)
    }

enum class DarkMode {
    ON,
    OFF,
    AUTO,
}

enum class NavigationTab {
    HOME,
    SEARCH,
    MOODANDGENRES,
    LIBRARY,
}

enum class PlayerTextAlignment {
    SIDED,
    CENTER,
}

enum class LyricsPosition {
    LEFT,
    CENTER,
    RIGHT,
}
