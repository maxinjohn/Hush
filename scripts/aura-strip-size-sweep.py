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
# The transport row is asserted at every size in both orientations, from the buttons' own labels:
# each is the circle it is drawn as, the two side buttons are the same size, the play button is the
# biggest, and all three are inside the window. Those numbers come from the app's own in-process
# semantics report against the debug build (`Device.semantics_report`), not from `uiautomator`: on
# this device the platform publishes the play button's tappable node 1dp shorter than the button's
# laid-out size, which is a fact about the platform's rounding and not about the row. That row is the one thing on the player drawn at a
# fixed size inside a column whose width is whatever is left over, so it is where a window the
# layout was never measured at shows up as a *smaller next button* rather than as anything that
# looks broken - measured at a 600x400dp window before the fit existed: previous 68x68dp, play
# 88x88dp, next 48x68dp.
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
#   scripts/aura-strip-size-sweep.py --font-scales 1.0,1.5,2.0   # Android's text size, swept too
#   scripts/aura-strip-size-sweep.py --keep-rotation     # leave the window size and rotation up
#
#   --sizes        dp windows, WxH, comma separated; the default matrix is the phone, the tight and
#                  roomy presets, a split-screen window, a car head unit, a tablet and a desktop
#   --serial       the device to drive, when several are attached
#   --density      dp -> px, read from `wm density` (the override, when there is one) if not given
#   --tolerance-dp how far the pill and the row may disagree; passed straight to the measurer
#   --out DIR      where captures go; default .freebuff/aura-strip-sweep
#   --font-scales  the display's *other* axis: the font scales to sweep each size at, e.g.
#                  1.0,1.5,2.0 for the default, Android's largest accessibility setting and its
#                  maximum. Default is the scale the device is already on, so the flag is the
#                  opt-in; a run that sweeps more than one scale names the captures by scale.
#   --keep-rotation  do not restore the window size and rotation afterwards (the font scale is
#                  always put back, since it is a device-wide accessibility setting)
#   --restore-device clear a size override and a font scale left behind by a killed run and exit,
#                  without sweeping
#
# Exit code: 0 when every size passed, 1 when any did not, 2 for a usage or device error.

import argparse
import os
import re
import signal
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
#
# The middle band is the car and head-unit sizes, in the dp a head unit actually reports: a
# 1280x720px display at 240dpi is 853x480dp, at 160dpi it is the full 1280x720dp, and a 9" panel
# running 320dpi gives 640x360dp. 600x400 is the smallest of them and is here because it is the
# window the transport row's next button was measured at 48x68dp in against previous's 68x68 - a
# column narrower than the preset the column's own height had chosen.
DEFAULT_SIZES = [
    "804x300", "804x360", "804x450", "804x600",
    "600x360", "600x400", "640x400", "450x360",
    "800x480", "853x480", "960x540", "1024x600", "1280x720", "1920x720",
    "560x300", "720x400",
    "360x804", "270x600", "320x568", "640x800", "640x960", "768x1024",
    # The short portrait windows: a freeform or desktop window, which is the only way a portrait
    # window is ever this short. 280x380dp is the size the transport's height was measured at - the
    # play button came out 76x61dp there, an ellipse, because nothing fitted the row to the height
    # it was handed the way it was already fitted to the width.
    "300x400", "280x380",
]

NODE = re.compile(r"<node[^>]*>")
CODEC = re.compile(r"kbps|kHz|\bFLAC\b|OPUS|AAC|MP3|Vorbis", re.I)
MARKERS = ("Share", "Add to queue", "Sleep timer", "Lyrics")

# The transport row's own labels, from R.string.widget_previous / R.string.next / R.string.play and
# R.string.widget_pause. Reading the three buttons by name is what makes this check specific - by
# size alone, the row is three circles and there is no way to say which one is which, which is
# exactly the thing under test.
TRANSPORT_PREVIOUS = "Previous"
TRANSPORT_PLAY = ("Play", "Pause")
TRANSPORT_NEXT = "Next"

# The player's own close control, from R.string.close. Checked too, and for the same reason: it sits
# in a Row beside the NOW PLAYING pill on top of the artwork column, which is the narrowest place in
# the layout - in a half-screen landscape window that column is ~166dp against a ~171dp row, and the
# child a Row squeezes is the last one, which there is the button that closes the player.
PLAYER_CLOSE = "Close"

# How far apart a button's width and height may be before it is no longer the circle it is drawn as,
# and how far the two side buttons may differ from each other. Both are sub-dp on purpose: a squeezed
# button was 20dp off, and a tolerance wide enough to hide that would hide the failure.
SQUARE_TOLERANCE_DP = 0.75
SIDE_TOLERANCE_DP = 0.75

# The debug build's own semantics probe: the report it writes, and the broadcast that asks for one.
PROBE_ACTION = "app.hush.music.action.SEMANTICS_PROBE"
PROBE_RECEIVER = f"{PKG}/app.hush.music.debug.SemanticsProbeReceiver"
PROBE_REPORT = "files/debug/semantics-probe.txt"

# One node as the probe prints it: `[merged] id=4274 d=7 at=444,2218 248x248 clickable keys=… "Pause"`.
# The id is captured because the node that owns a label's words is printed in both trees, and a
# label counted twice is a count nobody can read; see `clipped_rows`.
PROBE_NODE = re.compile(
    r"^\s*\[(merged|unmerged)\] id=(\d+) d=\d+ at=(-?\d+),(-?\d+) (\d+)x(\d+)(.*)$"
)
# The label is whatever the node says, quoted at the end of the line - text first, then a content
# description. `re.DOTALL` is not wanted: a report line is a line.
PROBE_LABEL = re.compile(r'"([^"]*)"\s*$')
# The probe's own count of how each label fared: `text fit: 28 labels measured, 27 fit, 1
# ellipsized, 0 clipped (...)`.
TEXT_FIT = re.compile(r"text fit: (\d+) labels measured[^\n]*?(\d+) clipped")

# The token the probe prints on a node whose label was drawn narrower than it needs and *not*
# ellipsized - characters simply missing. See `clipped_problem`.
PROBE_CLIPPED = "clipped"
# The window the probe measured in, in px: `window=3216x1200 boundary=972 focus=true`.
PROBE_WINDOW = re.compile(r"^window=(\d+)x(\d+)", re.M)


def one_line(text):
    """A shell result as one line: `wm size` answers on two, and half of it is the wrong answer."""
    return "; ".join(part.strip() for part in text.splitlines() if part.strip())


