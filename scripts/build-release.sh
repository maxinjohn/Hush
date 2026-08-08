#!/usr/bin/env bash
# Build + sign any Hush APK locally (release or debug, all flavors: foss/gms × mobile/tv × all ABIs).
# Usage: scripts/build-release.sh [distribution] [device] [abi] [debug|release]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DISTRIBUTIONS=(foss gms)
DEVICES=(mobile tv)
ABIS=(universal arm64 armeabi x86 x86_64)
BUILD_TYPES=(release debug)
BUILD_TYPE="release"

capitalize() {
  local value="$1"
  printf '%s%s' "$(printf '%.1s' "$value" | tr '[:lower:]' '[:upper:]')" "${value#?}"
}

variant_paths() {
  local distribution="$1"
  local device="$2"
  local abi="$3"
  local build_type="${4:-release}"
  local device_cap abi_cap build_type_cap
  device_cap="$(capitalize "$device")"
  abi_cap="$(capitalize "$abi")"
  build_type_cap="$(capitalize "$build_type")"
  GRADLE_TASK=":app:assemble$(capitalize "$distribution")${device_cap}${abi_cap}${build_type_cap}"
  APK_DIR="app/build/outputs/apk/${distribution}${device_cap}${abi_cap}/${build_type}"
  APK_NAME="hush-${distribution}-${device}-${abi}-${build_type}.apk"
  APK_PATH="${APK_DIR}/${APK_NAME}"
  BUILD_TYPE="$build_type"
}

print_usage() {
  cat <<'EOF'
Usage: scripts/build-release.sh [distribution] [device] [abi] [debug|release]

Local builds. Gradle assembles an APK; release builds are re-signed.

Arguments (defaults: foss mobile arm64 release):
  distribution   foss | gms
  device         mobile | tv
  abi            universal | arm64 | armeabi | x86 | x86_64 | all
  build type     debug | release  (default: release)

Special targets:
  list, --list              Print every variant and Gradle task name
  all                       Build all 20 variants (slow)
  mobile-all [distribution] Build all 5 mobile ABIs
  tv-all [distribution]     Build all 5 TV ABIs

Examples:
  scripts/build-release.sh gms mobile universal         # release
  scripts/build-release.sh gms universal debug          # debug
  scripts/build-release.sh foss arm64 debug             # debug
  scripts/build-release.sh gms mobile x86_64
  scripts/build-release.sh foss mobile all
  scripts/build-release.sh mobile-all gms
  scripts/build-release.sh list

Wrappers (same script):
  scripts/build-foss-mobile-release.sh [abi]
  scripts/build-gms-mobile-release.sh [abi]

Clean before build (use instead of ./gradlew clean, which can hang on macOS):
  bash scripts/clean-build-safe.sh
EOF
}

print_variants() {
  echo "All local release variants:"
  echo ""
  printf "  %-5s %-7s %-10s  %s\n" "DIST" "DEVICE" "ABI" "APK filename"
  for distribution in "${DISTRIBUTIONS[@]}"; do
    for device in "${DEVICES[@]}"; do
      for abi in "${ABIS[@]}"; do
        variant_paths "$distribution" "$device" "$abi"
        printf "  %-5s %-7s %-10s  %s\n" "$distribution" "$device" "$abi" "$APK_NAME"
      done
    done
  done
}

run_gradle() {
  local abi="$1"
  local task="$2"
  local gradle_args=(--no-daemon --max-workers=2)
  if [ "$abi" = "armeabi" ] || [ "$abi" = "x86" ]; then
    ./gradlew "$task" "${gradle_args[@]}" 2>&1 | python3 -c "import re,sys;p=re.compile(r'^(WARNING: )?\[CXX5202\] This app only has 32-bit \[(armeabi-v7a|x86)\] native libraries\. Beginning August 1, 2019 Google Play store requires that all apps that include native libraries must provide 64-bit versions\. For more information, visit https://g\.co/64-bit-requirement\$');[sys.stdout.write(l) for l in sys.stdin if not p.match(l.rstrip())]"
  else
    ./gradlew "$task" "${gradle_args[@]}"
  fi
}

