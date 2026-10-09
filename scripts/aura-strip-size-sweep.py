#!/usr/bin/env python3
# Hush (2026) — GPL-3.0. Contributors: see git history.
#
# Assert that the Aura player keeps its shape at *every* window size, not just this phone's.
#
# `aura-strip-align.py` measures one capture and `aura-strip-probe.sh` takes that one capture.
# Between them they answer "did my change break the row?" for the size the device happens to be at,
# which is exactly the wrong size to ask about: the layouts that broke in the last two rounds broke
# at 804x600dp (the pill's second width cap pulled it 10dp short once the window grew tall enough
# for the 50dp-button preset) and below ~340dp of width (the action row's 30dp floor made the row
# wider than the column it had just been measured against), and neither is this phone's own
# 804x360dp. Both were found by hand, by changing `wm size` and re-reading the tree - which is
# precisely the work that does not get repeated the next time somebody touches the layout.
#
# So this drives the device through the matrix instead. For every size it pins the orientation,
# brings the app up, taps the mini player to expand it, reads the player's own accessibility tree to
# find the provenance line's rows, captures, and hands the capture to `aura-strip-align.py` with
# those rows pinned. Landscape sizes get the full geometric assertion, in dp, at the tolerance the
# measurer is given. Portrait sizes get the structural one - the strip's text is drawn and every
# node is inside the window - because the measurer is deliberately landscape-only (the pill it looks
# for is the landscape one; in portrait the same facts live in the queue sheet).
#
# It is Python rather than a shell harness because the interesting half is parsing the accessibility
# dump, which is XML: the shells beside it stop short of driving the UI on purpose, and doing this in
# `grep`/`sed` would be a worse copy of the parser already here. Nothing is mocked - every number it
# prints came off the device in that run.
#
# usage:
#   scripts/aura-strip-size-sweep.py
#   scripts/aura-strip-size-sweep.py --sizes 804x600,960x600,450x360
#   scripts/aura-strip-size-sweep.py --serial eea86cae --tolerance-dp 1.5
#   scripts/aura-strip-size-sweep.py --keep-rotation     # leave the window size and rotation up
#
#   --sizes        dp windows, WxH, comma separated; the default matrix is the phone, the tight and
#                  roomy presets, a split-screen window, a car head unit, a tablet and a desktop
#   --serial       the device to drive, when several are attached
#   --density      dp -> px, read from `wm density` (the override, when there is one) if not given
#   --tolerance-dp how far the pill and the row may disagree; passed straight to the measurer
#   --out DIR      where captures go; default .freebuff/aura-strip-sweep
#   --keep-rotation  do not restore the window size and rotation afterwards
#
# Exit code: 0 when every size passed, 1 when any did not, 2 for a usage or device error.

import argparse
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MEASURE = os.path.join(HERE, "aura-strip-align.py")
PKG = "app.hush.music.debug"

# The dp windows the layout has to hold at. Landscape (rotation 1) sizes are the ones the measurer
# can assert on; the portrait ones are here so a reflow that drops a row entirely is caught too.
# 804x360 is this project's own device at its 640 density override; 804x300 and 804x450 straddle the
# tight and roomy preset thresholds; 450x360 is a split-screen window; 800x480 is a car head unit;
# 960x600 and 1280x720 are tablet and desktop widths.
DEFAULT_SIZES = [
    "804x300", "804x360", "804x450", "804x600",
    "600x360", "450x360",
    "800x480", "960x600", "1280x720",
    "360x804", "270x600", "320x568", "640x800",
]

NODE = re.compile(r"<node[^>]*>")
CODEC = re.compile(r"kbps|kHz|\bFLAC\b|OPUS|AAC|MP3|Vorbis", re.I)
MARKERS = ("Share", "Add to queue", "Sleep timer", "Lyrics")


def attr(node, name):
    match = re.search(name + r'="([^"]*)"', node)
    return match.group(1) if match else None


def bounds(node):
    match = re.match(
        r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", attr(node, "bounds") or ""
    )
    if not match:
        return None
    x1, y1, x2, y2 = (int(value) for value in match.groups())
    return None if x2 <= x1 or y2 <= y1 else (x1, y1, x2, y2)


