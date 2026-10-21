#!/usr/bin/env python3
# Hush (2026) — GPL-3.0. Contributors: see git history.
#
# Measure the landscape provenance strip's span from a still capture and assert it lines up with
# the action row above it.
#
# The strip is the "codec • bitrate • source (delivery) • codec from this session's resolve" pill
# that Aura draws directly under the action buttons, sized to exactly the width those buttons span. That "exactly" is the
# whole point of the row, and it is also the one thing nothing else checks: the width is not a
# constant, it arrives at runtime from a `LaunchedEffect` in the button row (`onContentWidthChange`)
# once the row knows how many buttons it is about to draw. Change the count, or the spacing, or let
# a split-screen column squeeze the row, and the pill and the buttons drift apart silently - the
# pill is a footnote, and nobody reads a footnote to find a layout bug.
#
# A capture is the only place the two spans can be compared as *drawn*, but a naive comparison does
# not survive the backdrop. The row sits on Aura's accent wash: a gradient that is bright on the
# left of the controls column and dark on the right, so neither "darker than 0.x" nor "brighter
# than 0.x" is a rule that holds across the pill. What does hold, and what this tool leans on, is
# that both the strip and the buttons draw a **rim** - a hairline translucent-white outline (1dp,
# ~30% white on the strip, 1dp ~18% on the buttons) - and a rim is a *local* maximum in its own
# row: brighter than the pixels a dp to its left and to its right, whichever way the wash slopes.
# So every rule below is per-pixel and relative; none of them is a global brightness level.
#
#   1. **Find the pill by its fill.** A row of the pill is a row where a long span of columns is
#      markedly darker than those same columns one *preset-height* above it. Above the pill is the
#      action row - bright discs on a bright wash - and inside it is the 0.55-black scrim, so the
#      ratio is ~0.45 there and ~1.0 across the rest of the column. The span is read across the
#      line's own text: the text is bright, so the row is dark, stroke, dark, and the stretches are
#      joined across anything no wider than a glyph stroke (see [strip_band]). The span also has to
#      be *interior*: it must not touch the scan window's edges. That rule is what keeps the equally
#      dark sheet *below* the pill out of the answer - the sheet is dark from edge to edge, so its
#      span has no end inside the window and is thrown away.
#   2. **Read the pill's span off its rim.** The band step 1 found is the pill's *fill*. The rim
#      sits up to a dp outside that, and the rounded corners pull the rim inward on the band's
#      first and last rows, so the span is the union over the pill's own rows of each row's
#      outermost local maxima: the row through the pill's middle reaches the true ends and every
#      other row is a subset of it. Scanning stops within a few dp of the fill, so a bright artwork
#      edge to the left can never be mistaken for the pill's left end.
#   3. **Find the buttons the same way.** The action row is the band of rows roughly one preset
#      height above the pill's fill, and the union over those rows of the same
#      outermost-local-maxima rule is the first and last button's rim. A union rather than a single
#      row is what makes this independent of which row happens to be the discs' widest; and because
#      the transport above and the title further up are both narrower and centred, neither can
#      widen the union past the action row's own ends.
#
# Step 1 is the reason this file exists next to the design change it verifies: at the portrait
# weights (0.34 black fill, 0.5dp rim) the landscape pill degraded to ~0.72 of its backdrop and the
# rule below found nothing at all, while the buttons - on a brighter wash - still showed their rims.
# The strip's landscape glass now reads at ~0.45, which is the margin this tool needs.
#
# usage:
#   aura-strip-align.py --shot SHOT.png
#   aura-strip-align.py --shot SHOT.raw --size 3216x1440
#   aura-strip-align.py --shot SHOT.png --density 4 --tolerance-dp 1.0
#
#   --shot          a landscape capture: a PNG, or screencap's headerless .raw with --size
#   --size          WxHpx, required for .raw captures
#   --density       device pixels per dp of the capture; default 4 (this project's 480dpi device at
#                   its 640 density override)
#   --tolerance-dp  how far the two spans may disagree; default 1.0
#   --scrim-ratio   how much darker than its backdrop a column has to be to count as the pill's
#                   fill; default 0.62
#   --margin        how much brighter than its neighbours a pixel has to be to count as a rim, out
#                   of 255; default 12
#   --x0 / --x1     the scan window in px. --x1 defaults to 99% of the width, and --x0 to 56% of
#                   it - right of the artwork column, where the controls column's content lives.
#                   That 56% is a *guess*, and when --strip-y has pinned the pill's rows the tool
#                   searches instead of guessing: a run found hard against the window's left edge is
#                   re-read in a wider window (44%, then 34% of the width), because a run cut by the
#                   window is thrown away as "dark all the way across" and a pill that is thrown
#                   away is a pill this tool has no number for. Which window did the work is printed
#                   with the verdict. Passing --x0 explicitly turns the search off: a caller who
#                   says where to look has said it. Measured at an 804x360dp window in the
#                   full-width layout (no rail), the pill ran 1625-2857px (50.5%..89%) against a
#                   default window starting at 1801px, and at 450dp: --x0 640 reads a pill of
#                   718-1714px against an action row of 716-1715px, ALIGNED to 0.50dp
#   --strip-y       y1,y2px of the pill's rows, when they are already known (the player's
#                   accessibility tree names the row's text). Pins the search off the artwork, and
#                   is what lets --x0 be widened when the guess was too narrow (see --x0: the same
#                   pinning is what makes a wider window than the artwork column safe, because the
#                   searched rows are the pill's own)
#   --label         name used in the output; default "provenance strip"
#
# Exit code: 0 when the two spans agree within --tolerance-dp, 1 when they do not - including when
# the strip's glass could not be found at all, which is the same assertion failing for a different
# reason (a fill that sank into the wash is exactly the state this tool was written against).
#
# Requires Pillow. Everything else is core Python: the harness runs on a stock checkout.

