#!/usr/bin/env bash
# Build the upstream SpotiFLAC Go runtime AAR (gobackend) used for native
# SpotiFLAC playback. The AAR is produced from the SpotiFLAC-Mobile go_backend
# via gomobile and lands at app/libs/gobackend.aar.
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

if [[ ! -d "$GO_BACKEND" ]]; then
  echo "SpotiFLAC-Mobile checkout not found at: $PARENT_DIR" >&2
  echo "Clone it first: git clone --depth 1 https://github.com/spotiflacapp/SpotiFLAC-Mobile.git" >&2
  exit 1
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
