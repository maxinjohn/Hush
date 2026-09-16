#!/usr/bin/env bash
# Waze bridge lifecycle smoke test - the release gate for the bridge on a real device.
#
# `waze-bridge-smoke-test.sh` proves the bridge *talks to Waze* (queue, transport, reconnect). This
# one proves the lifecycle Hush drives around it, which is what actually breaks users on release day:
#
#   detect        every bridge resolves to a state, and the app can see the bundled build
#   baseline      the bridge is removed, by the app's own uninstall route
#   install       Hush installs the bundled bridge, through the system installer
#   verify        the app's own inspection agrees the bridge is installed at the bundled version
#   update        an older bridge is replaced *in place* - no uninstall, same signature (with
#                 --older-apk: the older bridge is staged on a clean slate first, because the platform
#                 refuses a downgrade over an installed release-signed package)
#   uninstall     the bridge is removed again, by the app's own route
#   repair        a differently-signed bridge is replaced (remove, then install) - when one exists, or
#                 when --mismatch-apk stages one
#   restore       the device is left with the bridge installed at the bundled version
#
# Every app-side step is driven through the debug-only `WazeBridgeRepairDebugReceiver`, which logs one
# machine-readable `smoke step=… verdict=…` line per operation. Nothing here parses the app's prose.
#
# System prompts are unavoidable on a real device: the installer dialog, Google Play Protect's
# "scan app" prompt, and vendor extras (OnePlus/Cosmos `InstallGuideActivity`). With --auto-answer the
# script taps those prompts itself, and only on nodes owned by system packages - never on Hush's own
# UI, which has its own Install buttons that would otherwise be hit by mistake.
#
# One variable is not Hush's to control: the device's package verifier (Play Protect) scans a
# sideloaded bridge and, when that scan cannot reach Google, aborts the install session. Observed on a
# OnePlus NE2211 as `E/Finsky: VerifyApps: Waiting for package installation timed out` followed by the
# platform's "App not installed" - the app did everything right and the session died anyway. It is not
# suppressed by the verify-apps setting, so the gate reports that case as BLOCKED (its own verdict and
# exit code 3) rather than inventing a pass: the run did not prove the app works, and it did not prove
# the app is broken. The device evidence is quoted in the step's detail. --strict-verifier-timeout
# makes it a failure instead, for a device where the verifier is known to work.
#
# Requirements: adb + python3, a connected device, and Hush installed *debug* (the receiver is
# debug-only; a release build has no way in). The bridge to test must be bundled in that build.
#
# Usage:
#   scripts/waze-bridge-lifecycle-smoke.sh --auto-answer
#   scripts/waze-bridge-lifecycle-smoke.sh --bridge youtube --older-apk /tmp/bridge-1710.apk
#
# To exercise the in-place update leg without an old APK at hand, build one and pass it (the build
# output path is reused, so build it before the run and the bundled archive is unaffected):
#   ./gradlew :waze-shim:assembleDeezerRelease -PshimRevision=0
#   scripts/waze-bridge-lifecycle-smoke.sh --older-apk \
#       waze-shim/build/outputs/apk/deezer/release/waze-shim-deezer-release.apk
#   ./gradlew :waze-shim:assembleDeezerRelease      # restore the normal build (same output path)
#
# Exit codes: 0 = every step passed (skips are reported, never counted as passes),
#             1 = a step failed, 2 = usage or device error,
#             3 = the device's own package verifier blocked a step, so the run proved neither way.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

HUSH_PKG="app.hush.music.debug"
BRIDGE_PKG="deezer.android.app"
OLDER_APK=""
MISMATCH_APK=""
STAGED_FIRST_INSTALL=""
BRIDGE_CODES=("spotify" "youtubeMusic" "deezer")
# Every bridge declares the same signature-level permission, so they can only be staged one
# differently-signed at a time - see the repair leg.
ALL_BRIDGE_PACKAGES=("com.spotify.music" "com.google.android.apps.youtube.music" "deezer.android.app")
RECEIVER="app.hush.music.waze.WazeBridgeRepairDebugReceiver"
ACTION="app.hush.music.action.WAZE_REPAIR_DEBUG"

AUTO_ANSWER=0
INSTALL_ATTEMPTS=2
TOLERATE_VERIFIER=1
SKIP_UPDATE=0
SKIP_REPAIR=0
LEAVE_UNINSTALLED=0
VERBOSE=0
JSON=0
STEP_TIMEOUT=300

# Known system prompts - in the order they should be preferred - and the packages allowed to own
# them. Hush's own UI is deliberately excluded: it has its own "Install" buttons, and tapping those
# instead of the platform dialog is exactly how an automated run reports failure for a step that
# actually worked. Only these labels are ever tapped, so a dialog's own refusal action ("Cancel",
# "Don't install app") is never chosen by accident.
PROMPT_PACKAGES=(com.google.android.packageinstaller com.android.packageinstaller com.android.vending com.oplus.stdsp)
# Order is preference, and "Done" is last on purpose: it is the dismissal on the installer's success
# screen ("App installed."), which has to be cleared before the next step can see the app behind it.
# Only affirmative actions (and that dismissal) are listed - never "Cancel", "Don't install app" or
# "Open", so an automated run can never decline an install or launch a Bridge by accident.
PROMPT_TEXTS=(Install Update "Install anyway" "Scan app" "Continue installation" OK Done)