import argparse
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment, not logic
    sys.exit("aura-strip-align: Pillow is required (pip3 install pillow)")

# The most a corner can pull a fill run in from the pill's own end, in dp: the pill's corner radius.
# The app draws it as RoundedCornerShape(12.dp), and the rim search below widens from 3dp up to this
# before it accepts that a rim pinned to the window's edge is the row running past the fill rather
# than the window being too narrow. It is also why the action row's window (15dp) is safe: the corner
# is the only thing that can hide a rim, and it cannot hide more than this.
CORNER_DP = 12.0

# How much of a row's span has to be darker than the backdrop for it to be the pill's fill rather
# than a bright row with a few stray dark pixels in it. The pill's scrim reads at ~0.2 of its
# backdrop and its own text is what makes up the rest of the span, so the floor is well clear of
# both: at 0.7 a row of text over the wash cannot qualify, and a row through the pill's glyphs does
# with room to spare.
DARK_SHARE = 0.7


def load(path, size=None):
    """A capture as (luma bytes, (width, height)). One byte per pixel, row-major.

    `size` is needed for screencap's headerless raw output, and is the PNG's own size otherwise.
    """
    if path.lower().endswith(".raw"):
        if not size:
            sys.exit("aura-strip-align: raw captures need the frame size (pass --size WxH)")
        width, height = size
        with open(path, "rb") as handle:
            data = handle.read()
        # `screencap` writes an RGBA buffer, and on some builds a 16-byte header in front of it.
        # Detected rather than assumed, because getting it wrong shifts every pixel by four.
        stride = width * height * 4
        if len(data) == stride + 16:
            data = data[16:]
        elif len(data) != stride:
            sys.exit(
                f"aura-strip-align: {path} is {len(data)} bytes, expected {stride} "
                f"(or {stride + 16}): is the frame size right?"
            )
        image = Image.frombytes("RGBA", (width, height), data).convert("RGB")
    else:
        image = Image.open(path).convert("RGB")
    # Luma rather than a sum over RGB: every rule here is relative, so the exact weighting does not
    # matter and one byte per pixel is what keeps a 4.6-megapixel frame scannable in pure Python.
    return image.convert("L").tobytes(), image.size


