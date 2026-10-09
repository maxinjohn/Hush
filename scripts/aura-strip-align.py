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
#   1. **Find the pill by its fill.** A row of the pill is a row where a long run of columns is
#      markedly darker than those same columns one *preset-height* above it. Above the pill is the
#      action row - bright discs on a bright wash - and inside it is the 0.55-black scrim, so the
#      ratio is ~0.45 there and ~1.0 across the rest of the column. The run also has to be
#      *interior*: it must not touch the scan window's edges. That rule is what keeps the equally
#      dark sheet *below* the pill out of the answer - the sheet is dark from edge to edge, so its
#      run has no end inside the window and is thrown away.
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
#   --x0 / --x1     the scan window in px, defaulting to 56%..99% of the width - right of the
#                   artwork column, where the controls column's own content lives. The pill is a
#                   fixed 250-340dp centred in that column, so the narrower the window the further
#                   left it starts, and a window under ~700dp puts its left end *left* of the 56%
#                   default - where the run touches the window edge and is thrown away as "dark all
#                   the way across". Those sizes are measured with --x0 at about 0.35x the width:
#                   at a 450dp window --x0 640 reads a pill of 718-1714px against an action row
#                   of 716-1715px, ALIGNED to 0.50dp
#   --strip-y       y1,y2px of the pill's rows, when they are already known (the player's
#                   accessibility tree names the row's text). Pins the search off the artwork, and
#                   is the only way to measure a full-width player, whose pill starts left of the
#                   default window (see --x0 too: the same pinning is what makes a wider --x0 than
#                   the artwork column safe, because the searched rows are the pill's own)
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


def strip_band(buf, width, height, x0, x1, offset, ratio, min_run, strip_y=None):
    """The rows of the pill's fill, plus where that fill starts and ends.

    A row belongs to the pill when a long, *interior* run of its columns is darker than the same
    columns `offset` px higher up. `strip_y` narrows the rows searched to the pill's own, which is
    how a caller who already knows where the row is keeps the search off the artwork.
    Returns (top, bottom, run_start, run_end) or None.
    """
    hits = []
    y_from, y_to = (strip_y if strip_y else (int(height * 0.35), height - offset))
    for y in range(y_from, min(y_to, height - offset)):
        base = (y - offset) * width
        row = y * width
        run = 0
        start = best_start = None
        best = 0
        for x in range(x0, x1):
            above = buf[base + x]
            if above > 10 and buf[row + x] < ratio * above:
                if run == 0:
                    start = x
                run += 1
            else:
                if run > best:
                    best, best_start = run, start
                run = 0
        if run > best:
            best, best_start = run, start
        # No run at all, too short to be the row, or a run that reaches a window edge and so belongs
        # to something that is dark all the way across - the sheet under the pill, say.
        if best_start is None or best < min_run:
            continue
        if best_start <= x0 or best_start + best >= x1:
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
    x0 = args.x0 if args.x0 is not None else round(width * 0.56)
    # Where the controls column starts depends on whether the player is drawn beside a navigation
    # rail or across the whole window, and in the full-width layout the pill begins at ~51% - left
    # of the default window, so the run is cut in half and thrown away as "dark all the way
    # across". `--strip-y` is the answer to that: a caller who knows which rows the pill is on (the
    # player's own accessibility tree names its text) does not need the tool to guess, and pinning
    # the rows is also what keeps a dark *artwork* region from being mistaken for the pill, whose
    # fill is only ~0.45 of its backdrop - the same contrast a dark album cover can show against
    # itself. Guessing is for the common case; --strip-y is for the rest.
    band = strip_band(
        buf,
        width,
        height,
        x0,
        x1,
        round(18 * density),
        args.scrim_ratio,
        round(100 * density),
        args.strip_y,
    )
    if band is None:
        return None
    top, bottom, run_start, run_end = band

    # A pill's fill is a band, not a line: a scrim that has sunk into the wash leaves only stray
    # single rows behind, and reading a span off one of those is how a tool reports a confident
    # number for a strip it never found. Both floors below are shape checks on the pill itself, not
    # alignment claims, so they say "not measurable" rather than "misaligned".
    if bottom - top < round(2 * density) or (run_end - run_start) / density < 150:
        return None

    # The rim lies at most a dp outside the fill, so scanning starts there and stops there: far
    # enough to catch the rim at the pill's widest row, not so far that the artwork's bright edge
    # joins in on the left.
    pad = round(3 * density)
    strip_rows = range(max(0, top - round(2 * density)), min(height, top + round(20 * density)))
    strip = union_span(buf, width, strip_rows, max(x0, run_start - pad), min(x1, run_end + pad), reach, args.margin)
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