def clear_leftover_override(device):
    """Put back a size override a killed run left, and report the one that was cleared.

    The window size is this harness's to drive, and `wm size` is *not* how a screen resolution is
    chosen on this phone: OxygenOS switches between the panel's own modes - `dumpsys display` lists
    them, 1080x2412 and 1440x3216 here - and `display_size_forced` is written by none of them. So a
    size override that is not the panel's own size is debris from a tool, and nearly always the
    window of a run that was killed before its `finally` could restore anything: SIGKILL cannot be
    caught, and a run driven by a shell that ends takes one.

    This used to be *adopted* instead - read back at startup as "the size it was found at" and put
    back at the end of every later run, which is how a single killed run turned the 560x300dp window
    of this matrix into the phone's permanent resolution, every screen on it laid out 300x560dp
    rather than its own 360x804dp. A leftover that is adopted is a leftover that survives; a
    leftover that is cleared and said out loud is one the next run cannot inherit.

    An override that *is* the panel's own size is left alone: it changes nothing, and it is the one
    a person could have set for themselves.
    """
    was_size = device.shell("wm size")
    physical = re.search(r"Physical size: (\d+x\d+)", was_size)
    override = re.search(r"Override size: (\d+x\d+)", was_size)
    if not override or (physical and override.group(1) == physical.group(1)):
        return None
    # Said out loud, and not only for the person watching: a repair that happens silently is a
    # repair nobody can tell apart from the bug never having been there.
    print(
        f"aura-strip-size-sweep: clearing a {override.group(1)} size override left on the device "
        "by a run that was killed; the window it was driving is not this phone's size"
    )
    device.shell("wm size reset")
    return override.group(1)


def clear_leftover_font_scale(device):
    """Put back the font scale a killed run left, and report the one that was cleared.

    The font scale is the *other* device-wide knob this harness drives, and it is driven the same
    way as the window size - `settings put system font_scale`, put back in the same `finally` - so a
    run killed between the two leaves it behind exactly as the size is left behind, and a phone left
    at 2.0 is a phone whose every screen is drawn with doubled text. That is what was found here:
    `font_scale 2` with the whole system - this app's headings, the launcher's labels, the status
    bar - at twice the text it is meant to have, from a swept scale whose restore never ran.

    What separates debris from a setting is the phone's own second copy of it. OxygenOS keeps the
    font size chosen in Display settings in `device_font_scale`, beside the framework's
    `font_scale`, and this harness writes only the framework's. So the two *disagreeing* is this
    script's fingerprint - not the value being unusual. Two values that agree are left alone even
    when they are not 1.0, because then it is the person's setting and not ours; on a device that
    keeps no second copy the question cannot be answered, and the scale is left rather than guessed
    at.
    """
    was = device.read_font_scale()
    own = device.read_device_font_scale()
    if was is None or own is None or abs(was - own) < 0.001:
        return None
    print(
        f"aura-strip-size-sweep: clearing a font scale of {was:g} left on the device by a run that "
        f"was killed; the phone's own font size is {own:g}"
    )
    device.shell(f"settings put system font_scale {own:g}")
    device.shell(f"am force-stop {PKG}")
    return was


def device_state_line(device):
    """The two device-wide knobs this harness drives, read back from the device itself."""
    font = device.read_font_scale()
    return (
        f"size {one_line(device.shell('wm size'))}; "
        f"font scale {'unknown' if font is None else format(font, 'g')}"
    )


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
        # The rotation the current window wants, re-applied after anything that resets it - see
        # `dump`. Portrait sizes are measured in portrait because of this, not despite it.
        self.rotation = 1

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

    def semantics_report(self, label, sample_limit=1000):
        """The debug build's own in-process semantics report for the screen, or None.

        The transport row is measured from this rather than from `uiautomator`, and the reason is a
        measurement that was wrong: on this device `uiautomator` publishes the play button's
        *tappable* node one dp shorter than the button (62x61dp at an 804x300dp window) while the
        button's own label node beside it reports 62x62dp - and the composition, read in-process,
        says 248x248px = 62x62dp. A check that reads the platform's rounded node would have called
        a correct row broken. The probe reads Compose's own tree, where a size is the size that was
        laid out. It is debug-only, which this harness already requires.
        """
        self.shell(f"run-as {PKG} rm -f {PROBE_REPORT}")
        self.shell(
            f"am broadcast -n {PROBE_RECEIVER} -a {PROBE_ACTION} --es label {label} "
            f"--ei sample-limit {sample_limit}"
        )
        marker = f"semantics-probe step=content label={label}"
        for _ in range(20):
            text = self.shell(f"run-as {PKG} cat {PROBE_REPORT}", timeout=60)
            if marker in text:
                return text
            time.sleep(0.5)
        return None

    def read_font_scale(self):
        value = self.shell("settings get system font_scale")
        try:
            return float(value)
        except ValueError:
            return None

    def read_device_font_scale(self):
        """The font size the phone's own Display settings hold, or None where it keeps no copy.

        See `clear_leftover_font_scale`: this is the value that says whether `font_scale` is the
        person's setting or this harness's debris.
        """
        value = self.shell("settings get system device_font_scale")
        try:
            return float(value)
        except ValueError:
            return None

    def set_font_scale(self, scale):
        """The window's other axis: how big the text is inside it.

        `font_scale` reaches a running app as a configuration change, but the app is restarted
        anyway: the sweep re-launches per size (see `reach_player`), and a scale that only some of
        the sizes in a run had actually applied is the kind of difference that shows up as one size
        failing for no reason - a fresh process per scale is the state the person who set the
        setting in Display settings would also get.
        """
        self.shell(f"settings put system font_scale {scale:g}")
        self.shell(f"am force-stop {PKG}")
        time.sleep(1.5)

    def set_window(self, width_dp, height_dp):
        """A landscape WxHdp window is a portrait-natural display of HxW dp, rotated."""
        landscape = width_dp > height_dp
        natural_w = round((height_dp if landscape else width_dp) * self.px_per_dp)
        natural_h = round((width_dp if landscape else height_dp) * self.px_per_dp)
        self.shell(f"wm size {natural_w}x{natural_h}")
        self.rotation = 1 if landscape else 0
        self.rotate(self.rotation)
        time.sleep(2.0)
        return landscape

    def capture(self, path):
        with open(path, "wb") as handle:
            subprocess.run(
                self.adb + ["exec-out", "screencap", "-p"], stdout=handle, timeout=120
            )

    def dump(self, tries=6):
        """The tree as XML, or None, in the frame this window's rotation asks for.

        The dump resets the rotation on this device, and the rotation it resets it to is not the one
        the size asked for: it used to be put back to landscape unconditionally, which meant every
        *portrait* size in the matrix was measured with the app laid out for landscape - the numbers
        looked plausible (a transport row, square and centred) and belonged to the wrong layout
        entirely. It is put back to the rotation this window wants.

        That reset also races the dump itself: every so often the tree comes back in the other
        frame - measured at a 560x300dp window, 2 dumps in 6, with the window landscape and the
        dump 1200x2240. A dump in the wrong frame is not a slightly-off measurement, it is a
        measurement of nothing: the caller pins the strip to rows that belong to the other axis, the
        capture is the right way round, and the row it is looking for is nowhere in it - which is
        how a size whose strip is on screen the whole time came back "could not be measured". So
        the rotation is asserted before the dump as well as after it, and a dump in the other frame
        is thrown away and asked for again rather than handed on.
        """
        want_landscape = self.rotation in (1, 3)
        for _ in range(tries):
            self.rotate(self.rotation)
            self.shell("rm -f /sdcard/sweep.xml")
            if "dumped" in self.shell("uiautomator dump /sdcard/sweep.xml", timeout=120).lower():
                xml = self.shell("cat /sdcard/sweep.xml", timeout=120)
                frame = dump_frame(xml)
                if frame and (frame[0] > frame[1]) != want_landscape:
                    time.sleep(1.0)
                    continue
                self.rotate(self.rotation)
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


