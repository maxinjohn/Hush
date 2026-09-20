#!/usr/bin/env python3
# Hush (2026) — GPL-3.0. Contributors: see git history.
#
# How much a screen moved between two captures, split by where.
#
# This is the press probe's *control*: it takes a frame, presses a button, cancels the press, and
# takes another frame. Nothing about a cancelled press may survive it, so the two frames have to be
# the same picture. When they are not, the cycle's evidence is worthless - whatever changed is the
# screen moving, and the press measurement would have credited the button with it. That is not
# hypothetical: a probe that cancelled a press by dragging the finger clear scrolled the list under
# it, and the resulting band-wide repaint filled every angle around the button and passed the
# circularity test in `press-ring-measure.py`.
#
# The three counts say which kind of disturbance it was:
#
#   in-band     changed inside the window the ring is searched in - nothing may change here
#   around      changed in the neighbourhood just outside that window - a band-wide repaint, which is
#               how a press that scrolled its own list gives itself away
#   whole-frame changed anywhere at all, including the clock, the progress bar and any marquee
#
# Only the first two matter for validity: a mini player's progress bar and a scrolling title keep a
# device permanently "changed" at whole-frame scale, and neither says anything about the button.
#
# usage:
#   press-frame-diff.py A.png B.png --rect x1,y1,x2,y2 [--band 0.45] [--tolerance 240]
#
# Exit code: 0 when the band is still (within `--tolerance`), 1 when it moved.

import argparse
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment, not logic
    sys.exit("press-frame-diff: Pillow is required (pip3 install pillow)")


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("a")
    parser.add_argument("b")
    parser.add_argument("--rect", required=True)
    parser.add_argument("--band", type=float, default=0.45)
    parser.add_argument("--max-fraction", type=float, default=1.45)
    parser.add_argument("--threshold", type=int, default=12)
    parser.add_argument("--tolerance", type=int, default=240)
    parser.add_argument("--label", default="cycle")
    args = parser.parse_args()

    rect = tuple(int(part) for part in args.rect.replace(" ", "").split(","))
    if len(rect) != 4:
        sys.exit("press-frame-diff: --rect wants x1,y1,x2,y2")
    x1, y1, x2, y2 = rect
    short_side = min(x2 - x1, y2 - y1)
    margin = int(round(short_side * args.band))

    a = Image.open(args.a).convert("RGB")
    b = Image.open(args.b).convert("RGB")
    if a.size != b.size:
        sys.exit(f"press-frame-diff: captures differ in size ({a.size} vs {b.size})")
    width, height = a.size

    pa, pb = a.load(), b.load()
    in_band = around = whole = 0
    # Distances from the button's rectangle, not from its centre, so a tall or wide control is
    # surrounded by the same thickness of margin on every side.
    for y in range(height):
        for x in range(width):
            first, second = pa[x, y], pb[x, y]
            if abs(first[0] - second[0]) + abs(first[1] - second[1]) + abs(first[2] - second[2]) <= args.threshold:
                continue
            whole += 1
            dx = max(x1 - x, 0, x - x2 + 1)
            dy = max(y1 - y, 0, y - y2 + 1)
            out = max(dx, dy)
            if out <= margin:
                in_band += 1
            elif out <= margin + short_side:
                around += 1

    still = in_band + around <= args.tolerance
    verdict = "still" if still else "MOVED"
    print(
        f"{args.label}: {verdict} - in-band={in_band} around={around} whole-frame={whole} "
        f"(window {margin}px, tolerance {args.tolerance})"
    )
    return 0 if still else 1


if __name__ == "__main__":
    sys.exit(main())
