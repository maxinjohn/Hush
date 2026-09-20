#!/usr/bin/env python3
# Hush (2026) — GPL-3.0. Contributors: see git history.
#
# The radial profile of what a press changed, for when a ring verdict needs explaining.
#
# `press-ring-measure.py` answers yes or no, and it answers it from the drawn button's edge outward.
# When the answer is no and the code plainly draws a ring, the next question is *where* the ring went -
# under the button's own paint, outside the window, or nowhere at all - and that is a question about the
# shape of the change, not its verdict. This prints the change binned by distance from the button's
# centre, plus how round it is, so the answer is visible rather than inferred.
#
# usage:
#   press-ring-profile.py REST.png SHOT.raw... --center 720,2480 [--keep 152] [--threshold 8] [--reach 250]
#
#   --keep   the radius the button's own paint covers: pixels inside it are the contraction, and
#            anything beyond it that changed could be a ring. Pass the drawn radius if you know it
#            (half the smaller side of the button's layout), or omit it and read the profile first.
#   --reach  how far out to gather, in pixels.

import argparse
import math
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment, not logic
    sys.exit("press-ring-profile: Pillow is required (pip3 install pillow)")

SECTORS = 12
BIN = 10


def raw(path, size):
    width, height = size
    with open(path, "rb") as handle:
        data = handle.read()
    stride = width * height * 4
    if len(data) == stride + 16:
        data = data[16:]
    elif len(data) != stride:
        sys.exit(f"press-ring-profile: {path} is {len(data)} bytes, expected {stride} (+16)")
    return Image.frombytes("RGBA", (width, height), data).convert("RGB")


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("rest")
    parser.add_argument("shots", nargs="+")
    parser.add_argument("--center", required=True)
    parser.add_argument("--keep", type=float, default=None)
    parser.add_argument("--threshold", type=int, default=8)
    parser.add_argument("--reach", type=int, default=250)
    args = parser.parse_args()

    cx, cy = (float(part) for part in args.center.replace(" ", "").split(","))
    rest = Image.open(args.rest).convert("RGB")
    width, height = rest.size
    pixels = rest.load()

    for path in args.shots:
        shot = raw(path, (width, height))
        shot_pixels = shot.load()
        bins = [0] * (args.reach // BIN + 1)
        counts = [0] * SECTORS
        radii = [0.0] * SECTORS
        beyond = 0
        peak_beyond = None
        for y in range(max(int(cy) - args.reach, 0), min(int(cy) + args.reach, height)):
            for x in range(max(int(cx) - args.reach, 0), min(int(cx) + args.reach, width)):
                a, b = pixels[x, y], shot_pixels[x, y]
                delta = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
                if delta <= args.threshold:
                    continue
                r = math.hypot(x - cx, y - cy)
                index = int(r // BIN)
                if index < len(bins):
                    bins[index] += 1
                if args.keep is not None and r > args.keep:
                    beyond += 1
                    sector = int((math.atan2(y - cy, x - cx) + math.pi) / (2 * math.pi) * SECTORS) % SECTORS
                    counts[sector] += 1
                    radii[sector] = max(radii[sector], r)
                    if peak_beyond is None or delta > peak_beyond[0]:
                        peak_beyond = (delta, r, x, y)
        print(f"--- {path.split('/')[-1]} ---")
        print("  radius bins:", " ".join(f"{i * BIN}:{c}" for i, c in enumerate(bins) if c) or "nothing changed")
        if args.keep is not None:
            filled = [radii[i] for i in range(SECTORS) if counts[i] >= 4]
            spread = (max(filled) - min(filled)) / (sum(filled) / len(filled)) if len(filled) >= 2 else 0.0
            print(
                f"  beyond the paint ({args.keep:.0f}px): {beyond}px, arc {len(filled)}/{SECTORS} sectors, "
                f"outer edge {max(filled) if filled else 0:.0f}px = "
                f"{(max(filled) / args.keep if filled else 0):.2f}x, spread {spread:.2f}"
            )
            if peak_beyond:
                delta, r, x, y = peak_beyond
                print(
                    f"  strongest beyond it: delta {delta} at r={r:.0f} ({x},{y}) "
                    f"rest={pixels[x, y]} shot={shot_pixels[x, y]}"
                )


if __name__ == "__main__":
    main()
