#!/usr/bin/env bash
#
# Compose semantics probe (debug builds only).
#
# The property this exists to settle: on a OnePlus running Android 12, `uiautomator` sees the mini
# player and the bottom bars of Hush and NO node for the Home, Search or Library tab - not even the
# `scrollable` node a LazyColumn always carries - while those screens render and respond to taps.
# Everything behind those tabs, Settings included, is therefore invisible to anything outside the app,
# which is why scripted checks of them silently do nothing instead of failing.
#
# Two opposite faults produce that symptom:
#
#   the content carries no semantics at all            -> an app-side fault, fixable here
#   it carries them and they never reach the bridge    -> a platform-side one, not this app's code
#
# Nothing that reads the accessibility bridge can tell them apart, because the bridge is the thing in
# question. So the probe reads Compose's own semantics tree inside the app's process, where the merged
# root IS the tree an accessibility service reads and the unmerged root shows what merging removed.
# See app/src/debug/kotlin/app/hush/music/debug/SemanticsProbeReceiver.kt.
#
# Tabs are driven by the debug build's `navigate_to` intent extra, so no tap land on a guessed
# coordinate. Each tab is probed in turn and reported as:
#
#   PRESENT       content nodes are in the merged tree; an accessibility service can read them
#   MERGED_AWAY   content nodes exist but merging removed all of them
#   ABSENT        no content nodes at all: the screen describes nothing
#
# Exit codes: 0 every tab reached a verdict, 1 a probe failed, 2 the run could not be decided (no
# device, no debug build, no report) - a run that decided nothing is never reported as a pass.
#
# Usage:
#   scripts/semantics-probe.sh [--tabs home,search] [--boundary 2604] [--force-a11y] [--serial <adb-serial>]
#                               [--verbose]
#
# --force-a11y turns Compose's own test hook on first, so the platform tree is built whether or not
# a screen reader is attached - which removes "the bridge had not been told to work" from the list of
# explanations for a screen that is visible to a person and absent to a service.

set -euo pipefail

ACTION="app.hush.music.action.SEMANTICS_PROBE"
RECEIVER_SUFFIX="app.hush.music.debug.SemanticsProbeReceiver"
ACTIVITY_SUFFIX="app.hush.music.MainActivity"
REPORT_PATH="files/debug/semantics-probe.txt"

TABS="home,search"
BOUNDARY=""
SERIAL="${ANDROID_SERIAL:-}"
VERBOSE=0
FORCE_A11Y=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tabs) TABS="${2:-}"; shift 2 ;;
        --boundary) BOUNDARY="${2:-}"; shift 2 ;;
        --serial) SERIAL="${2:-}"; shift 2 ;;
        --force-a11y) FORCE_A11Y=1; shift ;;
        --verbose) VERBOSE=1; shift ;;
        -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