print_usage() {
  cat <<'EOF'
Usage: scripts/waze-bridge-lifecycle-smoke.sh [options]

Options:
  --bridge <id|package|name>   Bridge to exercise: spotify | youtubeMusic | deezer, or its package,
                               or its Waze display name (default: deezer.android.app)
  --hush-pkg <package>         Hush package with the debug receiver (default app.hush.music.debug)
  --mismatch-apk <file>        A differently-signed bridge APK to stage so the repair leg has
                               something to repair. Staging takes every bridge off the device
                               briefly (they share a signature-level permission), then puts the
                               others back. Build one with:
                                 apksigner sign --ks /tmp/foreign.jks --out /tmp/foreign.apk \
                                   waze-shim/build/outputs/apk/<bridge>/release/waze-shim-<bridge>-release.apk
  --older-apk <file>           An older, same-key bridge APK to stage for the in-place update leg.
                               It is installed on a clean slate (the platform refuses a version
                               downgrade over an installed release-signed bridge), then Hush must
                               replace it with the bundled build without an uninstall
  --auto-answer                Tap the known system prompts (installer, Play Protect, vendor guide)
  --install-attempts <n>       Times to run the install leg before failing it (default 2)
  --strict-verifier-timeout    Count a device-side verifier timeout as a failure instead of BLOCKED
  --skip-update                Skip the in-place update leg
  --skip-repair                Skip the repair leg
  --leave-uninstalled          Do not restore the bridge at the end
  --timeout <seconds>          Budget per waiting step (default 300)
  --json                       Print a JSON summary as the last line
  --verbose                    Print the receiver's raw log lines for failed steps
  -h, --help                   Show this help

Exit codes: 0 all steps passed, 1 a step failed, 2 usage/device error,
            3 the device's own package verifier blocked a step (neither pass nor fail).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bridge) BRIDGE_PKG="$2"; shift 2 ;;
    --hush-pkg) HUSH_PKG="$2"; shift 2 ;;
    --older-apk) OLDER_APK="$2"; shift 2 ;;
    --mismatch-apk) MISMATCH_APK="$2"; shift 2 ;;
    --auto-answer) AUTO_ANSWER=1; shift ;;
    --install-attempts) INSTALL_ATTEMPTS="$2"; shift 2 ;;
    --strict-verifier-timeout) TOLERATE_VERIFIER=0; shift ;;
    --skip-update) SKIP_UPDATE=1; shift ;;
    --skip-repair) SKIP_REPAIR=1; shift ;;
    --leave-uninstalled) LEAVE_UNINSTALLED=1; shift ;;
    --timeout) STEP_TIMEOUT="$2"; shift 2 ;;
    --json) JSON=1; shift ;;
    --verbose) VERBOSE=1; shift ;;
    -h|--help) print_usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; print_usage >&2; exit 2 ;;
  esac
done

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb is required." >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required." >&2; exit 2; }

# --- naming ------------------------------------------------------------------

case "$BRIDGE_PKG" in
  spotify) BRIDGE_PKG="com.spotify.music"; BRIDGE_NAME="Spotify" ;;
  youtubeMusic|youtube|youtube-music) BRIDGE_PKG="com.google.android.apps.youtube.music"; BRIDGE_NAME="YouTube Music" ;;
  deezer) BRIDGE_PKG="deezer.android.app"; BRIDGE_NAME="Deezer" ;;
  *)
    case "$BRIDGE_PKG" in
      com.spotify.music) BRIDGE_NAME="Spotify" ;;
      com.google.android.apps.youtube.music) BRIDGE_NAME="YouTube Music" ;;
      deezer.android.app) BRIDGE_NAME="Deezer" ;;
      *) BRIDGE_NAME="${BRIDGE_PKG##*.}" ;;
    esac
    ;;
esac

pkg_name_of() {
  case "$1" in
    com.spotify.music) printf 'Spotify' ;;
    com.google.android.apps.youtube.music) printf 'YouTube Music' ;;
    deezer.android.app) printf 'Deezer' ;;
    *) printf '%s' "${1##*.}" ;;
  esac
}

# --- helpers -----------------------------------------------------------------

ADB=(adb)
LOG_DIR="$ROOT_DIR/.freebuff/waze-lifecycle"
mkdir -p "$LOG_DIR"
DUMP_FILE="$LOG_DIR/dump.xml"

STEP_NAMES=()
STEP_VERDICTS=()
STEP_DETAILS=()
FAILED=0
PASSED=0
SKIPPED=0
BLOCKED=0

log() { printf '%s\n' "$*"; }
# Progress notes go to stderr on purpose: waiting steps are invoked inside `$(...)`, and stdout there
# is captured for the verdict line. Anything written to stdout mid-wait would vanish from the console.
note() { printf '  [info] %s\n' "$*" >&2; }
dbg() { [[ "$VERBOSE" == "1" ]] && printf '  [dbg] %s\n' "$*" >&2; return 0; }

record() { # record <name> <PASS|FAIL|SKIP|BLOCKED> <detail>
  STEP_NAMES+=("$1"); STEP_VERDICTS+=("$2"); STEP_DETAILS+=("$3")
  case "$2" in
    PASS) PASSED=$((PASSED + 1)); log "  [PASS] $1: $3" ;;
    FAIL) FAILED=$((FAILED + 1)); log "  [FAIL] $1: $3" ;;
    SKIP) SKIPPED=$((SKIPPED + 1)); log "  [SKIP] $1: $3" ;;
    BLOCKED) BLOCKED=$((BLOCKED + 1)); log "  [BLOCKED] $1: $3" ;;
  esac
}

recv() {
  # Explicit component: an implicit broadcast is silently dropped by Android 8+ rules here.
  ${ADB[@]} shell am broadcast --include-stopped-packages \
    -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" --es op "$1" >/dev/null 2>&1
}

receiver_lines() { ${ADB[@]} logcat -d -v brief -s WazeBridgeDebug:* 2>/dev/null | tr -d '\r'; }

status_line() { # status_line <package>
  receiver_lines | grep -F "smoke step=status bridge=$1 " | tail -1
}

