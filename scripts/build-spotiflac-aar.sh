#!/usr/bin/env bash
# Build the upstream SpotiFLAC Go runtime AAR (gobackend) used for native
# SpotiFLAC playback. The AAR is produced from the SpotiFLAC-Mobile go_backend
# via gomobile and lands at app/libs/gobackend.aar.
#
# Upstream deleted go_backend in 5.0.x and replaced it with rust_backend, so a checkout on a
# current tag has nothing to build from. This script therefore pins the Go tree: the checkout has
# to be on SPOTIFLAC_BACKEND_PIN, which is the last commit that still carries it. Bumping that pin
# is a backend migration (Cargo + bindgen + a new JNI surface), not a checkout update.
#
# Requirements: Go >= 1.26, Android SDK + NDK (ANDROID_NDK_HOME or the default
# sdk.dir from local.properties), gomobile + gobind installed (see steps below).
#
# Usage: scripts/build-spotiflac-aar.sh [path-to-SpotiFLAC-Mobile-checkout]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PARENT_DIR="${1:-$ROOT_DIR/SpotiFLAC-Mobile}"
GO_BACKEND="$PARENT_DIR/go_backend"
OUT_AAR="$ROOT_DIR/app/libs/gobackend.aar"

# v4.9.6-3: the last SpotiFLAC-Mobile commit whose go_backend still exists.
SPOTIFLAC_BACKEND_PIN="a34e2935"
SPOTIFLAC_UPSTREAM="https://github.com/spotiflacapp/SpotiFLAC-Mobile.git"

if [[ ! -d "$PARENT_DIR" ]]; then
  echo "SpotiFLAC-Mobile checkout not found at: $PARENT_DIR" >&2
  echo "Clone it first: git clone --depth 1 $SPOTIFLAC_UPSTREAM" >&2
  exit 1
fi

if [[ ! -d "$GO_BACKEND" ]]; then
  echo "No go_backend in: $GO_BACKEND" >&2
  echo "" >&2
  echo "Upstream replaced the Go backend with rust_backend (5.0.x), so a checkout on a current" >&2
  echo "tag has nothing for gomobile to bind. Hush builds from the Go tree, so put the checkout" >&2
  echo "back on the pin this script expects:" >&2
  echo "" >&2
  echo "  git -C '$PARENT_DIR' fetch origin && git -C '$PARENT_DIR' checkout $SPOTIFLAC_BACKEND_PIN" >&2
  echo "" >&2
  echo "Moving the pin means migrating to rust_backend, not running this script against main." >&2
  exit 1
fi

# Building from an unpinned checkout is the one way this fails silently: the tree still exists but
# no longer matches the runtime Hush was verified against, so say so before spending the build.
if git -C "$PARENT_DIR" rev-parse --git-dir >/dev/null 2>&1; then
  HEAD_SHA="$(git -C "$PARENT_DIR" rev-parse HEAD 2>/dev/null || true)"
  case "$HEAD_SHA" in
    "$SPOTIFLAC_BACKEND_PIN"*) ;;
    *)
      echo "warning: SpotiFLAC-Mobile is at ${HEAD_SHA:-unknown}, not the pinned $SPOTIFLAC_BACKEND_PIN" >&2
      echo "warning: building gobackend.aar from an unverified go_backend" >&2
      ;;
  esac
fi

command -v go >/dev/null 2>&1 || { echo "Go toolchain is required (go.mod needs >= 1.26.5)" >&2; exit 1; }

# Resolve the Android SDK/NDK the same way Gradle does.
SDK_DIR="${ANDROID_HOME:-}"
if [[ -z "$SDK_DIR" && -f "$ROOT_DIR/local.properties" ]]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tail -1)"
fi
[[ -n "$SDK_DIR" ]] || { echo "Android SDK location unknown (set ANDROID_HOME or sdk.dir)" >&2; exit 1; }

NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/android-ndk-r29}"
if [[ ! -d "$NDK_DIR" ]]; then
  # Fall back to whatever single NDK is installed.
  NDK_DIR="$(ls -d "$SDK_DIR"/ndk/* 2>/dev/null | head -1 || true)"
fi
[[ -n "$NDK_DIR" && -d "$NDK_DIR" ]] || { echo "Android NDK not found under $SDK_DIR/ndk" >&2; exit 1; }

export ANDROID_HOME="$SDK_DIR"
export ANDROID_NDK_HOME="$NDK_DIR"

# Install the go.mod-pinned gomobile/gobind versions (reproducible builds).
GOBIN_DIR="$(mktemp -d)"
(cd "$GO_BACKEND" && GOBIN="$GOBIN_DIR" go install golang.org/x/mobile/cmd/gomobile)
(cd "$GO_BACKEND" && GOBIN="$GOBIN_DIR" go install golang.org/x/mobile/cmd/gobind)
export PATH="$GOBIN_DIR:$PATH"

(cd "$GO_BACKEND" && gomobile init)

mkdir -p "$(dirname "$OUT_AAR")"
# arm/arm64 only: Hush's abiFilters strip every other ABI anyway.
(cd "$GO_BACKEND" && gomobile bind -target=android/arm,android/arm64 -androidapi 24 -o "$OUT_AAR" .)

echo "gobackend.aar written to: $OUT_AAR ($(du -h "$OUT_AAR" | cut -f1))"
