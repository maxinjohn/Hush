/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import app.hush.music.constants.CompactFloatingToolbarBottomPadding
import app.hush.music.constants.CompactFloatingToolbarHeight
import app.hush.music.constants.FloatingToolbarBottomPadding
import app.hush.music.constants.FloatingToolbarHeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A look for the whole app, not for one screen.
 *
 * Hush already had a theme in two senses and neither was this. The colour scheme, the font and the
 * dark/light choice flow through [HushTheme], so they already reach every screen - but they are
 * *tokens*, and two of the things that make an app recognisable are not tokens at all: the corner
 * radii are constants in [HushDesign] that a hundred call sites read directly, and the anatomy of a
 * list row, a settings row and the navigation bar is written into the components that draw them.
 * The player has had its own designs ([app.hush.music.constants.PlayerDesignStyle]) for a while, which
 * is why "Aura" reads as a player theme rather than an app theme: it is one screen's layout, not a
 * decision about the app.
 *
 * A skin is the decision about the app. It is deliberately *not* a copy of another app's screens -
 * three forks of Home, Settings and Library would be three times the surface to keep working, and
 * every one of them would have to be re-taught each time a row gains a badge, a sheet gains a
 * gesture, or a queue row gains a prefetch ring. Instead a skin is a small set of choices that the
 * *shared* components read: what the palette is allowed to do, which type scale is in force, how
 * round things are, and - as the components are converted - how a row, a card and the navigation
 * bar are built. One layout that adapts, rather than N layouts that drift.
 *
 * Two rules keep this from being a trap:
 *
 *  - [HUSH] is the shipped look and changes nothing. Every choice in its spec is the value the app
 *    already had, so an install that never opens this setting renders exactly as before.
 *  - A skin never rewrites a preference the user set. It cannot turn off blur, turn on dynamic
 *    colour or move a slider, because those are the user's answers to separate questions; it can
 *    only decide what *its own* surfaces do with them.
 */
