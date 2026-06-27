#!/usr/bin/env bash
# Mobile release build wrapper (see build-release.sh for all variants).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DISTRIBUTION="${1:-foss}"
ABI="${2:-arm64}"

if [ "$DISTRIBUTION" = "arm64" ] || [ "$DISTRIBUTION" = "universal" ] || [ "$DISTRIBUTION" = "armeabi" ] || [ "$DISTRIBUTION" = "x86" ] || [ "$DISTRIBUTION" = "x86_64" ] || [ "$DISTRIBUTION" = "all" ]; then
  exec "$SCRIPT_DIR/build-release.sh" foss mobile "$DISTRIBUTION"
fi

exec "$SCRIPT_DIR/build-release.sh" "$DISTRIBUTION" mobile "$ABI"
