#!/usr/bin/env bash
# Hush (2026) — GPL-3.0. Contributors: see git history.
#
# Prove that a button's press ring is really drawn outside the button.
#
# ## Why this exists
#
# `screencap` on a phone is slow: measured on the reporting device (OnePlus NE2211), 1347ms as PNG
# and 613ms as raw pixels. A press ring lives 520ms. So a capture fired at a press is always
# finished *after* the thing being measured - and, worse, the frame it samples is decided by when in
# that 613ms the display buffer is read, which the caller cannot see. Two earlier attempts at this
# measurement failed in exactly that way and produced the wrong answer ("no ring"), which is how a
# button whose ring is clipped by a `Modifier.clip()` looks identical to a button that draws none.
#
# `screenrecord` would solve it outright and is the obvious instrument, but it is denied to the
# `shell` user on this device: `screenrecord` answers "Unable to open ...: Permission denied" for
# every path, including `/data/local/tmp`, under ColorOS's policy. So this harness lengthens the
# *capture* window instead of shortening the animation: it presses the button once per delay and
# takes a raw capture at each, which is a sweep across the ring's 520ms life rather than a bet on
# one frame. Raw rather than PNG because the encode is 700ms of the 1347 - and the sample point
# comes before it, so raw samples the screen earlier for the same delay.
#
# Because every cycle presses for real, the rest frame is taken **per cycle**, immediately before
# that cycle's press: a control whose activation changes the screen (a shuffle button starting
# playback, a menu opening) would otherwise be compared against a scene that no longer exists, and
# the difference would be measured as though the button had drawn it. A PNG rest frame also tells
# the measure step how big a frame is, so the raw captures need no size passed in.
#
# And the press is **cancelled rather than completed**: the finger goes down on the button, the
# capture is taken while it is held, and the gesture is then ended with `motionevent CANCEL`, which
# Compose reads as a cancelled gesture and dispatches no click. That is the right instrument for this
# measurement, because `PressMotion` draws the whole effect on the *press* - the ring starts on the
# way down, and the frame loop keeps it alive while the finger stays. So the ring is measurable
# without the button doing its job, and a sweep over a play button no longer starts playback four
# times. `--click` restores a real press-and-release for the rare case that is what you want.
#
# CANCEL rather than a drag clear of the button, because a drag is a *scroll* on any scrollable
# surface: the first version of this cancelled by moving the pointer away, which scrolled the list,
# changed the whole header band, and passed its own ring test. `motionevent` supports CANCEL on this
# device (`input motionevent <DOWN|UP|MOVE|CANCEL> <x> <y>`) and CANCEL is not a gesture the app can
# interpret as anything else.
#
# And each cycle is **checked against itself**: a frame is taken before the press and again after the
# press has been cancelled and the screen has settled, and the two must be the same picture
# (`press-frame-diff.py`). A cycle whose press disturbed the screen is reported as CONTAMINATED and
# thrown out, so the verdict is never built on a screenshot that moved for some other reason. That
# check is what turns "a ring was seen" into "a ring was seen on a screen that was standing still".
#
# ## What it reports
#
# For each delay: how many pixels changed *inside* the button's rest bounds (the contraction and the
# release spring - the give) and how many changed *outside* them (only a ring can be there), the
# ring's radius as a multiple of half the short side (`PressMotion.haloRadiusFraction`'s own unit,
# where 1.0 is the button's edge), and the peak colour delta. Then PASS or FAIL, with the delay that
# caught it.
#
# ## Usage
#
#   scripts/press-ring-probe.sh --rect 608,2108,832,2332 --label shuffle
#   scripts/press-ring-probe.sh --desc Shuffle --label shuffle      # rect from the a11y tree
#   scripts/press-ring-probe.sh --rect ... --delays "0 100 200 300 400 500"
#   scripts/press-ring-probe.sh --rect ... --settle 2.0                # slower, calmer cycles
#   scripts/press-ring-probe.sh --rect ... --click                     # let the action actually run
#   scripts/press-ring-probe.sh --rect ... --paint 80                  # the radius the fill covers, when
#                                                                     # the rect is a minimum touch target
#
# Delays are milliseconds *after the press*, and the sweep is what makes the measurement reliable:
# the default set covers the ring's whole life, so one of them lands inside it whatever the device's
# input latency turns out to be.
#
# ## Caveat
#
# The probe cancels every press, so the button's own action does not run; pass `--click` if you need
# it to. Either way the ring is a *press* effect, so a control that only rings on release (none in
# Hush) would not be measured by this.
#
# The settle time between cycles is what lets a screen come to rest before its stability frame is
# taken. A surface with a marquee or a moving progress bar never reports "whole-frame=0", which is why
# only changes *in and around the button* invalidate a cycle.
#
# Exit code: 0 when a ring was measured outside the button, 1 when it was not.