enum class AppSkin(
    /** Stable id, stored in preferences; never the ordinal. */
    val id: String,
    val label: String,
    val blurb: String,
) {
    HUSH(
        id = "hush",
        label = "Hush",
        blurb = "The shipped look - your palette, the Hush type scale, soft corners.",
    ),
    BITCHORD(
        id = "bitchord",
        label = "BitChord",
        blurb = "Monochrome and typographic: near-black surfaces, heavier tighter type, small radii.",
    ),
    METROLIST(
        id = "metrolist",
        label = "Metrolist",
        blurb = "Material and roomy: baseline type, large radii, carded rows, your palette.",
    ),
    VIVI(
        id = "vivi",
        label = "Vivi Music",
        blurb = "Outfit type, a Material navigation bar, and a capsule mini player over your palette.",
    ),
    SIMPMUSIC(
        id = "simpmusic",
        label = "SimpMusic",
        blurb = "Poppins and loud titles: small body text, grouped cards, a floating capsule bar.",
    ),
    ;

    /**
     * What this skin changes.
     *
     * Written out per skin rather than derived from the colour scheme, because the two are
     * independent: a user can be on BitChord's monochrome look and still want Hush's type, and the
     * reverse. Nothing here reads a preference.
     */
    val spec: SkinSpec
        get() =
            when (this) {
                HUSH ->
                    SkinSpec(
                        radiusScale = 1f,
                        typography = SkinTypography.BRAND,
                        color = SkinColor.USER_PALETTE,
                        surface = SkinSurface.STANDARD,
                        nav = SkinNav.FLOATING,
                        row = SkinRow.ICON_CIRCLE,
                        header = SkinHeader.BRAND,
                        headerMark = true,
                        expressiveMotion = true,
                    )

                // BitChord is a black-and-white app with SF Pro Display and a pill bottom bar: white
                // text on near-black, one red accent kept for isolated badges, and no colour
                // extracted from artwork behind the chrome. Its rows are plain - a label, a value,
                // a chevron - and its bar is a floating pill whose tabs are drawn flat against it,
                // which is most of why it reads as a different app at a glance.
                BITCHORD ->
                    SkinSpec(
                        radiusScale = 0.6f,
                        typography = SkinTypography.SF_PRO,
                        color = SkinColor.MONOCHROME,
                        surface = SkinSurface.SOLID,
                        // A floating pill, like Hush's and like every other parent's here: the
                        // difference a BitChord screen shows at the bottom is the monochrome
                        // container and the small radii, not a different kind of bar.
                        nav = SkinNav.FLOATING,
                        row = SkinRow.PLAIN,
                        miniPlayer = SkinMiniPlayer.COMPACT_PILL,
                        // Its top bar is the app's own mark at rest - `FrostedTopBar` draws the logo
                        // and leaves the title to the feed - and its settings list is the only place
                        // a row's anatomy differs, so the header stays the shipped one.
                        header = SkinHeader.BRAND,
                        headerMark = true,
                        expressiveMotion = false,
                    )

                // Metrolist's identity is Material 3 at its most literal: the baseline type scale,
                // generous radii, and content on filled cards rather than full-bleed rows. Its
                // accent is left to the user's palette and the system's dynamic colour, because
                // that is what the app itself does - there is no brand hue to copy. The carded
                // treatment is `row`, not `surface`: what makes a row a card is the row's own
                // anatomy - its corners, its container and the gap around it - and `surface` has no
                // say in that, so naming it twice would be two places to change one decision.
                // Metrolist themes with Material 3 and nothing else: the platform font, Material's
                // own scale (`Theme.kt` passes no typography at all, so every style is the default),
                // a real Material navigation bar rather than a floating pill, and settings rows that
                // are each their own card with a small tinted icon tile - 20dp/16dp padding, a 40dp
                // tile at 12dp radius on primary at 10%, 4dp between rows.
                METROLIST ->
                    SkinSpec(
                        // 24dp group corners against Hush's 32dp, and a 12dp icon tile.
                        radiusScale = 0.75f,
                        typography = SkinTypography.MATERIAL,
                        color = SkinColor.USER_PALETTE,
                        surface = SkinSurface.STANDARD,
                        nav = SkinNav.MATERIAL_BAR,
                        row = SkinRow.M3_CARD_TILE,
                        miniPlayer = SkinMiniPlayer.MATERIAL_CAPSULE,
                        // Metrolist's own bar: the screen's name where Hush puts the mark and the
                        // wordmark, and no mark at all - its four actions are plain icon buttons,
                        // with no rounded container behind them.
                        header = SkinHeader.SCREEN_TITLE,
                        headerMark = false,
                        expressiveMotion = true,
                    )

                // Vivi Music is Outfit at almost Material's proportions - uniform normal weights, and
                // the same 64dp capsule mini player Metrolist draws with the artwork's progress as a
                // ring around the cover. Its bar is the Material one, so the two skins differ in the
                // type and the surfaces rather than in the anatomy.
                VIVI ->
                    SkinSpec(
                        radiusScale = 1f,
                        typography = SkinTypography.OUTFIT,
                        color = SkinColor.USER_PALETTE,
                        surface = SkinSurface.STANDARD,
                        nav = SkinNav.MATERIAL_BAR,
                        row = SkinRow.ICON_CIRCLE,
                        miniPlayer = SkinMiniPlayer.MATERIAL_CAPSULE,
                        // Vivi leads its bar with the app's own icon and then the screen's name; the
                        // rest of the bar is Metrolist's, which is why the two differ by a mark.
                        header = SkinHeader.SCREEN_TITLE,
                        headerMark = true,
                        expressiveMotion = true,
                    )

                // SimpMusic is the opposite bet on type from Hush: Poppins with a small body and
                // titles at 25sp Bold, settings in groups on one card rather than one card per row,
                // and the shortest mini player of the four - 56dp with a 26dp cover inside an 8dp
                // ring, so the progress is the ring and the pill is nearly all chrome.
                SIMPMUSIC ->
                    SkinSpec(
                        // A 20dp group card where Hush's card is 32dp: 32 x 0.7 is 22.4dp, which is
                        // the nearest a single scale can put every radius to SimpMusic's own.
                        radiusScale = 0.7f,
                        typography = SkinTypography.POPPINS,
                        color = SkinColor.USER_PALETTE,
                        surface = SkinSurface.STANDARD,
                        // Its own bar, not the pill with other numbers: a capsule of the destinations
                        // with the search separate beside it. See [SkinNav.SPLIT_CAPSULE].
                        nav = SkinNav.SPLIT_CAPSULE,
                        // A card per row rather than one card per group. SimpMusic groups consecutive
                        // rows on a shared card, and the settings list Hush draws is flat - it has no
                        // group to hand a skin, and inventing one would be a grouping rule this list
                        // does not have. At 0.7 the corners land on the parent's own 20dp and the
                        // container is the same fill, so the family resemblance survives the honest
                        // version of it.
                        row = SkinRow.CARD,
                        miniPlayer = SkinMiniPlayer.COMPACT_PILL,
                        // SimpMusic's top bar is the app's name over a greeting, not the screen's
                        // name - the one of the five whose header does not follow the screen.
                        header = SkinHeader.APP_NAME,
                        headerMark = false,
                        expressiveMotion = true,
                    )
            }

    companion object {
        /** The look an install has unless it says otherwise. */
        val DEFAULT = HUSH

        /** Every skin the Appearance screen offers, in the order it offers them. */
        val selectableValues: List<AppSkin> = entries

        /**
         * A stored id, or [DEFAULT] when it is missing or from a build that no longer has it.
         *
         * Deliberately not `valueOf`: an unknown id is a downgrade problem, not a crash, and the one
         * thing that must not happen is an install that cannot start because it once ran a build
         * with a skin that has since been removed.
         */
        fun fromId(id: String?): AppSkin =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** Everything one skin decides that is not a colour. */
data class SkinSpec(
    /**
     * Multiplies every radius in [HushDesign].
     *
     * A scale rather than a table of numbers per skin, because a skin that had to name all nine
     * radii would be a requirement to re-tune it every time a component gains a shape - and the
     * ratios between the radii are the design, not the absolute numbers.
     */
    val radiusScale: Float,
    val typography: SkinTypography,
    val color: SkinColor,
    val surface: SkinSurface,
    val nav: SkinNav,
    val row: SkinRow,
    /**
     * How the top bar is built - the one surface every top-level screen begins with.
     *
     * A field rather than something [nav] implies, because the two follow each other only by
     * coincidence: Metrolist and Vivi share both a Material bar and a titled header, SimpMusic has
     * its own bar and a titled header of its own, and BitChord's pill comes with the mark and the
     * app's name above it. Named here once because the same decision explains where the app's loose
     * actions end up: see [skinHeaderHostsActions].
     */
    val header: SkinHeader,
    /** Whether the app's own mark leads the header, before whatever the header calls itself. */
    val headerMark: Boolean,
    /**
     * How the mini player - the bottom panel every screen ends in - is built.
     *
     * A field of its own rather than something the row or the bar implies, because it is the one
     * surface a user sees on *every* screen: a parent's miniature player is as much its signature as
     * its type, and two of these skins share a bar and a row anatomy while differing by 12dp of pill.
     */
    val miniPlayer: SkinMiniPlayer = SkinMiniPlayer.HUSH,
    /**
     * Whether Material's expressive motion scheme is in force. A skin with calm motion is not a
     * slower one: springs still settle in a few hundred milliseconds, they just do not overshoot.
     */
    val expressiveMotion: Boolean,
)

/**
 * Where a skin's palette comes from.
 *
 * [USER_PALETTE] means the colour scheme is built exactly as Hush builds it today - the user's
 * palette, the artwork colour when dynamic theming is on, or the system's palette on Android 12+.
 */
enum class SkinColor { USER_PALETTE, MONOCHROME }

/**
 * What a skin's surfaces are made of.
 *
 * Two values, not three: a variant is named here only once something reads it, so "carded content"
 * lives in [SkinRow.CARD] - the row that is a card - rather than also being a surface nothing would
 * honour. The distinction this does carry is whether surfaces are allowed to be tonal and tinted at
 * all, which is the one a monochrome skin answers differently.
 */
enum class SkinSurface {
    /** The shipped treatment: tonal containers, artwork-tinted where the app already does that. */
    STANDARD,

    /** Flat opaque colour, no tint and no translucency. */
    SOLID,
}

/**
 * How the top bar names the app.
 *
 * Three, because that is how many answers the parents give: Hush and BitChord put the app's own
 * mark and name up there and let the feed carry the screen's; Metrolist and Vivi put the screen's
 * name there - BitChord's own bar even fades the title in only once the feed has scrolled past it;
 * and SimpMusic puts the app's name over a greeting, the same words on every screen.
 */
enum class SkinHeader {
    /** The mark and the app's name, over rounded translucent action buttons. */
    BRAND,

    /** The name of the screen being shown, over plain round action buttons. */
    SCREEN_TITLE,

    /** The app's name over a time-of-day greeting, over plain round action buttons. */
    APP_NAME,
}

/**
 * Whether the app's loose actions - recognition, listening together, shuffle - are drawn in the top
 * bar rather than the bottom one.
 *
 * One predicate rather than a field per screen, because it is one decision with one cause: a skin
 * whose bar is its parent's bar has that bar's *contents* too, and every parent whose header is not
 * the shipped one keeps those actions in the header instead. Hush's own pill carries them inline
 * beside the destinations and its header is the brand one, which is exactly the case that keeps the
 * shipped layout shipped.
 */
fun skinHeaderHostsActions(skin: AppSkin): Boolean = skin.spec.header != SkinHeader.BRAND

/**
 * How the bottom navigation is built.
 *
 * Three values, and each one is drawn by a component that exists: a spec field that nothing honours
 * is a claim about the skin that the app does not keep, so a variant is only named here once the bar
 * can actually draw it.
 */
enum class SkinNav {
    /** The shipped bar: a floating pill, inset from the edges of the screen. */
    FLOATING,

    /**
     * Material's own `NavigationBar`: full width, 80dp, `surfaceContainer`, every destination named.
     *
     * Two parents and Hush all use a floating bar, so the interesting difference is not "pill or
     * not" - it is whether the bar is a `NavigationBar` at all. A skin that says `MATERIAL_BAR` gets
     * Material's component, insets, elevation and label behaviour with it, which is what makes
     * Metrolist look unlike Hush at the bottom of the screen even at the same height.
     */
    MATERIAL_BAR,

    /**
     * SimpMusic's bar: a capsule of the destinations with Search outside it as a circle of its own.
     *
     * Its own value rather than a flag on [FLOATING], because the two differ by more than the
     * numbers: the pill lays its destinations out in a row and puts the app's loose actions beside
     * them, where this one draws a 64dp capsule - the destinations, labelled under their icons -
     * and a separate round Search button, which is the shape SimpMusic's screens are recognised by.
     */
    SPLIT_CAPSULE,
}

/**
 * How the mini player is built.
 *
 * Three shapes for four parents, because two of them ship the same one: Metrolist and Vivi both draw
 * a 64dp capsule inset 12dp from the edges with the artwork's progress as a ring, and BitChord and
 * SimpMusic both draw a 56dp pill - one with a 40dp cover and a spinner, one with a 26dp cover in an
 * 8dp ring.
 */
enum class SkinMiniPlayer {
    /** The shipped pill: full width, artwork-tinted, the artwork's progress in a ring. */
    HUSH,

    /** 64dp, inset 12dp, a 40dp cover, a plain surface container. */
    MATERIAL_CAPSULE,

    /** 56dp, inset 12dp, a 40dp cover with a rounded-square corner, no progress ring. */
    COMPACT_PILL,
}

/**
 * How a settings or list row is built.
 *
 * The same rule as [SkinNav]: all four are drawn by `SettingsSegmentedItem`, so a theme that names
 * one is asking for something the app can already render.
 */
enum class SkinRow {
    /** The shipped row: an accent disc beside the text, grouped into segments. */
    ICON_CIRCLE,

    /** A bare accent icon beside the text, no container, rows told apart by a divider. */
    PLAIN,

    /** One filled card per row, with the gaps between them written into the list. */
    CARD,

    /**
     * Metrolist's row: a filled card, a 40dp tinted tile at 12dp radius instead of a disc, and 20dp
     * of leading padding - narrower than Hush's 26dp, which is what makes its rows read as wider.
     *
     * Separate from [CARD] because the two differ by more than a radius: this one keeps the icon
     * container and shrinks it, changes the container tint (primary at 10% against the scheme's
     * surfaceContainerLow) and tightens the padding, and those are three decisions a user would see.
     */
    M3_CARD_TILE,

}

/**
 * Which type scale is in force, and which face it was drawn for.
 *
 * The two are one decision rather than two fields, because a scale is a set of ratios *for a face*:
 * SF Pro Display at 34sp and Outfit at 34sp are not the same heading, and a skin that let a user mix
 * one app's scale with another's font would be a look no parent has. Each variant therefore names the
 * family it was measured on, and [skinFontFamily] is what the theme asks for.
 */
enum class SkinTypography(
    /** Null means "whatever font the user chose", which is what Hush's own scale is drawn on. */
    val family: FontFamily?,
) {
    /** Hush's own scale, on the user's chosen font. */
    BRAND(null),

    /**
     * BitChord's scale: SF Pro Display, heavy and tight, with the display sizes pulled in.
     *
     * Nine of its fifteen styles are stated and the rest are Material's defaults with the family
     * swapped, which is exactly what BitChord's own `BitChordTypography` does - so a style it never
     * named still wears SF Pro rather than the platform font.
     */
    SF_PRO(SfProDisplayFontFamily),

    /** Material 3's baseline scale on the platform font, which is what Metrolist themes with. */
    MATERIAL(FontFamily.Default),

    /** Vivi Music's scale: Outfit, uniform normal weights, wide tracking on the body styles. */
    OUTFIT(OutfitFontFamily),

    /** SimpMusic's scale: Poppins, larger and bolder titles, small body text. */
    POPPINS(PoppinsFontFamily),
}

/** The skin in force for the composition; [AppSkin.DEFAULT] outside a themed subtree. */
val LocalAppSkin = staticCompositionLocalOf { AppSkin.DEFAULT }

/**
 * The mini player's measurements for the skin in force.
 *
 * A composition local of its own rather than a read of `LocalAppSkin` inside the mini player, because
 * the numbers are needed in three places that do not all have a skin - the pill itself, the inset
 * arithmetic in `MainActivity`, and the sheet's dismissal bound - and because a screen that only
 * wants the height should not have to know which skin produced it.
 */
val LocalMiniPlayerAnatomy = staticCompositionLocalOf { MiniPlayerAnatomy.Hush }

/**
 * What a skin decides about the bottom panel.
 *
 * Deliberately numbers rather than a layout: the pill is one composable with one set of behaviours
 * (swipe to skip, the artwork's ring, the download ring, the track-change slide), and four parents'
 * worth of separate trees is four times the surface to keep working. What a parent's panel differs by
 * on sight is its height, how far it is inset, how big the cover is inside it, the cover's own shape
 * and whether that cover carries a ring - so those are what a skin names.
 *
 * What it deliberately does *not* name is the panel's colour. That is the user's own
 * `MiniPlayerBackgroundStyle` - theme, artwork, gradient or blur - and a skin that overrode it would
 * be answering a question the user has already answered, which is the one thing a skin may not do.
 * A parent's plain panel is reachable from here by choosing the theme style.
 */
data class MiniPlayerAnatomy(
    /** The design height. The pill still grows for a large font scale; see `rememberMiniPlayerHeight`. */
    val height: Dp,
    /** How far the pill is inset from the window's edges, per side. */
    val horizontalInset: Dp,
    /** The cover, inside its progress ring. */
    val artworkInner: Dp,
    /** The ring's outer size, which is what the panel lays out around the cover. */
    val artworkOuter: Dp,
    /**
     * The cover's corner radius, or null for a circle.
     *
     * BitChord rounds its cover 8dp while every other parent and Hush draw a circle, and a rounded
     * square against a circle is visible at 40dp from across a room.
     */
    val artworkCorner: Dp?,
    /**
     * Whether the cover carries a progress ring.
     *
     * BitChord draws a spinner in the transport slot instead, so its panel has no ring at all - and a
     * ring left drawn for it would be a Hush behaviour wearing another app's measurements.
     */
    val showsProgressRing: Boolean,
) {
    companion object {
        /**
         * The shipped pill, which is what every number in `MiniPlayerComponents` was written for:
         * the width of the window, 64dp of it, with a 38dp cover in a 48dp ring.
         */
        val Hush =
            MiniPlayerAnatomy(
                height = 64.dp,
                horizontalInset = 0.dp,
                artworkInner = 38.dp,
                artworkOuter = 48.dp,
                artworkCorner = null,
                showsProgressRing = true,
            )

        /**
         * Metrolist's and Vivi's capsule: 64dp, inset 12dp from the window, a 40dp cover in a 3dp arc
         * that carries playback and download progress.
         */
        val MaterialCapsule =
            MiniPlayerAnatomy(
                height = 64.dp,
                horizontalInset = 12.dp,
                artworkInner = 40.dp,
                artworkOuter = 48.dp,
                artworkCorner = null,
                showsProgressRing = true,
            )

        /** BitChord's and SimpMusic's pill: 56dp, inset 12dp, a 40dp cover in a 48dp slot. */
        val CompactPill =
            MiniPlayerAnatomy(
                height = 56.dp,
                horizontalInset = 12.dp,
                artworkInner = 40.dp,
                artworkOuter = 48.dp,
                // BitChord's own 8dp: a rounded square rather than a circle.
                artworkCorner = 8.dp,
                // BitChord puts the play/pause spinner in the transport slot and leaves the cover
                // bare, so a progress ring drawn for it would be Hush's behaviour wearing BitChord's
                // measurements.
                showsProgressRing = false,
            )
    }
}

/** The bottom panel's measurements for a skin. */
fun skinMiniPlayerAnatomy(skin: AppSkin): MiniPlayerAnatomy =
    when (skin.spec.miniPlayer) {
        SkinMiniPlayer.HUSH -> MiniPlayerAnatomy.Hush
        SkinMiniPlayer.MATERIAL_CAPSULE -> MiniPlayerAnatomy.MaterialCapsule
        SkinMiniPlayer.COMPACT_PILL -> MiniPlayerAnatomy.CompactPill
    }

/**
 * The bottom bar's measurements for the skin in force.
 *
 * Beside the pill's numbers, and for the same reason: several places have to leave room for the bar
 * - every scrollable screen's bottom padding, the mini player's place above it, and the bound the
 * player sheet collapses to - and none of them can see a [SkinSpec] field on its own.
 *
 * A bar's shape is not its size, which is why this is a second set of numbers rather than a field on
 * [SkinNav]: Material's `NavigationBar` is 80dp and full width and sits on the window's own bottom
 * inset, where the shipped pill is 64dp and floats 8dp above that inset. The two are not a scale
 * factor apart, and the difference is exactly what a screen has to reserve.
 */
data class NavigationBarAnatomy(
    /** The design height, which the bar still grows past for a large font scale. */
    val height: Dp,
    /** Its height while the mini player is on screen, when the skin draws a shorter bar then. */
    val compactHeight: Dp,
    /** The gap between the bar and the window's bottom edge, or between it and the pill above. */
    val bottomPadding: Dp,
    val compactBottomPadding: Dp,
    /**
     * Whether the bar already contains the window's bottom inset.
     *
     * Material's bar does: it is flush with the bottom of the window and applies the system bars'
     * inset itself, so anything that also reserved that inset for it would lift the bar's space by
     * the height of the navigation area a second time. The floating pill does not - it floats above
     * that inset and the screens behind it reserve it themselves.
     */
    val consumesSystemInset: Boolean,
) {
    companion object {
        /**
         * Material 3's own `NavigationBar` container height.
         *
         * Written as a number because this Material version publishes no constant for it - the
         * component measures 80dp including the labels, which is the whole difference from the pill.
         * `AppSkinTest` pins the two anatomies against each other, so a Material that changed its own
         * height would fail there rather than as a clipped line of text on a phone.
         */
        val MaterialBarHeight = 80.dp

        /**
         * The shipped pill, which is what every number in `MainActivity` was written for: 64dp
         * floating 8dp above the system inset, 56dp while the player is showing.
         */
        val Floating =
            NavigationBarAnatomy(
                height = FloatingToolbarHeight,
                compactHeight = CompactFloatingToolbarHeight,
                bottomPadding = FloatingToolbarBottomPadding,
                compactBottomPadding = CompactFloatingToolbarBottomPadding,
                consumesSystemInset = false,
            )

        /**
         * Material's own bar: one height whether the player shows or not, no floating gap, and the
         * window's bottom inset inside it rather than under it.
         */
        val Material =
            NavigationBarAnatomy(
                height = MaterialBarHeight,
                compactHeight = MaterialBarHeight,
                bottomPadding = 0.dp,
                compactBottomPadding = 0.dp,
                consumesSystemInset = true,
            )

        /**
         * SimpMusic's capsule and its Search circle, measured off the app's own screenshot rather
         * than off its source, which publishes no constant for either: the capsule comes out 56dp
         * tall with a 48dp circle beside it, floating 12dp above the window's bottom inset - the
         * same gap Vivi's floating bar leaves, which is why both read as suspended rather than
         * docked.
         */
        val SplitCapsule =
            NavigationBarAnatomy(
                height = SplitCapsuleHeight,
                compactHeight = SplitCapsuleHeight,
                bottomPadding = SplitCapsuleBottomPadding,
                compactBottomPadding = SplitCapsuleBottomPadding,
                consumesSystemInset = false,
            )
    }
}

/** The capsule SimpMusic's destinations sit in, and the height of the Search circle beside it. */
val SplitCapsuleHeight = 56.dp

/** How far SimpMusic's bar floats above the window's bottom edge. */
val SplitCapsuleBottomPadding = 12.dp

/** The bottom bar's measurements for a skin. */
fun skinNavigationAnatomy(skin: AppSkin): NavigationBarAnatomy =
    when (skin.spec.nav) {
        SkinNav.FLOATING -> NavigationBarAnatomy.Floating
        SkinNav.MATERIAL_BAR -> NavigationBarAnatomy.Material
        SkinNav.SPLIT_CAPSULE -> NavigationBarAnatomy.SplitCapsule
    }

/** The bottom bar's measurements for the composition; the shipped pill outside a themed subtree. */
val LocalNavigationAnatomy = staticCompositionLocalOf { NavigationBarAnatomy.Floating }

/**
 * One radius, in the skin's proportions.
 *
 * Clamped, because this multiplies values that reach layout: a spec is a compile-time constant
 * today, and the clamp is what keeps a future one from turning a card into a circle or collapsing a
 * row to a point.
 */
fun skinRadius(
    base: Dp,
    spec: SkinSpec,
): Dp = base * spec.radiusScale.coerceIn(MinRadiusScale, MaxRadiusScale)

/** The narrowest and widest a skin may throw the radii; see [skinRadius]. */
const val MinRadiusScale = 0.4f
const val MaxRadiusScale = 1.6f

/**
 * The type scale for a skin.
 *
 * [base] is the app's own scale, already built from the user's font preference, so `BRAND` costs
 * nothing and every other variant is a scale measured off a parent rather than a restyling of Hush's.
 * A scale is stated in full: Material's [Typography] is fifteen styles and a screen falls through any
 * one of them that is left open, so each variant below names the styles its parent stated and keeps
 * Material's default - with its own family - for the rest. That is what the parents themselves do.
 */
fun skinTypography(
    skin: AppSkin,
    base: Typography,
): Typography =
    when (skin.spec.typography) {
        SkinTypography.BRAND -> base
        SkinTypography.MATERIAL -> Typography()
        SkinTypography.SF_PRO -> bitChordTypography()
        SkinTypography.OUTFIT -> viviTypography()
        SkinTypography.POPPINS -> simpMusicTypography()
    }

/**
 * The family a skin draws with, or null for "the user's own font".
 *
 * The theme reads this only while the user is on the default font: a skin may say which face it is
 * designed around, and a user who has picked a font for the whole app keeps it. That is the line the
 * skins do not cross - they decide what their own surfaces look like, never which of the user's
 * answers to forget.
 */
fun skinFontFamily(skin: AppSkin): FontFamily? = skin.spec.typography.family

/**
 * BitChord's scale: SF Pro Display, nine styles stated, the rest Material's with the family swapped.
 *
 * The line heights are the ones its table implies rather than ones it states - BitChord sets sizes,
 * weights and tracking and leaves line height to Material, and a style whose size grew without its
 * line height growing would overlap itself. Where the size is unchanged, so is the line height.
 */
private fun bitChordTypography(): Typography {
    val family = SkinTypography.SF_PRO.family ?: FontFamily.Default
    val base = Typography()
    fun style(
        from: TextStyle,
        size: Float,
        weight: FontWeight,
        spacing: Float,
    ): TextStyle =
        from.copy(
            fontFamily = family,
            fontSize = size.sp,
            fontWeight = weight,
            letterSpacing = spacing.sp,
            lineHeight = (size * (from.lineHeight.value / from.fontSize.value)).sp,
        )

    return Typography(
        displayLarge = style(base.displayLarge, 34f, FontWeight.W800, -0.8f),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = style(base.headlineLarge, 30f, FontWeight.W800, -0.7f),
        headlineMedium = style(base.headlineMedium, 22f, FontWeight.W700, -0.4f),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = style(base.titleLarge, 20f, FontWeight.W700, -0.3f),
        titleMedium = style(base.titleMedium, 16f, FontWeight.W600, -0.2f),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = style(base.bodyLarge, 16f, FontWeight.W400, 0f),
        bodyMedium = style(base.bodyMedium, 14f, FontWeight.W400, 0f),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = style(base.labelMedium, 12f, FontWeight.W600, 0f),
        labelSmall = style(base.labelSmall, 11f, FontWeight.W600, 0f),
    )
}

/**
 * Vivi Music's scale, stated in full: uniform normal weights and wide tracking on the body styles.
 *
 * Nothing here is a restyling of Hush's - the sizes are Vivi's own (displayLarge is 57sp as Material
 * is, but its headlineMedium is 28sp where Hush's is Bold at the same size, and its titleLarge is
 * 22sp Normal where Hush's is SemiBold), which is why the table is written out rather than derived.
 */
private fun viviTypography(): Typography {
    val family = SkinTypography.OUTFIT.family ?: FontFamily.Default
    fun style(
        size: Float,
        lineHeight: Float,
        weight: FontWeight,
        spacing: Float,
    ): TextStyle =
        TextStyle(
            fontFamily = family,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            fontWeight = weight,
            letterSpacing = spacing.sp,
        )

    return Typography(
        displayLarge = style(57f, 64f, FontWeight.Normal, -0.25f),
        displayMedium = style(45f, 52f, FontWeight.Normal, 0f),
        displaySmall = style(36f, 44f, FontWeight.Normal, 0f),
        headlineLarge = style(32f, 40f, FontWeight.Normal, 0f),
        headlineMedium = style(28f, 36f, FontWeight.Normal, 0f),
        headlineSmall = style(24f, 32f, FontWeight.Normal, 0f),
        titleLarge = style(22f, 28f, FontWeight.Normal, 0f),
        titleMedium = style(16f, 24f, FontWeight.Medium, 0.15f),
        titleSmall = style(14f, 20f, FontWeight.Medium, 0.1f),
        bodyLarge = style(16f, 24f, FontWeight.Normal, 0.5f),
        bodyMedium = style(14f, 20f, FontWeight.Normal, 0.25f),
        bodySmall = style(12f, 16f, FontWeight.Normal, 0.4f),
        labelLarge = style(14f, 20f, FontWeight.Medium, 0.1f),
        labelMedium = style(12f, 16f, FontWeight.Medium, 0.5f),
        labelSmall = style(11f, 16f, FontWeight.Medium, 0.5f),
    )
}

/**
 * SimpMusic's scale: Poppins, a small body and loud titles - 25sp Bold where Material is 22sp
 * SemiBold, and an 11sp bodySmall against Material's 12sp.
 *
 * It states no line height and no tracking at all, so the sizes and weights are Poppins' and the
 * spacing is Material's, which is the combination its own screens are drawn with.
 */
private fun simpMusicTypography(): Typography {
    val family = SkinTypography.POPPINS.family ?: FontFamily.Default
    val base = Typography()

    fun style(
        from: TextStyle,
        size: Float,
        weight: FontWeight,
    ): TextStyle =
        from.copy(
            fontFamily = family,
            fontSize = size.sp,
            fontWeight = weight,
            lineHeight = (size * (from.lineHeight.value / from.fontSize.value)).sp,
        )

    return Typography(
        displayLarge = style(base.displayLarge, 20f, FontWeight.Normal),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = style(base.headlineLarge, 23f, FontWeight.Bold),
        headlineMedium = style(base.headlineMedium, 20f, FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = style(base.titleLarge, 25f, FontWeight.Bold),
        titleMedium = style(base.titleMedium, 18f, FontWeight.SemiBold),
        titleSmall = style(base.titleSmall, 13f, FontWeight.SemiBold),
        bodyLarge = style(base.bodyLarge, 18f, FontWeight.Normal),
        bodyMedium = style(base.bodyMedium, 13f, FontWeight.Normal),
        bodySmall = style(base.bodySmall, 11f, FontWeight.Normal),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = style(base.labelMedium, 16f, FontWeight.Bold),
        labelSmall = style(base.labelSmall, 14f, FontWeight.SemiBold),
    )
}