class Device:
    """adb, plus the two things being driven: the window size and the app."""

    def __init__(self, serial):
        self.adb = ["adb"] if not serial else ["adb", "-s", serial]
        self.px_per_dp = 4.0

    def shell(self, command, timeout=90):
        result = subprocess.run(
            self.adb + ["shell", command], capture_output=True, text=True, timeout=timeout
        )
        return result.stdout.strip()

    def ok(self):
        result = subprocess.run(
            self.adb + ["get-state"], capture_output=True, text=True, timeout=20
        )
        return result.returncode == 0

    def read_density(self):
        """The override density is the one the app is laid out in, so it wins over the physical."""
        physical = override = ""
        for line in self.shell("wm density").splitlines():
            if "Override density" in line:
                override = line.split()[-1]
            elif "Physical density" in line:
                physical = line.split()[-1]
        value = override or physical
        if not value or value == "0":
            return None
        return int(value) / 160.0

    def rotate(self, rotation):
        self.shell("settings put system accelerometer_rotation 0")
        self.shell(f"settings put system user_rotation {rotation}")

    def set_window(self, width_dp, height_dp):
        """A landscape WxHdp window is a portrait-natural display of HxW dp, rotated."""
        landscape = width_dp > height_dp
        natural_w = round((height_dp if landscape else width_dp) * self.px_per_dp)
        natural_h = round((width_dp if landscape else height_dp) * self.px_per_dp)
        self.shell(f"wm size {natural_w}x{natural_h}")
        self.rotate(1 if landscape else 0)
        time.sleep(2.0)
        return landscape

    def capture(self, path):
        with open(path, "wb") as handle:
            subprocess.run(
                self.adb + ["exec-out", "screencap", "-p"], stdout=handle, timeout=120
            )

    def dump(self, tries=6):
        """The tree as XML, or None. The dump also resets the rotation on this device."""
        for _ in range(tries):
            self.shell("rm -f /sdcard/sweep.xml")
            if "dumped" in self.shell("uiautomator dump /sdcard/sweep.xml", timeout=120).lower():
                xml = self.shell("cat /sdcard/sweep.xml", timeout=120)
                self.rotate(1)  # cheapest guard against the rotation reset, harmless otherwise
                return xml
            time.sleep(1.5)
        return None


def nodes(xml):
    found = []
    for raw in NODE.findall(xml or ""):
        box = bounds(raw)
        if box:
            found.append(
                (
                    box,
                    attr(raw, "text") or "",
                    attr(raw, "content-desc") or "",
                    attr(raw, "clickable") == "true",
                )
            )
    return found


def expanded(xml):
    return bool(xml) and any(marker in xml for marker in MARKERS)


def codec_row(xml):
    for box, text, _desc, _clickable in nodes(xml):
        if text and CODEC.search(text):
            return box
    return None


def reach_player(device, tries=3):
    """Home -> tap the mini player -> expanded. Returns the tree, expanded or not."""
    device.shell("input keyevent KEYCODE_WAKEUP")
    device.shell("wm dismiss-keyguard")
    device.shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(4)
    xml = None
    for _ in range(tries):
        xml = device.dump()
        if expanded(xml):
            return xml
        found = nodes(xml)
        height = max((box[3] for box, _t, _c, _k in found), default=0)
        targets = [
            (box, clickable)
            for box, _t, _c, clickable in found
            if clickable and box[1] > height * 0.6
        ]
        if targets:
            # The widest clickable row at the bottom is the mini player. Tap its left fifth: the
            # right-hand end of that row is where its sleep-timer and overflow controls sit, and
            # those open a sheet instead of the player.
            box = max(targets, key=lambda item: item[0][2] - item[0][0])[0]
            device.shell(
                f"input tap {box[0] + (box[2] - box[0]) // 5} {(box[1] + box[3]) // 2}"
            )
            time.sleep(2.5)
        xml = device.dump()
    return xml


