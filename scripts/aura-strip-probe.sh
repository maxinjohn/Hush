#!/usr/bin/env bash
# Hush (2026) — GPL-3.0. Contributors: see git history.
#
# Capture the landscape Aura player and assert its provenance strip still spans the action row.
#
# `aura-strip-align.py` does the measuring; this is the part that has to touch the device: pin it to
# landscape, take a capture, and put the rotation back the way it was found. It is deliberately not
# a UI driver - reaching the expanded player is a tap on the mini player whose position depends on
# what is already on screen - so it captures what is there and says plainly when the strip is not on
# screen, rather than pressing at things until something happens.
#
# Two details are not left to the caller. The rotation, because the measurement refuses a portrait
# capture (the strip only exists in landscape) and because a device left rotated is a device the
# next person's screenshots are wrong on, so the harness restores the previous settings even when it
# fails. And the density, because every number the measurement reports is in dp: it is read from
# `wm density` rather than assumed, so the tool is right on a device at a different scale instead of
# reporting spans off by the ratio.
#
# usage:
#   scripts/aura-strip-probe.sh
#   scripts/aura-strip-probe.sh --tolerance-dp 1.5
#   scripts/aura-strip-probe.sh --from .freebuff/aura-strip/shot.png     # measure an existing capture
#   scripts/aura-strip-probe.sh --serial eea86cae
#
#   --from FILE      measure FILE instead of capturing; needs no device
#   --out DIR        where captures and the measurement log go; default .freebuff/aura-strip
#   --tolerance-dp   how far the two spans may disagree; default 1.0
#   --density PX_PER_DP   read from `wm density` when capturing; pass it only when the capture came
#                    from somewhere else (`--from`)
#   --serial SERIAL  the device to drive, when several are attached
#   --keep-rotation  do not restore the rotation settings afterwards
#
# Exit code: 0 when the strip and the action row agree, 1 when they do not or when the strip is not
# on screen, 2 for a usage or device error.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT=".freebuff/aura-strip"
TOLERANCE_DP="1.0"
DENSITY_DP=""
SERIAL="${ANDROID_SERIAL:-}"
FROM=""
KEEP_ROTATION="false"

while [ $# -gt 0 ]; do
    case "$1" in
        --from) FROM="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --tolerance-dp) TOLERANCE_DP="$2"; shift 2 ;;
        --density) DENSITY_DP="$2"; shift 2 ;;
        --serial) SERIAL="$2"; shift 2 ;;
        --keep-rotation) KEEP_ROTATION="true"; shift ;;
        *) echo "aura-strip-probe: unknown argument $1" >&2; exit 2 ;;
    esac
done

mkdir -p "$OUT"
SHOT="$OUT/aura-strip.png"

# An existing capture means no device to read a density off, so the caller's value (or the
# measurement's own default) is what it is measured at.
if [ -n "$FROM" ]; then
    if [ -n "$DENSITY_DP" ]; then
        python3 "$HERE/aura-strip-align.py" --shot "$FROM" --density "$DENSITY_DP" --tolerance-dp "$TOLERANCE_DP"
    else
        python3 "$HERE/aura-strip-align.py" --shot "$FROM" --tolerance-dp "$TOLERANCE_DP"
    fi
    exit $?
fi

ADB=(adb)
if [ -n "$SERIAL" ]; then
    ADB=(adb -s "$SERIAL")
fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "aura-strip-probe: no device (set ANDROID_SERIAL or pass --serial for one of several)" >&2
    exit 2
fi

# Remembered so a failure still leaves the device as it was found: a probe that rotates the screen
# and walks away changes the next measurement as much as the one it was trying to take.
WAS_ACCEL="$("${ADB[@]}" shell settings get system accelerometer_rotation | tr -d '\r')"
WAS_ROTATION="$("${ADB[@]}" shell settings get system user_rotation | tr -d '\r')"

restore() {
    if [ "$KEEP_ROTATION" = "true" ]; then
        return
    fi
    # An unset or null setting is left unset rather than written as the literal "null".
    if [ -n "$WAS_ACCEL" ] && [ "$WAS_ACCEL" != "null" ]; then
        "${ADB[@]}" shell settings put system accelerometer_rotation "$WAS_ACCEL" >/dev/null 2>&1 || true
    fi
    if [ -n "$WAS_ROTATION" ] && [ "$WAS_ROTATION" != "null" ]; then
        "${ADB[@]}" shell settings put system user_rotation "$WAS_ROTATION" >/dev/null 2>&1 || true
    fi
}
trap restore EXIT

# `wm density` prints a physical line and, when the density has been overridden, a second one - and
# it is the override the app is laid out in, so it is the one that matters. Read as a pair rather
# than as the first match, because the physical line prints first and taking it would scale every
# reported span by the ratio between the two.
DENSITY_PX="$("${ADB[@]}" shell wm density | tr -d '\r' | awk '/Override density/ {override=$3} /Physical density/ {physical=$3} END {print (override != "" ? override : physical)}')"
if [ -z "$DENSITY_PX" ] || [ "$DENSITY_PX" = "0" ]; then
    echo "aura-strip-probe: could not read wm density; pass the capture to aura-strip-align.py with --density" >&2
    exit 2
fi
if [ -z "$DENSITY_DP" ]; then
    DENSITY_DP="$(python3 -c "print(round($DENSITY_PX / 160.0, 3))")"
fi

"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"${ADB[@]}" shell settings put system accelerometer_rotation 0 >/dev/null
"${ADB[@]}" shell settings put system user_rotation 1 >/dev/null
sleep 2

"${ADB[@]}" exec-out screencap -p > "$SHOT"
echo "aura-strip-probe: captured $SHOT (density $DENSITY_PX = ${DENSITY_DP}px/dp)"

set +e
python3 "$HERE/aura-strip-align.py" \
    --shot "$SHOT" --density "$DENSITY_DP" --tolerance-dp "$TOLERANCE_DP" \
    | tee "$OUT/aura-strip.log"
STATUS="${PIPESTATUS[0]}"
set -e

if [ "$STATUS" -ne 0 ] && grep -q "could not be measured" "$OUT/aura-strip.log"; then
    echo "aura-strip-probe: open the full player on the device (tap the mini player) and run again - the strip is only drawn there." >&2
fi

exit "$STATUS"