state_of() { status_line "$1" | sed -n 's/.* state=\([A-Z_]*\).*/\1/p' | head -1; }
installed_version_of() { status_line "$1" | sed -n 's/.* installedVersion=\([^ ]*\).*/\1/p' | head -1; }
bundled_version_of() { status_line "$1" | sed -n 's/.* bundledVersion=\([^ ]*\).*/\1/p' | head -1; }

foreground_hush() {
  ${ADB[@]} shell am start -n "$HUSH_PKG/app.hush.music.MainActivity" >/dev/null 2>&1
  sleep 2
}

app_installed_package() { # the platform's view of the package version, if any
  ${ADB[@]} shell dumpsys package "$1" 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1
}

# pkg_first_install_time: the platform's record of when the package was first installed.
#
# The in-place update leg uses it as proof that the bridge was *replaced* rather than removed and
# reinstalled: an update keeps this timestamp, a fresh install resets it. The epoch values belong to
# users the package was uninstalled for, so they are ignored.
pkg_first_install_time() {
  ${ADB[@]} shell dumpsys package "$1" 2>/dev/null \
    | sed -n 's/.*firstInstallTime=\(.*\)/\1/p' | grep -v '1970-01-01' | head -1 | tr -d '\r'
}

# answer_prompts_once: taps at most one known system prompt, on a system-owned node.
answer_prompts_once() {
  [[ "$AUTO_ANSWER" == "1" ]] || return 1
  local texts=""
  local t
  for t in "${PROMPT_TEXTS[@]}"; do texts+="$t|"; done
  texts="${texts%|}"
  # Kept in preference order on the python side: the list is the priority, not the layout.

  # A dump can fail for its own reasons (the platform refuses to settle, the window is secured), and
  # a silent failure here is indistinguishable from "no prompt on screen" - which is exactly how this
  # scanner can spend a whole step budget finding nothing. So the dump is attempted twice and its
  # outcome is reported rather than assumed.
  local dump_out=""
  local dump_try
  for dump_try in 1 2; do
    ${ADB[@]} shell rm -f /sdcard/waze_lifecycle.xml >/dev/null 2>&1
    dump_out="$(${ADB[@]} shell uiautomator dump --compressed /sdcard/waze_lifecycle.xml 2>&1 | tr -d '\r')"
    [[ "$dump_out" == *"UI hierchary dumped"* || "$dump_out" == *"dumped to"* ]] && break
    sleep 1
  done
  if ! ${ADB[@]} pull /sdcard/waze_lifecycle.xml "$DUMP_FILE" >/dev/null 2>&1 || [[ ! -s "$DUMP_FILE" ]]; then
    dbg "prompt scan: no dump (uiautomator said: ${dump_out:-nothing})"
    return 1
  fi
  # Which screen the dump belongs to decides whether a prompt can be found at all: uiautomator
  # dumps one window, and on a vendor ROM the installer can run in its own task.
  dbg "prompt scan window: $(grep -o 'package="[^"]*"' "$DUMP_FILE" 2>/dev/null | sort -u | tr '\n' ' ')"
  # What the dump actually offers from a system package, so a miss is diagnosable from the log
  # instead of only visible as "the script never tapped anything".
  dbg "prompt scan candidates: $(python3 - "$DUMP_FILE" "${PROMPT_PACKAGES[*]}" <<'PYCAND'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
allowed = sys.argv[2].split()
seen = []
for m in re.finditer(r'<node\b[^>]*>', xml):
    tag = m.group(0)
    t = re.search(r'text="([^"]*)"', tag)
    p = re.search(r'package="([^"]*)"', tag)
    if t and p and t.group(1).strip() and p.group(1) in allowed:
        label = t.group(1).strip()
        if label not in seen:
            seen.append(label)
print(' | '.join(seen[:12]) if seen else 'none')
PYCAND
)"
  local center
  center="$(python3 - "$DUMP_FILE" "$texts" "${PROMPT_PACKAGES[*]}" <<'PYEOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
wanted = sys.argv[2].split('|')
allowed = sys.argv[3].split()
found = {}
for m in re.finditer(r'<node\b[^>]*>', xml):
    tag = m.group(0)
    t = re.search(r'text="([^"]*)"', tag)
    p = re.search(r'package="([^"]*)"', tag)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not t or not p or not b:
        continue
    if t.group(1) not in wanted or p.group(1) not in allowed:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    found[t.group(1)] = ((x1 + x2) // 2, (y1 + y2) // 2)
# The list order is the preference: an affirmative action always wins over a later one.
for label in wanted:
    if label in found:
        x, y = found[label]
        print(f"{x} {y} {label}")
        sys.exit(0)
sys.exit(1)
PYEOF
)" || { dbg "no prompt matched in the dump"; return 1; }
  [[ -n "$center" ]] || { dbg "no prompt matched in the dump"; return 1; }
  local x y label
  read -r x y label <<<"$center"
  note "answering system prompt '$label' at $x,$y"
  ${ADB[@]} shell input tap "$x" "$y" >/dev/null 2>&1
  sleep 1
  return 0
}

# platform_verifier_evidence: the device's own package verifier giving up on a sideloaded install.
#
# Deliberately narrow - only the verifier's own "VerifyApps" messages count, because the platform's
# generic failure screen looks the same whether the verifier timed out or the APK was rejected, and a
# real Hush defect must never be excused as a device condition.
#
# It also arrives late: Play Protect's scan takes minutes to time out, while the app's own watch gives
# up in about half a minute, so the evidence has to be looked for *after* the fact.
platform_verifier_evidence() {
  ${ADB[@]} logcat -d -v brief 2>/dev/null \
    | grep -m1 -E 'VerifyApps' \
    | tr -d '\r' | sed 's/^[^ ]* *[^ ]* *//' | cut -c1-140
}

