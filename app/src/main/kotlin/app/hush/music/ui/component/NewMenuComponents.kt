/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hush.music.constants.EnableHapticFeedbackKey
import app.hush.music.utils.rememberPreference

private val MenuActionButtonShape = RoundedCornerShape(12.dp)

private data class MenuLayoutMetrics(
    val actionMinHeight: Dp,
    val actionIconBoxSize: Dp,
    val actionContentPadding: PaddingValues,
    val actionTextStyle: androidx.compose.ui.text.TextStyle,
    val gridSpacing: Dp,
    val sectionOuterPadding: Dp,
    val sectionInnerPadding: PaddingValues,
    val headerVerticalPadding: Dp,
    val listItemVerticalPadding: Dp,
    val sheetHorizontalPadding: Dp,
    val containerBottomPadding: Dp,
)

@Composable
private fun rememberMenuLayoutMetrics(): MenuLayoutMetrics {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val compact = screenWidth < 600
    val spacious = screenWidth >= 840
    val typography = MaterialTheme.typography

    return remember(compact, spacious, typography) {
        when {
            spacious ->
                MenuLayoutMetrics(
                    actionMinHeight = 96.dp,
                    actionIconBoxSize = 28.dp,
                    actionContentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                    actionTextStyle = typography.labelLarge,
                    gridSpacing = 12.dp,
                    sectionOuterPadding = 6.dp,
                    sectionInnerPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    headerVerticalPadding = 12.dp,
                    listItemVerticalPadding = 4.dp,
                    sheetHorizontalPadding = 20.dp,
                    containerBottomPadding = 32.dp,
                )

            compact ->
                MenuLayoutMetrics(
                    actionMinHeight = 80.dp,
                    actionIconBoxSize = 24.dp,
                    actionContentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    actionTextStyle = typography.labelMedium,
                    gridSpacing = 8.dp,
                    sectionOuterPadding = 4.dp,
                    sectionInnerPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    headerVerticalPadding = 8.dp,
                    listItemVerticalPadding = 0.dp,
                    sheetHorizontalPadding = 16.dp,
                    containerBottomPadding = 20.dp,
                )

            else ->
                MenuLayoutMetrics(
                    actionMinHeight = 88.dp,
                    actionIconBoxSize = 26.dp,
                    actionContentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    actionTextStyle = typography.labelLarge,
                    gridSpacing = 10.dp,
                    sectionOuterPadding = 5.dp,
                    sectionInnerPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    headerVerticalPadding = 10.dp,
                    listItemVerticalPadding = 2.dp,
                    sheetHorizontalPadding = 18.dp,
                    containerBottomPadding = 28.dp,
                )
        }
    }
}

/** Standard icon size for grid actions inside bottom-sheet menus. */
@Composable
fun menuActionIconSize(): Dp = rememberMenuLayoutMetrics().actionIconBoxSize

@Composable
fun rememberMenuSheetHorizontalPadding(): Dp = rememberMenuLayoutMetrics().sheetHorizontalPadding

@Composable
fun NewActionButton(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val metrics = rememberMenuLayoutMetrics()
    val containerColor = if (backgroundColor.isSpecified) backgroundColor else MaterialTheme.colorScheme.surfaceContainerHigh
    val actionContentColor = if (contentColor.isSpecified) contentColor else MaterialTheme.colorScheme.onSurfaceVariant
    val haptic = LocalHapticFeedback.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

    FilledTonalButton(
        onClick = {
            if (enableHapticFeedback) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            onClick()
        },
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = metrics.actionMinHeight),
        enabled = enabled,
        shape = MenuActionButtonShape,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = containerColor,
                contentColor = actionContentColor,
            ),
        contentPadding = metrics.actionContentPadding,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(metrics.actionIconBoxSize),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }

            Text(
                text = text,
                style = metrics.actionTextStyle,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}

@Composable
fun NewMenuItem(
    headlineContent: @Composable () -> Unit,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberMenuLayoutMetrics()
    val content: @Composable () -> Unit = {
        ListItem(
            headlineContent = headlineContent,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            modifier =
                Modifier.padding(
                    horizontal = 4.dp,
                    vertical = metrics.listItemVerticalPadding,
                ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            tonalElevation = 0.dp,
        )
    }

    if (onClick == null) {
        Box(modifier = modifier.fillMaxWidth()) {
            content()
        }
    } else {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
        ) {
            content()
        }
    }
}

@Composable
fun NewMenuSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberMenuLayoutMetrics()
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier.padding(
                horizontal = metrics.sheetHorizontalPadding,
                vertical = metrics.headerVerticalPadding,
            ),
    )
}

@Composable
fun NewActionGrid(
    actions: List<NewAction>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    if (actions.isEmpty()) return

    val metrics = rememberMenuLayoutMetrics()
    val columnCount = columns.coerceAtLeast(1)
    val rows = actions.chunked(columnCount)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
            ) {
                row.forEach { action ->
                    NewActionButton(
                        icon = action.icon,
                        text = action.text,
                        onClick = action.onClick,
                        modifier = Modifier.weight(1f),
                        enabled = action.enabled,
                        backgroundColor = action.backgroundColor,
                        contentColor = action.contentColor,
                    )
                }

                repeat(columnCount - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

data class NewAction(
    val icon: @Composable () -> Unit,
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val backgroundColor: Color = Color.Unspecified,
    val contentColor: Color = Color.Unspecified,
)

@Composable
fun NewMenuContent(
    headerContent: @Composable (() -> Unit)? = null,
    actionGrid: @Composable (() -> Unit)? = null,
    menuItems: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberMenuLayoutMetrics()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
    ) {
        headerContent?.invoke()
        actionGrid?.invoke()

        if (actionGrid != null && menuItems != null) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = metrics.headerVerticalPadding),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        menuItems?.invoke()
    }
}

@Composable
fun NewIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val containerColor = if (backgroundColor.isSpecified) backgroundColor else MaterialTheme.colorScheme.surfaceContainerHigh
    val iconContentColor = if (contentColor.isSpecified) contentColor else MaterialTheme.colorScheme.onSurfaceVariant

    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = containerColor,
                contentColor = iconContentColor,
            ),
    ) {
        icon()
    }
}

@Composable
fun NewMenuContainer(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberMenuLayoutMetrics()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.sheetHorizontalPadding)
                .padding(bottom = metrics.containerBottomPadding),
    ) {
        content()
    }
}

@Composable
fun MenuSurfaceSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val metrics = rememberMenuLayoutMetrics()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(vertical = metrics.sectionOuterPadding),
    ) {
        Column(
            modifier = Modifier.padding(metrics.sectionInnerPadding),
            content = content,
        )
    }
}
