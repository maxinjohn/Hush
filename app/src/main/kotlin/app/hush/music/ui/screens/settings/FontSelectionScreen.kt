/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.AppFontPreference
import app.hush.music.constants.FontPreferenceKey
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.theme.GoogleSansFontFamily
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.theme.OutfitFontFamily
import app.hush.music.ui.theme.PlusJakartaSansFontFamily
import app.hush.music.ui.theme.SansFlexFontFamily
import app.hush.music.ui.theme.fontFamilyFor
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.rememberEnumPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSelectionScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (fontPreference, onFontPreferenceChange) =
        rememberEnumPreference(
            FontPreferenceKey,
            defaultValue = AppFontPreference.DEFAULT,
        )

    val previewFontFamily =
        remember(fontPreference) {
            when (fontPreference) {
                AppFontPreference.CUSTOM -> fontFamilyFor(AppFontPreference.DEFAULT)
                else -> fontFamilyFor(fontPreference)
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(
            heightFraction = 0.55f,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Scaffold(
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.font_preference)) },
                    navigationIcon = {
                        IconButton(
                            onClick = navController::navigateUp,
                            onLongClick = navController::backToMain,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_back),
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
            },
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
            ) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.font_selection_preview).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.font_selection_preview_quote),
                            fontFamily = previewFontFamily,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Text(
                            text = stringResource(R.string.font_selection_preview_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }

                PreferenceGroup(title = stringResource(R.string.font_preference)) {
                    fontOptions.forEach { option ->
                        item {
                            FontOptionRow(
                                title = stringResource(option.titleRes),
                                description = stringResource(option.descriptionRes),
                                fontFamily = option.previewFamily,
                                selected = fontPreference == option.preference,
                                onSelect = { onFontPreferenceChange(option.preference) },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

private data class FontOption(
    val preference: AppFontPreference,
    val titleRes: Int,
    val descriptionRes: Int,
    val previewFamily: FontFamily,
)

private val fontOptions =
    listOf(
        FontOption(
            preference = AppFontPreference.DEFAULT,
            titleRes = R.string.font_preference_default,
            descriptionRes = R.string.font_option_default_desc,
            previewFamily = fontFamilyFor(AppFontPreference.DEFAULT),
        ),
        FontOption(
            preference = AppFontPreference.SYSTEM,
            titleRes = R.string.font_preference_system,
            descriptionRes = R.string.font_option_system_desc,
            previewFamily = FontFamily.Default,
        ),
        FontOption(
            preference = AppFontPreference.OUTFIT,
            titleRes = R.string.font_option_outfit,
            descriptionRes = R.string.font_option_outfit_desc,
            previewFamily = OutfitFontFamily,
        ),
        FontOption(
            preference = AppFontPreference.PLUS_JAKARTA,
            titleRes = R.string.font_option_plus_jakarta,
            descriptionRes = R.string.font_option_plus_jakarta_desc,
            previewFamily = PlusJakartaSansFontFamily,
        ),
        FontOption(
            preference = AppFontPreference.SANS_FLEX,
            titleRes = R.string.font_option_sans_flex,
            descriptionRes = R.string.font_option_sans_flex_desc,
            previewFamily = SansFlexFontFamily,
        ),
        FontOption(
            preference = AppFontPreference.GOOGLE_SANS,
            titleRes = R.string.font_option_google_sans,
            descriptionRes = R.string.font_option_google_sans_desc,
            previewFamily = GoogleSansFontFamily,
        ),
    )

@Composable
private fun FontOptionRow(
    title: String,
    description: String,
    fontFamily: FontFamily,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    PreferenceEntry(
        title = {
            Text(
                text = title,
                fontFamily = fontFamily,
            )
        },
        description = description,
        onClick = onSelect,
        trailingContent = {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
        },
    )
}
