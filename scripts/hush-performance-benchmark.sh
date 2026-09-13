#!/usr/bin/env bash
# Repeatable Hush debug-build performance probe.
# Measures process/activity cold-start latency and captures memory plus playback
# resolution evidence. It deliberately does not install, clear app data, or
# alter device settings.
set -euo pipefail

PKG="app.hush.music.debug"
RUNS=3
WAIT_SECONDS=8
OUT_DIR=".freebuff/performance"

usage() {
  cat <<'EOF'
Usage: scripts/hush-performance-benchmark.sh [--pkg PACKAGE] [--runs N] [--wait SECONDS]

The package must already be installed. The script records:
  - time to process start
  - time to the Hush activity becoming focused
  - PSS/RSS/heap after the wait period
  - stream-resolution and playback errors from logcat
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pkg) PKG="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --wait) WAIT_SECONDS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v adb >/dev/null || { echo "adb is required" >&2; exit 2; }
[[ "$(adb devices | awk 'NR==2 {print $2}')" == "device" ]] || {
  echo "No authorized adb device is connected" >&2
  exit 2
}
mkdir -p "$OUT_DIR"
report="$OUT_DIR/report-$(date +%Y%m%d-%H%M%S).tsv"
printf 'run\tprocess_ms\tfocus_ms\tpss_kb\trss_kb\tjava_heap_kb\tnative_heap_kb\tstream_errors\n' > "$report"

echo "Hush performance benchmark: package=$PKG runs=$RUNS wait=${WAIT_SECONDS}s"
echo "Report: $report"

for run in $(seq 1 "$RUNS"); do
  log="$OUT_DIR/run-${run}-$(date +%Y%m%d-%H%M%S).log"
  adb logcat -c
  adb shell am force-stop "$PKG"
  # am force-stop is asynchronous on some OEM builds. Do not measure against
  # the previous process; wait until the package has really disappeared.
  for _ in $(seq 1 50); do
    if [[ -z "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)" ]]; then
      break
    fi
    sleep 0.1
  done
  start_ns=$(python3 -c 'import time; print(time.monotonic_ns())')
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

  process_ms=""
  focus_ms=""
  for _ in $(seq 1 80); do
    now_ns=$(python3 -c 'import time; print(time.monotonic_ns())')
    process_state=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
    focus_state=$(adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | grep -c "$PKG" || true)
    if [[ -z "$process_ms" ]] && [[ -n "$process_state" ]]; then
      elapsed_ms=$(( (now_ns - start_ns) / 1000000 ))
      if (( elapsed_ms >= 0 )); then
        process_ms=$elapsed_ms
      fi
    fi
    if [[ -z "$focus_ms" ]] && [[ "$focus_state" -gt 0 ]]; then
      elapsed_ms=$(( (now_ns - start_ns) / 1000000 ))
      if (( elapsed_ms >= 0 )); then
        focus_ms=$elapsed_ms
      fi
    fi
    [[ -n "$process_ms" && -n "$focus_ms" ]] && break
    sleep 0.1
  done
  if [[ -z "$process_ms" ]]; then process_ms="TIMEOUT"; fi
  if [[ -z "$focus_ms" ]]; then focus_ms="TIMEOUT"; fi

  sleep "$WAIT_SECONDS"
  adb logcat -d -v threadtime > "$log"
  mem=$(adb shell dumpsys meminfo "$PKG" 2>/dev/null || true)
  pss=$(printf '%s\n' "$mem" | awk '/TOTAL PSS:/ {print $3; exit}')
  rss=$(printf '%s\n' "$mem" | awk '/TOTAL PSS:/ {print $6; exit}')
  java=$(printf '%s\n' "$mem" | awk '/Java Heap:/ {print $3; exit}')
  native=$(printf '%s\n' "$mem" | awk '/Native Heap:/ {print $3; exit}')
  pss=${pss:-0}; rss=${rss:-0}; java=${java:-0}; native=${native:-0}
  errors=$(grep -Eic 'FATAL EXCEPTION|OutOfMemoryError|onPlayerError|No stream available|BadStreamPlayerResponse' "$log" || true)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$run" "$process_ms" "$focus_ms" "$pss" "$rss" "$java" "$native" "$errors" >> "$report"
  printf 'run %s: process=%sms focus=%sms PSS=%sKB RSS=%sKB Java=%sKB Native=%sKB errors=%s\n' \
    "$run" "$process_ms" "$focus_ms" "$pss" "$rss" "$java" "$native" "$errors"
done

echo "Playback-resolution evidence (latest run):"
latest_log="$OUT_DIR/run-${RUNS}-"*'.log'
latest_log=$(ls -t "$OUT_DIR"/run-*.log 2>/dev/null | head -1 || true)
if [[ -n "$latest_log" ]]; then
  grep -E 'Source routing:|Fetching player response|onPlayerError|No stream available|BadStreamPlayerResponse|STATE_READY|onIsPlayingChanged' "$latest_log" | tail -60 || true
fi
echo "Saved $report"
