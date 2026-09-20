#!/usr/bin/env python3
# Hush (2026) — GPL-3.0. Contributors: see git history.
#
# Measure what a button's press actually drew, from a still capture.
#
# The thing being measured is `PressMotion`: on press a button contracts, on release it springs past
# its own size, and a halo ring expands from its centre and fades. The ring is the part a clip can
# silently eat — a `Modifier.clip()` placed in front of the press modifier cuts it off at the
# button's own edge, so the row looks static while the code that draws a ring is all still there.
#
# So the question is precisely: *did something change outside the button's bounds that could be this
# button's ring?* Three properties have to hold, and each one rules out a different impostor:
#
#   1. the change is **outside** the button's own bounds - a contraction can only move pixels within
#      them, so a clip that eats the ring shows up here as `outside = 0`;
#   2. the change is **within the ring's reach**, which is `PressMotion.HALO_END_FRACTION` (1.30)
#      times half the short side - a header re-tinting because playback started, or a list
#      reordering, changes pixels *there* rather than in the band just outside the button;
#   3. the change is **circular about the button** - the halo is a disc centred on it, so the far
#      edge of what changed is at the same radius whichever way you look. A rectangle of
#      background changing colour has a flat edge and fails this outright;
#   4. the change **stops somewhere** - a disc of radius R has nothing beyond R. If pixels changed
#      out past the furthest a ring can ever reach, the screen moved for a reason that is not this
#      button, and the cycle is thrown away rather than credited. That fourth rule is the one that
#      caught a real false positive: a press that scrolled its own list changed the whole header
#      band, which filled every sector evenly and passed the circularity test.
#
# Point 3 is what makes the difference between measuring the app and measuring the screen, and it is
# why a rest frame is taken immediately *before* each press rather than once at the start: a button
# whose activation changes the screen (a shuffle button starting playback) would otherwise be
# compared against a scene that no longer exists.
#
# usage:
#   press-ring-measure.py --rest REST.png --shot SHOT [--shot ...] --rect x1,y1,x2,y2
#   press-ring-measure.py --pair REST.png:SHOT.raw [--pair ...] --rect x1,y1,x2,y2
#   press-ring-measure.py --pair REST.png:SHOT.raw:NOISE.png [--pair ...] --rect ...
#
#   --rest / --shot  one rest frame shared by every shot
#   --pair           REST:SHOT, one pair per shot, each with its own rest frame. A third field adds
#                    the noise frame: a second rest capture, taken after the press was cancelled, and
#                    any pixel that differs between it and REST is dropped from the evidence entirely
#                    - it belongs to something that moves on its own (a marquee title, a progress
#                    bar, a list that scrolled), and crediting it to the button is how a surface with
#                    a scrolling label passes a ring test it should fail.
#   --rect           the button's bounds on screen, x1,y1,x2,y2
#   --paint          the radius (px) the button's own fill covers. Defaults to half the rect's short
#                    side, which is right when the accessibility node **is** the control - the player's
#                    play button (304x304px node, 152px paint) and the playlist header's buttons. It is
#                    wrong when the node is only a minimum touch target: the mini player's controls are
#                    40dp of paint inside a 48dp target, so half the rect is 92px against 80px of paint
#                    and the ring's whole visible life lands in that gap - reported as "give" instead of
#                    a ring. That is the one number this tool cannot recover from the picture alone,
#                    because a ring inflates the very edge it would be measured against; take it from the
#                    button's own layout (40dp at this device's density 4 = 160px, so 80px).
#   --threshold      per-pixel difference (summed over RGB) that counts as a change; default 12
#   --band           how far past the button to look, as a multiple of its short side; default 0.45
#   --max-fraction   the furthest a ring can be drawn, in halves of the short side; default 1.45
#
# Exit code: 0 when at least one shot shows a ring outside the button, 1 when none does.
#
# Requires Pillow. Everything else is core Python: the harness runs on a stock checkout.

import argparse
import math
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment, not logic
    sys.exit("press-ring-measure: Pillow is required (pip3 install pillow)")

