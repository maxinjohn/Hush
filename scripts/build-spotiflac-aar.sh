#!/usr/bin/env bash
# Build the upstream Go runtime AAR (gobackend) used for native lossless playback. The AAR is
# produced from the go_backend of the upstream app - SpotiFLAC-Mobile until it was renamed Mornye on
# 2026-10-20, `SpotiFLAC-Mobile/` on disk here because that is the directory every existing
# checkout and document uses - via gomobile, and lands at app/libs/gobackend.aar.
#
# Upstream deleted go_backend in 5.0.x and replaced it with rust_backend, so a checkout on a
# current tag has nothing to build from. This script therefore pins the Go tree: the checkout has
# to be on the Go revision scripts/spotiflac-backend-pins.sh names, which is the last commit that
# still carries it. That file is the one place a backend revision is written down - this script and
# scripts/build-spotiflac-rust.sh both read it, so the AAR built here and the Rust tree built there
# cannot drift apart. Bumping the Go pin is a backend migration (Cargo + bindgen + a new JNI
# surface), not a checkout update.
#
# Requirements: Go >= 1.26, Android SDK + NDK (ANDROID_NDK_HOME or the default
# sdk.dir from local.properties), gomobile + gobind installed (see steps below).
#
# Usage: scripts/build-spotiflac-aar.sh [--checkout-only] [path-to-SpotiFLAC-Mobile-checkout]
#   --checkout-only   verify the checkout is on the pinned Go revision, then stop (no build)
set -euo pipefail

usage() {
  sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Backend revisions, read from their single definition rather than restated: a pin spelled out in
# both build scripts is a pin that can be bumped in one of them and forgotten in the other.
PINS_FILE="$SCRIPT_DIR/spotiflac-backend-pins.sh"
[[ -f "$PINS_FILE" ]] || { echo "error: missing $PINS_FILE (the backend pins live there)" >&2; exit 1; }
# shellcheck source=scripts/spotiflac-backend-pins.sh
source "$PINS_FILE"

CHECKOUT_ONLY=0
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --checkout-only) CHECKOUT_ONLY=1 ;;
    -h|--help) usage; exit 0 ;;
    --*) echo "error: unknown option: $arg" >&2; exit 2 ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done

PARENT_DIR="${POSITIONAL[0]:-$ROOT_DIR/$SPOTIFLAC_MOBILE_DIR}"
GO_BACKEND="$PARENT_DIR/go_backend"
OUT_AAR="$ROOT_DIR/app/libs/gobackend.aar"

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
  echo "  git -C '$PARENT_DIR' fetch origin && git -C '$PARENT_DIR' checkout $SPOTIFLAC_GO_BACKEND_PIN" >&2
  echo "" >&2
  echo "Moving the pin means migrating to rust_backend, not running this script against main." >&2
  exit 1
fi

# Building from an unpinned checkout is the one way this fails silently: the tree still exists but
# no longer matches the runtime Hush was verified against, so say so before spending the build.
# Compared against the full SHA rather than the seven characters the pin is read as: a prefix match
# accepts any commit that happens to start the same way.
HEAD_SHA=""
if git -C "$PARENT_DIR" rev-parse --git-dir >/dev/null 2>&1; then
  HEAD_SHA="$(git -C "$PARENT_DIR" rev-parse HEAD 2>/dev/null || true)"
  if [[ "$HEAD_SHA" != "$SPOTIFLAC_GO_BACKEND_SHA" ]]; then
    echo "warning: SpotiFLAC-Mobile is at ${HEAD_SHA:-unknown}, not the pinned $SPOTIFLAC_GO_BACKEND_PIN ($SPOTIFLAC_GO_BACKEND_SHA)" >&2
    echo "warning: building gobackend.aar from an unverified go_backend" >&2
  fi
fi

# The pinned revision is the whole reason this script exists, so a caller can ask for exactly that
# check without paying for a gomobile build - the same flag the Rust script offers.
if [[ "$CHECKOUT_ONLY" == "1" ]]; then
  if [[ "$HEAD_SHA" != "$SPOTIFLAC_GO_BACKEND_SHA" ]]; then
    echo "error: $PARENT_DIR is at ${HEAD_SHA:-unknown}, not the pinned $SPOTIFLAC_GO_BACKEND_PIN ($SPOTIFLAC_GO_BACKEND_SHA)" >&2
    exit 1
  fi
  echo "Checkout verified at $SPOTIFLAC_GO_BACKEND_SHA (go_backend present); --checkout-only, stopping."
  exit 0
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
#
# -ldflags="-s -w": without it the AAR is a debug build carrying full DWARF and a .symtab/.strtab
# (9.60 MB per ABI, 19.2 MB of the two slices), which compresses down but still ships in every APK.
# Stripping takes the AAR 33.33 -> 18.47 MB and the universal release APK 49.15 -> 34.50 MB, and is
# inert on the JNI surface: classes.jar is byte-identical, the 1752 Java_gobackend_* exports and the
# DT_NEEDED/PT_LOAD tables are unchanged, and .text/.gopclntab (Go tracebacks)/.rodata/.rela.dyn are
# identical in size. Only .debug_*, .symtab/.strtab and .note.gnu.build-id go, so a native crash
# symbolicated from the shipped .so needs a separate unstripped build. Measurements in
# docs/spotiflac-go-aar-size-investigation.md.
(cd "$GO_BACKEND" && gomobile bind -target=android/arm,android/arm64 -androidapi "$SPOTIFLAC_ANDROID_API" -ldflags="-s -w" -o "$OUT_AAR" .)

echo "gobackend.aar written to: $OUT_AAR ($(du -h "$OUT_AAR" | cut -f1))"