def fail(message):
    """A usage or device error: exit 2, the code the harnesses beside this one use for it."""
    print(f"aura-strip-size-sweep: {message}", file=sys.stderr)
    return 2


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--sizes", help="dp windows as WxH, comma separated")
    parser.add_argument("--serial")
    parser.add_argument("--density", type=float)
    parser.add_argument("--tolerance-dp", type=float, default=1.0)
    parser.add_argument("--out", default=".freebuff/aura-strip-sweep")
    parser.add_argument("--keep-rotation", action="store_true")
    args = parser.parse_args()

    # `is not None`, not truthiness: an explicitly empty --sizes is a typo to report, not a request
    # to run the whole matrix.
    sizes = []
    for spec in (args.sizes.split(",") if args.sizes is not None else DEFAULT_SIZES):
        spec = spec.strip()
        if not spec:
            continue
        try:
            width, height = (int(part) for part in spec.lower().split("x"))
        except ValueError:
            return fail(f"--sizes wants WxHdp entries, not {spec!r}")
        sizes.append((width, height))
    if not sizes:
        return fail("--sizes selected nothing")

    device = Device(args.serial)
    if not device.ok():
        return fail("no device (pass --serial for one of several)")

    # An override size is put back as it was found - resetting it would leave the device at a
    # different size than the person running this had chosen.
    was_size = device.shell("wm size")
    override = re.search(r"Override size: (\d+x\d+)", was_size)
    restore_size = f"wm size {override.group(1)}" if override else "wm size reset"
    was_accel = device.shell("settings get system accelerometer_rotation") or "1"
    was_rotation = device.shell("settings get system user_rotation") or "0"

    density = args.density or device.read_density()
    if not density:
        return fail("could not read wm density; pass --density")
    device.px_per_dp = density

    os.makedirs(args.out, exist_ok=True)
    failures = []
    print(f"aura-strip-size-sweep: {len(sizes)} window sizes, {density}px/dp\n")
    try:
        for width_dp, height_dp in sizes:
            label = f"{width_dp}x{height_dp}"
            landscape = device.set_window(width_dp, height_dp)
            xml = reach_player(device)
            if not expanded(xml):
                print(f"  {label:>9}  SKIP - could not reach the expanded player")
                failures.append((label, "unreachable"))
                continue

            shot = os.path.join(args.out, f"aura-{label}dp.png")
            device.capture(shot)
            strip = codec_row(xml)
            if strip is None:
                print(f"  {label:>9}  FAIL - the provenance line is not drawn")
                failures.append((label, "strip missing"))
                continue

            found = nodes(xml)
            # The dump's own root is the window the app was given, so a node outside it is a node
            # the layout drew past its bounds - the one failure every size here is exposed to.
            root = found[0][0] if found else (0, 0, 0, 0)
            if not landscape:
                outside = [
                    box
                    for box, _t, _c, _k in found
                    if box[2] > root[2] or box[3] > root[3] or box[0] < root[0]
                ]
                if outside:
                    print(f"  {label:>9}  FAIL - {len(outside)} node(s) outside the window")
                    failures.append((label, "overflow"))
                else:
                    print(
                        f"  {label:>9}  OK (portrait) - strip and rows all inside "
                        f"{root[2] / density:.0f}x{root[3] / density:.0f}dp"
                    )
                continue

            # The measurer's default scan window starts at 56% of the width, which is where the
            # pill's left end sits on a wide window - but the pill is a fixed 250-340dp centred in
            # the controls column, so the narrower the window the further left it starts, and under
            # ~700dp the run starts left of the window and is discarded. 0.35x the width is left of
            # the pill's left end at every size here and still right of the artwork column.
            x0 = round(root[2] * 0.35) if width_dp < 700 else None
            command = [
                sys.executable, MEASURE, "--shot", shot,
                "--density", str(density), "--tolerance-dp", str(args.tolerance_dp),
                "--strip-y", f"{strip[1] - 8},{strip[3] + 8}",
            ]
            if x0 is not None:
                command += ["--x0", str(x0)]
            def run_measure():
                done = subprocess.run(command, capture_output=True, text=True)
                text = done.stdout + done.stderr
                for line in text.splitlines():
                    if (
                        "ALIGNED" in line
                        or "MISALIGNED" in line
                        or "could not be measured" in line
                        or "but no action row" in line
                    ):
                        return done.returncode, line.split(": ", 1)[-1]
                return done.returncode, text.strip().splitlines()[-1] if text.strip() else "no output"

            code, verdict = run_measure()
            # An answer of "misaligned" is a result and is reported as such. *No* answer is not one:
            # the measurer reads rims off a still capture, so a frame taken while the marquee was
            # still settling can leave it unable to find the row at all - which is a different fact
            # from the layout being wrong, and it is worth one re-capture before calling it either
            # way. It is still counted as a failure if the second attempt also has nothing to say.
            if code != 0 and "MISALIGNED" not in verdict:
                device.capture(shot)
                code, verdict = run_measure()
                if code != 0 and "MISALIGNED" not in verdict:
                    verdict = f"could not be measured ({verdict})"
            if code == 0:
                print(f"  {label:>9}  PASS - {verdict}")
            else:
                print(f"  {label:>9}  FAIL - {verdict}")
                failures.append((label, verdict))
            sys.stdout.flush()
    finally:
        if not args.keep_rotation:
            device.shell(restore_size)
            device.shell(f"settings put system accelerometer_rotation {was_accel}")
            device.shell(f"settings put system user_rotation {was_rotation}")
            device.shell("rm -f /sdcard/sweep.xml")

    print()
    if failures:
        for label, why in failures:
            print(f"aura-strip-size-sweep: {label} FAILED - {why}")
        print(f"aura-strip-size-sweep: {len(failures)} of {len(sizes)} sizes did not hold")
        return 1
    print(f"aura-strip-size-sweep: all {len(sizes)} sizes hold ({args.out})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