SECTORS = 12
# How many of the twelve sectors have to hold changed pixels before the change is an arc rather than
# a blob: half the circle is plenty of evidence for a ring, and a corner of a re-tinted panel is not.
MIN_SECTORS = 6


def load(path, size=None):
    """A capture as an RGB image. `size` is needed for screencap's headerless raw output."""
    if path.lower().endswith(".raw"):
        if not size:
            sys.exit("press-ring-measure: raw captures need the frame size (pass a PNG rest frame)")
        width, height = size
        with open(path, "rb") as handle:
            data = handle.read()
        # `screencap` writes the pixel buffer, and on some builds a 16-byte header in front of it.
        # Detected rather than assumed, because getting it wrong shifts every pixel by four.
        stride = width * height * 4
        if len(data) == stride + 16:
            data = data[16:]
        elif len(data) != stride:
            sys.exit(
                f"press-ring-measure: {path} is {len(data)} bytes, expected {stride} "
                f"(or {stride + 16}): is the frame size right?"
            )
        return Image.frombytes("RGBA", (width, height), data).convert("RGB")
    return Image.open(path).convert("RGB")


def scan(rest, shot, rect, threshold, band, max_fraction, noise=None, paint=None):
    """What the shot changed, split by whether it is inside the button's paint or outside it.

    The rectangle a caller passes is the control's own bounds - its accessibility node, which for every
    button measured here is also what the fill is inscribed in, so half its short side is the paint's
    radius. That is checked rather than assumed: the contraction's outer edge is measured from the
    press itself (below), and reported beside the rectangle's half so a reader can see the two agree.
    """
    x1, y1, x2, y2 = rect
    short_side = min(x2 - x1, y2 - y1)
    if short_side <= 0:
        sys.exit("press-ring-measure: --rect must be x1,y1,x2,y2 with x2>x1 and y2>y1")
    if rest.size != shot.size:
        sys.exit(f"press-ring-measure: captures differ in size ({rest.size} vs {shot.size})")

    cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
    # What the fill covers. Half the rect is right when the accessibility node is the control itself, and
    # too big when the node is only a minimum touch target - the ring's visible life then falls inside the
    # rect and is counted as the contraction. `--paint` overrides it with the drawn radius.
    half = float(paint) if paint else short_side / 2.0
    # The ring's own unit: `PressMotion.haloRadiusFraction` runs from 0.50 (so far under the button that
    # none of it shows) to 1.30, so above 1.0 is outside the button and past the ceiling is not this
    # button's ring at all. The window ends just above 1.30, so a ring drawn to its full extent is
    # bounded by it and anything past it is honestly something else.
    reach = half * max_fraction
    search = max(reach, half * (1.0 + band))
    inside = outside = far = masked = 0
    inside_max = 0
    ring_delta = 0
    ring_radius = 0.0
    ring_at = None
    paint = 0.0
    window_pixels = 0
    sector_count = [0] * SECTORS
    sector_radius = [0.0] * SECTORS
    ox1, oy1, ox2, oy2 = int(cx - search), int(cy - search), int(cx + search) + 1, int(cy + search) + 1
    pa, pb = rest.load(), shot.load()
    pn = noise.load() if noise is not None else None
    for y in range(max(oy1, 0), min(oy2, rest.size[1])):
        for x in range(max(ox1, 0), min(ox2, rest.size[0])):
            window_pixels += 1
            a, b = pa[x, y], pb[x, y]
            delta = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
            if delta <= threshold:
                continue
            if pn is not None:
                # Anything that also differs between the two rest frames moves on its own, so this
                # pixel cannot say what the press did.
                c = pn[x, y]
                if abs(a[0] - c[0]) + abs(a[1] - c[1]) + abs(a[2] - c[2]) > threshold:
                    masked += 1
                    continue
            radius = math.hypot(x - cx, y - cy)
            # Inside the button's paint: the contraction, the squash, and any part of a ring the button's
            # own fill covers. None of it is evidence of a ring. A circle is the shape every button here
            # is drawn with (a fill inscribed in the node), and a square node's corners are inside the
            # paint too, so the same bound serves both - the corners are simply where the paint reaches
            # further than the ring's own ceiling can.
            if radius <= half:
                inside += 1
                inside_max = max(inside_max, delta)
                paint = max(paint, radius)
                continue
            if radius > reach:
                # Too far away to be this button's ring: something else on the screen changed.
                # Counted so it is visible in the output, never added to the ring's evidence.
                far += 1
                continue
            outside += 1
            ring_delta = max(ring_delta, delta)
            if radius > ring_radius:
                ring_radius = radius
                ring_at = (x, y)
            angle = math.atan2(y - cy, x - cx)
            sector = int((angle + math.pi) / (2 * math.pi) * SECTORS) % SECTORS
            sector_count[sector] += 1
            sector_radius[sector] = max(sector_radius[sector], radius)

    # The sectors that actually hold part of the change, and how much their far edges disagree. A
    # disc centred on the button agrees with itself; a straight edge does not.
    filled = [sector_radius[i] for i in range(SECTORS) if sector_count[i] >= 4]
    spread = 0.0
    if len(filled) >= 2:
        mean = sum(filled) / len(filled)
        spread = (max(filled) - min(filled)) / mean if mean else 0.0

    return {
        "short_side": short_side,
        "target_half": half,
        "paint": paint,
        "window": window_pixels,
        "masked": masked,
        "inside": inside,
        "inside_max": inside_max,
        "outside": outside,
        "far": far,
        "ring_delta": ring_delta,
        "radius": ring_radius,
        "fraction": ring_radius / half if half else 0.0,
        "ring_at": ring_at,
        "arc_sectors": len(filled),
        "spread": spread,
    }