def dump_frame(xml):
    """The window the dump was written in, as (width, height) px, or None.

    The dump's own root is the window the app was given - the frame the node bounds are in, which
    is why a dump in the wrong one sends every row it pins to the wrong axis.
    """
    found = nodes(xml)
    return (found[0][0][2], found[0][0][3]) if found else None


def expanded(xml):
    return bool(xml) and any(marker in xml for marker in MARKERS)


def codec_row(xml):
    for box, text, _desc, _clickable in nodes(xml):
        if text and CODEC.search(text):
            return box
    return None


def png_size(path):
    """A PNG's pixel size from its IHDR, without decoding the image - (width, height), or None."""
    try:
        with open(path, "rb") as handle:
            header = handle.read(33)
    except OSError:
        return None
    if len(header) < 33 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        return None
    return int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")


def probe_strip_rows(report, capture_size, density):
    """The provenance line's own rows, in the capture's pixels, from the app's in-process tree.

    A second source for the line the dump normally names, and it exists because the dump is not
    reliable about this one node. Measured on device at a 280x380dp window, default font scale: the
    line was drawn (a screenshot holds it, marquee mid-scroll) and the app's own tree reported it
    (`FLAC • 43 MB • SpotiFLAC · deezer (device cache)`), while `uiautomator`'s dump for the same
    frame carried no node with that text at all - and the same window at a 2.0 font scale did. A
    harness that reads only the dump therefore calls a drawn row missing, and reports it as the
    layout's fault.

    The rows are the box's own top and bottom, because the probe reports in the capture's pixels
    like everything else here - see `probe_nodes` for the one reading of its line that gets that
    right. A row that falls outside the capture is skipped rather than returned: the report is read
    from the tree the capture was taken with, so a row outside it is one of the two being from
    another frame, and pinning the measurer off the image is how a window whose pill was drawn and
    legible came back "could not be measured".
    """
    if capture_size is None:
        return None
    for box, label, _clickable in probe_nodes(report):
        if not label or not CODEC.search(label):
            continue
        top, bottom = box[1], box[3]
        # A degenerate box is not an answer: the report carries the same line more than once (merged
        # and unmerged), and a copy whose width or height collapsed says nothing about where the row
        # is - so the next candidate is read, and only a report where none of them has a height is
        # the "not drawn" this returns None for.
        if bottom - top <= 0:
            continue
        if bottom <= 0 or top >= capture_size[1]:
            continue
        pad = round(2 * density)
        return max(0, top - pad), bottom + pad
    return None


def probe_nodes(report):
    """The report's merged nodes as (box, label, clickable), in the capture's pixels.

    Merged only: that is the tree an accessibility service reads, and the unmerged root below it is
    the same nodes again with merging undone.

    A node is printed as `at=<top>,<left> WxH` - y before x, which is the probe's own convention
    (`SemanticsProbeReport.line`, and the format its unit test pins) and the one the other scripts
    beside this one read. The two are easy to take the wrong way round, and taking them that way is
    silent: every size still reads correctly, because a box's width and height come out right
    whichever field precedes which, so only the *placements* are wrong - which is how the strip's
    band came back 200 rows off a window whose pill was drawn the whole time, and how a `--strip-y`
    pinned to it made the measurer report a legible pill as missing. So the y is read first and the
    box assembled as the (left, top, right, bottom) everything below expects.
    """
    out = []
    for line in (report or "").splitlines():
        match = PROBE_NODE.match(line)
        if not match or match.group(1) != "merged":
            continue
        _tree, _id, top, left, width, height, rest = match.groups()
        left, top, width, height = (int(value) for value in (left, top, width, height))
        label = PROBE_LABEL.search(rest)
        out.append(
            (
                (left, top, left + width, top + height),
                label.group(1) if label else "",
                "clickable" in rest,
            )
        )
    return out


def clipped_rows(report):
    """The labels the probe measured as clipped, as (text, width, height), one per node.

    Not a reading of the pixels and not a guess: for each label the probe asks Compose's own layout
    what the words need on one line and how much width the layout was given, and reports the ones
    that were told to hold one line and did not get it - so "the box is the size the code asked for
    and the characters are missing" is a fact about the node itself. That is exactly the failure a
    sweep reading geometry cannot see - measured at a 1920x720dp window at font scale 2.0, the
    landscape clock was drawn as "0:1" while its node reported the full "0:01" in a box that was the
    size the layout had chosen, and every check in this file passed.

    Both trees are read, and the rows are deduplicated by node id. The probe records the fit on the
    node that *owns* the words - the node the unmerged tree holds and the merged tree has folded
    into its parent - so a labelled node is printed in both and would otherwise be counted twice; and
    which tree carries the token is the probe's business, not this reader's, so neither is assumed.

    The token is looked for in the part of the line *before* the quoted label, so a track whose title
    happens to contain the word cannot fake one.
    """
    out = []
    seen = set()
    for line in (report or "").splitlines():
        match = PROBE_NODE.match(line)
        if not match:
            continue
        _tree, node_id, _top, _left, width, height, rest = match.groups()
        if PROBE_CLIPPED not in rest.split('"', 1)[0].split():
            continue
        if node_id in seen:
            continue
        seen.add(node_id)
        label = PROBE_LABEL.search(rest)
        out.append((label.group(1) if label else "", int(width), int(height)))
    return out