set -euo pipefail

PKG="${PKG:-app.hush.music.debug}"
DEVICE_DIR="/data/local/tmp/hush-ring"
OUT=".freebuff/press-ring"
RECT=""
LABEL="button"
DELAYS="0 100 200 300 400 500"
BAND="0.6"
THRESHOLD="12"
SETTLE="1.2"
CLICK="false"
PAINT=""
DESC=""

while [ $# -gt 0 ]; do
    case "$1" in
        --rect) RECT="$2"; shift 2 ;;
        --desc) DESC="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --delays) DELAYS="$2"; shift 2 ;;
        --band) BAND="$2"; shift 2 ;;
        --threshold) THRESHOLD="$2"; shift 2 ;;
        --settle) SETTLE="$2"; shift 2 ;;
        --click) CLICK="true"; shift ;;
        --paint) PAINT="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        *) echo "press-ring-probe: unknown argument $1" >&2; exit 2 ;;
    esac
done

ADB=(adb)
if [ -n "${ANDROID_SERIAL:-}" ]; then
    ADB=(adb -s "$ANDROID_SERIAL")
fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "press-ring-probe: no device (set ANDROID_SERIAL for one of several)" >&2
    exit 2
fi

mkdir -p "$OUT"

# The button's rest bounds. From the accessibility tree when asked for by description, because the
# surfaces this is aimed at are Compose and the coordinates are otherwise read off a screenshot by
# eye - which is how a probe ends up measuring the empty space next to the button.
if [ -n "$DESC" ]; then
    "${ADB[@]}" shell uiautomator dump --compressed /sdcard/ring-dump.xml >/dev/null 2>&1
    # The dump is fetched to a file rather than piped, because the reader below is a heredoc: a pipe
    # into `python3 - <<'PY'` is discarded in favour of the heredoc, and the script then reads EOF and
    # reports "nothing is described as Shuffle" on a screen that plainly shows it.
    "${ADB[@]}" shell cat /sdcard/ring-dump.xml > "$OUT/ring-dump.xml"
    RECT="$(python3 - "$OUT/ring-dump.xml" "$DESC" <<'PY'
import re, sys
path, wanted = sys.argv[1], sys.argv[2]
raw = open(path, encoding="utf-8", errors="replace").read()
for node in re.finditer(r"<node[^>]*>", raw):
    tag = node.group(0)
    desc = re.search(r'content-desc="([^"]*)"', tag)
    text = re.search(r'text="([^"]*)"', tag)
    label = (desc.group(1) if desc else "") or (text.group(1) if text else "")
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if bounds and label.strip() == wanted:
        print(",".join(bounds.groups()))
        break
PY
)"
    if [ -z "$RECT" ]; then
        echo "press-ring-probe: nothing on screen is described as '$DESC'" >&2
        exit 3
    fi
fi

if [ -z "$RECT" ]; then
    echo "press-ring-probe: pass --rect x1,y1,x2,y2 or --desc NAME" >&2
    exit 2
fi

IFS=, read -r X1 Y1 X2 Y2 <<<"$RECT"
CX=$(( (X1 + X2) / 2 ))
CY=$(( (Y1 + Y2) / 2 ))
SHORT=$(( X2 - X1 < Y2 - Y1 ? X2 - X1 : Y2 - Y1 ))

"${ADB[@]}" shell rm -rf "$DEVICE_DIR" >/dev/null 2>&1 || true
"${ADB[@]}" shell mkdir -p "$DEVICE_DIR" >/dev/null

echo "press-ring-probe: $LABEL at $RECT (centre $CX,$CY, short side ${SHORT}px), delays: ${DELAYS}ms"

