/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hush.music.R
import app.hush.music.ui.screens.Screens
import app.hush.music.ui.theme.HushDesign
import app.hush.music.ui.theme.rememberHushAccentGradient

@Composable
fun FloatingNavigationToolbar(
    items: List<Screens>,
    pureBlack: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onShuffleClick: (() -> Unit)? = null,
    shuffleIconRes: Int? = null,
    shuffleContentDescription: String = "",
    onMusicRecognitionClick: (() -> Unit)? = null,
    musicRecognitionContentDescription: String = "",
    onMusicTogetherClick: (() -> Unit)? = null,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
    onSearchItemDoubleClick: (() -> Unit)? = null,
) {
    val toolbarContainerColor = floatingToolbarContainerColor(pureBlack = pureBlack)
    val toolbarColors =
        FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = toolbarContainerColor,
        )
    val hasOverflowAction =
        (onShuffleClick != null && shuffleIconRes != null) ||
            onMusicRecognitionClick != null ||
            onMusicTogetherClick != null

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val showSelectedLabels = maxWidth >= 360.dp

        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier.widthIn(max = 520.dp),
            colors = toolbarColors,
        ) {
            ToolbarItemsContainer(
                items = items,
                pureBlack = pureBlack,
                compact = compact,
                showSelectedLabels = showSelectedLabels,
                isSelected = isSelected,
                onItemClick = onItemClick,
                onSearchItemDoubleClick = onSearchItemDoubleClick,
                overflowContent =
                    if (hasOverflowAction) {
                        {
                            FloatingToolbarOverflowInlineAction(
                                pureBlack = pureBlack,
                                compact = compact,
                                onShuffleClick = onShuffleClick,
                                shuffleIconRes = shuffleIconRes,
                                shuffleContentDescription = shuffleContentDescription,
                                onMusicRecognitionClick = onMusicRecognitionClick,
                                musicRecognitionContentDescription = musicRecognitionContentDescription,
                                onMusicTogetherClick = onMusicTogetherClick,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun ToolbarItemsContainer(
    items: List<Screens>,
    pureBlack: Boolean,
    compact: Boolean,
    showSelectedLabels: Boolean,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
    onSearchItemDoubleClick: (() -> Unit)?,
    overflowContent: (@Composable () -> Unit)?,
) {
    val density = LocalDensity.current
    val itemWidths = remember { mutableStateMapOf<Screens, Dp>() }
    val itemPositions = remember { mutableStateMapOf<Screens, Dp>() }
    val accentGradient = rememberHushAccentGradient()

    val activeScreen = items.find { isSelected(it) }
    val targetWidth = itemWidths[activeScreen] ?: 0.dp
    val targetPosition = itemPositions[activeScreen] ?: 0.dp

    val slidingPillWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "pillWidth",
    )

    val slidingPillOffset by animateDpAsState(
        targetValue = targetPosition,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "pillOffset",
    )

    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            if (targetWidth > 0.dp) {
                Box(
                    modifier =
                        Modifier
                            .offset(x = slidingPillOffset)
                            .width(slidingPillWidth)
                            .fillMaxHeight()
                            .clip(HushDesign.navItemShape)
                            .background(accentGradient),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                items.forEach { screen ->
                    val selected = isSelected(screen)
                    val onClick =
                        remember(screen, selected, onItemClick) {
                            { onItemClick(screen, selected) }
                        }
                    val onDoubleClick =
                        remember(screen, onSearchItemDoubleClick) {
                            if (screen == Screens.Search) {
                                onSearchItemDoubleClick
                            } else {
                                null
                            }
                        }
                    FloatingNavigationToolbarItem(
                        screen = screen,
                        selected = selected,
                        showSelectedLabel = showSelectedLabels,
                        pureBlack = pureBlack,
                        compact = compact,
                        onClick = onClick,
                        onDoubleClick = onDoubleClick,
                        modifier =
                            Modifier.onGloballyPositioned { coordinates ->
                                itemWidths[screen] = with(density) { coordinates.size.width.toDp() }
                                itemPositions[screen] = with(density) { coordinates.positionInParent().x.toDp() }
                            },
                    )
                }
            }
        }

        if (overflowContent != null) {
            Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
            overflowContent()
        }
    }
}

@Composable
private fun FloatingToolbarOverflowInlineAction(
    pureBlack: Boolean,
    compact: Boolean,
    onShuffleClick: (() -> Unit)?,
    shuffleIconRes: Int?,
    shuffleContentDescription: String,
    onMusicRecognitionClick: (() -> Unit)?,
    musicRecognitionContentDescription: String,
    onMusicTogetherClick: (() -> Unit)?,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) HushDesign.NavPressScale else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "overflowPressScale",
    )
    val horizontalPadding = if (compact) 10.dp else 12.dp
    val verticalPadding = if (compact) 8.dp else 12.dp

    Box {
        Row(
            modifier =
                Modifier
                    .scale(pressScale)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = { menuExpanded = !menuExpanded },
                    ).padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.more_horiz),
                contentDescription = stringResource(R.string.more),
                tint = floatingToolbarItemContentColor(pureBlack),
            )
        }

        FloatingToolbarOverflowMenu(
            expanded = menuExpanded,
            pureBlack = pureBlack,
            onDismissRequest = { menuExpanded = false },
            onMusicRecognitionClick = onMusicRecognitionClick,
            musicRecognitionContentDescription = musicRecognitionContentDescription,
            onMusicTogetherClick = onMusicTogetherClick,
            onShuffleClick = onShuffleClick,
            shuffleIconRes = shuffleIconRes,
            shuffleContentDescription = shuffleContentDescription,
        )
    }
}