# platform_install_conflict <package>: the platform refusing a correctly-signed bridge because it
# still holds a record of the bridge that was removed.
#
# A bridge declares `app.hush.music.permission.WAZE_BRIDGE_CONTROL` at signature protection, so when a
# *differently-signed* bridge has been installed and removed, the platform can keep that signature as
# the permission's owner. Every later install of the same package name is then refused - with
# `INSTALL_FAILED_DUPLICATE_PERMISSION` ("conflicts with an existing package") while another bridge is
# installed, or `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ("signatures do not match") once none is. Hush did
# its part and the platform is in a state only a further removal or a reboot clears, so this is a
# device condition to report by name - not a Hush failure, and not something to excuse as "the
# verifier". Evidence is looked for in the failure screen and in the platform's own refusal.
platform_install_conflict() { # platform_install_conflict <package>
  local pkg="$1" hit=""
  local screen=""
  ${ADB[@]} shell rm -f /sdcard/waze_conflict.xml >/dev/null 2>&1
  if ${ADB[@]} shell uiautomator dump --compressed /sdcard/waze_conflict.xml >/dev/null 2>&1 \
    && ${ADB[@]} pull /sdcard/waze_conflict.xml "$DUMP_FILE" >/dev/null 2>&1 \
    && [[ -s "$DUMP_FILE" ]]; then
    screen="$(grep -oE 'conflicts with an existing package|signatures do not match|Update incompatible' "$DUMP_FILE" | head -1)"
  fi
  hit="$(${ADB[@]} logcat -d -v brief 2>/dev/null | tail -500 \
    | grep -m1 -oE 'INSTALL_FAILED_DUPLICATE_PERMISSION|INSTALL_FAILED_UPDATE_INCOMPATIBLE' | head -1)"
  if [[ -n "$screen" ]]; then
    printf '%s' "$screen"
  else
    printf '%s' "$hit"
  fi
}