adb_cmd() {
    if [[ -n "$SERIAL" ]]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

log() { printf '%s\n' "$*"; }
dbg() { [[ "$VERBOSE" == "1" ]] && printf '  [dbg] %s\n' "$*" >&2; return 0; }

# ── Device and package ────────────────────────────────────────────────────────

if ! adb_cmd get-state >/dev/null 2>&1; then
    log "No device: connect one (or pass --serial)."
    exit 2
fi

PACKAGE="app.hush.music.debug"
if ! adb_cmd shell pm path "$PACKAGE" >/dev/null 2>&1; then
    log "The debug build is not installed ($PACKAGE); the probe's receiver ships in no release."
    exit 2
fi

MODEL="$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
SDK="$(adb_cmd shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
log "device=$MODEL android=$SDK package=$PACKAGE"

# The screen height is read from the device because the boundary between the content area and the
# player/bottom bars is a position, not a subtree: the bars are drawn over the content inside one
# Compose view. The default puts the line where those bars begin on every layout Hush ships; it is
# reported with every verdict, so a wrong boundary shows up in the bands rather than in the answer.
if [[ -z "$BOUNDARY" ]]; then
    SCREEN="$(adb_cmd shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1x\2/p' | tail -1)"
    HEIGHT="${SCREEN##*x}"
    if [[ -z "$HEIGHT" ]]; then
        log "Could not read the screen size from the device (wm size)."
        exit 2
    fi
    BOUNDARY=$(( HEIGHT * 81 / 100 ))
fi
log "boundary=$BOUNDARY (a node above this line counts as the screen's content)"

RECEIVER="$PACKAGE/$RECEIVER_SUFFIX"
ACTIVITY="$PACKAGE/$ACTIVITY_SUFFIX"

# ── Helpers ───────────────────────────────────────────────────────────────────

# A whole run has to be discarded before each probe: the report is a file, and a probe that never
# ran would otherwise be read as the previous tab's answer.
clear_report() { adb_cmd shell run-as "$PACKAGE" rm -f "$REPORT_PATH" >/dev/null 2>&1 || true; }

read_report() { adb_cmd shell run-as "$PACKAGE" cat "$REPORT_PATH" 2>/dev/null | tr -d '\r' || true; }

show_tab() {
    local route="$1"
    adb_cmd shell am start -n "$ACTIVITY" --es navigate_to "$route" >/dev/null 2>&1 || true
    # The composition has to exist before it can describe itself: the probe reads the live tree, and
    # a tab mid-transition would report the screen it is leaving.
    sleep 2
}

probe() {
    local label="$1"
    local extra=()
    [[ "$FORCE_A11Y" == "1" ]] && extra=(--ez force-a11y true)
    adb_cmd shell am broadcast -n "$RECEIVER" -a "$ACTION" \
        --es label "$label" --ei content-bottom "$BOUNDARY" "${extra[@]}" >/dev/null 2>&1 || true
    local waited=0
    while (( waited < 20 )); do
        if read_report | grep -q 'semantics-probe step=content'; then return 0; fi
        sleep 1
        (( waited++ )) || true
    done
    return 1
}

# ── Run ───────────────────────────────────────────────────────────────────────

log ""
PASS=0
FAIL=0
UNDECIDED=0
SUMMARY=""

IFS=',' read -r -a TAB_LIST <<<"$TABS"
for TAB in "${TAB_LIST[@]}"; do
    [[ -z "$TAB" ]] && continue
    log "── $TAB"

    show_tab "$TAB"
    clear_report

    if ! probe "$TAB"; then
        log "  SKIP  the probe left no report (is the app in the foreground?)"
        UNDECIDED=$((UNDECIDED + 1))
        SUMMARY="$SUMMARY$TAB: SKIPPED"$'\n'
        continue
    fi

    REPORT="$(read_report)"
    VERDICT="$(sed -n 's/.*semantics-probe step=content label=[^ ]* verdict=\([^ ]*\).*/\1/p' <<<"$REPORT" | tail -1)"
    VERDICT_LINE="$(grep 'semantics-probe step=content' <<<"$REPORT" | tail -1)"
    MEANING="$(sed -n 's/^meaning=//p' <<<"$REPORT" | tail -1)"

    grep -E '^(window|nodes:|content \(|bars \(|.* y bands:|host:|why|  id=|    ancestry=|provider:|  provider )' <<<"$REPORT" | sed 's/^/  /' || true
    log "  $VERDICT_LINE"
    [[ -n "$MEANING" ]] && log "  meaning: $MEANING"
    # What the content actually says, unless --verbose asks for the bar nodes too: the point of the
    # sample is to show the content a reader cannot otherwise see, not to dump a screen.
    if [[ "$VERBOSE" == "1" ]]; then
        sed -n '/sample content nodes/,/sample bar nodes/p' <<<"$REPORT" | sed 's/^/  /' || true
    else
        sed -n '/sample content nodes/,/sample bar nodes/p' <<<"$REPORT" | sed '1d;$d' | head -n 8 | sed 's/^/  /' || true
    fi

    case "$VERDICT" in
        PRESENT|MERGED_AWAY|ABSENT) PASS=$((PASS + 1)) ;;
        SKIPPED) UNDECIDED=$((UNDECIDED + 1)) ;;
        *) FAIL=$((FAIL + 1)) ;;
    esac
    SUMMARY="$SUMMARY$TAB: $VERDICT"$'\n'
done

log ""
log "Summary"
printf '%s' "$SUMMARY" | sed 's/^/  /'
log "  $PASS decided, $FAIL failed, $UNDECIDED undecided"
log ""
log "PRESENT means the app is not the fault: the content describes itself inside the process, so"
log "whatever cannot see it is reading the accessibility bridge. ABSENT means it never described"
log "itself, which is fixable here. MERGED_AWAY sits between the two."

if (( FAIL > 0 )); then exit 1; fi
if (( UNDECIDED > 0 )); then exit 2; fi
exit 0