@Composable
private fun FloatingToolbarOverflowMenu(
    expanded: Boolean,
    pureBlack: Boolean,
    onDismissRequest: () -> Unit,
    onMusicRecognitionClick: (() -> Unit)?,
    musicRecognitionContentDescription: String,
    onMusicTogetherClick: (() -> Unit)?,
    onShuffleClick: (() -> Unit)?,
    shuffleIconRes: Int?,
    shuffleContentDescription: String,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(24.dp),
        containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        if (onMusicRecognitionClick != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.music_recognition)) },
                onClick = {
                    onDismissRequest()
                    onMusicRecognitionClick.invoke()
                },
                leadingIcon = {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = floatingToolbarMenuIconContainerColor(pureBlack = pureBlack),
                        contentColor = floatingToolbarMenuIconContentColor(pureBlack = pureBlack),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.mic),
                                contentDescription =
                                    musicRecognitionContentDescription.ifEmpty {
                                        stringResource(R.string.music_recognition)
                                    },
                            )
                        }
                    }
                },
                colors = overflowMenuItemColors(pureBlack),
            )
        }

        if (onMusicTogetherClick != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.music_together)) },
                onClick = {
                    onDismissRequest()
                    onMusicTogetherClick.invoke()
                },
                leadingIcon = {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = floatingToolbarMenuIconContainerColor(pureBlack = pureBlack),
                        contentColor = floatingToolbarMenuIconContentColor(pureBlack = pureBlack),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.multi_user),
                                contentDescription = null,
                            )
                        }
                    }
                },
                colors = overflowMenuItemColors(pureBlack),
            )
        }

        if (onShuffleClick != null && shuffleIconRes != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shuffle)) },
                onClick = {
                    onDismissRequest()
                    onShuffleClick()
                },
                leadingIcon = {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = floatingToolbarMenuIconContainerColor(pureBlack = pureBlack),
                        contentColor = floatingToolbarMenuIconContentColor(pureBlack = pureBlack),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(shuffleIconRes),
                                contentDescription =
                                    shuffleContentDescription.ifEmpty {
                                        stringResource(R.string.shuffle)
                                    },
                            )
                        }
                    }
                },
                colors = overflowMenuItemColors(pureBlack),
            )
        }
    }
}

@Composable
private fun overflowMenuItemColors(pureBlack: Boolean) =
    MenuDefaults.itemColors(
        textColor = if (pureBlack) Color.White else MaterialTheme.colorScheme.onSurface,
        leadingIconColor = if (pureBlack) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant,
        disabledTextColor =
            if (pureBlack) {
                Color.White.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        disabledLeadingIconColor =
            if (pureBlack) {
                Color.White.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
    )

@Composable
private fun FloatingNavigationToolbarItem(
    screen: Screens,
    selected: Boolean,
    showSelectedLabel: Boolean,
    pureBlack: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = HushDesign.navItemShape
    val showLabel = selected && showSelectedLabel
    val transition = updateTransition(targetState = selected, label = "navItem_${screen.route}")

    val contentColor by transition.animateColor(
        transitionSpec = { spring(stiffness = Spring.StiffnessMedium) },
        label = "contentColor",
    ) { isSelected ->
        if (isSelected) {
            if (pureBlack) Color.White else MaterialTheme.colorScheme.onPrimary
        } else {
            floatingToolbarItemContentColor(pureBlack)
        }
    }

    val iconScale by transition.animateFloat(
        transitionSpec = {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "iconScale",
    ) { isSelected -> if (isSelected) 1.12f else 1.0f }

    val horizontalPadding by transition.animateDp(
        transitionSpec = {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "horizontalPadding",
    ) { isSelected ->
        when {
            isSelected && showSelectedLabel -> if (compact) 12.dp else 16.dp
            compact -> 10.dp
            else -> 12.dp
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) HushDesign.NavPressScale else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "pressScale",
    )

    Row(
        modifier =
            modifier
                .scale(pressScale)
                .clip(shape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Tab,
                    onClick = onClick,
                    onDoubleClick = onDoubleClick,
                ).widthIn(min = if (compact) 44.dp else 48.dp)
                .padding(horizontal = horizontalPadding, vertical = if (compact) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(
            targetState = selected,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "iconCrossfade",
            modifier = Modifier.scale(iconScale),
        ) { isSelected ->
            Icon(
                painter = painterResource(if (isSelected) screen.iconIdActive else screen.iconIdInactive),
                contentDescription = stringResource(screen.titleId),
                tint = contentColor,
            )
        }

        AnimatedVisibility(
            visible = showLabel,
            enter =
                fadeIn(
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) +
                    expandHorizontally(
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        expandFrom = Alignment.Start,
                    ),
            exit =
                fadeOut(
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) +
                    shrinkHorizontally(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        shrinkTowards = Alignment.Start,
                    ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.size(if (compact) 6.dp else 8.dp))
                Text(
                    text = stringResource(screen.titleId),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun floatingToolbarContainerColor(pureBlack: Boolean): Color =
    if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer

@Composable
private fun floatingToolbarSelectedItemContainerColor(pureBlack: Boolean): Color =
    if (pureBlack) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.secondaryContainer

@Composable
private fun floatingToolbarSelectedItemContentColor(pureBlack: Boolean): Color =
    if (pureBlack) Color.White else MaterialTheme.colorScheme.onSecondaryContainer

@Composable
private fun floatingToolbarItemContentColor(pureBlack: Boolean): Color =
    if (pureBlack) {
        Color.White.copy(alpha = 0.82f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun floatingToolbarMenuIconContainerColor(pureBlack: Boolean): Color =
    if (pureBlack) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.secondaryContainer

@Composable
private fun floatingToolbarMenuIconContentColor(pureBlack: Boolean): Color =
    if (pureBlack) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