def strip_band(buf, width, height, x0, x1, offset, ratio, min_run, gap=0, strip_y=None):
    """The rows of the pill's fill, plus where that fill starts and ends.

    A row belongs to the pill when a long, *interior* run of its columns is darker than the same
    columns `offset` px higher up - interior meaning air at both ends, so a run that reaches the
    window's edge is not the pill but whatever is dark all the way across it (see the rule in the
    per-row loop). `strip_y` narrows the rows searched to the pill's own, which is how a caller who
    already knows where the row is keeps the search off the artwork.

    `gap` is the length of bright interruption the run may be read *across*, and it exists because
    the pill is not blank: the text it exists to show sits inside it, so a row through the text is
    dark, bright stroke, dark, ... and the longest unbroken stretch measures the space between two
    words rather than the pill. Measured at a 560x300dp window, default font scale: the pill's own
    220dp of scrim came back as a 104dp stretch at its longest - the glyphs of `96 kbps • 3 MB •
    SpotiFLAC` interrupt it - and the window was reported as having no pill at all while the pill was
    drawn and readable in the capture. So a second pass joins the stretches across a bright
    interruption no wider than `gap` - the widest a glyph stroke can be at this density, far
    narrower than the wash the pill ends in - and the joined span has to be long, interior, and dark
    for most of its length. Joining cannot let the sheet below the pill in: that is dark to the
    window's edge, so it still has no air at one end, which is what the rule always said.

    The default is 0, which is the strict rule: a span must be dark without a break. The caller
    asks for the joining pass only when the strict one has nothing usable to offer, so every window
    that already measured keeps the number it had.
    Returns (top, bottom, run_start, run_end) or None.
    """
    hits = []
    y_from, y_to = (strip_y if strip_y else (int(height * 0.35), height - offset))
    for y in range(y_from, min(y_to, height - offset)):
        base = (y - offset) * width
        row = y * width
        dark = 0
        start = best_start = None
        best = 0
        last_dark = None
        # The longest span with air at *both* ends, joined across a bright interruption of at most
        # [gap] px (none at all, on the strict pass). A span that reaches a window edge is not the
        # pill: it belongs to something dark all the way across, which is the sheet under the pill
        # when the window is a narrow one and the *artwork* - or the wash left of the controls
        # column - when the window was widened past it. Either way the pill can be the next span
        # along in the same row, so the row is not given up over one bad span: measured at a 600x360dp
        # window, where the widened window started left of the controls column and the longest span in
        # every row was the artwork's, this reported the whole size as unmeasurable with the pill
        # drawn and readable in the capture. Only spans with air at both ends are considered at all,
        # so the rule that keeps the sheet out of the measurement is unchanged.
        for x in range(x0, x1 + 1):
            is_dark = x < x1 and buf[base + x] > 10 and buf[row + x] < ratio * buf[base + x]
            if is_dark:
                if start is not None and x - last_dark - 1 > gap:
                    # The dark stopped for longer than a stroke: the span is over, so it is judged
                    # before this pixel opens a new one.
                    span = last_dark - start + 1
                    if span > best and start > x0 and last_dark < x1 and dark >= DARK_SHARE * span:
                        best, best_start = span, start
                    start = None
                if start is None:
                    start = x
                dark += 1
                last_dark = x
                continue
            if start is not None and x - last_dark - 1 > gap:
                # The span is judged the moment the brightness lasts longer than [gap] - at the very
                # first bright pixel on the strict pass, which is where the unbroken rule ended it.
                span = last_dark - start + 1
                if span > best and start > x0 and last_dark < x1 and dark >= DARK_SHARE * span:
                    best, best_start = span, start
                start = None
        if start is not None:
            span = last_dark - start + 1
            if span > best and start > x0 and last_dark < x1 and dark >= DARK_SHARE * span:
                best, best_start = span, start
        if best_start is None or best < min_run:
            continue
        hits.append((y, best_start, best_start + best, best))
    if not hits:
        return None

    hits.sort(key=lambda hit: hit[3], reverse=True)
    peak = hits[0][3]
    keep = sorted(hit[0] for hit in hits if hit[3] >= 0.5 * peak)
    inside = set(keep)
    top = bottom = hits[0][0]
    while top - 1 in inside:
        top -= 1
    while bottom + 1 in inside:
        bottom += 1
    # The widest fill run is the deepest row of the band, which is the least pulled in by the pill's
    # rounded corners - so it is the best estimate of where the fill itself begins and ends.
    widest = max((hit for hit in hits if top <= hit[0] <= bottom), key=lambda hit: hit[3])
    return top, bottom, widest[1], widest[2]