build_variant() {
  local distribution="$1"
  local device="$2"
  local abi="$3"
  local build_type="$4"
  variant_paths "$distribution" "$device" "$abi" "$build_type"

  local shim_suffix="Release"
  if [ "$build_type" = "debug" ]; then
    shim_suffix="Debug"
  fi

  if [ "$device" = "mobile" ]; then
    echo ""
    echo "==> Building Waze shim APKs"
    ./gradlew ":waze-shim:assembleSpotify${shim_suffix}" ":waze-shim:assembleYoutubeMusic${shim_suffix}" ":waze-shim:assembleDeezer${shim_suffix}" --no-daemon --max-workers=2
    (cd waze-shim/build/outputs/apk && rm -f waze-shims.zip && zip -j waze-shims.zip "spotify/${build_type}/waze-shim-spotify-${build_type}.apk" "youtubeMusic/${build_type}/waze-shim-youtubeMusic-${build_type}.apk" "deezer/${build_type}/waze-shim-deezer-${build_type}.apk" && cp waze-shims.zip "${ROOT_DIR}/app/src/mobile/assets/")
  fi
  echo "==> Building ${APK_NAME}"
  run_gradle "$abi" "$GRADLE_TASK"
  if [ "$build_type" = "debug" ]; then
    echo "    $ROOT_DIR/$APK_PATH"
  else
    bash "$ROOT_DIR/scripts/resign-release-apk.sh" "$ROOT_DIR/$APK_PATH"
    echo "    $ROOT_DIR/$APK_PATH"
  fi
}

build_abi_set() {
  local distribution="$1"
  local device="$2"
  local abi_arg="$3"
  local build_type="$4"
  if [ "$abi_arg" = "all" ]; then
    for abi in "${ABIS[@]}"; do
      build_variant "$distribution" "$device" "$abi" "$build_type"
    done
  else
    build_variant "$distribution" "$device" "$abi_arg" "$build_type"
  fi
}

if [ "${1:-}" = "list" ] || [ "${1:-}" = "--list" ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    print_usage
    echo ""
  fi
  print_variants
  exit 0
fi

if [ "${1:-}" = "all" ]; then
  echo "Building all 20 release variants..."
  for distribution in "${DISTRIBUTIONS[@]}"; do
    for device in "${DEVICES[@]}"; do
      for abi in "${ABIS[@]}"; do
        build_variant "$distribution" "$device" "$abi"
      done
    done
  done
  exit 0
fi

if [ "${1:-}" = "mobile-all" ]; then
  distribution="${2:-foss}"
  build_abi_set "$distribution" mobile all "${3:-release}"
  exit 0
fi

if [ "${1:-}" = "tv-all" ]; then
  distribution="${2:-gms}"
  build_abi_set "$distribution" tv all "${3:-release}"
  exit 0
fi

DISTRIBUTION="${1:-foss}"
DEVICE="${2:-mobile}"
ABI="${3:-arm64}"
BUILD_TYPE="${4:-release}"

# Support "debug" or "release" as last argument anywhere
for arg in "$@"; do
  if [ "$arg" = "debug" ] || [ "$arg" = "release" ]; then
    BUILD_TYPE="$arg"
    break
  fi
done

# Strip build_type from positional args so back-compat parsing works
for arg in "$@"; do
  if [ "$arg" = "debug" ] || [ "$arg" = "release" ]; then
    continue
  fi
  if [ -z "${POS_ARGS:-}" ]; then
    POS_ARGS="$arg"
  else
    POS_ARGS="$POS_ARGS $arg"
  fi
done
set -- ${POS_ARGS:-foss mobile arm64}
DISTRIBUTION="${1:-foss}"
DEVICE="${2:-mobile}"
ABI="${3:-arm64}"

# Back-compat: "foss arm64" without "mobile"
if [ "$DEVICE" = "arm64" ] || [ "$DEVICE" = "universal" ] || [ "$DEVICE" = "armeabi" ] || [ "$DEVICE" = "x86" ] || [ "$DEVICE" = "x86_64" ] || [ "$DEVICE" = "all" ]; then
  ABI="$DEVICE"
  DEVICE="mobile"
fi

# Back-compat: "gms universal" without "mobile"
if [ "$DISTRIBUTION" = "gms" ] || [ "$DISTRIBUTION" = "foss" ]; then
  :
elif [ "$DISTRIBUTION" = "arm64" ] || [ "$DISTRIBUTION" = "universal" ] || [ "$DISTRIBUTION" = "armeabi" ] || [ "$DISTRIBUTION" = "x86" ] || [ "$DISTRIBUTION" = "x86_64" ] || [ "$DISTRIBUTION" = "all" ]; then
  ABI="$DISTRIBUTION"
  DISTRIBUTION="foss"
  DEVICE="mobile"
fi

case "$DISTRIBUTION" in
  foss|gms) ;;
  *)
    print_usage
    exit 1
    ;;
esac

case "$DEVICE" in
  mobile|tv) ;;
  *)
    print_usage
    exit 1
    ;;
esac

case "$ABI" in
  universal|arm64|armeabi|x86|x86_64|all) ;;
  *)
    print_usage
    exit 1
    ;;
esac

build_abi_set "$DISTRIBUTION" "$DEVICE" "$ABI" "$BUILD_TYPE"

echo ""
echo "Done ($BUILD_TYPE build)."
echo "Done."
