#!/usr/bin/env bash
# Build upstream's SpotiFLAC Rust backend (rust_backend, UniFFI) for Hush's two
# shipped Android ABIs and stage the results where Gradle can pick them up.
#
# Replaces the one-off worktree the 2026-10-07 feasibility spike used: this script
# materializes its own pinned checkout (a git worktree of the SpotiFLAC-Mobile
# clone), so the build is reproducible from the repo instead of from a directory
# someone happened to create by hand.
#
# Which revision is "pinned" is not decided here:
# scripts/spotiflac-backend-pins.sh states it, and scripts/build-spotiflac-aar.sh reads the same
# file for the Go tree Hush ships today. Bump the backend there, not in either build script.
#
# Outputs (both gitignored):
#   app/libs/spotiflac-rust/jniLibs/<abi>/libspotiflac_mobile.so
#   app/libs/spotiflac-rust/kotlin/com/spotiflac/backend/spotiflac_mobile.kt
#
# Upstream stages the same two directories under rust_backend/target/ and wires
# them into Gradle with java.srcDir + jniLibs.srcDir; that wiring is Phase 4 and
# is intentionally not done here.
#
# Requirements: rustup + cargo (the workspace pins 1.98.1 via rust-toolchain.toml),
# the two Android Rust targets, an Android NDK (r29 verified; the NDK version
# upstream pins, 29.0.14206865, is not required), and a libclang bindgen can use.
#
# Usage: scripts/build-spotiflac-rust.sh [--checkout-only] [path-to-SpotiFLAC-Mobile-clone]
#   --checkout-only   materialize + verify the pinned source, then stop (no build)
#   SPOTIFLAC_RUST_SRC=/path   use an existing checkout instead of materializing one
set -euo pipefail

usage() {
  sed -n '2,27p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() { echo "error: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

CHECKOUT_ONLY=0
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --checkout-only) CHECKOUT_ONLY=1 ;;
    -h|--help) usage; exit 0 ;;
    --*) die "unknown option: $arg" ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done

# Backend revisions and the clone they come from, from their single definition. Neither the Go
# revision nor the Rust one is restated here: scripts/spotiflac-backend-pins.sh states both, and the
# Go AAR script reads the same file, which is what keeps the two from naming different backends.
PINS_FILE="$SCRIPT_DIR/spotiflac-backend-pins.sh"
[[ -f "$PINS_FILE" ]] || die "missing $PINS_FILE (the backend pins live there)"
# shellcheck source=scripts/spotiflac-backend-pins.sh
source "$PINS_FILE"

# The SpotiFLAC-Mobile clone the Go AAR script also uses. Its worktree is where the
# pinned Rust tree comes from, so the two backends share one object store.
BASE_CLONE="${POSITIONAL[0]:-${SPOTIFLAC_MOBILE_SRC:-$ROOT_DIR/$SPOTIFLAC_MOBILE_DIR}}"
# The pinned Rust source. Defaults to a script-managed worktree at the repo root.
SRC="${SPOTIFLAC_RUST_SRC:-$ROOT_DIR/SpotiFLAC-Rust}"
STAGE_ROOT="$ROOT_DIR/app/libs/spotiflac-rust"

# Android API level, from the shared pins: the Rust linker and sysroot suffix have to match the Go
# AAR's -androidapi, or the two backends are built for different device ranges.
ANDROID_API="$SPOTIFLAC_ANDROID_API"

# --- pinned source checkout ---------------------------------------------------

resolved_sha() {
  git -C "$1" rev-parse HEAD 2>/dev/null || true
}

# Guarantee $SRC holds exactly the pinned tree, creating a worktree of the base
# clone when it does not yet exist. Never deletes an existing checkout: a wrong
# one is reported, not reset, so uncommitted state is never lost.
materialize_checkout() {
  if [[ ! -d "$BASE_CLONE" ]]; then
    echo "SpotiFLAC-Mobile clone not found at $BASE_CLONE; cloning $SPOTIFLAC_UPSTREAM"
    git clone "$SPOTIFLAC_UPSTREAM" "$BASE_CLONE"
  fi
  git -C "$BASE_CLONE" rev-parse --git-dir >/dev/null 2>&1 \
    || die "$BASE_CLONE is not a git checkout"

  # Make sure the pinned tag and its objects are present. A shallow clone cannot
  # check out other history, so unshallow it first if that is what we were given.
  if [[ "$(git -C "$BASE_CLONE" rev-parse --is-shallow-repository)" == "true" ]]; then
    echo "Unshallowing $BASE_CLONE so the pinned tag can be checked out"
    git -C "$BASE_CLONE" fetch --unshallow --tags --force origin
  else
    git -C "$BASE_CLONE" fetch --tags --force origin
  fi

  if git -C "$SRC" rev-parse --git-dir >/dev/null 2>&1; then
    echo "Reusing existing checkout at $SRC"
  elif [[ -e "$SRC" ]]; then
    die "$SRC exists but is not a git checkout; move it aside or set SPOTIFLAC_RUST_SRC"
  else
    mkdir -p "$(dirname "$SRC")"
    echo "Creating worktree at $SRC ($SPOTIFLAC_RUST_TAG)"
    git -C "$BASE_CLONE" worktree add --detach "$SRC" "$SPOTIFLAC_RUST_TAG"
  fi
}

