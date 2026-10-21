/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.focusGroup
import app.hush.music.ui.component.tvDpadScroll
import app.hush.music.ui.component.rememberTvDevice
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import app.hush.music.BuildConfig
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.theme.LocalAppSkin
import app.hush.music.ui.theme.SkinRow
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.Updater

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    latestVersionName: String,
    onClearUpdateBadge: () -> Unit = {},
) {
    val context = LocalContext.current
    val isAndroid12OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val hasAndroidAuto =
        remember {
            try {
                context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isTvDevice = rememberTvDevice()
    val tvFirstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isTvDevice) {
        if (isTvDevice) {
            tvFirstItemFocusRequester.requestFocus()
        }
    }

    val storagePermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    val notificationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    var isStorageGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    var isNotificationGranted by remember {
        mutableStateOf(
            notificationPermission == null ||
                ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            isStorageGranted = result[storagePermission] == true || isStorageGranted
            if (notificationPermission != null) {
                isNotificationGranted = result[notificationPermission] == true || isNotificationGranted
            }
        }

    val shouldShowPermissionHint = !isStorageGranted || !isNotificationGranted
    val hasUpdate =
        BuildConfig.UPDATER_AVAILABLE &&
            Updater.isUpdateAvailable(latestVersionName, BuildConfig.VERSION_NAME)
    var isUpdateDismissed by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val settingsGroups = buildSettingsGroups(navController, isAndroid12OrLater, hasAndroidAuto, hasUpdate, context)
    val searchableSettings = getAllSearchableSettings()
    val baseSettingsItems =
        remember(settingsGroups) {
            settingsGroups.flatMap { it.items }
        }
    val settingsItems =
        if (searchQuery.isBlank()) {
            baseSettingsItems
        } else {
            val searchLower = searchQuery.trim().lowercase()
            val matchedTopLevel =
                baseSettingsItems.filter {
                    it.title.lowercase().contains(searchLower) ||
                        it.subtitle.orEmpty().lowercase().contains(searchLower)
                }
            val matchedSubSettings =
                searchableSettings
                    .filter { it.label.lowercase().contains(searchLower) }
                    .groupBy { it.sectionTitle }
                    .map { (sectionTitle, entries) ->
                        val route = entries.first().route
                        SettingsItem(
                            key = "search:${sectionTitle}:${route}",
                            icon = painterResource(R.drawable.search),
                            title = sectionTitle,
                            subtitle =
                                stringResource(
                                    R.string.settings_search_matches,
                                    entries.size,
                                ),
                            accentColor = MaterialTheme.colorScheme.primary,
                            onClick = { navController.navigate(route) },
                        )
                    }
            (matchedTopLevel + matchedSubSettings).distinctBy { it.key }
        }
    val isSearching = searchQuery.isNotBlank()

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
                windowInsets = TopAppBarDefaults.windowInsets,
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val rowStyle = LocalAppSkin.current.spec.row
        LazyColumn(
            state = listState,
            // How far apart rows sit is part of the skin, not a constant: the segments were drawn
            // 2dp apart because they are one group, and a skin that makes each row a card of its own
            // has to say so here or the cards fuse into a slab.
            verticalArrangement =
                Arrangement.spacedBy(
                    when (rowStyle) {
                        // Rows told apart by a divider are one list and sit flush.
                        SkinRow.PLAIN -> 0.dp
                        // SimpMusic draws one card per group; Hush's list has no groups, so the card
                        // is per row and this is the gap that keeps two of them from fusing - at its
                        // own 20dp corner a wider gap than one group's internal hairline.
                        SkinRow.CARD -> 10.dp
                        // Metrolist's own 4dp between its cards: the parent's list is a stack of
                        // cards that nearly touch, and the corners are what separate them.
                        SkinRow.M3_CARD_TILE -> 4.dp
                        SkinRow.ICON_CIRCLE -> 2.dp
                    },
                ),
            modifier =
                Modifier
                    .fillMaxSize()
                    .tvDpadScroll(listState, scope)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
            contentPadding =
                PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = SettingsDimensions.ScreenBottomPadding,
                ),
        ) {
            if (hasUpdate && !isUpdateDismissed) {
                item(key = "update", contentType = "settings_banner") {
                    SettingsUpdateBanner(
                        latestVersion = latestVersionName,
                        onClick = { navController.navigate("settings/update") },
                        onDismiss = { isUpdateDismissed = true },
                        modifier =
                            Modifier
                                .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                                .padding(bottom = SettingsDimensions.SectionSpacing),
                    )
                }
            }

            if (shouldShowPermissionHint) {
                item(key = "permission", contentType = "settings_banner") {
                    SettingsPermissionBanner(
                        onRequestPermission = {
                            val toRequest =
                                buildList {
                                    if (!isStorageGranted) add(storagePermission)
                                    if (!isNotificationGranted && notificationPermission != null) {
                                        add(notificationPermission)
                                    }
                                }
                            if (toRequest.isNotEmpty()) {
                                permissionLauncher.launch(toRequest.toTypedArray())
                            }
                        },
                        modifier =
                            Modifier
                                .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                                .padding(bottom = SettingsDimensions.SectionSpacing),
                    )
                }
            }

            item(key = "search", contentType = "settings_search") {
                SettingsSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier =
                        Modifier
                            .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                            .padding(top = 8.dp, bottom = 12.dp),
                )
            }

            if (settingsItems.isEmpty() && isSearching) {
                item(key = "search_empty", contentType = "settings_empty") {
                    Text(
                        text = stringResource(R.string.settings_search_no_results, searchQuery),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding, vertical = 32.dp),
                    )
                }
            }

            itemsIndexed(
                items = settingsItems,
                key = { _, item -> item.key },
                contentType = { _, _ -> "settings_segment" },
            ) { index, settingsItem ->
                SettingsSegmentedItem(
                    item = settingsItem,
                    index = index,
                    count = settingsItems.size,
                    modifier = Modifier
                        .padding(
                            horizontal =
                                when (rowStyle) {
                                    SkinRow.PLAIN -> 20.dp
                                    // A card is inset from the window's edge by the gap between two
                                    // cards rather than by a list margin: at 26dp Metrolist's cards
                                    // were one screen's width narrower than Metrolist's.
                                    SkinRow.M3_CARD_TILE -> 16.dp
                                    else -> 26.dp
                                },
                        )
                        .then(if (index == 0) Modifier.focusRequester(tvFirstItemFocusRequester) else Modifier),
                )
            }
        }
    }
    }
}
