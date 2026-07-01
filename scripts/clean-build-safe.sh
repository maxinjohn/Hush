#!/usr/bin/env bash
# Remove build outputs without hanging on macOS duplicate folders ("name 2").
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

./gradlew --stop 2>/dev/null || true

remove_path() {
  local path="$1"
  if [ -e "$path" ]; then
    echo "Removing $path"
    rm -rf "$path" || true
  fi
}

# Finder / interrupted builds sometimes leave "folder 2" copies that hang rm/clean forever.
while IFS= read -r dup; do
  remove_path "$dup"
done < <(find app/build .gradle/configuration-cache -type d -name '* 2' 2>/dev/null || true)

# Old-package KSP backup trees from pre-rename builds can loop on delete.
while IFS= read -r stale; do
  remove_path "$stale"
done < <(find app/build/kspCaches -type d -path '*/backups/kotlin/moe' 2>/dev/null || true)

remove_path app/build

echo "Build output cleared."