def text_fit_counts(report):
    """(labels measured, labels clipped) from the probe's own text-fit line, or None if absent."""
    match = TEXT_FIT.search(report or "")
    return (int(match.group(1)), int(match.group(2))) if match else None


def clipped_problem(report):
    """The problem when a label is drawn narrower than it needs, or None when every one fits.

    A label that did not fit *and ends in an ellipsis* is not reported: that is the app choosing to
    shorten something it knows can be shortened, and it reads as shortened. What is reported is the
    silent case - characters dropped with nothing to show it - which is what a clock drawn as "0:1"
    was. A report with no text-fit line at all is a problem too, and of the same kind: it means
    nothing measured the labels, and a check that cannot run is not a check that passed.
    """
    counts = text_fit_counts(report)
    if counts is None:
        return (
            "the probe's report carries no text-fit line, so nothing measured whether any label is "
            "drawn wider than the box it was given (an older debug build?)"
        )
    measured, clipped = counts
    if measured == 0:
        return "no label was measured, so a clipped one cannot be told from a label that fits"
    if clipped == 0:
        return None
    rows = clipped_rows(report)
    # The names are the whole value of the message when they are there, and the count is enough when
    # they are not: a report whose count says clipped but whose lines do not say which is still a
    # finding, and it is worded so that nobody has to guess whether the check ran.
    named = ", ".join(f'"{text}" at {width}x{height}px' for text, width, height in rows[:4])
    return (
        f"{clipped} label(s) drawn narrower than they need, clipped with no ellipsis (characters "
        f"missing, and the node reports a box the size the code asked for): {named}"
        if named
        else f"{clipped} label(s) clipped with no ellipsis; the report's text-fit line counts them"
    )


def text_fit_summary(report):
    """What to say about the labels when none is clipped, so a green line proves the check ran."""
    counts = text_fit_counts(report)
    if counts is None:
        return "text fit not reported"
    return f"text fits ({counts[0]} labels)"


def probe_window(report):
    """The window the probe measured in, or None."""
    match = PROBE_WINDOW.search(report or "")
    return (int(match.group(1)), int(match.group(2))) if match else None


def named_transport(found):
    """The three buttons by their labels, or None on a build that does not label them."""
    labelled = {}
    for box, text, clickable in found:
        if not clickable:
            continue
        if text == TRANSPORT_PREVIOUS:
            labelled["previous"] = box
        elif text == TRANSPORT_NEXT:
            labelled["next"] = box
        elif text in TRANSPORT_PLAY:
            labelled["play"] = box
    if len(labelled) == 3:
        return labelled
    return None


def geometric_transport(found, density):
    """The same three buttons without labels, as (previous, play, next), or None.

    Kept because a harness that can only read the fixed build cannot show the bug it was written
    for: the pre-fix build has no labels, and the row's geometry is the only thing there is to
    measure. Three buttons sharing a y band, none smaller than a thumb target, and - the rule that
    tells it apart from the rows it is not - the middle one is both the widest and the tallest. The
    action row is six buttons, the two chip rows have three but their widest is at one end, and the
    lone header buttons are one apiece; the transport is the only row of three built around a
    bigger button in the middle. On the labelled build the names win and this is never consulted.
    """
    # `(box, text)`, not a third name: this yields three values and unpacks three, but there was
    # never a third one to yield, so the comprehension raised `NameError: label` the moment anything
    # passed the filter - which is to say it raised exactly when it was needed, on the unlabelled
    # build it exists for, and never in a labelled run. Found by running the font-scale pass below,
    # where one size's probe report came back without the three names.
    candidates = [
        (box, text)
        for box, text, clickable in found
        if clickable
        and 44 <= (box[2] - box[0]) / density <= 140
        and 44 <= (box[3] - box[1]) / density <= 140
    ]
    # On one line first - the buttons of a row share their centre y to the pixel, while something
    # drawn elsewhere on the screen can still land within a couple of dp of that line (the artwork
    # column's own overlay did at a 600x400dp window: 80x56dp, 16dp to the left).
    lines = []
    for box in sorted(candidates, key=lambda item: item[0][1]):
        centre = (box[0][1] + box[0][3]) / 2
        for line in lines:
            if abs(centre - line[0]) <= 3 * density:
                line[1].append(box[0])
                break
        else:
            lines.append([centre, [box[0]]])
    # Then side by side: the transport is three buttons next to each other, and a row is a chain
    # whose neighbours are within a gap of each other. This is what keeps a left-hand overlay on the
    # same line from joining the row it is nowhere near.
    found_rows = []
    for _centre, on_line in lines:
        chain = []
        for box in sorted(on_line, key=lambda item: item[0]):
            if chain and box[0] - chain[-1][2] > 40 * density:
                chain = []
            chain.append(box)
            if len(chain) == 3:
                widths = [member[2] - member[0] for member in chain]
                heights = [member[3] - member[1] for member in chain]
                if (
                    widths[1] > widths[0]
                    and widths[1] > widths[2]
                    and heights[1] > heights[0]
                    and heights[1] > heights[2]
                ):
                    found_rows.append(list(chain))
    if len(found_rows) != 1:
        # None or several: saying so is the answer. A harness that picked one of two candidates
        # would be reporting the row it guessed at, which is worse than reporting nothing.
        return None
    boxes = found_rows[0]
    return {"previous": boxes[0], "play": boxes[1], "next": boxes[2]}


def inside_window(box, window):
    """Whether a box is drawn inside the window, in the window's own pixels.

    Both are in the capture's frame - the probe reports node bounds in screen pixels, the same ones
    `screencap` writes (see `probe_nodes`) - so each edge is compared with the edge it belongs to: x
    with the width, y with the height. It used to compare both of a box's axes against the window's
    *larger* side, which on a portrait window let a row drawn past the right edge pass unnamed,
    because x and y arrive swapped in the report and neither number could be trusted to be the one
    the window's width wanted.
    """
    return box[0] >= 0 and box[1] >= 0 and box[2] <= window[0] and box[3] <= window[1]


def check_close_button(found, window, density):
    """Whether the player's close control survived the header row, or the reason it did not.

    It is the last child of a `SpaceBetween` row whose first child is a fixed-width label, so a
    column narrower than the two together shrinks the *button* - to nothing, at the widths a
    half-screen landscape window gives - while the label keeps its size. That is the same failure as
    the transport's, in the other row that has a fixed-size control beside a measured one.
    """
    box = None
    for candidate, text, clickable in found:
        if clickable and text == PLAYER_CLOSE:
            box = candidate
    if box is None:
        return None, "the player's close control is not in the tree at all"
    width, height = (box[2] - box[0]) / density, (box[3] - box[1]) / density
    if abs(width - height) > SQUARE_TOLERANCE_DP:
        return None, (
            f"the close control is {width:.1f}x{height:.1f}dp - squared off by the row it shares "
            "with the NOW PLAYING label"
        )
    if width < 24:
        return None, f"the close control is {width:.1f}dp - too small to be tapped"
    if window is not None and not inside_window(box, window):
        return None, f"the close control is drawn outside the window ({box})"
    return f"close {width:.1f}dp square", None


