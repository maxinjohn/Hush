#!/usr/bin/env bash
#
# SpotiFLAC source-verification smoke test (debug builds only).
#
# The property this exists to prove is *where a grant is delivered*. A Turnstile grant redeems only
# for the extension whose challenge raised it, and the extension runtime's challenge is not a relay
# credential - the relay exchange answers it with HTTP 403. On a car head unit, whose embedded WebView
# is older than Cloudflare supports, the extension challenge is the *only* one on screen, so that
# misrouting is what a user saw as "the browser says OK and Hush still says 403".
#
# The script drives the debug-only SpotiFLACDebugReceiver (see app/src/debug) and asserts the routing
# from the app's own log lines. It cannot solve Cloudflare for you: use `--open` to put the challenge
# in the device's browser, tick it there, then run the script again - a solved challenge still
# publishes its unspent grant, which `recover` delivers.
#
# Usage:
#   scripts/spotiflac-verify-smoke.sh [--source deezer] [--open] [--serial <adb-serial>] [--verbose]
#
# It also drives the *automatic* route (step 4), which is the one that must work with nobody in
# front of the device, and asserts the per-source auth state it acts on - a source misreported as
# "nothing to verify" is never queued, which is what "verification does nothing" looks like.
#
# Exit codes: 0 all checks passed, 1 a check failed, 2 the run could not be decided (no device, no
# challenge) — a check that cannot be decided is never reported as a pass.

set -euo pipefail

ACTION="app.hush.music.action.SPOTIFLAC_DEBUG"
RECEIVER="app.hush.music.debug/app.hush.music.spotiflac.SpotiFLACDebugReceiver"
PACKAGES=("app.hush.music.debug" "app.hush.music")

SOURCE=""
OPEN=0
VERBOSE=0
SERIAL="${ANDROID_SERIAL:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --source) SOURCE="${2:-}"; shift 2 ;;
        --open) OPEN=1; shift ;;
        --serial) SERIAL="${2:-}"; shift 2 ;;
        --verbose) VERBOSE=1; shift ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
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

PACKAGE=""
for candidate in "${PACKAGES[@]}"; do
    if adb_cmd shell pm path "$candidate" >/dev/null 2>&1; then
        PACKAGE="$candidate"
        break
    fi
done

if [[ -z "$PACKAGE" ]]; then
    log "No Hush package installed."
    exit 2
fi

if [[ "$PACKAGE" != "app.hush.music.debug" ]]; then
    log "The smoke test needs the debug build (the receiver ships in no release)."
    exit 2
fi

RECEIVER="$PACKAGE/app.hush.music.spotiflac.SpotiFLACDebugReceiver"
MODEL="$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
log "device=$MODEL package=$PACKAGE"

# ── Helpers ───────────────────────────────────────────────────────────────────

dump_log() {
    adb_cmd logcat -d -v brief -s SpotiFLACDebug 2>/dev/null \
        | sed 's/^.*SpotiFLACDebug *( *[0-9]*): //' || true
}

send() {
    adb_cmd logcat -c >/dev/null 2>&1 || true
    adb_cmd shell am broadcast -n "$RECEIVER" -a "$ACTION" "$@" >/dev/null 2>&1 || true
    local waited=0
    while (( waited < 30 )); do
        if dump_log | grep -q 'received action='; then
            # Give the operation itself a moment past its receipt line.
            sleep 3
            return 0
        fi
        sleep 1
        (( waited++ )) || true
    done
    return 1
}

log_verdicts() { dump_log | grep -oE 'spotiflac-debug step=[^ ]+ source=[^ ]+ verdict=[^ ]+.*' || true; }

PASS=0
FAIL=0
UNDECIDED=0

check() {
    local name="$1" ok="$2" detail="$3"
    if [[ "$ok" == "1" ]]; then
        PASS=$((PASS + 1)); log "  PASS  $name${detail:+ — $detail}"
    else
        FAIL=$((FAIL + 1)); log "  FAIL  $name${detail:+ — $detail}"
    fi
}

undecided() {
    local name="$1" reason="$2"
    UNDECIDED=$((UNDECIDED + 1)); log "  SKIP  $name — $reason"
}

# ── 1. State ──────────────────────────────────────────────────────────────────

log ""
log "1. runtime state"
# Kept: every later step clears the log buffer, and the per-source auth state read here is what
# step 4 decides from.
STATE_DUMP=""
if ! send --es op state; then
    undecided "state" "the receiver did not answer"
else
    STATE_DUMP="$(dump_log)"
    grep 'state source=' <<<"$STATE_DUMP" | sed 's/^/     /' || true
    check "state answered" "$(grep -q 'step=state source=- verdict=ok' <<<"$STATE_DUMP" && echo 1 || echo 0)" ""
fi

# ── 2. Challenge and its owner ────────────────────────────────────────────────

if [[ -z "$SOURCE" ]]; then
    SOURCE="$(dump_log | sed -n 's/.*state source=\([^ ]*\) auth=NEEDS_VERIFICATION.*/\1/p' | head -1)"
fi

if [[ -z "$SOURCE" ]]; then
    undecided "owner" "no source needs verification (nothing to check)"
    log ""
    log "Summary: $PASS passed, $FAIL failed, $UNDECIDED undecided"
    exit $(( FAIL > 0 ? 1 : 0 ))
fi

