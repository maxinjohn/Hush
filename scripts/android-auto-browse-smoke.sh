#!/usr/bin/env bash
#
# Android Auto browse-tree smoke (debug builds only).
#
# The property this exists to settle: what a car screen is actually offered. Android Auto's library
# only exists once a head unit connects, and the screen that shows it is in the car, so on a phone
# there is nothing to look at - which is why a missing Spotify folder, a switch that does nothing or
# a track a tap cannot play all go unnoticed until someone is sitting in the vehicle.
#
# The debug build can answer that without one: its receiver connects the app's own MediaBrowser to
# its own MusicService - the same library session, the same media ids a head unit receives - and
# writes the tree to files/debug/auto-library.txt together with the switches that produced it.
# See app/src/debug/kotlin/app/hush/music/auto/AutoLibraryDebugReceiver.kt.
#
# Parents walked by default: the car's root, the Home folder, and "Mixes and radios" - the folder
# that decides whether the recommended-playlist groups (YouTube, Spotify) appear at all.
#
# Exit codes: 0 every parent answered, 1 a parent returned nothing, 2 the run could not be decided
# (no device, no debug build) - a run that decided nothing is never reported as a pass.
#
# Usage:
#   scripts/android-auto-browse-smoke.sh [--parents root,home,home_mixes_and_radios]
#                                        [--serial <adb-serial>] [--verbose]

set -euo pipefail

ACTION="app.hush.music.action.AUTO_LIBRARY_DEBUG"
RECEIVER_SUFFIX="app.hush.music.auto.AutoLibraryDebugReceiver"
REPORT_PATH="files/debug/auto-library.txt"

PARENTS="root,home,home_mixes_and_radios"
SERIAL="${ANDROID_SERIAL:-}"
VERBOSE=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --parents) PARENTS="${2:-}"; shift 2 ;;
        --serial) SERIAL="${2:-}"; shift 2 ;;
        --verbose) VERBOSE=1; shift ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

adb_cmd() {
    if [[ -n "$SERIAL" ]]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

log() { printf '%s\n' "$*"; }
dbg() { [[ "$VERBOSE" == "1" ]] && printf '  [dbg] %s\n' "$*" >&2; return 0; }

if ! adb_cmd get-state >/dev/null 2>&1; then
    log "No device: connect one (or pass --serial)."
    exit 2
fi

PACKAGE="app.hush.music.debug"
if ! adb_cmd shell pm path "$PACKAGE" >/dev/null 2>&1; then
    log "The debug build is not installed ($PACKAGE); the receiver ships in no release."
    exit 2
fi

MODEL="$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
SDK="$(adb_cmd shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
log "device=$MODEL android=$SDK package=$PACKAGE"
log ""

RECEIVER="$PACKAGE/$RECEIVER_SUFFIX"

# The report is a file, so a parent that never answered would otherwise be read as the previous
# parent's tree.
clear_report() { adb_cmd shell run-as "$PACKAGE" rm -f "$REPORT_PATH" >/dev/null 2>&1 || true; }
read_report() { adb_cmd shell run-as "$PACKAGE" cat "$REPORT_PATH" 2>/dev/null | tr -d '\r' || true; }

# The id has to be named (`-n`): an implicit broadcast to a manifest receiver is not delivered on
# Android 8+, and `am broadcast` still reports success, so the implicit form reads as "the tree was
# empty" - the wrong conclusion to draw from a probe whose job is to report what happened.
browse() {
    local parent="$1"
    clear_report
    adb_cmd shell am broadcast -n "$RECEIVER" -a "$ACTION" --es parent "$parent" >/dev/null 2>&1 || true
    local waited=0
    while (( waited < 25 )); do
        # Either answer counts as answered: a tree, or a reason why there is none.
        if read_report | grep -q "^parent=\|^browse failed"; then return 0; fi
        sleep 1
        (( waited++ )) || true
    done
    return 1
}

FAILED=0
FIRST=1
for parent in ${PARENTS//,/ }; do
    [[ -z "$parent" ]] && continue
    if ! browse "$parent"; then
        log "parent=$parent  UNANSWERED (no report after 25s)"
        FAILED=1
        FIRST=0
        continue
    fi
    report="$(read_report)"
    if [[ "$FIRST" == "1" ]]; then FIRST=0; else log ""; fi
    log "$report"
    dbg "raw report: $REPORT_PATH"
done

log ""
if (( FAILED )); then
    log "RESULT: FAIL - at least one parent produced no tree"
    exit 1
fi
log "RESULT: PASS - every parent answered"