def check_transport(found, window, density):
    """Whether the transport row is the row it is drawn as, or the reason it is not.

    The three assertions are the ones the failure this exists for broke: every button a circle, the
    two side buttons the same size, the play button the biggest, and all of it on screen. A `Row`
    that outgrows its column does not clip or overlap - it hands the *last* child what is left, so
    on a device it reads as a next button smaller than the previous one, which is only visible if
    something states the sizes side by side.

    `window` is the probe's own window size in px, used only to say whether the row is on screen:
    it is the same frame the boxes below are in, so the comparison is an edge-to-edge one (see
    `inside_window`).
    """
    labelled = named_transport(found)
    source = "by name"
    if labelled is None:
        labelled = geometric_transport(found, density)
        source = "by geometry (the tree does not name all three transport buttons)"
    if labelled is None:
        return None, "the transport row could not be found in the tree"

    sizes = {}
    for name in ("previous", "play", "next"):
        box = labelled[name]
        sizes[name] = ((box[2] - box[0]) / density, (box[3] - box[1]) / density, box)

    for name, (width, height, _box) in sizes.items():
        if abs(width - height) > SQUARE_TOLERANCE_DP:
            return None, (
                f"the {name} button is {width:.1f}x{height:.1f}dp - not the circle it is drawn as, "
                "which is what a row wider than its column does to its last child"
            )
        if width < 24:
            return None, f"the {name} button is {width:.1f}dp - too small to be a transport control"
    if abs(sizes["previous"][0] - sizes["next"][0]) > SIDE_TOLERANCE_DP:
        return None, (
            f"previous is {sizes['previous'][0]:.1f}dp and next is {sizes['next'][0]:.1f}dp - the "
            "two side buttons are drawn from one size and must not come out different"
        )
    if sizes["play"][0] < sizes["previous"][0]:
        return None, (
            f"play is {sizes['play'][0]:.1f}dp against previous's {sizes['previous'][0]:.1f}dp - "
            "the play button is the biggest of the three by design"
        )
    if window is not None:
        for name, (_width, _height, box) in sizes.items():
            if not inside_window(box, window):
                return None, (
                    f"the {name} button is drawn outside the {window[0]}x{window[1]}px window ({box})"
                )

    return (
        f"transport {sizes['previous'][0]:.1f}/{sizes['play'][0]:.1f}/{sizes['next'][0]:.1f}dp square "
        f"({source})"
    ), None


# The action row's own labels, from R.string.share, R.string.liked (hush_strings), 
# R.string.action_download, R.string.shuffle, R.string.repeat_mode_all and R.string.queue. The first
# four are drawn whatever the track is; Download is not drawn for local media and Queue only where
# the layout has room for it, so those two are read but not required.
ACTION_ALWAYS = ("Share", "Liked", "Shuffle", "Repeat queue")
ACTION_OPTIONAL = ("Download", "Queue")
ACTION_LABELS = ACTION_ALWAYS + ACTION_OPTIONAL

# The smallest an action button may be drawn. The row's own floor is 16dp - the same number the
# landscape action row yields to - and this is the check that holds it.
ACTION_MIN_DP = 16.0


def action_row(found, window, density):
    """The action row's buttons by name, or None when they are not drawn as one row.

    Candidates are grouped by their centre along one axis, the way the transport's are: the row's
    buttons share their line, so the group holding all four of the row's own labels is the row.
    Grouping is also what keeps this off the *mini* player's controls, which carry some of the same
    labels but never all four.

    Both axes are tried for where the line runs, and a group is accepted only when it holds all four
    of the row's own labels *and* its buttons sit side by side along the other axis. The second axis
    is not a second guess at which row this is - it is the same line read the other way round - and
    the side-by-side rule is what keeps a genuine vertical stack of the same four labels (no layout
    here draws one, but a sheet could) from being read as a row.
    """
    if window is None:
        return None
    ceiling = max(window)
    candidates = [
        (box, text)
        for box, text, clickable in found
        if clickable
        and text in ACTION_LABELS
        and min(box[0], box[1]) >= 0
        and max(box[2], box[3]) <= ceiling
    ]
    # (y1, y2) then (x1, x2): the buttons of one row share their centre along one axis, and sit
    # side by side along the other.
    for line_axis, side_axis in (((1, 3), (0, 2)), ((0, 2), (1, 3))):
        lines = []
        for box, text in candidates:
            centre = (box[line_axis[0]] + box[line_axis[1]]) / 2
            for line in lines:
                if abs(centre - line[0]) <= 3 * density:
                    line[1].setdefault(text, box)
                    break
            else:
                lines.append([centre, {text: box}])
        best = None
        for _centre, labelled in lines:
            if not all(name in labelled for name in ACTION_ALWAYS):
                continue
            # Side by side, or a column: sorted along the other axis, no two may overlap.
            ordered = sorted(
                labelled.values(), key=lambda box: box[side_axis[0]]
            )
            if any(
                box[side_axis[0]] < previous[side_axis[1]]
                for previous, box in zip(ordered, ordered[1:])
            ):
                continue
            if best is None or len(labelled) > len(best):
                best = labelled
        if best is not None:
            return best
    return None


def check_action_row(found, window, density):
    """Whether the row of action buttons under the transport is a row of buttons a thumb can hit.

    The transport has its own check; this is the row beneath it, and it needed one because it fails
    in a different way. The transport answers a stack that is out of room by *shrinking* to whatever
    height is left, while the action row is the last fixed child of that stack and is simply handed
    the remainder - so it is laid out at nothing at all, silently, with every other check in this
    file passing. Measured on device at the default font scale, Share/Liked/Download/Shuffle/Repeat:
    44x44dp each at a 360x804dp window, **0x0dp at 280x380dp**.
    """
    labelled = action_row(found, window, density)
    if labelled is None:
        return None, (
            "the action row could not be found in the tree - "
            f"{', '.join(ACTION_ALWAYS)} are not drawn as one row at this size"
        )
    measured = {}
    for name, box in labelled.items():
        width = (box[2] - box[0]) / density
        height = (box[3] - box[1]) / density
        if width < ACTION_MIN_DP or height < ACTION_MIN_DP:
            return None, (
                f"the {name} action is {width:.1f}x{height:.1f}dp - too small to be the button it is "
                "drawn as. The action row is the last fixed row in the column, so a stack that is "
                "over budget lays it out at nothing (measured: 0x0dp at a 280x380dp window against "
                "44x44dp at 360x804dp, both at the default font scale)"
            )
        if abs(width - height) > SQUARE_TOLERANCE_DP:
            return None, (
                f"the {name} action is {width:.1f}x{height:.1f}dp - not the square it is drawn as"
            )
        measured[name] = width
    summary = "/".join(f"{measured[name]:.0f}" for name in ACTION_ALWAYS if name in measured)
    return f"action row {summary}dp square (by name)", None


