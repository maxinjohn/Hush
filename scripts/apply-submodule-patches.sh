#!/usr/bin/env bash
# Apply Hush-only patches on top of the upstream core submodule checkout.
# CI runs this after checkout; Gradle also runs it automatically before compile when needed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

apply_patch() {
  local patch="$1"
  if [ ! -f "$patch" ]; then
    echo "Missing patch: $patch"
    exit 1
  fi
  if git -C core apply --check "$patch" >/dev/null 2>&1; then
    echo "Applying $(basename "$patch") to core..."
    git -C core apply "$patch"
  else
    echo "Skipping $(basename "$patch") (already applied or upstream includes it)."
  fi
}

[ -e core/.git ] || git -C core rev-parse HEAD >/dev/null 2>&1 || {
  echo "core submodule is not initialized. Run: git submodule update --init --recursive"
  exit 1
}

apply_patch "$ROOT_DIR/patches/core-innertube-ip-version.patch"