# No array holds state across the sweep except this one, and every expansion of it below is guarded:
# macOS ships bash 3.2, where `set -u` makes an empty array's `${a[@]}` an error rather than nothing.
PAIRS=()
TIMING="$OUT/$LABEL-timing.txt"
REST="$OUT/$LABEL-rest.png"
: > "$TIMING"
# The gesture's ending: either a real release on the button, so the action runs, or a cancel, which
# ends the press without dispatching a click and without the app reading a drag or a scroll out of it.
if [ "$CLICK" = "true" ]; then
    release_lines="input motionevent UP $CX $CY"
else
    release_lines="input motionevent CANCEL $CX $CY"
fi

for delay in $DELAYS; do
    name="$LABEL-$delay"
    # A zero-length sleep is not a sleep: asking for it is a parse error on some builds, and asking
    # for nothing is the same thing anyway.
    sleep_ms="$(awk -v ms="$delay" 'BEGIN { printf "%.3f", ms / 1000 }')"
    if [ "$delay" = "0" ]; then
        wait_line=":"
    else
        wait_line="sleep $sleep_ms"
    fi
    # This cycle's own rest frame, taken once the previous cycle has stopped moving. `screencap -p`
    # is the slow one, but a rest frame is not being timed, and reusing one frame for the whole
    # sweep is what makes a state-changing button unmeasurable.
    [ "$SETTLE" = "0" ] || sleep "$SETTLE"
    "${ADB[@]}" exec-out screencap -p > "$OUT/$name-before.png"
    # One press per delay, with the capture starting that long after it. `date +%s%3N` around each
    # step is what makes a result diagnosable: it says how long the shell itself took to reach the
    # press, which is the part of the latency a caller cannot otherwise see.
    "${ADB[@]}" shell "
        date +%s%3N > $DEVICE_DIR/$name.ms
        input motionevent DOWN $CX $CY
        date +%s%3N >> $DEVICE_DIR/$name.ms
        $wait_line
        date +%s%3N >> $DEVICE_DIR/$name.ms
        screencap $DEVICE_DIR/$name.raw
        date +%s%3N >> $DEVICE_DIR/$name.ms
        $release_lines
        date +%s%3N >> $DEVICE_DIR/$name.ms
    " >/dev/null
    "${ADB[@]}" pull "$DEVICE_DIR/$name.raw" "$OUT/$name.raw" >/dev/null 2>&1 || true
    "${ADB[@]}" shell cat "$DEVICE_DIR/$name.ms" 2>/dev/null | tr '\n' ' ' >> "$TIMING" || true
    echo " <- $name" >> "$TIMING"

    # The control: cancel the press and let the screen settle, then take the same picture again. This
    # frame does two jobs - it reports whether the cycle disturbed anything, and it is handed to the
    # measure step as the noise frame, which drops every pixel that differs between it and the rest
    # frame. A marquee title, a progress bar and a list that scrolled are all things that move on
    # their own, and the mask is what keeps them out of the button's evidence.
    sleep "$SETTLE"
    "${ADB[@]}" exec-out screencap -p > "$OUT/$name-after.png"
    python3 "$(dirname "$0")/press-frame-diff.py" \
        "$OUT/$name-before.png" "$OUT/$name-after.png" \
        --rect "$RECT" --band "$BAND" --label "$name stability" >> "$TIMING" 2>&1 || true
    PAIRS+=("$OUT/$name-before.png:$OUT/$name.raw:$OUT/$name-after.png")
done

echo "press-ring-probe: timings (device ms: before press, press, capture start, capture end, release)"
cat "$TIMING"

# The one frame that has to survive the whole sweep: a picture of the screen before any of it, kept
# so a reader can compare two captures by hand when a result is surprising.
for pair in ${PAIRS[@]+"${PAIRS[@]}"}; do
    cp "${pair%%:*}" "$REST" 2>/dev/null || true
    break
done


pair_args=()
for pair in ${PAIRS[@]+"${PAIRS[@]}"}; do
    shot="${pair#*:}"
    shot="${shot%%:*}"
    [ -s "$shot" ] && pair_args+=(--pair "$pair")
done
if [ "${#pair_args[@]}" -eq 0 ]; then
    echo "press-ring-probe: no captures were produced" >&2
    exit 2
fi

paint_arg=()
[ -n "$PAINT" ] && paint_arg=(--paint "$PAINT")

python3 "$(dirname "$0")/press-ring-measure.py" \
    --rect "$RECT" \
    --band "$BAND" \
    --threshold "$THRESHOLD" \
    --label "$LABEL" \
    ${paint_arg[@]+"${paint_arg[@]}"} \
    "${pair_args[@]}"