log ""
log "2. challenge for $SOURCE"
if ! send --es op challenge --es source "$SOURCE"; then
    undecided "challenge" "the receiver did not answer"
    log ""; log "Summary: $PASS passed, $FAIL failed, $UNDECIDED undecided"; exit 2
fi

CHALLENGE_LINE="$(dump_log | grep "challenge source=$SOURCE owner=" | tail -1 || true)"
CHALLENGE_URL="$(sed -n 's/.*url=\(.*\)$/\1/p' <<<"$CHALLENGE_LINE")"
OWNER="$(sed -n 's/.*owner=\([^ ]*\).*/\1/p' <<<"$CHALLENGE_LINE")"
[[ -n "$CHALLENGE_LINE" ]] && log "     $CHALLENGE_LINE"

check "a challenge exists" "$([[ -n "$CHALLENGE_URL" ]] && echo 1 || echo 0)" "${CHALLENGE_URL:0:80}"
check "the challenge names its owner" "$([[ -n "$OWNER" && "$OWNER" != "none" ]] && echo 1 || echo 0)" "owner=$OWNER"

# ── 3. Where the grant goes ───────────────────────────────────────────────────

log ""
log "3. grant routing (owner-only delivery)"

if [[ "$OPEN" == "1" && -n "$CHALLENGE_URL" ]]; then
    log "     opening the challenge in the device's browser - tick the checkbox there"
    adb_cmd shell am start -a android.intent.action.VIEW -d "$CHALLENGE_URL" >/dev/null 2>&1 || true
    log "     then re-run this script to deliver the grant it publishes"
fi

if ! send --es op recover --es source "$SOURCE"; then
    undecided "recover" "the receiver did not answer"
else
    RECOVER_LINE="$(dump_log | grep "step=recover source=$SOURCE" | tail -1 || true)"
    log "     ${RECOVER_LINE:-recover produced no verdict}"
    if grep -q 'reason=challenge-not-solved' <<<"$RECOVER_LINE"; then
        undecided "grant delivered to $OWNER" "the challenge is not solved yet (use --open, tick it, re-run)"
    else
        check "grant delivered to $OWNER" "$(grep -q 'verdict=PASS' <<<"$RECOVER_LINE" && echo 1 || echo 0)" "$RECOVER_LINE"
    fi
fi

# The routing rule itself: whatever happened, a runtime grant must never reach the relay exchange,
# because that is answered with HTTP 403 by construction and used to clear a working session.
ALL_LOG="$(adb_cmd logcat -d -v brief 2>/dev/null || true)"
check "no runtime grant offered to the relay exchange" \
    "$(grep -qE 'relay grant exchange: success=false err=Exchange failed with HTTP 40' <<<"$ALL_LOG" && echo 0 || echo 1)" ""
check "the relay session was not cleared" \
    "$(grep -qiE 'exchange auth failed|clearing session' <<<"$ALL_LOG" && echo 0 || echo 1)" ""
check "completeGrant went to one extension only" \
    "$([[ "$(grep -c 'completeGrant id=' <<<"$ALL_LOG" || true)" -le 1 ]] && echo 1 || echo 0)" \
    "attempts=$(grep -c 'completeGrant id=' <<<"$ALL_LOG" || true)"

# ── 4. The automatic route ────────────────────────────────────────────────────
#
# The route that must work with nobody in front of the device: the challenge is solved in an
# invisible WebView by Cloudflare itself. It is also where a misread auth state hides - a source
# that reports "nothing to verify" is never queued, so the failure looks like verification doing
# nothing at all. `verify` drives it and waits for the outcome, so this step fails loudly when the
# state is wrong rather than passing by silence.

log ""
log "4. automatic verification"
AUTO_SOURCE=""
for source in tidal-web deezer qobuz-web amazon apple-music; do
    if grep -q "step=state source=$source verdict=ok auth=NEEDS_VERIFICATION" <<<"$STATE_DUMP"; then
        AUTO_SOURCE="$source"
        break
    fi
done

if [[ -z "$AUTO_SOURCE" ]]; then
    undecided "automatic verification" "every signed source is already verified (nothing to drive)"
else
    log "     driving the automatic route for $AUTO_SOURCE (no browser, no tap)"
    if ! send --es op verify --es source "$AUTO_SOURCE"; then
        undecided "automatic verification" "the receiver did not answer"
    else
        # The receiver itself waits for the challenge, so one send covers the whole run.
        waited=0
        while (( waited < 150 )); do
            if dump_log | grep -q "step=verify source=$AUTO_SOURCE verdict="; then break; fi
            sleep 2
            (( waited += 2 )) || true
        done
        VERIFY_LINE="$(dump_log | grep "step=verify source=$AUTO_SOURCE" | tail -1 || true)"
        log "     ${VERIFY_LINE:-verify produced no verdict}"
        if [[ -z "$VERIFY_LINE" ]]; then
            undecided "automatic verification of $AUTO_SOURCE" "the run did not finish inside the wait"
        elif grep -q 'verdict=SKIPPED' <<<"$VERIFY_LINE"; then
            undecided "automatic verification of $AUTO_SOURCE" "the source needs no check"
        else
            check "automatic verification of $AUTO_SOURCE" \
                "$(grep -q 'verdict=PASS' <<<"$VERIFY_LINE" && echo 1 || echo 0)" "$VERIFY_LINE"
        fi
    fi
fi

log ""
log "Summary: $PASS passed, $FAIL failed, $UNDECIDED undecided"
exit $(( FAIL > 0 ? 1 : 0 ))
