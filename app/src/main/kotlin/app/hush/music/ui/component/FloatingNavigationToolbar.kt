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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import app.hush.music.ui.theme.LocalAppSkin
import app.hush.music.ui.theme.SkinNav
import app.hush.music.ui.theme.SplitCapsuleHeight
import app.hush.music.ui.theme.rememberHushAccentGradient
import app.hush.music.ui.theme.skinHeaderHostsActions

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
    // A Material skin's bar is not a pill over the content at all - it is Material's own
    // `NavigationBar`: the width of the window, every destination named, Material's own indicator
    // behind the selected one, its own height and its own insets. That is the difference a Metrolist
    // screen shows at the bottom of every screen, and it is a different component rather than the
    // same one dressed differently - which is also why it is not a branch inside this toolbar's
    // item code: the two share the destinations and nothing else.
    val skinSpec = LocalAppSkin.current.spec
    val navStyle = skinSpec.nav
    val materialBar = navStyle == SkinNav.MATERIAL_BAR
    val splitCapsule = navStyle == SkinNav.SPLIT_CAPSULE
    // A bar that is its parent's bar holds its parent's contents as well: Metrolist, Vivi and
    // SimpMusic all keep recognition, listening together and shuffle out of the bar, so a skin on
    // one of their bars passes no actions here and the header draws them instead.
    val actionsInBar = !skinHeaderHostsActions(LocalAppSkin.current)
    val barShuffleClick = if (actionsInBar) onShuffleClick else null
    val barShuffleIconRes = if (actionsInBar) shuffleIconRes else null
    val barShuffleDescription = if (actionsInBar) shuffleContentDescription else ""
    val barRecognitionClick = if (actionsInBar) onMusicRecognitionClick else null
    val barRecognitionDescription = if (actionsInBar) musicRecognitionContentDescription else ""
    val barTogetherClick = if (actionsInBar) onMusicTogetherClick else null
    val toolbarContainerColor = floatingToolbarContainerColor(pureBlack = pureBlack)
    val toolbarColors =
        FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = toolbarContainerColor,
        )
    val hasOverflowAction =
        (barShuffleClick != null && barShuffleIconRes != null) ||
            barRecognitionClick != null ||
            barTogetherClick != null

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // A bar with a pill around one destination can afford to name only the selected one; a bar
        // that is the width of the screen has the room to name them all, and a strip of four
        // unlabelled icons is what an app looks like when it has no room for a dock.
        val showSelectedLabels = maxWidth >= 360.dp

        val bar: @Composable () -> Unit = {
            HorizontalFloatingToolbar(
                expanded = true,
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
                                    onShuffleClick = barShuffleClick,
                                    shuffleIconRes = barShuffleIconRes,
                                    shuffleContentDescription = barShuffleDescription,
                                    onMusicRecognitionClick = barRecognitionClick,
                                    musicRecognitionContentDescription = barRecognitionDescription,
                                    onMusicTogetherClick = barTogetherClick,
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }

        if (materialBar) {
            MaterialNavigationBar(
                items = items,
                pureBlack = pureBlack,
                isSelected = isSelected,
                onItemClick = onItemClick,
            )
        } else if (splitCapsule) {
            SplitCapsuleNavigationBar(
                items = items,
                pureBlack = pureBlack,
                isSelected = isSelected,
                onItemClick = onItemClick,
                onSearchItemDoubleClick = onSearchItemDoubleClick,
            )
        } else {
            bar()
        }
    }
}

/**
 * Material's own bar, for a skin that themes with Material 3 and nothing else.
 *
 * Everything about it is Material's: the 80dp height, the `surfaceContainer` fill, the pill behind
 * the selected destination, the labels under every item whether it is selected or not, and the
 * ripple. Metrolist draws exactly this and gets its look from the fact that it is this component -
 * a floating toolbar with Material's colours on it is still a floating toolbar, which is what the
 * earlier attempt at the skin was and why it read as Hush in a different palette.
 *
 * The bar carries the destinations and nothing else. It used to also draw the app's loose actions -
 * recognition, listening together, shuffle - as three unlabelled items after them, which is not what
 * any of the parents whose bar this is does: Metrolist's own bar has its three destinations, and
 * those actions live in its top bar. Six items in one bar also squeezed the three labels this bar
 * exists to show. The header draws them instead; see `skinHeaderHostsActions`.
 *
 * The one Hush behaviour it cannot carry is the Search destination's double tap - `NavigationBarItem`
 * exposes a single `onClick` - so a Material skin opens the search field from the first tap and the
 * long-press shortcuts stay where they are.
 */