if [[ -n "${SPOTIFLAC_RUST_SRC:-}" ]]; then
  [[ -d "$SRC/rust_backend" ]] || die "SPOTIFLAC_RUST_SRC=$SRC has no rust_backend/"
else
  materialize_checkout
fi

HAVE_SHA="$(resolved_sha "$SRC")"
if [[ -n "$HAVE_SHA" ]]; then SHA_SHORT="${HAVE_SHA:0:7}"; else SHA_SHORT="unknown"; fi
if [[ -n "$HAVE_SHA" && "$HAVE_SHA" != "$SPOTIFLAC_RUST_SHA" ]]; then
  echo "warning: $SRC is at ${HAVE_SHA:0:7}, not the pinned ${SPOTIFLAC_RUST_SHA:0:7} ($SPOTIFLAC_RUST_TAG)" >&2
  echo "warning: building rust_backend from an unverified tree" >&2
fi

RUST_BACKEND_DIR="$SRC/rust_backend"
[[ -f "$RUST_BACKEND_DIR/Cargo.toml" ]] \
  || die "no rust_backend/Cargo.toml under $SRC (is this the Go-era pin? it needs $SPOTIFLAC_RUST_TAG)"

CARGO_TARGET_DIR="${SPOTIFLAC_RUST_TARGET_DIR:-$RUST_BACKEND_DIR/target}"
export CARGO_TARGET_DIR

echo "Source:   $SRC ($SHA_SHORT)"
echo "Target:   $CARGO_TARGET_DIR"

if [[ "$CHECKOUT_ONLY" == "1" ]]; then
  [[ -z "$HAVE_SHA" || "$HAVE_SHA" == "$SPOTIFLAC_RUST_SHA" ]] \
    || die "checkout is not at the pinned commit"
  echo "Checkout verified at $SPOTIFLAC_RUST_SHA; --checkout-only, stopping."
  exit 0
fi

# --- host toolchain ----------------------------------------------------------

command -v cargo >/dev/null 2>&1 || die "cargo not found (install rustup: https://rustup.rs)"
if command -v rustup >/dev/null 2>&1; then
  HAVE_RUSTUP=1
else
  HAVE_RUSTUP=0
  echo "warning: rustup not found; assuming the Android targets are already installed" >&2
fi

# bindgen (rquickjs) and cc-rs (QuickJS) need a libclang. Only set it when the
# caller has not, so an override always wins.
if [[ -z "${LIBCLANG_PATH:-}" ]]; then
  for candidate in \
    "/Library/Developer/CommandLineTools/usr/lib" \
    "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib" \
    "/usr/lib/llvm-18/lib" \
    "/usr/lib/x86_64-linux-gnu"; do
    if [[ -e "$candidate/libclang.dylib" || -e "$candidate/libclang.so" ]]; then
      export LIBCLANG_PATH="$candidate"
      break
    fi
  done
fi
[[ -n "${LIBCLANG_PATH:-}" ]] \
  || echo "warning: LIBCLANG_PATH unset and no libclang found; bindgen may fail" >&2
echo "libclang: ${LIBCLANG_PATH:-<unset>}"

# --- Android SDK / NDK -------------------------------------------------------

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f "$ROOT_DIR/local.properties" ]]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tail -1)"
fi
[[ -n "$SDK_DIR" ]] || die "Android SDK location unknown (set ANDROID_HOME or sdk.dir)"
export ANDROID_HOME="$SDK_DIR"

NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_DIR" ]]; then
  # Prefer r29 (verified); otherwise the newest NDK installed.
  if [[ -d "$SDK_DIR/ndk/android-ndk-r29" ]]; then
    NDK_DIR="$SDK_DIR/ndk/android-ndk-r29"
  else
    NDK_DIR="$(ls -d "$SDK_DIR"/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)"
    NDK_DIR="${NDK_DIR%/}"
  fi
fi
[[ -n "$NDK_DIR" && -d "$NDK_DIR" ]] || die "Android NDK not found under $SDK_DIR/ndk"
export ANDROID_NDK_HOME="$NDK_DIR"