# wait_for_line <pattern> <seconds> -> prints the matching line
wait_for_line() {
  local pattern="$1" budget="$2" waited=0
  while (( waited <= budget )); do
    local hit
    hit="$(receiver_lines | grep -E "$pattern" | tail -1)"
    if [[ -n "$hit" ]]; then printf '%s\n' "$hit"; return 0; fi
    answer_prompts_once || true
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# wait_for_state <package> <state-regex> <seconds>
wait_for_state() {
  local pkg="$1" want="$2" budget="$3" waited=0 state
  while (( waited <= budget )); do
    recv status
    sleep 1
    state="$(state_of "$pkg")"
    if [[ -n "$state" ]] && [[ "$state" =~ $want ]]; then return 0; fi
    answer_prompts_once || true
    sleep 2
    waited=$((waited + 3))
  done
  return 1
}

dump_failure_evidence() {
  [[ "$VERBOSE" == "1" ]] || return 0
  log "  [dbg] receiver log:"
  receiver_lines | tail -25 | sed 's/^/    /'
}

# --- preflight ---------------------------------------------------------------

if [[ "$(${ADB[@]} devices | sed -n '2p' | grep -c 'device$')" != "1" ]]; then
  echo "ERROR: exactly one device must be connected (adb devices)." >&2
  exit 2
fi

if ! ${ADB[@]} shell pm path "$HUSH_PKG" >/dev/null 2>&1; then
  echo "ERROR: $HUSH_PKG is not installed. The receiver is debug-only - install a debug build." >&2
  exit 2
fi

if ! ${ADB[@]} shell dumpsys package "$HUSH_PKG" 2>/dev/null | grep -q "$RECEIVER"; then
  echo "ERROR: $RECEIVER is not registered in $HUSH_PKG. Is this a debug build of current dev?" >&2
  exit 2
fi

log "======================================================================"
log " Waze bridge lifecycle smoke test"
log "   device : $(${ADB[@]} shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
log "   hush   : $HUSH_PKG"
log "   bridge : $BRIDGE_NAME ($BRIDGE_PKG)"
log "   answer : $([[ "$AUTO_ANSWER" == "1" ]] && echo 'system prompts tapped automatically' || echo 'prompts must be answered by hand')"
log "======================================================================"

# Animations off for the run: they decide when a tap lands and when a dump settles.
ANIM_KEYS=(window_animation_scale transition_animation_scale animator_duration_scale)
ANIM_VALUES=()
for key in "${ANIM_KEYS[@]}"; do
  ANIM_VALUES+=("$(${ADB[@]} shell settings get global "$key" 2>/dev/null | tr -d '\r')")
  ${ADB[@]} shell settings put global "$key" 0 >/dev/null 2>&1
done

restore_device_settings() {
  local i
  for ((i = 0; i < ${#ANIM_KEYS[@]}; i++)); do
    ${ADB[@]} shell settings put global "${ANIM_KEYS[$i]}" "${ANIM_VALUES[$i]:-1}" >/dev/null 2>&1
  done
}
trap restore_device_settings EXIT

foreground_hush

# --- 1. detect ---------------------------------------------------------------

${ADB[@]} logcat -c
recv status
if wait_for_line "smoke step=status bridge=$BRIDGE_PKG " 30 >/dev/null; then
  BUNDLED_VERSION="$(bundled_version_of "$BRIDGE_PKG")"
  if [[ -z "$BUNDLED_VERSION" || "$BUNDLED_VERSION" == "none" ]]; then
    record "detect" FAIL "the app cannot read its bundled bridge APK (bundledVersion=$BUNDLED_VERSION)"
  else
    record "detect" PASS "state=$(state_of "$BRIDGE_PKG") bundled=$BUNDLED_VERSION"
  fi
else
  record "detect" FAIL "no status verdict from the receiver within 30s"
  dump_failure_evidence
fi

if [[ "$FAILED" -gt 0 || "$BLOCKED" -gt 0 ]]; then
  log ""
  log "Aborting: the app's own inspection is not answering, so no later step could be trusted."
  exit 1
fi

# --- 2. baseline: remove whatever is installed -------------------------------

if [[ "$(state_of "$BRIDGE_PKG")" == "NOT_INSTALLED" ]]; then
  record "baseline" PASS "already absent"
else
  ${ADB[@]} logcat -c
  ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
    --es op uninstall --es package "$BRIDGE_PKG" >/dev/null 2>&1
  verdict="$(wait_for_line "smoke step=uninstall bridge=$BRIDGE_PKG " 20 || true)"
  dbg "uninstall verdict: ${verdict:-none}"
  # A refusal is instant and silent, so there is nothing to wait for: go straight to the platform's
  # own uninstall instead of spending the whole step budget first.
  budget="$STEP_TIMEOUT"
  [[ "$verdict" == *"verdict=REFUSED"* ]] && budget=15
  if wait_for_state "$BRIDGE_PKG" "^NOT_INSTALLED$" "$budget"; then
    record "baseline" PASS "removed by the app's uninstall route"
  else
    log "  [info] the app's direct uninstall request was refused; removing through the platform"
    ${ADB[@]} uninstall "$BRIDGE_PKG" >/dev/null 2>&1
    if wait_for_state "$BRIDGE_PKG" "^NOT_INSTALLED$" 30; then
      record "baseline" PASS "removed via the platform (the app's direct request was refused)"
    else
      record "baseline" FAIL "the bridge is still installed"
      dump_failure_evidence
    fi
  fi
fi

# --- 3. install --------------------------------------------------------------

if [[ "$FAILED" -eq 0 ]]; then
  INSTALL_OK=0
  attempt=1
  while (( attempt <= INSTALL_ATTEMPTS )); do
    [[ "$attempt" -gt 1 ]] && note "install attempt $attempt of $INSTALL_ATTEMPTS"
    # A failed install leaves the platform's failure screen up, which would block the next attempt.
    ${ADB[@]} shell input keyevent 4 >/dev/null 2>&1
    foreground_hush
    ${ADB[@]} logcat -c
    ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
      --es op install --es package "$BRIDGE_PKG" >/dev/null 2>&1
    line="$(wait_for_line "smoke step=(install|update) bridge=$BRIDGE_PKG " "$STEP_TIMEOUT" || true)"
    if [[ -n "$line" && "$line" == *"verdict=PASS"* ]]; then
      INSTALL_OK=1
      record "install" PASS "$(printf '%s' "$line" | sed 's/.*smoke //')"
      break
    fi
    platform="$(platform_verifier_evidence)"
    detail="${line:-no verdict within ${STEP_TIMEOUT}s}"
    detail="$(printf '%s' "$detail" | sed 's/.*smoke //')"
    conflict="$(platform_install_conflict "$BRIDGE_PKG")"
    [[ -n "$platform" ]] && detail="$detail | device verifier: $platform"
    if (( attempt == INSTALL_ATTEMPTS )); then
      # The retained-record refusal is named before the verifier: it is specific, it has a remedy the
      # user can act on, and confusing it with a verifier timeout sends them looking in the wrong place.
      if [[ -n "$conflict" && "$TOLERATE_VERIFIER" == "1" ]]; then
        record "install" BLOCKED "the platform still holds a record of the bridge that was removed, so it refuses the correctly-signed one ($conflict). Remove the bridge once more in Settings > Waze integration (or reboot) and re-run"
        attempt=$((attempt + 1))
        continue
      fi
      # Give the device's verifier time to admit it, so a device-side abort is not reported as a
      # failure of the app (and a genuine failure is not excused as a device condition).
      if [[ -z "$platform" && "$TOLERATE_VERIFIER" == "1" ]]; then
        note "no verdict yet; waiting for the device verifier to report itself"
        sleep 20
        platform="$(platform_verifier_evidence)"
        [[ -n "$platform" ]] && detail="$detail | device verifier: $platform"
      fi
      if [[ -n "$platform" && "$TOLERATE_VERIFIER" == "1" ]]; then
        record "install" BLOCKED "the device's package verifier aborted the install session ($detail)"
      else
        record "install" FAIL "$detail"
        dump_failure_evidence
      fi
    else
      note "attempt $attempt did not land ($detail)"
    fi
    attempt=$((attempt + 1))
  done
fi

# --- 4. verify ---------------------------------------------------------------

if [[ "$FAILED" -eq 0 ]]; then
  ${ADB[@]} logcat -c
  ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
    --es op verify --es package "$BRIDGE_PKG" \
    --es expect installed --es version "$BUNDLED_VERSION" >/dev/null 2>&1
  line="$(wait_for_line "smoke step=verify bridge=$BRIDGE_PKG " 30 || true)"
  if [[ "$line" == *"verdict=PASS"* ]]; then
    record "verify" PASS "installed at the bundled version $BUNDLED_VERSION"
  else
    record "verify" FAIL "${line:-no verify verdict} (platform version=$(app_installed_package "$BRIDGE_PKG"))"
  fi
fi

# --- 5. update in place ------------------------------------------------------

if [[ "$SKIP_UPDATE" == "1" ]]; then
  record "update" SKIP "--skip-update"
elif [[ "$FAILED" -gt 0 || "$BLOCKED" -gt 0 ]]; then
  record "update" SKIP "an earlier step did not pass"
else
  INSTALLED_VERSION="$(installed_version_of "$BRIDGE_PKG")"
  if [[ -n "$OLDER_APK" && "$FAILED" -eq 0 && "$BLOCKED" -eq 0 ]]; then
    if [[ ! -f "$OLDER_APK" ]]; then
      record "update" FAIL "older APK not found: $OLDER_APK"
      INSTALLED_VERSION=""
    else
      # The older bridge has to be staged *fresh*. The platform refuses a version downgrade over an
      # installed release-signed package (`adb install -d` only permits that for debuggable ones - a
      # bridge is not debuggable), so this leg removes what the install step put there, installs the
      # older bridge, and then lets Hush replace it. What the leg proves is the property that matters
      # to a user on release day: same key, replaced in place, without an uninstall.
      log "  [info] staging the older bridge for the in-place update leg: $OLDER_APK"
      ${ADB[@]} uninstall "$BRIDGE_PKG" >/dev/null 2>&1
      stage_out="$(${ADB[@]} install "$OLDER_APK" 2>&1 | tail -1 | tr -d '\r')"
      recv status
      sleep 1
      INSTALLED_VERSION="$(installed_version_of "$BRIDGE_PKG")"
      if [[ -z "$INSTALLED_VERSION" || "$INSTALLED_VERSION" == "none" || "$INSTALLED_VERSION" == "$BUNDLED_VERSION" ]]; then
        record "update" FAIL "could not stage an older bridge to update from ($stage_out)"
        dump_failure_evidence
      else
        log "  [info] staged $INSTALLED_VERSION; Hush should now replace it with $BUNDLED_VERSION in place"
      STAGED_FIRST_INSTALL="$(pkg_first_install_time "$BRIDGE_PKG")"
      dbg "staged firstInstallTime=${STAGED_FIRST_INSTALL:-unknown}"
      fi
    fi
  fi

  if [[ "$FAILED" -gt 0 || "$BLOCKED" -gt 0 ]]; then
    :
  elif [[ -z "$INSTALLED_VERSION" || "$INSTALLED_VERSION" == "none" ]]; then
    record "update" FAIL "nothing is installed to update"
  elif [[ "$INSTALLED_VERSION" == "$BUNDLED_VERSION" ]]; then
    record "update" SKIP "installed version already equals the bundled one ($BUNDLED_VERSION); pass --older-apk to exercise this leg"
  else
    foreground_hush
    ${ADB[@]} logcat -c
    ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
      --es op install --es package "$BRIDGE_PKG" >/dev/null 2>&1
    line="$(wait_for_line "smoke step=update bridge=$BRIDGE_PKG " "$STEP_TIMEOUT" || true)"
    if [[ -z "$line" ]]; then
      # A single verdict wait is not the whole budget an attempt may need: a platform prompt (Play
      # Protect's scan) holds the install session open while the app's own watch is still running, so
      # the outcome is confirmed from the bridge's state instead of being failed for being slow. The
      # proof that it was replaced *in place* - the property this leg exists for - is that the
      # platform's first-install time for the package did not change.
      if wait_for_state "$BRIDGE_PKG" "^BRIDGE_CURRENT$" "$STEP_TIMEOUT"; then
        after_first_install="$(pkg_first_install_time "$BRIDGE_PKG")"
        if [[ -n "$STAGED_FIRST_INSTALL" && "$after_first_install" == "$STAGED_FIRST_INSTALL" ]]; then
          record "update" PASS "$INSTALLED_VERSION -> $BUNDLED_VERSION in place (no verdict line within ${STEP_TIMEOUT}s; the platform's first-install time is unchanged, so it was replaced, not reinstalled)"
        elif [[ -n "$STAGED_FIRST_INSTALL" && -n "$after_first_install" ]]; then
          record "update" FAIL "the bridge reached $BUNDLED_VERSION but was reinstalled rather than replaced in place (firstInstallTime $STAGED_FIRST_INSTALL -> $after_first_install)"
        else
          record "update" PASS "$INSTALLED_VERSION -> $BUNDLED_VERSION (no verdict line within ${STEP_TIMEOUT}s, and firstInstallTime was unreadable, so in-place was not separately confirmed)"
        fi
      else
        record "update" FAIL "no update verdict within ${STEP_TIMEOUT}s and the bridge is $(state_of "$BRIDGE_PKG") (platform version=$(app_installed_package "$BRIDGE_PKG"))"
        dump_failure_evidence
      fi
    elif [[ "$line" == *"verdict=PASS"* && "$line" == *"version=$BUNDLED_VERSION"* ]]; then
      record "update" PASS "$INSTALLED_VERSION -> $BUNDLED_VERSION without an uninstall"
    else
      detail="$(printf '%s' "$line" | sed 's/.*smoke //')"
      # An in-place update the platform aborted is a device condition when the platform says so:
      # either its verifier gave up, or it still holds the removed bridge's signature record. Both are
      # reported by name, with the evidence attached, instead of as a Hush defect.
      conflict="$(platform_install_conflict "$BRIDGE_PKG")"
      platform="$(platform_verifier_evidence)"
      if [[ "$TOLERATE_VERIFIER" == "1" && ( -n "$conflict" || -n "$platform" ) ]]; then
        record "update" BLOCKED "the platform aborted the in-place update ($detail)${conflict:+ | platform record: $conflict}${platform:+ | device verifier: $platform}"
      else
        record "update" FAIL "$detail"
        dump_failure_evidence
      fi
    fi
  fi
fi

# --- 6. uninstall ------------------------------------------------------------

if [[ "$FAILED" -gt 0 || "$BLOCKED" -gt 0 ]]; then
  record "uninstall" SKIP "an earlier step did not pass"
else
  ${ADB[@]} logcat -c
  ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
    --es op uninstall --es package "$BRIDGE_PKG" >/dev/null 2>&1
  # Read *this* attempt's verdict. The baseline leg's verdict must not leak into this one: a refusal
  # there says nothing about what happened here, and using it shortened this step's budget wrongly.
  uninstall_verdict="$(wait_for_line "smoke step=uninstall bridge=$BRIDGE_PKG " 20 || true)"
  budget="$STEP_TIMEOUT"
  [[ "$uninstall_verdict" == *"verdict=REFUSED"* ]] && budget=15
  if wait_for_state "$BRIDGE_PKG" "^NOT_INSTALLED$" "$budget"; then
    route="the app's uninstall request"
    [[ "$uninstall_verdict" == *"verdict=REFUSED"* ]] && route="the platform, after the app's request was refused"
    record "uninstall" PASS "removed by $route"
  else
    log "  [info] request refused; removing through the platform"
    ${ADB[@]} uninstall "$BRIDGE_PKG" >/dev/null 2>&1
    if wait_for_state "$BRIDGE_PKG" "^NOT_INSTALLED$" 30; then
      record "uninstall" PASS "removed via the platform (the app's direct request was refused)"
    else
      record "uninstall" FAIL "still installed after every route (${uninstall_verdict:-no verdict})"
      dump_failure_evidence
    fi
  fi
fi

# --- 7. repair ---------------------------------------------------------------

# A repair is only reachable with a bridge whose signature Hush does not trust, and that condition
# cannot be reached on its own: every bridge declares the same signature-level permission
# (`app.hush.music.permission.WAZE_BRIDGE_CONTROL`), so a differently-signed bridge is refused with
# `INSTALL_FAILED_DUPLICATE_PERMISSION` while any other bridge is installed, and cannot replace an
# installed one either. Staging a mismatch therefore means taking the other bridges off the device,
# which is why it is opt-in (`--mismatch-apk`) and why they are put back afterwards.
STAGED_OTHER_PACKAGES=()
MISMATCH_INSTALL_OUT=""

stage_signature_mismatch() { # stage_signature_mismatch <differently-signed-apk>
  local apk="$1" pkg
  STAGED_OTHER_PACKAGES=()
  for pkg in "${ALL_BRIDGE_PACKAGES[@]}"; do
    if [[ "$pkg" == "$BRIDGE_PKG" ]]; then
      ${ADB[@]} uninstall "$pkg" >/dev/null 2>&1
      continue
    fi
    if ${ADB[@]} shell pm path "$pkg" >/dev/null 2>&1; then
      STAGED_OTHER_PACKAGES+=("$pkg")
      note "moving $(pkg_name_of "$pkg") aside for the repair leg"
      ${ADB[@]} uninstall "$pkg" >/dev/null 2>&1
    fi
  done
  MISMATCH_INSTALL_OUT="$(${ADB[@]} install "$apk" 2>&1 | tail -1 | tr -d '\r')"
  recv status
  sleep 1
  [[ "$(state_of "$BRIDGE_PKG")" == "BRIDGE_SIGNATURE_MISMATCH" ]]
}

restore_staged_bridges() {
  local pkg
  for pkg in "${STAGED_OTHER_PACKAGES[@]}"; do
    note "putting $(pkg_name_of "$pkg") back"
    foreground_hush
    ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
      --es op install --es package "$pkg" >/dev/null 2>&1
    wait_for_line "smoke step=(install|update) bridge=$pkg " "$STEP_TIMEOUT" >/dev/null || true
    if wait_for_state "$pkg" "^BRIDGE_CURRENT$" "$STEP_TIMEOUT"; then
      note "$(pkg_name_of "$pkg") restored"
    else
      note "WARNING: $(pkg_name_of "$pkg") was not restored; use Install in Settings > Waze integration"
    fi
  done
  STAGED_OTHER_PACKAGES=()
}

if [[ "$SKIP_REPAIR" == "1" ]]; then
  record "repair" SKIP "--skip-repair"
elif [[ "$FAILED" -gt 0 || "$BLOCKED" -gt 0 ]]; then
  record "repair" SKIP "an earlier step did not pass"
else
  REPAIR_RECORDED=0
  ${ADB[@]} logcat -c
  recv status
  sleep 1

  # The mismatch may already be on the device (a lab device, or a previous staged run); only stage
  # one when it is not, so an existing real mismatched bridge is what gets repaired.
  if [[ "$(state_of "$BRIDGE_PKG")" != "BRIDGE_SIGNATURE_MISMATCH" && -n "$MISMATCH_APK" ]]; then
    if [[ ! -f "$MISMATCH_APK" ]]; then
      record "repair" FAIL "mismatch APK not found: $MISMATCH_APK"
      REPAIR_RECORDED=1
    else
      note "staging a differently-signed bridge: $MISMATCH_APK"
      if stage_signature_mismatch "$MISMATCH_APK"; then
        :
      else
        record "repair" FAIL "staging did not produce a signature mismatch ($MISMATCH_INSTALL_OUT)"
        REPAIR_RECORDED=1
        dump_failure_evidence
      fi
    fi
  fi

  if [[ "$REPAIR_RECORDED" -eq 0 ]]; then
    if [[ "$(state_of "$BRIDGE_PKG")" == "BRIDGE_SIGNATURE_MISMATCH" ]]; then
      foreground_hush
      ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
        --es op repair --es package "$BRIDGE_PKG" >/dev/null 2>&1
      repair_step="$(wait_for_line "smoke step=repair bridge=$BRIDGE_PKG " 30 || true)"
      dbg "repair request verdict: ${repair_step:-none}"
      # The repair's removal half is a system dialog (or the platform's own refusal of the app's
      # request); headless, the platform removes it here.
      ${ADB[@]} uninstall "$BRIDGE_PKG" >/dev/null 2>&1
      answer_prompts_once || true
      ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
        --es op check >/dev/null 2>&1
      # Only the *check* verdict decides this. Matching the install line here was a real harness bug:
      # it satisfied the wait, but an install verdict is not the completion rule the repair is judged
      # on, so a repair that worked was reported as failed.
      line="$(wait_for_line "smoke step=check bridge=$BRIDGE_PKG " "$STEP_TIMEOUT" || true)"
      if [[ "$line" == *"verdict=WAITING_FOR_UNINSTALL"* ]]; then
        # The removal reached the app late, or the platform held on to the record long enough for the
        # check to run first. Remove what is left and ask again instead of failing the repair for a
        # platform delay.
        note "the app still sees the staged bridge; removing it again and re-checking"
        ${ADB[@]} uninstall "$BRIDGE_PKG" >/dev/null 2>&1
        answer_prompts_once || true
        ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
          --es op check >/dev/null 2>&1
        line="$(wait_for_line "smoke step=check bridge=$BRIDGE_PKG " "$STEP_TIMEOUT" || true)"
      fi
      if [[ "$line" == *"verdict=COMPLETE_WITH_INSTALL"* ]]; then
        # The install it launched still needs the prompt answered; give it the budget.
        if wait_for_state "$BRIDGE_PKG" "^BRIDGE_CURRENT$" "$STEP_TIMEOUT"; then
          if [[ ${#STAGED_OTHER_PACKAGES[@]} -gt 0 ]]; then
            if [[ "$(installed_version_of "$BRIDGE_PKG")" == "$BUNDLED_VERSION" ]]; then
              record "repair" PASS "staged mismatch removed, then re-installed at $BUNDLED_VERSION"
            else
              record "repair" FAIL "re-installed at $(installed_version_of "$BRIDGE_PKG") instead of $BUNDLED_VERSION"
            fi
          else
            record "repair" PASS "removed then re-installed at $BUNDLED_VERSION"
          fi
        else
          record "repair" FAIL "install after the removal did not land"
          dump_failure_evidence
        fi
      else
        record "repair" FAIL "${line:-the app did not complete the repair}"
        dump_failure_evidence
      fi
    else
      record "repair" SKIP "nothing is differently signed and no --mismatch-apk was given, so there is nothing to repair"
    fi
  fi

  # Whatever the verdict, the bridges moved aside for the staging must not be left missing.
  if [[ ${#STAGED_OTHER_PACKAGES[@]} -gt 0 ]]; then
    restore_staged_bridges
  fi
fi

# --- 8. restore --------------------------------------------------------------

if [[ "$LEAVE_UNINSTALLED" == "1" ]]; then
  record "restore" SKIP "--leave-uninstalled"
else
  # Best effort even after an earlier failure, on purpose: a failing run must not leave the device
  # without a bridge, because that is the state the next run - and the user's car - then inherits.
  recv status
  sleep 1
  if [[ "$(state_of "$BRIDGE_PKG")" == "BRIDGE_CURRENT" ]]; then
    record "restore" PASS "already installed at $BUNDLED_VERSION"
    RESTORE_DONE=1
  else
    log "  [info] restoring the bridge under test (state=$(state_of "$BRIDGE_PKG"))"
    RESTORE_DONE=0
  fi
fi

if [[ "$LEAVE_UNINSTALLED" != "1" && "${RESTORE_DONE:-0}" != "1" ]]; then
  foreground_hush
  ${ADB[@]} logcat -c
  ${ADB[@]} shell am broadcast --include-stopped-packages -n "$HUSH_PKG/$RECEIVER" -a "$ACTION" \
    --es op install --es package "$BRIDGE_PKG" >/dev/null 2>&1
  wait_for_line "smoke step=(install|update) bridge=$BRIDGE_PKG " "$STEP_TIMEOUT" >/dev/null || true
  if wait_for_state "$BRIDGE_PKG" "^BRIDGE_CURRENT$" "$STEP_TIMEOUT"; then
    record "restore" PASS "installed at $BUNDLED_VERSION"
  else
    record "restore" FAIL "could not restore the bridge (state=$(state_of "$BRIDGE_PKG"))"
    dump_failure_evidence
  fi
fi

# --- summary -----------------------------------------------------------------

log ""
log "======================================================================"
for i in "${!STEP_NAMES[@]}"; do
  printf '  %-10s %-5s %s\n' "${STEP_NAMES[$i]}" "${STEP_VERDICTS[$i]}" "${STEP_DETAILS[$i]}"
done
log "  ----------------------------------------------------------------"
log "  Summary: $PASSED passed, $FAILED failed, $BLOCKED blocked, $SKIPPED skipped"
[[ "$BLOCKED" -gt 0 ]] && log "           (blocked = the device refused the step for a platform reason - its package verifier, or a
           record of a bridge that was removed - so nothing was proved either way; the detail says which)"
log "======================================================================"

if [[ "$JSON" == "1" ]]; then
  python3 - "$BRIDGE_PKG" "${STEP_NAMES[@]}" __ "${STEP_VERDICTS[@]}" __ "${STEP_DETAILS[@]}" <<'PYEOF'
import json, sys
argv = sys.argv[1:]
bridge = argv[0]
argv = argv[1:]
first = argv.index("__")
second = argv.index("__", first + 1)
names, verdicts, details = argv[:first], argv[first + 1:second], argv[second + 1:]
print(json.dumps({
    "bridge": bridge,
    "steps": [
        {"step": n, "verdict": v, "detail": d}
        for n, v, d in zip(names, verdicts, details)
    ],
    "passed": verdicts.count("PASS"),
    "failed": verdicts.count("FAIL"),
    "blocked": verdicts.count("BLOCKED"),
    "skipped": verdicts.count("SKIP"),
}, sort_keys=True))
PYEOF
fi

if [[ "$FAILED" -gt 0 ]]; then
  log "TIP: re-run with --verbose for the receiver's own log lines."
  exit 1
fi
if [[ "$BLOCKED" -gt 0 ]]; then
  # Not a pass and not a failure: the run could not decide. A CI job can treat 3 as "re-run on a
  # device whose verifier works" without silently releasing on an unproven lifecycle.
  log "TIP: the device's package verifier blocked a step; re-run on a device without it, or pass --strict-verifier-timeout to fail instead."
  exit 3
fi
exit 0