def is_ring(result, minimum_outside, max_spread):
    return (
        result["outside"] >= minimum_outside
        and result["fraction"] > 1.0
        and result["arc_sectors"] >= MIN_SECTORS
        and result["spread"] <= max_spread
        and result["far"] == 0
    )


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--rest")
    parser.add_argument("--shot", action="append", default=[])
    parser.add_argument("--pair", action="append", default=[])
    parser.add_argument("--rect", required=True)
    parser.add_argument("--paint", type=float, default=0.0)
    parser.add_argument("--threshold", type=int, default=12)
    parser.add_argument("--band", type=float, default=0.45)
    # Just above `PressMotion.HALO_END_FRACTION`'s 1.30, so a ring drawn to its full extent is still
    # bounded by the window and anything past it is honestly not this button's.
    parser.add_argument("--max-fraction", type=float, default=1.34)
    parser.add_argument("--max-spread", type=float, default=0.35)
    parser.add_argument("--max-moving", type=float, default=0.5)
    parser.add_argument("--label", default="button")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    rect = tuple(int(part) for part in args.rect.replace(" ", "").split(","))
    if len(rect) != 4:
        sys.exit("press-ring-measure: --rect wants x1,y1,x2,y2")

    pairs = []
    if args.pair:
        for pair in args.pair:
            parts = pair.split(":")
            if len(parts) == 2:
                parts.append(None)
            if len(parts) != 3 or not parts[0] or not parts[1]:
                sys.exit(f"press-ring-measure: --pair wants REST:SHOT or REST:SHOT:NOISE, got {pair}")
            pairs.append(tuple(parts))
    elif args.rest:
        pairs = [(args.rest, shot, None) for shot in args.shot]
    if not pairs:
        sys.exit("press-ring-measure: pass --rest with --shot, or --pair REST:SHOT")

    rect_half = min(rect[2] - rect[0], rect[3] - rect[1]) / 2
    paint_note = ""
    if args.paint:
        paint_note = f" (the rect's half is {rect_half:.0f}px - a minimum touch target, not the paint)"
    if not args.quiet:
        print(
            f"{args.label}: bounds {rect[0]},{rect[1]}-{rect[2]},{rect[3]}, paint reaches "
            f"{args.paint or rect_half:.0f}px from the centre{paint_note} and a ring may reach "
            f"{(args.paint or rect_half) * args.max_fraction:.0f}px"
        )

    # A contraction moves the button's own edge, which is a few hundred pixels at most; a ring is a
    # curve *outside* it. Requiring a real count outside keeps an antialiased edge or a one-pixel
    # jitter from being read as a ring, and it is scaled to the button so a 40dp control is held to
    # the same standard as a 96dp one.
    minimum_outside = 60

    rang = False
    best = None
    results = []
    unstable = []
    for rest_path, shot_path, noise_path in pairs:
        rest = load(rest_path)
        width, height = rest.size
        noise = load(noise_path, (width, height)) if noise_path else None
        result = scan(
            rest,
            load(shot_path, (width, height)),
            rect,
            args.threshold,
            args.band,
            args.max_fraction,
            noise,
            args.paint or None,
        )
        results.append((shot_path, result))
        drew_ring = is_ring(result, minimum_outside, args.max_spread)
        rang = rang or drew_ring
        # How much of the window had to be thrown away as self-moving says whether the surface was
        # still enough for the verdict to mean anything - a list that scrolled has no opinion about
        # its buttons.
        moving_share = result["masked"] / result["window"] if result["window"] else 0.0
        if noise is not None and moving_share > args.max_moving:
            unstable.append((shot_path, moving_share))
        if not args.quiet:
            verdict = "ring" if drew_ring else ("give" if result["inside"] else "no change")
            if noise is not None and moving_share > args.max_moving:
                verdict = f"unstable ({moving_share:.0%} of the window moves on its own)"
            print(
                f"  {shot_path.split('/')[-1]:28s} give={result['inside']:6d} "
                f"(paint edge {result['paint']:.0f}px of "
                f"{result['target_half']:.0f}) outside={result['outside']:6d} "
                f"far={result['far']:6d} masked={result['masked']:6d} "
                f"reach={result['fraction']:4.2f}x arc={result['arc_sectors']:2d}/{SECTORS} "
                f"spread={result['spread']:.2f} delta={result['ring_delta']:4d} -> {verdict}"
            )
        # The strongest evidence, not the furthest pixel: a shot where more of the ring's arc was
        # drawn is a better witness than one with a lone stray pixel nearer the limit.
        if drew_ring and (best is None or result["outside"] > best[1]["outside"]):
            best = (shot_path, result)

    if rang:
        path, result = best
        print(
            f"{args.label}: RING seen outside the button in {path.split('/')[-1]} - "
            f"radius {result['radius']:.0f}px = {result['fraction']:.2f}x the button's paint "
            f"({result['paint']:.0f}px measured), circular across "
            f"{result['arc_sectors']}/{SECTORS} sectors (spread {result['spread']:.2f}), "
            f"{result['outside']}px changed, delta {result['ring_delta']}"
        )
        if unstable:
            share = max(share for _, share in unstable)
            print(
                f"{args.label}: WARNING - up to {share:.0%} of the measured window moves on its own, "
                "so treat this as weaker evidence than a still surface's"
            )
        return 0

    # Say which failure it is, because they mean different things: a give with no ring is a clip
    # eating it, no change at all is a capture that missed the press, and a give plus changes past the
    # ring's reach is a screen that moved for some other reason and a capture that proves nothing.
    gave = any(result["inside"] for _, result in results)
    strayed = sum(result["far"] for _, result in results)
    widest = max(results, key=lambda pair: pair[1]["outside"])[1] if results else None
    if gave:
        reason = (
            "a contraction was captured inside the drawn button, so the press landed but nothing "
            "round was drawn beyond its edge"
        )
        if widest and widest["fraction"] > 1.0:
            reason += f"; the furthest change outside reached {widest['fraction']:.2f}x that edge"
    else:
        reason = "nothing changed at all - the captures missed the press"
    if strayed:
        reason += (
            f" ({strayed}px changed past the ring's {args.max_fraction:.2f}x reach, so the screen was "
            "moving for some other reason)"
        )
    if widest and widest["outside"]:
        reason += (
            f"; the change outside reached {widest['fraction']:.2f}x out, "
            f"{widest['arc_sectors']}/{SECTORS} sectors, spread {widest['spread']:.2f}"
        )
    print(f"{args.label}: NO RING outside the button ({reason})")
    return 1


if __name__ == "__main__":
    sys.exit(main())