NOTICE_DISMISS = "Not now"


def dismiss_launch_notice(device, xml):
    """Tap the app's own launch notice away and re-read the tree, if it is up.

    It is the SpotiFLAC session card, anchored to the bottom of the window at launch, and it is
    drawn *over* the bottom of the player - the provenance strip and the transport row. Measured
    with it up at an 804x360dp window: the strip's left end read 6.5dp inside the action row, and
    previous/next were absent from the tree altogether while the card covered them. A person
    dismisses the card before studying the player; a measurement that does not is reporting the card
    it measured through, so this does the same tap.
    """
    for _ in range(2):
        if not xml or NOTICE_DISMISS not in xml:
            return xml
        target = None
        for box, text, desc, _clickable in nodes(xml):
            if text == NOTICE_DISMISS or desc == NOTICE_DISMISS:
                target = box
        if target is None:
            return xml
        device.shell(f"input tap {(target[0] + target[2]) // 2} {(target[1] + target[3]) // 2}")
        time.sleep(2)
        xml = device.dump()
    return xml


def mini_player_row(xml):
    """The mini player's own row, or None.

    A wide, clickable row in the bottom half of the window. Wide is the whole rule: that is what
    tells the pill apart from everything else that lives down there - the navigation bar's icons, a
    genre chip, the launch notice's two buttons. Measured on a cold start at a 450x360dp window with
    no mini player yet (the queue had not come back), where the rule this replaced - "the widest
    clickable row in the bottom 40%" - tapped the navigation bar and the size was reported
    unreachable for a layout that is fine.

    The bottom half is read from the box's *centre*, not its top edge. The pill is published as one
    clickable container that reaches from its own top down to the window's bottom, so its top edge
    sits above the halfway line: measured at a 560x300dp window at font scale 2.0 it came back
    `(0,552)-(2240,1200)`, a top at 46% of the height, and a rule wanting the *top* past the halfway
    line threw the one row it was looking for away and called a fine layout unreachable. The width
    floor is still what separates it from the navigation bar, whose own controls came back 493px
    against a 1232px floor.
    """
    found = nodes(xml)
    frame = dump_frame(xml)
    if not found or not frame:
        return None
    bottom = max(box[3] for box, _t, _c, _k in found)
    best = None
    for box, _text, _desc, clickable in found:
        if not clickable or (box[1] + box[3]) / 2 < bottom * 0.5:
            continue
        if box[2] - box[0] < frame[0] * 0.55:
            continue
        if best is None or (box[2] - box[0]) > (best[2] - best[0]):
            best = box
    return best


def reach_player(device, tries=6):
    """Home -> tap the mini player -> expanded. Returns the tree, expanded or not."""
    device.shell("input keyevent KEYCODE_WAKEUP")
    device.shell("wm dismiss-keyguard")
    device.shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(4)
    xml = None
    for _ in range(tries):
        xml = device.dump()
        # The launch notice is dismissed *before* the mini player is looked for. It is anchored to
        # the bottom of the window, which is exactly where the mini player is, so with it up the tap
        # that should expand the player lands on the card instead - on a cold start at a 450x360dp
        # window every one of three attempts tapped the card and the size was reported unreachable,
        # while the same size reached the player fine on a warm app (where the card's dismissal,
        # which lasts for the process, had already been made by an earlier step).
        xml = dismiss_launch_notice(device, xml)
        if expanded(xml):
            return xml
        box = mini_player_row(xml)
        if box is None:
            # Nothing that looks like the mini player yet: a cold start brings the queue back a
            # beat later. Waiting is the answer - pressing at whatever else is down there opens a
            # tab or a card and reports the size unreachable while its layout is fine.
            time.sleep(2.0)
            continue
        # Tap its left fifth: the right-hand end of that row is where its sleep-timer and overflow
        # controls sit, and those open a sheet instead of the player. A quarter of the way down
        # rather than the row's middle, because the container the pill is published as is taller
        # than the pill: `(0,552)-(2240,1200)` at a 560x300dp window at font scale 2.0 against a pill
        # that ends at 872, so its middle is *below* the pill, and a quarter of the way down is
        # inside the pill on every window - the pill's own seek bar sits at its bottom, so this stays
        # clear of a tap that would seek instead of expand.
        device.shell(
            f"input tap {box[0] + (box[2] - box[0]) // 5} {box[1] + (box[3] - box[1]) // 4}"
        )
        time.sleep(2.5)
        xml = device.dump()
    return xml


