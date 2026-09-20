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
  - whether each launch was genuinely cold (the process was really gone first)
  - the platform's own cold-start timing (am start -W)
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
printf 'run\tcold\tthis_ms\ttotal_ms\twait_ms\tpss_kb\trss_kb\tjava_heap_kb\tnative_heap_kb\tstream_errors\n' > "$report"

echo "Hush performance benchmark: package=$PKG runs=$RUNS wait=${WAIT_SECONDS}s"
echo "Report: $report"

launcher="$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tail -1 | tr -d '\r')"
if [[ -z "$launcher" || "$launcher" != */* ]]; then
  echo "Could not resolve the launcher activity for $PKG" >&2
  exit 2
fi
echo "Launcher: $launcher"

for run in $(seq 1 "$RUNS"); do
  log="$OUT_DIR/run-${run}-$(date +%Y%m%d-%H%M%S).log"
  adb logcat -c
  adb shell am force-stop "$PKG"
  # am force-stop is asynchronous on some OEM builds, and a foreground playback
  # service can be restarted behind it. Waiting for pidof to empty is not enough:
  # the previous harness assumed the kill had landed and reported a 0 ms start
  # (a warm process it had never actually killed) or a TIMEOUT it never checked
  # for. Only a run whose process is genuinely gone is timed.
  cold=no
  for _ in $(seq 1 60); do
    if [[ -z "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)" ]]; then
      cold=yes
      break
    fi
    sleep 0.25
  done

  # am start -W reports the platform's own timing instead of polling dumpsys
  # window, which measured how long the poll took as often as how long the app
  # took to draw.
  start_out="$(adb shell am start -W -n "$launcher" 2>/dev/null | tr -d '\r' || true)"
  this_ms=$(printf '%s\n' "$start_out" | awk '/ThisTime/ {print $2; exit}')
  total_ms=$(printf '%s\n' "$start_out" | awk '/^TotalTime:/ {print $2}')
  wait_ms=$(printf '%s\n' "$start_out" | awk '/^WaitTime:/ {print $2}')
  if [[ -z "$total_ms" ]]; then
    this_ms="TIMEOUT"; total_ms="TIMEOUT"; wait_ms="TIMEOUT"
  fi

  sleep "$WAIT_SECONDS"
  adb logcat -d -v threadtime > "$log"
  mem=$(adb shell dumpsys meminfo "$PKG" 2>/dev/null || true)
  # A busy device occasionally answers meminfo with nothing. A zero row in the
  # report reads as "no memory", so retry once before recording it.
  if [[ -z "$(printf '%s\n' "$mem" | awk '/TOTAL PSS:/ {print $3; exit}')" ]]; then
    sleep 2
    mem=$(adb shell dumpsys meminfo "$PKG" 2>/dev/null || true)
  fi
  pss=$(printf '%s\n' "$mem" | awk '/TOTAL PSS:/ {print $3; exit}')
  rss=$(printf '%s\n' "$mem" | awk '/TOTAL PSS:/ {print $6; exit}')
  java=$(printf '%s\n' "$mem" | awk '/Java Heap:/ {print $3; exit}')
  native=$(printf '%s\n' "$mem" | awk '/Native Heap:/ {print $3; exit}')
  # An absent reading is reported as n/a, never as zero: a zero row would read as
  # "this build uses no memory" in a comparison.
  pss=${pss:-n/a}; rss=${rss:-n/a}; java=${java:-n/a}; native=${native:-n/a}
  errors=$(grep -Eic 'FATAL EXCEPTION|OutOfMemoryError|onPlayerError|No stream available|BadStreamPlayerResponse' "$log" || true)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$run" "$cold" "$this_ms" "$total_ms" "$wait_ms" "$pss" "$rss" "$java" "$native" "$errors" >> "$report"
  printf 'run %s: cold=%s start=%sms (this=%sms wait=%sms) PSS=%sKB RSS=%sKB Java=%sKB Native=%sKB errors=%s\n' \
    "$run" "$cold" "$total_ms" "$this_ms" "$wait_ms" "$pss" "$rss" "$java" "$native" "$errors"
done

echo "Playback-resolution evidence (latest run):"
latest_log="$OUT_DIR/run-${RUNS}-"*'.log'
latest_log=$(ls -t "$OUT_DIR"/run-*.log 2>/dev/null | head -1 || true)
if [[ -n "$latest_log" ]]; then
  grep -E 'Source routing:|Fetching player response|onPlayerError|No stream available|BadStreamPlayerResponse|STATE_READY|onIsPlayingChanged' "$latest_log" | tail -60 || true
fi
echo "Saved $report"