# The LLVM prebuilt directory is named for the host (darwin-x86_64 even on
# Apple Silicon), so discover it instead of assuming.
NDK_PREBUILT=""
for host in "$NDK_DIR"/toolchains/llvm/prebuilt/*/; do
  if [[ -x "${host}bin/aarch64-linux-android${ANDROID_API}-clang" ]]; then
    NDK_PREBUILT="${host%/}"
    break
  fi
done
[[ -n "$NDK_PREBUILT" ]] || die "no usable LLVM prebuilt toolchain under $NDK_DIR/toolchains/llvm/prebuilt"
NDK_BIN="$NDK_PREBUILT/bin"
NDK_SYSROOT="$NDK_PREBUILT/sysroot"
echo "NDK:      $NDK_DIR"

# --- ABIs --------------------------------------------------------------------

ANDROID_ABIS_RAW="${SPOTIFLAC_RUST_ANDROID_ABIS-arm64-v8a,armeabi-v7a}"
case "$ANDROID_ABIS_RAW" in
  arm64-v8a|armeabi-v7a|arm64-v8a,armeabi-v7a|armeabi-v7a,arm64-v8a) ;;
  *) die "SPOTIFLAC_RUST_ANDROID_ABIS must be arm64-v8a and/or armeabi-v7a, comma-separated" ;;
esac
IFS=',' read -r -a ANDROID_ABIS <<< "$ANDROID_ABIS_RAW"

abi_target() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7-linux-androideabi ;;
    *) die "unsupported ABI: $1" ;;
  esac
}
abi_clang_prefix() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7a-linux-androideabi ;;
    *) die "unsupported ABI: $1" ;;
  esac
}

TARGETS=()
for abi in "${ANDROID_ABIS[@]}"; do
  target="$(abi_target "$abi")"
  linker="$NDK_BIN/$(abi_clang_prefix "$abi")${ANDROID_API}-clang"
  [[ -x "$linker" ]] || die "Android API $ANDROID_API linker not found: $linker"
  TARGETS+=("$target")
done

if [[ "$HAVE_RUSTUP" == "1" ]]; then
  # Run inside rust_backend so rust-toolchain.toml selects the pinned 1.98.1.
  (cd "$RUST_BACKEND_DIR" && rustup target add "${TARGETS[@]}")
fi

# --- host library + UniFFI Kotlin bindings -----------------------------------

case "$(uname -s)" in
  Darwin)
    HOST_LIB_NAME="libspotiflac_mobile.dylib"
    # Keep cc-rs (QuickJS) compatible with the host linker.
    export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-11.0}"
    ;;
  Linux) HOST_LIB_NAME="libspotiflac_mobile.so" ;;
  *) die "use macOS or Linux for this build" ;;
esac
HOST_LIBRARY="$CARGO_TARGET_DIR/release/$HOST_LIB_NAME"
BINDINGS_DIR="$CARGO_TARGET_DIR/bindings/kotlin"

# Build the library separately from bindgen so CLI/cargo-metadata features are not
# unified into the shipped library (mirrors upstream's script).
echo "Building host library (spotiflac-mobile)…"
(cd "$RUST_BACKEND_DIR" && cargo build --locked --release -p spotiflac-mobile)
[[ -f "$HOST_LIBRARY" ]] || die "host build did not produce $HOST_LIBRARY"

echo "Generating UniFFI Kotlin bindings…"
(cd "$RUST_BACKEND_DIR" && cargo run --locked -p spotiflac-bindgen -- generate \
  --library "$HOST_LIBRARY" \
  --language kotlin \
  --out-dir "$BINDINGS_DIR" \
  --no-format)
[[ -n "$(find "$BINDINGS_DIR" -name '*.kt' -print -quit 2>/dev/null)" ]] \
  || die "bindgen produced no Kotlin files under $BINDINGS_DIR"

# --- Android cross-builds ----------------------------------------------------

# Align ELF segments for Android devices using 16 KB pages.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

for abi in "${ANDROID_ABIS[@]}"; do
  target="$(abi_target "$abi")"
  linker="$NDK_BIN/$(abi_clang_prefix "$abi")${ANDROID_API}-clang"
  target_env="${target//-/_}"
  # Upper-case via tr, not ${var^^}: macOS ships bash 3.2 which lacks it.
  target_env_upper="$(echo "$target_env" | tr '[:lower:]' '[:upper:]')"

  # Configure the linker plus cc-rs (QuickJS C) and libclang (bindgen) for the
  # same target — the host SDK cannot supply Android headers or ABI layouts.
  export "CARGO_TARGET_${target_env_upper}_LINKER"="$linker"
  export "CC_${target_env}"="$linker"
  export "AR_${target_env}"="$NDK_BIN/llvm-ar"
  export "BINDGEN_EXTRA_CLANG_ARGS_${target_env}"="--sysroot=\"$NDK_SYSROOT\" --target=$(abi_clang_prefix "$abi")${ANDROID_API}"

done

echo "Cross-compiling for: ${ANDROID_ABIS[*]}"
for abi in "${ANDROID_ABIS[@]}"; do
  target="$(abi_target "$abi")"
  (cd "$RUST_BACKEND_DIR" && cargo build --locked --release -p spotiflac-mobile --target "$target")
  built="$CARGO_TARGET_DIR/$target/release/libspotiflac_mobile.so"
  [[ -f "$built" ]] || die "missing Android artifact for $abi: $built"
  mkdir -p "$CARGO_TARGET_DIR/android/jniLibs/$abi"
  cp "$built" "$CARGO_TARGET_DIR/android/jniLibs/$abi/"
  echo "  $abi -> $(du -h "$CARGO_TARGET_DIR/android/jniLibs/$abi/libspotiflac_mobile.so" | cut -f1)"
done

# --- stage for Gradle --------------------------------------------------------

# Our own generated output tree; safe to recreate from scratch. Guard the path so
# a mis-set variable can never point rm at something else.
[[ "$STAGE_ROOT" == "$ROOT_DIR/app/libs/spotiflac-rust" ]] \
  || die "refusing to clear unexpected stage root: $STAGE_ROOT"
rm -rf "$STAGE_ROOT"
mkdir -p "$STAGE_ROOT/jniLibs" "$STAGE_ROOT/kotlin"

for abi in "${ANDROID_ABIS[@]}"; do
  mkdir -p "$STAGE_ROOT/jniLibs/$abi"
  cp "$CARGO_TARGET_DIR/android/jniLibs/$abi/libspotiflac_mobile.so" "$STAGE_ROOT/jniLibs/$abi/"
done
cp -R "$BINDINGS_DIR/." "$STAGE_ROOT/kotlin/"

# Record which revision these artifacts came from, and the hash of each one.
#
# Nothing can recover this from the binaries afterwards: a Rust build is not reproducible bit for
# bit (paths, build ids, timestamps), so "is this stage still the pinned backend?" cannot be
# answered by rebuilding and diffing. Writing it down at the one moment both facts are known - here,
# where the checkout was already asserted against $SPOTIFLAC_RUST_SHA - is what lets the
# verifySpotiFLACRustStage Gradle task refuse a stage left behind by an earlier pin. The rm -rf
# above is what makes the stamp trustworthy rather than merely present: a rebuild cannot leave an
# old stamp or an old artifact beside a new one.
#
# The hashes are not a signature. Someone who edits an artifact *and* its line here defeats them;
# the failure this exists for is the accidental one - bumping a pin, or hand-swapping a .so, and
# shipping the artifacts the last build happened to leave in a gitignored directory.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

PROVENANCE="$STAGE_ROOT/PROVENANCE"
{
  echo "# Written by scripts/build-spotiflac-rust.sh. Read by the verifySpotiFLACRustStage Gradle task."
  echo "revision=$SPOTIFLAC_RUST_TAG"
  echo "commit=$SPOTIFLAC_RUST_SHA"
  echo "android_api=$SPOTIFLAC_ANDROID_API"
  echo "abis=$ANDROID_ABIS_RAW"
  echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$PROVENANCE"
# Every staged file, including the Kotlin bindings: a stage whose binding file was regenerated
# while its .so was not is exactly the kind of half-updated tree this has to catch.
while IFS= read -r rel; do
  printf 'artifact=%s sha256=%s\n' "$rel" "$(sha256_of "$STAGE_ROOT/$rel")"
done < <(cd "$STAGE_ROOT" && find jniLibs kotlin -type f | LC_ALL=C sort) >> "$PROVENANCE"

echo
echo "Staged Rust backend for Gradle:"
for abi in "${ANDROID_ABIS[@]}"; do
  echo "  $STAGE_ROOT/jniLibs/$abi/libspotiflac_mobile.so ($(du -h "$STAGE_ROOT/jniLibs/$abi/libspotiflac_mobile.so" | cut -f1))"
done
find "$STAGE_ROOT/kotlin" -name '*.kt' -print | sed "s|^|  |"
echo "  $PROVENANCE ($(grep -c '^artifact=' "$PROVENANCE") artifact hashes, revision $SPOTIFLAC_RUST_TAG)"
echo "Done (source $SPOTIFLAC_RUST_TAG @ $SHA_SHORT)."