def row_span(buf, width, y, xa, xb, reach, margin):
    """Where a row's outermost local maxima are - the rim's two ends, whatever is between them."""
    row = y * width
    left = right = None
    for x in range(xa, xb):
        value = buf[row + x]
        if value > buf[row + x - reach] + margin and value > buf[row + x + reach] + margin:
            if left is None:
                left = x
            right = x
    return left, right


def union_span(buf, width, rows, xa, xb, reach, margin):
    """The union of every row's local maxima - the widest a row's rim reaches over the band."""
    left = right = None
    for y in rows:
        row_left, row_right = row_span(buf, width, y, xa, xb, reach, margin)
        if row_left is None:
            continue
        left = row_left if left is None else min(left, row_left)
        right = row_right if right is None else max(right, row_right)
    return left, right


def measure(buf, width, height, args):
    """The strip's span and the action row's span, in px, or a reason neither could be read."""
    x1 = args.x1 if args.x1 is not None else round(width * 0.99)
    density = args.density
    reach = max(3, round(1.5 * density))
    # The guess, and the search that replaces it when the caller pinned the rows with --strip-y and
    # left --x0 alone. Whichever window finds the pill first wins, so the tightest one that works is
    # the one that is reported and nothing that already measured moves by a pixel. See --x0 in the
    # usage note: 56% of the width is right of the pill's left end only while the player is drawn
    # *beside* a navigation rail. Full-width - a window on a screen whose route has no rail, a
    # folded or desktop window - the controls column centres in the whole width and the pill begins
    # at ~51%, so the 56% window cuts its left end off and the run, reaching the window's edge, is
    # discarded as "dark all the way across". Measured at an 804x360dp window in that layout: the
    # pill ran 1625-2857px (50.5%..89%) against a window starting at 1801px, and the size came back
    # unmeasurable. Widening is safe precisely because the rows are pinned: the artwork is searched
    # only where the pill's own text is, and the pill is the only glass in those rows.
    default_x0 = round(width * 0.56)
    x0 = args.x0 if args.x0 is not None else default_x0
    windows = [x0]
    if args.x0 is None and args.strip_y is not None:
        windows += [w for w in (round(width * 0.44), round(width * 0.34)) if w < x0]
    # The windows are tried in order, and the first band the pill could actually *be* wins, so the
    # shape floors are asked here rather than after the search: a fragment - a band one row tall, or
    # one no wider than a word, or one cut by the window's own edge - is not an answer, and it sends
    # the search to the wider window instead of ending it. Measured at a 560x300dp window, default
    # font scale: at 56% of the width the pill's left end sits 13dp to the left of the window, so
    # every row through the pill was read as cut and the band came back a single row - and a rule
    # that took the first band whose fill began more than 15dp right of the window stopped there and
    # reported a pill that was drawn and aligned in the capture as unmeasurable.
    min_band_px = round(2 * density)
    min_fill_dp = 150
    def usable(candidate, found):
        """Whether a band is the pill's fill rather than a fragment of it.

        Three ways of not being it: too thin to be a band (a scrim that sank into the wash leaves
        stray rows), too narrow to be this pill (a word rather than the glass around it), or starting
        hard against the window's left edge - which means the window cut the fill, and a wider one
        may hold the whole pill.
        """
        top, bottom, run_start, run_end = found
        return (
            bottom - top >= min_band_px
            and (run_end - run_start) / density >= min_fill_dp
            and run_start - candidate > round(15 * density)
        )

    def find(gap):
        """The first window whose band the pill could be, or (None, None)."""
        for candidate in windows:
            found = strip_band(
                buf,
                width,
                height,
                candidate,
                x1,
                round(18 * density),
                args.scrim_ratio,
                round(100 * density),
                gap,
                args.strip_y,
            )
            if found is not None and usable(candidate, found):
                return candidate, found
        return None, None

    # The strict fill rule, over every window, before the pass that reads across the pill's own
    # text - and that order is the whole reason the joining cannot move a pill that was already
    # measured. A window whose *fragment* the joining pass can dress up as a band (measured at a
    # 450x360dp window: the strict pass had only a cut stub at 44% of the width, while joining read
    # a confident-looking 974-1710px band out of the pill's right two thirds and the rim search then
    # began 15dp inside the pill's own left end) must not be reached before the wider window the
    # strict rule would have gone to. So the joining pass is a fallback for a window the strict rule
    # cannot read at all, which is what it was written for, and nothing else. See [strip_band].
    x0, band = find(0)
    if band is None:
        found_x0, found = find(round(2 * density))
        if found is not None:
            x0, band = found_x0, found
    if band is None:
        return None
    top, bottom, run_start, run_end = band

    # The rim lies at most a dp outside the fill *on the row it is found on* - but the fill is read
    # at one row (the band's deepest, which is where the longest run is) and the rim is taken over
    # many. Rows nearer the pill's cap are pulled in by its rounded corner, by up to the corner
    # radius, so a window of 3dp around the fill is not wide enough there: it clamps the rim at the
    # window's edge instead of reading it, and reports the clamp as misalignment. Measured on device
    # at a 1.5x font scale: a 23dp-tall pill measured 1206px against its own 1215px row - 1.25dp of
    # disagreement, symmetric, that was the window and not the app, and that vanished (1864..3079px
    # against a 1864..3079px row) as soon as the window was allowed to reach the rim.
    #
    # So the window starts at 3dp and widens only when the rim it found is pinned to the window's
    # edge, and never past the corner radius - which is the most a rounded corner can pull a fill
    # in, and stays far short of the artwork column's bright edge (25dp left of the pill at the
    # narrowest window measured). A caller that pinned `--strip-y` also gets its rows scanned in
    # full, because the pill's own fill band is only as tall as the text inside it: 20dp below that
    # band is partway up the cap of a tall pill, which is the same narrowing by another route.
    if args.strip_y is not None:
        strip_rows = range(max(0, args.strip_y[0]), min(height, args.strip_y[1]))
    else:
        strip_rows = range(max(0, top - round(2 * density)), min(height, top + round(20 * density)))
    strip = None
    for corner_dp in (3, 5, 8, CORNER_DP):
        pad = round(corner_dp * density)
        xa = max(x0, run_start - pad)
        xb = min(x1, run_end + pad)
        strip = union_span(buf, width, strip_rows, xa, xb, reach, args.margin)
        if strip[0] is None or (strip[0] > xa and strip[1] < xb):
            break
    if strip[0] is None or (strip[1] - strip[0]) / density < 150:
        return None

    # The action row sits a gap (4dp) plus its own height above the pill; 60dp up is past its top
    # even at the roomiest preset and still short of the seek row. Only the union matters, so the
    # few rows of transport this may clip are harmless: they are narrower and centred.
    #
    # Horizontally the scan is held to the pill's own neighbourhood, because the controls column's
    # right edge - where the artwork's accent glow steps down into the wash - is itself a bright
    # edge one column to the left, and a scan that reached it would report the *artwork* as the
    # row's left end (measured on device at a 450dp window: a step at 1807 against a row beginning
    # at 1893). The window is wide enough (15dp a side) that a row ending anywhere within the
    # tolerance is still found on its own; an end pinned to the window's edge means the buttons run
    # further than the pill, which is itself the misalignment, so it is reported as one.
    pad = round(15 * density)
    ax0 = max(x0, run_start - pad)
    ax1 = min(x1, run_end + pad)
    action_rows = range(max(0, top - round(60 * density)), max(0, top - round(6 * density)))
    action = union_span(buf, width, action_rows, ax0, ax1, reach, args.margin)
    return {
        "strip": strip,
        "action": action,
        "band": (top, bottom),
        "fill": (run_start, run_end),
        "window": (ax0, ax1),
        "clipped": (
            action[0] is not None and (action[0] <= ax0 or action[1] >= ax1)
        ),
        "x0": x0,
        "x1": x1,
    }


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--shot", required=True)
    parser.add_argument("--size")
    parser.add_argument("--density", type=float, default=4.0)
    parser.add_argument("--tolerance-dp", type=float, default=1.0)
    parser.add_argument("--scrim-ratio", type=float, default=0.62)
    parser.add_argument("--margin", type=int, default=12)
    parser.add_argument("--x0", type=int)
    parser.add_argument("--x1", type=int)
    parser.add_argument("--strip-y", help="y1,y2px of the pill's own rows, when they are known")
    parser.add_argument("--label", default="provenance strip")
    args = parser.parse_args()
    if args.strip_y:
        try:
            args.strip_y = tuple(
                int(part) for part in args.strip_y.replace(" ", "").split(",")
            )
        except ValueError:
            sys.exit("aura-strip-align: --strip-y wants y1,y2")
        if len(args.strip_y) != 2:
            sys.exit("aura-strip-align: --strip-y wants y1,y2")

    size = None
    if args.size:
        try:
            width, height = (int(part) for part in args.size.lower().replace(" ", "").split("x"))
        except ValueError:
            sys.exit("aura-strip-align: --size wants WxH, e.g. 3216x1440")
        size = (width, height)

    buf, (width, height) = load(args.shot, size)
    if width <= height:
        sys.exit(
            f"aura-strip-align: {args.shot} is {width}x{height} - this measures the landscape "
            "player, so the capture has to be wider than it is tall"
        )

    result = measure(buf, width, height, args)
    if result is None:
        print(
            f"{args.label}: the glass could not be measured in {args.shot.split('/')[-1]} - no "
            f"interior run of fill at or below {args.scrim_ratio:.2f}x its backdrop was long "
            "enough to be the pill. Either the strip was not drawn (a track with no resolved "
            "format row) or its scrim has sunk into the wash again."
        )
        return 1

    density = args.density
    strip_left, strip_right = result["strip"]
    action_left, action_right = result["action"]
    if action_left is None:
        print(
            f"{args.label}: found the pill ({strip_left}-{strip_right}px) but no action row above "
            "it - no button rim stood out from the wash, so there is nothing to align against."
        )
        return 1
    if result["clipped"]:
        window = result["window"]
        print(
            f"{args.label}: MISALIGNED - a button rim reaches the edge of the {window[0]}-"
            f"{window[1]}px window the row was searched in, so the row runs further than the pill "
            "does. The pill is supposed to end where the buttons do."
        )
        return 1

    left_dp = (strip_left - action_left) / density
    right_dp = (strip_right - action_right) / density
    width_dp = (strip_right - strip_left) / density
    print(
        f"{args.label}: pill rim {strip_left}-{strip_right}px "
        f"({strip_left / density:.1f}-{strip_right / density:.1f}dp, {width_dp:.1f}dp wide), "
        f"fill band y{result['band'][0]}-{result['band'][1]}"
    )
    print(
        f"{args.label}: action row {action_left}-{action_right}px "
        f"({action_left / density:.1f}-{action_right / density:.1f}dp, "
        f"{(action_right - action_left) / density:.1f}dp wide)"
    )
    print(
        f"{args.label}: ends differ by {left_dp:+.2f}dp and {right_dp:+.2f}dp "
        f"(tolerance {args.tolerance_dp:.2f}dp)"
    )

    if abs(left_dp) <= args.tolerance_dp and abs(right_dp) <= args.tolerance_dp:
        print(
            f"{args.label}: ALIGNED - the pill spans the first and last button's rim to within "
            f"{max(abs(left_dp), abs(right_dp)):.2f}dp"
        )
        return 0

    worst = "left" if abs(left_dp) > abs(right_dp) else "right"
    print(
        f"{args.label}: MISALIGNED - the pill's {worst} end is {abs(left_dp) if worst == 'left' else abs(right_dp):.2f}dp "
        f"off the button row's, past the {args.tolerance_dp:.2f}dp tolerance"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