def remeasure_settled(device, shot, run_measure, attempts=2):
    """Measure again off a settled frame, when the measurer had *nothing* to say about one.

    A still taken while the layout was still arriving is a frame the rims cannot be read off, and
    "nothing to say" is not a result: the layout may be perfectly aligned. Each attempt re-asserts
    the rotation this window wants and gives the layout a beat before the capture, so the frame
    measured is the settled one rather than whichever one the previous step happened to land on. A
    verdict of *misaligned* is a result and never reaches here - only silence does - so this cannot
    turn a real failure into a pass; a strip that really is missing has nothing to say on every
    attempt and is still reported as the failure it is.
    """
    code, verdict = None, None
    for _ in range(attempts):
        device.rotate(device.rotation)
        time.sleep(1.5)
        device.capture(shot)
        code, verdict = run_measure()
        if code == 0 or "MISALIGNED" in verdict:
            return code, verdict
    return code, verdict


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
    parser.add_argument(
        "--font-scales",
        help="comma separated font scales to sweep, e.g. 1.0,1.5,2.0; default is the device's own",
    )
    parser.add_argument("--keep-rotation", action="store_true")
    parser.add_argument(
        "--restore-device",
        action="store_true",
        help="put the window size and the font scale back and exit, without sweeping; for a device "
        "a killed run left at one of this matrix's windows or at one of its swept font scales",
    )
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
    if args.restore_device:
        # For a phone that is already stuck, so the repair does not cost a full run; the sweep does
        # the same thing for itself at startup below. Both knobs a killed run can leave behind are
        # cleared - the size and the font scale are equally the harness's to put back, and a stuck
        # font scale is the one that is easy to mistake for a phone that has always looked like
        # that. The rotation is left alone: nothing but a sweep sets it, so it is not a leftover.
        clear_leftover_override(device)
        clear_leftover_font_scale(device)
        print(f"aura-strip-size-sweep: device on exit: {device_state_line(device)}")
        return 0

    clear_leftover_override(device)
    # Before the scale is read back as "the one it was found at": a leftover adopted at startup is
    # a leftover that survives every later run. See `clear_leftover_font_scale`.
    clear_leftover_font_scale(device)
    restore_size = "wm size reset"
    was_accel = device.shell("settings get system accelerometer_rotation") or "1"
    was_rotation = device.shell("settings get system user_rotation") or "0"

    density = args.density or device.read_density()
    if not density:
        return fail("could not read wm density; pass --density")
    device.px_per_dp = density

    was_font_scale = device.read_font_scale()
    if args.font_scales is not None:
        scales = []
        for spec in args.font_scales.split(","):
            spec = spec.strip()
            if not spec:
                continue
            try:
                scales.append(float(spec))
            except ValueError:
                return fail(f"--font-scales wants numbers, not {spec!r}")
        if not scales:
            return fail("--font-scales selected nothing")
    else:
        # The device's own scale, whatever the person running this has set: the axis exists to be
        # swept explicitly, and a default that quietly overrode their setting would be a worse
        # default than the one the matrix already has.
        scales = [was_font_scale if was_font_scale is not None else 1.0]
    swept = len(scales) > 1

    os.makedirs(args.out, exist_ok=True)
    failures = []
    total = len(sizes) * len(scales)
    scale_note = ", ".join(f"f{scale:.1f}" for scale in scales)
    print(f"aura-strip-size-sweep: {len(sizes)} window sizes x {len(scales)} font {scale_note}, {density}px/dp\n")
    # Every window at every scale, in one flat list: the loop body below is already the length it
    # is, and nesting the sizes inside the scales would indent all of it a level further for no
    # gain. The scale is applied when it changes, which is once per pass over the sizes.
    work = [
        (scale, width_dp, height_dp) for scale in scales for width_dp, height_dp in sizes
    ]
    applied_scale = None
    # A killed run is what left the device at a sweep window in the first place, so the ordinary ways
    # of stopping one - Ctrl-C, `kill`, a terminal that closes - have to run the same restore the end
    # of a run does. The `finally` below already does it; this is what makes the interpreter reach it
    # instead of dying on the spot. SIGKILL still cannot be caught, which is why the startup repair
    # above exists rather than being a nicety.
    def stop_on_signal(signum, _frame):
        raise SystemExit(128 + signum)

    for stopping in (signal.SIGINT, signal.SIGTERM):
        signal.signal(stopping, stop_on_signal)
    try:
        for font_scale, width_dp, height_dp in work:
            if applied_scale is None or abs(font_scale - applied_scale) > 0.001:
                if was_font_scale is None or abs(font_scale - was_font_scale) > 0.001:
                    device.set_font_scale(font_scale)
                applied_scale = font_scale
            label = f"{width_dp}x{height_dp}" + (f" f{font_scale:.1f}" if swept else "")
            shot_name = (
                f"aura-{width_dp}x{height_dp}dp"
                + (f"-f{font_scale:.1f}" if swept else "")
                + ".png"
            )
            landscape = device.set_window(width_dp, height_dp)
            xml = reach_player(device)
            if not expanded(xml):
                print(f"  {label:>9}  SKIP - could not reach the expanded player")
                failures.append((label, "unreachable"))
                continue

            # The transport row comes from the app's own in-process report (see
            # Device.semantics_report), the strip and the overflow check from the dump. The tag is
            # the label with its spaces taken out: it is one word in an `am broadcast` command line,
            # and a space in it splits the argument in two - the broadcast then runs with a truncated
            # label and no report is ever written, which reads as "the probe did not answer" at
            # every size in the run.
            tag = f"sweep-{width_dp}x{height_dp}" + (f"-f{font_scale:g}" if swept else "")
            report = device.semantics_report(tag)
            if report is None:
                print(f"  {label:>9}  SKIP - the debug build did not answer the semantics probe")
                failures.append((label, "no probe report"))
                continue
            def read_rows(source):
                """The three labelled rows as (transport, close, actions), each with its problem."""
                probe = probe_nodes(source)
                window = probe_window(source)
                return (
                    *check_transport(probe, window, density),
                    *check_close_button(probe, window, density),
                    *check_action_row(probe, window, density),
                )

            transport, transport_problem, close, close_problem, actions, action_problem = read_rows(
                report
            )
            # A row that is not in the tree *yet* is not a row that is not drawn: the sheet is still
            # arriving on the frames right after `reach_player` returns. Measured at a 360x804dp
            # window: the action row was absent from the report taken straight after the player was
            # reached, and 44x44dp a button from one taken two seconds later, with nothing in the app
            # changing in between. So a row that is *missing* is read again off a settled report, and
            # only a miss that survives that is a miss - the same rule the measurer's own silence
            # gets (see `remeasure_settled`). A row that was *found* wrong is a result either way
            # and never reaches here.
            if any(
                problem and "could not be found" in problem
                for problem in (transport_problem, close_problem, action_problem)
            ):
                for _ in range(2):
                    time.sleep(1.5)
                    settled = device.semantics_report(f"{tag}-settled")
                    if settled is None:
                        break
                    rows = read_rows(settled)
                    if not any(
                        problem and "could not be found" in problem
                        for problem in (rows[1], rows[3], rows[5])
                    ):
                        report = settled
                        transport, transport_problem = rows[0], rows[1]
                        close, close_problem = rows[2], rows[3]
                        actions, action_problem = rows[4], rows[5]
                        break

            # Settle, then read the tree, the probe and the pixels from the *same* settled frame.
            # Read back when the player was reached, a short window's report could still carry the
            # previous frame's boxes, and `probe_strip_rows` would then pin the measurer to rows the
            # pill was never on: measured at a 560x300dp window - a size whose line is drawn under
            # the action row the whole time - the report named it ~370px below the capture's own
            # window and the measurer reported the strip missing, on a run whose other two font
            # scales read that same size fine. Re-reading here is what makes the rows and the pixels
            # the same frame, so a size can no longer be called broken for having been photographed
            # mid-arrival.
            time.sleep(1.2)
            device.rotate(device.rotation)
            xml = device.dump() or xml
            report = device.semantics_report(f"{tag}-capture") or report
            # Read off the same settled report the rows and the pixels are: a label's fit is a fact
            # about the composition, and a reading taken mid-arrival is a reading of the frame
            # before the one being measured.
            text_problem = clipped_problem(report)
            text_note = text_fit_summary(report)

            # The report is kept beside the capture: it is the only record of the rows this window
            # was measured against (`--strip-y`, the transport's sizes), and without it a failure in
            # the run can only be re-measured by driving the device to that window again. Written
            # after the settle above, so it is the same tree the capture is measured with.
            try:
                with open(os.path.join(args.out, f"{tag}.probe.txt"), "w") as handle:
                    handle.write(report)
            except OSError:
                pass

            shot = os.path.join(args.out, shot_name)
            device.capture(shot)
            strip = codec_row(xml)
            strip_rows = None
            if strip is None:
                # The dump is the first source for the line's rows, and the app's own tree is the
                # second - see `probe_strip_rows` for the window and font scale at which the dump
                # carried no node with the line's text while the line was drawn and the tree named
                # it. A line that neither source has is the failure it always was.
                strip_rows = probe_strip_rows(report, png_size(shot), density)
            if strip is None and strip_rows is None:
                print(f"  {label:>9}  FAIL - the provenance line is not drawn")
                failures.append((label, "strip missing"))
                continue

            found = nodes(xml)
            # The dump's own root is the window the app was given, so a node outside it is a node
            # the layout drew past its bounds - the one failure every size here is exposed to, in
            # both orientations. In landscape this is the check the geometry ones cannot make: the
            # transport can be square and symmetric while the row *below* it - the action row, the
            # title, the time labels - runs off an edge, and only the bounds say so.
            root = found[0][0] if found else (0, 0, 0, 0)
            outside = [
                box
                for box, _t, _c, _k in found
                if box[2] > root[2] or box[3] > root[3] or box[0] < root[0]
            ]
            outside_problem = (
                f"{len(outside)} node(s) outside the window: "
                + ", ".join(
                    f"{box} = ({box[0] / density:.0f},{box[1] / density:.0f})"
                    f"..({box[2] / density:.0f},{box[3] / density:.0f})dp"
                    for box in outside[:4]
                )
                if outside
                else None
            )
            if not landscape:
                problems = []
                if outside_problem:
                    problems.append(outside_problem)
                if transport_problem:
                    problems.append(transport_problem)
                if action_problem:
                    problems.append(action_problem)
                if close_problem:
                    problems.append(close_problem)
                if text_problem:
                    problems.append(text_problem)
                if problems:
                    print(f"  {label:>9}  FAIL - {'; '.join(problems)}")
                    failures.append((label, "; ".join(problems)))
                else:
                    print(
                        f"  {label:>9}  OK (portrait) - {transport}; {actions}; {close}; {text_note}; "
                        f"strip and rows all inside "
                        f"{root[2] / density:.0f}x{root[3] / density:.0f}dp"
                    )
                continue

            # No --x0: the measurer's own scan window starts at 56% of the width, and where the
            # pill begins depends on the *layout*, not on the width - beside a navigation rail the
            # controls column starts right of that guess, across the whole window (a route with no
            # rail, a folded or desktop window) it starts at ~51% and the guess cuts the pill's left
            # end off. This harness used to compensate with a width test (0.35x under 700dp), which
            # is a proxy that reads the wrong variable: it sent a 960x540dp window into 56% and a
            # 600x360dp one into 0.35x, while both are full-width. The measurer can see whether the
            # run it found was cut by the window and searches wider for itself when it was, so the
            # window belongs there, with the pixels.
            command = [
                sys.executable, MEASURE, "--shot", shot,
                "--density", str(density), "--tolerance-dp", str(args.tolerance_dp),
                "--strip-y",
                f"{strip[1] - 8},{strip[3] + 8}" if strip is not None else f"{strip_rows[0]},{strip_rows[1]}",
            ]
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
            # the measurer reads rims off a still capture, so a frame taken while the layout was
            # still settling can leave it unable to find the row at all - which is a different fact
            # from the layout being wrong, and it is worth a settled re-measure before calling it
            # either way. Measured in a full matrix run: 560x300dp came back unreadable twice in a
            # row - the strip is drawn (both frames hold it, pixel for pixel, against the same size
            # measured a moment earlier) and the row is the one it should be - so a failure here is
            # still counted as one only when the settled attempts have nothing to say either.
            if code != 0 and "MISALIGNED" not in verdict:
                code, verdict = remeasure_settled(device, shot, run_measure)
                if code != 0 and "MISALIGNED" not in verdict:
                    verdict = f"could not be measured ({verdict})"
            if (
                code == 0
                and transport_problem is None
                and close_problem is None
                and action_problem is None
                and text_problem is None
            ):
                print(f"  {label:>9}  PASS - {verdict}; {transport}; {actions}; {close}; {text_note}")
            else:
                problems = [
                    part
                    for part in (
                        outside_problem,
                        transport_problem,
                        action_problem,
                        close_problem,
                        text_problem,
                        None if code == 0 else verdict,
                    )
                    if part
                ]
                print(f"  {label:>9}  FAIL - {'; '.join(problems)}")
                failures.append((label, "; ".join(problems)))
            sys.stdout.flush()
    finally:
        if not args.keep_rotation:
            device.shell(restore_size)
            device.shell(f"settings put system accelerometer_rotation {was_accel}")
            device.shell(f"settings put system user_rotation {was_rotation}")
            device.shell("rm -f /sdcard/sweep.xml")
            # Said out loud: the phone's size and font scale are this harness's device-wide side
            # effects, and a run that ends silently leaves no way to tell a restored device from a
            # stuck one without asking it. See `clear_leftover_override` and
            # `clear_leftover_font_scale` for what a stuck one looks like.
            print(f"aura-strip-size-sweep: device on exit: {device_state_line(device)}")
        # The font scale is put back whether or not the rotation is left alone: it is a device-wide
        # accessibility setting, and a harness that left the phone on 2.0 would be handing the next
        # person a phone whose text is not what they chose.
        if was_font_scale is not None:
            device.shell(f"settings put system font_scale {was_font_scale:g}")
            device.shell(f"am force-stop {PKG}")

    print()
    if failures:
        for label, why in failures:
            print(f"aura-strip-size-sweep: {label} FAILED - {why}")
        print(f"aura-strip-size-sweep: {len(failures)} of {total} windows did not hold")
        return 1
    print(f"aura-strip-size-sweep: all {total} windows hold ({args.out})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