@Composable
private fun MaterialNavigationBar(
    items: List<Screens>,
    pureBlack: Boolean,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = floatingToolbarContainerColor(pureBlack = pureBlack),
        contentColor = floatingToolbarItemContentColor(pureBlack = pureBlack),
        tonalElevation = 3.dp,
    ) {
        items.forEach { screen ->
            val selected = isSelected(screen)
            NavigationBarItem(
                selected = selected,
                onClick = { onItemClick(screen, selected) },
                icon = {
                    Icon(
                        painter =
                            painterResource(
                                if (selected) screen.iconIdActive else screen.iconIdInactive,
                            ),
                        contentDescription = stringResource(screen.titleId),
                    )
                },
                label = {
                    Text(
                        text = stringResource(screen.titleId),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/**
 * SimpMusic's bar: the destinations in a capsule, Search outside it as a circle of its own.
 *
 * The shape its screens are recognised by, and the reason it is not the pill: nothing in this bar
 * slides to the selected destination - each one holds its own rounded highlight and its label stays
 * under its icon whether it is selected or not - and Search is not in the capsule at all, it is a
 * button beside it. That last part is why this is a component of its own rather than a flag inside
 * the pill's item code: no amount of styling makes a row of destinations and a separate circle the
 * same row.
 *
 * The capsule is a 56dp `surfaceContainer` with a one-dp outline and a 6dp lift, and the circle is
 * 48dp of the same surface, which is what the app's own screenshot measures rather than what a
 * constant in its source says - it publishes none for either. Both float 12dp above the window's
 * bottom inset; see `NavigationBarAnatomy.SplitCapsule`, which is what the screens behind this bar
 * reserve.
 */
@Composable
private fun SplitCapsuleNavigationBar(
    items: List<Screens>,
    pureBlack: Boolean,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
    onSearchItemDoubleClick: (() -> Unit)?,
) {
    val containerColor = floatingToolbarContainerColor(pureBlack = pureBlack)
    val outlineColor =
        if (pureBlack) {
            Color(0xFF222222)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        }
    // Search is not a destination in this bar: it is the circle outside it. A build with no search
    // screen to navigate to comes back to the capsule unharmed rather than to an empty circle.
    val capsuleItems = items.filter { it != Screens.Search }
    val searchItem = items.firstOrNull { it == Screens.Search }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .height(SplitCapsuleHeight)
                    .shadow(elevation = 6.dp, shape = CircleShape)
                    .background(containerColor, shape = CircleShape)
                    .border(width = 1.dp, color = outlineColor, shape = CircleShape)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            capsuleItems.forEach { screen ->
                CapsuleNavItem(
                    screen = screen,
                    selected = isSelected(screen),
                    pureBlack = pureBlack,
                    onClick = { onItemClick(screen, isSelected(screen)) },
                )
            }
        }
        if (searchItem != null) {
            Spacer(Modifier.width(16.dp))
            Box(
                modifier =
                    Modifier
                        .size(SplitCapsuleSearchSize)
                        .shadow(elevation = 6.dp, shape = CircleShape)
                        .background(containerColor, shape = CircleShape)
                        .border(width = 1.dp, color = outlineColor, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CapsuleNavItem(
                    screen = searchItem,
                    selected = isSelected(searchItem),
                    pureBlack = pureBlack,
                    showsLabel = false,
                    onSearchDoubleClick = onSearchItemDoubleClick,
                    onClick = { onItemClick(searchItem, isSelected(searchItem)) },
                )
            }
        }
    }
}

/** 48dp is what the app's own Search circle measures; the capsule beside it is 56dp tall. */
private val SplitCapsuleSearchSize = 48.dp

/**
 * One destination inside SimpMusic's capsule: the icon over its own label, in a highlight that is
 * drawn for the selected one and transparent for the rest.
 *
 * The label is always there rather than only under the selected destination, because that is what
 * the capsule is: two named places, both of them readable at a glance, rather than an icon row that
 * names whichever one you are already on.
 */
@Composable
private fun CapsuleNavItem(
    screen: Screens,
    selected: Boolean,
    pureBlack: Boolean,
    onClick: () -> Unit,
    showsLabel: Boolean = true,
    onSearchDoubleClick: (() -> Unit)? = null,
) {
    // The selected destination is the palette's own accent, not a filled pill around a plain icon:
    // in this bar the highlight is a tint and the colour is what says which place you are on.
    val contentColor =
        if (selected) {
            if (pureBlack) Color.White else MaterialTheme.colorScheme.primary
        } else {
            floatingToolbarItemContentColor(pureBlack)
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
        label = "capsulePressScale",
    )

    Box(
        modifier =
            Modifier
                .scale(pressScale)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                    shape = CircleShape,
                ).combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Tab,
                    onClick = onClick,
                    onDoubleClick = onSearchDoubleClick,
                ).padding(
                    horizontal = if (showsLabel) 14.dp else 12.dp,
                    vertical = if (showsLabel) 6.dp else 12.dp,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (selected) screen.iconIdActive else screen.iconIdInactive,
                    ),
                contentDescription = stringResource(screen.titleId),
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            if (showsLabel) {
                Text(
                    text = stringResource(screen.titleId),
                    color = contentColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/**
 * The highlight that slides between destinations, and the only place its animation is read.
 *
 * The two animated values live here rather than in [ToolbarItemsContainer] because of where a state
 * read is allowed to happen. `animateDpAsState` hands back a `State`, and reading it in composition
 * subscribes *that* composable's scope to it - so while the spring ran, every frame invalidated the
 * container and re-ran the composition of every item in the bar, for a highlight whose own shape
 * never changes. Hoisting the reads down here means a frame of the slide costs a recomposition of
 * one `Box` with no children, and the container only recomposes when the *targets* move, which is a
 * handful of times per tab change instead of sixty a second.
 *
 * The targets are passed as plain `Dp`s and not as the animating state, which is what keeps the
 * container out of the per-frame path; passing the state itself would move the read back up.
 *
 * The offset stays on the non-lambda overload on purpose, so lint's `UseOfNonLambdaOffsetOverload`
 * is still reported here. Moving it to the lambda form would not remove this composable's
 * per-frame recomposition, because the width beside it has no lambda overload and is read in the
 * same scope - the recomposition has already happened by the time the offset would be deferred.
 * The cost that mattered was *which* scope paid it, and that is what the hoist fixed.
 */
@Composable
private fun SlidingNavigationPill(
    targetOffset: Dp,
    targetWidth: Dp,
    accentGradient: Brush,
) {
    val slidingPillWidth by
        animateDpAsState(
            targetValue = targetWidth,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "pillWidth",
        )

    val slidingPillOffset by
        animateDpAsState(
            targetValue = targetOffset,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "pillOffset",
        )

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

    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            if (targetWidth > 0.dp) {
                SlidingNavigationPill(
                    targetOffset = targetPosition,
                    targetWidth = targetWidth,
                    accentGradient = accentGradient,
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

        SkinActionsMenu(
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

/**
 * The app's loose actions - recognition, listening together, shuffle - as one menu.
 *
 * Shared by the two places they can be opened from, because it is one list and not two: Hush's pill
 * opens it from the ellipsis beside its destinations, and a skin on a parent's bar opens the same
 * menu from the header, which is where those parents keep the actions that do not fit their bar. A
 * second copy of these three items is how the two would come to offer different things.
 *
 * Items that a screen does not have (shuffle off the home screen, or either of them with the bars
 * hidden) are simply absent rather than disabled: the menu is a list of things that can be done
 * here, and a greyed row is not one of them.
 */
@Composable
fun SkinActionsMenu(
    expanded: Boolean,
    pureBlack: Boolean,
    onDismissRequest: () -> Unit,
    onMusicRecognitionClick: (() -> Unit)?,
    musicRecognitionContentDescription: String,
    onMusicTogetherClick: (() -> Unit)?,
    onShuffleClick: (() -> Unit)?,
    shuffleIconRes: Int?,
    shuffleContentDescription: String,
    /**
     * New releases, for the one header with no room to keep it in the row - SimpMusic's, which names
     * the app over two lines. Null everywhere the button itself is on screen, which is the pill and
     * every other skin's header.
     */
    onNewReleaseClick: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(24.dp),
        containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        if (onNewReleaseClick != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.new_release_albums)) },
                onClick = {
                    onDismissRequest()
                    onNewReleaseClick()
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
                                painter = painterResource(R.drawable.new_release),
                                contentDescription = null,
                            )
                        }
                    }
                },
                colors = overflowMenuItemColors(pureBlack),
            )
        }

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
